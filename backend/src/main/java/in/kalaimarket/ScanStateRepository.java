package in.kalaimarket;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ScanStateRepository extends JpaRepository<ScanState,String> {
 List<ScanState> findBySymbol(String symbol);
}
