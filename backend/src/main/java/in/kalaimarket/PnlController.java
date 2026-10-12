package in.kalaimarket;
import java.time.*;import java.math.BigDecimal;import java.util.*;import org.springframework.web.bind.annotation.*;import org.springframework.data.domain.PageRequest;import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api") public class PnlController {
 private final DemoExecutionService execution;private final BrokerDealRepository deals;private final BrokerSyncRepository syncs;private final BrokerExecutionRepository executions;private final AutoSignalRepository signals;private final ExecutionEventRepository events;
 public PnlController(DemoExecutionService e,BrokerDealRepository d,BrokerSyncRepository s,BrokerExecutionRepository x,AutoSignalRepository a,ExecutionEventRepository v){execution=e;deals=d;syncs=s;executions=x;signals=a;events=v;}
 public static class Day {
  public String date;public BigDecimal profit=BigDecimal.ZERO,commission=BigDecimal.ZERO,swap=BigDecimal.ZERO,fee=BigDecimal.ZERO;public int exitDeals;
  public BigDecimal getNet(){return profit.add(commission).add(swap).add(fee);}
  public void add(BrokerDeal d){profit=profit.add(d.profit);commission=commission.add(d.commission);swap=swap.add(d.swap);fee=fee.add(d.fee);if((d.dealType==0||d.dealType==1)&&(d.entryKind==1||d.entryKind==3))exitDeals++;}
 }
 @GetMapping("/pnl") public Map<String,Object> pnl(@RequestParam(defaultValue="Asia/Kolkata") String zone,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="ACCOUNT") String scope){
  ZoneId tz;try{tz=ZoneId.of(zone);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid timezone");}
  if(!scope.equals("ACCOUNT")&&!scope.equals("KMR"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Scope must be ACCOUNT or KMR");
  if(from==null)from=LocalDate.now(tz);if(to==null)to=from;if(to.isBefore(from)||to.toEpochDay()-from.toEpochDay()>366)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose at most 367 days, with to on or after from");
  var daily=new TreeMap<String,Day>();var total=new Day();total.date="TOTAL";
  var data=deals.findByAccountLoginAndBrokerServerAndTradedAtGreaterThanEqualAndTradedAtLessThanOrderByTradedAtAsc(execution.getAccount(),execution.getServer(),from.atStartOfDay(tz).toInstant(),to.plusDays(1).atStartOfDay(tz).toInstant());
  for(var d:data){if(scope.equals("KMR")&&d.signalId==null)continue;var date=d.tradedAt.atZone(tz).toLocalDate().toString();var day=daily.computeIfAbsent(date,k->{var x=new Day();x.date=k;return x;});day.add(d);total.add(d);}
  var sync=syncs.findById(execution.getAccount()+":"+execution.getServer()).orElse(null);Map<String,Object> out=new LinkedHashMap<>();out.put("zone",zone);out.put("scope",scope);out.put("from",from);out.put("to",to);out.put("daily",daily.values());out.put("total",total);out.put("sync",sync);out.put("historyAvailable",sync!=null&&!sync.historyCursor.equals(Instant.EPOCH));out.put("stale",sync==null||sync.syncedAt.isBefore(Instant.now().minusSeconds(90)));return out;
 }
 @GetMapping("/deals") public List<BrokerDeal> deals(@RequestParam(defaultValue="0") int page){if(page<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page");return deals.recent(execution.getAccount(),execution.getServer(),PageRequest.of(page,100));}
 @GetMapping("/tracker") public List<Map<String,Object>> tracker(@RequestParam(defaultValue="0") int page){
  if(page<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page");return executions.findByAccountLoginAndBrokerServer(execution.getAccount(),execution.getServer(),PageRequest.of(page,100,org.springframework.data.domain.Sort.by("createdAt").descending())).stream().map(e->{Map<String,Object> out=new LinkedHashMap<>();out.put("execution",e);out.put("signal",signals.findById(e.signalId).orElseThrow());var net=deals.findBySignalIdOrderByTradedAtAsc(e.signalId).stream().map(BrokerDeal::getNet).reduce(BigDecimal.ZERO,BigDecimal::add);out.put("realizedNet",net);return out;}).toList();
 }
 @GetMapping("/tracker/{id}") public Map<String,Object> detail(@PathVariable long id){var e=executions.findBySignalId(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"No execution claim for this signal"));if(e.accountLogin!=execution.getAccount()||!e.brokerServer.equals(execution.getServer()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Different bound account");return Map.of("execution",e,"signal",signals.findById(id).orElseThrow(),"events",events.findBySignalIdOrderByIdAsc(id),"deals",deals.findBySignalIdOrderByTradedAtAsc(id));}
}
