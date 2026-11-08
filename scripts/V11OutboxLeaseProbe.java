import com.flowgate.control.FlowGateControlApplication;
import com.flowgate.control.service.OutboxClaimService;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
/** 隔离MySQL真实两Worker短事务与过期owner实证，不连接Kafka。 */
public class V11OutboxLeaseProbe {
 public static void main(String[]args)throws Exception{
  var boot=new SpringApplication(FlowGateControlApplication.class);boot.setWebApplicationType(WebApplicationType.NONE);
  try(var ctx=boot.run("--flowgate.async.enabled=false")){
   var claims=ctx.getBean(OutboxClaimService.class);var jdbc=ctx.getBean(JdbcTemplate.class);var pool=Executors.newFixedThreadPool(2);
   try{
    var barrier=new CyclicBarrier(2);
    var a=pool.submit(()->{barrier.await();return claims.claim(3);});var b=pool.submit(()->{barrier.await();return claims.claim(3);});
    var one=a.get();var two=b.get();if(one.size()!=3||two.size()!=3)throw new AssertionError("领取数量");
    var ids=new HashSet<Long>();one.forEach(r->ids.add(r.id));two.forEach(r->ids.add(r.id));if(ids.size()!=6)throw new AssertionError("有效租约重叠");
    if(TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("领取后仍持事务");
    var old=one.get(0);jdbc.update("UPDATE fg_outbox_event SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",old.id);
    var current=claims.claim(1).get(0);if(!current.id.equals(old.id)||current.leaseOwner.equals(old.leaseOwner))throw new AssertionError("租约未换owner");
    if(claims.sent(old)!=0||claims.sent(current)!=1)throw new AssertionError("迟到回执覆盖新owner");
    if(jdbc.queryForObject("SELECT COUNT(*) FROM fg_quota_ledger",Long.class)!=6)throw new AssertionError("不应修改权威流水");
    System.out.println("{\"passed\":true,\"workers\":2,\"uniqueLeases\":6,\"oldOwnerUpdate\":0,\"newOwnerUpdate\":1}");
   }finally{pool.shutdownNow();}
  }
 }
}
