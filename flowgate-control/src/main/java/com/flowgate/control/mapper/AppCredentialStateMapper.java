package com.flowgate.control.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.AppCredentialState;
/** 与应用行锁配合的持久凭证代次。 */
@org.apache.ibatis.annotations.Mapper
public interface AppCredentialStateMapper extends BaseMapper<AppCredentialState> {
 @org.apache.ibatis.annotations.Select("SELECT * FROM fg_app_credential_state WHERE principal_id=#{id} FOR SHARE")
 AppCredentialState shareById(@org.apache.ibatis.annotations.Param("id") long id);
}
