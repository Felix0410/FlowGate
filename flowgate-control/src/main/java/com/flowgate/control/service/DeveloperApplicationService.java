package com.flowgate.control.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.flowgate.control.service.BusinessInputs.*;

/** 客户与应用的ADMIN管理域；独立数据模型，不签发任何应用或旧Caller凭证。 */
@Service
public class DeveloperApplicationService {
    private final DeveloperEntryMapper developers; private final ApplicationEntryMapper apps; private final AuditService audit;
    public DeveloperApplicationService(DeveloperEntryMapper developers,ApplicationEntryMapper apps,AuditService audit) { this.developers=developers;this.apps=apps;this.audit=audit; }
    public record AppView(Long id,Long developerId,String developerName,String developerStatus,String code,String name,String status,Instant createdAt,Instant updatedAt,String credentialStatus) {}
    public DeveloperEntry developer(long id) { var row=developers.selectById(id(id));if(row==null)throw missing("客户");return row; }
    public ApplicationEntry application(long id) { var row=apps.selectById(id(id));if(row==null)throw missing("应用");return row; }
    private AppView view(ApplicationEntry row) { var parent=developer(row.developerId);return new AppView(row.id,row.developerId,parent.name,parent.status,row.code,row.name,row.status,row.createdAt,row.updatedAt,"FEATURE_NOT_READY"); }
    public List<DeveloperEntry> developers(long after,int limit) { page(after,limit);return developers.selectList(new QueryWrapper<DeveloperEntry>().gt("id",after).orderByAsc("id").last("LIMIT "+limit)); }
    public List<AppView> applications(Long developer,long after,int limit) { page(after,limit);if(developer!=null)id(developer);return apps.selectList(new QueryWrapper<ApplicationEntry>().gt("id",after).eq(developer!=null,"developer_id",developer).orderByAsc("id").last("LIMIT "+limit)).stream().map(this::view).toList(); }
    public AppView app(long id) { return view(application(id)); }
    @Transactional public DeveloperEntry saveDeveloper(Long id,Map<String,Object> input,long actor) {
        fields(input,"code","name","status");var row=id==null?new DeveloperEntry():developers.lockById(id(id));if(row==null)throw missing("客户");
        // 1. PATCH只变更显式字段，数据库维护创建时间；状态变更与订阅父级锁串行。
        if(id==null || input.containsKey("code"))row.code=code(input.get("code"));
        if(id==null || input.containsKey("name"))row.name=text(input.get("name"),"客户名称",200);
        if(id==null || input.containsKey("status"))row.status=status(input.getOrDefault("status","ACTIVE"));
        try { if(id==null)developers.insert(row);else developers.updateById(row); }catch(DuplicateKeyException duplicate){throw duplicate();}
        audit.record(actor,id==null?"CREATE_DEVELOPER":"UPDATE_DEVELOPER","developer",row.id.toString(),null,"保存客户管理记录");return developer(row.id);
    }
    @Transactional public AppView saveApp(Long id,Map<String,Object> input,long actor) {
        fields(input,"developerId","code","name","status");var row=id==null?new ApplicationEntry():apps.lockById(id(id));if(row==null)throw missing("应用");
        // 2. 应用归属创建后固定，避免历史订阅被转移；编辑使用应用行锁与订阅串行。
        if(id==null)row.developerId=number(input.get("developerId"),Long.MAX_VALUE,"客户ID");
        else if(input.containsKey("developerId"))require(row.developerId==number(input.get("developerId"),Long.MAX_VALUE,"客户ID"),"IMMUTABLE_OWNER","应用归属不可修改，请创建新应用");
        var parent=developers.lockById(row.developerId);if(parent==null)throw missing("客户");
        if(id==null)require("ACTIVE".equals(parent.status),"INACTIVE_PARENT","停用客户不能新建应用");
        if(id==null || input.containsKey("code"))row.code=code(input.get("code"));
        if(id==null || input.containsKey("name"))row.name=text(input.get("name"),"应用名称",200);
        if(id==null || input.containsKey("status"))row.status=status(input.getOrDefault("status","ACTIVE"));
        try { if(id==null)apps.insert(row);else apps.updateById(row); }catch(DuplicateKeyException duplicate){throw duplicate();}
        // 3. A阶段只记录管理动作；没有Redis跨系统撤销或发证副作用。
        audit.record(actor,id==null?"CREATE_APPLICATION":"UPDATE_APPLICATION","application",row.id.toString(),null,"保存应用管理状态（凭证未启用）");return app(row.id);
    }
    public void credentials(long id) { application(id);throw new ApiFailure(409,"FEATURE_NOT_READY","A阶段不签发应用凭证，需B阶段身份隔离及配额验证后启用"); }
}
