package in.kalaimarket;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="scan_state") public class ScanState {
 @Id public String id;
 @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
 @Column(name="decision_context",columnDefinition="jsonb") public java.util.Map<String,Object> decisionContext=new java.util.LinkedHashMap<>();
 @Column(name="evaluated_by") public String evaluatedBy="SYSTEM:SCANNER";
 @Column(name="evaluation_count") public long evaluationCount;
 @PrePersist @PreUpdate public void stamp(){evaluatedBy=SignalActor.current();evaluationCount++;}
 public String symbol;
 @Enumerated(EnumType.STRING) public Timeframe timeframe;
 @Column(name="last_bar") public Instant lastBar;
 @Column(name="last_scan_at") public Instant lastScanAt;
 @Column(name="last_quote_at") public Instant lastQuoteAt;
 @Column(name="received_at") public Instant receivedAt;
 public String reason;
 public int quality;
}
