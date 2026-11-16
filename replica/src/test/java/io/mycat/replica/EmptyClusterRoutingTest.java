package io.mycat.replica;

import io.mycat.ReplicaBalanceType;
import io.mycat.config.ClusterConfig;
import io.mycat.config.DatasourceConfig;
import io.mycat.plug.loadBalance.LoadBalanceManager;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * MYCAT CHANGE (I-DEBT-140): 全孤儿集群（I-DEBT-98 自愈后注册为空）在运行时路由不应裸崩。
 * 修复前：getDatasourceNameByReplicaName 对空集群走 getDatasource → requireNonNull("No data source available")
 * 抛 NPE → 外层 catch 的 !master 兜底对空 rawDataSourceMap 做 iterator().next() 抛 NoSuchElementException。
 * 修复后：读写列表与原始实例全空时直接走 graceful 语义（return replicaName）。
 */
public class EmptyClusterRoutingTest {

    private ReplicaSelectorRuntime newRuntimeWithAllOrphanCluster() {
        ClusterConfig clusterConfig = new ClusterConfig();
        clusterConfig.setName("orphan_cluster");
        // 全孤儿：引用的数据源配置全部缺失
        clusterConfig.setMasters(Collections.singletonList("ds_missing_master"));
        clusterConfig.setReplicas(Collections.singletonList("ds_missing_replica"));

        // 全局数据源映射非空（否则 I-DEBT-98 会整体跳过集群，不进入本票的崩溃路径）
        DatasourceConfig unrelated = new DatasourceConfig();
        unrelated.setName("ds_other");
        Map<String, DatasourceConfig> datasources = new HashMap<>();
        datasources.put("ds_other", unrelated);

        return new ReplicaSelectorRuntime(
                Collections.singletonList(clusterConfig),
                datasources,
                new LoadBalanceManager(),
                name -> 0,
                (command, initialDelay, period, unit) -> null);
    }

    @Test
    public void allOrphanClusterRoutingMustNotThrow() {
        ReplicaSelectorRuntime runtime = newRuntimeWithAllOrphanCluster();
        // 集群已注册但实例为空
        ReplicaSelector selector = runtime.getReplicaMap().get("orphan_cluster");
        assertNotNull(selector);
        assertEquals(0, selector.getRawDataSourceMap().size());

        // 读路由（曾抛 NoSuchElementException）不抛，走 graceful 返回 replicaName
        String read = runtime.getDatasourceNameByReplicaName("orphan_cluster", false, null);
        assertEquals("orphan_cluster", read);
        // 写路由不抛
        String write = runtime.getDatasourceNameByReplicaName("orphan_cluster", true, null);
        assertEquals("orphan_cluster", write);
        // 随机入口不抛
        assertDoesNotThrow(runtime::getDatasourceNameByRandom);
    }

    @Test
    public void allOrphanClusterPhysicsInstanceRoutingMustNotThrow() {
        ReplicaSelectorRuntime runtime = newRuntimeWithAllOrphanCluster();
        // PhysicsInstance 级路由入口同样不抛（曾由 requireNonNull("No data source available") 裸崩）
        assertDoesNotThrow(() -> runtime.getDatasourceByReplicaName("orphan_cluster", false, null));
        assertDoesNotThrow(() -> runtime.getWriteDatasourceByReplicaName("orphan_cluster", null));
        assertDoesNotThrow(() -> runtime.getDatasourceNameByReplicaName("orphan_cluster", false,
                ReplicaBalanceType.NONE, null));
    }
}
