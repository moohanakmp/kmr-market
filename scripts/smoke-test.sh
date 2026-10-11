#!/usr/bin/env bash
set -euo pipefail
export DB_PASSWORD=ci-only-password ADMIN_PASSWORD=ci-admin-password-long MT5_FEED_PASSWORD=ci-feed-password-long
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
docker run -d --name kmr-test --network host -e JDBC_DATABASE_URL=jdbc:postgresql://localhost:5432/kmr -e DB_USERNAME=kmr_ci -e DB_SCHEMA=kmr -e DB_PASSWORD -e ADMIN_PASSWORD -e MT5_FEED_PASSWORD kmr:ci >/dev/null
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
assert api('/feed/candles',payload)['status']=='SCANNED'
assert api('/feed/candles',payload)['status']=='UNCHANGED'
signals=api('/signals',auth=admin)
assert len(signals)==1 and signals[0]['status']=='PENDING' and signals[0]['entry']>q['ask']
s=signals[0]
# Bad future candle or stale quote cannot create a new setup.
bad=dict(payload,quote=dict(q,time=iso(now-timedelta(seconds=100))))
try:api('/feed/candles',bad);raise AssertionError('stale quote accepted')
except urllib.error.HTTPError as e:assert e.code==400
# Feed principal cannot use admin endpoints.
try:api('/signals');raise AssertionError('feed could read admin API')
except urllib.error.HTTPError as e:assert e.code==403
# A later quote beyond TP1 must be MISSED, not an instant TP.
api('/feed/quotes',{'quotes':[{'symbol':'XAUUSD','quote':{'time':iso(now+timedelta(seconds=1)),'bid':s['tp1']+0.1,'ask':s['tp1']+0.11}}]})
assert api('/signals',auth=admin)[0]['status']=='MISSED'
# Subsequent candles for same source candle cannot duplicate the missed setup.
assert api('/feed/candles',payload)['status']=='UNCHANGED'
assert len(api('/signals',auth=admin))==1
assert len(api('/scanner',auth=admin)['timeframes'])==21
print('MT5 signal integration checks passed')
PYTEST
