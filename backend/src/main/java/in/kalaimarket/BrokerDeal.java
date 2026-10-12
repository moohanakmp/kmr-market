package in.kalaimarket;
import jakarta.persistence.*;import java.time.Instant;import java.math.BigDecimal;
@Entity @Table(name="broker_deal") public class BrokerDeal {
 @Id public String id;@Column(name="account_login") public long accountLogin;@Column(name="broker_server") public String brokerServer;
 public long ticket;@Column(name="order_ticket") public long orderTicket;@Column(name="position_identifier") public long positionIdentifier;
 @Column(name="signal_id") public Long signalId;@Column(name="traded_at") public Instant tradedAt;public String symbol;
 @Column(name="deal_type") public int dealType;@Column(name="entry_kind") public int entryKind;public int reason;
 @Column(precision=20,scale=8) public BigDecimal volume;@Column(precision=20,scale=8) public BigDecimal price;
 @Column(precision=24,scale=8) public BigDecimal profit;@Column(precision=24,scale=8) public BigDecimal commission;
 @Column(precision=24,scale=8) public BigDecimal swap;@Column(precision=24,scale=8) public BigDecimal fee;
 public BigDecimal getNet(){return profit.add(commission).add(swap).add(fee);}
}
