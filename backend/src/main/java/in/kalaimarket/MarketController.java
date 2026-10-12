package in.kalaimarket;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
@RestController @RequestMapping("/api") public class MarketController {
 private final TradeRepository trades; private final WatchRepository watch;
 public MarketController(TradeRepository trades,WatchRepository watch) { this.trades=trades;this.watch=watch; }
 @GetMapping("/me") public Map<String,String> me() { return Map.of("app","KMR Market","version","1.0.0","mode","AUTO_AND_MANUAL_PLANS"); }
 @GetMapping("/plans") public List<TradePlanView> plans() { return trades.findPlanViews(PageRequest.of(0,100)); }
 @PostMapping("/plans") @ResponseStatus(HttpStatus.CREATED) public TradePlan create(@Valid @RequestBody TradeRequest r) {
  if(!r.validLevels()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"BUY: stop < entry < target; SELL: target < entry < stop");
  TradePlan p=new TradePlan();p.symbol=r.symbol();p.direction=r.direction();p.entry=r.entry();p.stopLoss=r.stopLoss();p.target=r.target();return trades.save(p);
 }
 @DeleteMapping("/plans/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { trades.deleteById(id); }
 public record WatchRequest(@NotBlank @Pattern(regexp="[A-Z0-9._-]{2,20}") String symbol) {}
 @GetMapping("/watchlist") public List<WatchItem> watchlist() { return watch.findAll(Sort.by("symbol")); }
 @PostMapping("/watchlist") public WatchItem add(@Valid @RequestBody WatchRequest r) { return watch.save(new WatchItem(r.symbol())); }
 @DeleteMapping("/watchlist/{symbol}") @ResponseStatus(HttpStatus.NO_CONTENT) public void remove(@PathVariable String symbol) { watch.deleteById(symbol); }
}
