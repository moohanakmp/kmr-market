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
assert p['entry']==s['entry'] and p['stopLoss']==s['stopLoss'] and p['tp1']==s['tp1'] and p['tp2']==s['tp2'] and p['target']==s['tp2']
assert p['createdAt']==s['createdAt']
cfg=api('/feed/execution/config')
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
print('MT5 signal integration checks passed')
PYTEST
