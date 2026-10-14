package in.kalaimarket;
import java.time.*;import java.util.*;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;
@Service public class OrderService {
 private final AutoSignalRepository signals;private final TradeRepository plans;private final FeedLockRepository locks;private final MarketQuoteRepository quotes;private final BrokerExecutionRepository executions;
 public OrderService(AutoSignalRepository s,TradeRepository p,FeedLockRepository l,MarketQuoteRepository q,BrokerExecutionRepository e){signals=s;plans=p;locks=l;quotes=q;executions=e;}
 private PublicMarketRepository publicMarket; private com.fasterxml.jackson.databind.ObjectMapper auditJson;
 @org.springframework.beans.factory.annotation.Autowired void publicEvidence(PublicMarketRepository market,com.fasterxml.jackson.databind.ObjectMapper json){publicMarket=market;auditJson=json;}
 private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
 @Transactional public AutoSignal manual(ManualOrderRequest r){
  if(!r.validLevels()||!Timeframe.enabled().contains(r.timeframe()))throw bad("Use enabled timeframe and strictly ordered targets beyond entry; stop must be on the opposite side");
  if(r.expiresAt().isAfter(Instant.now().plus(Duration.ofDays(31))))throw bad("Expiry must be within 31 days");
  if(locks.lock(r.symbol()).isEmpty())throw bad("Add instrument to watchlist and run the feed first");
  var existing=signals.findByRequestKey(r.requestKey());if(existing.isPresent())return existing.get();
  if(r.planId()!=null){var p=plans.findById(r.planId()).orElseThrow(()->bad("Saved plan not found"));if(p.sourceSignalId!=null)throw bad("Plan already has an order request; do not submit it twice");}
  var q=quotes.findById(r.symbol()).orElseThrow(()->bad("Wait for a fresh public reference price"));var now=Instant.now();
  if(q.quotedAt.isBefore(now.minusSeconds(90))||q.quotedAt.isAfter(now.plusSeconds(30)))throw bad("Fresh reference price required before queueing a manual order; MT5 is needed only for execution");
  boolean buy=r.direction()==TradePlan.Direction.BUY;var market=buy?q.ask:q.bid;
  if(r.pendingKind()!=AutoSignal.PendingKind.MARKET&&(r.pendingKind()==AutoSignal.PendingKind.STOP?(buy?r.entry().compareTo(market)<=0:r.entry().compareTo(market)>=0):(buy?r.entry().compareTo(market)>=0:r.entry().compareTo(market)<=0)))throw bad("STOP entries must be beyond current price; LIMIT entries must be on the pullback side");
  if(r.pendingKind()==AutoSignal.PendingKind.MARKET&&r.entry().subtract(market).abs().doubleValue()>Math.abs(r.entry().subtract(r.stopLoss()).doubleValue())*.25)throw bad("Manual market plan moved too far from the public reference price");
  var s=new AutoSignal();s.symbol=r.symbol();s.direction=r.direction();s.timeframe=r.timeframe();s.pendingKind=r.pendingKind();s.entry=r.entry();s.stopLoss=r.stopLoss();s.targets=List.copyOf(r.targets());s.tp1=s.targets.get(0);s.tp2=s.targets.get(Math.min(1,s.targets.size()-1));s.strategy="MANUAL_PENDING_V1";s.reason="Manual order: operator selected direction and price levels";s.decisionContext=Map.of("decisionType","HUMAN_ENTERED","explanation","Operator selected these levels; no indicator validation is implied", "referenceSource",q.source,"referenceQuoteAt",q.quotedAt.toString(),"referenceBid",q.bid,"referenceAsk",q.ask,"requestKey",r.requestKey().toString());s.decisionContext=new LinkedHashMap<>(s.decisionContext);
  if(r.decisionReason()!=null&&!r.decisionReason().isBlank()){s.decisionContext.put("operatorReason",r.decisionReason().trim());s.reason=r.decisionReason().trim().substring(0,Math.min(255,r.decisionReason().trim().length()));}
  if(publicMarket!=null)publicMarket.findById(r.symbol()).ifPresent(row->{try{s.decisionContext.put("referenceAnalysis",auditJson.readTree(row.analysis));s.decisionContext.put("referenceAnalysisAt",row.updatedAt.toString());s.decisionContext.put("referenceAnalysisNote","Last available analysis, not proof these manually entered levels passed validation");}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Cannot preserve manual decision evidence",ex);}});
  s.quality=0;s.requestKey=r.requestKey();s.manualPlanId=r.planId();s.sourceBar=now;s.createdAt=now;s.updatedAt=now;s.lastQuoteAt=q.quotedAt;s.expiresAt=r.expiresAt();return signals.saveAndFlush(s);
 }
 @Transactional public void cancel(long id){var s=signals.findById(id).orElseThrow(()->bad("Order request not found"));
  var e=executions.findBySignalId(id);if(e.isPresent()&&e.get().status==BrokerExecution.Status.OPEN)throw new ResponseStatusException(HttpStatus.CONFLICT,"Position is already open; pending cancellation cannot close it");
  s.cancelRequested=true;if(s.status==AutoSignal.Status.PENDING)s.status=AutoSignal.Status.CANCELLED;s.updatedAt=Instant.now();
 }
}
