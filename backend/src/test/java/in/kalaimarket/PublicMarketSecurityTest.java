package in.kalaimarket;
import org.junit.jupiter.api.Test;import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;import org.springframework.context.annotation.Import;import org.springframework.test.context.bean.override.mockito.MockitoBean;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=PublicMarketController.class,properties={"app.admin.username=kalai","app.admin.password=1234567890","MT5_FEED_PASSWORD=1234567"}) @Import(SecurityConfig.class)
class PublicMarketSecurityTest {
 @Autowired MockMvc mvc;@MockitoBean PublicSignalService service;@MockitoBean PublicMarketData data;
 @Test void anonymousCannotSubmitPublicCandles()throws Exception{mvc.perform(post("/api/market/public-snapshot").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());}
 @Test void executorCredentialCannotCreatePublicSignals()throws Exception{mvc.perform(post("/api/market/public-snapshot").with(httpBasic("mt5_feed","1234567")).contentType("application/json").content("{}")).andExpect(status().isForbidden());}
 @Test void executorCredentialCannotReadManualAnalysis()throws Exception{mvc.perform(get("/api/market/analysis/BTCUSD").with(httpBasic("mt5_feed","1234567"))).andExpect(status().isForbidden());}
 @Test void adminCanReadIndependentSourceStatus()throws Exception{mvc.perform(get("/api/market/public-status").with(httpBasic("kalai","1234567890"))).andExpect(status().isOk()).andExpect(jsonPath("$.mt5Purpose").value("ORDER_EXECUTION_ONLY"));}
}
