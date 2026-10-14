package in.kalaimarket;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;
public interface AutoSignalRepository extends JpaRepository<AutoSignal,Long> {
 @org.springframework.data.jpa.repository.Modifying
 @org.springframework.data.jpa.repository.Query("update AutoSignal s set s.status=:expired,s.version=s.version+1,s.updatedAt=:now,s.updatedBy='SYSTEM:EXPIRY' where s.status=:pending and s.expiresAt<=:now")
 int expirePending(@org.springframework.data.repository.query.Param("now") Instant now,@org.springframework.data.repository.query.Param("expired") AutoSignal.Status expired,@org.springframework.data.repository.query.Param("pending") AutoSignal.Status pending);
 Optional<AutoSignal> findByRequestKey(java.util.UUID key);
 List<AutoSignal> findByStatusAndTimeframeIn(AutoSignal.Status status,Collection<Timeframe> frames,org.springframework.data.domain.Pageable page);
 List<AutoSignal> findBySymbolAndStatusIn(String symbol,Collection<AutoSignal.Status> status);
 boolean existsBySymbolAndTimeframeAndSourceBar(String symbol,Timeframe timeframe,Instant bar);
 List<AutoSignal> findByTimeframeIn(Collection<Timeframe> frames,org.springframework.data.domain.Pageable page);
}
