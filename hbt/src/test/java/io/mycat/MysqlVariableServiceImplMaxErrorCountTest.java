package io.mycat;

import io.mycat.config.ServerPersona;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * I-DEBT-69 Blade C：无后端（兜底模板表）路径下 {@code @@max_error_count} 必须按 persona 给出真库默认值。
 *
 * <p>真库判据（2026-10-31 活体钉死七档，{@code SELECT @@max_error_count}）：
 * <ul>
 *   <li>MySQL 5.7.44 → 64；8.0.46 / 8.4.11 / 9.0.1 → <b>1024</b></li>
 *   <li>MariaDB 10.6.28 / 10.11.19 / 11.4.13 → <b>64</b></li>
 * </ul>
 * 即：MariaDB 一律 64；MySQL major ≥ 8 为 1024；其余 64。
 *
 * <p>破口：兜底表原不含该键，{@code SHOW SESSION VARIABLES} 缺行、{@code SELECT @@max_error_count}
 * 回 NULL，与真库默认值不符；且 {@code ShowWarningsSQLHandler} 截断逻辑依赖该键，取不到只能保守不截断。
 */
class MysqlVariableServiceImplMaxErrorCountTest {

    private Map<Class, Object> savedContext;

    @BeforeEach
    void saveContext() {
        savedContext = MetaClusterCurrent.copyContext();
    }

    @AfterEach
    void restoreContext() {
        MetaClusterCurrent.register(savedContext == null ? new IdentityHashMap<>() : savedContext);
    }

    /** 无后端数据源装配路径（fallback 模板表）。 */
    private MysqlVariableServiceImpl assembleWithoutBackend() {
        JdbcConnectionManager manager = mock(JdbcConnectionManager.class);
        when(manager.getConfigAsMap()).thenReturn(Map.of());
        return new MysqlVariableServiceImpl(manager);
    }

    private String sessionValueOf(ServerPersona persona) {
        MetaClusterCurrent.register(VersionProfile.class, VersionProfileFactory.of(persona, null));
        return String.valueOf(assembleWithoutBackend().getSessionVariable("max_error_count"));
    }

    @Test
    void mysql57FallsBackTo64() {
        assertEquals("64", sessionValueOf(ServerPersona.MYSQL_5_7),
                "MySQL 5.7 真库 @@max_error_count 默认 64");
    }

    @Test
    void mysql80FallsBackTo1024() {
        assertEquals("1024", sessionValueOf(ServerPersona.MYSQL_8_0),
                "MySQL 8.0 真库 @@max_error_count 默认 1024");
    }

    @Test
    void mysql84FallsBackTo1024() {
        assertEquals("1024", sessionValueOf(ServerPersona.MYSQL_8_4),
                "MySQL 8.4 真库 @@max_error_count 默认 1024");
    }

    @Test
    void mysql90FallsBackTo1024() {
        assertEquals("1024", sessionValueOf(ServerPersona.MYSQL_9_X),
                "MySQL 9.0 真库 @@max_error_count 默认 1024");
    }

    @Test
    void mariadb106FallsBackTo64() {
        assertEquals("64", sessionValueOf(ServerPersona.MARIADB_10_6),
                "MariaDB 10.6 真库 @@max_error_count 默认 64（与 MySQL 8 的 1024 不同轨）");
    }

    @Test
    void mariadb1011FallsBackTo64() {
        assertEquals("64", sessionValueOf(ServerPersona.MARIADB_10_11),
                "MariaDB 10.11 真库 @@max_error_count 默认 64");
    }

    @Test
    void mariadb114FallsBackTo64() {
        assertEquals("64", sessionValueOf(ServerPersona.MARIADB_11_4),
                "MariaDB 11.4 真库 @@max_error_count 默认 64");
    }

    /** SHOW SESSION VARIABLES 走 getSessionVariables()，兜底表必须真的带这一行。 */
    @Test
    void sessionVariableListContainsMaxErrorCount() {
        MetaClusterCurrent.register(VersionProfile.class, VersionProfileFactory.of(ServerPersona.MYSQL_8_0, null));
        MysqlVariableServiceImpl service = assembleWithoutBackend();

        Object value = null;
        for (Object[] row : service.getSessionVariables()) {
            if ("max_error_count".equalsIgnoreCase(String.valueOf(row[0]))) {
                value = row[1];
                break;
            }
        }
        assertNotNull(value, "兜底 SHOW SESSION VARIABLES 必须含 max_error_count 行");
        assertEquals("1024", String.valueOf(value));
    }

    /** 读名兼容：GET @@max_error_count 走不带前缀的键；global 侧同值（模板表 session 同步到 global）。 */
    @Test
    void globalAndBareNameReadSameValue() {
        MetaClusterCurrent.register(VersionProfile.class, VersionProfileFactory.of(ServerPersona.MYSQL_8_0, null));
        MysqlVariableServiceImpl service = assembleWithoutBackend();

        assertEquals("1024", String.valueOf(service.getGlobalVariable("@@max_error_count")));
        assertEquals("1024", String.valueOf(service.getSessionVariable("@@session.max_error_count")),
                "带 session 前缀经 NameMap 归一后同样命中");
    }
}
