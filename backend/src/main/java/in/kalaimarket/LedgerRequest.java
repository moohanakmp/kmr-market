package in.kalaimarket;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.math.BigDecimal;import java.time.Instant;import java.util.*;
public record LedgerRequest(@NotNull @Pattern(regexp="[0-9a-fA-F-]{36}") String worker,long account,@NotNull @Size(max=80) String server,
 @NotNull Instant observedAt,Instant historyCursor,
 @NotNull @Size(max=200) List<@Valid Deal> deals,@NotNull @Size(max=500) List<Map<String,Object>> positions,
 @NotNull @Digits(integer=16,fraction=8) BigDecimal balance,@NotNull @Digits(integer=16,fraction=8) BigDecimal equity){
 public record Deal(@Positive long ticket,@PositiveOrZero long orderTicket,@PositiveOrZero long positionIdentifier,@Positive Long signalId,
  @NotNull Instant tradedAt,@NotNull @Size(max=20) String symbol,@Min(0) @Max(17) int dealType,@Min(0) @Max(3) int entryKind,@Min(0) int reason,
  @NotNull @PositiveOrZero @Digits(integer=12,fraction=8) BigDecimal volume,@NotNull @PositiveOrZero @Digits(integer=12,fraction=8) BigDecimal price,
  @NotNull @Digits(integer=16,fraction=8) BigDecimal profit,@NotNull @Digits(integer=16,fraction=8) BigDecimal commission,
  @NotNull @Digits(integer=16,fraction=8) BigDecimal swap,@NotNull @Digits(integer=16,fraction=8) BigDecimal fee){}
}
