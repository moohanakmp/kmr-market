# KMR demo broker execution

This update adds an opt-in Windows execution worker. It actually submits broker
pending orders when eligible signals are available. The market-data bridge stays
read-only. Execution is restricted to the configured USD hedging DEMO account;
real accounts, other logins, other servers and netting accounts are rejected.

## Policy requested by the user

- Exact volume: 0.01 lots. Instruments that cannot accept it are skipped; volume
  is never rounded up.
- Per broker instrument: up to 5 pending orders and up to 5 open positions,
  counted separately. Other instruments have their own limits. Manual orders
  and positions count toward the limits, but this worker never modifies them.
- At 5 open positions, stop new entries and attempt to cancel that instrument's
  remaining KMR pending orders. Broker fills occur independently and can happen
  before cancellations complete. This is a monitored limit, not an atomic broker
  guarantee; a burst of fills or external trades can exceed 5 open positions.
  Extra positions are not automatically closed.
- Enabled timeframes: M1, M5, M15, M30, H1, D1, W1, MN1.
- BUY STOP / SELL STOP with the signal's SL and broker take-profit at TP2.
  No market-order fallback if the entry has already passed.
- At TP1, attempt a tighter SL beyond the actual fill price. The buffer is the
  larger of current spread and the broker-calculated price distance for a $0.10
  gross profit cushion, plus known negative position swap. Tick rounding, minimum
  stop distance and freeze distance are checked. SL is never loosened.
- TP2 normally closes the full position on the broker. If it is still open when
  a TP2 quote is observed, try to lock SL at TP1 as a fallback while retaining
  TP2. There is no TP1 partial close and no runner beyond TP2.

The $0.10 cushion is a projected GROSS amount in a USD account. Commission,
unknown closing fees, slippage and gaps can still cause a net loss. Broker stop
constraints can delay or reject the change. The rule cannot guarantee no loss.

## One-time setup

1. Deploy the latest main commit. Flyway V4 creates execution records and a
   single-worker lease. No new DB grants are needed for the existing app owner.
2. In Render Environment set MT5_DEMO_ACCOUNT to your intended demo account
   number and MT5_DEMO_SERVER to the exact server shown in your MT5 title bar.
   Default account is 0, so execution claims are disabled until configured.
   Save and redeploy. Keep the existing MT5_FEED_PASSWORD.
3. On Windows MT5, sign into that DEMO account. Enable Algo Trading. In Tools >
   Options > Expert Advisors, allow algorithmic trading and uncheck "Disable
   automated trading via external Python API". Keep the protection that disables
   algorithmic trading when the account changes enabled. Use a dedicated demo
   terminal and do not switch accounts while execution is running.
4. Stop the old data bridge with Ctrl+C, then in your project folder run:

```powershell
git pull --ff-only
.\.venv-mt5\Scripts\python.exe -m pip install -r bridge/requirements.txt
.\.venv-mt5\Scripts\python.exe bridge/mt5_bridge.py --url https://kmr-market.onrender.com --broker-utc-offset-hours 3
```

5. Keep that window running. Open a SECOND PowerShell window in the same project
   folder, then replace DEMO_ACCOUNT_NUMBER with the same Render account number:

```powershell
.\.venv-mt5\Scripts\python.exe bridge/mt5_executor.py --url https://kmr-market.onrender.com --execute-demo --account DEMO_ACCOUNT_NUMBER --server OctaFX-Demo --broker-utc-offset-hours 3
```

Enter the same KMR feed password privately at each process's prompt. Broker
credentials remain in the MT5 terminal. Do not put broker passwords in Render,
command arguments or Git. Add --terminal with the exact terminal64.exe path if
there is more than one MT5 terminal. If using a symbol map, give the SAME mapping
file to both processes via --symbol-map bridge/symbol-map.json.

This startup flag submits DEMO broker orders. Keep MT5 and both processes
running. On a Windows VPS, run them inside that remote machine. Turning off the
machine stops entries and dynamic TP1 protection; already accepted broker SL,
TP2 and specified pending expiry remain on the broker.

## Eligibility and broker checks

Only PENDING, unexpired enabled-timeframe signals without a quote-gap flag and
with a fresh feed quote may be claimed. The worker also checks fresh broker
quotes, current spread, current capacity and broker stop constraints. Historical,
gapped, already-triggered and invalidated setups are not chased. On instruments
without specified pending expiry, it skips the entry instead of creating an
unbounded GTC order. Expiration uses the explicitly configured broker time offset.

## Actual broker state vs signal state

Trade plans now include MT5 execution status, order/position ticket, reported broker SL/TP, and a broker
message (hover over the execution cell). Signal status is still quote observation;
OPEN/CLOSED execution status comes from broker inventory/deal history. A planned
TP hit is not proof of a filled broker trade or realized profit.

The backend reserves each signal durably BEFORE sending. One signal is submitted
at most once automatically. Orders use a dedicated magic number and KMR signal
comment. Unknown replies and crashes are reconciled against broker orders,
positions and history; the worker never blindly retries an entry. An UNKNOWN
record requires inspection of actual MT5 history before any manual intervention.
Rejections and preflight problems print their reason; orders do not appear merely
because a signal is saved.

Only one worker may hold the backend lease, and only one local process may hold
the Windows lock. After an abnormal crash, another worker may have to wait up to
five minutes for the lease to expire. Normal Ctrl+C attempts to release it.

## Validation

Offline broker tests exercise volume, separate per-instrument limits, account
guards, pending expiry, both SL directions, freeze/stop limits, uncertain replies,
restart deduplication and TP2 retention. Java tests and PostgreSQL integration
test the claim lease, account binding, duplicate claim and broker-ticket display.
These tests do not connect to or place orders on any broker. Run a demo session
to validate Octa's actual symbol constraints, expiry convention and execution.

Primary references:
- https://www.mql5.com/en/docs/python_metatrader5/mt5ordersend_py
- https://www.mql5.com/en/docs/python_metatrader5/mt5ordercalcprofit_py
- https://www.mql5.com/en/docs/python_metatrader5/mt5ordercheck_py
