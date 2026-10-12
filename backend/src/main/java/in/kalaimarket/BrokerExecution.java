package in.kalaimarket;
import jakarta.persistence.*;
import java.time.Instant;
import java.math.BigDecimal;
@Entity @Table(name="broker_execution") public class BrokerExecution {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(name="signal_id") public Long signalId;
 @Column(name="account_login") public long accountLogin;
 @Column(name="broker_server") public String brokerServer;
 public String worker;
 @Enumerated(EnumType.STRING) public Status status;
 @Column(name="order_ticket") public Long orderTicket;
 @Column(name="position_ticket") public Long positionTicket;
 @Column(name="broker_stop_loss",precision=20,scale=8) public BigDecimal brokerStopLoss;
 @Column(name="broker_take_profit",precision=20,scale=8) public BigDecimal brokerTakeProfit;
 public String message;
 @Column(name="created_at") public Instant createdAt;
 @Column(name="updated_at") public Instant updatedAt;
 public enum Status { CLAIMED, BROKER_PENDING, OPEN, CLOSED, CANCELLED, REJECTED, UNKNOWN }
}
