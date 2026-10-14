package in.kalaimarket;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="auto_signal") public class AutoSignal {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Version public long version;
 @Column(name="execution_note") public String executionNote;
 @Column(name="created_by",updatable=false) public String createdBy;
 @Column(name="initiated_by",updatable=false) public String initiatedBy;
 @Column(name="updated_by") public String updatedBy;
 @JdbcTypeCode(SqlTypes.JSON) @Column(name="decision_context",columnDefinition="jsonb") public Map<String,Object> decisionContext=new LinkedHashMap<>();
 @PrePersist public void stampCreation(){initiatedBy=SignalActor.current();createdBy=isManual()?initiatedBy:"SYSTEM:"+strategy;updatedBy=createdBy;}
 @Transient public String activityActor;
 @PreUpdate public void stampUpdate(){updatedBy=activityActor==null?SignalActor.current():activityActor;}
 public String symbol;
 @Enumerated(EnumType.STRING) public Timeframe timeframe;
 @Enumerated(EnumType.STRING) public TradePlan.Direction direction;
 @Enumerated(EnumType.STRING) public Status status=Status.PENDING;
 @Column(precision=20,scale=8) public BigDecimal entry;
 @Column(name="stop_loss",precision=20,scale=8) public BigDecimal stopLoss;
 @Column(precision=20,scale=8) public BigDecimal tp1;
 @Column(precision=20,scale=8) public BigDecimal tp2;
 public int quality;
 @Column(name="source_atr") public Double sourceAtr;
 public String reason;
 public String strategy="EMA_BREAKOUT_V1";
 @Column(name="source_bar") public Instant sourceBar;
 @Column(name="created_at") public Instant createdAt;
 @Column(name="expires_at") public Instant expiresAt;
 @Column(name="activated_at") public Instant activatedAt;
 @Column(name="updated_at") public Instant updatedAt;
 @Column(name="last_quote_at") public Instant lastQuoteAt;
 @Column(name="tracking_gap") public boolean trackingGap;
 @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") public List<BigDecimal> targets=new ArrayList<>();
 @Column(name="pending_kind") @Enumerated(EnumType.STRING) public PendingKind pendingKind=PendingKind.STOP;
 @Column(name="request_key") public UUID requestKey;
 @Column(name="manual_plan_id") public Long manualPlanId;
 @Column(name="cancel_requested") public boolean cancelRequested;
 @Column(name="reached_target") public int reachedTarget;
 public enum PendingKind {STOP,LIMIT,MARKET}
 public enum Status {PENDING, ACTIVE, TP1, TP2, TP3, TARGET, STOPPED, MISSED, EXPIRED, CANCELLED}
 public List<BigDecimal> getTargets(){return targets==null||targets.isEmpty()?List.of(tp1,tp2):targets;}
 public boolean finalTargetReached(){return reachedTarget>=getTargets().size();}
 public boolean isManual(){return "MANUAL_PENDING_V1".equals(strategy);}
 public static BigDecimal price(double v){return BigDecimal.valueOf(v).setScale(8,RoundingMode.HALF_UP);}
}
