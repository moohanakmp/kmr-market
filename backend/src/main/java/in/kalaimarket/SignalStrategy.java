package in.kalaimarket;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
@Component public class SignalStrategy {
 public record Analysis(TradePlan.Direction direction,double atr,int quality,String reason) {}
 public Analysis analyze(List<FeedRequest.Candle> bars,FeedRequest.Quote q){
  int n=bars.size(); double fast=ema(bars,20),slow=ema(bars,50),trend=ema(bars,200),atr=atr(bars);
  double close=bars.get(n-1).close(),priorHigh=-Double.MAX_VALUE,priorLow=Double.MAX_VALUE;
  for(int i=n-11;i<n-1;i++){priorHigh=Math.max(priorHigh,bars.get(i).high());priorLow=Math.min(priorLow,bars.get(i).low());}
  if(atr<=0||!Double.isFinite(atr)) return new Analysis(null,atr,0,"Insufficient price movement");
  if(q.ask()-q.bid()>atr*0.15) return new Analysis(null,atr,0,"Spread exceeds 15% of ATR");
  // Reject entry decisions when quotes have drifted far from the last closed bar.
  if(Math.abs((q.ask()+q.bid())/2-close)>atr*0.75) return new Analysis(null,atr,0,"Price moved too far from the closed candle");
  boolean buy=fast>slow&&close>trend&&close>priorHigh;
  boolean sell=fast<slow&&close<trend&&close<priorLow;
  if(!buy&&!sell) return new Analysis(null,atr,0,"Waiting for 10-bar breakout aligned with EMA20/50/200");
  double body=Math.abs(close-bars.get(n-1).open());
  int score=70+(Math.abs(fast-slow)>atr*0.2?10:0)+(body>atr*0.5?10:0)+(q.ask()-q.bid()<atr*0.05?10:0);
  return new Analysis(buy?TradePlan.Direction.BUY:TradePlan.Direction.SELL,atr,score,"10-bar closed-candle breakout; EMA20/50 and EMA200 aligned; ATR spread filter passed");
 }
 private double ema(List<FeedRequest.Candle> b,int period){double value=b.get(0).close(),k=2.0/(period+1);for(int i=1;i<b.size();i++)value=b.get(i).close()*k+value*(1-k);return value;}
 private double atr(List<FeedRequest.Candle>b){double total=0;for(int i=b.size()-14;i<b.size();i++){var c=b.get(i);double prev=b.get(i-1).close();total+=Math.max(c.high()-c.low(),Math.max(Math.abs(c.high()-prev),Math.abs(c.low()-prev)));}return total/14;}
 public AutoSignal create(String symbol,Timeframe tf,List<FeedRequest.Candle> bars,FeedRequest.Quote q,Analysis a,Instant now){
  AutoSignal s=new AutoSignal();s.symbol=symbol;s.timeframe=tf;s.direction=a.direction();s.quality=a.quality();s.reason=a.reason();
  double entry=a.direction()==TradePlan.Direction.BUY?Math.max(bars.get(bars.size()-1).high(),q.ask())+a.atr()*0.1:Math.min(bars.get(bars.size()-1).low(),q.bid())-a.atr()*0.1;
  double sign=a.direction()==TradePlan.Direction.BUY?1:-1,risk=a.atr()*1.5;
  s.entry=AutoSignal.price(entry);s.stopLoss=AutoSignal.price(entry-sign*risk);s.tp1=AutoSignal.price(entry+sign*risk);s.tp2=AutoSignal.price(entry+sign*risk*2);
  s.sourceBar=bars.get(bars.size()-1).closedAt();s.createdAt=now;s.updatedAt=now;s.lastQuoteAt=q.time();s.expiresAt=now.plusSeconds(tf.seconds*3);
  return s;
 }
 public void observe(AutoSignal s,FeedRequest.Quote q,Instant now){
  if(!q.time().isAfter(s.lastQuoteAt))return;
  if(q.time().isBefore(now.minusSeconds(90))||q.time().isAfter(now.plusSeconds(30)))return;
  if(q.time().isAfter(s.lastQuoteAt.plusSeconds(120)))s.trackingGap=true;
  s.lastQuoteAt=q.time();
  boolean buy=s.direction==TradePlan.Direction.BUY;double enter=buy?q.ask():q.bid(),exit=buy?q.bid():q.ask();
  if(s.status==AutoSignal.Status.PENDING){
   if(!now.isBefore(s.expiresAt)){s.status=AutoSignal.Status.EXPIRED;}
   else if(buy?enter>=s.entry.doubleValue():enter<=s.entry.doubleValue()){
    // A sampled quote beyond TP1 is a missed entry, never a fabricated immediate TP.
    if(buy?exit>=s.tp1.doubleValue():exit<=s.tp1.doubleValue())s.status=AutoSignal.Status.MISSED;
    else{s.status=AutoSignal.Status.ACTIVE;s.activatedAt=now;}
   } else if(buy?exit<=s.stopLoss.doubleValue():exit>=s.stopLoss.doubleValue())s.status=AutoSignal.Status.EXPIRED;
  }else if(s.status==AutoSignal.Status.ACTIVE||s.status==AutoSignal.Status.TP1){
   if(buy?exit<=s.stopLoss.doubleValue():exit>=s.stopLoss.doubleValue())s.status=AutoSignal.Status.STOPPED;
   else if(buy?exit>=s.tp2.doubleValue():exit<=s.tp2.doubleValue())s.status=AutoSignal.Status.TP2;
   else if(buy?exit>=s.tp1.doubleValue():exit<=s.tp1.doubleValue())s.status=AutoSignal.Status.TP1;
  }
  s.updatedAt=now;
 }
}
