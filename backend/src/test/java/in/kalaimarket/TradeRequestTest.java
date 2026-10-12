package in.kalaimarket;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class TradeRequestTest {
 TradeRequest r(TradePlan.Direction d,String stop,String target) {return new TradeRequest("XAUUSD",d,new BigDecimal("2000"),new BigDecimal(stop),new BigDecimal(target));}
 @Test void buyAndSellValidateOppositeDirections(){ assertTrue(r(TradePlan.Direction.BUY,"1990","2020").validLevels());assertTrue(r(TradePlan.Direction.SELL,"2010","1980").validLevels());assertFalse(r(TradePlan.Direction.BUY,"2010","2020").validLevels());assertFalse(r(TradePlan.Direction.SELL,"2010","2020").validLevels()); }
 @Test void equalStopOrTargetRejected(){assertFalse(r(TradePlan.Direction.BUY,"2000","2020").validLevels());assertFalse(r(TradePlan.Direction.SELL,"2010","2000").validLevels());}
}

