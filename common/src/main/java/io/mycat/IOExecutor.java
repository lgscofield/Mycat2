package io.mycat;

import io.mycat.beans.mycat.MycatErrorCode;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public interface IOExecutor {
    static final Logger LOGGER = LoggerFactory.getLogger(IOExecutor.class);

    /** 永不拒绝、不经准入闸。内层腿、释放腿、后台腿一律走这里（漏接闸只会 fail-open，不会死锁）。 */
    public <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler);

    /** 经准入闸：许可耗尽且等待超时后以 MYCAT-3007 失败，不抛异常给调用方。只用于最外层客户端入口。 */
    public <T> Future<T> executeAdmitted(Handler<Promise<T>> blockingCodeHandler);

    /** 真实在飞任务数：提交 +1，promise 完成 −1。 */
    long count();

    /** 历史最大在飞数，不随完成回落。 */
    long peakInFlight();

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

    /** 无配置时的兜底（类加载期与测试仍需要它），取配置默认值而非无界。 */
    public static final IOExecutor DEFAULT = new VtIOExecutor(null, IOExecutorConfig.defaults());

    public static IOExecutor create(IOExecutorConfig config) {
        return new VtIOExecutor(null, config);
    }

    public static IOExecutor fromVertx(Vertx vertx) {
        VertxMetrics metrics = (VertxMetrics) ((VertxImpl) vertx).getMetrics();
        IOExecutorConfig config = IOExecutorConfig.defaults();
        PoolMetrics<?> poolMetrics = metrics == null ? null
                : metrics.createPoolMetrics("io", "mycat.io-thread", (int) config.getPermits());
        return new VtIOExecutor(poolMetrics, config);
    }

}

/**
 * I-PR-1：DEFAULT / create / fromVertx 共用的具名虚拟线程骨架，避免多份实现漂移。
 * count()、许可、注册表、池指标四者同生命周期：提交时登记，promise 完成钩子里一并释放。
 */
final class VtIOExecutor implements IOExecutor {

    static final ConcurrentHashMap<Long, Thread> LIVE_VIRTUAL_THREADS = new ConcurrentHashMap<>();

    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("mycat-io-", 0).factory());
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong peak = new AtomicLong();
    private final PoolMetrics<?> poolMetrics;
    private final IOExecutorConfig config;
    private final Semaphore permits;

    VtIOExecutor(PoolMetrics<?> poolMetrics, IOExecutorConfig config) {
        this.poolMetrics = poolMetrics;
        this.config = config;
        this.permits = new Semaphore((int) config.getPermits());
    }

    @Override
    public <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler) {
        return submit(blockingCodeHandler, false);
    }

    @Override
    public <T> Future<T> executeAdmitted(Handler<Promise<T>> blockingCodeHandler) {
        return submit(blockingCodeHandler, true);
    }

    private <T> Future<T> submit(Handler<Promise<T>> blockingCodeHandler, boolean admitted) {
        PoolMetrics metrics = poolMetrics;
        Object queueMetric = metrics != null ? metrics.submitted() : null;
        peak.accumulateAndGet(count.incrementAndGet(), Math::max);
        Promise<T> promise = VertxUtil.newPromise();
        Future<T> fut = promise.future();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Object> execMetric = new AtomicReference<>();
        AtomicBoolean permitHeld = new AtomicBoolean();
        fut.onComplete(ar -> {
            count.decrementAndGet();
            Thread thread = worker.get();
            if (thread != null) {
                LIVE_VIRTUAL_THREADS.remove(thread.getId(), thread);
            }
            if (permitHeld.get()) {
                permits.release();
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
                // 许可只在 VT 内 acquire：调用线程可能是 event loop，绝不能被准入等待阻塞。
                if (admitted) {
                    if (!acquirePermit()) {
                        if (metrics != null) {
                            metrics.rejected(queueMetric);
                        }
                        promise.tryFail(MycatErrorCode.createMycatException(MycatErrorCode.ERR_IO_CONCURRENCY_LIMIT,
                                "IO 并发已达许可上界 " + config.getPermits() + "，等待 " + config.getAdmitTimeoutMillis()
                                        + "ms 仍未获准入；请稍后重试，或调大 workerPool.maxPoolSize"));
                        return;
                    }
                    permitHeld.set(true);
                }
                try {
                    blockingCodeHandler.handle(promise);
                } catch (Throwable e) {
                    promise.tryFail(e);
                }
            });
        } catch (RejectedExecutionException e) {
            // VT 每任务一线程，不抛 REE；该分支留给池关闭等极端情形。
            count.decrementAndGet();
            if (metrics != null) {
                metrics.rejected(queueMetric);
            }
            throw e;
        }
        return fut;
    }

    /** 不加第二套「许可最大持有超时」：泄漏由 I-DEBT-84 语句 deadline 兜底，两套回收逻辑会互相打架。 */
    private boolean acquirePermit() {
        try {
            return permits.tryAcquire(config.getAdmitTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public long count() {
        return count.get();
    }

    @Override
    public long peakInFlight() {
        return peak.get();
    }
}