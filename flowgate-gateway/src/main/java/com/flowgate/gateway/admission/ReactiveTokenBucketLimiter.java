package com.flowgate.gateway.admission;

import com.flowgate.gateway.policy.EffectivePolicyResolver.Rule;
import java.time.Duration;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Reactive Redis 单 Key Lua；执行故障明确返回 unavailable，绝不静默放行。 */
@Component
public class ReactiveTokenBucketLimiter {
    private final ReactiveStringRedisTemplate redis;
    private final DefaultRedisScript<List> script;
    public record Decision(boolean allowed,boolean unavailable,long retryAfterMs,long remainingMilli) {}
    public ReactiveTokenBucketLimiter(ReactiveStringRedisTemplate redis) {
        this.redis=redis; script=new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/token-bucket.lua")); script.setResultType(List.class);
    }
    public Mono<Decision> check(Rule rule) {
        return redis.execute(script,List.of(rule.key()),List.of(Integer.toString(rule.capacity()),Integer.toString(rule.refillPerSecond())))
                .single().map(values -> {
                    if(values.size()!=3 || values.stream().anyMatch(v -> !(v instanceof Number))) throw new IllegalStateException("Lua返回类型不符");
                    return new Decision(((Number)values.get(0)).longValue()==1,false,
                            ((Number)values.get(1)).longValue(),((Number)values.get(2)).longValue());
                }).timeout(Duration.ofSeconds(2)).onErrorReturn(new Decision(false,true,0,0));
    }
}
