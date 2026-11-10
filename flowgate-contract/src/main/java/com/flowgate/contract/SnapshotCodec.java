package com.flowgate.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 两端共用的确定性 JSON：对象键按字典序，数组保持稳定 ID 顺序，校验排除最外层 checksum。 */
public final class SnapshotCodec {
    public static final ObjectMapper JSON = new ObjectMapper();
    private SnapshotCodec() {}

    /** 递归排序对象字段，不修改业务数组或输入节点。 */
    public static JsonNode canonical(JsonNode node) {
        // 1. 对象字段递归排序，抵消 MySQL JSON 存储重排与两端序列化顺序差异。
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonical(value)));
            return sorted;
        }
        if (node.isArray()) {
            // 2. 数组顺序由发布者按稳定 ID 确定，编码层不擅自重排。
            var sorted = JSON.createArrayNode();
            node.forEach(value -> sorted.add(canonical(value)));
            return sorted;
        }
        // 3. 字符串、数字及布尔值保留原始 JSON 类型。
        return node;
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String checksum(Snapshot snapshot) {
        ObjectNode payload = JSON.valueToTree(snapshot);
        payload.remove("checksum");
        return digest(JSON.writeValueAsString(canonical(payload)));
    }

    public static Snapshot seal(Snapshot snapshot) {
        return new Snapshot(snapshot.schemaVersion(), snapshot.version(), checksum(snapshot), snapshot.services(),
                snapshot.apis(), snapshot.routes(), snapshot.policies(), snapshot.bindings());
    }

    public static void verify(Snapshot snapshot) {
        if ((snapshot.schemaVersion() != 1 && snapshot.schemaVersion() != 2) || snapshot.version() < 1 || !checksum(snapshot).equals(snapshot.checksum())) {
            throw new IllegalArgumentException("快照版本格式或校验和错误");
        }
    }
}
