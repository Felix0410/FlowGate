package com.flowgate.control.async;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 每轮从DB读取；缺表/缺行失败关闭。切换只允许维护工具在全实例退出后CAS。 */
@Service
public class LegacyTransportFence {
    private final JdbcTemplate db;
    public LegacyTransportFence(JdbcTemplate db){this.db=db;}
    public Map<String,Object> status(){
        try {var row=db.queryForMap("SELECT mode,epoch,cutover_max_outbox_id AS highWatermark,changed_at AS checkedChangeAt FROM fg_legacy_mq_cutover WHERE id=1");return row;}
        catch(org.springframework.dao.DataAccessException unavailable){return Map.of("mode","UNKNOWN","epoch",0,"reason","栅栏未安装或数据库不可用；禁止旧用量投递");}
    }
    public boolean allows(String mode){return mode.equals(status().get("mode"));}
}
