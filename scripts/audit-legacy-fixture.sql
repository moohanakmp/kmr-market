INSERT INTO auto_signal(symbol,timeframe,direction,status,entry,stop_loss,tp1,tp2,quality,reason,strategy,source_bar,created_at,expires_at,updated_at,last_quote_at)
VALUES ('LEGACYAUTO','M15','BUY','EXPIRED',100,90,110,120,80,'Old setup','EMA_BREAKOUT_V1',now()-interval '1 day',now()-interval '1 day',now()-interval '23 hours',now()-interval '23 hours',now()-interval '23 hours'),
('LEGACYMAN','M15','BUY','PENDING',100,90,110,120,0,'Old manual','MANUAL_PENDING_V1',now(),now(),now()+interval '1 hour',now(),now()),
('LEGACYSCALP','M5','BUY','PENDING',100,90,110,120,0,'Old scalp','KMR_V28_SCALP',now(),now(),now()+interval '1 hour',now(),now());
