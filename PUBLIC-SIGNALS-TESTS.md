# v28 independent public signals — verification

**Local verification:** 67 Java tests, 56 offline Python tests and 21 v28 formula/provenance checks passed. Frontend TypeScript checking and Vite production build passed.

Java tests ran using OpenJDK 17 with `-Djava.version=17`; the project/CI/container remain configured for Java 21. The external JDK download was unavailable here. Existing Mockito fixtures use its subclass mock maker to avoid sandbox JVM attachment limits.

No broker login or MT5 feed was used to create the public auto-signal fixtures or manual analysis. These synthetic fixtures validate software behavior, not win rate.

## Changes covered

- The copied v28 ready fixture scores 98/100 on BTC with all 12 gates passing in both Node and the embedded JavaScript engine. Each of 20 original function bodies matches its attachment-derived SHA-256 hash.
- Independent signal creation, public quote persistence, linked plan behavior prepared for PostgreSQL, same-bar deduplication, existing-setup blocking and executor-role isolation.
- Manual confirmed/fallback analysis, contradictory HTFs, missing/news-blackout calendar, stale reference data, current forming candle exclusion and malformed OHLC validation.
- Existing session persistence/CSRF, legacy strategy/quotes, order lifecycle, broker P&L ledger and demo executor tests retained.
- Added MT5 MARKET request tests: actual broker price, public/broker quote-gap rejection, broker stop/target attachment, no pending expiry requirement and strict Low Profit fee/$4-loss checks.

## Limits

- Docker and PostgreSQL/Flyway runtime were not executed locally. CI now includes an additional real PostgreSQL/container smoke test that creates a v28 public signal and linked plan without MT5. CI was not run: publishing the changes to the public repository was rejected by automatic approval review pending explicit authorization.
- No actual Windows MT5 order, broker fee schedule or live terminal connection was tested. The approved demo-only account and 0.01-lot execution policy remain unchanged.
- No browser rendering/UI automation was run here; TypeScript compilation and production bundling passed.
- Public Binance requests returned HTTP 451 in this workspace; gold-api.com spot returned HTTP 200. Feed restriction failures are displayed, not turned into synthetic signals. Provider access and background availability remain separate from engine correctness.
- No trained AI model, guaranteed $1 outcome or profitable accuracy is claimed.

## Java cases

- PASS — `ApiSecurityTest.invalidTradeRejected`
- PASS — `ApiSecurityTest.badPasswordDenied`
- PASS — `ApiSecurityTest.anonymousApiDenied`
- PASS — `ApiSecurityTest.validCredentialsAccepted`
- PASS — `BrokerLedgerServiceTest.wrongAccountCannotImportAnything`
- PASS — `BrokerLedgerServiceTest.duplicateDealUsesStableAccountServerTicketKeyAndKeepsLink`
- PASS — `DemoExecutionSecurityTest.malformedLeaseCannotClaimOwnership`
- PASS — `DemoExecutionSecurityTest.anonymousCannotReadExecutionConfig`
- PASS — `DemoExecutionSecurityTest.feedCanReadFixedVolumeAndSeparateLimits`
- PASS — `DemoExecutionServiceTest.releaseCannotClearAnotherWorkerLease`
- PASS — `DemoExecutionServiceTest.claimIsPersistedBeforeBrokerSubmission`
- PASS — `DemoExecutionServiceTest.expiredLeaseCanBeRecoveredAndOldOwnerCannotReport`
- PASS — `DemoExecutionServiceTest.duplicateCannotBeClaimed`
- PASS — `DemoExecutionServiceTest.wrongAccountOrServerCannotBeClaimed`
- PASS — `DemoExecutionServiceTest.gappedStaleExpiredAndDisabledSignalsCannotClaim`
- PASS — `DemoExecutionServiceTest.disabledBindingCannotClaim`
- PASS — `DemoExecutionServiceTest.competingWorkerCannotTakeValidLease`
- PASS — `FeedSecurityTest.tenCharacterAdminCanReadSignals`
- PASS — `FeedSecurityTest.feedHasAccessToFeedOnly`
- PASS — `FeedSecurityTest.feedCanReadServerTime`
- PASS — `FeedSecurityTest.anonymousFeedDenied`
- PASS — `FeedSecurityTest.feedCannotPostEmptyQuoteBatch`
- PASS — `FeedSecurityTest.anonymousCannotReadFeedTime`
- PASS — `OrderServiceTest.manualStopQueuesExactTargetsAndExpiry`
- PASS — `OrderServiceTest.staleQuoteAndDisabledFrameCannotQueue`
- PASS — `OrderServiceTest.repeatRequestReturnsSameSignalWithoutAnotherInsert`
- PASS — `OrderServiceTest.pendingCancelDoesNotCloseOpenPosition`
- PASS — `OrderServiceTest.limitAllowsPullbackEntryAndSingleFinalTarget`
- PASS — `OrderServiceTest.invalidLevelsAndWrongPendingSideCannotQueue`
- PASS — `PnlLedgerTest.kmrScopeExcludesUnlinkedBrokerTrades`
- PASS — `PnlLedgerTest.dailyNetIncludesCostsAndUsesChennaiDateBoundary`
- PASS — `PublicMarketSecurityTest.adminCanReadIndependentSourceStatus`
- PASS — `PublicMarketSecurityTest.anonymousCannotSubmitPublicCandles`
- PASS — `PublicMarketSecurityTest.executorCredentialCannotReadManualAnalysis`
- PASS — `PublicMarketSecurityTest.executorCredentialCannotCreatePublicSignals`
- PASS — `PublicSignalServiceTest.sameClosedBarNotInsertedTwice`
- PASS — `PublicSignalServiceTest.storesSignalAndReferenceQuoteWithoutAnyMt5Feed`
- PASS — `PublicSignalServiceTest.existingPublicSetupBlocksAnother`
- PASS — `PublicSignalServiceTest.mt5IngestIsExecutionOnlyInIndependentMode`
- PASS — `QuoteFreshnessTest.rejectsStaleQuoteBeforeAnyDatabaseWork`
- PASS — `QuoteFreshnessTest.rejectsFutureQuoteBeforeAnyDatabaseWork`
- PASS — `SecurityConfigTest.allowsFeedToRemainDisabled`
- PASS — `SecurityConfigTest.rejectsReservedAdminUsernameIgnoringCase`
- PASS — `SecurityConfigTest.rejectsSharedPasswords`
- PASS — `SecurityConfigTest.rejectsShortFeedPassword`
- PASS — `SecurityConfigTest.rejectsShortAdminPassword`
- PASS — `SecurityConfigTest.acceptsTenCharacterAdminAndSevenOrEightCharacterFeed(String)[1]`
- PASS — `SecurityConfigTest.acceptsTenCharacterAdminAndSevenOrEightCharacterFeed(String)[2]`
- PASS — `SessionPersistenceTest.reloadAuthenticatesUsingSessionWithoutPassword`
- PASS — `SessionPersistenceTest.cookieWritesRequireCsrfAndLogoutInvalidatesSession`
- PASS — `SessionPersistenceTest.feedCannotCreateBrowserAdminSession`
- PASS — `SignalStrategyTest.activationThenTargetsRequireLaterQuotes`
- PASS — `SignalStrategyTest.pendingExpiresWithoutClaimingEntry`
- PASS — `SignalStrategyTest.sellSetupStartsBelowCurrentBid`
- PASS — `SignalStrategyTest.rejectsWideSpreadAndFlagsQuoteGap`
- PASS — `SignalStrategyTest.buySetupIsPendingAboveCurrentAsk`
- PASS — `TradeRequestTest.buyAndSellValidateOppositeDirections`
- PASS — `TradeRequestTest.equalStopOrTargetRejected`
- PASS — `V28EngineTest.malformedOHLCRejected`
- PASS — `V28EngineTest.manualAnalysisDoesNotRequireBroker`
- PASS — `V28EngineTest.exactV28FixtureCreatesAutoWithoutMt5`
- PASS — `V28EngineTest.currentFormingBarExcluded`
- PASS — `V28EngineTest.highImpactNewsPreventsAuto`
- PASS — `V28EngineTest.staleHigherTimeframeBlocksAuto`
- PASS — `V28EngineTest.opposingHigherTimeframeShowsLowerConfidencePlan`
- PASS — `V28EngineTest.missingCalendarSuppressesAutoKeepsManual`
- PASS — `V28EngineTest.stalePublicQuoteRejected`

## Python cases

- PASS — `test_candle_conversion_preserves_month_and_weekend_gaps`
- PASS — `test_candles_and_quotes_use_same_conversion_and_keep_prices`
- PASS — `test_default_does_not_infer_offset_from_future_tick`
- PASS — `test_invalid_offset_is_rejected`
- PASS — `test_observed_octa_three_hour_offset_becomes_fresh`
- PASS — `test_old_quote_stays_old_after_conversion`
- PASS — `test_wrong_offset_still_rejects_future_quote`
- PASS — `test_all_slow_samples_fail_without_using_old_estimate`
- PASS — `test_cold_start_is_resampled`
- PASS — `test_server_clock_uses_monotonic_time_not_windows_clock`
- PASS — `test_timezone_is_required`
- PASS — `test_fresh_quote_preserves_broker_time`
- PASS — `test_future_quote_is_not_uploaded`
- PASS — `test_missing_and_invalid_quotes_wait`
- PASS — `test_old_quote_is_not_retimestamped`
- PASS — `test_transit_margin_boundaries`
- PASS — `test_windows_clock_drift_does_not_change_quote_age`
- PASS — `test_before_tp1_and_invalid_broker_distance_do_not_move_sl`
- PASS — `test_bound_demo_account_only`
- PASS — `test_broker_exit_deals_confirm_closure_not_signal_status`
- PASS — `test_disabled_frames_stale_gap_and_crossed_entries_rejected`
- PASS — `test_disabled_python_trading_blocks_write`
- PASS — `test_explicit_market_order_does_not_require_pending_expiry_feature`
- PASS — `test_low_profit_actual_contract_loss_cannot_exceed_four_usd`
- PASS — `test_low_profit_execution_requires_confirmed_fees`
- PASS — `test_no_volume_increase_or_gtc_fallback`
- PASS — `test_open_cap_cancels_owned_pending_without_closing_positions`
- PASS — `test_partial_exit_without_live_snapshot_remains_unknown`
- PASS — `test_pending_has_exact_volume_sl_tp2_and_expiry`
- PASS — `test_public_market_signal_rejects_broker_reference_gap`
- PASS — `test_public_market_signal_uses_actual_broker_quote`
- PASS — `test_rejected_record_after_restart_does_not_block_or_resend`
- PASS — `test_sell_stop_uses_bid_and_correct_levels`
- PASS — `test_separate_limits_per_commodity`
- PASS — `test_tp1_buy_protects_spread_and_gross_cushion`
- PASS — `test_tp1_sell_protection_and_no_loosen`
- PASS — `test_tp2_fallback_locks_tp1`
- PASS — `test_uncertain_send_and_restart_never_resubmit`
- PASS — `test_unknown_inventory_and_api_lease_prevent_submission`
- PASS — `test_history_failure_does_not_advance_cursor`
- PASS — `test_expired_pending_is_removed_at_broker`
- PASS — `test_four_targets_gap_jump_and_reversal_retain_highest_stage`
- PASS — `test_known_entry_commission_is_in_cushion`
- PASS — `test_manual_buy_and_sell_limit`
- PASS — `test_manual_cancel_pending_only_and_fill_race_keeps_open`
- PASS — `test_manual_prices_are_never_silently_rounded`
- PASS — `test_multi_target_sell_is_symmetric`
- PASS — `test_single_target_and_bad_target_order`
- PASS — `test_three_targets_close_at_final_and_lock_tp1_at_tp2`

- PASS — `ExecutionTest.test_market_partial_fill_is_reconciled_never_treated_as_rejected`

## Signal audit extension

The audit extension adds 10 Java tests (77 Java tests total), actor attribution, manual reason capture, and PostgreSQL channel/history assertions. See `SIGNAL-AUDIT.md`. Together with 56 offline broker tests and 21 v28 formula checks, 154 automated checks pass locally, plus the SQL migration/assertion suite and the frontend build. Local Java uses the available JDK 17 override; Java 21 and PostgreSQL 18 are checked by CI.
