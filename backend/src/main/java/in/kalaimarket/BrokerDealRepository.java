package in.kalaimarket;
import java.util.*;import java.time.Instant;import org.springframework.data.jpa.repository.JpaRepository;import org.springframework.data.jpa.repository.Query;import org.springframework.data.repository.query.Param;
public interface BrokerDealRepository extends JpaRepository<BrokerDeal,String>{
 List<BrokerDeal> findByAccountLoginAndBrokerServerAndTradedAtGreaterThanEqualAndTradedAtLessThanOrderByTradedAtAsc(long account,String server,Instant from,Instant to);
 List<BrokerDeal> findBySignalIdOrderByTradedAtAsc(Long id);
 @Query("select d from BrokerDeal d where d.accountLogin=:account and d.brokerServer=:server order by d.tradedAt desc,d.ticket desc")
 List<BrokerDeal> recent(@Param("account") long account,@Param("server") String server,org.springframework.data.domain.Pageable page);
}
