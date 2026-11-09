package com.flowgate.control.service;
import com.flowgate.contract.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
/** 默认关闭的施工/部署门禁；只有明确完整接流名单的所有节点都支持才允许计量。 */
@Service
public class QuotaActivationService {
 private final boolean enabled;private final Set<String> traffic;private final NodeCapabilityEntryMapper capabilities;private final ReleaseNodeMapper nodes;
 public QuotaActivationService(NodeCapabilityEntryMapper caps,ReleaseNodeMapper nodes,@Value("${flowgate.quota-activation-enabled:false}") boolean enabled,@Value("${FLOWGATE_QUOTA_TRAFFIC_NODES:}") String traffic){this.capabilities=caps;this.nodes=nodes;this.enabled=enabled;this.traffic=new HashSet<>();for(String n:traffic.split(","))if(!n.isBlank())this.traffic.add(n.trim());}
 private ConfigStateMapper releaseState; private ConfigReleaseMapper releaseFacts;
 @org.springframework.beans.factory.annotation.Autowired public void setReleaseFacts(ConfigStateMapper state,ConfigReleaseMapper releases){this.releaseState=state;this.releaseFacts=releases;}
 public boolean enabled(){return enabled;}
 public void record(String node,long version,String checksum,List<String> features){
  if(!enabled)return; // 未部署B表时旧V1默认关闭场景仍可正常上报。
  Rules.require(features==null||features.size()<=8,"能力列表过大");
  var list=features==null?List.<String>of():features;list.forEach(f->Rules.require(f!=null&&f.matches("[A-Z0-9_]{1,50}"),"能力字段非法"));
  var row=new NodeCapabilityEntry();row.nodeId=node;row.version=version;row.checksum=checksum;row.featuresJson=SnapshotCodec.JSON.writeValueAsString(list);row.lastSeenAt=Instant.now();
  if(capabilities.selectById(node)==null)capabilities.insert(row);else capabilities.updateById(row);
 }
 public void requireReady(long version,String checksum){
  if(!enabled)throw new ApiFailure(409,"QUOTA_FEATURE_NOT_READY","配额激活开关关闭，完整链路验证后才可启用");
  if(traffic.isEmpty())throw unavailable();
  // 1. 检查完整部署白名单，而非任意一个心跳；旧上报会用空features覆盖，不继承旧能力。
  for(String id:traffic){
   var c=capabilities.selectById(id);var n=nodes.selectOne(new QueryWrapper<ReleaseNode>().eq("node_id",id));
   if(c==null||n==null||c.version!=version||n.appliedVersion!=version||!checksum.equals(c.checksum)||!checksum.equals(n.checksum)
      ||Duration.between(c.lastSeenAt,Instant.now()).isNegative()||Duration.between(c.lastSeenAt,Instant.now()).getSeconds()>15
      ||n.lastSeenAt==null||Duration.between(n.lastSeenAt,LocalDateTime.now(ZoneOffset.UTC)).isNegative()||Duration.between(n.lastSeenAt,LocalDateTime.now(ZoneOffset.UTC)).getSeconds()>15
      ||!SnapshotCodec.JSON.readTree(c.featuresJson).toString().contains("\""+QuotaPolicy.FEATURE+"\""))throw unavailable();
  }
 }
 private ApiFailure unavailable(){return new ApiFailure(409,"GATEWAY_CAPABILITY_NOT_READY","接流节点名单、可信能力、心跳或应用版本未就绪");}
 /** 已认证状态API复用真正门禁只读检查，不以ONLINE或开关替代后端资格。 */
 public Object status(){
  var result=new LinkedHashMap<String,Object>();result.put("activationEnabled",enabled);result.put("trafficNodes",traffic);
  String status="UNKNOWN",code="NOT_VERIFIED",reason="当前发布资格未验证";
  // 1. 默认OFF不依赖新增表，不改变旧V1状态与安全撤销能力。
  if(!enabled){status="BLOCKED";code="QUOTA_FEATURE_NOT_READY";reason="计量关闭为安全默认；完整演示请使用隔离启动入口";}
  else if(releaseState!=null&&releaseFacts!=null){
   try{
    var current=releaseState.selectById(1);
    if(current==null||current.currentVersion==0){status="BLOCKED";code="INITIAL_RELEASE_REQUIRED";reason="先发布非计量初版并等待网关上报可信能力";}
    else{
     var release=releaseFacts.selectOne(new QueryWrapper<ConfigRelease>().eq("version",current.currentVersion));
     if(release!=null){
      // 2. 与签发/计量发布相同的完整名单、双时间戳、版本/checksum及能力检查。
      requireReady(current.currentVersion,release.sha256);status="READY";code="GATE_READY";reason="本次后端只读门禁通过；签发与发布仍会实时复验";
     }
    }
   }catch(ApiFailure blocked){status="BLOCKED";code=blocked.code;reason=blocked.getMessage();}
   catch(org.springframework.dao.DataAccessException unavailable){status="UNKNOWN";code="DEPENDENCY_UNAVAILABLE";reason="可信门禁数据未取得；请运行doctor检查表结构和数据库";}
  }
  // 3. 不暴露快照正文、内部Key或用户凭证，不写任何业务记录。
  result.put("readiness",status);result.put("code",code);result.put("reason",reason);return result;
 }
 public List<String> features(String node){if(!enabled)return List.of();var row=capabilities.selectById(node);return row==null?List.of():SnapshotCodec.JSON.readValue(row.featuresJson,List.class);}
}
