package in.kalaimarket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public record FeedRequest(
 @Pattern(regexp="[A-Z0-9._-]{2,20}") @NotNull String symbol,
 @NotNull Timeframe timeframe,
 @Valid @NotNull Quote quote,
 @Size(min=220,max=300) @NotNull List<@Valid Candle> candles) {
 public record Quote(@NotNull Instant time,@Positive double bid,@Positive double ask) {}
 public record Candle(@NotNull Instant time,@NotNull Instant closedAt,@Positive double open,@Positive double high,@Positive double low,@Positive double close) {}
 public record QuoteItem(@Pattern(regexp="[A-Z0-9._-]{2,20}") @NotNull String symbol,@Valid @NotNull Quote quote) {}
 public record QuoteBatch(@Size(min=1,max=50) @NotNull List<@Valid QuoteItem> quotes) {}
}
