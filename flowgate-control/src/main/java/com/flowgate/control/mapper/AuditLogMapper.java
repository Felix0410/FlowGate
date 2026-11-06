package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/** fg_audit_log 的最小 MyBatis-Plus 访问接口。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
