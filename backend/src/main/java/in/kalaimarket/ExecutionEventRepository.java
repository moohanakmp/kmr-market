package in.kalaimarket;
import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent,Long>{List<ExecutionEvent> findBySignalIdOrderByIdAsc(Long id);}
