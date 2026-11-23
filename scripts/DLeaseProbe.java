import com.flowgate.control.FlowGateControlApplication;
import com.flowgate.control.service.OutboxClaimService;
import com.flowgate.control.entity.OutboxEventEntry;
import org.springframework.boot.*;
import java.util.*;
import java.util.concurrent.*;
/** Independent D schema: real two-worker claims and natural 30s lease expiry. */
public class DLeaseProbe {
 public static void main(String[] args){try{run();System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}}
 static List<OutboxEventEntry> take(OutboxClaimService service)throws Exception{
  var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var rows=Collections.synchronizedList(new ArrayList<OutboxEventEntry>());
  try{var tasks=new ArrayList<Future<?>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{try{start.await();for(int n=0;n<6;n++)rows.addAll(service.claim(20));}catch(Exception e){throw new RuntimeException(e);}}));start.countDown();for(var task:tasks)task.get(15,TimeUnit.SECONDS);return rows;}finally{pool.shutdownNow();}
 }
 static void run()throws Exception{
  var boot=new SpringApplication(FlowGateControlApplication.class);boot.setWebApplicationType(WebApplicationType.NONE);
  try(var context=boot.run()){
   var service=context.getBean(OutboxClaimService.class);var old=take(service);if(old.size()!=100||old.stream().map(r->r.id).distinct().count()!=100)throw new AssertionError("CLAIM_OVERLAP");
   long began=System.nanoTime();Thread.sleep(31000);int late=0;for(var row:old)late+=service.sent(row);if(late!=0)throw new AssertionError("EXPIRED_OWNER_UPDATED");
   var next=take(service);if(next.size()!=100||next.stream().map(r->r.id).distinct().count()!=100)throw new AssertionError("RECOVERY_LOST");int stale=0,sent=0;for(var row:old)stale+=service.sent(row);for(var row:next)sent+=service.sent(row);if(stale!=0||sent!=100)throw new AssertionError("OWNER_CAS");
   System.out.println("D_LEASE100_PASS naturalSeconds="+(System.nanoTime()-began)/1e9+" oldOwnerUpdates=0 newOwnerSent=100");
  }
 }
}
