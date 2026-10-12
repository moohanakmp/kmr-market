package in.kalaimarket;
import java.util.*;
import java.math.BigDecimal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
@RestController @RequestMapping("/api/feed/execution") public class DemoExecutionController {
 private final DemoExecutionService service;private final AutoSignalRepository signals;private final BrokerLedgerService ledger;
 public DemoExecutionController(DemoExecutionService s,AutoSignalRepository r,BrokerLedgerService l){service=s;signals=r;ledger=l;}
 @GetMapping("/config") public Map<String,Object> config(){return Map.of("mode","DEMO_ONLY_V2","account",service.getAccount(),"server",service.getServer(),"volume",0.01,"maxPendingPerSymbol",5,"maxOpenPerSymbol",5,"takeProfit","FINAL_TARGET","profitCushionUsd",0.1,"timeframes",Timeframe.enabled());}
 @GetMapping("/signals") public List<AutoSignal> signals(){return signals.findByStatusAndTimeframeIn(AutoSignal.Status.PENDING,Timeframe.enabled(),PageRequest.of(0,250,Sort.by("createdAt").ascending()));}
 @GetMapping("/tracked") public List<Map<String,Object>> tracked(){return service.tracked();}
 public record Worker(@Pattern(regexp="[0-9a-fA-F-]{36}") @NotNull String worker) {}
 public record Claim(@Pattern(regexp="[0-9a-fA-F-]{36}") @NotNull String worker,long account,@Size(max=80) @NotNull String server,@Positive long signalId) {}
 public record Report(@Pattern(regexp="[0-9a-fA-F-]{36}") @NotNull String worker,@Positive long signalId,@NotNull BrokerExecution.Status status,@Positive Long orderTicket,@Positive Long positionTicket,@Size(max=255) @NotNull String message,@PositiveOrZero BigDecimal brokerStopLoss,@PositiveOrZero BigDecimal brokerTakeProfit,@Positive BigDecimal brokerEntryPrice,@Positive BigDecimal volume,BigDecimal floatingProfit,@Positive Long positionIdentifier,@Min(0) @Max(10) Integer reachedTarget,@Min(0) @Max(10) Integer protectedTarget,@Size(max=40) String closeReason) {}
 public record Note(@NotNull @Pattern(regexp="[0-9a-fA-F-]{36}") String worker,@Positive long signalId,@NotNull @Size(max=255) String message){}
 @PostMapping("/note") public Map<String,String> note(@Valid @RequestBody Note n){service.note(n.worker(),n.signalId(),n.message());return Map.of("status","NOTED");}
 @GetMapping("/history") public Map<String,Object> history(){return ledger.cursor();}
 @PostMapping("/ledger") public Map<String,Object> ledger(@Valid @RequestBody LedgerRequest r){return ledger.ingest(r);}
 @PostMapping("/lease") public Map<String,Object> lease(@Valid @RequestBody Worker r){return service.renew(r.worker());}
 @PostMapping("/release") public Map<String,String> release(@Valid @RequestBody Worker r){service.release(r.worker());return Map.of("status","RELEASED");}
 @PostMapping("/claim") public BrokerExecution claim(@Valid @RequestBody Claim r){return service.claim(r.worker(),r.account(),r.server(),r.signalId());}
 @PostMapping("/report") public BrokerExecution report(@Valid @RequestBody Report r){return service.report(r.worker(),r.signalId(),r.status(),r.orderTicket(),r.positionTicket(),r.message(),r.brokerStopLoss(),r.brokerTakeProfit(),r.brokerEntryPrice(),r.volume(),r.floatingProfit(),r.positionIdentifier(),r.reachedTarget(),r.protectedTarget(),r.closeReason());}
}
