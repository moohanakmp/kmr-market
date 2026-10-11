#!/usr/bin/env bash
set -euo pipefail
export DB_PASSWORD=ci-only-password ADMIN_PASSWORD=ci-admin-password-long
# GitHub service PostgreSQL is reachable on the Linux host network.
docker run -d --name kmr-test --network host -e JDBC_DATABASE_URL=jdbc:postgresql://localhost:5432/kmr -e DB_USERNAME=kmr -e DB_PASSWORD -e ADMIN_PASSWORD kmr:ci >/dev/null
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
