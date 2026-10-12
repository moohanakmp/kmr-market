package in.kalaimarket;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class QuoteFreshnessTest {
 private final AutoSignalService service = new AutoSignalService(null, null, null, null, null);

 private void rejectsQuoteAt(Instant time) {
  var batch = new FeedRequest.QuoteBatch(List.of(new FeedRequest.QuoteItem("BTCUSD", new FeedRequest.Quote(time, 100, 101))));
  var error = assertThrows(ResponseStatusException.class, () -> service.quotes(batch));
  assertEquals(400, error.getStatusCode().value());
  assertTrue(error.getReason().contains("quote UTC="));
  assertTrue(error.getReason().contains("server UTC="));
  assertTrue(error.getReason().contains("age seconds="));
 }

 @Test void rejectsStaleQuoteBeforeAnyDatabaseWork() {rejectsQuoteAt(Instant.now().minusSeconds(120));}
 @Test void rejectsFutureQuoteBeforeAnyDatabaseWork() {rejectsQuoteAt(Instant.now().plusSeconds(120));}
}
