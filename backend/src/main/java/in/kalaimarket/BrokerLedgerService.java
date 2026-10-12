package in.kalaimarket;
import java.util.*;import java.time.*;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;
@Service public class BrokerLedgerService {
 private final DemoExecutionService execution;private final BrokerDealRepository deals;private final BrokerSyncRepository syncs;private final BrokerExecutionRepository executions;
 public BrokerLedgerService(DemoExecutionService e,BrokerDealRepository d,BrokerSyncRepository s,BrokerExecutionRepository x){execution=e;deals=d;syncs=s;executions=x;}
 public String key(){return execution.getAccount()+":"+execution.getServer();}
 public Map<String,Object> cursor(){return Map.of("historyCursor",syncs.findById(key()).map(s->s.historyCursor).orElse(Instant.EPOCH),"identities",executions.findByAccountLoginAndBrokerServer(execution.getAccount(),execution.getServer(),org.springframework.data.domain.Pageable.unpaged()));}
 @Transactional public Map<String,Object> ingest(LedgerRequest r){
  execution.guard(r.worker());var now=Instant.now();
  if(execution.getAccount()<=0||r.account()!=execution.getAccount()||!r.server().equals(execution.getServer()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Ledger account binding mismatch");
  if(r.observedAt().isAfter(now.plusSeconds(30))||r.observedAt().isBefore(now.minusSeconds(120))||r.historyCursor()!=null&&r.historyCursor().isAfter(now.plusSeconds(30)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ledger UTC clock is stale or in the future");
  for(var x:r.deals()){
   if(x.tradedAt().isAfter(now.plusSeconds(30)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Deal time is in the future; verify broker offset");
   if(x.dealType()==2||x.dealType()==3||x.dealType()==5||x.dealType()==6)continue; // balance/credit/correction/bonus are not trading P&L
   var id=key()+":"+x.ticket();var d=deals.findById(id).orElseGet(BrokerDeal::new);d.id=id;d.accountLogin=r.account();d.brokerServer=r.server();d.ticket=x.ticket();d.orderTicket=x.orderTicket();d.positionIdentifier=x.positionIdentifier();
   if(x.signalId()!=null){var e=executions.findBySignalId(x.signalId()).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Deal signal has no execution claim"));if(e.accountLogin!=r.account()||!e.brokerServer.equals(r.server()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Deal execution account mismatch");d.signalId=x.signalId();}
   d.tradedAt=x.tradedAt();d.symbol=x.symbol();d.dealType=x.dealType();d.entryKind=x.entryKind();d.reason=x.reason();d.volume=x.volume();d.price=x.price();d.profit=x.profit();d.commission=x.commission();d.swap=x.swap();d.fee=x.fee();deals.save(d);
  }
  var sync=syncs.findById(key()).orElseGet(BrokerSync::new);sync.id=key();sync.syncedAt=r.observedAt();if(sync.historyCursor==null)sync.historyCursor=Instant.EPOCH;
  if(r.historyCursor()!=null&&r.historyCursor().isAfter(sync.historyCursor))sync.historyCursor=r.historyCursor();sync.positions=r.positions();sync.balance=r.balance();sync.equity=r.equity();syncs.save(sync);return Map.of("received",r.deals().size(),"syncedAt",sync.syncedAt);
 }
}
