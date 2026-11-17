package io.mycat.plug.sequence;

import io.mycat.IOExecutor;
import io.mycat.MetaClusterCurrent;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 2：DefaultPrototypeGenerator 三处阻塞 synchronized → 每实例单把 ReentrantLock，
 * 外加 D5：value 字段补 volatile（:90 的锁外读靠它拿到可见性）。
 *
 * <p>观测手段同 NewMycatConnectionImplLockTest：锁同一性探针。持住唯一的锁，站点必须阻塞；
 * 放锁后必须离开临界区。结构断言量不到「漏换某一处」，探针能。
 *
 * <p>不依赖后端库：ConnectionManager 不注册，getNumber() 在临界区内抛 NPE 属预期。
 * fetch() 的临界区在 getNumber() 成功之后才到达，故用一个只覆写 getNumber() 的测试子类
 * 把后端依赖摘掉——被测的仍是生产 fetch() 的加锁代码本身。
 */
public class DefaultPrototypeGeneratorLockTest {

    private static final long BLOCK_PROBE_MILLIS = 400;
    private static final long RELEASE_TIMEOUT_SECONDS = 5;

    private Map<Class, Object> savedContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
        MetaClusterCurrent.register(IOExecutor.class, inlineIOExecutor());
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    @Test
    public void lockIsSinglePerInstance() {
        assertEquals(1, reentrantLockFields(DefaultPrototypeGenerator.class).size(),
                "必须恰好一个 ReentrantLock 实例字段（单锁拓扑，禁止拆成多把）");
    }

    @Test
    public void noSynchronizedMethodRemains() {
        for (Method m : DefaultPrototypeGenerator.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(m.getModifiers()), "方法级 synchronized 未换锁: " + m);
        }
    }

    /** D5：value 必须 volatile —— :90 的锁外读靠它保证可见性。 */
    @Test
    public void valueFieldIsVolatile() throws Exception {
        Field value = DefaultPrototypeGenerator.class.getDeclaredField("value");
        assertTrue(Modifier.isVolatile(value.getModifiers()), "value 字段必须带 volatile 修饰符");
    }

    @Test
    public void getNumberSiteAcquiresTheSingleLock() throws Exception {
        DefaultPrototypeGenerator g = newGenerator();
        assertAcquiresSingleLock(g, g::getNumber);
    }

    /** get() 的 :69 临界区：预置 value 让放锁后的递归能收敛，不至于空转。 */
    @Test
    public void getSwapSiteAcquiresTheSingleLock() throws Exception {
        DefaultPrototypeGenerator g = newGenerator();
        setField(g, "value", new DefaultPrototypeGenerator.Value(0L, 1000L));
        assertAcquiresSingleLock(g, g::get);
    }

    /** fetch() 的 :92 临界区：只有 getNumber() 成功返回后才到达。 */
    @Test
    public void fetchPublishSiteAcquiresTheSingleLock() throws Exception {
        DefaultPrototypeGenerator g = new StubGenerator();
        cancelTimer(g);
        assertAcquiresSingleLock(g, g::fetch);
    }

    /** 两线程并发 getNumber() 必须排队在同一把锁上。 */
    @Test
    public void getNumberIsMutuallyExclusive() throws Exception {
        DefaultPrototypeGenerator g = newGenerator();
        ReentrantLock lock = singleLock(g);
        ExecutorService pool = Executors.newFixedThreadPool(2, daemonFactory("getNumber"));
        java.util.concurrent.Future<?> a;
        java.util.concurrent.Future<?> b;
        lock.lock();
        try {
            a = pool.submit(g::getNumber);
            b = pool.submit(g::getNumber);
            awaitQueueLength(lock, 2);
        } finally {
            lock.unlock();
        }
        try {
            a.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            b.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // 未注册 ConnectionManager，方法体在临界区内抛异常属预期
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 测试替身与探针工具 ----

    /** 只摘掉后端依赖，fetch()/get() 的加锁代码仍是生产的。 */
    static final class StubGenerator extends DefaultPrototypeGenerator {
        StubGenerator() {
            super(new HashMap<>());
        }

        @Override
        public Value getNumber() {
            return new Value(1L, 100L);
        }
    }

    private static DefaultPrototypeGenerator newGenerator() {
        DefaultPrototypeGenerator g = new DefaultPrototypeGenerator(new HashMap<>());
        cancelTimer(g);
        return g;
    }

    /** 构造器会挂一个 24ms 周期的 fetch，探针期间必须停掉，否则 value 会被它抢先填上。 */
    private static void cancelTimer(DefaultPrototypeGenerator g) {
        try {
            Field f = DefaultPrototypeGenerator.class.getDeclaredField("scheduledFuture");
            f.setAccessible(true);
            ((ScheduledFuture<?>) f.get(g)).cancel(false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object instance, String name, Object v) {
        try {
            Field f = DefaultPrototypeGenerator.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(instance, v);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static IOExecutor inlineIOExecutor() {
        return new IOExecutor() {
            @Override
            public <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler) {
                Promise<T> promise = Promise.promise();
                blockingCodeHandler.handle(promise);
                return promise.future();
            }

            @Override
            public long count() {
                return 0;
            }
        };
    }

    private static void assertAcquiresSingleLock(Object instance, Runnable site) throws Exception {
        ReentrantLock lock = singleLock(instance);
        ExecutorService ex = Executors.newSingleThreadExecutor(daemonFactory("lock-probe"));
        java.util.concurrent.Future<?> f;
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
            f.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // 无后端时方法体在临界区内抛异常属预期；探针只要求它进入并离开临界区
        } finally {
            ex.shutdownNow();
        }
    }

    private static ReentrantLock singleLock(Object instance) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(reentrantLockFields(c));
        }
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RELEASE_TIMEOUT_SECONDS);
        while (lock.getQueueLength() < n) {
            if (System.nanoTime() > deadline) {
                fail("等待 " + n + " 个线程排队于同一把锁超时，实得 " + lock.getQueueLength());
            }
            Thread.sleep(10);
        }
    }

    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}