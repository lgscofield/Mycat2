package io.mycat.newquery;

import io.mycat.beans.mycat.MycatRelDataType;
import io.mycat.beans.mycat.MycatRowMetaData;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 2：NewMycatConnectionImpl 六处阻塞 synchronized → 每实例单把 ReentrantLock 的不回退锁。
 *
 * <p>观测手段是「锁同一性探针」：持住那把唯一的锁，被测站点必须阻塞；放锁后必须离开临界区。
 * 它一次钉住两件事——互斥没丢，且六处站点没有一处被漏换或换成另一把锁。
 * 单靠「恰好一个 ReentrantLock 字段」的结构断言量不到漏换，故两者都要。
 *
 * <p>不依赖后端库：connection 传 null，方法体在临界区内抛 NPE 属预期，探针只关心进出临界区。
 */
public class NewMycatConnectionImplLockTest {

    private static final long BLOCK_PROBE_MILLIS = 400;
    private static final long RELEASE_TIMEOUT_SECONDS = 5;

    private static NewMycatConnectionImpl newConnection() {
        return new NewMycatConnectionImpl("lock-test", false, null);
    }

    @Test
    public void lockIsSinglePerInstance() {
        assertEquals(1, reentrantLockFields(NewMycatConnectionImpl.class).size(),
                "必须恰好一个 ReentrantLock 实例字段（单锁拓扑，禁止拆成多把）");
    }

    @Test
    public void noSynchronizedMethodRemains() {
        for (Method m : NewMycatConnectionImpl.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(m.getModifiers()), "方法级 synchronized 未换锁: " + m);
        }
    }

    @Test
    public void prepareQueryCollectorSiteAcquiresTheSingleLock() throws Exception {
        NewMycatConnectionImpl c = newConnection();
        assertAcquiresSingleLock(c, () -> c.prepareQuery("SELECT 1", Collections.emptyList(), noopCollector()));
    }

    @Test
    public void prepareQueryArrowSiteAcquiresTheSingleLock() throws Exception {
        NewMycatConnectionImpl c = newConnection();
        assertAcquiresSingleLock(c, () -> c.prepareQuery("SELECT 1", Collections.emptyList(),
                (MycatRelDataType) null, (BufferAllocator) null).subscribe(v -> { }, e -> { }));
    }

    @Test
    public void prepareQueryPacketSiteAcquiresTheSingleLock() throws Exception {
        NewMycatConnectionImpl c = newConnection();
        assertAcquiresSingleLock(c, () -> c.prepareQuery("SELECT 1", Collections.emptyList(), 0)
                .subscribe(v -> { }, e -> { }));
    }

    @Test
    public void callSiteAcquiresTheSingleLock() throws Exception {
        NewMycatConnectionImpl c = newConnection();
        assertAcquiresSingleLock(c, () -> c.call("SELECT 1"));
    }

    @Test
    public void insertSiteAcquiresTheSingleLock() throws Exception {
        NewMycatConnectionImpl c = newConnection();
        assertAcquiresSingleLock(c, () -> c.insert("INSERT INTO t VALUES (1)", Collections.emptyList()));
    }

    @Test
    public void updateSiteAcquiresTheSingleLock() throws Exception {
        NewMycatConnectionImpl c = newConnection();
        assertAcquiresSingleLock(c, () -> c.update("UPDATE t SET a = 1", Collections.emptyList()));
    }

    /**
     * 同一实例上两条并发语句必须排队在**同一把**锁上：持锁期间两个线程都进不去，
     * 放锁后都离开临界区。queueLength==2 是「互斥且同锁」的确定性证据，不用计时猜。
     */
    @Test
    public void concurrentQueriesOnSameConnectionAreSerialized() throws Exception {
        NewMycatConnectionImpl c = newConnection();
        ReentrantLock lock = singleLock(c);
        ExecutorService pool = Executors.newFixedThreadPool(2, daemonFactory("serialized"));
        Future<?> a;
        Future<?> b;
        lock.lock();
        try {
            a = pool.submit(() -> c.insert("INSERT INTO t VALUES (1)", Collections.emptyList()));
            b = pool.submit(() -> c.prepareQuery("SELECT 1", Collections.emptyList(), noopCollector()));
            awaitQueueLength(lock, 2);
        } finally {
            lock.unlock();
        }
        try {
            a.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            b.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // 无后端连接，方法体在临界区内抛异常属预期
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 持锁路径内再入另一处持锁方法不得死锁。采集器回调由生产代码在临界区内触发，
     * 回调里再调 call(...) —— 拆成多把锁或换成不可重入锁都会在这里挂住。
     * 5s 硬超时：超时即 FAIL，且 assertTimeoutPreemptively 保证测试自身不会挂死。
     */
    @Test
    public void reentrantNestingDoesNotDeadlock() {
        NewMycatConnectionImpl c = newConnection();
        ReentrantLock lock = singleLock(c);
        AtomicBoolean insideGuard = new AtomicBoolean(false);
        AtomicBoolean nestedDone = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        MysqlCollector reentering = new MysqlCollector() {
            @Override
            public void onColumnDef(MycatRowMetaData mycatRowMetaData) {
            }

            @Override
            public void onRow(Object[] row) {
            }

            @Override
            public void onComplete() {
                reenter();
            }

            @Override
            public void onError(Throwable e) {
                reenter();
            }

            private void reenter() {
                insideGuard.set(lock.isHeldByCurrentThread());
                c.call("SELECT 1");
                nestedDone.set(true);
                done.countDown();
            }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            c.prepareQuery("SELECT 1", Collections.emptyList(), reentering);
            assertTrue(done.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "持锁路径内再入另一持锁方法死锁了（回调 5s 内未完成）");
            assertTrue(insideGuard.get(),
                    "采集器回调必须仍在生产临界区内同线程持锁，否则这条测试量不到可重入嵌套");
            assertTrue(nestedDone.get());
        });
    }

    // ---- 探针与反射工具 ----

    private static void assertAcquiresSingleLock(Object instance, Runnable site) throws Exception {
        ReentrantLock lock = singleLock(instance);
        ExecutorService ex = Executors.newSingleThreadExecutor(daemonFactory("lock-probe"));
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
            f.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // 无后端连接时方法体在临界区内抛异常属预期；探针只要求它进入并离开临界区
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

    private static MysqlCollector noopCollector() {
        return new MysqlCollector() {
            @Override
            public void onColumnDef(MycatRowMetaData mycatRowMetaData) {
            }

            @Override
            public void onRow(Object[] row) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable e) {
            }
        };
    }
}