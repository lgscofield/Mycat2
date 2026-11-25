package io.mycat;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 6 Step 3 活体升级：LocalLockServiceImpl.lock 原为 synchronized 单例方法，
 * 第三轮活体 136 帧钉住里占 7 帧——持实例监视器跨整个 DDL supplier，supplier 内 park 在
 * MySQL socket read（NativeProtocol.readMessage:586）⇒ 钉住载体线程。
 *
 * <p>核心断言是 {@link Thread#holdsLock}：park 那一刻是否仍持实例监视器，这是钉住条件的直接可测代理。
 * 结构断言（无 synchronized 方法、单把 ReentrantLock）量不到「换锁后语义变了」，故另加全局串行保持锁。
 */
public class LocalLockServiceImplPinTest {

    private static final long AWAIT_SECONDS = 5;
    private static final long BLOCK_PROBE_MILLIS = 400;

    @Test
    public void lockDoesNotHoldInstanceMonitorWhileSupplierParks() throws Exception {
        LocalLockServiceImpl service = new LocalLockServiceImpl();
        AtomicBoolean monitorHeld = new AtomicBoolean(true);
        AtomicBoolean onVirtualThread = new AtomicBoolean(false);
        AtomicBoolean parked = new AtomicBoolean(false);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        java.util.concurrent.Future<Future<Void>> f = vt.submit(() -> service.lock("ddl", () -> {
            monitorHeld.set(Thread.holdsLock(service));
            onVirtualThread.set(Thread.currentThread().isVirtual());
            inside.countDown();
            try {
                parked.set(release.await(AWAIT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Future.succeededFuture();
        }));
        try {
            assertTrue(inside.await(AWAIT_SECONDS, TimeUnit.SECONDS), "supplier 未启动，探针前提不成立");
            assertTrue(onVirtualThread.get(), "supplier 必须跑在虚拟线程上，否则量不到钉住条件");
            assertFalse(monitorHeld.get(),
                    "supplier park 时仍持有实例监视器 ⇒ VT 钉住载体线程；必须换 ReentrantLock");
        } finally {
            release.countDown();
            vt.shutdown();
        }
        f.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(parked.get(), "supplier 未真的 park 到放闸，探针前提不成立");
    }

    @Test
    public void noSynchronizedMethodRemains() {
        for (Method m : LocalLockServiceImpl.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(m.getModifiers()), "方法级 synchronized 未换锁: " + m);
        }
    }

    @Test
    public void lockIsSinglePerInstance() {
        assertEquals(1, reentrantLockFields(LocalLockServiceImpl.class).size(),
                "必须恰好一个 ReentrantLock 实例字段（单锁拓扑）");
    }

    /**
     * 语义保持：原 synchronized 单例方法使**所有 name 全局串行**。换锁不得顺手改成 per-name，
     * 那会放大 DDL 并发、属行为变更。本锁把「不同 name 也互斥」钉死。
     */
    @Test
    public void differentNamesStillSerializeGlobally() throws Exception {
        LocalLockServiceImpl service = new LocalLockServiceImpl();
        ReentrantLock lock = singleLock(service);
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "lock-probe");
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.Future<?> a = pool.submit(() -> service.lock("name-a", () -> {
            firstInside.countDown();
            try {
                release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Future.succeededFuture();
        }));
        try {
            assertTrue(firstInside.await(AWAIT_SECONDS, TimeUnit.SECONDS), "第一个 supplier 未进入");
            java.util.concurrent.Future<?> b = pool.submit(() -> service.lock("name-b", () -> {
                secondEntered.set(true);
                return Future.succeededFuture();
            }));
            try {
                b.get(BLOCK_PROBE_MILLIS, TimeUnit.MILLISECONDS);
                fail("不同 name 竟能并发进入 ⇒ 全局串行语义被改成 per-name，超出本票范围");
            } catch (TimeoutException expected) {
                // 正是要的：全局互斥
            }
            assertFalse(secondEntered.get());
            assertEquals(1, lock.getQueueLength(), "第二个调用必须排队在同一把锁上");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        a.get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** 行为保持：supplier 抛异常仍须转成 failedFuture，不得把异常泄给调用线程。 */
    @Test
    public void supplierThrowableBecomesFailedFuture() {
        LocalLockServiceImpl service = new LocalLockServiceImpl();
        RuntimeException boom = new RuntimeException("boom");
        Future<Void> result = service.lock("ddl", () -> {
            throw boom;
        });
        assertTrue(result.failed(), "supplier 抛异常必须返回 failedFuture");
        assertEquals(boom, result.cause());
    }

    /** 行为保持：正常路径原样返回 supplier 的 future。 */
    @Test
    public void returnsSupplierFutureUnchanged() {
        LocalLockServiceImpl service = new LocalLockServiceImpl();
        Future<Void> expected = Future.succeededFuture();
        assertEquals(expected, service.lock("ddl", () -> expected));
    }

    private static ReentrantLock singleLock(Object instance) {
        List<Field> fields = reentrantLockFields(instance.getClass());
        assertEquals(1, fields.size(), "必须恰好一个 ReentrantLock 实例字段");
        Field f = fields.get(0);
        f.setAccessible(true);
        try {
            return (ReentrantLock) f.get(instance);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static List<Field> reentrantLockFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && ReentrantLock.class.isAssignableFrom(f.getType())) {
                out.add(f);
            }
        }
        return out;
    }
}