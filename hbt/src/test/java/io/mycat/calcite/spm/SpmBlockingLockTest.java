package io.mycat.calcite.spm;

import io.mycat.DrdsSql;
import io.mycat.MetaClusterCurrent;
import io.mycat.config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpmBlockingLockTest {

    private Map<Class, Object> savedContext;

    @BeforeEach
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
        Map<Class, Object> context = new IdentityHashMap<>();
        context.put(ServerConfig.class, new ServerConfig());
        MetaClusterCurrent.register(context);
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    @Test
    public void queryPlannerCacheMissDoesNotHoldInstanceMonitorWhileBlocking() throws Exception {
        AtomicReference<QueryPlanner> plannerRef = new AtomicReference<>();
        AtomicBoolean monitorHeld = new AtomicBoolean(true);
        AtomicBoolean onVirtualThread = new AtomicBoolean();
        AtomicBoolean parked = new AtomicBoolean();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        QueryPlanCache cache = (QueryPlanCache) Proxy.newProxyInstance(
                QueryPlanCache.class.getClassLoader(), new Class<?>[]{QueryPlanCache.class},
                (proxy, method, args) -> {
                    if ("getAcceptedMycatRelList".equals(method.getName())) {
                        return Collections.emptyList();
                    }
                    if ("add".equals(method.getName())) {
                        monitorHeld.set(Thread.holdsLock(plannerRef.get()));
                        onVirtualThread.set(Thread.currentThread().isVirtual());
                        inside.countDown();
                        try {
                            parked.set(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new ProbeStop();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        QueryPlanner planner = new QueryPlanner(cache);
        plannerRef.set(planner);
        ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        Future<?> task = vt.submit(() -> {
            try {
                planner.getAcceptedMycatRelList(null);
            } catch (ProbeStop expected) {
                return;
            }
            throw new AssertionError("探针未在 planCache.add 处停下");
        });
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS), "QueryPlanner 未进入探针");
            assertTrue(onVirtualThread.get(), "探针必须运行在虚拟线程上");
            assertFalse(monitorHeld.get(), "缓存未命中阻塞时仍持有实例监视器 ⇒ VT 钉住载体线程");
        } finally {
            release.countDown();
            vt.shutdown();
        }
        task.get(5, TimeUnit.SECONDS);
        assertTrue(parked.get(), "探针未真的 park 到放闸");
    }

    @Test
    public void memPlanCacheAddDoesNotHoldInstanceMonitorWhileBlocking() throws Exception {
        AtomicBoolean monitorHeld = new AtomicBoolean(true);
        AtomicBoolean onVirtualThread = new AtomicBoolean();
        AtomicBoolean parked = new AtomicBoolean();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemPlanCache cache = new MemPlanCache(null) {
            @Override
            public Baseline getBaseline(DrdsSql sql) {
                monitorHeld.set(Thread.holdsLock(this));
                onVirtualThread.set(Thread.currentThread().isVirtual());
                inside.countDown();
                try {
                    parked.set(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new ProbeStop();
            }
        };
        ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        Future<?> task = vt.submit(() -> {
            try {
                cache.add(false, null);
            } catch (ProbeStop expected) {
                return;
            }
            throw new AssertionError("探针未在 getBaseline 处停下");
        });
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS), "MemPlanCache 未进入探针");
            assertTrue(onVirtualThread.get(), "探针必须运行在虚拟线程上");
            assertFalse(monitorHeld.get(), "编译计划阻塞时仍持有实例监视器 ⇒ VT 钉住载体线程");
        } finally {
            release.countDown();
            vt.shutdown();
        }
        task.get(5, TimeUnit.SECONDS);
        assertTrue(parked.get(), "探针未真的 park 到放闸");
    }

    @Test
    public void hitClassesUseOneReentrantLockPerInstance() {
        assertEquals(1, reentrantLockFields(QueryPlanner.class).size());
        assertEquals(1, reentrantLockFields(MemPlanCache.class).size());
    }

    @Test
    public void hitClassesHaveNoSynchronizedMethods() {
        for (Class<?> type : new Class<?>[]{QueryPlanner.class, MemPlanCache.class}) {
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(Modifier.isSynchronized(method.getModifiers()), "方法级 synchronized 未换锁: " + method);
            }
        }
    }

    private static List<Field> reentrantLockFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && ReentrantLock.class.isAssignableFrom(field.getType())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static final class ProbeStop extends RuntimeException {
    }
}
