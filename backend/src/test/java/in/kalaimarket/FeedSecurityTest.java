package in.kalaimarket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=AutoSignalController.class,properties={"app.admin.username=kalai","app.admin.password=1234567890","MT5_FEED_PASSWORD=1234567"})
@Import(SecurityConfig.class)
class FeedSecurityTest {
 @Autowired MockMvc mvc;
 @MockitoBean AutoSignalService service;
 @MockitoBean AutoSignalRepository signals;
 @MockitoBean ScanStateRepository scans;
 @MockitoBean WatchRepository watch;
 @Test void feedHasAccessToFeedOnly() throws Exception {
  mvc.perform(get("/api/feed/symbols").with(httpBasic("mt5_feed","1234567"))).andExpect(status().isOk());
  mvc.perform(get("/api/signals").with(httpBasic("mt5_feed","1234567"))).andExpect(status().isForbidden());
 }
 @Test void feedCannotPostEmptyQuoteBatch() throws Exception {mvc.perform(post("/api/feed/quotes").with(httpBasic("mt5_feed","1234567")).contentType("application/json").content("{\"quotes\":[]}")).andExpect(status().isBadRequest());}
 @Test void tenCharacterAdminCanReadSignals() throws Exception {
  when(signals.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class))).thenReturn(org.springframework.data.domain.Page.empty());
  mvc.perform(get("/api/signals").with(httpBasic("kalai","1234567890"))).andExpect(status().isOk());
 }
 @Test void anonymousFeedDenied() throws Exception {mvc.perform(get("/api/feed/symbols")).andExpect(status().isUnauthorized());}
}
