package io.mycat;

import io.mycat.calcite.CodeExecuterContext;
import io.mycat.calcite.MycatRelDatasourceSourceInfo;
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.rewriter.Distribution;
import io.mycat.calcite.table.GlobalTable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-PR-1 Task 6 Step 3 活体升级：AsyncMycatDataContextImpl 原有 5 个 synchronized 方法
 * （probeMergedBackendMeta / getConnection / recycleConnection / getObservables / endFuture），
 * 共用实例监视器；getConnection 在临界区内做阻塞 JDBC，活体实测 park 在 logback appender 的
 * ReentrantLock ⇒ 第三轮 136 帧里占 10 帧（帧标 getConnection:226）。
 *
 * <p>覆盖口径如实说明：本类是**结构锁**。该类为 abstract 且构造需要 MycatDataContext/CodeExecuterContext
 * 与预解析语句，离线做「持锁必阻塞」行为探针代价与风险都高。
 * 钉住是否真消除由活体 L1 决定性证明；CHM mapping function 那条不变式另由
 * {@code io.mycat.vertx.VertxExecuterComputeIfAbsentPinTest} 的行为探针 + 源扫描覆盖。
 */
public class AsyncMycatDataContextImplLockTest {

    @Test
    public void partitionCacheMissDoesNotUseComputeIfAbsentAndConcurrentMissComputesOnce() throws Exception {
        AtomicBoolean computeIfAbsentCalled = new AtomicBoolean();
        AtomicInteger calculations = new AtomicInteger();
        CountDownLatch insideCalculation = new CountDownLatch(1);
        CountDownLatch releaseCalculation = new CountDownLatch(1);

        Partition partition = Mockito.mock(Partition.class);
        Mockito.when(partition.getTargetName()).thenReturn("c0");
        GlobalTable globalTable = Mockito.mock(GlobalTable.class);
        Mockito.when(globalTable.getUniqueName()).thenReturn("db.t");
        Mockito.when(globalTable.getDataNode()).thenReturn(partition);
        Mockito.when(globalTable.getGlobalDataNode()).thenAnswer(invocation -> {
            insideCalculation.countDown();
            assertTrue(releaseCalculation.await(5, TimeUnit.SECONDS), "分区计算探针未真的等待放闸");
            return Collections.singletonList(partition);
        });

        Distribution distribution = Distribution.of(globalTable);
        MycatView view = Mockito.mock(MycatView.class);
        Mockito.doAnswer(invocation -> {
            calculations.incrementAndGet();
            return distribution;
        }).when(view).getDistribution();
        MycatRelDatasourceSourceInfo sourceInfo = new MycatRelDatasourceSourceInfo(null, null, view);
        CodeExecuterContext codeExecuterContext = Mockito.mock(CodeExecuterContext.class);
        Mockito.when(codeExecuterContext.getRelContext()).thenReturn(Collections.singletonMap("node", sourceInfo));
        Mockito.when(codeExecuterContext.getConstantMap()).thenReturn(Collections.emptyMap());
        DrdsSqlWithParams sql = Mockito.mock(DrdsSqlWithParams.class);
        Mockito.when(sql.getHintDataNodeFilter()).thenReturn(Optional.empty());

        AsyncMycatDataContextImpl.SqlMycatDataContextImpl context =
                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(null, codeExecuterContext, sql);
        Field cacheField = AsyncMycatDataContextImpl.SqlMycatDataContextImpl.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        cacheField.set(context, new ProbeConcurrentMap(computeIfAbsentCalled));

        ExecutorService vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        try {
            Future<Optional<List<PartitionGroup>>> first = vt.submit(() -> context.getPartition("node"));
            assertTrue(insideCalculation.await(5, TimeUnit.SECONDS), "未进入分区计算探针");
            Future<Optional<List<PartitionGroup>>> second = vt.submit(() -> context.getPartition("node"));
            releaseCalculation.countDown();

            Optional<List<PartitionGroup>> firstResult = first.get(5, TimeUnit.SECONDS);
            Optional<List<PartitionGroup>> secondResult = second.get(5, TimeUnit.SECONDS);
            assertSame(firstResult.get(), secondResult.get(), "同一 node 的并发 cache miss 必须发布同一结果");
            assertFalse(computeIfAbsentCalled.get(),
                    "分区 cache miss 仍在 ConcurrentHashMap.computeIfAbsent 内执行 ⇒ mapping function 持 bin 监视器跨阻塞 HTTP ⇒ VT 钉住载体");
            assertEquals(1, calculations.get(), "同一 node 的并发 cache miss 只能计算一次");
        } finally {
            releaseCalculation.countDown();
            vt.shutdownNow();
        }
    }

    private static final class ProbeConcurrentMap extends ConcurrentHashMap<String, List<PartitionGroup>> {
        private final AtomicBoolean computeIfAbsentCalled;

        private ProbeConcurrentMap(AtomicBoolean computeIfAbsentCalled) {
            this.computeIfAbsentCalled = computeIfAbsentCalled;
        }

        @Override
        public List<PartitionGroup> computeIfAbsent(String key,
                Function<? super String, ? extends List<PartitionGroup>> mappingFunction) {
            computeIfAbsentCalled.set(true);
            return super.computeIfAbsent(key, mappingFunction);
        }
    }

    @Test
    public void noSynchronizedMethodRemains() {
        for (Method m : AsyncMycatDataContextImpl.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(m.getModifiers()), "方法级 synchronized 未换锁: " + m);
        }
    }

    @Test
    public void lockIsSinglePerInstance() {
        assertEquals(1, reentrantLockFields(AsyncMycatDataContextImpl.class).size(),
                "必须恰好一个 ReentrantLock 实例字段：五个方法仍须互斥于同一把锁，拆成多把会放大并发");
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