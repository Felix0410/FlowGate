package com.flowgate.control.service;

import com.flowgate.control.entity.AuditLog;
import com.flowgate.control.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

/** 只保存动作与对象摘要；调用方事务存在时随事务一起提交。 */
@Service
public class AuditService {
    private final AuditLogMapper mapper;
    public AuditService(AuditLogMapper mapper) { this.mapper = mapper; }
    public void record(long actor, String action, String type, String id, Long version, String summary) {
        AuditLog entry = new AuditLog(); entry.actorId = actor; entry.action = action; entry.targetType = type;
        entry.targetId = id; entry.releaseVersion = version; entry.summary = summary; mapper.insert(entry);
    }
}
