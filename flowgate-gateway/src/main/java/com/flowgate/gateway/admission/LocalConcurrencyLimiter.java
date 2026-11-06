package com.flowgate.gateway.admission;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.flowgate.gateway.policy.EffectivePolicyResolver.Rule;

/** 单实例非阻塞在途保护；相同规则复用池，旧 Route/请求持有旧池直到终止。 */
public final class LocalConcurrencyLimiter {
    private static final Map<String,WeakReference<Pool>> POOLS=new HashMap<>();
    private LocalConcurrencyLimiter() {}
    /** 只在配置构建阶段同步，不在 Netty 请求热路径持锁；弱引用避免历史配置常驻。 */
    public static synchronized Pool pool(String key,int max) {
        POOLS.entrySet().removeIf(e -> e.getValue().get()==null);
        var reference=POOLS.get(key); Pool pool=reference==null ? null : reference.get();
        if(pool==null) { pool=new Pool(max); POOLS.put(key,new WeakReference<>(pool)); }
        return pool;
    }
    public static final class Pool {
        private final int max;
        private final AtomicInteger inFlight=new AtomicInteger();
        public Pool(int max) { if(max<1) throw new IllegalArgumentException("并发上限必须为正"); this.max=max; }
        public boolean tryAcquire() {
            int current;
            do { current=inFlight.get(); if(current>=max) return false; }
            while(!inFlight.compareAndSet(current,current+1));
            return true;
        }
        private void release() { inFlight.decrementAndGet(); }
        public int inFlight() { return inFlight.get(); }
    }
    /** 一个请求的全部许可，部分获取失败及所有 Reactor 终止路径均可幂等归还。 */
    public static final class Lease implements AutoCloseable {
        private final List<Pool> held=new ArrayList<>();
        private final AtomicBoolean closed=new AtomicBoolean();
        public Rule acquire(List<Rule> rules) {
            // 1. 调用者提供 SERVICE→ROUTE 顺序；失败时不排队，立即释放前层。
            for(Rule rule:rules) if(rule.type().equals("CONCURRENCY")) {
                if(!rule.pool().tryAcquire()) { close(); return rule; }
                held.add(rule.pool());
            }
            return null;
        }
        @Override public void close() {
            // 2. 每个请求最多释放一次，不能把旧请求释放到新版本池。
            if(closed.compareAndSet(false,true)) held.forEach(Pool::release);
        }
    }
}
