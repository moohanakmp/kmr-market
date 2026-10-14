package in.kalaimarket;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
class SignalActorTest {
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 @Test void automaticCreationIdentifiesEngineAndInitiator(){SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("audit-tester","never-store-this","ROLE_ADMIN"));var s=new AutoSignal();s.strategy="KMR_V28_PUBLIC";s.stampCreation();assertEquals("SYSTEM:KMR_V28_PUBLIC",s.createdBy);assertEquals("USER:audit-tester",s.initiatedBy);assertFalse(s.createdBy.contains("never-store"));}
 @Test void manualCreationIdentifiesUser(){SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("audit-tester","secret","ROLE_ADMIN"));var s=new AutoSignal();s.strategy="MANUAL_PENDING_V1";s.stampCreation();assertEquals("USER:audit-tester",s.createdBy);}
 @Test void backgroundUpdateIdentifiesSystem(){var s=new AutoSignal();s.stampUpdate();assertEquals("SYSTEM:SCANNER",s.updatedBy);}
 @Test void brokerUpdateIdentifiesWorker(){var s=new AutoSignal();s.activityActor="MT5_WORKER:worker-1";s.stampUpdate();assertEquals("MT5_WORKER:worker-1",s.updatedBy);}
}
