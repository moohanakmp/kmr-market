package in.kalaimarket;

import java.math.BigDecimal;
import java.time.Instant;

/** Plan levels plus the linked signal's current observed status. */
public record TradePlanView(Long id, String symbol, TradePlan.Direction direction,
 BigDecimal entry, BigDecimal stopLoss, BigDecimal target, Instant createdAt,
 Long sourceSignalId, Timeframe timeframe, AutoSignal.Status status,
 BigDecimal tp1, Boolean trackingGap, Instant expiresAt) {
 public String getSource() {return sourceSignalId == null ? "MANUAL" : "MT5";}
 public BigDecimal getTp2() {return sourceSignalId == null ? null : target;}
}
