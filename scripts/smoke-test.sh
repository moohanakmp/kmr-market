#!/usr/bin/env bash
set -euo pipefail
export DB_PASSWORD=ci-only-password ADMIN_PASSWORD=ci-admin-password-long MT5_FEED_PASSWORD=ci-feed-password-long MT5_DEMO_ACCOUNT=123456
# Reproduce Neon: non-empty public schema, no Flyway history, limited app role.
PGPASSWORD=ci-only-password psql -h localhost -U kmr -d kmr -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE public.unrelated_data (id integer PRIMARY KEY);
INSERT INTO public.unrelated_data VALUES (42);
CREATE ROLE kmr_ci WITH LOGIN PASSWORD 'ci-only-password';
GRANT CONNECT ON DATABASE kmr TO kmr_ci;
CREATE SCHEMA kmr;
GRANT USAGE, CREATE ON SCHEMA kmr TO kmr_ci;
SQL
# GitHub service PostgreSQL is reachable on the Linux host network.
docker run -d --name kmr-test --network host -e JDBC_DATABASE_URL=jdbc:postgresql://localhost:5432/kmr -e DB_USERNAME=kmr_ci -e DB_SCHEMA=kmr -e DB_PASSWORD -e ADMIN_PASSWORD -e MT5_FEED_PASSWORD -e MT5_DEMO_ACCOUNT kmr:ci >/dev/null
trap 'docker logs kmr-test; docker rm -f kmr-test >/dev/null' EXIT
ready=false
for attempt in $(seq 1 60); do
  if curl -fsS http://localhost:8080/actuator/health >/dev/null; then ready=true; break; fi
  sleep 2
done
[ "$ready" = true ]
[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/plans)" = 401 ]
curl -fsS http://localhost:8080/ | grep -q 'KMR Market'
curl -fsS -u "kalai:$ADMIN_PASSWORD" -H 'Content-Type: application/json' -d '{"symbol":"XAUUSD","direction":"BUY","entry":2000,"stopLoss":1990,"target":2020}' http://localhost:8080/api/plans > /tmp/kmr-plan.json
python3 - <<'PYTEST'
import json
p=json.load(open('/tmp/kmr-plan.json'))
assert p['symbol']=='XAUUSD' and p['id']>0
PYTEST
[ "$(curl -s -o /dev/null -w '%{http_code}' -u "kalai:$ADMIN_PASSWORD" -H 'Content-Type: application/json' -d '{"symbol":"XAUUSD","direction":"BUY","entry":2000,"stopLoss":2010,"target":2020}' http://localhost:8080/api/plans)" = 400 ]
curl -fsS -u "kalai:$ADMIN_PASSWORD" http://localhost:8080/api/plans | grep -q XAUUSD
curl -fsS -u "kalai:$ADMIN_PASSWORD" -H 'Content-Type: application/json' -d '{"symbol":"USDJPY"}' http://localhost:8080/api/watchlist >/dev/null
curl -fsS -u "kalai:$ADMIN_PASSWORD" -X DELETE http://localhost:8080/api/watchlist/USDJPY >/dev/null

# Tables and migration history belong to kmr; public data remains intact.
PGPASSWORD=ci-only-password psql -h localhost -U kmr -d kmr -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF (SELECT id FROM public.unrelated_data) <> 42 THEN
    RAISE EXCEPTION 'Existing public data changed';
  END IF;
  IF to_regclass('kmr.flyway_schema_history') IS NULL
    OR to_regclass('kmr.trade_plan') IS NULL
    OR to_regclass('kmr.watch_item') IS NULL THEN
    RAISE EXCEPTION 'Expected KMR tables missing';
  END IF;
  IF to_regclass('public.trade_plan') IS NOT NULL THEN
    RAISE EXCEPTION 'KMR tables were created in public';
  END IF;
END $$;
SQL

# Generate closed-candle setup and test replay protection and quote lifecycle.
python3 - <<'PYTEST'
import base64,json,urllib.request,urllib.error
from datetime import datetime,timezone,timedelta
now=datetime.now(timezone.utc).replace(microsecond=0)
def iso(x):return x.isoformat().replace('+00:00','Z')
bars=[]
for i in range(260):
 p=2000+i*0.02;c=p+(3 if i==259 else 0)
 bars.append(dict(time=iso(now-timedelta(seconds=(261-i)*60)),closedAt=iso(now-timedelta(seconds=(260-i)*60)),open=p,high=max(p,c)+0.1,low=min(p,c)-0.1,close=c))
q=dict(time=iso(now),bid=bars[-1]['close'],ask=bars[-1]['close']+0.01)
payload=dict(symbol='XAUUSD',timeframe='M1',quote=q,candles=bars)
feed='Basic '+base64.b64encode(b'mt5_feed:ci-feed-password-long').decode()
admin='Basic '+base64.b64encode(b'kalai:ci-admin-password-long').decode()
def api(path,data=None,auth=feed):
 req=urllib.request.Request('http://localhost:8080/api'+path,data=None if data is None else json.dumps(data).encode(),headers={'Authorization':auth,'Content-Type':'application/json'})
 with urllib.request.urlopen(req) as res:return json.load(res)
server_time=datetime.fromisoformat(api('/feed/time')['serverTime'].replace('Z','+00:00'))
assert abs((server_time-datetime.now(timezone.utc)).total_seconds()) < 10
assert api('/feed/candles',payload)['status']=='SCANNED'
assert api('/feed/candles',payload)['status']=='UNCHANGED'
signals=api('/signals',auth=admin)
assert len(signals)==1 and signals[0]['status']=='PENDING' and signals[0]['entry']>q['ask']
s=signals[0]
def linked_plan():
 plans=api('/plans',auth=admin)
 linked=[p for p in plans if p['sourceSignalId']==s['id']]
 assert len(linked)==1, 'signal must have exactly one saved plan'
 return linked[0]
p=linked_plan()
assert p['source']=='MT5' and p['timeframe']=='M1' and p['status']=='PENDING'
assert p['entry']==s['entry'] and p['stopLoss']==s['stopLoss'] and p['tp1']==s['tp1'] and p['tp2']==s['tp2'] and p['target']==s['targets'][-1]
assert p['createdAt']==s['createdAt']
cfg=api('/feed/execution/config')
assert cfg['mode']=='DEMO_ONLY_V2' and cfg['takeProfit']=='FINAL_TARGET'
assert len(s['targets'])==3 and s['targets'][2]>s['tp2']
assert cfg['volume']==0.01 and cfg['maxPendingPerSymbol']==5 and cfg['maxOpenPerSymbol']==5
assert cfg['timeframes']==['M1','M5','M15','M30','H1','D1','W1','MN1']
worker='11111111-1111-4111-8111-111111111111'
other='22222222-2222-4222-8222-222222222222'
api('/feed/execution/lease',dict(worker=worker))
try:api('/feed/execution/lease',dict(worker=other));raise AssertionError('competing executor accepted')
except urllib.error.HTTPError as e:assert e.code==409
try:api('/feed/execution/claim',dict(worker=worker,account=999,server='OctaFX-Demo',signalId=s['id']));raise AssertionError('wrong account accepted')
except urllib.error.HTTPError as e:assert e.code==403
claim=dict(worker=worker,account=123456,server='OctaFX-Demo',signalId=s['id'])
assert api('/feed/execution/claim',claim)['status']=='CLAIMED'
try:api('/feed/execution/claim',claim);raise AssertionError('duplicate execution claim accepted')
except urllib.error.HTTPError as e:assert e.code==409
api('/feed/execution/report',dict(worker=worker,signalId=s['id'],status='BROKER_PENDING',orderTicket=777,brokerStopLoss=s['stopLoss'],brokerTakeProfit=s['tp2'],message='CI synthetic acknowledgement; no broker called'))
assert linked_plan()['brokerStatus']=='BROKER_PENDING' and linked_plan()['orderTicket']==777
assert linked_plan()['brokerStopLoss']==s['stopLoss'] and linked_plan()['brokerTakeProfit']==s['tp2']
api('/feed/execution/report',dict(worker=worker,signalId=s['id'],status='OPEN',orderTicket=777,positionTicket=888,message='CI synthetic position'))
assert linked_plan()['positionTicket']==888 and linked_plan()['brokerStatus']=='OPEN'
api('/feed/execution/report',dict(worker=worker,signalId=s['id'],status='CLOSED',message='CI synthetic closure'))
assert api('/feed/execution/tracked')==[]
assert len(api('/tracker/'+str(s['id']),auth=admin)['events'])>=4
api('/feed/execution/release',dict(worker=worker))
api('/feed/execution/lease',dict(worker=other))
api('/feed/execution/release',dict(worker=other))
manual=[p for p in api('/plans',auth=admin) if p['source']=='MANUAL']
assert len(manual)==1 and manual[0]['entry']==2000 and manual[0]['target']==2020 and manual[0]['timeframe'] is None
# Bad future candle or stale quote cannot create a new setup.
bad=dict(payload,quote=dict(q,time=iso(now-timedelta(seconds=100))))
try:api('/feed/candles',bad);raise AssertionError('stale quote accepted')
except urllib.error.HTTPError as e:assert e.code==400
future=dict(payload,quote=dict(q,time=iso(now+timedelta(seconds=120))))
try:api('/feed/candles',future);raise AssertionError('future quote accepted')
except urllib.error.HTTPError as e:assert e.code==400
# Feed principal cannot use admin endpoints.
try:api('/signals');raise AssertionError('feed could read admin API')
except urllib.error.HTTPError as e:assert e.code==403
# A later quote beyond TP1 must be MISSED, not an instant TP.
api('/feed/quotes',{'quotes':[{'symbol':'XAUUSD','quote':{'time':iso(now+timedelta(seconds=1)),'bid':s['tp1']+0.1,'ask':s['tp1']+0.11}}]})
assert api('/signals',auth=admin)[0]['status']=='MISSED'
assert linked_plan()['status']=='MISSED' and linked_plan()['id']==p['id']
# Subsequent candles for same source candle cannot duplicate the missed setup.
assert api('/feed/candles',payload)['status']=='UNCHANGED'
assert len(api('/signals',auth=admin))==1
assert linked_plan()['id']==p['id']
assert api('/scanner',auth=admin)['timeframes']==['M1','M5','M15','M30','H1','D1','W1','MN1']
# Manual request + existing draft conversion, single target support and durable dedup.
import uuid
api('/feed/quotes',{'quotes':[{'symbol':'XAUUSD','quote':{'time':iso(now+timedelta(seconds=2)),'bid':2000,'ask':2000.01}}]})
manual_request=dict(requestKey=str(uuid.uuid4()),planId=manual[0]['id'],symbol='XAUUSD',direction='BUY',timeframe='M5',pendingKind='STOP',entry=2010,stopLoss=2000,targets=[2020,2030,2040],expiresAt=iso(now+timedelta(hours=1)))
m=api('/orders/manual',manual_request,admin)
assert api('/orders/manual',manual_request,admin)['id']==m['id']
linked=[p for p in api('/plans',auth=admin) if p['sourceSignalId']==m['id']]
assert len(linked)==1 and linked[0]['id']==manual[0]['id'] and linked[0]['source']=='MANUAL' and linked[0]['targets']==[2020,2030,2040]
api('/feed/execution/lease',dict(worker=worker))
claim=dict(worker=worker,account=123456,server='OctaFX-Demo',signalId=m['id'])
api('/feed/execution/claim',claim)
api('/feed/execution/report',dict(worker=worker,signalId=m['id'],status='BROKER_PENDING',orderTicket=999,brokerEntryPrice=2010,volume=.01,brokerStopLoss=2000,brokerTakeProfit=2040,message='CI manual pending; fake broker'))
api('/orders/'+str(m['id'])+'/cancel',{},admin)
assert api('/feed/execution/tracked')[0]['signal']['cancelRequested']
api('/feed/execution/report',dict(worker=worker,signalId=m['id'],status='CANCELLED',message='CI pending removed; fake broker'))
try:
 req=urllib.request.Request('http://localhost:8080/api/plans/'+str(linked[0]['id']),method='DELETE',headers={'Authorization':admin})
 urllib.request.urlopen(req);raise AssertionError('audit plan deleted')
except urllib.error.HTTPError as e:assert e.code==409
single=dict(manual_request,requestKey=str(uuid.uuid4()),planId=None,entry=1990,stopLoss=1980,targets=[2000],pendingKind='LIMIT')
assert len(api('/orders/manual',single,admin)['targets'])==1
# Expiry without an incoming quote.
short=dict(manual_request,requestKey=str(uuid.uuid4()),planId=None,expiresAt=iso(datetime.now(timezone.utc)+timedelta(seconds=1)))
exp=api('/orders/manual',short,admin)
import time
time.sleep(17)
assert next(x for x in api('/signals',auth=admin) if x['id']==exp['id'])['status']=='EXPIRED'
# Realized ledger is idempotent and includes all known deal costs.
clock_now=datetime.now(timezone.utc)
deals=[dict(ticket=10001,orderTicket=999,positionIdentifier=888,signalId=s['id'],tradedAt=iso(clock_now),symbol='XAUUSD',dealType=1,entryKind=1,reason=5,volume=.01,price=2040,profit=3,commission=-.2,swap=-.1,fee=-.01)]
ledger=dict(worker=worker,account=123456,server='OctaFX-Demo',observedAt=iso(clock_now),historyCursor=iso(clock_now),deals=deals,positions=[],balance=10003,equity=10003)
api('/feed/execution/ledger',ledger);api('/feed/execution/ledger',ledger)
report=api('/pnl?zone=Asia%2FKolkata',auth=admin)
assert abs(report['total']['net']-2.69)<.000001 and report['total']['exitDeals']==1 and report['historyAvailable']
assert len(api('/deals',auth=admin))==1
assert abs(api('/tracker/'+str(s['id']),auth=admin)['deals'][0]['net']-2.69)<.000001
api('/feed/execution/release',dict(worker=worker))
# Browser cookie session survives page reload; CSRF protects cookie writes.
import http.cookiejar
with urllib.request.urlopen(urllib.request.Request('http://localhost:8080/api/session',headers={'X-Forwarded-Proto':'https'})) as res:
 cookie=res.headers.get('Set-Cookie','').lower()
 assert '; secure' in cookie and 'httponly' in cookie and 'samesite=strict' in cookie, 'HTTPS proxy session cookie must be secure'
jar=http.cookiejar.CookieJar();browser=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def browser_api(path,method='GET',body=None,headers=None):
 req=urllib.request.Request('http://localhost:8080/api'+path,method=method,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**(headers or {})})
 with browser.open(req) as res:return json.load(res)
assert not browser_api('/session')['authenticated']
browser_api('/session','POST',headers={'Authorization':admin})
state=browser_api('/session');assert state['authenticated']
assert browser_api('/me')['app']=='KMR Market'
try:browser_api('/session','DELETE');raise AssertionError('cookie write without CSRF accepted')
except urllib.error.HTTPError as e:assert e.code==403
browser_api('/session','DELETE',headers={'X-CSRF-TOKEN':state['csrfToken']})
assert not browser_api('/session')['authenticated']
print('MT5 manual orders, lifecycle, P&L ledger and browser session integration checks passed')
PYTEST
