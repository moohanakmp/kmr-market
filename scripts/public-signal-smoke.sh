#!/usr/bin/env bash
set -euo pipefail
# CI-only fictional account/credentials; no broker is contacted.
export DB_PASSWORD=ci-only-password ADMIN_PASSWORD=ci-admin-password-long MT5_FEED_PASSWORD=ci-feed-password-long MT5_DEMO_ACCOUNT=123456
PGPASSWORD=ci-only-password psql -h localhost -U kmr -d kmr -v ON_ERROR_STOP=1 -c 'CREATE SCHEMA kmr_public;'
docker run -d --name kmr-public-test --network host -e PORT=8081 -e JDBC_DATABASE_URL=jdbc:postgresql://localhost:5432/kmr -e DB_USERNAME=kmr -e DB_SCHEMA=kmr_public -e DB_PASSWORD -e ADMIN_PASSWORD -e MT5_FEED_PASSWORD -e MT5_DEMO_ACCOUNT -e KMR_PUBLIC_SIGNALS_ENABLED=true kmr:ci >/dev/null
trap 'docker logs kmr-public-test; docker rm -f kmr-public-test >/dev/null' EXIT
ready=false
for attempt in $(seq 1 60); do
 if curl -fsS http://localhost:8081/actuator/health >/dev/null; then ready=true; break; fi
 sleep 2
done
[ "$ready" = true ]
python3 - <<'PY'
import base64,json,time,urllib.request,urllib.error
from pathlib import Path
admin='Basic '+base64.b64encode(b'kalai:ci-admin-password-long').decode()
feed='Basic '+base64.b64encode(b'mt5_feed:ci-feed-password-long').decode()
def api(path,data=None,auth=admin):
 req=urllib.request.Request('http://localhost:8081/api'+path,data=None if data is None else json.dumps(data).encode(),headers={'Authorization':auth,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)
 except urllib.error.HTTPError as e:
  print('API failure:',path,'HTTP',e.code,e.read().decode()[:1000]);raise
fixture=json.loads(Path('backend/src/test/resources/v28-btc-fixture.json').read_text());now=int(time.time()*1000);shift=now-fixture['now']
for key in ('c5','c15','h1','h4'):
 for b in fixture[key]:b['t']+=shift
fixture.update(now=now,quoteAt=now,calendarAt=now)
assert api('/market/public-status')['mt5Purpose']=='ORDER_EXECUTION_ONLY'
api('/watchlist',{'symbol':'BTCUSD'})
analysis=api('/market/public-snapshot',fixture)
assert analysis['auto']['entryType']=='MARKET' and analysis['scan']['score']==98
signals=[s for s in api('/signals') if s['strategy']=='KMR_V28_PUBLIC']
assert len(signals)==1 and signals[0]['status']=='PENDING' and signals[0]['pendingKind']=='MARKET'
assert len([p for p in api('/plans') if p['sourceSignalId']==signals[0]['id']])==1
api('/market/public-snapshot',fixture)
assert len([s for s in api('/signals') if s['strategy']=='KMR_V28_PUBLIC'])==1
assert api('/market/analysis/BTCUSD')['manual']['entryType']=='MARKET'
audit=api('/audit?channel=AUTO&signalId='+str(signals[0]['id']))['items']
created=[e for e in audit if e['event_type']=='SIGNAL_CREATED']
assert len(created)==1 and created[0]['actor']=='SYSTEM:KMR_V28_PUBLIC'
assert created[0]['initiated_by']=='USER:kalai'
assert created[0]['decision_context']['scan']['e20']>0
assert any(e['event_type']=='MANUAL_ANALYSIS_VIEWED' for e in api('/audit?channel=MANUAL')['items'])
try:api('/market/public-snapshot',fixture,feed)
except urllib.error.HTTPError as e:assert e.code==403
else:raise AssertionError('Executor role must not create public signals')
print('Public signal, linked plan, deduplication, manual analysis and role isolation passed without MT5.')
PY
