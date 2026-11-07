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

/** 套餐定义与订阅管理；应用行锁是互斥协议，不以Redis或部分唯一索引代替。 */
@Service
public class PlanSubscriptionService {
    private final ApiPlanEntryMapper plans; private final SubscriptionEntryMapper subscriptions;
    private final ApplicationEntryMapper apps; private final DeveloperEntryMapper developers;private final AuditService audit;
    public PlanSubscriptionService(ApiPlanEntryMapper plans,SubscriptionEntryMapper subscriptions,ApplicationEntryMapper apps,DeveloperEntryMapper developers,AuditService audit) {this.plans=plans;this.subscriptions=subscriptions;this.apps=apps;this.developers=developers;this.audit=audit;}
    public record SubscriptionView(Long id,Long applicationId,String applicationName,Long planId,String planName,Instant effectiveAt,Instant expiresAt,String status,Integer planVersionSnapshot,Instant createdAt,Instant updatedAt,String periodState,boolean parentsActive) {}
    public ApiPlanEntry plan(long id) { var row=plans.selectById(id(id));if(row==null)throw missing("套餐");return row; }
    public List<ApiPlanEntry> plans(long after,int limit) {page(after,limit);return plans.selectList(new QueryWrapper<ApiPlanEntry>().gt("id",after).orderByAsc("id").last("LIMIT "+limit));}
    @Transactional public ApiPlanEntry savePlan(Long id,Map<String,Object> input,long actor) {
        fields(input,"code","name","monthlyUnits","status");var row=id==null?new ApiPlanEntry():plans.lockById(id(id));if(row==null)throw missing("套餐");
        if(id==null){row.monthlyUnits=number(input.get("monthlyUnits"),1000000,"套餐额度");row.planVersion=1;row.code=code(input.get("code"));}
        else {
            require(!input.containsKey("monthlyUnits"),"IMMUTABLE_PLAN","套餐额度创建后不可修改，请创建新套餐");
            require(!input.containsKey("code") || row.code.equals(code(input.get("code"))),"IMMUTABLE_PLAN","套餐代码不可修改");
        }
        if(id==null || input.containsKey("name"))row.name=text(input.get("name"),"套餐名称",200);
        if(id==null || input.containsKey("status"))row.status=status(input.getOrDefault("status","ACTIVE"));
        try {if(id==null)plans.insert(row);else plans.updateById(row);}catch(DuplicateKeyException duplicate){throw duplicate();}
        audit.record(actor,id==null?"CREATE_PLAN":"UPDATE_PLAN","plan",row.id.toString(),null,"保存套餐（额度定义不追溯修改）");return plan(row.id);
    }
    private SubscriptionView view(SubscriptionEntry row,Instant now) {
        var app=apps.selectById(row.applicationId);var parent=developers.selectById(app.developerId);var plan=plan(row.planId);
        String phase=!now.isBefore(row.expiresAt)?"EXPIRED":now.isBefore(row.effectiveAt)?"SCHEDULED":"CURRENT";
        return new SubscriptionView(row.id,row.applicationId,app.name,row.planId,plan.name,row.effectiveAt,row.expiresAt,row.status,row.planVersionSnapshot,row.createdAt,row.updatedAt,phase,"ACTIVE".equals(app.status) && "ACTIVE".equals(parent.status) && "ACTIVE".equals(plan.status));
    }
    public SubscriptionView subscription(long id) {var row=subscriptions.selectById(id(id));if(row==null)throw missing("订阅");return view(row,apps.nowUtc());}
    public List<SubscriptionView> subscriptions(Long application,long after,int limit) {page(after,limit);if(application!=null)id(application);var now=apps.nowUtc();return subscriptions.selectList(new QueryWrapper<SubscriptionEntry>().gt("id",after).eq(application!=null,"application_id",application).orderByAsc("id").last("LIMIT "+limit)).stream().map(r->view(r,now)).toList();}
    @Transactional public SubscriptionView subscribe(Map<String,Object> input,long actor) {
        fields(input,"applicationId","planId","effectiveAt","expiresAt","status");
        long application=number(input.get("applicationId"),Long.MAX_VALUE,"应用ID"),planId=number(input.get("planId"),Long.MAX_VALUE,"套餐ID");
        var start=instant(input.get("effectiveAt"));var end=instant(input.get("expiresAt"));
        require("ACTIVE".equals(input.getOrDefault("status","ACTIVE")),"VALIDATION_ERROR","首版只支持创建ACTIVE订阅，不开放任意区间/状态修改");
        // 1. 同一应用真正加行锁；后续读都用FOR UPDATE当前读，避免RR旧快照看不到等待前的提交。
        var app=apps.lockById(application);if(app==null)throw missing("应用");
        var parent=developers.shareById(app.developerId);var plan=plans.shareById(planId);if(plan==null)throw missing("套餐");
        if(!"ACTIVE".equals(app.status))throw new ApiFailure(403,"APP_DISABLED","应用已暂停");
        if(parent==null || !"ACTIVE".equals(parent.status))throw new ApiFailure(403,"DEVELOPER_DISABLED","客户已暂停");
        require("ACTIVE".equals(plan.status),"PLAN_DISABLED","套餐已停用");
        // 2. 时钟取数据库UTC，持有同一应用锁期间校验半开区间和下一自然月唯一排队。
        var now=apps.nowUtc();var existing=subscriptions.selectList(new QueryWrapper<SubscriptionEntry>().eq("application_id",application).orderByAsc("id").last("FOR UPDATE"));
        SubscriptionSchedule.validate(start,end,now,existing);
        var row=new SubscriptionEntry();row.applicationId=application;row.planId=planId;row.effectiveAt=start;row.expiresAt=end;row.status="ACTIVE";row.planVersionSnapshot=plan.planVersion;
        // 3. 插入、冻结套餐版本与审计同一事务；任何失败回滚，绝不签发凭证或初始化额度。
        subscriptions.insert(row);audit.record(actor,"CREATE_SUBSCRIPTION","subscription",row.id.toString(),null,"创建UTC期间订阅，套餐版本冻结");
        return subscription(row.id);
    }
}
