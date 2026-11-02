package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-69 Blade D 余项 · 跨片 SELECT 告警回传行为锁（活体差分对拍，2026-11-02）。
 *
 * <p><b>命题</b>：跨片全扫描计算投影 {@code select cast(note as signed) from <2分片表>}（无分片键 →
 * 必经 Calcite merge 路径 {@code AsyncMycatDataContextImpl.SqlMycatDataContextImpl.getObservableList}
 * 逐 fragment 下推）时，后端 MySQL 对非数值串逐行产 <b>Warning 1292（Truncated incorrect）</b>；这些后端
 * 诊断条件必须汇入<b>前端会话诊断区（DA）</b>，令紧随其后的 {@code SELECT @@warning_count} / {@code SHOW WARNINGS}
 * 与直连真库 oracle 逐格一致。
 *
 * <p><b>RED 依据（当前代码 file:line，见 spec
 * {@code 2026-10-29-warning-subsystem-design.md} §6f）</b>：该路径调 6 参 {@code VertxExecuter.runQuery}
 * （{@code AsyncMycatDataContextImpl.java:376}），其委托 7 参重载时末位传 <b>{@code warnings -> {}} 空实现</b>
 * （{@code VertxExecuter.java:973-980}），故 collector 的 {@code onWarnings} 被静默丢弃 → 跨片 {@code @@warning_count}=0，
 * 而单片命中（原文本直通 {@code ReceiverImpl.java:208} 已接 sink）可见 1292。本锁即钉此分叉。
 *
 * <p><b>与 I-DEBT-76 关系</b>：76 已把 CAST 下推回后端算（值+类型保真达成、后端确有 1292 可采），
 * 本锁据此前提断诊断回传——修 sink 断链即转绿。
 *
 * <p><b>行序诚实边界</b>：并发 merge 下跨片告警全局次序不保证，本锁只比<b>计数与错误码集合</b>，不比精确行序。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CrossShardWarningRelayTest implements MycatTest {

    private static final String SCHEMA = "dbwarnrelay";
    /** oracle 独立非分片库（直连真库，与 Mycat 物理分库隔离）。 */
    private static final String PROBE_DB = "probe_warnrelay";
    private static final String SHARD = " dbpartition by mod_hash(`id`) dbpartitions 2";
    private static final int EXPECT_TRUNC_WARNINGS = 2; // 'abc' + 'xyz' 各产一条 1292；'123' 干净

    private Connection adminBackend; // 直连 DB1（真 MySQL 8.0）
    private Connection mycat;        // 经 Mycat@8066

    @BeforeAll
    public void setup() throws Exception {
        adminBackend = getMySQLConnection(DB1);
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, RESET_CONFIG);
            execute(setup, "drop database if exists " + SCHEMA);
            purgePhysical();
            // auto 聚点 c0/prototype 簇硬编 prototypeDs，按 -Ddb1 重绑，让物理分库落对拍后端（产真 1292）。
            addC0(setup);
            execute(setup, "create database " + SCHEMA);
            execute(setup, "use " + SCHEMA);
            execute(setup, "drop table if exists shg");
            execute(setup, "CREATE TABLE `shg` (`id` bigint NOT NULL, `note` varchar(255), PRIMARY KEY(`id`))" + SHARD);
            execute(setup, "insert into shg(`id`,`note`) values (1,'abc'),(2,'xyz'),(3,'123')");
        }
        mycat = getMySQLConnection(DB_MYCAT);
        execute(mycat, "use " + SCHEMA);

        // oracle：同一真库建等价非分片表，灌同数据，供"期望告警数"基准。
        execute(adminBackend, "drop database if exists " + PROBE_DB);
        execute(adminBackend, "create database " + PROBE_DB);
        execute(adminBackend, "drop table if exists " + PROBE_DB + ".shg");
        execute(adminBackend, "CREATE TABLE " + PROBE_DB + ".shg (`id` bigint NOT NULL, `note` varchar(255), PRIMARY KEY(`id`))");
        execute(adminBackend, "insert into " + PROBE_DB + ".shg values (1,'abc'),(2,'xyz'),(3,'123')");
    }

    @AfterAll
    public void teardown() throws Exception {
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, "drop database if exists " + SCHEMA);
            execute(setup, RESET_CONFIG);
        }
        execute(adminBackend, "drop database if exists " + PROBE_DB);
        if (adminBackend != null) {
            adminBackend.close();
        }
        if (mycat != null) {
            mycat.close();
        }
    }

    /**
     * 主命题：跨片全扫描 cast 的后端 1292 必须汇入会话 DA，{@code @@warning_count} 与直连 oracle 逐格一致。
     */
    @Test
    public void crossShardSelectWarningsReachSessionDA() throws Exception {
        int oracleWc = oracleWarningCount(adminBackend,
                "select cast(note as signed) from " + PROBE_DB + ".shg order by id");
        assertEquals(EXPECT_TRUNC_WARNINGS, oracleWc,
                "I-DEBT-69 前置：oracle 直连真库跨全表 cast 应产 2 条 1292（'abc'/'xyz'），实得 " + oracleWc);

        // 经 Mycat 跨片全扫描（无分片键 → Calcite merge 路径），随后同会话读上一条语句诊断计数。
        executeQuery(mycat, "select cast(note as signed) as n from shg order by id");
        int mycatWc = scalarWarningCount(mycat, "select @@warning_count");

        assertEquals(oracleWc, mycatWc,
                "I-DEBT-69：跨片全扫描 cast 的后端截断告警须汇入会话 DA；当前 Calcite 路 warningSink 是 no-op "
                        + "致 @@warning_count=0（单片直通有 1292）。oracle=" + oracleWc + " mycat=" + mycatWc);
    }

    /**
     * 明细面：跨片全扫描后 {@code SHOW WARNINGS} 须含 1292 行（与 oracle 码集合一致）。
     * {@code SHOW WARNINGS} 属读回型诊断语句、自身不清诊断区，故须在 cast 之后、任何普通语句之前读。
     */
    @Test
    public void crossShardShowWarningsContainsTruncation() throws Exception {
        executeQuery(mycat, "select cast(note as signed) as n from shg order by id");
        List<Map<String, Object>> warns = executeQuery(mycat, "show warnings");
        java.util.Set<String> mycatCodes = codes(warns);
        assertTrue(!mycatCodes.isEmpty(),
                "I-DEBT-69：跨片 cast 后 SHOW WARNINGS 不应为空，实得 " + warns);
        assertTrue(mycatCodes.contains("1292"),
                "I-DEBT-69：跨片 cast 后 SHOW WARNINGS 须含错误码 1292，实得码集合 " + mycatCodes);
    }

    // ---------------------------------------------------------------- helpers

    /** 直连 oracle：跑给定 select 后同会话读上一条语句的 @@warning_count。 */
    private int oracleWarningCount(Connection c, String selectSql) throws Exception {
        // 先清诊断区（一条无害语句），再跑目标 select，再读，确保只计本次。
        executeQuery(c, "select 1");
        executeQuery(c, selectSql);
        return scalarWarningCount(c, "select @@warning_count");
    }

    private int scalarWarningCount(Connection c, String sql) throws Exception {
        List<Map<String, Object>> rows = executeQuery(c, sql);
        Object v = rows.get(0).values().iterator().next();
        // 真库 @@warning_count 回数值列；经 Mycat 变量服务可能以字符串下发（属另一表征面，非本债），此处兼容取数。
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return Integer.parseInt(String.valueOf(v).trim());
    }

    /** SHOW WARNINGS 结果集里取 Code 列（列名大小写随版本，取第二列稳健）。 */
    private java.util.Set<String> codes(List<Map<String, Object>> warns) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (Map<String, Object> row : warns) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String k = e.getKey().toLowerCase();
                if (k.contains("code")) {
                    set.add(String.valueOf(e.getValue()));
                }
            }
        }
        return set;
    }

    private void purgePhysical() throws Exception {
        List<Map<String, Object>> schemas = executeQuery(adminBackend,
                "select schema_name from information_schema.schemata where schema_name like '" + SCHEMA + "%'");
        for (Map<String, Object> row : schemas) {
            execute(adminBackend, "drop database if exists `" + row.values().iterator().next() + "`");
        }
    }
}
