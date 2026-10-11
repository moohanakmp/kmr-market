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
@WebMvcTest(controllers=MarketController.class,properties={"app.admin.username=kalai","app.admin.password=test-password-long-enough"})
@Import(SecurityConfig.class)
class ApiSecurityTest {
 @Autowired MockMvc mvc;
 @MockitoBean TradeRepository trades;
 @MockitoBean WatchRepository watch;
 @Test void anonymousApiDenied() throws Exception {mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());}
 @Test void validCredentialsAccepted() throws Exception {mvc.perform(get("/api/me").with(httpBasic("kalai","test-password-long-enough"))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1.0.0"));}
 @Test void badPasswordDenied() throws Exception {mvc.perform(get("/api/me").with(httpBasic("kalai","wrong"))).andExpect(status().isUnauthorized());}
 @Test void invalidTradeRejected() throws Exception {mvc.perform(post("/api/plans").with(httpBasic("kalai","test-password-long-enough")).contentType("application/json").content("{\"symbol\":\"XAUUSD\",\"direction\":\"BUY\",\"entry\":2000,\"stopLoss\":2010,\"target\":2020}")).andExpect(status().isBadRequest());}
}
