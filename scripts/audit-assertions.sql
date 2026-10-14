-- Run after V1 through V7 in an isolated test schema. Everything rolls back.
BEGIN;
DO $$ BEGIN
 IF (SELECT count(*) FROM signal_activity_audit WHERE event_type='LEGACY_BASELINE' AND actor='SYSTEM:MIGRATION' AND symbol LIKE 'LEGACY%')<>3 THEN RAISE EXCEPTION 'Historical backfill failed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM auto_signals WHERE symbol='LEGACYAUTO' AND created_by='LEGACY_UNKNOWN' AND status='EXPIRED' AND decision_context='{}') THEN RAISE EXCEPTION 'Legacy evidence fabricated or lost'; END IF;
 IF NOT EXISTS(SELECT 1 FROM manual_signals WHERE symbol='LEGACYMAN') OR NOT EXISTS(SELECT 1 FROM low_profit_signals WHERE symbol='LEGACYSCALP') THEN RAISE EXCEPTION 'Legacy channel classification failed'; END IF;
END $$;
INSERT INTO auto_signal(symbol,timeframe,direction,status,entry,stop_loss,tp1,tp2,quality,reason,strategy,source_bar,created_at,expires_at,updated_at,last_quote_at,created_by,initiated_by,updated_by,decision_context)
VALUES ('AUDITAUTO','M15','BUY','PENDING',100,90,110,120,90,'EMA20 pullback','KMR_V28_PUBLIC',now(),now(),now()-interval '1 minute',now(),now(),'SYSTEM:KMR_V28_PUBLIC','USER:audit-tester','SYSTEM:KMR_V28_PUBLIC','{"scan":{"e20":99,"rsi":55},"source":"TEST"}'),
('AUDITMANUAL','M15','BUY','PENDING',100,90,110,120,0,'Operator entered levels','MANUAL_PENDING_V1',now(),now(),now()+interval '1 hour',now(),now(),'USER:audit-tester','USER:audit-tester','USER:audit-tester','{"operatorReason":"EMA20 retest"}'),
('AUDITSCALP','M5','BUY','PENDING',100,90,110,120,0,'Range fade','KMR_V28_SCALP',now(),now(),now()+interval '1 hour',now(),now(),'SYSTEM:KMR_V28_SCALP','SYSTEM:SCANNER','SYSTEM:SCANNER','{}');
DO $$ BEGIN
 IF (SELECT count(*) FROM auto_signals WHERE symbol LIKE 'AUDIT%')<>1 OR (SELECT count(*) FROM manual_signals WHERE symbol LIKE 'AUDIT%')<>1 OR (SELECT count(*) FROM low_profit_signals WHERE symbol LIKE 'AUDIT%')<>1 THEN RAISE EXCEPTION 'Separate channel tables incorrect'; END IF;
 IF (SELECT count(*) FROM signal_activity_audit WHERE symbol LIKE 'AUDIT%' AND event_type='SIGNAL_CREATED')<>3 THEN RAISE EXCEPTION 'Creation history missing'; END IF;
 IF NOT EXISTS(SELECT 1 FROM signal_activity_audit WHERE symbol='AUDITAUTO' AND decision_context->'scan'->>'e20'='99' AND actor='SYSTEM:KMR_V28_PUBLIC') THEN RAISE EXCEPTION 'Decision evidence missing'; END IF;
END $$;
-- This matches the JPA bulk expiry, which bypasses entity callbacks.
UPDATE auto_signal SET status='EXPIRED',version=version+1 WHERE symbol='AUDITAUTO';
UPDATE auto_signal SET cancel_requested=true,status='CANCELLED',updated_by='USER:audit-tester' WHERE symbol='AUDITMANUAL';
INSERT INTO broker_execution(signal_id,account_login,broker_server,worker,status,message,created_at,updated_at)
 SELECT id,123456,'Test-Demo','test-worker','CLAIMED','Reserved',now(),now() FROM auto_signal WHERE symbol='AUDITSCALP';
UPDATE broker_execution SET status='OPEN',broker_entry_price=100,broker_stop_loss=90,broker_take_profit=120,volume=.01,message='Broker fill confirmed' WHERE worker='test-worker';
UPDATE broker_execution SET broker_stop_loss=101,protected_target=1,message='Protect profit' WHERE worker='test-worker';
UPDATE broker_execution SET status='CLOSED',message='Final target',close_reason='TP' WHERE worker='test-worker';
INSERT INTO scan_state(id,symbol,timeframe,last_bar,last_scan_at,last_quote_at,received_at,reason,quality,evaluated_by,decision_context)
 VALUES ('AUDITSCAN|M5','AUDITSCAN','M5',now(),now(),now(),now(),'Waiting for pullback',10,'SYSTEM:SCANNER','{"source":"TEST","scan":{"e20":99}}');
INSERT INTO broker_deal(id,account_login,broker_server,ticket,order_ticket,position_identifier,signal_id,traded_at,symbol,deal_type,entry_kind,reason,volume,price,profit,commission,swap,fee)
 SELECT 'audit-deal',123456,'Test-Demo',1001,1002,1003,id,now(),symbol,1,1,5,.01,120,1.2,-.1,0,-.1 FROM auto_signal WHERE symbol='AUDITSCALP';
DO $$ DECLARE n bigint; BEGIN
 IF NOT EXISTS(SELECT 1 FROM signal_activity_audit WHERE symbol='AUDITAUTO' AND event_type='EXPIRED' AND previous_status='PENDING' AND actor='SYSTEM:EXPIRY' AND after_state->>'expires_at' IS NOT NULL) THEN RAISE EXCEPTION 'Bulk expiry audit missing'; END IF;
 IF (SELECT status FROM auto_signals WHERE symbol='AUDITAUTO')<>'EXPIRED' THEN RAISE EXCEPTION 'Channel state not synchronized'; END IF;
 IF NOT EXISTS(SELECT 1 FROM signal_activity_audit WHERE symbol='AUDITMANUAL' AND event_type='CANCELLED' AND actor='USER:audit-tester') THEN RAISE EXCEPTION 'Cancel identity missing'; END IF;
 IF (SELECT count(*) FROM signal_activity_audit WHERE symbol='AUDITSCALP' AND event_type LIKE 'BROKER_%')<>5 THEN RAISE EXCEPTION 'Broker lifecycle incomplete'; END IF;
 IF NOT EXISTS(SELECT 1 FROM signal_activity_audit WHERE symbol='AUDITSCALP' AND event_type='BROKER_STOP_CHANGED' AND actor='MT5_WORKER:test-worker') THEN RAISE EXCEPTION 'Stop protection event missing'; END IF;
 IF NOT EXISTS(SELECT 1 FROM signal_activity_audit WHERE symbol='AUDITSCAN' AND channel='LOW_PROFIT' AND event_type='SCAN_EVALUATED') THEN RAISE EXCEPTION 'No-signal diagnostics missing'; END IF;
 IF NOT EXISTS(SELECT 1 FROM signal_activity_audit WHERE event_type='BROKER_DEAL' AND after_state->>'profit' IS NOT NULL AND after_state->>'fee' IS NOT NULL) THEN RAISE EXCEPTION 'Broker profit/cost evidence missing'; END IF;
 SELECT count(*) INTO n FROM signal_activity_audit;
 UPDATE scan_state SET last_scan_at=now()+interval '1 second',evaluation_count=evaluation_count+1 WHERE symbol='AUDITSCAN';
 IF (SELECT count(*) FROM signal_activity_audit)<>n THEN RAISE EXCEPTION 'Unchanged scan duplicated'; END IF;
 UPDATE auto_signal SET last_quote_at=now()+interval '1 second',version=version+1 WHERE symbol='AUDITSCALP';
 IF (SELECT count(*) FROM signal_activity_audit)<>n THEN RAISE EXCEPTION 'Heartbeat generated a false lifecycle event'; END IF;
 BEGIN UPDATE signal_activity_audit SET reason='tamper'; RAISE EXCEPTION 'Tampering accepted'; EXCEPTION WHEN raise_exception THEN IF SQLERRM NOT LIKE 'Audit rows are append-only%' THEN RAISE; END IF; END;
 BEGIN DELETE FROM signal_activity_audit; RAISE EXCEPTION 'Deletion accepted'; EXCEPTION WHEN raise_exception THEN IF SQLERRM NOT LIKE 'Audit rows are append-only%' THEN RAISE; END IF; END;
 BEGIN TRUNCATE signal_activity_audit; RAISE EXCEPTION 'Truncate accepted'; EXCEPTION WHEN raise_exception THEN IF SQLERRM NOT LIKE 'Audit rows are append-only%' THEN RAISE; END IF; END;
 BEGIN UPDATE auto_signal SET decision_context='{}' WHERE symbol='AUDITAUTO'; RAISE EXCEPTION 'Evidence rewrite accepted'; EXCEPTION WHEN raise_exception THEN IF SQLERRM NOT LIKE 'Signal creation identity%' THEN RAISE; END IF; END;
 BEGIN UPDATE auto_signal SET strategy='MANUAL_PENDING_V1' WHERE symbol='AUDITAUTO'; RAISE EXCEPTION 'Channel rewrite accepted'; EXCEPTION WHEN raise_exception THEN IF SQLERRM NOT LIKE 'A signal cannot change%' THEN RAISE; END IF; END;
END $$;
-- Trigger routing must still work when the caller uses a different schema.
SET LOCAL search_path TO public;
UPDATE kmr_audit_test.auto_signal SET execution_note='Cross-schema update',updated_by='USER:audit-tester' WHERE symbol='AUDITMANUAL';
SET LOCAL search_path TO kmr_audit_test;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM signal_activity_audit WHERE symbol='AUDITMANUAL' AND event_type='EXECUTION_NOTE' AND reason='Cross-schema update') THEN RAISE EXCEPTION 'Cross-schema audit routing failed'; END IF;
END $$;
ROLLBACK;
