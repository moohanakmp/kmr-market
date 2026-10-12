package in.kalaimarket;
import jakarta.persistence.*;import java.time.Instant;import java.math.BigDecimal;import java.util.*;import org.hibernate.annotations.JdbcTypeCode;import org.hibernate.type.SqlTypes;
@Entity @Table(name="broker_sync") public class BrokerSync {
 @Id public String id;@Column(name="synced_at") public Instant syncedAt;@Column(name="history_cursor") public Instant historyCursor;
 @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") public List<Map<String,Object>> positions=new ArrayList<>();
 @Column(precision=24,scale=8) public BigDecimal balance;@Column(precision=24,scale=8) public BigDecimal equity;
}
