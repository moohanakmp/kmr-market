package in.kalaimarket;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record TradeRequest(@NotBlank @Pattern(regexp="[A-Z0-9._-]{2,20}") String symbol,
 @NotNull TradePlan.Direction direction,
 @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=8) BigDecimal entry,
 @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=8) BigDecimal stopLoss,
 @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=12,fraction=8) BigDecimal target) {
 public boolean validLevels() {
  return direction==TradePlan.Direction.BUY ? stopLoss.compareTo(entry)<0 && target.compareTo(entry)>0 : stopLoss.compareTo(entry)>0 && target.compareTo(entry)<0;
 }
}
