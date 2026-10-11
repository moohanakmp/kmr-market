package in.kalaimarket;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;
public interface AutoSignalRepository extends JpaRepository<AutoSignal,Long> {
 List<AutoSignal> findBySymbolAndStatusIn(String symbol,Collection<AutoSignal.Status> status);
 boolean existsBySymbolAndTimeframeAndSourceBar(String symbol,Timeframe timeframe,Instant bar);
}
