package in.kalaimarket;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
@Component public class SignalAuditRecorder {
 private final JdbcTemplate db;
 public SignalAuditRecorder(JdbcTemplate db){this.db=db;}
 public void manualAnalysis(String symbol,JsonNode evidence){
  db.update("INSERT INTO signal_activity_audit(channel,symbol,timeframe,event_type,actor,observed_at,reason,after_state,decision_context) VALUES('MANUAL',?,'M15','MANUAL_ANALYSIS_VIEWED',?,?,'Manual preview only; no order submitted','{}',?::jsonb)",symbol,SignalActor.current(),java.sql.Timestamp.from(Instant.now()),evidence.toString());
 }
 public void sourceState(String symbol,String event,String reason){
  db.update("INSERT INTO signal_activity_audit(channel,symbol,event_type,actor,observed_at,reason,after_state) VALUES('AUTO',?,?,'SYSTEM:PUBLIC_COLLECTOR',?,?, '{}')",symbol,event,java.sql.Timestamp.from(Instant.now()),reason);
 }
}
