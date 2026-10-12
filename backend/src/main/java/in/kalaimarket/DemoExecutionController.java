package in.kalaimarket;
import java.util.*;
import java.math.BigDecimal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
@RestController @RequestMapping("/api/feed/execution") public class DemoExecutionController {
 private final DemoExecutionService service;private final AutoSignalRepository signals;
 public DemoExecutionController(DemoExecutionService s,AutoSignalRepository r){service=s;signals=r;}
 @GetMapping("/config") public Map<String,Object> config(){return Map.of("mode","DEMO_ONLY","account",service.getAccount(),"server",service.getServer(),"volume",0.01,"maxPendingPerSymbol",5,"maxOpenPerSymbol",5,"takeProfit","TP2","profitCushionUsd",0.1,"timeframes",Timeframe.enabled());}
 @GetMapping("/signals") public List<AutoSignal> signals(){return signals.findByTimeframeIn(Timeframe.enabled(),PageRequest.of(0,250,Sort.by("createdAt").descending()));}
 @GetMapping("/tracked") public List<Map<String,Object>> tracked(){return service.tracked();}
 public record Worker(@Pattern(regexp="[0-9a-fA-F-]{36}") @NotNull String worker) {}
 public record Claim(@Pattern(regexp="[0-9a-fA-F-]{36}") @NotNull String worker,long account,@Size(max=80) @NotNull String server,@Positive long signalId) {}
 public record Report(@Pattern(regexp="[0-9a-fA-F-]{36}") @NotNull String worker,@Positive long signalId,@NotNull BrokerExecution.Status status,@Positive Long orderTicket,@Positive Long positionTicket,@Size(max=255) @NotNull String message,@PositiveOrZero BigDecimal brokerStopLoss,@PositiveOrZero BigDecimal brokerTakeProfit) {}
 @PostMapping("/lease") public Map<String,Object> lease(@Valid @RequestBody Worker r){return service.renew(r.worker());}
 @PostMapping("/release") public Map<String,String> release(@Valid @RequestBody Worker r){service.release(r.worker());return Map.of("status","RELEASED");}
 @PostMapping("/claim") public BrokerExecution claim(@Valid @RequestBody Claim r){return service.claim(r.worker(),r.account(),r.server(),r.signalId());}
 @PostMapping("/report") public BrokerExecution report(@Valid @RequestBody Report r){return service.report(r.worker(),r.signalId(),r.status(),r.orderTicket(),r.positionTicket(),r.message(),r.brokerStopLoss(),r.brokerTakeProfit());}
}
