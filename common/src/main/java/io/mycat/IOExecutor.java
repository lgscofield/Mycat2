package io.mycat;

import io.mycat.config.ThreadPoolExecutorConfig;
import io.mycat.util.VertxUtil;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxImpl;
import io.vertx.core.spi.metrics.PoolMetrics;
import io.vertx.core.spi.metrics.VertxMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public interface IOExecutor {
    static final Logger LOGGER = LoggerFactory.getLogger(IOExecutor.class);

    public <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler);

    long count();

    public static List<Thread> findAllThreads() {
        // VT 不属于任何 ThreadGroup，ThreadUtil 的 enumerate 看不见它们，故并上活体注册表，按 id 去重。
        Map<Long, Thread> byId = new LinkedHashMap<>();
        for (Thread thread : ThreadUtil.findAllThreads()) {
            byId.put(thread.getId(), thread);
        }
        for (Thread thread : VtIOExecutor.LIVE_VIRTUAL_THREADS.values()) {
            byId.putIfAbsent(thread.getId(), thread);
        }
        return new ArrayList<>(byId.values());
    }

    public static boolean interrupt(long pid) {
        return interruptById(pid, "interrupt");
    }

    public static boolean kill(long pid) {
        // kill 的实际效果仍只是 interrupt；名实不符已挂账 spec §11，本票不改行为。
        return interruptById(pid, "kill");
    }

    private static boolean interruptById(long pid, String verb) {
        Thread thread = VtIOExecutor.LIVE_VIRTUAL_THREADS.get(pid);
        if (thread == null) {
            thread = Arrays.stream(ThreadUtil.findAllThreads())
                    .filter(i -> i.getId() == pid).findFirst().orElse(null);
        }
        if (thread == null) {
            return false;
        }
        try {
            LOGGER.info("prepare {} id:{}", verb, thread.getId());
            thread.interrupt();
            LOGGER.info("{} id:{}", verb, thread.getId());
            return true;
        } catch (Exception e) {
            LOGGER.error("{} id:{} fail", verb, thread.getId(), e);
        }
        return false;
    }

    public static final IOExecutor DEFAULT = new VtIOExecutor(null);

    public static IOExecutor fromVertx(Vertx vertx) {
        VertxMetrics metrics = (VertxMetrics) ((VertxImpl) vertx).getMetrics();
        PoolMetrics<?> poolMetrics = metrics == null ? null
                : metrics.createPoolMetrics("io", "mycat.io-thread", new ThreadPoolExecutorConfig().getMaxPoolSize());
        return new VtIOExecutor(poolMetrics);
    }

}

/**
 * I-PR-1：DEFAULT 与 fromVertx 共用的具名虚拟线程骨架，避免两份实现漂移。
 * count() 是真实在飞任务数：提交 +1，promise 完成 −1；在 handler 返回时减是既有 bug 的病根。
 */
final class VtIOExecutor implements IOExecutor {

    static final ConcurrentHashMap<Long, Thread> LIVE_VIRTUAL_THREADS = new ConcurrentHashMap<>();

    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("mycat-io-", 0).factory());
    private final AtomicLong count = new AtomicLong();
    private final PoolMetrics<?> poolMetrics;

    VtIOExecutor(PoolMetrics<?> poolMetrics) {
        this.poolMetrics = poolMetrics;
    }

    @Override
    public <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler) {
        PoolMetrics metrics = poolMetrics;
        Object queueMetric = metrics != null ? metrics.submitted() : null;
        count.incrementAndGet();
        Promise<T> promise = VertxUtil.newPromise();
        Future<T> fut = promise.future();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Object> execMetric = new AtomicReference<>();
        fut.onComplete(ar -> {
            count.decrementAndGet();
            Thread thread = worker.get();
            if (thread != null) {
                LIVE_VIRTUAL_THREADS.remove(thread.getId(), thread);
            }
            if (metrics != null) {
                metrics.end(execMetric.get(), fut.succeeded());
            }
        });
        try {
            executorService.execute(() -> {
                Thread current = Thread.currentThread();
                worker.set(current);
                LIVE_VIRTUAL_THREADS.put(current.getId(), current);
                if (metrics != null) {
                    execMetric.set(metrics.begin(queueMetric));
                }
                try {
                    blockingCodeHandler.handle(promise);
                } catch (Throwable e) {
                    promise.tryFail(e);
                }
            });
        } catch (RejectedExecutionException e) {
            // VT 每任务一线程，不抛 REE；该分支留给准入拒绝复用。
            count.decrementAndGet();
            if (metrics != null) {
                metrics.rejected(queueMetric);
            }
            throw e;
        }
        return fut;
    }

    @Override
    public long count() {
        return count.get();
    }
}