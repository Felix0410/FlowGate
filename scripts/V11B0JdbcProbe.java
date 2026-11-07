import com.zaxxer.hikari.*;
import com.flowgate.control.mapper.ApplicationEntryMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import java.sql.*;
import java.time.*;

/** 只运行于测试自有库；实测池UTC、MyBatis Instant及月末TIMESTAMP毫秒，无管理诊断端点。 */
public class V11B0JdbcProbe {
 public static void main(String[] args)throws Exception{
  var config=new HikariConfig();config.setJdbcUrl(System.getenv("PROBE_URL"));config.setUsername("root");config.setPassword(System.getenv("PROBE_PASSWORD"));config.setMaximumPoolSize(2);
  config.setConnectionInitSql("SET time_zone = '+00:00'");
  try(var pool=new HikariDataSource(config)){
   String global,session;
   // 1. MySQL全局非UTC，但Hikari连接实际为UTC；CURRENT与UTC函数结果必须相同。
   try(var c=pool.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT @@global.time_zone,@@session.time_zone,TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(3),CURRENT_TIMESTAMP(3))")){
    rows.next();global=rows.getString(1);session=rows.getString(2);if(!session.equals("+00:00")||rows.getLong(3)!=0)throw new AssertionError("会话不是UTC");
   }
   // 2. 用实际Mapper和实际驱动转换，而非计算固定本地偏移来冒充时区修复。
   var cfg=new Configuration(new Environment("b0",new JdbcTransactionFactory(),pool));cfg.addMapper(ApplicationEntryMapper.class);
   try(var sql=new SqlSessionFactoryBuilder().build(cfg).openSession()){
    Instant now=sql.getMapper(ApplicationEntryMapper.class).nowUtc();if(Math.abs(Duration.between(now,Instant.now()).toMillis())>5000)throw new AssertionError("MyBatis时钟偏移");
   }
   // 3. 临时表只属于连接；验证UTC月末最后毫秒与下一月零点精确回读。
   try(var c=pool.getConnection();var statement=c.createStatement()){
    statement.execute("CREATE TEMPORARY TABLE b0_instant_probe(t TIMESTAMP(3))");
    for(String value:new String[]{"2026-11-30T23:59:59.999Z","2026-12-01T00:00:00Z"}){
     statement.execute("DELETE FROM b0_instant_probe");Instant expected=Instant.parse(value);
     try(var insert=c.prepareStatement("INSERT INTO b0_instant_probe VALUES(?)")){insert.setTimestamp(1,Timestamp.from(expected));insert.executeUpdate();}
     try(var rows=statement.executeQuery("SELECT t FROM b0_instant_probe")){rows.next();if(!rows.getTimestamp(1).toInstant().equals(expected))throw new AssertionError("TIMESTAMP毫秒回读不一致");}
    }
   }
   System.out.println("{\"passed\":true,\"globalTimeZone\":\""+global+"\",\"sessionTimeZone\":\""+session+"\",\"jvmTimeZone\":\""+ZoneId.systemDefault()+"\",\"mapperClockAndMonthBoundaryRoundTrip\":true}");
  }
 }
}
