# Signal tables and activity audit

Flyway migration V7 creates three **real, separate tables**:

| Table | Signal origin |
| --- | --- |
| `auto_signals` | Automatic public-data signals and legacy automatic strategies |
| `manual_signals` | Orders explicitly submitted by the authenticated operator |
| `low_profit_signals` | KMR v28 scalp signals |

Each contains the signal ID, symbol, strategy, creator, initiator, creation time, validity deadline, status, reason, original decision evidence and latest meaningful lifecycle snapshot. The existing `auto_signal` table remains the common execution queue so saved plans, order IDs, broker tickets and historical P&L keep their original links. An ID belongs to exactly one channel; changing a signal's origin is rejected.

## Who, when, why

`signal_activity_audit` is an append-only journal maintained by PostgreSQL triggers. It records:

- **Who:** authenticated manual username, automatic strategy identity, initiating username when public snapshots come from a signed-in browser, or reporting MT5 worker ID. Credentials, tokens and broker passwords are never audit evidence.
- **When:** UTC database `occurred_at` and source `observed_at`, plus creation time, source candle, requested `expires_at`, activation and update times in the snapshots. `EXPIRED` records when the expiry transition was persisted, which may be later than its deadline.
- **Why:** public signal decisions retain the exact engine output, EMA20/50/200, RSI, ADX, MACD, trend, volume estimates, individual gates, news/time checks, source and planned levels available to the engine. Missing/uncomputed indicators remain missing. The score is a rule score, not a probability of profit.
- **Manual intent:** optional operator reason and exact chosen prices; the latest available public analysis is retained with its own timestamp and clearly identified as reference evidence. Manual input is not automatically claimed to have passed the engine's gates.
- **Lifecycle:** creation, activation, target progress, stop, missed entry, cancellation request, expiry, tracking gaps and execution notes, with before/after state.
- **Broker activity:** claim, accepted/pending order, fill, unknown/rejected state, stop/target protection changes, closure and confirmed deals including profit/commission/swap/fees. A reference-price signal outcome is not a broker fill or a realized profit.
- **Diagnostics:** new closed-bar decisions, changed blocking conditions, manual analysis previews, public feed failure/recovery. Previews never create orders. Repeated unchanged polls increment `scan_state.evaluation_count` without creating duplicate audit decisions; heartbeat-only quote and floating-P&L refreshes are not order lifecycle events.

Audit rows reject UPDATE, DELETE and TRUNCATE. Original creator/initiator/evidence cannot be rewritten. This protects normal application operations; a PostgreSQL owner/superuser can deliberately alter triggers, so this is not external tamper-proof storage. No audit purge is scheduled. Monitor SQL storage usage as history grows; a free hosting quota is finite.

## Existing records

The additive migration backfills all three channel tables and adds `LEGACY_BASELINE` and existing `LEGACY_BROKER_EVENT` records. Unknown old creators are `LEGACY_UNKNOWN`. Migration recording time and any known original observation time are separate. Old EMA values or expiry-transition timestamps are **not reconstructed or invented**.

## Read history

The signed-in UI has **Signal audit history**, with channel, symbol and signal-ID filters, older-page loading, reasons, and expandable before/after/indicator evidence. Times display in the device timezone.

Authenticated ADMIN endpoint:

```
GET /api/audit?channel=AUTO&symbol=BTCUSD&signalId=123&limit=50
GET /api/audit?channel=LOW_PROFIT&eventType=EXPIRED&beforeId=5000&limit=50
```

`limit` is 1–200. Use returned `nextBeforeId` to page backwards; it is null on the last page. The broker FEED credential cannot read this endpoint. There is no audit mutation endpoint.

Useful SQL (set your deployment schema in `search_path` first):

```sql
SELECT * FROM auto_signals ORDER BY created_at DESC;
SELECT * FROM manual_signals ORDER BY created_at DESC;
SELECT * FROM low_profit_signals ORDER BY created_at DESC;
SELECT occurred_at, observed_at, actor, event_type,
       previous_status, new_status, reason,
       decision_context #>> '{scan,e20}' AS ema20,
       after_state->>'expires_at' AS expires_at
FROM signal_activity_audit WHERE signal_id = 123 ORDER BY id;
```

## Validation

- Seven migrations and audit SQL assertions run locally on PGlite's embedded PostgreSQL engine, including historical backfill, channel separation, indicator preservation, bulk expiry without JPA callbacks, manual cancellation identity, broker fills/protected stops/closure, realized deal costs, scan decisions, heartbeat deduplication, and mutation rejection.
- `scripts/test-signal-audit.sh` runs the same migrations/assertions on PostgreSQL 18 in GitHub CI.
- Java tests check admin-only access, denied anonymous/feed access, bounded keyset pagination, parameterized filters, and actor attribution. Public signal tests assert persisted EMA/gate evidence.
- Existing Java, offline broker, formula provenance checks and frontend production build are also required. See `PUBLIC-SIGNALS-TESTS.md` for the prior signal-engine tests and execution limits.

Deployment applies V7 automatically after V6. Back up production data before an operator-managed rollout. These tests do not prove live broker fills, strategy profitability, or availability of a blocked public feed.
