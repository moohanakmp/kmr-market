"""Execution tests use an offline fake broker, never an MT5 account."""
import copy
import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace as N
import unittest
from unittest.mock import patch

with patch.dict(sys.modules, {"MetaTrader5": N(), "requests": N()}):
    spec=importlib.util.spec_from_file_location('executor_tested',Path(__file__).with_name('mt5_executor.py'))
    ex=importlib.util.module_from_spec(spec);spec.loader.exec_module(ex)

NOW=1800000000
ex.ACCOUNT=123456  # synthetic demo identity, not a user's account
clock=N(now=lambda:NOW)
def iso(t): return ex.datetime.fromtimestamp(t,ex.timezone.utc).isoformat().replace('+00:00','Z')
def signal(buy=True):
    return dict(id=7,symbol='XAUUSD',timeframe='M1',status='PENDING',trackingGap=False,
                direction='BUY' if buy else 'SELL',entry=100,stopLoss=90 if buy else 110,
                tp1=110 if buy else 90,tp2=120 if buy else 80,
                createdAt=iso(NOW-5),expiresAt=iso(NOW+180),lastQuoteAt=iso(NOW-1))

class Broker:
    ACCOUNT_TRADE_MODE_DEMO=0;ACCOUNT_MARGIN_MODE_RETAIL_HEDGING=2
    TRADE_ACTION_DEAL=1;TRADE_ACTION_PENDING=5;TRADE_ACTION_SLTP=6;TRADE_ACTION_REMOVE=8
    ORDER_TYPE_BUY=0;ORDER_TYPE_SELL=1;ORDER_TYPE_BUY_STOP=4;ORDER_TYPE_SELL_STOP=5;ORDER_TYPE_BUY_LIMIT=2;ORDER_TYPE_SELL_LIMIT=3
    POSITION_TYPE_BUY=0;POSITION_TYPE_SELL=1;ORDER_FILLING_RETURN=2;ORDER_FILLING_IOC=1;ORDER_FILLING_FOK=0;ORDER_TIME_SPECIFIED=2;ORDER_TIME_GTC=0;SYMBOL_TRADE_EXECUTION_MARKET=2
    TRADE_RETCODE_DONE=10009;TRADE_RETCODE_PLACED=10008;TRADE_RETCODE_TIMEOUT=10012;TRADE_RETCODE_CONNECTION=10031
    ORDER_STATE_CANCELED=2;ORDER_STATE_EXPIRED=6;ORDER_STATE_REJECTED=5
    DEAL_ENTRY_IN=0;DEAL_ENTRY_OUT=1;DEAL_ENTRY_OUT_BY=3
    def __init__(self):
        self.account=N(login=ex.ACCOUNT,server=ex.SERVER,trade_mode=0,currency='USD',margin_mode=2,trade_allowed=True,trade_expert=True,balance=10000,equity=10000)
        self.terminal=N(trade_allowed=True,tradeapi_disabled=False)
        self.info=N(volume_min=.01,volume_max=10,volume_step=.01,expiration_mode=4,trade_tick_size=.01,point=.01,trade_stops_level=2,trade_freeze_level=2)
        self.tick=N(time=NOW+10800-1,bid=98.9,ask=99.1)
        self.orders=[];self.positions=[];self.sent=[];self.reply=N(retcode=self.TRADE_RETCODE_PLACED,order=900)
        self.modify_reply=N(retcode=self.TRADE_RETCODE_DONE,order=900)
    def account_info(self):return self.account
    def terminal_info(self):return self.terminal
    def orders_get(self):return self.orders
    def positions_get(self):return self.positions
    def symbol_select(self,*a):return True
    def symbol_info(self,*a):return self.info
    def symbol_info_tick(self,*a):return self.tick
    def order_calc_profit(self,side,symbol,volume,entry,close):return (close-entry)*(1 if side==0 else -1)*volume*100
    def order_check(self,request):return N(retcode=0)
    def order_send(self,request):
        self.sent.append(request)
        return self.modify_reply if request['action']!=self.TRADE_ACTION_PENDING else self.reply
    def history_orders_get(self,*a):return []
    def history_deals_get(self,*a,**kw):return []

class Api:
    def __init__(self,s):self.s=s;self.records={};self.calls=[]
    def call(self,path,payload=None):
        self.calls.append((path,payload))
        if path=='config':return dict(account=ex.ACCOUNT,server=ex.SERVER,mode=ex.MODE)
        if path=='lease':return {}
        if path=='note':return {}
        if path=='signals':return [self.s]
        if path=='tracked':return [dict(execution=e,signal=self.s) for e in self.records.values() if e['status'] not in ('CLOSED','STOPPED','EXPIRED','CANCELLED','REJECTED')]
        if path=='claim':
            sid=payload['signalId']
            if sid in self.records:raise ex.ApiError(409,'Signal already claimed')
            self.records[sid]=dict(signalId=sid,status='CLAIMED',orderTicket=None,positionTicket=None,createdAt=iso(NOW))
            return self.records[sid]
        if path=='report':
            e=self.records[payload['signalId']]
            e.update(status=payload['status'])
            for field in ('brokerStopLoss','brokerTakeProfit','brokerEntryPrice','volume','reachedTarget','protectedTarget','positionIdentifier'):
                if payload.get(field) is not None:e[field]=payload[field]
            if payload['orderTicket']:e['orderTicket']=payload['orderTicket']
            if payload['positionTicket']:e['positionTicket']=payload['positionTicket']
            return e
        raise AssertionError(path)

class ExecutionTest(unittest.TestCase):
    def setUp(self):self.b=Broker();self.s=signal();self.api=Api(self.s)
    def engine(self):
        e=ex.Executor(self.b,self.api,clock,10800,{})
        e.last_sync=ex.time.monotonic()  # ledger tested separately below
        return e
    def test_bound_demo_account_only(self):
        for field,value in [('login',1),('server','Other'),('trade_mode',2),('currency','EUR'),('margin_mode',0)]:
            b=Broker();setattr(b.account,field,value)
            with self.assertRaises(RuntimeError):ex.account_guard(b)
            self.assertEqual(b.sent,[])
    def test_public_market_signal_uses_actual_broker_quote(self):
        self.s.update(strategy='KMR_V28_PUBLIC',pendingKind='MARKET',sourceAtr=5)
        r=ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)
        self.assertEqual(r['action'],self.b.TRADE_ACTION_DEAL);self.assertEqual(r['price'],99.1)
        self.assertEqual(r['volume'],.01);self.assertEqual(r['sl'],90);self.assertEqual(r['tp'],120)
        self.assertNotIn('expiration',r)
    def test_market_partial_fill_is_reconciled_never_treated_as_rejected(self):
        self.s.update(strategy='KMR_V28_PUBLIC',pendingKind='MARKET',sourceAtr=5)
        self.b.modify_reply=N(retcode=10010,order=900)
        engine=self.engine();engine.cycle();engine.cycle()
        self.assertEqual(self.api.records[7]['status'],'UNKNOWN')
        self.assertEqual(sum(r['action']==self.b.TRADE_ACTION_DEAL for r in self.b.sent),1)
    def test_public_market_signal_rejects_broker_reference_gap(self):
        self.s.update(strategy='KMR_V28_PUBLIC',pendingKind='MARKET',sourceAtr=1)
        with self.assertRaises(ValueError):ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)
        self.assertEqual(self.b.sent,[])
    def test_explicit_market_order_does_not_require_pending_expiry_feature(self):
        self.s.update(strategy='KMR_V28_PUBLIC',pendingKind='MARKET',sourceAtr=5);self.b.info.expiration_mode=0
        self.assertEqual(ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)['action'],self.b.TRADE_ACTION_DEAL)
    def test_low_profit_execution_requires_confirmed_fees(self):
        self.s.update(strategy='KMR_V28_SCALP',pendingKind='MARKET',sourceAtr=5)
        with patch.dict(ex.os.environ,{},clear=True):
            with self.assertRaises(ValueError):ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)
    def test_low_profit_actual_contract_loss_cannot_exceed_four_usd(self):
        self.s.update(strategy='KMR_V28_SCALP',pendingKind='MARKET',sourceAtr=5)
        with patch.dict(ex.os.environ,{'MT5_ROUND_TRIP_FEE_PER_LOT_USD':'0'}):
            with self.assertRaises(ValueError):ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)
    def test_disabled_python_trading_blocks_write(self):
        self.b.terminal.tradeapi_disabled=True
        with self.assertRaises(RuntimeError):self.engine().cycle()
        self.assertEqual(self.b.sent,[])
    def test_separate_limits_per_commodity(self):
        orders=[N(symbol='XAUUSD') for _ in range(5)]
        self.assertFalse(ex.capacity('XAUUSD',orders,[]))
        self.assertTrue(ex.capacity('BTCUSD',orders,[]))
        positions=[N(symbol='XAUUSD') for _ in range(5)]
        self.assertFalse(ex.capacity('XAUUSD',[],positions))
        self.assertTrue(ex.capacity('BTCUSD',[],positions))
        self.assertTrue(ex.capacity('XAUUSD',orders[:4],positions[:4]))
    def test_pending_has_exact_volume_sl_tp2_and_expiry(self):
        r=ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)
        self.assertEqual(r['volume'],.01);self.assertEqual(r['tp'],120);self.assertEqual(r['sl'],90)
        self.assertEqual(r['expiration'],NOW+180+10800)
        self.assertEqual(r['type'],self.b.ORDER_TYPE_BUY_STOP)
    def test_sell_stop_uses_bid_and_correct_levels(self):
        self.b.tick=N(time=NOW+10800,bid=100.9,ask=101.1)
        r=ex.pending_request(self.b,signal(False),'XAUUSD',clock,10800)
        self.assertEqual(r['type'],self.b.ORDER_TYPE_SELL_STOP);self.assertEqual(r['tp'],80)
    def test_disabled_frames_stale_gap_and_crossed_entries_rejected(self):
        for update in [dict(timeframe='H2'),dict(trackingGap=True),dict(expiresAt=iso(NOW-1)),dict(lastQuoteAt=iso(NOW-100))]:
            with self.assertRaises(ValueError):ex.pending_request(self.b,dict(self.s,**update),'XAUUSD',clock,10800)
        self.b.tick.ask=100.1
        with self.assertRaises(ValueError):ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)
    def test_no_volume_increase_or_gtc_fallback(self):
        self.b.info.volume_min=.1
        with self.assertRaises(ValueError):ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)
        self.b.info.volume_min=.01;self.b.info.expiration_mode=1
        with self.assertRaises(ValueError):ex.pending_request(self.b,self.s,'XAUUSD',clock,10800)
    def test_tp1_buy_protects_spread_and_gross_cushion(self):
        p=N(type=0,symbol='XAUUSD',volume=.01,price_open=100,sl=90,swap=0)
        tick=N(bid=110,ask=110.2)
        stop=ex.protection_stop(self.b,self.s,p,tick,self.b.info)
        self.assertGreaterEqual(stop,100.2-1e-8)
        self.assertGreaterEqual(self.b.order_calc_profit(0,p.symbol,p.volume,p.price_open,stop),.1)
    def test_tp1_sell_protection_and_no_loosen(self):
        p=N(type=1,symbol='XAUUSD',volume=.01,price_open=100,sl=110,swap=0)
        stop=ex.protection_stop(self.b,signal(False),p,N(bid=89.8,ask=90),self.b.info)
        self.assertLessEqual(stop,99.8+1e-8)
        p.sl=99
        self.assertIsNone(ex.protection_stop(self.b,signal(False),p,N(bid=89.8,ask=90),self.b.info))
    def test_before_tp1_and_invalid_broker_distance_do_not_move_sl(self):
        p=N(type=0,symbol='XAUUSD',volume=.01,price_open=100,sl=90,swap=0)
        self.assertIsNone(ex.protection_stop(self.b,self.s,p,N(bid=109,ask=109.2),self.b.info))
        self.b.info.trade_stops_level=1000
        self.assertIsNone(ex.protection_stop(self.b,self.s,p,N(bid=110,ask=110.2),self.b.info))
    def test_tp2_fallback_locks_tp1(self):
        p=N(type=0,symbol='XAUUSD',volume=.01,price_open=100,sl=100.2,swap=0)
        self.assertEqual(ex.protection_stop(self.b,self.s,p,N(bid=120,ask=120.2),self.b.info),110)
    def test_claim_precedes_send_and_repeat_cycle_does_not_resend(self):
        engine=self.engine();engine.cycle();engine.cycle()
        self.assertEqual(len(self.b.sent),1)
        self.assertEqual(self.api.records[7]['orderTicket'],900)
    def test_uncertain_send_and_restart_never_resubmit(self):
        self.b.reply=None;self.engine().cycle();self.engine().cycle()
        self.assertEqual(len(self.b.sent),1);self.assertEqual(self.api.records[7]['status'],'UNKNOWN')
    def test_rejected_record_after_restart_does_not_block_or_resend(self):
        self.api.records[7]=dict(status='REJECTED',orderTicket=None,positionTicket=None,createdAt=iso(NOW))
        engine=self.engine();engine.cycle();engine.cycle();self.assertEqual(self.b.sent,[])
    def test_unknown_inventory_and_api_lease_prevent_submission(self):
        self.b.orders=None
        with self.assertRaises(RuntimeError):self.engine().cycle()
        self.assertEqual(self.b.sent,[])
        self.b.orders=[]
        with patch.object(self.api,'call',side_effect=RuntimeError('offline')):
            with self.assertRaises(RuntimeError):self.engine().cycle()
        self.assertEqual(self.b.sent,[])
    def test_open_cap_cancels_owned_pending_without_closing_positions(self):
        self.api.records[7]=dict(status='BROKER_PENDING',orderTicket=900,positionTicket=None,createdAt=iso(NOW))
        self.b.orders=[N(symbol='XAUUSD',ticket=900,magic=ex.MAGIC,comment='KMR:7')]
        self.b.positions=[N(symbol='XAUUSD',ticket=i,magic=0,comment='manual') for i in range(5)]
        self.engine().cycle()
        self.assertEqual([r['action'] for r in self.b.sent],[self.b.TRADE_ACTION_REMOVE])
        self.assertEqual(self.api.records[7]['status'],'CANCELLED')
    def test_position_sltp_keeps_broker_tp2(self):
        self.api.records[7]=dict(status='OPEN',orderTicket=900,positionTicket=901,createdAt=iso(NOW))
        self.b.positions=[N(symbol='XAUUSD',ticket=901,magic=ex.MAGIC,comment='KMR:7',type=0,volume=.01,price_open=100,sl=90,tp=120,swap=0)]
        self.b.tick=N(time=NOW+10800,bid=110,ask=110.2)
        self.engine().cycle()
        self.assertEqual(self.b.sent[0]['action'],self.b.TRADE_ACTION_SLTP)
        self.assertEqual(self.b.sent[0]['tp'],120)
    def test_rejected_stop_change_is_not_published_as_success(self):
        self.api.records[7]=dict(status='OPEN',orderTicket=900,positionTicket=901,createdAt=iso(NOW))
        self.b.positions=[N(symbol='XAUUSD',ticket=901,magic=ex.MAGIC,comment='KMR:7',type=0,volume=.01,price_open=100,sl=90,tp=120,swap=0)]
        self.b.tick=N(time=NOW+10800,bid=110,ask=110.2);self.b.modify_reply=N(retcode=10016)
        self.engine().cycle()
        self.assertEqual(self.api.records[7]['brokerStopLoss'],90)
    def test_broker_exit_deals_confirm_closure_not_signal_status(self):
        self.api.records[7]=dict(status='OPEN',orderTicket=900,positionTicket=901,createdAt=iso(NOW))
        self.b.history_orders_get=lambda *a:[N(magic=ex.MAGIC,comment='KMR:7',ticket=900,state=4)]
        self.b.history_deals_get=lambda *a,**kw:[N(order=900,entry=0,position_id=901,volume=.01),N(order=999,entry=1,position_id=901,volume=.01)]
        self.engine().cycle()
        self.assertEqual(self.api.records[7]['status'],'CLOSED')
    def test_partial_exit_without_live_snapshot_remains_unknown(self):
        self.api.records[7]=dict(status='OPEN',orderTicket=900,positionTicket=901,createdAt=iso(NOW))
        self.b.history_orders_get=lambda *a:[N(magic=ex.MAGIC,comment='KMR:7',ticket=900,state=4)]
        self.b.history_deals_get=lambda *a,**kw:[N(order=900,entry=0,position_id=901,volume=.01),N(order=999,entry=1,position_id=901,volume=.005)]
        self.engine().cycle()
        self.assertEqual(self.api.records[7]['status'],'UNKNOWN')

class MultiTargetTest(unittest.TestCase):
    setUp=ExecutionTest.setUp
    engine=ExecutionTest.engine
    def test_three_targets_close_at_final_and_lock_tp1_at_tp2(self):
        s=dict(self.s,targets=[110,120,130]);r=ex.pending_request(self.b,s,'XAUUSD',clock,10800)
        self.assertEqual(r['tp'],130)
        p=N(type=0,symbol='XAUUSD',volume=.01,price_open=100,sl=100.2,swap=0)
        self.assertEqual(ex.protection_stop(self.b,s,p,N(bid=120,ask=120.2),self.b.info),110)
        self.assertEqual(ex.protection_stop(self.b,s,p,N(bid=125,ask=125.2),self.b.info),110)
    def test_four_targets_gap_jump_and_reversal_retain_highest_stage(self):
        s=dict(self.s,targets=[110,120,130,140]);p=N(type=0,symbol='XAUUSD',volume=.01,price_open=100,sl=100.2,swap=0)
        self.assertEqual(ex.protection_stop(self.b,s,p,N(bid=135,ask=135.2),self.b.info),120)
        self.assertEqual(ex.protection_stop(self.b,s,p,N(bid=125,ask=125.2),self.b.info,previous=3),120)
    def test_multi_target_sell_is_symmetric(self):
        s=dict(signal(False),targets=[90,80,70,60]);p=N(type=1,symbol='XAUUSD',volume=.01,price_open=100,sl=99.8,swap=0)
        self.assertEqual(ex.protection_stop(self.b,s,p,N(bid=64.8,ask=65),self.b.info),80)
    def test_known_entry_commission_is_in_cushion(self):
        self.b.history_deals_get=lambda *a,**kw:[N(commission=-.25,fee=-.05)]
        p=N(type=0,symbol='XAUUSD',volume=.01,price_open=100,sl=90,swap=-.1)
        stop=ex.protection_stop(self.b,self.s,p,N(bid=110,ask=110.2),self.b.info)
        self.assertGreaterEqual(stop,100.5-1e-8)
    def test_manual_buy_and_sell_limit(self):
        s=dict(self.s,strategy='MANUAL_PENDING_V1',pendingKind='LIMIT',entry=95,targets=[100,110,120])
        r=ex.pending_request(self.b,s,'XAUUSD',clock,10800);self.assertEqual(r['type'],2);self.assertEqual(r['price'],95)
        s=dict(signal(False),strategy='MANUAL_PENDING_V1',pendingKind='LIMIT',entry=105,targets=[100,90,80])
        r=ex.pending_request(self.b,s,'XAUUSD',clock,10800);self.assertEqual(r['type'],3);self.assertEqual(r['price'],105)
    def test_manual_prices_are_never_silently_rounded(self):
        s=dict(self.s,strategy='MANUAL_PENDING_V1',entry=100.001,targets=[110,120,130])
        with self.assertRaises(ValueError):ex.pending_request(self.b,s,'XAUUSD',clock,10800)
    def test_single_target_and_bad_target_order(self):
        self.assertEqual(ex.pending_request(self.b,dict(self.s,targets=[110]),'XAUUSD',clock,10800)['tp'],110)
        for levels in ([110,105],[110,110],[-10],[float('nan')]):
            with self.assertRaises(ValueError):ex.targets(dict(self.s,targets=levels))
    def test_expired_pending_is_removed_at_broker(self):
        self.s['expiresAt']=iso(NOW-1);self.s['status']='EXPIRED'
        self.api.records[7]=dict(status='BROKER_PENDING',orderTicket=900,positionTicket=None,createdAt=iso(NOW-10))
        self.b.orders=[N(symbol='XAUUSD',ticket=900,magic=ex.MAGIC,comment='KMR:7')]
        self.engine().cycle();self.assertEqual(self.api.records[7]['status'],'EXPIRED');self.assertEqual(self.b.sent[0]['action'],self.b.TRADE_ACTION_REMOVE)
    def test_manual_cancel_pending_only_and_fill_race_keeps_open(self):
        self.s['cancelRequested']=True
        self.api.records[7]=dict(status='BROKER_PENDING',orderTicket=900,positionTicket=None,createdAt=iso(NOW-10))
        self.b.orders=[N(symbol='XAUUSD',ticket=900,magic=ex.MAGIC,comment='KMR:7')]
        self.engine().cycle();self.assertEqual(self.api.records[7]['status'],'CANCELLED')
        self.b.sent=[];self.api.records[7]['status']='BROKER_PENDING';self.b.orders=[]
        self.b.positions=[N(symbol='XAUUSD',ticket=901,magic=ex.MAGIC,comment='KMR:7',type=0,volume=.01,price_open=100,sl=90,tp=120,swap=0)]
        self.engine().cycle();self.assertEqual(self.api.records[7]['status'],'OPEN');self.assertEqual(self.b.sent,[])

class LedgerTest(unittest.TestCase):
    def setUp(self):
        self.b=Broker();self.calls=[]
        def call(path,payload=None):
            self.calls.append((path,payload))
            if path=='history':return dict(historyCursor=iso(0),identities=[dict(signalId=7,orderTicket=900,positionTicket=901,positionIdentifier=901)])
            if path=='config':return dict(account=ex.ACCOUNT,server=ex.SERVER,mode=ex.MODE)
            return {}
        self.api=N(call=call);self.e=ex.Executor(self.b,self.api,clock,10800,{})
        def deal(ticket,entry,profit):return N(ticket=ticket,order=900 if entry==0 else 999,position_id=901,time=NOW+10800-1,time_msc=(NOW+10800-1)*1000,type=0 if entry==0 else 1,entry=entry,reason=0,symbol='XAUUSD',magic=ex.MAGIC,comment='KMR:7' if entry==0 else '[tp]',volume=.01,price=100,profit=profit,commission=-.2,swap=-.1 if entry else 0,fee=-.01)
        self.deals=[deal(101,0,0),deal(102,1,3)]
    def test_ledger_imports_costs_links_exit_and_preserves_utc_time(self):
        self.b.history_deals_get=lambda *a:self.deals
        self.e.sync_ledger();rows=[p for name,p in self.calls if name=='ledger'][0]['deals']
        self.assertEqual(len(rows),2);self.assertEqual(rows[1]['signalId'],7)
        self.assertEqual(ex.epoch(rows[1]['tradedAt']),NOW-1)
        self.assertAlmostEqual(sum(rows[1][k] for k in ('profit','commission','swap','fee')),2.69)
        self.assertEqual(self.b.sent,[])
    def test_repeat_and_restart_do_not_fabricate_new_deals(self):
        self.b.history_deals_get=lambda *a:self.deals
        self.e.sync_ledger();self.e.last_sync=-float('inf');self.e.sync_ledger()
        batches=[p for name,p in self.calls if name=='ledger'];self.assertEqual(batches[1]['deals'],[])
        self.assertEqual(batches[0]['deals'][0]['ticket'],101)
    def test_history_failure_does_not_advance_cursor(self):
        self.b.history_deals_get=lambda *a:None
        with self.assertRaises(RuntimeError):self.e.sync_ledger()
        self.assertFalse(any(name=='ledger' for name,p in self.calls))
    def test_deposit_excluded_and_corrected_fee_reimported(self):
        deposit=copy.copy(self.deals[0]);deposit.type=2;deposit.ticket=999;self.b.history_deals_get=lambda *a:self.deals+[deposit]
        self.e.sync_ledger();self.deals[1].fee=-.02;self.e.last_sync=-float('inf');self.e.sync_ledger()
        batches=[p for name,p in self.calls if name=='ledger'];self.assertEqual(len(batches[0]['deals']),2);self.assertEqual(len(batches[1]['deals']),1);self.assertEqual(batches[1]['deals'][0]['fee'],-.02)

if __name__=='__main__':unittest.main()
