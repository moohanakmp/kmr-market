"""Opt-in Octa DEMO executor. Keep the separate market-data bridge running."""
import argparse
from collections import Counter
from datetime import datetime, timezone, timedelta
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR
import getpass
import json
import math
import os
from pathlib import Path
import sys
import time
import uuid
from urllib.parse import urlparse
import MetaTrader5 as mt5
import requests
from mt5_bridge import ServerClock, checked_quote, broker_offset_hours

ACCOUNT = 0  # set explicitly at startup; never embed a user's account in source
SERVER = "OctaFX-Demo"
VOLUME = 0.01
LIMIT = 5
MAGIC = 490011
MODE = "DEMO_ONLY_V2"
FRAMES = ["M1", "M5", "M15", "M30", "H1", "D1", "W1", "MN1"]

def epoch(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()

def rounded(value, step, up):
    unit = Decimal(str(step))
    return float((Decimal(str(value)) / unit).to_integral_value(rounding=ROUND_CEILING if up else ROUND_FLOOR) * unit)

def account_guard(broker):
    account, terminal = broker.account_info(), broker.terminal_info()
    if account is None or terminal is None:
        raise RuntimeError("MT5 account/terminal unavailable.")
    if account.login != ACCOUNT or account.server != SERVER or account.trade_mode != broker.ACCOUNT_TRADE_MODE_DEMO:
        raise RuntimeError(f"Execution is bound to DEMO {ACCOUNT} on {SERVER}; real and other accounts are rejected.")
    if account.currency != "USD" or account.margin_mode != broker.ACCOUNT_MARGIN_MODE_RETAIL_HEDGING:
        raise RuntimeError("This executor requires a USD hedging demo account.")
    if not account.trade_allowed or not account.trade_expert or not terminal.trade_allowed or terminal.tradeapi_disabled:
        raise RuntimeError("Enable MT5 Algo Trading and allow external Python trading in Tools > Options > Expert Advisors.")
    return account

def snapshot(broker):
    # Orders first: an intervening fill can conservatively count twice, not miss
    # an order that becomes a position while these two snapshots are read.
    orders, positions = broker.orders_get(), broker.positions_get()
    if orders is None or positions is None:
        raise RuntimeError("Broker inventory unavailable; no new submissions.")
    return list(orders), list(positions)

def capacity(symbol, orders, positions):
    return sum(o.symbol == symbol for o in orders) < LIMIT and sum(p.symbol == symbol for p in positions) < LIMIT

def targets(signal):
    levels = signal.get('targets') or [signal['tp1'], signal['tp2']]
    values = [float(x) for x in levels]
    buy = signal['direction'] == 'BUY'
    prior = float(signal['entry'])
    for value in values:
        if not math.isfinite(value) or value<=0 or (value<=prior if buy else value>=prior):
            raise ValueError('Targets must be positive and strictly ordered beyond entry.')
        prior=value
    return values

def reached_target(signal, price, previous=0):
    buy=signal['direction']=='BUY'
    reached=[i+1 for i,p in enumerate(targets(signal)) if (price>=p if buy else price<=p)]
    return max([previous]+reached)

def pending_request(broker, signal, symbol, clock, offset):
    if signal["timeframe"] not in FRAMES or signal["status"] != "PENDING" or signal["trackingGap"] or signal.get("cancelRequested"):
        raise ValueError("Only enabled, untriggered signals without quote gaps are eligible.")
    if epoch(signal["expiresAt"]) <= clock.now() or not -30 <= clock.now()-epoch(signal["lastQuoteAt"]) <= 90:
        raise ValueError("Signal expired or feed quote is stale.")
    if not broker.symbol_select(symbol, True):
        raise ValueError("Broker symbol is unavailable; configure the exact symbol mapping.")
    info = broker.symbol_info(symbol)
    quote = checked_quote(broker.symbol_info_tick(symbol), clock, offset)
    if info is None or not info.volume_min <= VOLUME <= info.volume_max or info.volume_step <= 0 or abs(VOLUME/info.volume_step-round(VOLUME/info.volume_step)) > 1e-7:
        raise ValueError("Broker does not support exactly 0.01 lots for this instrument.")
    if not info.expiration_mode & 4:
        raise ValueError("Broker lacks specified pending-order expiry; no GTC fallback is submitted.")
    tick = info.trade_tick_size or info.point
    if tick <= 0:
        raise ValueError("Invalid broker tick size.")
    buy = signal["direction"] == "BUY"
    entry = rounded(signal["entry"], tick, buy)
    stop = rounded(signal["stopLoss"], tick, not buy)
    levels=targets(signal)
    target = rounded(levels[-1], tick, buy)
    manual=signal.get('strategy')=='MANUAL_PENDING_V1'
    limit=signal.get('pendingKind','STOP')=='LIMIT'
    if manual and any(abs(float(v)/tick-round(float(v)/tick))>1e-6 for v in [signal['entry'],signal['stopLoss']]+levels):
        raise ValueError('Manual entry, SL and targets must match the broker tick size; no silent price rounding.')
    distance = max(info.trade_stops_level*info.point, tick)
    atr = abs(float(signal["entry"])-float(signal["stopLoss"]))/1.5
    if not manual and quote["ask"]-quote["bid"] > atr*0.15:
        raise ValueError("Current broker spread exceeds the signal ATR filter.")
    if buy and quote["bid"] <= stop or not buy and quote["ask"] >= stop:
        raise ValueError("Current quote invalidated the setup before entry.")
    if buy:
        valid = stop < entry < target and (quote["ask"]-entry if limit else entry-quote["ask"]) >= distance and entry-stop >= distance and target-entry >= distance
    else:
        valid = target < entry < stop and (entry-quote["bid"] if limit else quote["bid"]-entry) >= distance and stop-entry >= distance and entry-target >= distance
    if not valid:
        raise ValueError("Entry has passed or levels violate broker stop distance; no market-order fallback.")
    return dict(action=broker.TRADE_ACTION_PENDING, symbol=symbol, volume=VOLUME,
                type=(broker.ORDER_TYPE_BUY_LIMIT if buy else broker.ORDER_TYPE_SELL_LIMIT) if limit else (broker.ORDER_TYPE_BUY_STOP if buy else broker.ORDER_TYPE_SELL_STOP),
                price=entry, sl=stop, tp=target, magic=MAGIC, comment=f"KMR:{signal['id']}",
                type_filling=broker.ORDER_FILLING_RETURN, type_time=broker.ORDER_TIME_SPECIFIED,
                expiration=int(epoch(signal["expiresAt"])+offset))

def protection_stop(broker, signal, position, tick, info, previous=0, respect_current=True):
    """Return a tighter broker-valid SL, or None; TP remains at TP2."""
    buy = position.type == broker.POSITION_TYPE_BUY
    if buy != (signal["direction"] == "BUY"):
        return None
    exit_price = tick.bid if buy else tick.ask
    stage=reached_target(signal,exit_price,previous)
    if stage<1:
        return None
    size = info.trade_tick_size or info.point
    distance = max(info.trade_stops_level*info.point, info.trade_freeze_level*info.point, size)
    boundary = rounded(exit_price-distance if buy else exit_price+distance, size, not buy)
    action = broker.ORDER_TYPE_BUY if buy else broker.ORDER_TYPE_SELL
    def profit(price):
        result = broker.order_calc_profit(action, position.symbol, position.volume, position.price_open, price)
        if result is None or not math.isfinite(result):
            raise RuntimeError("Broker profit calculation unavailable; SL unchanged.")
        return result
    history=broker.history_deals_get(position=getattr(position,'identifier',position.ticket if hasattr(position,'ticket') else 0))
    if history is None:
        raise RuntimeError('Entry cost history unavailable; SL unchanged.')
    known_cost=-sum(float(getattr(d,'commission',0))+float(getattr(d,'fee',0)) for d in history)
    cushion = 0.1 + max(0, -float(getattr(position, "swap", 0)))+max(0,known_cost)
    if profit(boundary) < cushion:
        return None
    # Calculate the price needed for a $0.10 gross cushion in this USD account.
    low, high = 0.0, abs(boundary-position.price_open)
    sign = 1 if buy else -1
    for _ in range(40):
        middle = (low+high)/2
        if profit(position.price_open+sign*middle) >= cushion:
            high = middle
        else:
            low = middle
    movement = max(high, tick.ask-tick.bid)
    candidate = rounded(position.price_open+sign*movement, size, buy)
    # Final target is the broker TP. Each intermediate target after TP1
    # protects the prior target, including jumps across several milestones.
    if stage>=2:
        prior=targets(signal)[min(stage-2,len(targets(signal))-2)]
        candidate=max(candidate,prior) if buy else min(candidate,prior)
        candidate=rounded(candidate,size,buy)
    if (candidate > boundary) if buy else (candidate < boundary):
        return None
    if profit(candidate) < cushion:
        return None
    if respect_current and position.sl and ((candidate <= position.sl+size/2) if buy else (candidate >= position.sl-size/2)):
        return None
    return candidate

class ApiError(RuntimeError):
    def __init__(self, status, message):
        super().__init__(f"KMR execution API {status}: {message}")
        self.status = status

class Api:
    def __init__(self, base, password):
        self.base = base
        self.session = requests.Session()
        self.session.auth = ("mt5_feed", password)
    def call(self, path, payload=None):
        result = self.session.request("GET" if payload is None else "POST", self.base+"/api/feed/execution/"+path,
                                      json=payload, timeout=(5, 10), allow_redirects=False)
        if not result.ok:
            try: message = result.json().get('message','')[:180]
            except ValueError: message = 'Response unavailable'
            raise ApiError(result.status_code,message)
        return result.json()

class Executor:
    def __init__(self, broker, api, clock, offset, mapping):
        self.broker, self.api, self.clock, self.offset, self.mapping = broker, api, clock, offset, mapping
        self.worker = str(uuid.uuid4())
        self.seen = set()
        self.messages = {}
        self.last_sync = -float("inf")
        self.deal_cache = {}
        self.force_history = False
    def log(self, key, message):
        now = time.monotonic()
        if key not in self.messages or now-self.messages[key] >= 30:
            print(message, flush=True)
            self.messages[key] = now
            return True
        return False
    def lease(self):
        account_guard(self.broker)
        cfg = self.api.call("config")
        if cfg.get('account')!=ACCOUNT or cfg.get('server')!=SERVER or cfg.get('mode')!=MODE:
            raise RuntimeError("Render account binding changed or is unset; broker writes paused.")
        self.api.call("lease", {"worker": self.worker})
    def report(self, signal_id, status, message, order=None, position=None, sl=None, tp=None, **details):
        return self.api.call("report", dict(worker=self.worker, signalId=signal_id, status=status,
                                            orderTicket=order, positionTicket=position, message=message[:255],
                                            brokerStopLoss=sl if sl is not None and sl>=0 else None,brokerTakeProfit=tp if tp is not None and tp>=0 else None, **details))
    def write(self, request):
        self.lease()  # renew and check account again immediately before any write
        account_guard(self.broker)
        return self.broker.order_send(request)
    def reconcile(self, tracked, orders, positions):
        b = self.broker
        for row in tracked:
            e, s = row["execution"], row["signal"]
            sid, token = s["id"], f"KMR:{s['id']}"
            self.seen.add(sid)
            matches = lambda item: item.magic == MAGIC and (item.comment == token or item.ticket in (e["orderTicket"], e["positionTicket"]))
            pos = next((p for p in positions if matches(p)), None)
            order = next((o for o in orders if matches(o)), None)
            if pos:
                tick=b.symbol_info_tick(pos.symbol)
                details=dict(brokerEntryPrice=pos.price_open,volume=pos.volume,floatingProfit=round(float(getattr(pos,'profit',0))+float(getattr(pos,'swap',0)),8),positionIdentifier=getattr(pos,'identifier',pos.ticket))
                if e['status']!='OPEN' or e['positionTicket']!=pos.ticket or e.get('brokerStopLoss')!=pos.sl or e.get('brokerTakeProfit')!=pos.tp:
                    self.report(sid,'OPEN','Broker position observed',e['orderTicket'],pos.ticket,sl=pos.sl,tp=pos.tp,**details)
                quote=checked_quote(tick,self.clock,self.offset)
                stage=reached_target(s,quote['bid'] if pos.type==b.POSITION_TYPE_BUY else quote['ask'],e.get('reachedTarget',0))
                details['reachedTarget']=stage
                if stage>e.get('reachedTarget',0):
                    self.report(sid,'OPEN',f'Broker quote reached TP{stage}',e['orderTicket'],pos.ticket,sl=pos.sl,tp=pos.tp,**details)
                info=b.symbol_info(pos.symbol)
                stop=protection_stop(b,s,pos,tick,info,stage,respect_current=False)
                if stop is not None and pos.sl and (pos.sl>=stop if pos.type==b.POSITION_TYPE_BUY else pos.sl<=stop):
                    if e.get('protectedTarget',0)<stage:
                        self.report(sid,'OPEN',f'Existing broker SL already protects TP{stage}',e['orderTicket'],pos.ticket,sl=pos.sl,tp=pos.tp,protectedTarget=stage,**details)
                elif stop is not None:
                    result=self.write(dict(action=b.TRADE_ACTION_SLTP,symbol=pos.symbol,position=pos.ticket,sl=stop,tp=pos.tp))
                    if result is not None and result.retcode==b.TRADE_RETCODE_DONE:
                        self.log((sid,'sl',stop),f'Signal {sid}: broker SL moved to {stop}; final TP unchanged.')
                        self.report(sid,'OPEN',f'TP{stage} protection accepted: SL={stop}',e['orderTicket'],pos.ticket,sl=stop,tp=pos.tp,protectedTarget=stage,**details)
                    else:
                        self.log((sid,'sl-reject'),f'Signal {sid}: SL change not confirmed; broker code {getattr(result,"retcode","NO RESPONSE")}.')
            elif order:
                open_count = sum(p.symbol == order.symbol for p in positions)
                expired = epoch(s["expiresAt"]) <= self.clock.now()
                if expired or s.get("cancelRequested") or s["trackingGap"] or s["status"] not in ("PENDING", "ACTIVE", "TP1", "TP2", "TP3", "TARGET") or open_count >= LIMIT:
                    result = self.write(dict(action=b.TRADE_ACTION_REMOVE, order=order.ticket))
                    if result is not None and result.retcode == b.TRADE_RETCODE_DONE:
                        status='EXPIRED' if expired or s['status']=='EXPIRED' else 'STOPPED' if s['status']=='STOPPED' else 'CANCELLED'
                        self.report(sid,status,'Broker confirms pending removal: expiry, cancellation, invalidation, gap or open-position limit',order.ticket,closeReason=status)
                elif e["status"] != "BROKER_PENDING" or e.get("brokerStopLoss")!=order.sl or e.get("brokerTakeProfit")!=order.tp:
                    self.report(sid, "BROKER_PENDING", "Pending order observed at broker", order.ticket, sl=order.sl, tp=order.tp,brokerEntryPrice=order.price_open,volume=order.volume_current)
            else:
                # Recover accepted-but-unacknowledged requests by deterministic
                # magic/comment and broker history. Never retry a claimed entry.
                start = datetime.fromtimestamp(epoch(e["createdAt"]),timezone.utc)-timedelta(days=1)
                end = datetime.fromtimestamp(self.clock.now()+abs(self.offset),timezone.utc)+timedelta(days=1)
                history = b.history_orders_get(start,end)
                deals = b.history_deals_get(start,end)
                if history is None or deals is None:
                    continue
                historic = next((o for o in history if matches(o)),None)
                if historic and historic.state in (b.ORDER_STATE_CANCELED,b.ORDER_STATE_EXPIRED,b.ORDER_STATE_REJECTED):
                    ended='REJECTED' if historic.state==b.ORDER_STATE_REJECTED else 'EXPIRED' if historic.state==b.ORDER_STATE_EXPIRED else 'STOPPED' if s['status']=='STOPPED' else 'CANCELLED'
                    self.report(sid,ended,'Broker history confirms pending order ended',historic.ticket,closeReason=ended)
                elif historic:
                    opening = [d for d in deals if d.order==historic.ticket and d.entry==b.DEAL_ENTRY_IN]
                    ids = {d.position_id for d in opening}
                    live = b.positions_get()
                    if live is None:
                        continue
                    recovered = next((p for p in live if p.magic==MAGIC and p.identifier in ids),None)
                    if recovered:
                        self.report(sid,"OPEN","Recovered position from broker deal history",historic.ticket,recovered.ticket,sl=recovered.sl,tp=recovered.tp)
                    elif opening and sum(d.volume for d in deals if d.position_id in ids and d.entry in (b.DEAL_ENTRY_OUT,b.DEAL_ENTRY_OUT_BY)) >= sum(d.volume for d in opening)-1e-8:
                        exits=[d for d in deals if d.position_id in ids and d.entry in (b.DEAL_ENTRY_OUT,b.DEAL_ENTRY_OUT_BY)]
                        last=max(exits,key=lambda d:getattr(d,'time_msc',getattr(d,'time',0)))
                        reason='STOP_LOSS' if getattr(last,'reason',None)==getattr(b,'DEAL_REASON_SL',4) else 'FINAL_TARGET' if getattr(last,'reason',None)==getattr(b,'DEAL_REASON_TP',5) else 'BROKER_EXIT'
                        self.report(sid,'STOPPED' if reason=='STOP_LOSS' else 'CLOSED','Broker exit deals confirm full closure',historic.ticket,e['positionTicket'],closeReason=reason,positionIdentifier=next(iter(ids)),brokerEntryPrice=round(sum(getattr(d,'price',0)*d.volume for d in opening)/sum(d.volume for d in opening),8) or None,volume=round(sum(d.volume for d in opening),8),floatingProfit=0)
                    elif e["status"]!="UNKNOWN":
                        self.report(sid,"UNKNOWN","Broker state not yet reconciled; no automatic resend",historic.ticket)
                elif e["status"]!="UNKNOWN":
                    self.report(sid,"UNKNOWN","No broker acknowledgement found; inspect history before any manual retry")
    def sync_ledger(self):
        if time.monotonic()-self.last_sync<30:
            return
        meta=self.api.call('history')
        stamp=self.clock.now()
        start=datetime.fromtimestamp(0 if self.force_history else max(0,epoch(meta['historyCursor'])-86400),timezone.utc)
        end=datetime.fromtimestamp(stamp+abs(self.offset)+86400,timezone.utc)
        all_deals=self.broker.history_deals_get(start,end)
        if all_deals is None:
            raise RuntimeError('Broker deal history unavailable; P&L sync not advanced.')
        by_position={};by_order={}
        for e in meta.get('identities',[]):
            if e.get('positionIdentifier') or e.get('positionTicket'):
                by_position[e.get('positionIdentifier') or e['positionTicket']]=e['signalId']
            if e.get('orderTicket'):
                by_order[e['orderTicket']]=e['signalId']
        for d in all_deals:
            if getattr(d,'magic',0)==MAGIC and getattr(d,'comment','').startswith('KMR:'):
                try:sid=int(d.comment.split(':')[1])
                except (ValueError,IndexError):continue
                if d.position_id:by_position[d.position_id]=sid
                if d.order:by_order[d.order]=sid
        rows=[]
        for d in all_deals:
            if d.type in (2,3,5,6):
                continue  # deposits, credit, balance corrections and bonuses
            row=dict(ticket=d.ticket,orderTicket=d.order,positionIdentifier=d.position_id,signalId=by_position.get(d.position_id,by_order.get(d.order)),
                     tradedAt=datetime.fromtimestamp((d.time_msc/1000 if d.time_msc else d.time)-self.offset,timezone.utc).isoformat(),symbol=d.symbol,
                     dealType=d.type,entryKind=d.entry,reason=d.reason,**{name:round(float(getattr(d,name,0)),8) for name in ('volume','price','profit','commission','swap','fee')})
            fingerprint=json.dumps(row,sort_keys=True)
            if self.deal_cache.get(d.ticket)!=fingerprint:
                rows.append((row,fingerprint))
        account=account_guard(self.broker)
        _,positions=snapshot(self.broker)
        live=[dict(ticket=p.ticket,identifier=p.identifier,symbol=p.symbol,type=p.type,volume=p.volume,priceOpen=p.price_open,currentPrice=p.price_current,
                   sl=p.sl,tp=p.tp,profit=p.profit,swap=p.swap) for p in positions]
        batches=[rows[i:i+200] for i in range(0,len(rows),200)] or [[]]
        for i,batch in enumerate(batches):
            self.lease()
            now=datetime.fromtimestamp(self.clock.now(),timezone.utc).isoformat()
            self.api.call('ledger',dict(worker=self.worker,account=ACCOUNT,server=SERVER,observedAt=now,
                                       historyCursor=datetime.fromtimestamp(stamp,timezone.utc).isoformat() if i==len(batches)-1 else None,
                                       deals=[r for r,f in batch],positions=live,balance=round(account.balance,8),equity=round(account.equity,8)))
            for r,f in batch:self.deal_cache[r['ticket']]=f
        self.last_sync=time.monotonic()
        self.force_history=False
        if rows:
            print(f'P&L ledger: synchronized {len(rows)} new/updated broker deals.',flush=True)

    def cycle(self):
        self.lease()
        rows = self.api.call("tracked")
        orders, positions = snapshot(self.broker)
        for row in rows:
            try:
                self.reconcile([row],orders,positions)
            except Exception as exc:
                self.log((row['signal']['id'],'manage'),f"Position/order management: {exc}")
        self.sync_ledger()
        signals = self.api.call("signals")
        for signal in signals:
            sid = signal["id"]
            if sid in self.seen or signal["status"]!="PENDING" or signal["timeframe"] not in FRAMES:
                continue
            symbol = self.mapping.get(signal["symbol"],signal["symbol"])
            orders, positions = snapshot(self.broker)
            if not capacity(symbol,orders,positions):
                self.log((symbol,"cap"),f"{symbol}: limit reached (5 pending or 5 open); waiting.")
                continue
            try:
                request = pending_request(self.broker,signal,symbol,self.clock,self.offset)
                check = self.broker.order_check(request)
                if check is None or check.retcode != 0:
                    raise ValueError(f"Broker preflight rejected: {getattr(check,'retcode','NO RESPONSE')}")
            except (ValueError,RuntimeError) as exc:
                if self.log((sid,'wait'),f'Signal {sid}: {exc}'):
                    self.api.call('note',dict(worker=self.worker,signalId=sid,message=str(exc)[:255]))
                continue
            # Reserve in PostgreSQL before sending; a lost response is never
            # resolved by blindly submitting the same signal a second time.
            self.lease()
            try:
                self.api.call("claim",dict(worker=self.worker,account=ACCOUNT,server=SERVER,signalId=sid))
            except ApiError as exc:
                if exc.status == 409 and ('already claimed' in str(exc) or 'Signal is disabled' in str(exc)):
                    self.seen.add(sid)
                    continue
                raise
            self.seen.add(sid)
            try:
                self.lease()
                orders,positions = snapshot(self.broker)
                if not capacity(symbol,orders,positions):
                    self.report(sid,"REJECTED","Capacity changed before submission; no broker order sent")
                    continue
                request = pending_request(self.broker,signal,symbol,self.clock,self.offset)
                account_guard(self.broker)
                result = self.broker.order_send(request)
                if result is None or result.retcode in (self.broker.TRADE_RETCODE_TIMEOUT,self.broker.TRADE_RETCODE_CONNECTION):
                    self.report(sid,"UNKNOWN","Broker reply uncertain; reconcile history, never resend")
                elif result.retcode in (self.broker.TRADE_RETCODE_DONE,self.broker.TRADE_RETCODE_PLACED):
                    self.report(sid,"BROKER_PENDING",f"Submitted 0.01 lots with SL and final TP; code {result.retcode}",result.order,sl=request['sl'],tp=request['tp'],brokerEntryPrice=request['price'],volume=request['volume'])
                    print(f"Signal {sid}: MT5 order {result.order} submitted for {symbol} at 0.01 lots.",flush=True)
                else:
                    self.report(sid,"REJECTED",f"Broker rejected entry, code {result.retcode}")
            except Exception as exc:
                self.log((sid,"uncertain"),f"Signal {sid}: submission outcome requires reconciliation: {exc}")

def main(args):
    global ACCOUNT, SERVER
    if args.account <= 0:
        raise ValueError("Specify your demo account number.")
    ACCOUNT, SERVER = args.account, args.server
    if not args.execute_demo:
        raise ValueError("Pass --execute-demo to explicitly enable demo broker orders.")
    base = args.url.rstrip('/')
    parts = urlparse(base)
    if parts.scheme!='https' or parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError("Use the plain public HTTPS KMR URL without credentials.")
    # One local worker; the backend lease also coordinates separate computers.
    import msvcrt
    directory = Path(os.environ['LOCALAPPDATA'])/'KMR'
    directory.mkdir(parents=True,exist_ok=True)
    lock = open(directory/f'executor-{ACCOUNT}.lock','a+b')
    lock.seek(0);lock.write(b'1');lock.flush();lock.seek(0)
    msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
    if not (mt5.initialize(args.terminal) if args.terminal else mt5.initialize()):
        raise RuntimeError("MT5 initialization failed; open the desktop terminal.")
    worker = None
    try:
        account_guard(mt5)
        password = os.environ.get('MT5_FEED_PASSWORD') or getpass.getpass('KMR MT5 feed password: ')
        api = Api(base,password)
        cfg = api.call('config')
        if cfg != dict(mode=MODE,account=ACCOUNT,server=SERVER,volume=VOLUME,maxPendingPerSymbol=LIMIT,maxOpenPerSymbol=LIMIT,takeProfit='FINAL_TARGET',profitCushionUsd=0.1,timeframes=FRAMES):
            raise RuntimeError('Execution configuration does not match the approved demo policy.')
        clock = ServerClock();clock.sync(api.session,base)
        mapping = json.load(open(args.symbol_map,encoding='utf-8')) if args.symbol_map else {}
        worker = Executor(mt5,api,clock,args.broker_utc_offset_hours*3600,mapping)
        worker.force_history=args.resync_history
        print(f'DEMO execution enabled for {ACCOUNT}: 0.01 lots, 5 pending + 5 open per commodity, final-target TP.',flush=True)
        refreshed = time.monotonic()
        while True:
            try:
                if time.monotonic()-refreshed >= 60:
                    clock.sync(api.session,base);refreshed=time.monotonic()
                worker.cycle()
            except Exception as exc:
                worker.log('cycle',f'Executor paused/retrying: {exc}')
            time.sleep(2)
    finally:
        if worker:
            try: worker.api.call('release',dict(worker=worker.worker))
            except Exception: pass
        mt5.shutdown()
        lock.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',required=True)
    parser.add_argument('--execute-demo',action='store_true')
    parser.add_argument('--account',required=True,type=int)
    parser.add_argument('--server',default='OctaFX-Demo')
    parser.add_argument('--resync-history',action='store_true',help='Backfill all broker-retained history once, including older corrections.')
    parser.add_argument('--terminal')
    parser.add_argument('--symbol-map')
    parser.add_argument('--broker-utc-offset-hours',type=broker_offset_hours,default=0)
    try: main(parser.parse_args())
    except KeyboardInterrupt: print('Executor stopped. Broker SL/final TP remain; intermediate-target SL management needs this worker running.')
    except Exception as exc: print(str(exc),file=sys.stderr);sys.exit(1)
