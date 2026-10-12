package in.kalaimarket;
import jakarta.persistence.*;import java.time.Instant;import java.math.BigDecimal;
@Entity @Table(name="execution_event") public class ExecutionEvent {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(name="signal_id") public Long signalId;public String status;public String message;
 @Column(name="observed_at") public Instant observedAt;
 @Column(name="stop_loss",precision=20,scale=8) public BigDecimal stopLoss;
 @Column(name="take_profit",precision=20,scale=8) public BigDecimal takeProfit;
 @Column(name="reached_target") public int reachedTarget;@Column(name="protected_target") public int protectedTarget;
}
