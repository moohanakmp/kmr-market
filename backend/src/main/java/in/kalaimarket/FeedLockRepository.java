package in.kalaimarket;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface FeedLockRepository extends JpaRepository<WatchItem,String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select w from WatchItem w where w.symbol=:symbol") Optional<WatchItem> lock(@Param("symbol") String symbol);
}
