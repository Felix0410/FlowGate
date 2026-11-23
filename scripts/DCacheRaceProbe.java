import com.flowgate.control.portal.CatalogCacheService;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.*;
import java.util.*;
/** 真Redis下控制慢Reader失效交错；不mock Redis或修改生产代次。 */
public class DCacheRaceProbe {
 public static void main(String[] args)throws Exception{
  var config=new RedisStandaloneConfiguration(System.getenv("FG_REDIS_HOST"),Integer.parseInt(System.getenv("FG_REDIS_PORT")));config.setPassword(System.getenv("FG_REDIS_PASSWORD"));var factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();factory.start();var redis=new StringRedisTemplate(factory);var cache=new CatalogCacheService(redis,true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();String scope="d-race-"+UUID.randomUUID();
  try{
   var old=pool.submit(()->cache.get(scope,20,5,true,()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError();}catch(InterruptedException e){throw new RuntimeException(e);}return Map.of("revision",1);}));
   if(!entered.await(5,TimeUnit.SECONDS))throw new AssertionError();cache.invalidate();var newer=cache.get(scope,20,5,true,()->Map.of("revision",2));release.countDown();old.get(5,TimeUnit.SECONDS);var result=cache.get(scope,20,5,true,()->{throw new AssertionError("NEW_GENERATION_CACHE_MISSING");});if(!result.equals(newer))throw new AssertionError("SLOW_READER_OVERWROTE_NEW");System.out.println("REAL_REDIS_GENERATION_CAS_PASS "+cache.diagnostics());
  }finally{release.countDown();pool.shutdownNow();factory.destroy();}
 }
}
