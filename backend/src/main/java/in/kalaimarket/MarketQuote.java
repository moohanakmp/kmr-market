package in.kalaimarket;
import jakarta.persistence.*;import java.time.Instant;import java.math.BigDecimal;
@Entity @Table(name="market_quote") public class MarketQuote {
 @Id public String symbol;@Column(precision=20,scale=8) public BigDecimal bid;@Column(precision=20,scale=8) public BigDecimal ask;
 @Column(name="quoted_at") public Instant quotedAt;
}
