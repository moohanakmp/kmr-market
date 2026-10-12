package in.kalaimarket;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BrokerExecutionRepository extends JpaRepository<BrokerExecution,Long> {
 Optional<BrokerExecution> findBySignalId(Long id);
 List<BrokerExecution> findByStatusIn(Collection<BrokerExecution.Status> states);
}
