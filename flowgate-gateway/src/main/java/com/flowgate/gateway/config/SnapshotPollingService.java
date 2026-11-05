package com.flowgate.gateway.config;

import com.flowgate.contract.*;
import com.flowgate.gateway.routing.SnapshotRouteDefinitionLocator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.event.*;
import org.springframework.cloud.gateway.route.CachingRouteLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.*;

/** 非阻塞拉取与应用反馈；刷新事件成功且缓存路由集匹配候选后，才上报 applied。 */
@Component
@EnableScheduling
public class SnapshotPollingService {
    private final ActiveSnapshotStore store;
    private final ApplicationEventPublisher events;
    private final WebClient client;
    private final String nodeId;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Sinks.One<Void> pending;
    private Disposable running;
    public SnapshotPollingService(ActiveSnapshotStore store, ApplicationEventPublisher events,
            @Value("${flowgate.control-uri}") String uri, @Value("${flowgate.internal-key}") String key,
            @Value("${flowgate.node-id}") String nodeId) {
        Rules.require(key.length() >= 32, "内部密钥至少32字符");
        this.store = store; this.events = events; this.nodeId = nodeId;
        // Boot 4 的 Gateway Starter 不保证自动注册 WebClient.Builder；复用已传递的 WebClient 即可。
        this.client = WebClient.builder().baseUrl(uri).defaultHeader("X-FlowGate-Internal-Key", key).build();
    }

    @Scheduled(fixedDelayString = "${flowgate.poll-ms:5000}")
    public void poll() {
        if (!busy.compareAndSet(false, true)) return;
        running = client.get().uri("/internal/snapshots/current").retrieve().bodyToMono(Snapshot.class).timeout(Duration.ofSeconds(3))
                .flatMap(next -> next.version() > store.version() ? apply(next) : Mono.empty())
                .then(Mono.defer(this::report)).doFinally(signal -> busy.set(false))
                // 依赖故障保留内存有效路由；不输出含密钥/响应体的原始异常。
                .subscribe(ignored -> {}, error -> org.slf4j.LoggerFactory.getLogger(getClass()).warn("配置同步或节点上报未完成，保留最近有效路由"));
    }

    public Mono<Void> apply(Snapshot next) {
        return Mono.defer(() -> {
            // 1. 完整校验后暂存候选；当前请求仍由已匹配的旧 Route 持有旧权限。
            store.stage(next);
            Sinks.One<Void> completion = Sinks.one(); pending = completion;
            // 2. 使用官方缓存刷新；等待结果事件，不把发送刷新事件当成应用成功。
            events.publishEvent(new RefreshRoutesEvent(this));
            return completion.asMono().timeout(Duration.ofSeconds(3)).doOnSuccess(ignored -> store.confirm(next))
                    .doOnError(error -> store.restore()).doFinally(signal -> pending = null);
        });
    }

    /** 5.0.4 在缓存 put 后发布成功事件；仍比对缓存中的完整 RouteSpec，避免无关刷新误确认。 */
    @EventListener
    public void refreshed(RefreshRoutesResultEvent event) {
        Sinks.One<Void> completion = pending;
        Snapshot candidate = store.candidate();
        if (completion == null || candidate == null || !(event.getSource() instanceof CachingRouteLocator cache)) return;
        if (!event.isSuccess()) { completion.tryEmitError(new IllegalStateException("网关缓存刷新失败")); return; }
        cache.getRoutes().collectList().subscribe(routes -> {
            // 3. 缓存必须恰为候选的所有路由及版本权限；首次无规则也不会凭空开启公共代理。
            var actual = routes.stream().map(route -> route.getMetadata().get(SnapshotRouteDefinitionLocator.SPEC)).toList();
            if (actual.size() == candidate.routes().size() && new HashSet<>(actual).equals(new HashSet<>(candidate.routes()))) {
                completion.tryEmitEmpty();
            }
        }, completion::tryEmitError);
    }

    private Mono<Void> report() {
        Snapshot current = store.applied();
        if (current == null) return Mono.empty();
        return client.post().uri("/internal/nodes/applied").bodyValue(Map.of("nodeId", nodeId, "version", current.version(), "checksum", current.checksum()))
                .retrieve().toBodilessEntity().timeout(Duration.ofSeconds(3)).then();
    }
    @PreDestroy public void close() { if (running != null) running.dispose(); }
}
