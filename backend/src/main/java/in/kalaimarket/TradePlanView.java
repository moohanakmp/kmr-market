package in.kalaimarket;

import java.math.BigDecimal;
import java.time.Instant;

/** Plan levels plus the linked signal's current observed status. */
public record TradePlanView(Long id, String symbol, TradePlan.Direction direction,
 BigDecimal entry, BigDecimal stopLoss, BigDecimal target, Instant createdAt,
 Long sourceSignalId, Timeframe timeframe, AutoSignal.Status status,
 BigDecimal tp1, Boolean trackingGap, Instant expiresAt,
 BrokerExecution.Status brokerStatus, Long orderTicket, Long positionTicket, String brokerMessage,
 BigDecimal brokerStopLoss, BigDecimal brokerTakeProfit,
 String strategy, java.util.List<BigDecimal> targets, AutoSignal.PendingKind pendingKind,
 BigDecimal brokerEntryPrice, BigDecimal volume, Integer reachedTarget, Integer protectedTarget, String closeReason, Instant brokerUpdatedAt) {
 public String getSource() {return sourceSignalId == null || "MANUAL_PENDING_V1".equals(strategy) ? "MANUAL" : strategy!=null&&strategy.startsWith("KMR_V28")?"KMR PUBLIC":"MT5";}
 public BigDecimal getTp2() {return targets!=null&&targets.size()>1?targets.get(1):null;}
}
