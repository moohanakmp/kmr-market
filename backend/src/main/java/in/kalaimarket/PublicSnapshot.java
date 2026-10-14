package in.kalaimarket;
import java.util.*;
public record PublicSnapshot(String symbol,String source,long quoteAt,double price,
 List<Bar> c5,List<Bar> c15,List<Bar> h1,List<Bar> h4,List<News> events,long calendarAt,boolean marketOpen,long now) {
 public record Bar(long t,double o,double h,double l,double c,double v){}
 public record News(long ts,String title){}
 public PublicSnapshot at(long time){return new PublicSnapshot(symbol,source,quoteAt,price,c5,c15,h1,h4,events,calendarAt,marketOpen,time);}
 public void validate(long serverNow){
  if(!Set.of("XAUUSD","XAGUSD","BTCUSD").contains(symbol)||source==null||!Set.of("Binance BTC/USDT","Binance XAUUSDT perp rebased to spot","Binance XAGUSDT perp rebased to spot","Binance PAXG proxy rebased to spot").contains(source))throw new IllegalArgumentException("Unsupported public source");
  if(!Double.isFinite(price)||price<=0||quoteAt<=0||serverNow-quoteAt>90000||quoteAt>serverNow+30000||calendarAt>serverNow+30000)throw new IllegalArgumentException("Public price or calendar timestamp is invalid");
  if(c5==null||c15==null||h1==null||h4==null)throw new IllegalArgumentException("Public histories are required");
  for(List<Bar> bars:List.of(c5,c15,h1,h4)){
   if(bars.size()>750||bars.isEmpty())throw new IllegalArgumentException("Public history is missing or too large");long previous=0;
   for(Bar b:bars){if(b.t()<=previous||b.t()>serverNow+30000||!Double.isFinite(b.o()+b.h()+b.l()+b.c()+b.v())||b.l()<=0||b.h()<Math.max(b.o(),b.c())||b.l()>Math.min(b.o(),b.c())||b.v()<0)throw new IllegalArgumentException("Invalid chronological public OHLCV");previous=b.t();}
  }
  if(events==null||events.size()>500)throw new IllegalArgumentException("Invalid calendar");for(News ev:events)if(ev.title()==null||ev.title().length()>200||ev.ts()<=0)throw new IllegalArgumentException("Invalid calendar event");
 }
}
