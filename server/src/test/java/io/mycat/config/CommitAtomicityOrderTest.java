/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.config;

import io.mycat.MetaClusterCurrent;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * I-DEBT-130：{@code MycatRouterConfigOps.commit()} 两阶段资源交换非原子的顺序断言锁。
 *
 * <p>背景：{@code getJdbcConnectionManager}（:843-847）与 {@code getMycatMySQLManager}（:670-674）
 * 在"新资源构建成功"的 finally 里<b>立即 close 旧池</b>，而此时新资源尚未走完 commit 的后续阶段
 * （replica 注册 :534-540、resourceList.clear :545、metadata/authenticator/planner 等构建）。
 * 任一后续构造失败 → giveup 新资源 + 旧池已永久关 → 整机 {@code getConnection} 失败直至重启
 * （运维事故级）。commit 全流程离线不可复现（需 prototype 库/全量上下文），故用
 * <b>顺序断言锁</b>：可观测 fake（Mockito 池）记录 close 相对交换/注册的次序——
 * <ol>
 *   <li>交换 getter 返回时旧池必须存活（close 次数=0）——RED：现码 finally 即关；</li>
 *   <li>提交成功信号（onCommitSuccess→closeSwappedOutResourcesNow）之前旧池不得关闭，
 *       信号后恰好关一次；</li>
 *   <li>失败路径 restoreSwappedOutResourcesOnFailure：旧池引用恢复进 MetaClusterCurrent、
 *       旧池保持存活、本轮新建资源被 giveup（关闭）。</li>
 * </ol>
 * 完全离线：Druid 池惰性建连（同 {@code ConfigUpdaterOrphanConfigTest} 前提），不连库、不起实例。
 */
public class CommitAtomicityOrderTest {

    private Map<Class, Object> savedContext;
    private JdbcConnectionManager oldPool;
    private final AtomicInteger oldPoolCloseCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
        oldPool = Mockito.mock(JdbcConnectionManager.class);
        Mockito.doAnswer(inv -> {
            oldPoolCloseCount.incrementAndGet();
            return null;
        }).when(oldPool).close();
        MetaClusterCurrent.register(JdbcConnectionManager.class, oldPool);
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    private MycatRouterConfigOps newOps() {
        return new MycatRouterConfigOps(new MycatRouterConfig(), null,
                Options.builder().persistence(false).build());
    }

    /** 数据集变更（old→new 各一条）触发 getJdbcConnectionManager 的换池分支。 */
    private Object datasourceSwapUpdateSet() {
        DatasourceConfig oldDs = datasource("oldDs");
        DatasourceConfig newDs = datasource("newDs");
        return io.mycat.sqlhandler.config.UpdateSet.create(
                Collections.singletonList(newDs), Collections.singletonList(oldDs));
    }

    private static DatasourceConfig datasource(String name) {
        DatasourceConfig datasourceConfig = new DatasourceConfig();
        datasourceConfig.setName(name);
        datasourceConfig.setUser("root");
        datasourceConfig.setPassword("123456");
        datasourceConfig.setDbType("mysql");
        datasourceConfig.setUrl("jdbc:mysql://127.0.0.1:3306/mysql?useSSL=false");
        datasourceConfig.setInstanceType("READ_WRITE");
        return datasourceConfig;
    }

    @SuppressWarnings("unchecked")
    private Resource<JdbcConnectionManager> invokeGetJdbcConnectionManager(
            MycatRouterConfigOps ops, Object updateSet) throws Exception {
        Method m = MycatRouterConfigOps.class.getDeclaredMethod("getJdbcConnectionManager",
                io.mycat.sqlhandler.config.UpdateSet.class);
        m.setAccessible(true);
        return (Resource<JdbcConnectionManager>) m.invoke(ops, updateSet);
    }

    private static void invokePackageMethod(MycatRouterConfigOps ops, String name) throws Exception {
        Method m = MycatRouterConfigOps.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(ops);
    }

    @Test
    void swapGetterMustNotCloseOldPoolEagerly() throws Exception {
        MycatRouterConfigOps ops = newOps();
        Resource<JdbcConnectionManager> resource = invokeGetJdbcConnectionManager(ops, datasourceSwapUpdateSet());

        Assertions.assertSame(oldPool, MetaClusterCurrent.wrapper(JdbcConnectionManager.class),
                "交换 getter 只构建新池，不得动 MetaClusterCurrent 的旧池注册");
        Assertions.assertEquals(0, oldPoolCloseCount.get(),
                "I-DEBT-130 核心断言：交换 getter 返回时旧池必须仍然存活"
                        + "（现码在 finally 里立即 close 旧池，后续阶段一旦失败整机 getConnection 失效）");
        Assertions.assertFalse(resource.isBorrow(), "数据集变更分支必须返回新建池（borrow=false）");
        Assertions.assertNotSame(oldPool, resource.get(), "返回的是新池而非旧池");
    }

    @Test
    void closeDeferredUntilCommitSuccessSignal() throws Exception {
        MycatRouterConfigOps ops = newOps();
        Resource<JdbcConnectionManager> resource = invokeGetJdbcConnectionManager(ops, datasourceSwapUpdateSet());
        Assertions.assertEquals(0, oldPoolCloseCount.get(), "信号前旧池存活（顺序断言 1）");

        // 提交成功信号：仅在此刻允许关闭换下的旧池
        invokePackageMethod(ops, "closeSwappedOutResourcesNow");
        Assertions.assertEquals(1, oldPoolCloseCount.get(),
                "提交成功信号后旧池恰好关闭一次（close 必须发生在注册成功之后）");

        // 幂等：重复信号不得二次关闭
        invokePackageMethod(ops, "closeSwappedOutResourcesNow");
        Assertions.assertEquals(1, oldPoolCloseCount.get(), "旧池关闭必须幂等（不得重复 close）");
    }

    @Test
    void failurePathRestoresOldPoolReferenceAndKeepsItAlive() throws Exception {
        MycatRouterConfigOps ops = newOps();
        Resource<JdbcConnectionManager> resource = invokeGetJdbcConnectionManager(ops, datasourceSwapUpdateSet());

        // 模拟 commit 早段注册新池（commit() :533-534 的 MetaClusterCurrent.register）
        MetaClusterCurrent.register(JdbcConnectionManager.class, resource.get());
        Assertions.assertNotSame(oldPool, MetaClusterCurrent.wrapper(JdbcConnectionManager.class));

        // 模拟后续阶段失败（metadata/authenticator 构建抛出等）→ 失败恢复路径
        invokePackageMethod(ops, "restoreSwappedOutResourcesOnFailure");

        Assertions.assertSame(oldPool, MetaClusterCurrent.wrapper(JdbcConnectionManager.class),
                "失败路径必须把旧池引用恢复回 MetaClusterCurrent（整机 getConnection 不得失效）");
        Assertions.assertEquals(0, oldPoolCloseCount.get(),
                "失败路径旧池必须保持存活（close=0，等待下次 commit 正常退休）");
        // 本轮新建资源（未提交成功）必须被 giveup 并清出交换登记
        Assertions.assertTrue(ops.swappedInResources.isEmpty(),
                "失败路径新建资源必须 giveup 并清出登记（不得残留到下一轮 commit）");
        Assertions.assertTrue(ops.swappedOutResources.isEmpty(),
                "恢复完成后交换登记必须清空（不得残留到下一轮 commit）");
    }
}
