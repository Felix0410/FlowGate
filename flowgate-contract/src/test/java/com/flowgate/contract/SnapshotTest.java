package com.flowgate.contract;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 跨进程协议、安全路径和确定性校验的定向测试。 */
class SnapshotTest {
    @Test void canonicalChecksumSurvivesMysqlFieldReordering() {
        var snapshot = SnapshotCodec.seal(new Snapshot(1, 1, "", List.of(), List.of(), List.of(), List.of(), List.of()));
        String reordered = "{\"routes\":[],\"policies\":[],\"bindings\":[],\"apis\":[],\"services\":[],\"checksum\":\"" + snapshot.checksum() + "\",\"version\":1,\"schemaVersion\":1}";
        assertDoesNotThrow(() -> SnapshotCodec.verify(SnapshotCodec.JSON.readValue(reordered, Snapshot.class)));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.verify(new Snapshot(1, 2, snapshot.checksum(), List.of(), List.of(), List.of(), List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.verify(SnapshotCodec.seal(new Snapshot(3, 1, "", List.of(), List.of(), List.of(), List.of(), List.of()))));
    }
    @Test void rejectsAmbiguousOrUnsafePathsAndTargets() {
        var allowed = Set.of("http://127.0.0.1:8085");
        for (String uri : List.of("http://localhost:8085", "http://127.0.0.1:8085/demo", "http://user@127.0.0.1:8085", "http://127.1:8085", "http://169.254.169.254:80", "http://127.0.0.1:8081")) {
            assertThrows(IllegalArgumentException.class, () -> Rules.upstream(uri, allowed));
        }
        for (String path : List.of("/api/**", "/api/{id:.*}", "/admin/a", "/api/%2e", "/api/{id}/{id}", "/api/a/")) {
            assertThrows(IllegalArgumentException.class, () -> Rules.paths("GET", path, "/demo/fast/{id}"));
        }
        var a = new RouteSpec("a", "GET", "/api/fast/{id}", "/demo/fast/{id}", "http://127.0.0.1:8085", true, "", 0, 1);
        var b = new RouteSpec("b", "GET", "/api/fast/fixed", "/demo/fast/fixed", "http://127.0.0.1:8085", true, "", 1, 1);
        assertThrows(IllegalArgumentException.class, () -> Rules.routes(List.of(a, b), 1, allowed));
        assertDoesNotThrow(() -> Rules.routes(List.of(a), 1, allowed));
    }
}
