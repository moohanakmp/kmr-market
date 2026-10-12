package in.kalaimarket;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
@Entity @Table(name="trade_plan") public class TradePlan {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 public String symbol;
 @Enumerated(EnumType.STRING) public Direction direction;
 @Column(precision=20,scale=8) public BigDecimal entry;
 @Column(name="stop_loss",precision=20,scale=8) public BigDecimal stopLoss;
 @Column(precision=20,scale=8) public BigDecimal target;
 @Column(name="created_at") public Instant createdAt=Instant.now();
 @Column(name="source_signal_id") public Long sourceSignalId;
 public enum Direction { BUY, SELL }
}
