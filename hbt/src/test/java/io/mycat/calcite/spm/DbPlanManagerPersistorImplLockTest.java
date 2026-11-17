package io.mycat.calcite.spm;

import io.mycat.MetaClusterCurrent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 2：DbPlanManagerPersistorImpl 十三处方法级 synchronized → 每实例单把 ReentrantLock。
 *
 * <p>观测手段同另两个 P0 类：锁同一性探针。持住唯一的锁，站点必须阻塞；放锁后必须离开临界区。
 * 十三处逐一过探针——「恰好一个 ReentrantLock 字段」的结构断言量不到漏换，探针能。
 *
 * <p>不依赖后端库：清空 MetaClusterCurrent 后 getManagerOptional() 返回 empty，
 * 各方法在临界区内提前返回；探针只关心进出临界区，不关心返回值。
 */
public class DbPlanManagerPersistorImplLockTest {

    private static final long BLOCK_PROBE_MILLIS = 400;
    private static final long RELEASE_TIMEOUT_SECONDS = 5;

    private Map<Class, Object> savedContext;

    @BeforeEach
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
        MetaClusterCurrent.register(new IdentityHashMap<>());
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    @Test
    public void lockIsSinglePerInstance() {
        assertEquals(1, reentrantLockFields(DbPlanManagerPersistorImpl.class).size(),
                "必须恰好一个 ReentrantLock 实例字段（单锁拓扑，禁止拆成多把）");
    }

    @Test
    public void noSynchronizedMethodRemains() {
        for (Method m : DbPlanManagerPersistorImpl.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(m.getModifiers()), "方法级 synchronized 未换锁: " + m);
        }
    }

    @Test
    public void checkStoreAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, p::checkStore);
    }

    @Test
    public void loadBaselineAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.loadBaseline(1L));
    }

    @Test
    public void deleteBaselineAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.deleteBaseline(1L));
    }

    @Test
    public void listPlanAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.listPlan(1L));
    }

    @Test
    public void saveBaselineAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.saveBaseline((Baseline) null));
    }

    @Test
    public void loadAllBaselineAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, p::loadAllBaseline);
    }

    @Test
    public void clearAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, p::clear);
    }

    @Test
    public void loadPlanAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.loadPlan(1L));
    }

    @Test
    public void loadFixPlanAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.loadFixPlan(1L, 1L));
    }

    @Test
    public void loadPlanByBaselineIdAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.loadPlanByBaselineId(1L));
    }

    @Test
    public void savePlanAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.savePlan(null, false));
    }

    @Test
    public void deletePlanAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.deletePlan(1L));
    }

    @Test
    public void loadBaselineByBaseLineSqlAcquiresTheSingleLock() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        assertAcquiresSingleLock(p, () -> p.loadBaselineByBaseLineSql(null, null));
    }

    /** 两线程并发调**不同**持锁方法，必须排队在同一把锁上——跨方法互斥没丢。 */
    @Test
    public void persistorMethodsAreMutuallyExclusive() throws Exception {
        DbPlanManagerPersistorImpl p = new DbPlanManagerPersistorImpl();
        ReentrantLock lock = singleLock(p);
        ExecutorService pool = Executors.newFixedThreadPool(2, daemonFactory("persistor"));
        Future<?> a;
        Future<?> b;
        lock.lock();
        try {
            a = pool.submit(() -> p.loadPlan(1L));
            b = pool.submit(() -> p.listPlan(1L));
            awaitQueueLength(lock, 2);
        } finally {
            lock.unlock();
        }
        try {
            a.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            b.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
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
            // 无后端时方法体在临界区内抛异常属预期；探针只要求它进入并离开临界区
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
}