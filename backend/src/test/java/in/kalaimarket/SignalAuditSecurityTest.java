package in.kalaimarket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=SignalAuditController.class,properties={"app.admin.username=kalai","app.admin.password=1234567890","MT5_FEED_PASSWORD=1234567"}) @Import(SecurityConfig.class)
class SignalAuditSecurityTest {
 @Autowired MockMvc mvc; @MockitoBean JdbcTemplate db;
 @Test void anonymousCannotReadAudit()throws Exception{mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());verifyNoInteractions(db);}
 @Test void brokerCredentialCannotReadAudit()throws Exception{mvc.perform(get("/api/audit").with(httpBasic("mt5_feed","1234567"))).andExpect(status().isForbidden());verifyNoInteractions(db);}
 @Test void adminReadsAuditWithCursor()throws Exception{
  List<Map<String,Object>> rows=List.of(Map.of("id",12L,"event_type","EXPIRED"),Map.of("id",11L,"event_type","SIGNAL_CREATED"));
  when(db.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Map<String,Object>>>any(),any(Object[].class))).thenReturn(rows);
  mvc.perform(get("/api/audit?channel=AUTO&signalId=3&limit=1&beforeId=15").with(httpBasic("kalai","1234567890"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextBeforeId").value(12));
  verify(db).query(eq("SELECT * FROM signal_activity_audit WHERE true AND channel=? AND signal_id=? AND id<? ORDER BY id DESC LIMIT ?"),org.mockito.ArgumentMatchers.<RowMapper<Map<String,Object>>>any(),eq(new Object[]{"AUTO",3L,15L,2}));
 }
 @Test void invalidChannelRejected()throws Exception{mvc.perform(get("/api/audit?channel=INVALID").with(httpBasic("kalai","1234567890"))).andExpect(status().isBadRequest());verifyNoInteractions(db);}
 @Test void unboundedPageRejected()throws Exception{mvc.perform(get("/api/audit?limit=1000000").with(httpBasic("kalai","1234567890"))).andExpect(status().isBadRequest());verifyNoInteractions(db);}
 @Test void invalidCursorRejected()throws Exception{mvc.perform(get("/api/audit?beforeId=-1").with(httpBasic("kalai","1234567890"))).andExpect(status().isBadRequest());verifyNoInteractions(db);}
}
