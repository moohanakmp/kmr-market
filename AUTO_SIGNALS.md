# KMR MT5 auto signals

This update adds a read-only MT5 market-data bridge and a Java signal engine.
Signals are plans tracked against sampled broker quotes, not filled broker orders.
It does not reuse or migrate the old Netlify scanner algorithm.

## One-time setup

1. In Render Environment, create a separate `MT5_FEED_PASSWORD` (at least 7
   characters). Save and redeploy the latest main commit. Feed username is fixed
   as `mt5_feed`. Do not use the admin or broker password as the feed password.
   Admin password `ADMIN_PASSWORD` accepts 10 or more characters.
   No new database grants are needed; Flyway V2 adds tables to the existing kmr
   schema using your existing DB_SCHEMA=kmr and kmr_user credentials.
2. On **Windows**, install Python 3.11 x64 and your broker's MT5 desktop terminal.
   Log into the broker in MT5 and leave it open. This bridge reads the existing
   logged-in terminal, so no broker password goes into KMR or the bridge.
3. In Windows PowerShell:

   ```powershell
   winget install --exact --id Python.Python.3.11
   git clone https://github.com/moohanakmp/kmr-market.git
   cd kmr-market
   py -3.11 -m venv .venv-mt5
   .\.venv-mt5\Scripts\python.exe -m pip install -r bridge/requirements.txt
   .\.venv-mt5\Scripts\python.exe bridge/mt5_bridge.py --url https://YOUR_APP.onrender.com
   ```

   If you already cloned the repository, use `git pull` instead of cloning again.
   Enter the Render MT5_FEED_PASSWORD privately when the bridge prompts.
   **Use Windows Python, not WSL Python**, for the local MT5 integration.
4. Refresh KMR and open Auto Signals. Feed should become Live and Scanner activity
   should show your instruments/timeframes. The bridge follows the app watchlist.

If broker symbols differ (XAUUSDm, GOLD etc.), copy
`bridge/symbol-map.example.json` to `bridge/symbol-map.json` and edit its values to
match the exact names in MT5 Market Watch. Keep canonical keys from KMR watchlist.
Run:

```powershell
.\.venv-mt5\Scripts\python.exe bridge/mt5_bridge.py --url https://YOUR_APP.onrender.com --symbol-map bridge/symbol-map.json
```

For several MT5 terminals, specify:

```powershell
.\.venv-mt5\Scripts\python.exe bridge/mt5_bridge.py --url https://YOUR_APP.onrender.com --terminal "C:\Program Files\YOUR BROKER MT5\terminal64.exe"
```

## Timeframes and history

All 21 standard MT5 timeframes are supported:
M1, M2, M3, M4, M5, M6, M10, M12, M15, M20, M30,
H1, H2, H3, H4, H6, H8, H12, D1, W1, MN1.
The same strategy runs independently on each timeframe.
At least 220 closed bars are required for EMA200. Some brokers do not supply
that much weekly/monthly history; those streams wait rather than inventing data.
Open the symbol/timeframe chart and load history in MT5 if the bridge reports
insufficient bars. Only closed candles are sent; the current forming bar is excluded.

The default bridge setting expects UTC broker timestamps. It derives each
closedAt from the next bar's open time, including calendar months and market gaps.
The bridge synchronizes against authenticated KMR server UTC and uses a monotonic
clock, so Windows clock drift does not change quote age. It checks freshness before
every candle and quote upload, leaving transit margin within the backend limit
(90 seconds old / 30 seconds ahead).
Deploy the latest backend before updating the bridge; /api/feed/time is required.
A timestamp problem prints converted quote UTC, KMR UTC, and age in seconds.
Positive age means an old quote; negative age means the quote is ahead of KMR.
When markets are closed, quotes become stale and the UI reports stale / market closed.

### Broker timestamps with an observed UTC offset

The Octa demo logs supplied on 2026-10-11 showed continuously advancing BTCUSD
quote timestamps nearly exactly three hours ahead of KMR UTC (age about -10798s).
For that observed timestamp convention, stop the bridge, run git pull, and start:

```powershell
.\.venv-mt5\Scripts\python.exe bridge/mt5_bridge.py --url https://kmr-market.onrender.com --broker-utc-offset-hours 3
```

This explicit setting subtracts three hours from BOTH quote timestamps and all
candle open/close timestamps, leaving prices and time intervals unchanged.
It does not replace quote time with the current time: old quotes remain old and
are still rejected. No Render environment variable change is required.
Default is zero; keep zero for terminals already returning UTC. The offset must
match the broker's observed timestamp convention and may change with daylight
saving time. An incorrect offset continues to fail freshness checks; it is never
automatically inferred from a cached quote.

## Strategy and lifecycle

Strategy identifier: EMA_BREAKOUT_V1. BUY requires EMA20 > EMA50, price > EMA200,
and a closed candle above the preceding ten candle highs. SELL is the mirror.
ATR14 measures range; spread must be <= 15% of ATR, and the current price must be
within 0.75 ATR of the last close. Quality (70–100) counts rule confluence, candle
body and spread conditions. It is not calibrated confidence or a win probability.
This initial strategy has not been backtested against your broker.

Entry is beyond both the last candle extreme and current executable quote, with
0.1 ATR buffer. Stop is 1.5 ATR away, TP1 is 1R, TP2 is 2R.
Pending setups expire after three nominal timeframe lengths (MN1 uses 30 days
per nominal month). One open setup per symbol/timeframe; source-candle uniqueness
and row locking prevent duplicate setups from replayed uploads.

- PENDING: wait for a **later** quote to reach entry.
- ACTIVE: entry observed on a later quote.
- TP1/TP2/STOPPED: observed quote reaches target/stop after activation.
- MISSED: first triggering quote is already beyond TP1; no immediate TP profit.
- EXPIRED: pending deadline passed or price reached invalidation before entry.

The bridge samples quotes (default every 15 seconds); it is not a complete tick
recorder. Stops/targets crossed between samples may be missed. Gaps over 120 seconds
are flagged and remain visible. Statuses do not represent actual execution, fills,
slippage or realized P&L. Historical candle highs/lows do not retroactively trigger
new entries. Opposing setups on different timeframes may coexist.

## Runtime and free-tier limits

Keep the Windows PC awake, MT5 logged in, and the bridge process running.
If the PC/bridge stops, live scans and quote tracking stop. Render and Neon free
services can sleep and have quotas; an always-on cloud MT5 terminal is not supplied.
The bridge retries transient connections and displays stale data rather than
silently pretending to keep scanning. It sends full candles only when a closed bar
changes, plus small quote batches. Many symbols and timeframes increase bandwidth,
CPU and database storage; start with the four instruments in your watchlist.

Database retention is currently unbounded; the UI shows the latest 250 signals.
Monitor Neon usage and archive old signals before approaching storage limits.
Do not add fake keepalive traffic to bypass provider policy.

## Local development

Add MT5_FEED_PASSWORD to your shell environment when running Spring Boot. For
Docker Compose, add MT5_FEED_PASSWORD to .env and pass it via the supplied compose
entry. Local bridge can target `http://localhost:8080`; public URLs must be HTTPS.

## API and credentials

Feed user can access only:
- GET /api/feed/symbols
- GET /api/feed/time (server UTC for freshness checks)
- POST /api/feed/candles
- POST /api/feed/quotes

Admin views /api/signals and /api/scanner and retains existing watchlist/plans.
Credentials are Basic auth over HTTPS. The bridge prompts for its password and
never stores it in source. Environment variable MT5_FEED_PASSWORD is optionally
accepted for controlled process automation. Redirects are disabled, so feed
credentials are not forwarded to a different host.

No order_send / trade execution function is called. Broker integration for
execution requires a separate implementation and explicit demo-account validation.

## Automatically saved trade plans

Every newly generated signal automatically saves one linked trade plan in the
same database transaction. V3 also creates plans for signals already stored
before this update, preserving their original creation times and current states.
Existing manual plans are preserved. No broker order is submitted.

Trade plans show source (MT5/manual), timeframe, entry, SL, TP1, TP2, current
signal status and quote-gap flag. The list refreshes every 15 seconds and shows
the latest 100 plans. The saved target column holds TP2 for automatic plans;
TP1 and tracking metadata are read from the linked signal to avoid state drift.
Replay uploads and quote updates do not create duplicate plans. A unique signal
reference enforces one saved plan per signal. Removing a saved plan does not
cancel or delete its signal; later quote updates do not recreate a removed plan.

Deploy the latest backend and refresh the app. Existing bridge commands and
Render credentials do not change for this feature.
