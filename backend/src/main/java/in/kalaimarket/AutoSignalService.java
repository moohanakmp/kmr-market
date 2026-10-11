package in.kalaimarket;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service public class AutoSignalService {
 private static final List<AutoSignal.Status> OPEN=List.of(AutoSignal.Status.PENDING,AutoSignal.Status.ACTIVE,AutoSignal.Status.TP1);
 private final FeedLockRepository locks;private final AutoSignalRepository signals;private final ScanStateRepository scans;private final SignalStrategy strategy;
 public AutoSignalService(FeedLockRepository l,AutoSignalRepository s,ScanStateRepository f,SignalStrategy e){locks=l;signals=s;scans=f;strategy=e;}
 private void reject(String reason){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
 private void lock(String symbol){if(locks.lock(symbol).isEmpty())reject("Add symbol to watchlist first");}
 private void validateQuote(FeedRequest.Quote q,Instant now){
  if(!Double.isFinite(q.bid())||!Double.isFinite(q.ask())||q.bid()<=0||q.ask()<q.bid())reject("Invalid bid/ask");
  if(q.time().isBefore(now.minusSeconds(90))||q.time().isAfter(now.plusSeconds(30)))reject("Quote is stale or in the future: quote UTC="+q.time()+", server UTC="+now+", age seconds="+Duration.between(q.time(),now).toSeconds());
 }
 private void observe(String symbol,FeedRequest.Quote q,Instant now){
  for(var s:signals.findBySymbolAndStatusIn(symbol,OPEN))strategy.observe(s,q,now);
  for(var state:scans.findBySymbol(symbol))if(state.lastQuoteAt==null||q.time().isAfter(state.lastQuoteAt)){state.lastQuoteAt=q.time();state.receivedAt=now;}
 }
 @Transactional public Map<String,Object> ingest(FeedRequest r){
  Instant now=Instant.now();validateQuote(r.quote(),now);lock(r.symbol());
  Instant last=null;
  for(var c:r.candles()){
   if(!Double.isFinite(c.open())||!Double.isFinite(c.high())||!Double.isFinite(c.low())||!Double.isFinite(c.close())||c.low()<=0||c.low()>Math.min(c.open(),c.close())||c.high()<Math.max(c.open(),c.close())||!c.closedAt().isAfter(c.time())||c.closedAt().isAfter(r.quote().time())||(last!=null&&c.time().isBefore(last)))reject("Candles must be closed, chronological, non-overlapping and have valid OHLC values");
   last=c.closedAt();
  }
  String key=r.symbol()+"|"+r.timeframe();var state=scans.findById(key).orElseGet(ScanState::new);
  if(state.lastBar!=null&&!last.isAfter(state.lastBar)){observe(r.symbol(),r.quote(),now);return Map.of("status","UNCHANGED");}
  // Only evaluate a current closed bar. Historical seed data must not create signals.
  long maxAge=r.timeframe()==Timeframe.MN1?40L*86400:r.timeframe().seconds*2;
  if(last.isBefore(now.minusSeconds(maxAge)))reject("Latest candle is too old for this timeframe");
  observe(r.symbol(),r.quote(),now);
  var a=strategy.analyze(r.candles(),r.quote());state.id=key;state.symbol=r.symbol();state.timeframe=r.timeframe();state.lastBar=last;state.lastScanAt=now;state.lastQuoteAt=r.quote().time();state.receivedAt=now;state.quality=a.quality();state.reason=a.reason();
  boolean alreadyOpen=signals.findBySymbolAndStatusIn(r.symbol(),OPEN).stream().anyMatch(s->s.timeframe==r.timeframe());
  if(a.direction()!=null&&!alreadyOpen&&!signals.existsBySymbolAndTimeframeAndSourceBar(r.symbol(),r.timeframe(),last)){
   var s=strategy.create(r.symbol(),r.timeframe(),r.candles(),r.quote(),a,now);
   if(s.entry.signum()>0&&s.stopLoss.signum()>0&&s.tp1.signum()>0&&s.tp2.signum()>0&&(s.direction==TradePlan.Direction.BUY?s.stopLoss.compareTo(s.entry)<0&&s.entry.compareTo(s.tp1)<0&&s.tp1.compareTo(s.tp2)<0&&s.entry.doubleValue()>r.quote().ask():s.tp2.compareTo(s.tp1)<0&&s.tp1.compareTo(s.entry)<0&&s.entry.compareTo(s.stopLoss)<0&&s.entry.doubleValue()<r.quote().bid())){signals.save(s);state.reason="New "+s.direction+" setup; waiting for entry";}
   else state.reason="Levels too small or outside valid price range";
  }else if(alreadyOpen)state.reason="Existing setup is still being tracked";
  scans.save(state);return Map.of("status","SCANNED","reason",state.reason,"quality",state.quality);
 }
 @Transactional public void quotes(FeedRequest.QuoteBatch batch){
  Instant now=Instant.now();
  // Deterministic order prevents cross-symbol deadlocks between batches.
  for(var item:batch.quotes().stream().sorted(Comparator.comparing(FeedRequest.QuoteItem::symbol)).toList()){
   validateQuote(item.quote(),now);lock(item.symbol());observe(item.symbol(),item.quote(),now);
  }
 }
}
