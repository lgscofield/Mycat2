package io.mycat.sqlhandler.dql;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowCollationStatement;
import io.mycat.MetaClusterCurrent;
import io.mycat.MetadataManager;
import io.mycat.MycatDataContext;
import io.mycat.Response;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.config.ServerPersona;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import io.mycat.datasource.jdbc.datasource.DefaultConnection;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.sqlhandler.SQLRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * I-DEBT-70 刀 3 离线锁：{@code SHOW COLLATION} 服务面的分档渲染决策。
 *
 * <p>锁四条契约（spec 2026-11-03 §5 刀 3 / §7）：
 * ① Connector/NET 身份 + MariaDB persona + 真 MariaDB ≥10.11 后端 → 出**物化档快照**
 * （{@code sendResultSet}），不再透传模板档；
 * ② 任何其他身份（含 E8 的近似形状）→ 维持今日 {@code proxySelect} 透传，且不碰后端 old_mode；
 * ③ persona 非分档档 → 透传；后端串档（实为 MySQL）→ 透传；
 * ④ 取数任一步失败 → **回落今日透传路径**，绝不比现状更差，且会话态必复原。
 *
 * <p>假件：{@link FakeBackendJdbc}（JDBC 动态代理）+ Mockito（Response / 池 / 会话）。不连真库、不起服务。
 */
public class ShowCollationMaterializationDispatchTest {

    private static final String CLIENT_NAME = CollationMaterialization.CLIENT_NAME_ATTR;
    private static final String DOTNET = CollationMaterialization.CONNECTOR_NET;

    private Map<Class, Object> savedContext;
    private FakeBackendJdbc backend;
    private JdbcConnectionManager manager;
    private Response response;
    private MycatDataContext dataContext;

    @BeforeEach
    void registerFakeRuntime() {
        savedContext = new IdentityHashMap<>(MetaClusterCurrent.copyContext());
        backend = new FakeBackendJdbc();
        backend.showRows = Arrays.asList(
                new Object[]{"utf8mb3_general_ci", "utf8mb3", 11, "Yes", "Yes", 8},
                new Object[]{"uca1400_ai_ci", "utf8mb3", 2048, "Yes", "Yes", 8});

        DefaultConnection borrowed = Mockito.mock(DefaultConnection.class);
        Mockito.when(borrowed.getRawConnection()).thenReturn(backend.connection());
        manager = Mockito.mock(JdbcConnectionManager.class);
        Mockito.when(manager.getConnection(Mockito.anyString())).thenReturn(borrowed);

        response = Mockito.mock(Response.class);
        dataContext = Mockito.mock(MycatDataContext.class);

        MetaClusterCurrent.register(JdbcConnectionManager.class, manager);
        MetaClusterCurrent.register(VersionProfile.class,
                VersionProfileFactory.of(ServerPersona.MARIADB_10_11, null));
    }

    @AfterEach
    void restoreContext() {
        MetaClusterCurrent.register(savedContext);
    }

    // ------------------------------------------------------------------ tests

    /** ① dotnet 身份 → 物化档快照回前端，且真的种过位、复原过。 */
    @Test
    void dotnetIdentityGetsMaterializedSnapshotNotPassthrough() {
        runShow(attrs(CLIENT_NAME, DOTNET));

        Mockito.verify(response).sendResultSet(Mockito.any(RowBaseIterator.class));
        Mockito.verify(response, Mockito.never())
                .proxySelect(Mockito.anyList(), Mockito.anyString(), Mockito.anyList());
        Assertions.assertTrue(backend.sqls.stream()
                        .anyMatch(s -> s != null && s.startsWith("SET old_mode=CONCAT_WS")),
                "必须种位，实得语句序 " + backend.sqls);
        Assertions.assertEquals("SET old_mode='UTF8_IS_UTF8MB3'",
                backend.sqls.get(backend.sqls.size() - 1), "末条必须复原会话态，实得 " + backend.sqls);
    }

    /** ② 非 dotnet 身份（E8 近似形状也算）→ 一律维持透传，且绝不改后端会话态。 */
    @Test
    void otherIdentityKeepsPassthrough() {
        for (Map<String, String> attrs : List.of(
                attrs(CLIENT_NAME, "MySQL Connector Net 8.0.30"),
                attrs(CLIENT_NAME, "MYSQL-CONNECTOR-NET"),
                attrs(CLIENT_NAME, "libmysql"),
                attrs("_client_version", "8.0.30"),
                Collections.<String, String>emptyMap())) {
            setUpFreshBackend();
            runShow(attrs);
            Mockito.verify(response, Mockito.atLeastOnce())
                    .proxySelect(Mockito.anyList(), Mockito.anyString(), Mockito.anyList());
            Mockito.verify(response, Mockito.never()).sendResultSet(Mockito.any(RowBaseIterator.class));
            Assertions.assertTrue(backend.sqls.stream().noneMatch(s -> s != null && s.startsWith("SET old_mode")),
                    "未授权身份不得改后端会话态，实得 " + backend.sqls);
        }
    }

    /** ③ persona 不是分档档（8.0 / MariaDB 10.6）→ 透传。 */
    @Test
    void nonSegmentedPersonaKeepsPassthrough() {
        MetaClusterCurrent.register(VersionProfile.class,
                VersionProfileFactory.of(ServerPersona.MYSQL_8_0, null));
        runShow(attrs(CLIENT_NAME, DOTNET));
        Mockito.verify(response).proxySelect(Mockito.anyList(), Mockito.anyString(), Mockito.anyList());
        Mockito.verify(response, Mockito.never()).sendResultSet(Mockito.any(RowBaseIterator.class));

        MetaClusterCurrent.register(VersionProfile.class,
                VersionProfileFactory.of(ServerPersona.MARIADB_10_6, null));
        setUpFreshBackend();
        runShow(attrs(CLIENT_NAME, DOTNET));
        Mockito.verify(response, Mockito.atLeastOnce())
                .proxySelect(Mockito.anyList(), Mockito.anyString(), Mockito.anyList());
        Mockito.verify(response, Mockito.never()).sendResultSet(Mockito.any(RowBaseIterator.class));
    }

    /** ③' 后端串档（persona 说 MariaDB，借来的物理连接实为 MySQL 8.0）→ 透传，不乱设变量。 */
    @Test
    void straddledMysqlBackendKeepsPassthrough() {
        backend.product = "MySQL";
        backend.version = "8.0.34";
        runShow(attrs(CLIENT_NAME, DOTNET));
        Mockito.verify(response).proxySelect(Mockito.anyList(), Mockito.anyString(), Mockito.anyList());
        Mockito.verify(response, Mockito.never()).sendResultSet(Mockito.any(RowBaseIterator.class));
        Assertions.assertTrue(backend.sqls.stream().noneMatch(s -> s != null && s.startsWith("SET old_mode")),
                "实得 " + backend.sqls);
    }

    /** ④ 取数中途失败 → 回落透传（不比现状差）+ 会话态必复原。 */
    @Test
    void materializationFailureFallsBackToPassthroughAndRestores() {
        // 第 1 次 SHOW 是 handler 的 prototype 试跑（须成功），第 2 次才是取数——注入在取数上
        backend.failOnShow = new java.sql.SQLException("boom");
        backend.failOnShowAt = 2;
        runShow(attrs(CLIENT_NAME, DOTNET));

        Mockito.verify(response).proxySelect(Mockito.anyList(), Mockito.anyString(), Mockito.anyList());
        Mockito.verify(response, Mockito.never()).sendResultSet(Mockito.any(RowBaseIterator.class));
        Assertions.assertEquals("SET old_mode='UTF8_IS_UTF8MB3'",
                backend.sqls.get(backend.sqls.size() - 1), "失败也必须复原，实得 " + backend.sqls);
    }

    /** 会话拿不到 attrs（未宣告能力位/旧客户端/本地 JDBC 通道）→ 今日行为，透传。 */
    @Test
    void sessionWithoutAttrsKeepsTodayBehavior() {
        MycatDataContext plain = Mockito.mock(MycatDataContext.class);
        Mockito.when(plain.getClientConnectAttrs()).thenReturn(Collections.emptyMap());
        runShowOn(plain);
        Mockito.verify(response).proxySelect(Mockito.anyList(), Mockito.anyString(), Mockito.anyList());
    }

    // ------------------------------------------------------------------ helpers

    private void runShow(Map<String, String> attrs) {
        Mockito.when(dataContext.getClientConnectAttrs()).thenReturn(attrs);
        runShowOn(dataContext);
    }

    private void runShowOn(MycatDataContext ctx) {
        MySqlShowCollationStatement ast =
                (MySqlShowCollationStatement) SQLUtils.parseSingleMysqlStatement(FakeBackendJdbc.SHOW);
        SQLRequest<MySqlShowCollationStatement> request = new SQLRequest<>(ast);
        new ShowCollationSQLHandler().execute(request, ctx, response);
    }

    /** 逐 sub-test 重置假件语句序，避免多身份循环里相互污染断言。 */
    private void setUpFreshBackend() {
        backend.sqls.clear();
        backend.failOnShow = null;
        backend.failOnRead = null;
        backend.failOnSeal = null;
        backend.failOnShowAt = 1;
        backend.resetScriptCounters();
        Mockito.clearInvocations(response);
    }

    private static Map<String, String> attrs(String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    /** 装置自检（承重）：假池借出 + 试跑，逐字复刻 handler 的 prototype 试跑腿，异常必须外显而非被吞。 */
    @Test
    void fakePoolBorrowServesHandlerTryRun() {
        Throwable thrown = null;
        try {
            JdbcConnectionManager m = MetaClusterCurrent.wrapper(JdbcConnectionManager.class);
            try (DefaultConnection connection = m.getConnection(MetadataManager.getPrototype())) {
                com.alibaba.druid.util.JdbcUtils.executeQuery(connection.getRawConnection(),
                        FakeBackendJdbc.SHOW, Collections.emptyList());
            }
        } catch (Throwable t) {
            thrown = t;
        }
        Assertions.assertNull(thrown, "试跑腿必须通，实得 " + (thrown == null ? "" : java.util.Arrays.toString(thrown.getStackTrace())));
    }

    /** 直接坐线：物化档列必须原列原序 6 列（E7：MariaDB 10.11 无 Pad_attribute）。 */
    @Test
    void materializedSnapshotKeepsBackendColumnOrder() throws Exception {
        CollationMaterialization.Snapshot snapshot =
                CollationMaterialization.fetchSnapshot(backend.connection(), FakeBackendJdbc.SHOW);
        if (snapshot == null) {
            Assertions.fail("取数须产出快照（当前 RED 态：未实现）");
        }
        Assertions.assertEquals(Arrays.asList("Collation", "Charset", "Id", "Default", "Compiled", "Sortlen"),
                snapshot.getColumns().stream().map(c -> c.columnLabel).collect(java.util.stream.Collectors.toList()));

        // 上线名锁（活体第一轮踩过的坑）：Mycat 重建轨把 RowMetaData.getColumnName(i) 逐字写进
        // column_definition 的 name 位，而假件照 Connector/J 实况把物理名(ID)与显示名(Id)分开给。
        // 故此处必须见到**显示名**；取错源会立刻得到 ID/CHARACTER_SET_NAME，MySql.Data 的
        // reader["charset"] 随即 IndexOutOfRangeException。
        try (io.mycat.api.collector.RowBaseIterator it = snapshot.toBuilder().build()) {
            io.mycat.beans.mycat.MycatRowMetaData meta = it.getMetaData();
            List<String> wireNames = new java.util.ArrayList<>();
            for (int i = 0; i < meta.getColumnCount(); i++) {
                wireNames.add(meta.getColumnName(i));
            }
            Assertions.assertEquals(Arrays.asList("Collation", "Charset", "Id", "Default", "Compiled", "Sortlen"),
                    wireNames, "wire 列名必须是显示名，不得漏出 information_schema 物理名");
        }
    }

    /** 兜底：MetadataManager 原型名在本测试上下文里可解析（防止 mock 走错 target）。 */
    @Test
    void prototypeTargetIsResolvable() {
        Assertions.assertNotNull(MetadataManager.getPrototype());
    }

    /** 装置自检（承重）：假件必须能跑通 Druid JdbcUtils 的 prototype 试跑腿，否则下面的分档断言全不可信。 */
    @Test
    void fakeBackendServesDruidTryRun() throws Exception {
        java.util.List<java.util.Map<String, Object>> rows = com.alibaba.druid.util.JdbcUtils.executeQuery(
                backend.connection(), FakeBackendJdbc.SHOW, Collections.emptyList());
        Assertions.assertEquals(2, rows.size(), "试跑应读到两行，实得 " + rows);
        Assertions.assertEquals("uca1400_ai_ci", rows.get(1).get("Collation"));
        Assertions.assertEquals(2048, rows.get(1).get("Id"));
    }
}
