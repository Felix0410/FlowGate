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
 public Object status(){return Map.of("activationEnabled",enabled,"trafficNodes",traffic,"note","完整白名单节点通过能力及版本检查后才能启用计量");}
 public List<String> features(String node){if(!enabled)return List.of();var row=capabilities.selectById(node);return row==null?List.of():SnapshotCodec.JSON.readValue(row.featuresJson,List.class);}
}
