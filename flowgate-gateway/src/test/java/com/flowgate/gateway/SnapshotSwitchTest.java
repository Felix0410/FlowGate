package com.flowgate.gateway;

import com.flowgate.contract.*;
import com.flowgate.gateway.config.*;
import com.flowgate.gateway.routing.SnapshotRouteDefinitionLocator;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.event.*;
import org.springframework.cloud.gateway.route.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 Gateway 5.0.4 缓存确认反馈；旧 Route 保留自己版本的权限。 */
class SnapshotSwitchTest {
    private Snapshot snapshot(long version, boolean isPublic) {
        return SnapshotCodec.seal(new Snapshot(1, version, "", List.of(), List.of(), List.of(new RouteSpec("fast", "GET",
                "/api/fast/{id}", "/demo/fast/{id}", "http://127.0.0.1:8085", isPublic, "demo:read", 0, version)), List.of(), List.of()));
    }
    @Test void confirmsOnlyActualCacheAndKeepsOldMetadataDuringConcurrentSwitch() throws Exception {
        // 1. 使用官方缓存与实际完成事件，不用模拟布尔值代替已应用反馈。
        var store = new ActiveSnapshotStore("http://127.0.0.1:8085");
        var definitions = new SnapshotRouteDefinitionLocator(store);
        RouteLocator delegate = () -> definitions.getRouteDefinitions().map(def -> Route.async(def).asyncPredicate(exchange -> reactor.core.publisher.Mono.just(true)).build());
        var cache = new CachingRouteLocator(delegate);
        SnapshotPollingService[] poller = new SnapshotPollingService[1];
        poller[0] = new SnapshotPollingService(store, event -> cache.onApplicationEvent((RefreshRoutesEvent)event),
                "http://127.0.0.1:8081", "x".repeat(32), "test-node");
        cache.setApplicationEventPublisher(event -> poller[0].refreshed((RefreshRoutesResultEvent)event));
        poller[0].apply(snapshot(1, true)).block();
        Route old = cache.getRoutes().blockFirst();
        // 2. 小规模并发读取旧 Route，同时把新版本权限切入缓存。
        var calls = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var oldRequest = calls.submit(() -> { for (int i=0;i<100;i++) {
                var metadata = (RouteSpec)old.getMetadata().get(SnapshotRouteDefinitionLocator.SPEC);
                assertEquals(1, metadata.configVersion()); assertTrue(metadata.isPublic());
            }});
            var update = calls.submit(() -> poller[0].apply(snapshot(2, false)).block());
            oldRequest.get(); update.get();
        } finally { calls.shutdownNow(); }
        var active = (RouteSpec)cache.getRoutes().blockFirst().getMetadata().get(SnapshotRouteDefinitionLocator.SPEC);
        // 3. 新缓存和旧在途对象分别保持其版本，旧版本回退请求不得替换新缓存。
        assertEquals(2, store.version()); assertEquals(2, active.configVersion()); assertFalse(active.isPublic());
        assertThrows(IllegalArgumentException.class, () -> store.stage(snapshot(1, true)));
        assertEquals(2, store.version());
    }
    @Test void rejectsBadSnapshotWithoutChangingLastValidState() {
        var store = new ActiveSnapshotStore("http://127.0.0.1:8085");
        Snapshot good = snapshot(1, true); store.stage(good); store.confirm(good);
        assertThrows(IllegalArgumentException.class, () -> store.stage(new Snapshot(1, 2, "bad", List.of(), List.of(), good.routes(), List.of(), List.of())));
        assertEquals(good, store.applied()); assertEquals(good, store.candidate());
        var evil = new RouteSpec("evil", "GET", "/api/a", "/demo/a", "http://127.0.0.1:8081", true, "", 0, 2);
        assertThrows(IllegalArgumentException.class, () -> store.stage(SnapshotCodec.seal(new Snapshot(1, 2, "", List.of(), List.of(), List.of(evil), List.of(), List.of()))));
        assertEquals(good, store.applied());
    }
    @Test void doesNotReportPendingOrFailedRefreshAsApplied() {
        var store = new ActiveSnapshotStore("http://127.0.0.1:8085");
        Snapshot good = snapshot(1, true); store.stage(good); store.confirm(good);
        var poller = new SnapshotPollingService(store, event -> {}, "http://127.0.0.1:8081", "x".repeat(32), "test-node");
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var pending = poller.apply(snapshot(2, false)).subscribe(ignored -> {}, failure::set);
        assertEquals(1, store.version());
        var cache = new CachingRouteLocator(() -> Flux.empty());
        poller.refreshed(new RefreshRoutesResultEvent(cache, new IllegalStateException("刷新失败")));
        assertNotNull(failure.get());
        assertEquals(good, store.applied()); assertEquals(good, store.candidate());
        pending.dispose();
    }
}
