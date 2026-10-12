package in.kalaimarket;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="execution_worker") public class ExecutionWorker {
 @Id public Integer id;
 public String owner;
 @Column(name="lease_until") public Instant leaseUntil;
}
