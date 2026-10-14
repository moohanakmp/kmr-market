package in.kalaimarket;
import jakarta.persistence.*;import java.time.Instant;
@Entity @Table(name="public_market_state") public class PublicMarketState {
 @Id public String symbol;@Column(columnDefinition="text") public String snapshot;
 @Column(columnDefinition="text") public String analysis;@Column(name="updated_at") public Instant updatedAt;
}
