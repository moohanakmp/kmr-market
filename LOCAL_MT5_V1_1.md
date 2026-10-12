# KMR v1.1: local order synchronization and broker P&L

This version uses your existing configured USD hedging DEMO account. Native
Windows MT5, the quote bridge and the executor must run while the laptop is
awake and connected. Real accounts remain rejected. Browser reload now uses a
server session instead of losing the login held in page memory. Sign in once
after upgrading; normal reloads keep the session. Sessions expire after 30
minutes of inactivity and may be lost when the Render instance restarts.
Passwords are not stored in browser storage. Cookie writes require CSRF tokens.

## Update and start locally

Stop both existing Python windows with Ctrl+C. In the project folder:

```powershell
git pull --ff-only
.\.venv-mt5\Scripts\python.exe -m pip install -r bridge/requirements.txt
.\.venv-mt5\Scripts\python.exe bridge/mt5_bridge.py --url https://kmr-market.onrender.com --broker-utc-offset-hours 3
```

In another PowerShell window in the same folder, use your numeric demo account
number (replace DEMO_ACCOUNT_NUMBER, not a password):

```powershell
.\.venv-mt5\Scripts\python.exe bridge/mt5_executor.py --url https://kmr-market.onrender.com --execute-demo --account DEMO_ACCOUNT_NUMBER --server OctaFX-Demo --broker-utc-offset-hours 3
```

Enter Render's MT5_FEED_PASSWORD only at the hidden KMR password prompts. Keep
Render MT5_DEMO_ACCOUNT and MT5_DEMO_SERVER unchanged if they already match.
Use the same explicit --terminal path and symbol map on both workers if needed.
The known Octa raw timestamp conversion is UTC+3; verify the convention against
actual broker quotes/history rather than changing timestamps to pretend freshness.

The v2 execution API uses mode DEMO_ONLY_V2 and FINAL_TARGET. Old executors
pause when the new server mode appears, so git pull and restart are mandatory.
Existing broker orders retain their accepted SL/TP/expiry during the upgrade.
The migration preserves historical two-target orders; only new automatic setups
use three targets. It also preserves existing manual drafts without submitting
historical plans automatically.

## Optional automatic startup at Windows sign-in

Stop both manual Python windows before installing the supervised launcher:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\bridge\start_local.ps1 -Setup -InstallStartup
```

The setup asks for the numeric DEMO login, broker server, terminal64.exe path
and KMR feed password. It saves only the feed password as Windows DPAPI encrypted
text for this Windows user/machine, under LOCALAPPDATA\KMR-Market. Broker login
credentials remain in the native MT5 terminal. Configure MT5 to retain your demo
login, enable Algo Trading, and allow external Python trading. The Task Scheduler
entry KMR Local Demo runs interactively at Windows sign-in, starts MT5 and
supervises the two Python processes with retries after exits. It starts now too.
It does not bypass Windows login or require automatic Windows login.

Logs are in LOCALAPPDATA\KMR-Market\mt5_bridge.log and mt5_executor.log, with
separate *-error.log files. Worker logs restart when the worker process restarts;
the durable broker audit trail is in PostgreSQL. Read the last executor messages:

```powershell
Get-Content "$env:LOCALAPPDATA\KMR-Market\mt5_executor.log" -Tail 30
Get-Content "$env:LOCALAPPDATA\KMR-Market\mt5_executor-error.log" -Tail 30
```

To stop its owned workers and scheduled launcher:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\bridge\start_local.ps1 -Stop
```

The installed task remains enabled for the next sign-in; disable KMR Local Demo
in Task Scheduler if you want automatic startup disabled. If setup cannot register
a task under your Windows policy, use the two manual worker commands above.
The launcher is syntax-checked in CI, but Windows task registration/DPAPI and your
specific MT5 login still need verification on your laptop.

## Create manual orders

Add the exact instrument to Watchlist and wait for a fresh quote. The new manual
form requires side, STOP or LIMIT pending type, entry, SL, ordered target prices,
timeframe and pending expiry. Click Create and queue MT5 demo order.

- BUY STOP is above Ask; SELL STOP is below Bid.
- BUY LIMIT is below Ask; SELL LIMIT is above Bid.
- BUY targets strictly increase above entry; SELL targets strictly decrease below
  entry. Support is 1–10 targets. The final target is broker take-profit.
- Manual prices must match the broker's tick size. Unsupported 0.01 volume, margin,
  stop distance, freeze limits, unavailable symbols or unsupported specified expiry
  prevent submission. There is no market-order or GTC fallback.
- A saved historical manual draft has Queue in MT5; provide its pending type,
  timeframe, targets and expiry before submitting. The same saved plan is linked
  to the request instead of creating another plan row.
- Durable request IDs and execution claims prevent automatic duplicate sends,
  including after a lost HTTP/broker reply. Unknown replies are reconciled with
  broker inventory/history and never retried as new entries.

## Lifecycle

| KMR action or state | Broker synchronization |
| --- | --- |
| Eligible automatic setup or submitted manual request | Queue one pending order with 0.01 lots, SL, final TP and specified expiry |
| Broker accepts pending order | BROKER_PENDING plus ticket and broker entry/SL/TP |
| Broker fills pending order | OPEN plus actual fill price and position identifier |
| Pending expiry | EXPIRED after broker expiry/removal is confirmed |
| Pending setup stopped/invalidated | Remove the owned pending order; record STOPPED/CANCELLED |
| Cancel pending button | Request cancellation; record confirmed removal |
| Position hits broker SL | STOPPED with STOP_LOSS reason after full exit deals |
| Position hits final TP | CLOSED with FINAL_TARGET reason after full exit deals |

Signal observation remains separate from broker state. A candle/quote entry touch
is not proof of a broker fill. A cancellation racing a fill does not close the
new open position; its broker SL/final TP remain. Expiry applies to pending orders,
not to open positions. Linked plans and events remain for audit and cannot be
deleted using Remove draft. The executor manages only its KMR magic/tickets;
it reports direct MT5 trades for account P&L without managing those trades.

Limits remain separate per broker instrument: 5 pending and 5 open positions.
All account orders/positions count toward capacity; direct MT5 trades are not
modified. At 5 open positions, KMR stops entries and attempts to remove its
remaining pending orders. Fill/cancellation races can exceed this monitored
limit; the worker does not close extra positions automatically.

## Multiple targets

For new automatic setups, existing 1R and 2R levels become intermediate TP1/TP2;
a 3R level is final TP3. This is a mechanical extension, not a claim that the
strategy is profitable. Manual orders use the target prices you enter.

| Milestone | Action |
| --- | --- |
| TP1, with later targets remaining | Attempt SL beyond actual fill using the larger of spread and a broker-calculated $0.10 cushion, covering known entry costs and negative swap |
| TP2, with later targets remaining | Attempt SL at TP1 |
| TP3, with later targets remaining | Attempt SL at TP2 |
| Each later intermediate TP | Attempt SL at the previous target |
| Final target | Broker TP closes the entire remaining position |

Highest observed stage is retained after a pullback/restart. A jump across
several targets protects the highest previous target whose stop can be accepted.
SL is never loosened. Reached and Protected columns distinguish observed target
hits from broker-accepted stop changes. SL tightening may be deferred by broker
stop/freeze limits. No TP partial closes are performed. A single target simply
closes at that target. Final TP remains on the broker while intermediate SL
management requires the running local executor. Missing the quote of a brief
intrabar TP touch can delay protection: a polling bridge is not an atomic EA.
Commission on closing, slippage, gaps and changing costs can still cause a net
loss; the minimum cushion is not a no-loss guarantee.

## PostgreSQL P&L and complete tracker

Flyway V5 adds target arrays, request IDs, execution fields, market quotes,
execution_event, broker_deal and broker_sync. No manual SQL changes are required.
The worker initially imports available broker-retained deal history and then
syncs an overlapping recent window about every 30 seconds. Deals are upserted
by account/server/ticket, not appended on every sync. History progress advances
only after acknowledged batches. A stopped laptop leaves an explicitly stale
snapshot; history backfill reconciles broker exits when the worker returns.

Daily P&L defaults to Asia/Kolkata dates. Net is profit + signed commission +
signed swap + signed fee, booked on each deal's day. Entry commission may make a
day negative before a trade closes; partial exits count as exit deals rather than
as multiple completed trades. Deposits, credit, bonuses and balance corrections
are excluded. Account charge/dividend/tax deals are shown as supplied by MT5.
Choose ACCOUNT for the whole bound account or KMR for linked deals. Broker
account adjustments that cannot be linked to a position are excluded from KMR
scope. Floating position profit/swap and account balance/equity are snapshots,
not substitutes for realized results.

The tracker preserves pending, fill, SL changes, reached/protected stages,
cancellation and closure events with actual broker tickets, prices and exit
reasons. Historical executions imported at V5 have a marked baseline event;
missing earlier transitions are not invented. Open positions, the paginated
account deal ledger and KMR execution history remain accessible beyond the
latest 100 trade plans. Broker history availability limits retrospective data.
Corrections to deals older than the overlapping window require a full resync:
restart the executor with its usual arguments plus --resync-history.

API endpoints: GET /api/pnl?from=YYYY-MM-DD&to=YYYY-MM-DD&zone=Asia/Kolkata&scope=ACCOUNT,
GET /api/deals?page=0, GET /api/tracker?page=0 and GET /api/tracker/{signalId}.
POST /api/orders/manual queues manual requests; POST /api/orders/{id}/cancel
requests pending cancellation. Feed-only ledger endpoints retain HTTP Basic
credentials; browser endpoints use a cookie session and CSRF protection.

Validation uses offline fake MT5 tests plus Java/security tests and a real
PostgreSQL production-container integration test. Those tests do not place
broker orders. Confirm one demo pending order, fill, target protection and
closure against actual MT5 history before depending on this workflow.
