package in.kalaimarket;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BrokerExecutionRepository extends JpaRepository<BrokerExecution,Long> {
 List<BrokerExecution> findByAccountLoginAndBrokerServer(long account,String server,org.springframework.data.domain.Pageable page);
 Optional<BrokerExecution> findBySignalId(Long id);
 List<BrokerExecution> findByStatusIn(Collection<BrokerExecution.Status> states);
}
