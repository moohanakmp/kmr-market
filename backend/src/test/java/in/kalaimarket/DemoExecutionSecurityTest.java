package in.kalaimarket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;
@WebMvcTest(controllers=DemoExecutionController.class,properties={"app.admin.username=kalai","app.admin.password=1234567890","MT5_FEED_PASSWORD=1234567"})
@Import(SecurityConfig.class)
class DemoExecutionSecurityTest {
 @Autowired MockMvc mvc;
 @MockitoBean DemoExecutionService service;
 @MockitoBean AutoSignalRepository signals;
 @Test void anonymousCannotReadExecutionConfig() throws Exception {mvc.perform(get("/api/feed/execution/config")).andExpect(status().isUnauthorized());}
 @Test void feedCanReadFixedVolumeAndSeparateLimits() throws Exception {when(service.getServer()).thenReturn("OctaFX-Demo");mvc.perform(get("/api/feed/execution/config").with(httpBasic("mt5_feed","1234567"))).andExpect(status().isOk()).andExpect(jsonPath("$.volume").value(.01)).andExpect(jsonPath("$.maxPendingPerSymbol").value(5)).andExpect(jsonPath("$.maxOpenPerSymbol").value(5)).andExpect(jsonPath("$.timeframes.length()").value(8));}
 @Test void malformedLeaseCannotClaimOwnership() throws Exception {mvc.perform(post("/api/feed/execution/lease").with(httpBasic("mt5_feed","1234567")).contentType("application/json").content("{\"worker\":\"bad\"}")).andExpect(status().isBadRequest());}
}
