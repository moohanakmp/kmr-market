package in.kalaimarket;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
@Entity @Table(name="auto_signal") public class AutoSignal {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 public String symbol;
 @Enumerated(EnumType.STRING) public Timeframe timeframe;
 @Enumerated(EnumType.STRING) public TradePlan.Direction direction;
 @Enumerated(EnumType.STRING) public Status status=Status.PENDING;
 @Column(precision=20,scale=8) public BigDecimal entry;
 @Column(name="stop_loss",precision=20,scale=8) public BigDecimal stopLoss;
 @Column(precision=20,scale=8) public BigDecimal tp1;
 @Column(precision=20,scale=8) public BigDecimal tp2;
 public int quality;
 public String reason;
 public String strategy="EMA_BREAKOUT_V1";
 @Column(name="source_bar") public Instant sourceBar;
 @Column(name="created_at") public Instant createdAt;
 @Column(name="expires_at") public Instant expiresAt;
 @Column(name="activated_at") public Instant activatedAt;
 @Column(name="updated_at") public Instant updatedAt;
 @Column(name="last_quote_at") public Instant lastQuoteAt;
 @Column(name="tracking_gap") public boolean trackingGap;
 public enum Status {PENDING, ACTIVE, TP1, TP2, STOPPED, MISSED, EXPIRED}
 public static BigDecimal price(double v){return BigDecimal.valueOf(v).setScale(8,RoundingMode.HALF_UP);}
}
