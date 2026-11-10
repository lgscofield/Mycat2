package io.mycat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// I-DEBT-100/G8 离线锁: reset() 保留部署特定数据源凭据三元组(url/user/password)。
//
// 活体 RED 证据(修复前,三次同因事故):
//   ① G1 验收插曲(套件 RESET_CONFIG 后本机 prototypeDs 变 123456 → 后端全断)
//   ② E8 事故(同上)
//   ③ G8 wedge(compat 测试 RESET_CONFIG → 出厂 123456@localhost:3306 ≠ 本机 root/root → Druid 无限重试)
// 语义: reset 重置路由/schema 面,但数据源凭据是部署特定资产,不随出厂默认回归。
// CI 兼容性: CI 宿主既有 prototypeDs 即 123456,保留=同值,零变化。
public class ResetPreservesPrototypeCredentialsTest {

    private MycatRouterConfigOps opsWithPrototype(String url, String user, String password) {
        MycatRouterConfig existing = new MycatRouterConfig();
        DatasourceConfig ds = new DatasourceConfig();
        ds.setName("prototypeDs");
        ds.setDbType("mysql");
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        existing.getDatasources().add(ds);
        return new MycatRouterConfigOps(existing, null, null, existing);
    }

    @Test
    public void resetPreservesDeploymentCredentials() {
        MycatRouterConfigOps ops = opsWithPrototype(
                "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false", "root", "root");
        DatasourceConfig before = ops.getNewConfig().getDatasources().get(0);
        assertEquals("root", before.getUser());
        assertEquals("root", before.getPassword());
        ops.reset();
        DatasourceConfig after = ops.getNewConfig().getDatasources().stream()
                .filter(d -> "prototypeDs".equals(d.getName())).findFirst().orElseThrow();
        assertEquals("root", after.getUser(), "user 应保留部署值");
        assertEquals("root", after.getPassword(), "password 应保留部署值(出厂 123456 会致后端全断)");
        assertTrue(after.getUrl().contains("dbaudit_a"), "url 应保留部署值");
    }

    @Test
    public void resetStillResetsRoutingFace() {
        MycatRouterConfigOps ops = opsWithPrototype(
                "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false", "root", "root");
        // 路由面(schema/cluster)应回归出厂默认
        ops.reset();
        assertEquals(0, ops.getNewConfig().getSchemas().size(), "schema 应清空");
        assertTrue(ops.getNewConfig().getClusters().stream()
                .anyMatch(c -> "prototype".equals(c.getName())), "集群回归出厂 prototype");
    }

    @Test
    public void freshResetWithoutExistingPrototypeUsesBundledDefault() {
        MycatRouterConfigOps ops = new MycatRouterConfigOps(new MycatRouterConfig(), null, null, new MycatRouterConfig());
        ops.reset();
        DatasourceConfig after = ops.getNewConfig().getDatasources().stream()
                .filter(d -> "prototypeDs".equals(d.getName())).findFirst().orElseThrow();
        assertEquals("123456", after.getPassword(), "无既有部署凭据时回落出厂默认");
    }
}
