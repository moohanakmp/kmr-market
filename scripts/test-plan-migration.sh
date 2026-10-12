#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
PGPASSWORD=ci-only-password psql -h localhost -U kmr -d kmr -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
CREATE SCHEMA kmr_plan_migration_test;
SET LOCAL search_path TO kmr_plan_migration_test;
\i backend/src/main/resources/db/migration/V1__initial.sql
\i backend/src/main/resources/db/migration/V2__auto_signals.sql
INSERT INTO trade_plan(symbol,direction,entry,stop_loss,target,created_at)
 VALUES ('XAUUSD','BUY',100,90,120,now());
INSERT INTO auto_signal(symbol,timeframe,direction,status,entry,stop_loss,tp1,tp2,quality,reason,strategy,source_bar,created_at,expires_at,updated_at,last_quote_at)
 VALUES ('XAUUSD','H1','BUY','PENDING',100,90,110,120,90,'test','TEST',now(),now(),now()+interval '3 hours',now(),now()),
 ('XAUUSD','H2','SELL','EXPIRED',100,110,90,80,80,'test','TEST',now(),now(),now()-interval '1 hour',now(),now());
\i backend/src/main/resources/db/migration/V3__signal_trade_plans.sql
DO $$
BEGIN
 IF (SELECT count(*) FROM trade_plan)<>3 THEN RAISE EXCEPTION 'Backfill did not create one plan per signal'; END IF;
 IF (SELECT count(*) FROM trade_plan WHERE source_signal_id IS NULL AND entry=100 AND stop_loss=90 AND target=120)<>1 THEN
  RAISE EXCEPTION 'Manual plan changed';
 END IF;
 IF EXISTS(SELECT 1 FROM trade_plan p JOIN auto_signal s ON s.id=p.source_signal_id
  WHERE p.symbol<>s.symbol OR p.direction<>s.direction OR p.entry<>s.entry OR p.stop_loss<>s.stop_loss OR p.target<>s.tp2 OR p.created_at<>s.created_at) THEN
  RAISE EXCEPTION 'Backfilled levels differ';
 END IF;
END $$;
-- Trigger must target the signal's own schema even if the caller's search path
-- points elsewhere. This also covers inserts by an older app during deployment.
SET LOCAL search_path TO public;
INSERT INTO kmr_plan_migration_test.auto_signal(symbol,timeframe,direction,status,entry,stop_loss,tp1,tp2,quality,reason,strategy,source_bar,created_at,expires_at,updated_at,last_quote_at)
 VALUES ('BTCUSD','M1','BUY','PENDING',100,90,110,120,90,'test','TEST',now(),now(),now()+interval '3 minutes',now(),now());
SET LOCAL search_path TO kmr_plan_migration_test;
DO $$
BEGIN
 IF (SELECT count(*) FROM trade_plan WHERE source_signal_id IS NOT NULL)<>3 THEN RAISE EXCEPTION 'New signal did not create a plan'; END IF;
 BEGIN
  INSERT INTO trade_plan(symbol,direction,entry,stop_loss,target,created_at,source_signal_id)
   SELECT symbol,direction,entry,stop_loss,tp2,created_at,id FROM auto_signal LIMIT 1;
  RAISE EXCEPTION 'Duplicate linked plan accepted';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
END $$;
-- Updates do not insert another saved plan or change the saved price levels.
UPDATE auto_signal SET status='MISSED',tracking_gap=true WHERE symbol='BTCUSD';
DO $$
BEGIN
 IF (SELECT count(*) FROM trade_plan)<>4 THEN RAISE EXCEPTION 'Quote update duplicated a plan'; END IF;
END $$;
ROLLBACK;
SQL
