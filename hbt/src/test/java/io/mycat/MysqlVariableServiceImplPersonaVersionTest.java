package io.mycat;

import io.mycat.calcite.sqlfunction.infofunction.MycatVersionFunction;
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
 * I-DEBT-29-EXT 离线锁：{@code @@version} 变量读腿必须与 persona 宣称同源。
 * <p>
 * 实证破口（2026-10-30 活体 RED 目视，persona=MYSQL_5_7 + mysql:8.0 后端）：
 * {@code select @@version} 返 <b>8.0.46</b>（后端 SHOW GLOBAL VARIABLES 透传真值），而握手 serverVersion 与
 * {@code select version()}（{@link MycatVersionFunction}）返 persona 串——同一连接三宣称自相矛盾。
 * DBeaver 等 GUI 读 {@code @@version} 判方言，此裂缝直接破坏"元数据 GUI 无缝"判据（§7 第六列）。
 * <p>
 * 修法（本锁钉的行为契约）：{@link MysqlVariableServiceImpl} 装配期把 {@code version} 键归一为
 * {@link MycatVersionFunction#version()}（变量/函数两宣称口锁死同源，DI 未注册时共同回落缺省画像）。
 * 后端真值经 {@code @@version} 不再泄露——与 VERSION() 既有 persona 化口径对齐，非新发明。
 */
class MysqlVariableServiceImplPersonaVersionTest {

    private Map<Class, Object> savedContext;

    @BeforeEach
    void saveContext() {
        savedContext = MetaClusterCurrent.copyContext();
    }

    @AfterEach
    void restoreContext() {
        MetaClusterCurrent.register(savedContext == null ? new IdentityHashMap<>() : savedContext);
    }

    /** 无后端数据源装配路径（fallback 模板表）：version 键仍须按 persona 归一。 */
    private MysqlVariableServiceImpl assembleWithoutBackend() {
        JdbcConnectionManager manager = mock(JdbcConnectionManager.class);
        when(manager.getConfigAsMap()).thenReturn(Map.of());
        return new MysqlVariableServiceImpl(manager);
    }

    @Test
    void globalVersionFollowsRegisteredPersona() {
        VersionProfile profile = VersionProfileFactory.of(ServerPersona.MYSQL_8_0, null);
        MetaClusterCurrent.register(VersionProfile.class, profile);

        MysqlVariableServiceImpl service = assembleWithoutBackend();

        Object v = service.getGlobalVariable("@@version");
        assertNotNull(v, "version 键必须存在（fallback 模板原不含，persona 归一负责补上）");
        assertEquals("8.0.34", String.valueOf(v),
                "@@version 必须等于 persona MYSQL_8_0 的宣称串（与握手/VERSION() 同源）");
    }

    @Test
    void sessionVersionFollowsRegisteredPersona() {
        VersionProfile profile = VersionProfileFactory.of(ServerPersona.MARIADB_10_11, null);
        MetaClusterCurrent.register(VersionProfile.class, profile);

        MysqlVariableServiceImpl service = assembleWithoutBackend();

        assertEquals("10.11.8-MariaDB", String.valueOf(service.getSessionVariable("version")),
                "@@session.version 同样按画像归一（MariaDB 画像亦不得泄露后端真值）");
    }

    @Test
    void variableAndFunctionShareSingleAuthority() {
        VersionProfile profile = VersionProfileFactory.of(ServerPersona.MYSQL_9_X, null);
        MetaClusterCurrent.register(VersionProfile.class, profile);

        MysqlVariableServiceImpl service = assembleWithoutBackend();

        String fromVariable = String.valueOf(service.getGlobalVariable("version"));
        String fromFunction = MycatVersionFunction.version();
        assertEquals(fromFunction, fromVariable,
                "version() 与 @@version 宣称口必须单一权威（活体 P2 格的离线同构锁）");
        assertEquals("9.0.1", fromVariable);
    }

    @Test
    void fallsBackToDefaultProfileWithoutDi() {
        // DI 未注册 VersionProfile（引导早期/单测环境）：不得 NPE，回落缺省画像串（与 VERSION() 惯例一致）。
        MetaClusterCurrent.register(new IdentityHashMap<>());

        MysqlVariableServiceImpl service = assembleWithoutBackend();

        assertEquals(VersionProfileFactory.defaultProfile().serverVersionString(),
                String.valueOf(service.getGlobalVariable("version")),
                "无 DI 时回落 MYSQL_5_7 缺省画像，与 MycatVersionFunction 的缺省回落同口径");
    }
}
