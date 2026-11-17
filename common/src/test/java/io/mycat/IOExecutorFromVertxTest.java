package io.mycat;

import io.mycat.config.ThreadPoolExecutorConfig;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.core.spi.VertxMetricsFactory;
import io.vertx.core.spi.metrics.PoolMetrics;
import io.vertx.core.spi.metrics.VertxMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 3：fromVertx 零生产调用者，不测就是修了一段没人跑过的代码。
 * 用真实 Vertx 实例 + 手写记录型 metrics 桩（SPI 全是 default 方法，无需 mockito）行使同形全套。
 */
class IOExecutorFromVertxTest {

    private static final int K = 8;
    private static final long AWAIT_SECONDS = 5;

    private static final List<PoolCall> POOL_CALLS = new CopyOnWriteArrayList<>();
    private static Vertx vertx;
    private static IOExecutor ex;

    record PoolCall(String type, String name, int bound) {
    }

    /** 只记录 createPoolMetrics 的入参；其余 SPI 方法全用 default 空实现。 */
    static final class RecordingMetrics implements VertxMetrics {
        @Override
        public PoolMetrics<?> createPoolMetrics(String type, String name, int maxSize) {
            POOL_CALLS.add(new PoolCall(type, name, maxSize));
            return new PoolMetrics<Object>() {
            };
        }
    }

    static final class RecordingFactory implements VertxMetricsFactory {
        @Override
        public VertxMetrics metrics(VertxOptions options) {
            return new RecordingMetrics();
        }
    }

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx(new VertxOptions().setMetricsOptions(
                new MetricsOptions().setEnabled(true).setFactory(new RecordingFactory())));
        ex = IOExecutor.fromVertx(vertx);
    }

    @AfterAll
    static void stopVertx() throws Exception {
        if (vertx != null) {
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close().onComplete(ar -> closed.countDown());
            assertTrue(closed.await(20, TimeUnit.SECONDS), "Vertx 未在 20s 内关闭");
        }
    }

    @Test
    void poolMetricsBoundIsNotMaxValue() {
        PoolCall io = POOL_CALLS.stream()
                .filter(c -> "io".equals(c.type()) && "mycat.io-thread".equals(c.name()))
                .findFirst()
                .orElse(null);
        assertTrue(io != null, "fromVertx 必须登记 io/mycat.io-thread 池 metrics，实得 " + POOL_CALLS);
        assertNotEquals(Integer.MAX_VALUE, io.bound(),
                "池上界不得是 Integer.MAX_VALUE（那是名实相符谎言）");
        assertEquals(new ThreadPoolExecutorConfig().getMaxPoolSize(), io.bound(),
                "上界必须与 workerPool.maxPoolSize 默认值同源（D4），不得另写硬编码");
    }

    @Test
    void countTracksInFlightForAsyncBody() throws Exception {
        Promise<?>[] slot = new Promise<?>[K];
        CountDownLatch submitted = new CountDownLatch(K);
        try {
            for (int i = 0; i < K; i++) {
                final int idx = i;
                ex.executeBlocking(promise -> {
                    slot[idx] = promise;
                    submitted.countDown();
                });
            }
            assertTrue(submitted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "K 个 body 未在 5s 内全部执行");
            assertEquals(K, ex.count(), "异步型 body 未 complete promise 时 count() 必须仍计为在飞");
            for (int i = 0; i < K; i++) {
                slot[i].tryComplete();
                awaitCount(K - (i + 1L));
            }
            assertEquals(0, ex.count(), "全部 promise 完成后 count() 必须归零");
        } finally {
            for (Promise<?> p : slot) {
                if (p != null) {
                    p.tryComplete();
                }
            }
        }
    }

    @Test
    void tasksRunOnNamedVirtualThreads() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean();
        AtomicReference<String> name = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        ex.executeBlocking(promise -> {
            Thread t = Thread.currentThread();
            virtual.set(t.isVirtual());
            name.set(t.getName());
            promise.tryComplete();
            done.countDown();
        });
        assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "任务未在 5s 内完成");

        assertTrue(virtual.get(), "fromVertx 的任务也必须跑在虚拟线程上");
        assertTrue(name.get() != null && name.get().startsWith("mycat-io-"),
                "线程名必须以 mycat-io- 开头，实得 " + name.get());
    }

    @Test
    void findAllThreadsSeesLiveVirtualThread() throws Exception {
        AtomicLong vtId = new AtomicLong(-1);
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        try {
            ex.executeBlocking(promise -> {
                vtId.set(Thread.currentThread().getId());
                inBody.countDown();
                try {
                    gate.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                promise.tryComplete();
                done.countDown();
            });
            assertTrue(inBody.await(AWAIT_SECONDS, TimeUnit.SECONDS), "body 未在 5s 内进入阻塞");
            long id = vtId.get();
            assertTrue(containsThread(id), "fromVertx 的活体 VT 也必须能被 findAllThreads() 发现");
            gate.countDown();
            assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "body 未在 5s 内完成");
            awaitCount(0);
            assertFalse(containsThread(id), "promise 完成后注册表必须移除该 VT，不得泄漏");
        } finally {
            gate.countDown();
        }
    }

    @Test
    void interruptFindsVirtualThreadById() throws Exception {
        AtomicLong vtId = new AtomicLong(-1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        try {
            ex.executeBlocking(promise -> {
                vtId.set(Thread.currentThread().getId());
                inBody.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                promise.tryComplete();
                done.countDown();
            });
            assertTrue(inBody.await(AWAIT_SECONDS, TimeUnit.SECONDS), "body 未在 5s 内进入阻塞");
            assertTrue(IOExecutor.interrupt(vtId.get()), "interrupt(vtId) 必须先查 VT 注册表并命中");
            assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "被 interrupt 的 body 未在 5s 内退出");
            assertTrue(interrupted.get(), "VT 内必须观测到 InterruptedException");
        } finally {
            gate.countDown();
        }
    }

    private static boolean containsThread(long id) {
        return IOExecutor.findAllThreads().stream().anyMatch(t -> t.getId() == id);
    }

    private static void awaitCount(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (ex.count() != expected) {
            if (System.nanoTime() > deadline) {
                fail("count() 未在 " + AWAIT_SECONDS + "s 内变为 " + expected + "，实得 " + ex.count());
            }
            Thread.sleep(5);
        }
    }
}