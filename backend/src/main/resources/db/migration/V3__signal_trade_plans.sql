ALTER TABLE trade_plan ADD COLUMN source_signal_id BIGINT;
ALTER TABLE trade_plan ADD CONSTRAINT trade_plan_signal_fk
 FOREIGN KEY (source_signal_id) REFERENCES auto_signal(id);
ALTER TABLE trade_plan ADD CONSTRAINT trade_plan_signal_unique UNIQUE(source_signal_id);

-- Create the plan atomically with every new signal, including writes from an
-- older app instance during a rolling deploy. Quote updates do not create plans.
CREATE FUNCTION save_signal_trade_plan() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 EXECUTE format(
  'INSERT INTO %I.trade_plan (symbol,direction,entry,stop_loss,target,created_at,source_signal_id)
   VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (source_signal_id) DO NOTHING',
  TG_TABLE_SCHEMA)
 USING NEW.symbol,NEW.direction,NEW.entry,NEW.stop_loss,NEW.tp2,NEW.created_at,NEW.id;
 RETURN NEW;
END;
$$;

CREATE TRIGGER auto_signal_saved_plan AFTER INSERT ON auto_signal
 FOR EACH ROW EXECUTE FUNCTION save_signal_trade_plan();

-- Existing manual plans keep their null source. Existing signal history gets
-- one linked saved plan without replaying entries, quotes, or broker orders.
INSERT INTO trade_plan (symbol,direction,entry,stop_loss,target,created_at,source_signal_id)
 SELECT symbol,direction,entry,stop_loss,tp2,created_at,id FROM auto_signal
 ON CONFLICT (source_signal_id) DO NOTHING;
