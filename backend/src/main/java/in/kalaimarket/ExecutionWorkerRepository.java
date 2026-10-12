package in.kalaimarket;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
public interface ExecutionWorkerRepository extends JpaRepository<ExecutionWorker,Integer> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select w from ExecutionWorker w where w.id=1")
 Optional<ExecutionWorker> lockWorker();
}
