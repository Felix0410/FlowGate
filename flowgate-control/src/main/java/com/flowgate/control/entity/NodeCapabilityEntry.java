package com.flowgate.control.entity;
import com.baomidou.mybatisplus.annotation.*;
/** 仅内部认证节点上报的版本化能力，不从浏览器取得。 */
@TableName("fg_node_capability")
public class NodeCapabilityEntry {
 @TableId(type=IdType.INPUT) public String nodeId;
 public String featuresJson;public Long version;public String checksum;public java.time.Instant lastSeenAt;
}
