package io.mycat.plug.sequence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 6：活体序列生成器 {@code SequenceMySQLGenerator.get()} 的阻塞 synchronized 换锁。
 *
 * <p>静态 pinning 清单（2026-11-17-i-pr-3-pinning-inventory.md）把 P0 定为 3 文件 22 处，
 * 其中序列一项指向 {@code DefaultPrototypeGenerator}——但那个类只实现 {@code Supplier<Number>}、
 * 只有 Map 构造器，而 {@code SequenceGenerator.getSequence()} 走的是「无参构造 + 强转 SequenceHandler」，
 * 因此它在生产里根本不可达。真正被 {@code setSequence} 挂上的是本类：
 * {@code get()} 在实例监视器内做整段阻塞 JDBC（借连接 → executeQuery → 行锁 FOR UPDATE）。
 *
 * <p>核心断言不是反射看修饰符，而是 {@link Thread#holdsLock(Object)}：
 * JDK21 的 VT 钉住载体，触发条件正是「持监视器时 park」。取号腿在监视器内 park
 * ⇒ 载体被钉死 ⇒ 饱和时序列腿把载体吃光。故本测试让取号回调真的 park 在一个 latch 上，
 * 并在 park 前观测实例监视器是否被持有。换锁前 true（RED），换锁后 false（GREEN）。
 *
 * <p>不依赖后端库：三参 {@code init} 可注入取号函数，被测的仍是生产 {@code get()} 的加锁代码本身。
 */
public class SequenceMySQLGeneratorLockTest {

    private static final long BLOCK_PROBE_MILLIS = 400;
    private static final long AWAIT_SECONDS = 5;

    @Test
    public void lockIsSinglePerInstance() {
        assertEquals(1, reentrantLockFields(SequenceMySQLGenerator.class).size(),
                "必须恰好一个 ReentrantLock 实例字段（单锁拓扑，禁止拆成多把）");
    }

    @Test
    public void noSynchronizedMethodRemains() {
        for (Method m : SequenceMySQLGenerator.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(m.getModifiers()), "方法级 synchronized 未换锁: " + m);
        }
    }

    /**
     * 取号腿 park 时不得持有实例监视器——这就是 VT 钉住载体的触发条件本身。
     * 换锁前 {@code synchronized get()} 会让 holdsLock 为 true，本断言 RED。
     */
    @Test
    public void fetchLegDoesNotHoldInstanceMonitorWhileItParks() throws Exception {
        SequenceMySQLGenerator g = new SequenceMySQLGenerator();
        AtomicBoolean monitorHeld = new AtomicBoolean(true);
        AtomicBoolean onVirtualThread = new AtomicBoolean(false);
        AtomicBoolean parked = new AtomicBoolean(false);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        g.init("select 1", "prototype", (target, sql) -> {
            monitorHeld.set(Thread.holdsLock(g));
            onVirtualThread.set(Thread.currentThread().isVirtual());
            inside.countDown();
            try {
                parked.set(release.await(AWAIT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "1,100";
        });

        ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        Future<Number> f = vt.submit(g::get);
        try {
            assertTrue(inside.await(AWAIT_SECONDS, TimeUnit.SECONDS), "取号腿未在临界区内启动，前提不成立");
            assertTrue(onVirtualThread.get(), "取号腿必须跑在虚拟线程上，否则量不到钉住条件");
            assertFalse(monitorHeld.get(),
                    "取号腿 park 时仍持有实例监视器 ⇒ VT 钉住载体线程；必须换 ReentrantLock");
        } finally {
            release.countDown();
            vt.shutdown();
        }
        assertEquals(1L, f.get(AWAIT_SECONDS, TimeUnit.SECONDS).longValue());
        assertTrue(parked.get(), "取号回调未真的 park 到放闸，探针前提不成立");
    }

    @Test
    public void getSiteAcquiresTheSingleLock() throws Exception {
        SequenceMySQLGenerator g = newGenerator();
        assertAcquiresSingleLock(g, g::get);
    }

    /** 两个虚拟线程并发取号必须排队在同一把锁上。 */
    @Test
    public void getIsMutuallyExclusiveOnVirtualThreads() throws Exception {
        SequenceMySQLGenerator g = newGenerator();
        ReentrantLock lock = singleLock(g);
        ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        Future<Number> a;
        Future<Number> b;
        lock.lock();
        try {
            a = vt.submit(g::get);
            b = vt.submit(g::get);
            awaitQueueLength(lock, 2);
        } finally {
            lock.unlock();
        }
        try {
            assertEquals(1L, a.get(AWAIT_SECONDS, TimeUnit.SECONDS).longValue());
            assertEquals(2L, b.get(AWAIT_SECONDS, TimeUnit.SECONDS).longValue());
        } finally {
            vt.shutdown();
        }
    }

    /** 换锁不得改变取号语义：批内递增、跨批续号、无重号。此断言换锁前后都应通过。 */
    @Test
    public void concurrentVirtualThreadsStillGetUniqueSequentialIds() throws Exception {
        SequenceMySQLGenerator g = new SequenceMySQLGenerator();
        AtomicReference<Long> next = new AtomicReference<>(1L);
        g.init("select 1", "prototype", (target, sql) -> {
            long start = next.get();
            next.set(start + 100L);
            return start + ",100";
        });
        int threads = 8;
        int perThread = 50;
        ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(vt.submit(() -> {
                List<Long> ids = new ArrayList<>();
                for (int j = 0; j < perThread; j++) {
                    ids.add(g.get().longValue());
                }
                return ids;
            }));
        }
        Set<Long> seen = new HashSet<>();
        try {
            for (Future<List<Long>> f : futures) {
                List<Long> ids = f.get(AWAIT_SECONDS * 4L, TimeUnit.SECONDS);
                assertEquals(perThread, ids.size());
                for (Long id : ids) {
                    assertTrue(seen.add(id), "取号重号: " + id);
                }
            }
        } finally {
            vt.shutdown();
        }
        assertEquals(threads * perThread, seen.size());
    }

    // ---- 探针工具（口径同 DefaultPrototypeGeneratorLockTest）----

    private static SequenceMySQLGenerator newGenerator() {
        SequenceMySQLGenerator g = new SequenceMySQLGenerator();
        g.init("select 1", "prototype", (target, sql) -> "1,100");
        return g;
    }

    private static void assertAcquiresSingleLock(Object instance, Runnable site) throws Exception {
        ReentrantLock lock = singleLock(instance);
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "seq-lock-probe");
            t.setDaemon(true);
            return t;
        });
        Future<?> f;
        lock.lock();
        try {
            f = ex.submit(site);
            try {
                f.get(BLOCK_PROBE_MILLIS, TimeUnit.MILLISECONDS);
                fail("站点未阻塞于唯一锁：该处 synchronized 漏换，或换成了另一把锁");
            } catch (TimeoutException expected) {
                // 正是要的：持锁期间进不去
            }
        } finally {
            lock.unlock();
        }
        try {
            f.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
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

    private static void awaitQueueLength(ReentrantLock lock, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (lock.getQueueLength() < n) {
            if (System.nanoTime() > deadline) {
                fail("等待 " + n + " 个线程排队于同一把锁超时，实得 " + lock.getQueueLength());
            }
            Thread.sleep(10);
        }
    }
}