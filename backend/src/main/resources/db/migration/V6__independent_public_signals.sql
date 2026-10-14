CREATE TABLE public_market_state(symbol VARCHAR(20) PRIMARY KEY,snapshot TEXT NOT NULL,analysis TEXT NOT NULL,updated_at TIMESTAMPTZ NOT NULL);
ALTER TABLE market_quote ADD COLUMN source VARCHAR(80) NOT NULL DEFAULT 'MT5';
ALTER TABLE auto_signal ADD COLUMN source_atr DOUBLE PRECISION CHECK(source_atr IS NULL OR source_atr>0);
