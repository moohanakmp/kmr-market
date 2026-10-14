# Public KMR signals → MT5 execution only

## What the attached v28 app actually does

The original app makes ordinary public HTTP requests from the browser; it never needs an MT5 account to calculate signals. BTC uses Binance BTCUSDT spot OHLCV. Gold/silver use Binance XAUUSDT/XAGUSDT perpetual OHLCV rebased to gold-api.com spot prices; gold has an explicitly labeled PAXG fallback. The TradingView chart/gauge is an embedded display and does not provide numbers to KMR's engine.

The auto strategy uses **closed 15m candles**, independent **1H/4H trends**, EMA20/50/200, RSI14, MACD, ADX, pivot structure, pullbacks to value, order blocks and liquidity sweeps. Its 12 gates are higher-timeframe agreement, asset volatility band, ADX≥20, extension≤1.6 ATR, no directional spike body>1.5 ATR, RSI exhaustion, USD news, metals rollover 02:00–04:00 IST, opposing volume pressure, a value zone, a trigger candle and non-opposing 15m structure. All gates must pass; score≥70 gives MED and ≥84 HIGH. The score is a rules score, not a trained win probability.

Volume pressure is estimated from the candle close's location in its range, weighted by traded candle volume. Estimated delta/OBV/divergence is not a true aggressor footprint. None of these source formulas were replaced with the newer Java EMA-breakout rules. Twenty original functions are embedded using GraalJS and individually hashed in `v28-provenance.json`; no old passwords/login code were copied.

Auto v28 recalculates a structural plan from the live reference price: the stop is buffered beyond structure and bounded by 1–2.5 ATR; TP1 is 1R, TP2 is the next suitable liquidity level or 2R, TP3 extends to at least 3R. It rejects a late live entry and waits instead of auto-entering a stretched LIMIT plan. Manual analysis uses the same engine and returns a plan even with failed confirmation gates; it labels that **LOWER_CONFIDENCE** and requires an explicit manual decision before queueing.

The optional Low Profit scan copies v28's three closed-5m setups: trend pullback, momentum breakout, range/Bollinger fade. The reference target is $1 here; original v28's default was $2. The analysis uses v28's reference contract sizes/spread estimates. **Actual MT5 execution remains fixed at the previously approved 0.01 lot.** A Low Profit candidate can fail actual broker checks; the executor requires an explicitly confirmed fee estimate, at least $1 estimated net reward and no more than $4 estimated loss on the actual broker contract. Losses/slippage remain possible. There is no random-profit mechanism.

## What changed in the Java app

- Public server scanner and browser collector supply prices + closed OHLCV independently of MT5. The Java service runs the copied v28 JavaScript formulas and persists analyses/signals/plans in PostgreSQL.
- A public reference quote is labeled `PUBLIC_REFERENCE`; it is **not a broker bid/ask quote**. Signal observation and actual broker fills are separate.
- The browser uses the same direct public requests as the attached website and submits raw candles through the authenticated, CSRF-protected admin API. These uploaded prices/candles are validated but are not cryptographically attested provider data; keep admin access private. The executor role cannot create or read public-analysis submissions.
- Server collection continues without a browser only where its public providers are reachable. Missing/stale prices, history and calendars block fresh auto entries. HTTP 451/429/other failures are shown; no VPN, restricted mirror rotation or invented candles are used. A browser collector requires the browser to remain open and public endpoints to be reachable there. It does not provide an always-on cloud data SLA.
- The original manual preview remains analysis only. Choosing a plan fills the manual form; pressing Create/queue is the explicit order request. Manual hand-entered orders can also use the public reference price without MT5 connected.
- Fresh auto MARKET setups are stored as PENDING execution requests for 3 minutes (2 minutes for scalps). A reference quote cannot fabricate a broker fill. MARKET uses a broker market request only when explicitly specified; LIMIT/STOP keep their pending semantics. Expired, stopped or cancelled unfilled requests are removed at the broker when the worker is online. Orders/positions still reconcile using broker history and the existing lease/idempotency mechanisms.
- The executor rechecks current actual bid/ask, reference-to-broker drift, lot step, fill policy, margin/order-check, stop/target distances and account identity. No conversion from an invalid pending entry into a market order is allowed.
- Session restoration and current order/P&L tracking remain in place. Existing data is preserved by additive Flyway migration V6. Existing EMA strategy history stays labeled as legacy MT5 history; no historic loss is deleted.

## Configure and run

Default: `KMR_PUBLIC_SIGNALS_ENABLED=true`. No broker login is needed for public analysis. Keep XAUUSD, XAGUSD and/or BTCUSD in the watchlist. The background server fetcher starts automatically. Open KMR → Auto Signals to see public-data readiness/errors and Manual analysis to review the 12 gates and plan.

Optional Low Profit scanner: set `KMR_PUBLIC_SCALPS_ENABLED=true` on the Java server. For executing those candidates, set `MT5_ROUND_TRIP_FEE_PER_LOT_USD` on the Windows executor to the broker's verified total per-lot round-trip fees (zero only if verified zero). This does not change the demo-only account policy or 0.01-lot policy.

For MT5, use your existing approved demo account/server and private KMR feed password. Start only the executor:

```powershell
.\.venv-mt5\Scripts\python.exe bridge/mt5_executor.py --url https://kmr-market.onrender.com --execute-demo --account YOUR_APPROVED_DEMO_ACCOUNT --server OctaFX-Demo --broker-utc-offset-hours YOUR_VERIFIED_OFFSET
```

Do not paste literal placeholder strings; retain your existing verified account/offset. The launcher `bridge/start_local.ps1` now starts only the executor by default. Stop/restart old workers after pulling; the existing encrypted local configuration remains compatible. `-LegacyFeed` is available only for deliberately using the older server feed mode; that is not needed for v28 public signals.

The broker execution password is still named `MT5_FEED_PASSWORD` for compatibility, but now authorizes only the existing execution/reconciliation endpoints. It is not a broker login password. Never post it to chat. The existing server `MT5_DEMO_ACCOUNT` and `MT5_DEMO_SERVER` must match your approved demo account. No credentials or account settings are reset by this change.

## Validation and limits

See `PUBLIC-SIGNALS-TESTS.md`. Independent public analysis and signal creation are tested with fictional public snapshots, without connecting an MT5 account. Broker execution is tested with a fake broker. Actual provider access, Windows demo fills, Docker/PostgreSQL deployment and the Java-21 CI environment need their respective live checks. No improvement in trading accuracy or profit is established by code parity.
