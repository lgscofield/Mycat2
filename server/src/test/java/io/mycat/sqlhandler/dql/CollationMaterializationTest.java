package io.mycat.sqlhandler.dql;

import io.mycat.config.ServerPersona;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * I-DEBT-70 刀 2 离线锁：物化档身份谓词（E5/E8 全变体）+ 真库取数四步序 + old_mode 必复原。
 *
 * <p>取数序钉死为（同一借出连接内）：① {@code SELECT @@old_mode} 存前值；② 加法式种位
 * （E6 实测：裸 SET 会冲掉既有 UTF8_IS_UTF8MB3）；③ 原样执行 SHOW COLLATION；④ 复原前值，
 * 且 ③ 抛错时 ④ 仍必达——{@code old_mode} 是物理会话态，池连接跨会话共享，泄漏即污染他人会话。
 *
 * <p>假件见 {@link FakeBackendJdbc}，全程不连真库。
 */
public class CollationMaterializationTest {

    private static final String SHOW = FakeBackendJdbc.SHOW;

    /** E5/E8：唯一触发值 _client_name == "mysql-connector-net"（大小写敏感、全等）。 */
    @Test
    void predicateMatchesConnectorNetExactlyUnderMariadbPersona() {
        Assertions.assertTrue(CollationMaterialization.shouldMaterialize(
                attrs(CollationMaterialization.CLIENT_NAME_ATTR, CollationMaterialization.CONNECTOR_NET),
                ServerPersona.MARIADB_10_11));
        Assertions.assertTrue(CollationMaterialization.shouldMaterialize(
                attrs(CollationMaterialization.CLIENT_NAME_ATTR, CollationMaterialization.CONNECTOR_NET),
                ServerPersona.MARIADB_11_4));
    }

    /** E8 谓词形状：改名/大写/带版本后缀/前缀/空串/缺属性——真库一律不种，Mycat 亦不得种。 */
    @Test
    void predicateRejectsEveryNonExactShape() {
        ServerPersona p = ServerPersona.MARIADB_10_11;
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(attrs("_client_name", "MYSQL-CONNECTOR-NET"), p));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(attrs("_client_name", "MySQL Connector Net 8.0.30"), p));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(attrs("_client_name", "mysql-connector-net 8.0.30"), p));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(attrs("_client_name", "xmysql-connector-net"), p));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(attrs("_client_name", ""), p));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(attrs("_client_version", "8.0.30"), p));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(attrs("_client_name", "libmysql"), p));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(null, p));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(Collections.emptyMap(), p));
    }

    /** 身份门控 ≠ persona 无条件物化（spec §4 否决③）：非分档 persona 一律透传。 */
    @Test
    void predicateKeepsOtherPersonasOnTemplateView() {
        Map<String, String> dotnet = attrs(CollationMaterialization.CLIENT_NAME_ATTR,
                CollationMaterialization.CONNECTOR_NET);
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(dotnet, ServerPersona.MYSQL_5_7));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(dotnet, ServerPersona.MYSQL_8_0));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(dotnet, ServerPersona.MYSQL_8_4));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(dotnet, ServerPersona.MYSQL_9_X));
        Assertions.assertFalse(CollationMaterialization.shouldMaterialize(dotnet, ServerPersona.MARIADB_10_6));
    }

    /**
     * 后端门：persona 说是 MariaDB 还不够，借出的物理连接真得是 MariaDB ≥10.11（串档实况下不得乱设会话变量）。
     *
     * <p>假件默认形态即活体实测的 Connector/J：产品名一律谎报 "MySQL"，唯有版本串可信，且 MariaDB
     * 版本串对老客户端带 "5.5.5-" 兼容前缀 ⇒ 本锁同时钉死这两条（曾因采信产品名而活体不生效）。
     */
    @Test
    void backendGateRequiresRealMariadbAtLeast1011() {
        // 默认假件 = Connector/J 视角的 MariaDB 10.11（谎报 MySQL + 5.5.5- 前缀），须放行
        Assertions.assertTrue(CollationMaterialization.supportsOldModeSeal(new FakeBackendJdbc().connection()));

        FakeBackendJdbc unprefixed = new FakeBackendJdbc();
        unprefixed.version = "10.11.19-MariaDB-ubu2204";
        Assertions.assertTrue(CollationMaterialization.supportsOldModeSeal(unprefixed.connection()));

        FakeBackendJdbc mariadb114 = new FakeBackendJdbc();
        mariadb114.version = "5.5.5-11.4.2-MariaDB";
        Assertions.assertTrue(CollationMaterialization.supportsOldModeSeal(mariadb114.connection()));

        FakeBackendJdbc mariadb106 = new FakeBackendJdbc();
        mariadb106.version = "5.5.5-10.6.14-MariaDB";
        Assertions.assertFalse(CollationMaterialization.supportsOldModeSeal(mariadb106.connection()));

        FakeBackendJdbc mysql80 = new FakeBackendJdbc();
        mysql80.version = "8.0.34";
        Assertions.assertFalse(CollationMaterialization.supportsOldModeSeal(mysql80.connection()));

        FakeBackendJdbc mysql57 = new FakeBackendJdbc();
        mysql57.version = "5.7.33-log";
        Assertions.assertFalse(CollationMaterialization.supportsOldModeSeal(mysql57.connection()));
    }

    /** 取数四步序 + 快照原样搬运（列原列原序、值原样）。 */
    @Test
    void fetchRunsFourStepSequenceAndReturnsBackendRowsVerbatim() throws Exception {
        FakeBackendJdbc fake = new FakeBackendJdbc();
        fake.previousOldMode = "UTF8_IS_UTF8MB3";
        fake.showRows = Arrays.asList(
                new Object[]{"utf8mb3_general_ci", "utf8mb3", 11, "Yes", "Yes", 8},
                new Object[]{"uca1400_ai_ci", "utf8mb3", 2048, "Yes", "Yes", 8});

        CollationMaterialization.Snapshot snapshot =
                CollationMaterialization.fetchSnapshot(fake.connection(), SHOW);

        Assertions.assertNotNull(snapshot, "取数须产出快照");
        Assertions.assertEquals(Arrays.asList(
                "SELECT @@old_mode",
                "SET old_mode=CONCAT_WS(',',NULLIF(@@old_mode,''),'NO_NULL_COLLATION_IDS')",
                SHOW,
                "SET old_mode='UTF8_IS_UTF8MB3'"), fake.sqls, "四步序必须逐字命中");
        Assertions.assertEquals(6, snapshot.getColumns().size(), "列数照搬后端");
        // 两个名忠实分居：columnName = 后端物理列名(ID)，columnLabel = 显示名(Id)。
        // 上线取哪个由 Snapshot.toBuilder 决定，另由派发锁钉死（见 ShowCollationMaterializationDispatchTest）。
        Assertions.assertEquals("ID", snapshot.getColumns().get(2).columnName);
        Assertions.assertEquals("Id", snapshot.getColumns().get(2).columnLabel);
        Assertions.assertEquals(2, snapshot.getRows().size(), "行数照搬后端");
        Assertions.assertEquals(2048, snapshot.getRows().get(1)[2], "物化档 Id 为真值非 NULL");
    }

    /** E6 副作用锁：种位必须是加法 CONCAT_WS，不得冲掉既有 old_mode 值。 */
    @Test
    void sealIsAdditiveNotBare() throws Exception {
        FakeBackendJdbc fake = new FakeBackendJdbc();
        fake.showRows = Collections.singletonList(new Object[]{"a", "b", 1, "Yes", "Yes", 8});
        CollationMaterialization.fetchSnapshot(fake.connection(), SHOW);
        String seal = fake.sqls.get(1);
        Assertions.assertTrue(seal.contains("CONCAT_WS"), "必须加法式，实得 " + seal);
        Assertions.assertTrue(seal.contains("NO_NULL_COLLATION_IDS"), "必须含种位名");
    }

    /** 前值为空串的复原：SET old_mode=''（不得把本位留在会话上）。 */
    @Test
    void restoresEmptyPreviousValue() throws Exception {
        FakeBackendJdbc fake = new FakeBackendJdbc();
        fake.previousOldMode = "";
        fake.showRows = Collections.singletonList(new Object[]{"a", "b", 1, "Yes", "Yes", 8});
        CollationMaterialization.fetchSnapshot(fake.connection(), SHOW);
        Assertions.assertEquals("SET old_mode=''", fake.sqls.get(fake.sqls.size() - 1), "实得 SQL 序 " + fake.sqls);
    }

    /** 必复原：SHOW 中途抛错，复原语句仍须已发出，且如实返回 null 让调用方回落透传。 */
    @Test
    void restoresEvenWhenShowFailsAndYieldsNull() throws Exception {
        FakeBackendJdbc fake = new FakeBackendJdbc();
        fake.failOnShow = new SQLException("boom");

        CollationMaterialization.Snapshot snapshot =
                CollationMaterialization.fetchSnapshot(fake.connection(), SHOW);

        Assertions.assertNull(snapshot, "取数失败须如实返回 null（回落今日透传，绝不比现状更差）");
        Assertions.assertEquals(4, fake.sqls.size(), "四步一条不能少，实得 " + fake.sqls);
        Assertions.assertEquals("SET old_mode='UTF8_IS_UTF8MB3'", fake.sqls.get(3), "异常路径也须复原，实得 " + fake.sqls);
    }

    /** 读前值就失败：不得再种位、不得再复原（无从复原）。 */
    @Test
    void readFailureShortCircuitsWithoutTouchingOldMode() throws Exception {
        FakeBackendJdbc fake = new FakeBackendJdbc();
        fake.failOnRead = new SQLException("no old_mode");
        Assertions.assertNull(CollationMaterialization.fetchSnapshot(fake.connection(), SHOW));
        Assertions.assertEquals(Collections.singletonList("SELECT @@old_mode"), fake.sqls,
                "除读前值外不得有任何语句，实得 " + fake.sqls);
    }

    /** 种位本身失败：已读到的前值仍须复原回去（不欠会话态）。 */
    @Test
    void sealFailureStillRestores() throws Exception {
        FakeBackendJdbc fake = new FakeBackendJdbc();
        fake.failOnSeal = new SQLException("unknown variable");
        Assertions.assertNull(CollationMaterialization.fetchSnapshot(fake.connection(), SHOW));
        Assertions.assertEquals(3, fake.sqls.size(), "读前值 + 尝试种位 + 复原，实得 " + fake.sqls);
        Assertions.assertEquals("SET old_mode='UTF8_IS_UTF8MB3'", fake.sqls.get(2));
    }

    private static Map<String, String> attrs(String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
