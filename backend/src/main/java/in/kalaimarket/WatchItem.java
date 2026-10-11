package in.kalaimarket;
import jakarta.persistence.*;
@Entity @Table(name="watch_item") public class WatchItem {
 @Id public String symbol;
 public WatchItem() {} public WatchItem(String symbol) { this.symbol=symbol; }
}
