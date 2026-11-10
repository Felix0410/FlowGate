package com.flowgate.control.portal;

import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import com.flowgate.control.security.ApiFailure;

/** 参数化SQL的小型存储边界；表名与列名均由源码固定，不接收客户端SQL。 */
@Repository
public class PortalStore {
    final JdbcTemplate jdbc;
    public PortalStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public java.time.Instant nowUtc(){return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();}
    public List<Map<String,Object>> rows(String sql,Object... args) { return jdbc.queryForList(sql,args); }
    public Map<String,Object> one(String sql,Object... args) { var rows=rows(sql,args);return rows.isEmpty()?null:rows.get(0); }
    public Map<String,Object> need(String sql,Object... args) { var row=one(sql,args);if(row==null)throw new ApiFailure(404,"NOT_FOUND","对象不存在或不可访问");return row; }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();jdbc.update(connection->{var s=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return Objects.requireNonNull(key.getKey()).longValue();
    }
    public static long n(Map<String,Object> row,String name) { return ((Number)row.get(name)).longValue(); }
    public static String s(Map<String,Object> row,String name) { return String.valueOf(row.get(name)); }
}
