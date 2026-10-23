package io.mycat.datasource.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-AU-6 Task 2 锁：DruidDatasourceProvider 的驱动路由必须<b>显式</b>且<b>错配响亮</b>。
 * <ul>
 * <li>{@code driverClassFor}：dbType→驱动类静态映射（mariadb→org.mariadb.jdbc.Driver、mysql→com.mysql.cj.jdbc.Driver、
 * 其它→null 保持"不干预"现状，不扩面）。不赌 shade 产物里 ServiceLoader 描述文件的存活。</li>
 * <li>{@code checkUrlSchemeMatchesDbType}：dbType=mariadb 但 URL 是 jdbc:mysql: = 错配——此形态连 ed25519 用户
 * 必失败于驱动原生 {@code Unable to load authentication plugin}，宁可在建池前抛 IllegalStateException 指路。</li>
 * </ul>
 * 纯静态断言，不建池（建池路径的触发点在 createDataSource，行为由本二函数决定）。
 */
class DriverRoutingTest {

    @Test
    void mariadbDbTypeResolvesDriver() throws Exception {
        assertEquals("org.mariadb.jdbc.Driver", DruidDatasourceProvider.driverClassFor("mariadb"));
        Class.forName(DruidDatasourceProvider.driverClassFor("mariadb"));
    }

    @Test
    void mysqlDbTypeResolvesDriver() throws Exception {
        assertEquals("com.mysql.cj.jdbc.Driver", DruidDatasourceProvider.driverClassFor("mysql"));
        Class.forName(DruidDatasourceProvider.driverClassFor("mysql"));
    }

    @Test
    void unknownDbTypeReturnsNull() {
        assertNull(DruidDatasourceProvider.driverClassFor("clickhouse"));
        assertNull(DruidDatasourceProvider.driverClassFor(null));
    }

    /** dbType 字段默认 "mysql"(DatasourceConfig:29) 会遮蔽 URL 真相：jdbc:mariadb:// URL 必须按 scheme 路由 mariadb 驱动。 */
    @Test
    void urlSchemeWinsOverStaleDefaultDbType() {
        assertEquals("org.mariadb.jdbc.Driver", DruidDatasourceProvider.resolveDriverClass("mysql",
                "jdbc:mariadb://localhost:3308/mysql"));
        // mysql URL + 默认 dbType = 现状不变；mariadb dbType 显式声明也保持 mariadb
        assertEquals("com.mysql.cj.jdbc.Driver", DruidDatasourceProvider.resolveDriverClass("mysql",
                "jdbc:mysql://localhost:3306/db1"));
        assertEquals("org.mariadb.jdbc.Driver", DruidDatasourceProvider.resolveDriverClass("mariadb",
                "jdbc:mariadb://localhost:3308/mysql"));
        // 非 mysql/mariadb dbType 不扩面：走 driverClassFor 原语义
        assertNull(DruidDatasourceProvider.resolveDriverClass("clickhouse", "jdbc:clickhouse://localhost/x"));
    }

    @Test
    void schemeDbTypeMismatchFailsLoud() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DruidDatasourceProvider.checkUrlSchemeMatchesDbType("mariadb", "jdbc:mysql://localhost:3306/test"));
        assertTrue(e.getMessage().contains("jdbc:mariadb"), "报错须指路 jdbc:mariadb://，实得：" + e.getMessage());
        // 反向与匹配形态不得误伤：mysql dbType + jdbc:mysql://、mariadb dbType + jdbc:mariadb://、null dbType 均放行
        assertDoesNotThrow(() -> DruidDatasourceProvider.checkUrlSchemeMatchesDbType("mysql", "jdbc:mysql://localhost:3306/test"));
        assertDoesNotThrow(() -> DruidDatasourceProvider.checkUrlSchemeMatchesDbType("mariadb", "jdbc:mariadb://localhost:3308/test"));
        assertDoesNotThrow(() -> DruidDatasourceProvider.checkUrlSchemeMatchesDbType(null, "jdbc:clickhouse://localhost/test"));
    }
}
