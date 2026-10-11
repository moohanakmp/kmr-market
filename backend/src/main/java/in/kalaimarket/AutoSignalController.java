package in.kalaimarket;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
@RestController public class AutoSignalController {
 private final AutoSignalService service;private final AutoSignalRepository signals;private final ScanStateRepository scans;private final WatchRepository watch;
 public AutoSignalController(AutoSignalService s,AutoSignalRepository r,ScanStateRepository f,WatchRepository w){service=s;signals=r;scans=f;watch=w;}
 @GetMapping("/api/feed/symbols") public List<String> symbols(){return watch.findAll(Sort.by("symbol")).stream().map(w->w.symbol).toList();}
 @GetMapping("/api/feed/time") public Map<String,Object> feedTime(){return Map.of("serverTime",Instant.now());}
 @PostMapping("/api/feed/candles") public Map<String,Object> ingest(@Valid @RequestBody FeedRequest r){return service.ingest(r);}
 @PostMapping("/api/feed/quotes") public Map<String,String> quotes(@Valid @RequestBody FeedRequest.QuoteBatch r){service.quotes(r);return Map.of("status","ACCEPTED");}
 @GetMapping("/api/signals") public List<AutoSignal> signals(){return signals.findAll(PageRequest.of(0,250,Sort.by("createdAt").descending())).getContent();}
 @GetMapping("/api/scanner") public Map<String,Object> scanner(){
  return Map.of("strategy","EMA_BREAKOUT_V1","mode","OBSERVED_QUOTES","timeframes",Timeframe.values(),"serverTime",Instant.now(),"scans",scans.findAll(Sort.by("symbol","timeframe")));
 }
}
