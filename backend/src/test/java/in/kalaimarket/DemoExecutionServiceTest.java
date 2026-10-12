package in.kalaimarket;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DemoExecutionServiceTest {
 private final ExecutionWorkerRepository workers=mock(ExecutionWorkerRepository.class);
 private final BrokerExecutionRepository executions=mock(BrokerExecutionRepository.class);
 private final AutoSignalRepository signals=mock(AutoSignalRepository.class);
 private final DemoExecutionService service=new DemoExecutionService(workers,executions,signals,123456,"OctaFX-Demo");
 private final String owner="11111111-1111-4111-8111-111111111111";
 private AutoSignal signal;
 private ExecutionWorker lease;
 @BeforeEach void setup(){
  lease=new ExecutionWorker();lease.id=1;lease.owner=owner;lease.leaseUntil=Instant.now().plusSeconds(300);
  when(workers.lockWorker()).thenReturn(Optional.of(lease));
  signal=new AutoSignal();signal.id=7L;signal.status=AutoSignal.Status.PENDING;signal.timeframe=Timeframe.H1;signal.expiresAt=Instant.now().plusSeconds(60);signal.lastQuoteAt=Instant.now();
  when(signals.findById(7L)).thenReturn(Optional.of(signal));when(executions.save(any())).thenAnswer(i->i.getArgument(0));
 }
 @Test void claimIsPersistedBeforeBrokerSubmission(){assertEquals(BrokerExecution.Status.CLAIMED,service.claim(owner,123456,"OctaFX-Demo",7).status);verify(executions).save(any());}
 @Test void duplicateCannotBeClaimed(){when(executions.findBySignalId(7L)).thenReturn(Optional.of(new BrokerExecution()));assertThrows(ResponseStatusException.class,()->service.claim(owner,123456,"OctaFX-Demo",7));verify(executions,never()).save(any());}
 @Test void wrongAccountOrServerCannotBeClaimed(){assertThrows(ResponseStatusException.class,()->service.claim(owner,999,"OctaFX-Demo",7));assertThrows(ResponseStatusException.class,()->service.claim(owner,123456,"Real",7));verify(executions,never()).save(any());}
 @Test void disabledBindingCannotClaim(){var disabled=new DemoExecutionService(workers,executions,signals,0,"OctaFX-Demo");assertThrows(ResponseStatusException.class,()->disabled.claim(owner,123456,"OctaFX-Demo",7));}
 @Test void gappedStaleExpiredAndDisabledSignalsCannotClaim(){
  signal.trackingGap=true;assertThrows(ResponseStatusException.class,()->service.claim(owner,123456,"OctaFX-Demo",7));signal.trackingGap=false;
  signal.timeframe=Timeframe.H2;assertThrows(ResponseStatusException.class,()->service.claim(owner,123456,"OctaFX-Demo",7));signal.timeframe=Timeframe.H1;
  signal.lastQuoteAt=Instant.now().minusSeconds(120);assertThrows(ResponseStatusException.class,()->service.claim(owner,123456,"OctaFX-Demo",7));signal.lastQuoteAt=Instant.now();
  signal.expiresAt=Instant.now().minusSeconds(1);assertThrows(ResponseStatusException.class,()->service.claim(owner,123456,"OctaFX-Demo",7));verify(executions,never()).save(any());
 }
 @Test void competingWorkerCannotTakeValidLease(){assertThrows(ResponseStatusException.class,()->service.renew("22222222-2222-4222-8222-222222222222"));assertEquals(owner,lease.owner);}
 @Test void expiredLeaseCanBeRecoveredAndOldOwnerCannotReport(){lease.leaseUntil=Instant.now().minusSeconds(1);String other="22222222-2222-4222-8222-222222222222";service.renew(other);assertEquals(other,lease.owner);assertThrows(ResponseStatusException.class,()->service.report(owner,7,BrokerExecution.Status.UNKNOWN,null,null,"test",null,null));}
 @Test void releaseCannotClearAnotherWorkerLease(){service.release("22222222-2222-4222-8222-222222222222");assertEquals(owner,lease.owner);service.release(owner);assertNull(lease.owner);}
}
