package io.mycat;

import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 3：IOExecutor 换具名虚拟线程 + count() 名实相符 + VT 注册表补线程发现。
 * 全部用 DEFAULT（生产唯一接线点 MycatCore:135），不依赖活体数据库。
 */
class IOExecutorVtTest {

    private static final int K = 8;
    private static final long AWAIT_SECONDS = 5;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void releaseEverything() {
        for (Runnable r : cleanups) {
            r.run();
        }
        cleanups.clear();
    }

    /**
     * 关键测试：body 立即返回但不 complete promise（异步型），任务仍在飞。
     * 既有实现在提交后立刻 decrementAndGet ⇒ count≈0，此断言必失败。
     */
    @Test
    void countTracksInFlightForAsyncBody() throws Exception {
        IOExecutor ex = IOExecutor.DEFAULT;
        Promise<?>[] slot = new Promise<?>[K];
        CountDownLatch submitted = new CountDownLatch(K);
        cleanups.add(() -> {
            for (Promise<?> p : slot) {
                if (p != null) {
                    p.tryComplete();
                }
            }
        });

        for (int i = 0; i < K; i++) {
            final int idx = i;
            ex.executeBlocking(promise -> {
                slot[idx] = promise;
                submitted.countDown();
            });
        }
        assertTrue(submitted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "K 个 body 未在 5s 内全部执行");

        // 提交侧立即断言：count 在提交时 +1，body 返回不减，故此刻必为 K。
        assertEquals(K, ex.count(), "异步型 body 未 complete promise 时 count() 必须仍计为在飞");

        for (int i = 0; i < K; i++) {
            slot[i].tryComplete();
            awaitCount(ex, K - (i + 1L));
        }
        assertEquals(0, ex.count(), "全部 promise 完成后 count() 必须归零");
    }

    @Test
    void countTracksInFlightForBlockingBody() throws Exception {
        IOExecutor ex = IOExecutor.DEFAULT;
        CountDownLatch inBody = new CountDownLatch(K);
        CountDownLatch gate = new CountDownLatch(1);
        cleanups.add(gate::countDown);

        for (int i = 0; i < K; i++) {
            ex.executeBlocking(promise -> {
                inBody.countDown();
                try {
                    gate.await();
                    promise.tryComplete();
                } catch (InterruptedException e) {
                    promise.tryFail(e);
                }
            });
        }
        assertTrue(inBody.await(AWAIT_SECONDS, TimeUnit.SECONDS), "K 个 body 未在 5s 内全部进入阻塞");
        assertEquals(K, ex.count(), "阻塞型 body 卡住期间 count() 必须为 K");

        gate.countDown();
        awaitCount(ex, 0);
    }

    @Test
    void tasksRunOnNamedVirtualThreads() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean();
        AtomicReference<String> name = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        IOExecutor.DEFAULT.executeBlocking(promise -> {
            Thread t = Thread.currentThread();
            virtual.set(t.isVirtual());
            name.set(t.getName());
            promise.tryComplete();
            done.countDown();
        });
        assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "任务未在 5s 内完成");

        assertTrue(virtual.get(), "任务必须跑在虚拟线程上（VT-only，D6 不留平台回退）");
        assertTrue(name.get() != null && name.get().startsWith("mycat-io-"),
                "线程名必须以 mycat-io- 开头，实得 " + name.get());
    }

    @Test
    void findAllThreadsSeesLiveVirtualThread() throws Exception {
        IOExecutor ex = IOExecutor.DEFAULT;
        AtomicLong vtId = new AtomicLong(-1);
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        cleanups.add(gate::countDown);

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
        assertTrue(containsThread(id), "活体 VT 必须能被 findAllThreads() 发现（VT 不在任何 ThreadGroup 里）");

        gate.countDown();
        assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "body 未在 5s 内完成");
        awaitCount(ex, 0);
        assertFalse(containsThread(id), "promise 完成后注册表必须移除该 VT，不得泄漏");
    }

    /** 不回退锁：钉住「合并而非替换」，ThreadUtil 的平台线程发现必须原样保留。 */
    @Test
    void findAllThreadsStillSeesPlatformThreads() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Thread platform = new Thread(() -> {
            try {
                gate.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "io-executor-platform-probe");
        platform.setDaemon(true);
        platform.start();
        cleanups.add(gate::countDown);
        try {
            assertTrue(containsThread(platform.getId()),
                    "具名平台线程必须仍在 findAllThreads() 结果里（ThreadUtil 不改一字）");
        } finally {
            gate.countDown();
            platform.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
    }

    @Test
    void interruptFindsVirtualThreadById() throws Exception {
        AtomicLong vtId = new AtomicLong(-1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        cleanups.add(gate::countDown);

        IOExecutor.DEFAULT.executeBlocking(promise -> {
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
    }

    /**
     * 不回退锁：kill 的行为仍只是 interrupt。
     * 这是如实记录既有语义而非认可命名——kill(pid)==interrupt(pid) 的名实不符已挂账 spec §11，本票不修。
     */
    @Test
    void killBehavesAsInterrupt() throws Exception {
        AtomicLong vtId = new AtomicLong(-1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        cleanups.add(gate::countDown);

        IOExecutor.DEFAULT.executeBlocking(promise -> {
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

        assertTrue(IOExecutor.kill(vtId.get()), "kill(vtId) 必须先查 VT 注册表并命中");
        assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "被 kill 的 body 未在 5s 内退出");
        assertTrue(interrupted.get(), "kill 的实际效果必须只是 interrupt（不 stop、不销毁）");
    }

    private static boolean containsThread(long id) {
        return IOExecutor.findAllThreads().stream().anyMatch(t -> t.getId() == id);
    }

    /** 完成钩子若被派发到别的线程，count 递减会有微小延迟；轮询到期望值，5s 上限。 */
    private static void awaitCount(IOExecutor ex, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (ex.count() != expected) {
            if (System.nanoTime() > deadline) {
                fail("count() 未在 " + AWAIT_SECONDS + "s 内变为 " + expected + "，实得 " + ex.count());
            }
            Thread.sleep(5);
        }
    }
}