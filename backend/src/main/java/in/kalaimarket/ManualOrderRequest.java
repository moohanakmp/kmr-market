package in.kalaimarket;
import java.math.BigDecimal;import java.time.Instant;import java.util.*;import jakarta.validation.constraints.*;
public record ManualOrderRequest(@NotNull UUID requestKey,@Positive Long planId,
 @NotBlank @Pattern(regexp="[A-Z0-9._-]{2,20}") String symbol,@NotNull TradePlan.Direction direction,
 @NotNull Timeframe timeframe,@NotNull AutoSignal.PendingKind pendingKind,
 @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=8) BigDecimal entry,
 @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=8) BigDecimal stopLoss,
 @NotNull @Size(min=1,max=10) List<@NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=8) BigDecimal> targets,
 @NotNull @Future Instant expiresAt){
 public boolean validLevels(){
  boolean buy=direction==TradePlan.Direction.BUY;if(buy?stopLoss.compareTo(entry)>=0:stopLoss.compareTo(entry)<=0)return false;
  var prior=entry;for(var t:targets){if(buy?t.compareTo(prior)<=0:t.compareTo(prior)>=0)return false;prior=t;}return true;
 }
}
