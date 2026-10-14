-- Preserve the original execution queue and every existing foreign key.
ALTER TABLE auto_signal ADD COLUMN created_by TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE auto_signal ADD COLUMN initiated_by TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE auto_signal ADD COLUMN updated_by TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE auto_signal ADD COLUMN decision_context JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE scan_state ADD COLUMN evaluation_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE scan_state ADD COLUMN evaluated_by TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE scan_state ADD COLUMN decision_context JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE FUNCTION signal_channel(strategy TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
 SELECT CASE WHEN strategy='MANUAL_PENDING_V1' THEN 'MANUAL' WHEN strategy='KMR_V28_SCALP' THEN 'LOW_PROFIT' ELSE 'AUTO' END
$$;

-- Real, separate tables, one row per signal. The execution ID remains shared.
CREATE TABLE auto_signals (
 signal_id BIGINT PRIMARY KEY REFERENCES auto_signal(id),
 symbol VARCHAR(20) NOT NULL, strategy VARCHAR(40) NOT NULL,
 created_by TEXT NOT NULL, initiated_by TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 status VARCHAR(12) NOT NULL, reason TEXT NOT NULL,
 decision_context JSONB NOT NULL, current_snapshot JSONB NOT NULL,
 last_recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE manual_signals (LIKE auto_signals INCLUDING ALL);
ALTER TABLE manual_signals ADD FOREIGN KEY(signal_id) REFERENCES auto_signal(id);
CREATE TABLE low_profit_signals (LIKE auto_signals INCLUDING ALL);
ALTER TABLE low_profit_signals ADD FOREIGN KEY(signal_id) REFERENCES auto_signal(id);

CREATE TABLE signal_activity_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 signal_id BIGINT REFERENCES auto_signal(id), channel TEXT NOT NULL CHECK(channel IN ('AUTO','MANUAL','LOW_PROFIT')),
 symbol VARCHAR(20) NOT NULL, timeframe VARCHAR(4),
 event_type TEXT NOT NULL, actor TEXT NOT NULL, initiated_by TEXT,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), observed_at TIMESTAMPTZ,
 previous_status TEXT, new_status TEXT, reason TEXT NOT NULL,
 before_state JSONB, after_state JSONB NOT NULL,
 decision_context JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX signal_audit_signal_time ON signal_activity_audit(signal_id,id DESC);
CREATE INDEX signal_audit_channel_time ON signal_activity_audit(channel,id DESC);
CREATE INDEX signal_audit_symbol_time ON signal_activity_audit(symbol,id DESC);
CREATE INDEX signal_audit_time ON signal_activity_audit(occurred_at);

-- Freeze function search paths to the migration's schema, including when callers
-- address a table with a fully qualified name and a different search_path.
CREATE FUNCTION audit_signal_row() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE channel_name TEXT; table_name TEXT; event_name TEXT; who TEXT; old_row JSONB; new_row JSONB;
BEGIN
 channel_name=signal_channel(NEW.strategy);
 table_name=CASE channel_name WHEN 'MANUAL' THEN 'manual_signals' WHEN 'LOW_PROFIT' THEN 'low_profit_signals' ELSE 'auto_signals' END;
 new_row=to_jsonb(NEW);
 IF TG_OP='UPDATE' THEN
  old_row=to_jsonb(OLD);
  IF NEW.strategy<>OLD.strategy THEN RAISE EXCEPTION 'A signal cannot change its origin channel or strategy'; END IF;
  IF NEW.created_by<>OLD.created_by OR NEW.initiated_by<>OLD.initiated_by OR NEW.decision_context<>OLD.decision_context THEN
   RAISE EXCEPTION 'Signal creation identity and decision evidence are immutable';
  END IF;
  -- Quote heartbeat writes are represented by scanner evaluations; they are not
  -- new order lifecycle events. All economic/status/diagnostic changes remain.
  IF (new_row-ARRAY['version','updated_at','last_quote_at','updated_by'])=(old_row-ARRAY['version','updated_at','last_quote_at','updated_by']) THEN RETURN NEW; END IF;
 END IF;
 EXECUTE format('INSERT INTO %I.%I(signal_id,symbol,strategy,created_by,initiated_by,created_at,expires_at,status,reason,decision_context,current_snapshot) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) ON CONFLICT(signal_id) DO UPDATE SET status=excluded.status,expires_at=excluded.expires_at,reason=excluded.reason,current_snapshot=excluded.current_snapshot,last_recorded_at=clock_timestamp()',TG_TABLE_SCHEMA,table_name)
 USING NEW.id,NEW.symbol,NEW.strategy,NEW.created_by,NEW.initiated_by,NEW.created_at,NEW.expires_at,NEW.status,NEW.reason,NEW.decision_context,new_row;
 event_name=CASE WHEN TG_OP='INSERT' THEN 'SIGNAL_CREATED' WHEN OLD.status IS DISTINCT FROM NEW.status THEN NEW.status WHEN OLD.cancel_requested IS DISTINCT FROM NEW.cancel_requested THEN 'CANCEL_REQUESTED' WHEN OLD.execution_note IS DISTINCT FROM NEW.execution_note THEN 'EXECUTION_NOTE' WHEN OLD.reached_target IS DISTINCT FROM NEW.reached_target THEN 'TARGET_PROGRESS' WHEN OLD.tracking_gap IS DISTINCT FROM NEW.tracking_gap THEN 'TRACKING_GAP' ELSE 'SIGNAL_UPDATED' END;
 who=CASE WHEN TG_OP='INSERT' THEN NEW.created_by WHEN NEW.status='EXPIRED' AND OLD.status IS DISTINCT FROM NEW.status THEN 'SYSTEM:EXPIRY' ELSE NEW.updated_by END;
 INSERT INTO signal_activity_audit(signal_id,channel,symbol,timeframe,event_type,actor,initiated_by,observed_at,previous_status,new_status,reason,before_state,after_state,decision_context)
 VALUES(NEW.id,channel_name,NEW.symbol,NEW.timeframe,event_name,who,NEW.initiated_by,NEW.last_quote_at,CASE WHEN TG_OP='UPDATE' THEN OLD.status END,NEW.status,
 CASE event_name WHEN 'EXPIRED' THEN 'Signal validity deadline reached; broker cancellation is tracked separately' WHEN 'CANCELLED' THEN 'Signal cancelled; see cancellation flag, tracking gap and broker events' WHEN 'ACTIVE' THEN 'Signal activated; verify broker fill in BROKER events' WHEN 'STOPPED' THEN 'Reference price reached the signal stop; broker outcome is recorded separately' WHEN 'EXECUTION_NOTE' THEN coalesce(NEW.execution_note,'Execution note cleared') ELSE NEW.reason END,
 old_row,new_row,NEW.decision_context);
 RETURN NEW;
END $$;
CREATE TRIGGER signal_channel_audit AFTER INSERT OR UPDATE ON auto_signal FOR EACH ROW EXECUTE FUNCTION audit_signal_row();

-- Historical backfill records only what is actually known. It does not invent
-- old creators, indicator values, or historical expiry transition timestamps.
INSERT INTO auto_signals SELECT id,symbol,strategy,created_by,initiated_by,created_at,expires_at,status,reason,decision_context,to_jsonb(s),clock_timestamp() FROM auto_signal s WHERE signal_channel(strategy)='AUTO';
INSERT INTO manual_signals SELECT id,symbol,strategy,created_by,initiated_by,created_at,expires_at,status,reason,decision_context,to_jsonb(s),clock_timestamp() FROM auto_signal s WHERE signal_channel(strategy)='MANUAL';
INSERT INTO low_profit_signals SELECT id,symbol,strategy,created_by,initiated_by,created_at,expires_at,status,reason,decision_context,to_jsonb(s),clock_timestamp() FROM auto_signal s WHERE signal_channel(strategy)='LOW_PROFIT';
INSERT INTO signal_activity_audit(signal_id,channel,symbol,timeframe,event_type,actor,observed_at,new_status,reason,after_state,decision_context)
 SELECT id,signal_channel(strategy),symbol,timeframe,'LEGACY_BASELINE','SYSTEM:MIGRATION',updated_at,status,'Existing state imported; earlier actor and decision evidence unavailable',to_jsonb(s),decision_context FROM auto_signal s;

INSERT INTO signal_activity_audit(signal_id,channel,symbol,timeframe,event_type,actor,observed_at,new_status,reason,after_state)
 SELECT e.signal_id,signal_channel(s.strategy),s.symbol,s.timeframe,'LEGACY_BROKER_EVENT','LEGACY_UNKNOWN',e.observed_at,e.status,e.message,to_jsonb(e) FROM execution_event e JOIN auto_signal s ON s.id=e.signal_id;

CREATE FUNCTION audit_broker_row() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE s auto_signal%ROWTYPE; old_row JSONB; event_name TEXT;
BEGIN
 SELECT * INTO STRICT s FROM auto_signal WHERE id=NEW.signal_id;
 IF TG_OP='UPDATE' THEN
  old_row=to_jsonb(OLD);
  IF (to_jsonb(NEW)-ARRAY['updated_at','floating_profit'])=(old_row-ARRAY['updated_at','floating_profit']) THEN RETURN NEW; END IF;
 END IF;
 event_name=CASE WHEN TG_OP='INSERT' OR OLD.status IS DISTINCT FROM NEW.status THEN 'BROKER_'||NEW.status WHEN OLD.broker_stop_loss IS DISTINCT FROM NEW.broker_stop_loss THEN 'BROKER_STOP_CHANGED' WHEN OLD.broker_take_profit IS DISTINCT FROM NEW.broker_take_profit THEN 'BROKER_TARGET_CHANGED' ELSE 'BROKER_UPDATED' END;
 INSERT INTO signal_activity_audit(signal_id,channel,symbol,timeframe,event_type,actor,observed_at,previous_status,new_status,reason,before_state,after_state,decision_context)
 VALUES(s.id,signal_channel(s.strategy),s.symbol,s.timeframe,event_name,'MT5_WORKER:'||NEW.worker,NEW.updated_at,CASE WHEN TG_OP='UPDATE' THEN OLD.status END,NEW.status,NEW.message,old_row,to_jsonb(NEW),s.decision_context);
 RETURN NEW;
END $$;
CREATE TRIGGER broker_lifecycle_audit AFTER INSERT OR UPDATE ON broker_execution FOR EACH ROW EXECUTE FUNCTION audit_broker_row();

CREATE FUNCTION audit_deal_row() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE s auto_signal%ROWTYPE;
BEGIN
 IF NEW.signal_id IS NULL THEN RETURN NEW; END IF;
 IF TG_OP='UPDATE' AND to_jsonb(OLD)=to_jsonb(NEW) THEN RETURN NEW; END IF;
 SELECT * INTO STRICT s FROM auto_signal WHERE id=NEW.signal_id;
 INSERT INTO signal_activity_audit(signal_id,channel,symbol,timeframe,event_type,actor,observed_at,reason,before_state,after_state)
 VALUES(s.id,signal_channel(s.strategy),s.symbol,s.timeframe,CASE WHEN TG_OP='INSERT' THEN 'BROKER_DEAL' ELSE 'BROKER_DEAL_UPDATED' END,'SYSTEM:BROKER_LEDGER',NEW.traded_at,'Broker-confirmed deal, including realized profit and execution charges',CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) END,to_jsonb(NEW));
 RETURN NEW;
END $$;
CREATE TRIGGER deal_audit AFTER INSERT OR UPDATE ON broker_deal FOR EACH ROW EXECUTE FUNCTION audit_deal_row();

CREATE FUNCTION audit_scan_row() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE channel_name TEXT;
BEGIN
 -- Repeated identical polling is counted in scan_state; retain each new
 -- closed bar, changed decision, or changed blocking condition as an event.
 IF TG_OP='UPDATE' AND NEW.last_bar IS NOT DISTINCT FROM OLD.last_bar
  AND NEW.reason IS NOT DISTINCT FROM OLD.reason AND NEW.quality=OLD.quality
  AND NEW.decision_context->'blocks' IS NOT DISTINCT FROM OLD.decision_context->'blocks'
  AND NEW.decision_context->'scan'->'dir' IS NOT DISTINCT FROM OLD.decision_context->'scan'->'dir'
  AND NEW.decision_context->'scalp'->'dir' IS NOT DISTINCT FROM OLD.decision_context->'scalp'->'dir'
  AND jsonb_path_query_array(NEW.decision_context,'$.scan.gates[*].ok')=jsonb_path_query_array(OLD.decision_context,'$.scan.gates[*].ok')
  AND jsonb_path_query_array(NEW.decision_context,'$.scalp.gates[*].ok')=jsonb_path_query_array(OLD.decision_context,'$.scalp.gates[*].ok')
 THEN RETURN NEW; END IF;
 channel_name=CASE WHEN NEW.timeframe='M5' AND NEW.decision_context->>'source' IS NOT NULL THEN 'LOW_PROFIT' ELSE 'AUTO' END;
 INSERT INTO signal_activity_audit(channel,symbol,timeframe,event_type,actor,observed_at,reason,before_state,after_state,decision_context)
 VALUES(channel_name,NEW.symbol,NEW.timeframe,'SCAN_EVALUATED',NEW.evaluated_by,NEW.last_scan_at,NEW.reason,CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD)-'decision_context' END,to_jsonb(NEW)-'decision_context',NEW.decision_context);
 RETURN NEW;
END $$;
CREATE TRIGGER scan_evaluation_audit AFTER INSERT OR UPDATE ON scan_state FOR EACH ROW EXECUTE FUNCTION audit_scan_row();

CREATE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Audit rows are append-only; UPDATE, DELETE and TRUNCATE are prohibited'; END $$;
CREATE TRIGGER immutable_signal_audit BEFORE UPDATE OR DELETE ON signal_activity_audit FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER no_truncate_signal_audit BEFORE TRUNCATE ON signal_activity_audit FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();
