package in.kalaimarket;
import java.time.Instant;import org.springframework.stereotype.Component;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.transaction.annotation.Transactional;
@Component public class OrderExpiry {
 private final AutoSignalRepository signals;public OrderExpiry(AutoSignalRepository s){signals=s;}
 @Scheduled(fixedDelay=15000) @Transactional public void expire(){signals.expirePending(Instant.now(),AutoSignal.Status.EXPIRED,AutoSignal.Status.PENDING);}
}
