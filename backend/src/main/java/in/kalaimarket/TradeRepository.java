package in.kalaimarket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface TradeRepository extends JpaRepository<TradePlan,Long> {
 @Query("""
  select new in.kalaimarket.TradePlanView(p.id,p.symbol,p.direction,p.entry,p.stopLoss,p.target,
   p.createdAt,p.sourceSignalId,s.timeframe,s.status,s.tp1,s.trackingGap,s.expiresAt,
   e.status,e.orderTicket,e.positionTicket,coalesce(e.message,s.executionNote),e.brokerStopLoss,e.brokerTakeProfit,s.strategy,s.targets,s.pendingKind,e.brokerEntryPrice,e.volume,e.reachedTarget,e.protectedTarget,e.closeReason,e.updatedAt)
  from TradePlan p left join AutoSignal s on s.id=p.sourceSignalId left join BrokerExecution e on e.signalId=s.id
  order by p.createdAt desc,p.id desc
  """)
 List<TradePlanView> findPlanViews(Pageable page);
}
