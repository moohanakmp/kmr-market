package in.kalaimarket;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
@Service public class DemoExecutionService {
 public final long account;
 public final String server;
 private final ExecutionWorkerRepository workers;
 private final BrokerExecutionRepository executions;
 private final AutoSignalRepository signals;
 public DemoExecutionService(ExecutionWorkerRepository w,BrokerExecutionRepository e,AutoSignalRepository s,@Value("${MT5_DEMO_ACCOUNT:0}") long account,@Value("${MT5_DEMO_SERVER:OctaFX-Demo}") String server){workers=w;executions=e;signals=s;this.account=account;this.server=server;}
 public long getAccount(){return account;}
 public String getServer(){return server;}
 private ExecutionWorker lease(String worker){
  var row=workers.lockWorker().orElseThrow();
  if(row.owner!=null&&!row.owner.equals(worker)&&row.leaseUntil.isAfter(Instant.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Another demo executor owns the account lease");
  row.owner=worker;row.leaseUntil=Instant.now().plusSeconds(300);return row;
 }
 @Transactional public Map<String,Object> renew(String worker){return Map.of("leaseUntil",lease(worker).leaseUntil);}
 @Transactional public void release(String worker){var row=workers.lockWorker().orElseThrow();if(worker.equals(row.owner)){row.owner=null;row.leaseUntil=Instant.now();}}
 private void guard(String worker){
  var row=workers.lockWorker().orElseThrow();
  if(!worker.equals(row.owner)||!row.leaseUntil.isAfter(Instant.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Renew the demo executor lease first");
 }
 @Transactional public BrokerExecution claim(String worker,long account,String server,long id){
  if(this.account<=0||account!=this.account||!this.server.equals(server))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Configure MT5_DEMO_ACCOUNT and MT5_DEMO_SERVER; only that demo account is allowed");
  guard(worker);
  if(executions.findBySignalId(id).isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"Signal already claimed; reconcile broker history, do not resend");
  var s=signals.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Signal not found"));var now=Instant.now();
  if(!Timeframe.enabled().contains(s.timeframe)||s.status!=AutoSignal.Status.PENDING||s.trackingGap||!s.expiresAt.isAfter(now)||s.lastQuoteAt.isBefore(now.minusSeconds(90))||s.lastQuoteAt.isAfter(now.plusSeconds(30)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Signal is disabled, expired, triggered, gapped or lacks a fresh quote");
  var e=new BrokerExecution();e.signalId=id;e.accountLogin=account;e.brokerServer=server;e.worker=worker;e.status=BrokerExecution.Status.CLAIMED;e.message="Reserved before broker submission; never automatically resend";e.createdAt=now;e.updatedAt=now;
  return executions.save(e);
 }
 @Transactional public BrokerExecution report(String worker,long id,BrokerExecution.Status status,Long order,Long position,String message,BigDecimal sl,BigDecimal tp){
  guard(worker);var e=executions.findBySignalId(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Execution not found"));
  if(e.accountLogin!=account||!server.equals(e.brokerServer))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Execution belongs to a different configured account");
  e.worker=worker;e.status=status;if(order!=null)e.orderTicket=order;if(position!=null)e.positionTicket=position;if(sl!=null)e.brokerStopLoss=sl;if(tp!=null)e.brokerTakeProfit=tp;e.message=message;e.updatedAt=Instant.now();return e;
 }
 public List<Map<String,Object>> tracked(){
  return executions.findByStatusIn(List.of(BrokerExecution.Status.CLAIMED,BrokerExecution.Status.UNKNOWN,BrokerExecution.Status.BROKER_PENDING,BrokerExecution.Status.OPEN)).stream().filter(e->e.accountLogin==account&&server.equals(e.brokerServer)).map(e->{
   var s=signals.findById(e.signalId).orElseThrow();Map<String,Object> out=new HashMap<>();out.put("execution",e);out.put("signal",s);return out;
  }).toList();
 }
}
