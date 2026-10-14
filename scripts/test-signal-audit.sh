#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
{
 printf '%s\n' 'BEGIN;' 'CREATE SCHEMA kmr_audit_test;' 'SET LOCAL search_path TO kmr_audit_test;'
 for migration in backend/src/main/resources/db/migration/V*.sql; do
  if [[ "$migration" == */V7__* ]]; then cat scripts/audit-legacy-fixture.sql; fi
  cat "$migration"
 done
 printf '%s\n' 'COMMIT;' 'SET search_path TO kmr_audit_test;'
 cat scripts/audit-assertions.sql
 printf '%s\n' 'DROP SCHEMA kmr_audit_test CASCADE;'
} | PGPASSWORD=ci-only-password psql -h localhost -U kmr -d kmr -v ON_ERROR_STOP=1
