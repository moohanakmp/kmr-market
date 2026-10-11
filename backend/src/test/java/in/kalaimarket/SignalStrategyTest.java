package in.kalaimarket;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SignalStrategyTest {
 private final SignalStrategy engine=new SignalStrategy();
 private final Instant now=Instant.parse("2026-10-11T12:00:00Z");
 List<FeedRequest.Candle> bars(boolean buy){
  var bars=new ArrayList<FeedRequest.Candle>();
  for(int i=0;i<260;i++){double price=2000+(buy?1:-1)*i*0.02;
   double close=price+(i==259?(buy?3:-3):0);
   bars.add(new FeedRequest.Candle(now.minusSeconds((260-i)*60L),now.minusSeconds((259-i)*60L),price,Math.max(price,close)+0.1,Math.min(price,close)-0.1,close));}
  return bars;
 }
 @Test void buySetupIsPendingAboveCurrentAsk(){
  var b=bars(true);double last=b.get(259).close();var q=new FeedRequest.Quote(now,last,last+0.01);var a=engine.analyze(b,q);
  assertEquals(TradePlan.Direction.BUY,a.direction());var s=engine.create("XAUUSD",Timeframe.M1,b,q,a,now);
  assertEquals(AutoSignal.Status.PENDING,s.status);assertTrue(s.entry.doubleValue()>q.ask());assertTrue(s.stopLoss.compareTo(s.entry)<0);assertTrue(s.tp1.compareTo(s.entry)>0);
  engine.observe(s,new FeedRequest.Quote(now.plusSeconds(1),s.tp1.doubleValue()+0.1,s.tp1.doubleValue()+0.11),now.plusSeconds(1));
  assertEquals(AutoSignal.Status.MISSED,s.status);assertNull(s.activatedAt);
 }
 @Test void sellSetupStartsBelowCurrentBid(){var b=bars(false);double last=b.get(259).close();var q=new FeedRequest.Quote(now,last,last+0.01);var a=engine.analyze(b,q);assertEquals(TradePlan.Direction.SELL,a.direction());var s=engine.create("XAUUSD",Timeframe.M15,b,q,a,now);assertTrue(s.entry.doubleValue()<q.bid());assertTrue(s.stopLoss.compareTo(s.entry)>0);assertTrue(s.tp2.compareTo(s.tp1)<0);}
 @Test void activationThenTargetsRequireLaterQuotes(){
  var b=bars(true);double last=b.get(259).close();var q=new FeedRequest.Quote(now,last,last+0.01);var s=engine.create("XAUUSD",Timeframe.M1,b,q,engine.analyze(b,q),now);
  engine.observe(s,new FeedRequest.Quote(now.plusSeconds(1),s.entry.doubleValue(),s.entry.doubleValue()+0.01),now.plusSeconds(1));assertEquals(AutoSignal.Status.ACTIVE,s.status);
  engine.observe(s,new FeedRequest.Quote(now.plusSeconds(1),s.tp2.doubleValue(),s.tp2.doubleValue()+0.01),now.plusSeconds(1));assertEquals(AutoSignal.Status.ACTIVE,s.status);
  engine.observe(s,new FeedRequest.Quote(now.plusSeconds(2),s.tp1.doubleValue(),s.tp1.doubleValue()+0.01),now.plusSeconds(2));assertEquals(AutoSignal.Status.TP1,s.status);
  engine.observe(s,new FeedRequest.Quote(now.plusSeconds(3),s.tp2.doubleValue(),s.tp2.doubleValue()+0.01),now.plusSeconds(3));assertEquals(AutoSignal.Status.TP2,s.status);
 }
 @Test void rejectsWideSpreadAndFlagsQuoteGap(){var b=bars(true);double last=b.get(259).close();assertNull(engine.analyze(b,new FeedRequest.Quote(now,last,last+10)).direction());var q=new FeedRequest.Quote(now,last,last+0.01);var s=engine.create("XAUUSD",Timeframe.M5,b,q,engine.analyze(b,q),now);engine.observe(s,new FeedRequest.Quote(now.plusSeconds(130),last,last+0.01),now.plusSeconds(130));assertTrue(s.trackingGap);}
 @Test void pendingExpiresWithoutClaimingEntry(){var b=bars(true);double last=b.get(259).close();var q=new FeedRequest.Quote(now,last,last+0.01);var s=engine.create("XAUUSD",Timeframe.M1,b,q,engine.analyze(b,q),now);engine.observe(s,new FeedRequest.Quote(now.plusSeconds(181),s.entry.doubleValue(),s.entry.doubleValue()+0.01),now.plusSeconds(181));assertEquals(AutoSignal.Status.EXPIRED,s.status);assertNull(s.activatedAt);}
}
