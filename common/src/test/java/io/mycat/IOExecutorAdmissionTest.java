package io.mycat;

import io.mycat.beans.mycat.MycatErrorCode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 4：准入闸 executeAdmitted。每个测试用独立 create(cfg) 实例，许可与 count 互不干扰。
 * 不依赖活体数据库。
 */
class IOExecutorAdmissionTest {

    private static final long AWAIT_SECONDS = 5;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void releaseEverything() {
        for (Runnable r : cleanups) {
            r.run();
        }
        cleanups.clear();
    }

    @Test
    void rejectsBeyondPermitsWith3007() throws Exception {
        IOExecutor ex = IOExecutor.create(new IOExecutorConfig(4, 200));
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch inBody = new CountDownLatch(4);
        cleanups.add(gate::countDown);

        for (int i = 0; i < 4; i++) {
            ex.executeAdmitted(stuck(gate, inBody));
        }
        assertTrue(inBody.await(AWAIT_SECONDS, TimeUnit.SECONDS), "前 4 个未在 5s 内全部在飞");
        assertEquals(4, ex.count());

        Future<Object> fifth = ex.executeAdmitted(promise -> promise.tryComplete());
        assertIoLimit(awaitFailure(fifth, 3));
    }

    /** Review Focus #1：许可必须在 promise 完成时才释放，不是在 handler 返回时。 */
    @Test
    void permitHeldUntilPromiseCompletesForAsyncBody() throws Exception {
        IOExecutor ex = IOExecutor.create(new IOExecutorConfig(4, 200));
        Promise<?>[] slot = new Promise<?>[4];
        CountDownLatch submitted = new CountDownLatch(4);
        cleanups.add(() -> {
            for (Promise<?> p : slot) {
                if (p != null) {
                    p.tryComplete();
                }
            }
        });

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            ex.executeAdmitted(promise -> {
                slot[idx] = promise;
                submitted.countDown();
            });
        }
        assertTrue(submitted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "4 个异步型 body 未在 5s 内全部执行");
        assertEquals(4, ex.count(), "handler 已返回但 promise 未完成，任务仍在飞");

        Future<Object> fifth = ex.executeAdmitted(promise -> promise.tryComplete());
        assertIoLimit(awaitFailure(fifth, 3));

        slot[0].tryComplete();
        awaitCount(ex, 3);
        Future<Object> sixth = ex.executeAdmitted(promise -> promise.tryComplete());
        assertTrue(awaitSuccess(sixth, 3), "释放一个许可后，新任务必须能准入");
    }

    @Test
    void admitWaitIsBoundedByTimeout() throws Exception {
        IOExecutor ex = IOExecutor.create(new IOExecutorConfig(1, 200));
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch inBody = new CountDownLatch(1);
        cleanups.add(gate::countDown);

        ex.executeAdmitted(stuck(gate, inBody));
        assertTrue(inBody.await(AWAIT_SECONDS, TimeUnit.SECONDS), "占位任务未在 5s 内在飞");

        long t0 = System.nanoTime();
        Future<Object> second = ex.executeAdmitted(promise -> promise.tryComplete());
        Throwable cause = awaitFailure(second, 3);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertIoLimit(cause);
        assertTrue(elapsedMs >= 200, "必须是带超时的等待而非立即拒绝，实测 " + elapsedMs + "ms");
        assertTrue(elapsedMs < 2000, "等待必须有界而非无限等，实测 " + elapsedMs + "ms");
    }

    @Test
    void eventLoopIsNotBlockedByAdmission() throws Exception {
        IOExecutor ex = IOExecutor.create(new IOExecutorConfig(1, 200));
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch inBody = new CountDownLatch(1);
        cleanups.add(gate::countDown);

        ex.executeAdmitted(stuck(gate, inBody));
        assertTrue(inBody.await(AWAIT_SECONDS, TimeUnit.SECONDS), "占位任务未在 5s 内在飞");

        ExecutorService eventLoop = Executors.newSingleThreadExecutor();
        try {
            AtomicLong elapsedMs = new AtomicLong();
            AtomicReference<Future<Object>> ref = new AtomicReference<>();
            CountDownLatch called = new CountDownLatch(1);
            eventLoop.execute(() -> {
                long t0 = System.nanoTime();
                ref.set(ex.executeAdmitted(promise -> promise.tryComplete()));
                elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                called.countDown();
            });
            assertTrue(called.await(AWAIT_SECONDS, TimeUnit.SECONDS), "模拟 event loop 未在 5s 内执行到调用");
            assertTrue(elapsedMs.get() < 100,
                    "调用线程绝不得 acquire 许可（会阻塞 event loop），实测耗时 " + elapsedMs.get() + "ms");
            assertIoLimit(awaitFailure(ref.get(), 3));
        } finally {
            eventLoop.shutdownNow();
        }
    }

    /** Review Focus #4 / 不变式 A2：释放腿与内层腿走 executeBlocking，闸饱和也永不拒绝。 */
    @Test
    void executeBlockingNeverRejectsWhenGateSaturated() throws Exception {
        IOExecutor ex = IOExecutor.create(new IOExecutorConfig(2, 200));
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch inBody = new CountDownLatch(2);
        cleanups.add(gate::countDown);

        for (int i = 0; i < 2; i++) {
            ex.executeAdmitted(stuck(gate, inBody));
        }
        assertTrue(inBody.await(AWAIT_SECONDS, TimeUnit.SECONDS), "占满许可的 2 个任务未在 5s 内在飞");

        int n = 20;
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            ex.executeBlocking(promise -> promise.tryComplete())
                    .onComplete(ar -> {
                        if (ar.failed()) {
                            failures.incrementAndGet();
                        }
                        done.countDown();
                    });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "20 个 executeBlocking 未在 10s 内全部完成");
        assertEquals(0, failures.get(), "executeBlocking 不经闸，闸饱和时也不得拒绝任何一个");
    }

    @Test
    void countAndPeakInFlightReflectAdmittedTasks() throws Exception {
        IOExecutor ex = IOExecutor.create(new IOExecutorConfig(4, 200));
        assertEquals(0, ex.count());
        assertEquals(0, ex.peakInFlight());

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch inBody = new CountDownLatch(3);
        cleanups.add(gate::countDown);
        for (int i = 0; i < 3; i++) {
            ex.executeAdmitted(stuck(gate, inBody));
        }
        assertTrue(inBody.await(AWAIT_SECONDS, TimeUnit.SECONDS), "3 个任务未在 5s 内全部在飞");
        assertEquals(3, ex.count());
        assertEquals(3, ex.peakInFlight());

        gate.countDown();
        awaitCount(ex, 0);
        assertEquals(3, ex.peakInFlight(), "peakInFlight 是历史最大值，不随完成回落");
    }

    /**
     * A10：一条客户端语句会嵌套 3–4 层 executeBlocking（前端 → SQLHandler → wrapper → JdbcMySqlConnection）。
     * 内层腿不经闸，故许可被外层占满时嵌套仍能跑通。
     */
    @Test
    void nestedExecuteBlockingUnderSaturationDoesNotDeadlock() throws Exception {
        IOExecutor ex = IOExecutor.create(new IOExecutorConfig(2, 200));
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch outerStarted = new CountDownLatch(2);
        CountDownLatch innerDone = new CountDownLatch(2);
        CountDownLatch allDone = new CountDownLatch(2);
        cleanups.add(gate::countDown);

        for (int i = 0; i < 2; i++) {
            ex.executeAdmitted(outer -> {
                outerStarted.countDown();
                ex.executeBlocking(l1 -> {
                    ex.executeBlocking(l2 -> {
                        ex.executeBlocking(l3 -> {
                            try {
                                gate.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            l3.tryComplete();
                            innerDone.countDown();
                        });
                        l2.tryComplete();
                    });
                    l1.tryComplete();
                });
                try {
                    innerDone.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                outer.tryComplete();
            }).onComplete(ar -> allDone.countDown());
        }
        assertTrue(outerStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "2 个外层任务未在 5s 内启动");

        // 许可确已被两个外层占满：第三个准入请求必须被拒。
        assertIoLimit(awaitFailure(ex.executeAdmitted(promise -> promise.tryComplete()), 3));

        gate.countDown();
        assertTrue(allDone.await(10, TimeUnit.SECONDS), "内层走 executeBlocking 时，饱和下嵌套不得死锁");
        assertEquals(0, innerDone.getCount(), "最内层必须真的跑到，而不是外层超时放弃");
    }

    /**
     * 反向对照：同样嵌套但内层也经闸 ⇒ 外层占满许可后内层永远拿不到，观察到挂住。
     * 这把「内层不得加闸」钉成可执行证据而非注释。外层用 3s 有界等待自行解开，绝不挂死测试进程。
     */
    @Test
    void nestedExecuteAdmittedWouldDeadlock_documentsWhyInnerLegsAreUngated() throws Exception {
        IOExecutor ex = IOExecutor.create(new IOExecutorConfig(2, 200));
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch outerStarted = new CountDownLatch(2);
        CountDownLatch innerSubmitted = new CountDownLatch(2);
        CountDownLatch innerDone = new CountDownLatch(2);
        CountDownLatch allDone = new CountDownLatch(2);
        List<Future<?>> innerFutures = Collections.synchronizedList(new ArrayList<>());
        cleanups.add(gate::countDown);

        for (int i = 0; i < 2; i++) {
            ex.executeAdmitted(outer -> {
                outerStarted.countDown();
                Future<Object> l1 = ex.executeAdmitted(p1 -> {
                    ex.executeAdmitted(p2 -> {
                        ex.executeAdmitted(p3 -> {
                            try {
                                gate.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            p3.tryComplete();
                            innerDone.countDown();
                        });
                        p2.tryComplete();
                    });
                    p1.tryComplete();
                });
                innerFutures.add(l1);
                innerSubmitted.countDown();
                try {
                    innerDone.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                outer.tryComplete();
            }).onComplete(ar -> allDone.countDown());
        }
        assertTrue(outerStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "2 个外层任务未在 5s 内启动");
        assertTrue(innerSubmitted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "2 个内层准入请求未在 5s 内提交");

        for (Future<?> f : innerFutures) {
            assertIoLimit(awaitFailure(f, 3));
        }
        assertEquals(2, allDone.getCount(), "内层经闸时必须观察到外层挂住（这正是内层不得加闸的理由）");
        assertEquals(2, innerDone.getCount(), "最内层根本跑不到");

        assertTrue(allDone.await(6, TimeUnit.SECONDS), "外层 3s 有界等待必须自行解开，不得挂死测试进程");
    }

    private static Handler<Promise<Object>> stuck(CountDownLatch gate, CountDownLatch inBody) {
        return promise -> {
            inBody.countDown();
            try {
                gate.await();
                promise.tryComplete();
            } catch (InterruptedException e) {
                promise.tryFail(e);
            }
        };
    }

    private static void assertIoLimit(Throwable cause) {
        assertNotNull(cause, "准入失败必须让 Future 失败，不得抛给调用方也不得静默");
        assertTrue(cause instanceof MycatException,
                "cause 必须是 MycatException，实得 " + cause.getClass().getName());
        assertEquals(MycatErrorCode.ERR_IO_CONCURRENCY_LIMIT, ((MycatException) cause).getErrorCode(),
                "错误码必须是 MYCAT-3007");
    }

    private static Throwable awaitFailure(Future<?> f, long seconds) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        f.onComplete(ar -> {
            if (ar.failed()) {
                err.set(ar.cause());
            }
            done.countDown();
        });
        assertTrue(done.await(seconds, TimeUnit.SECONDS), "Future 未在 " + seconds + "s 内完成");
        return err.get();
    }

    private static boolean awaitSuccess(Future<?> f, long seconds) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        f.onComplete(ar -> {
            ok.set(ar.succeeded());
            done.countDown();
        });
        assertTrue(done.await(seconds, TimeUnit.SECONDS), "Future 未在 " + seconds + "s 内完成");
        return ok.get();
    }

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