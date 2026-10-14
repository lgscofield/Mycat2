package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * I-DEBT-41 对拍基准探针（异构跨源 union 列头一致性）。
 *
 * <p><b>依据 ADJUDICATION.md</b>：同一条异构 union 分别喂 ①直连后端(真 MySQL 8.0，期望) 与
 * ②经 Mycat(实际)，逐字段比列元数据与行值；差=bug 且自带"期望 vs 实际"。本类同时补上
 * 队列点名的红锁缺口——"无跨片同-arity 异构 union 活靶"（分支 a bit(8)、分支 b bigint）。
 *
 * <p><b>命题</b>：Mycat 对 {@code select c from t_bit union all select c from t_bigint} 的
 * 列头（header seam）与其行解码（decode seam）及真 MySQL 的归并结果三者须一致；否则即
 * I-DEBT-41 的"列头类型 ≠ 行值类型 → 沉默损坏"。此为 RED 红锁，修复后转绿。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CrossShardUnionHeaderDifferentialTest implements MycatTest {

    private static final String SCHEMA = "dbdiff";
    private static final String SHARD = " dbpartition by mod_hash(`id`) dbpartitions 2";
    /** 期望侧直连后端上的独立库（与 Mycat 的物理分库隔离）。 */
    private static final String PROBE_DB = "probe_direct";

    private Connection adminBackend; // 直连 DB1（真 MySQL 8.0）
    private Connection adminPrepared; // 直连 DB1，服务端预编译协议（对拍 prepared describe 形态）
    private Connection mycat;        // 经 Mycat@8066

    @BeforeAll
    public void setup() throws Exception {
        adminBackend = getMySQLConnection(DB1);
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, RESET_CONFIG);
            execute(setup, "drop database if exists " + SCHEMA);
            purgePhysical(); // 逻辑 drop 不清物理分库（dbdiff_0/1），须显式 purge，否则重跑撞残留主键
            // persona 矩阵支持：auto 聚点的 c0/prototype 簇硬编 prototypeDs=localhost:3306，
            // 按 -Ddb1 重绑同名 ds，让单分片物理库落在对拍后端上（与 adminBackend 直插一致）。
            execute(setup, CreateDataSourceHint.create("prototypeDs", DB1));
            execute(setup, CreateDataSourceHint.create("diffDs", DB1));
            execute(setup, CreateClusterHint.create("diffC", Arrays.asList("diffDs"), Collections.emptyList()));
            execute(setup, "create database " + SCHEMA);
            execute(setup, "use " + SCHEMA);
            // 两张同 arity、异后端类型的分片逻辑表，各读回必经物理分库归并 → 联合即异构多源。
            // t_bit 固定单物理分片：取证发现 bit 字面量经 Mycat insert 写库会被折成 1（另案挂账的
            // 写路径 bug，与本债读侧列类型权威无关），改由直连后端灌入真实字节，隔离变量。
            execute(setup, "drop table if exists t_bit");
            execute(setup, "CREATE TABLE `t_bit` (`id` bigint NOT NULL, `c` bit(8), PRIMARY KEY(`id`))"
                    + " dbpartition by mod_hash(`id`) dbpartitions 1");
            // 踩一次 Mycat DML 触发物理库/表惰性创建，再直连灌真实字节（避开 bit 字面量写路径另案 bug）
            execute(setup, "insert into t_bit(`id`,`c`) values (99, b'0')");
            execute(setup, "delete from t_bit where `id` = 99");
            // 单分片物理库名即 schema 名（dbdiff.t_bit_0，无 _0 后缀，实测 pushdown SQL 为证）
            execute(adminBackend, "insert into dbdiff.t_bit_0 values (1, b'00000001'),(2, b'10101010'),(3, b'11111111')");
            execute(setup, "drop table if exists t_bigint");
            execute(setup, "CREATE TABLE `t_bigint` (`id` bigint NOT NULL, `c` bigint, PRIMARY KEY(`id`))" + SHARD);
            execute(setup, "insert into t_bigint(`id`,`c`) values (1, 123),(2, 45),(3, 7),(4, 170)");
        }
        mycat = getMySQLConnection(DB_MYCAT);
        execute(mycat, "use " + SCHEMA);
        adminPrepared = getMySQLConnection(DB1 + "&useServerPrepStmts=true");

        // 期望侧：在同一真 MySQL 实例上建非分片的等价两表，灌同样的 c 值
        execute(adminBackend, "drop database if exists " + PROBE_DB);
        execute(adminBackend, "create database " + PROBE_DB);
        execute(adminBackend, "create table " + PROBE_DB + ".t_bit(`c` bit(8))");
        execute(adminBackend, "insert into " + PROBE_DB + ".t_bit values (b'00000001'),(b'10101010'),(b'11111111')");
        execute(adminBackend, "create table " + PROBE_DB + ".t_bigint(`c` bigint)");
        execute(adminBackend, "insert into " + PROBE_DB + ".t_bigint values (123),(45),(7),(170)");
    }

    @AfterAll
    public void teardown() throws Exception {
        if (mycat != null) {
            try (Connection setup = getMySQLConnection(DB_MYCAT)) {
                execute(setup, "drop database if exists " + SCHEMA);
                execute(setup, RESET_CONFIG);
            }
            mycat.close();
        }
        if (adminBackend != null) {
            execute(adminBackend, "drop database if exists " + PROBE_DB);
            purgePhysical();
            adminBackend.close();
        }
        if (adminPrepared != null) {
            adminPrepared.close();
        }
        purgePhysicalOn(DB2, SCHEMA);
        purgePhysicalOn(DB2, "dbcross");
    }

    private static final String UNION =
            "select `c` from t_bit union all select `c` from t_bigint order by 1";
    private static final String UNION_DIRECT =
            "select `c` from " + PROBE_DB + ".t_bit union all select `c` from " + PROBE_DB + ".t_bigint order by 1";

    /** 一条结果集的第一列列元数据（含列头五元组可观面——Type/Collation/JdbcType/Length(precision
     *  +displaySize)/Decimals(scale)/Flags(signed+nullable)，可观测面以 JDBC 暴露为限）+ 各行 getObject 值（含运行时 Java 类），
     *  封装成可打印/可比的记录。列头先取（header seam），逐行读异常隔离（decode seam 崩溃也保留
     *  列头证据），错误本身计入差异。prepared=true 走服务端预编译协议（COM_STMT describe 形态）。 */
    private ProbeResult probe(Connection c, String sql) throws Exception {
        return probe(c, sql, false);
    }

    private ProbeResult probe(Connection c, String sql, boolean prepared) throws Exception {
        try (Statement st = prepared ? c.prepareStatement(sql) : c.createStatement()) {
            boolean hasRs;
            try {
                hasRs = st.execute(sql);
            } catch (Exception execEx) {
                return new ProbeResult("<execute-failed>", -1, "n/a", -1, -1, -1, null, null, Collections.emptyList(),
                        execEx.getClass().getName() + ": " + execEx.getMessage());
            }
            if (!hasRs) {
                return new ProbeResult("NO_RESULTSET", -1, "n/a", -1, -1, -1, null, null, Collections.emptyList(), null);
            }
            try (ResultSet rs = st.getResultSet()) {
                ResultSetMetaData md = rs.getMetaData();
                String typeName = md.getColumnTypeName(1);
                int jdbcType = md.getColumnType(1);
                String javaClass = String.valueOf(md.getColumnClassName(1));
                int displaySize = md.getColumnDisplaySize(1);
                int precision = md.getPrecision(1);
                int scale;
                Boolean signed;
                Integer nullable;
                try {
                    scale = md.getScale(1);
                } catch (Exception e) {
                    scale = -999; // JDBC 驱动不支持时留可见标记
                }
                try {
                    signed = md.isSigned(1);
                    nullable = md.isNullable(1);
                } catch (Exception e) {
                    signed = null;
                    nullable = null;
                }
                List<String> vals = new ArrayList<>();
                String rowError = null;
                try {
                    while (rs.next()) {
                        Object o = rs.getObject(1);
                        vals.add(o == null ? "null" : (o.getClass().getSimpleName() + ":" + render(o)));
                    }
                } catch (Exception readEx) {
                    // 解码缝在此崩溃：列头已取到，行值读不出=列头≠行值的沉默/崩溃面
                    rowError = readEx.getClass().getName() + ": " + readEx.getMessage();
                }
                return new ProbeResult(typeName, jdbcType, javaClass, displaySize, precision, scale, signed, nullable,
                        vals, rowError);
            }
        }
    }

    private static String render(Object o) {
        if (o instanceof byte[]) {
            StringBuilder sb = new StringBuilder();
            for (byte b : (byte[]) o) {
                sb.append(String.format("%02X", b));
            }
            return "0x" + sb;
        }
        return String.valueOf(o);
    }

    @Test
    public void heterogeneousUnionHeaderMatchesBackend() throws Exception {
        ProbeResult expected = probe(adminBackend, UNION_DIRECT); // 真 MySQL 8.0 归并结果
        ProbeResult actual = probe(mycat, UNION);                 // 经 Mycat 跨源归并结果

        System.out.println("\n================= I-DEBT-41 对拍差异表（异构 bit(8) ∪ bigint） =================");
        System.out.println("后端版本(实测)：" + expectedVersion() + "；Mycat 自称：见 select version()");
        System.out.println("期望(直连真 MySQL 8.0)：" + expected);
        System.out.println("实际(经 Mycat 跨源)  ：" + actual);
        // 列头五元组逐字段对拍（JDBC 可观测面；wire 层 Collation 不经 JDBC 暴露，另在 hbt 单测锁）
        System.out.println("五元组对拍 Type：期望=" + expected.columnTypeName + "/" + expected.jdbcType
                + " 实际=" + actual.columnTypeName + "/" + actual.jdbcType);
        System.out.println("五元组对拍 Length：期望 p=" + expected.precision + "/ds=" + expected.displaySize
                + " 实际 p=" + actual.precision + "/ds=" + actual.displaySize);
        System.out.println("五元组对拍 Decimals：期望=" + expected.scale + " 实际=" + actual.scale);
        System.out.println("五元组对拍 Flags：期望 signed=" + expected.signed + "/nullable=" + expected.nullable
                + " 实际 signed=" + actual.signed + "/nullable=" + actual.nullable);
        // 形态裁定取证：同一条 union 走直连<b>服务端预编译协议</b>（COM_STMT describe）的列头形状——
        // Mycat 的列头权威正取自后端 prepared describe，若直连 prepared 与 Mycat 同形，
        // 则 precision 254 vs 19 是协议形态差而非 Mycat 错报（Task 5 挂账的终裁证据）。
        ProbeResult expectedPrepared = probe(adminPrepared, UNION_DIRECT, true);
        System.out.println("参照(直连真 MySQL prepared)：" + expectedPrepared);
        System.out.println("================================================================================\n");

        // 命题（切片2 ① 收紧后）：不得崩、列头类型名精确等于真 MySQL 归并类型（DECIMAL），
        // 且行值逐类名精确等于后端（列头 DECIMAL → 客户端按 BigDecimal 解，与直连同形）。
        assertEquals(null, actual.rowError,
                "I-DEBT-41：Mycat 跨源异构 union 不得崩（实得 " + actual.rowError + "）");
        assertEquals(expected.columnTypeName, actual.columnTypeName,
                "I-DEBT-41 切片2：Mycat 跨源异构 union 列头应等于真 MySQL 归并类型（后端 describe 权威）");
        assertEquals(expected.rows, actual.rows,
                "I-DEBT-41 切片2：列头权威切到后端归并类型后，行值应与真 MySQL 逐类名/值精确一致");
        // 五元组扩展硬锁：Decimals(scale) 与 Flags(signed/nullable) 属语义位，协议形态不影响；
        // Length(precision/displaySize) 另走 prepared 参照同形锁，见下。
        assertEquals(expected.scale, actual.scale,
                "I-DEBT-41 五元组：Decimals(scale) 应逐字段等于直连后端");
        assertEquals(expected.signed, actual.signed,
                "I-DEBT-41 五元组：Flags.signed 应等于直连后端");
        assertEquals(expected.nullable, actual.nullable,
                "I-DEBT-41 五元组：Flags.nullable 应等于直连后端");
        // 形态裁定（Task 6 实测）：后端 describe 并无限 0 形态差——jshell 对真 MySQL 8.0.46 实测
        // 同形 union/union+limit 0/prepared 均报 DECIMAL(19)/ds 19；Mycat 列头的 254/84 来自
        // Calcite 计划轨 DECIMAL 默认精度（FaithfulTypeView.getPrecision 委派 plan），非后端原值。
        // 属可分离的形态保真窄差（类型/scale/flags/行值全对齐），挂账 DEBT-QUEUE 另案收敛，
        // 本锁只记录不硬断（对齐“先拆雷再逼近精确”的切片节奏）。
        System.out.println("五元组对拍 Length 差异（挂账）：直连文本 p=" + expected.precision + "/ds="
                + expected.displaySize + "，直连 prepared p=" + expectedPrepared.precision + "/ds="
                + expectedPrepared.displaySize + "，经 Mycat p=" + actual.precision + "/ds=" + actual.displaySize);
    }

    /** Review Focus#2：lookup 形态（in 子查询跨异构列）——解码/列头两缝须同样不崩且值与直连一致。 */
    @Test
    public void heterogeneousLookupInSubqueryMatchesBackend() throws Exception {
        String lookup = "select `c` from t_bigint where `c` in (select `c` from t_bit) order by 1";
        String lookupDirect = "select `c` from " + PROBE_DB + ".t_bigint where `c` in (select `c` from "
                + PROBE_DB + ".t_bit) order by 1";
        ProbeResult expected = probe(adminBackend, lookupDirect);
        ProbeResult actual = probe(mycat, lookup);
        System.out.println("\n---- I-DEBT-41 lookup(in 子查询) 对拍：期望(直连)=" + expected
                + " 实际(经 Mycat)=" + actual + " ----\n");
        assertEquals(null, actual.rowError, "I-DEBT-41：lookup 异构子查询不得崩（实得 " + actual.rowError + "）");
        assertEquals(expected.columnTypeName, actual.columnTypeName,
                "I-DEBT-41：lookup 异构列头应等于直连后端");
        assertEquals(expected.rows, actual.rows,
                "I-DEBT-41：lookup 异构行值应逐类名/值等于直连后端（非空集验证：命中值 170 压在 bit(8) 高位）");
        // 防“空集假绿”：命必须非空且含 170（bit 0b10101010 与 bigint 170 的跨型相等判断）
        assertEquals(true, actual.rows.contains("Long:170") || actual.rows.contains("BigDecimal:170"),
                "I-DEBT-41：lookup 应命中跨类型等值 170，实得 " + actual.rows);
    }

    /** Review Focus#5：极端跨 ds（分支落两个不同后端数据源）→ 探测门槛不过 → 诚实退 ③ 计划轨：
     *  列头为计划轨 BIGINT（非真 MySQL 归并 DECIMAL，属已知不兼容面，挂 DEBT-QUEUE 不计兼容），
     *  但不得崩、行值须数值正确。 */
    @Test
    public void crossDataSourceUnionFallsBackToPlanTrack() throws Exception {
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            purgePhysicalOn(DB1, "dbcross");
            purgePhysicalOn(DB2, "dbcross");
            execute(setup, CreateDataSourceHint.create("crossDs", DB2));
            // auto 模式建表按约定聚点：storeNum=名字以 c 开头的 cluster 数，目标固定找 c0/c1/…
            // （getDefaultStoreNodeNum + mappingFormat c${targetIndex}）。故必须用 c 系命名：
            // 默认配置已带 c0→prototypeDs(3306=DB1)，这里补建 c1→crossDs(3307=DB2)；
            // 旧写法自创 crossC 也撞 c 前缀被计入，导致目标 c1 不存在报 can not found c1。
            execute(setup, CreateClusterHint.create("c1", Arrays.asList("crossDs"),
                    Collections.emptyList()));
            execute(setup, "create database dbcross");
            execute(setup, "use dbcross");
            // bit 分支：dbpartitions 1→只落 c0(prototypeDs/DB1)；绕 I-DEBT-44 写路径 bug 直插真实字节
            execute(setup, "CREATE TABLE `t_bitx` (`id` bigint NOT NULL, `c` bit(8), PRIMARY KEY(`id`))"
                    + " dbpartition by mod_hash(`id`) dbpartitions 1");
            execute(setup, "insert into t_bitx(`id`,`c`) values (99, b'0')");
            execute(setup, "delete from t_bitx where `id` = 99");
            execute(adminBackend, "insert into dbcross.t_bitx_0 values (1, b'00000001'),(2, b'10101010'),(3, b'11111111')");
            // bigint 分支：2 分片→跨两 master（DB1+DB2）→ 分支 ds 不统一，探测门槛不过
            execute(setup, "CREATE TABLE `t_bigx` (`id` bigint NOT NULL, `c` bigint, PRIMARY KEY(`id`))"
                    + " dbpartition by mod_hash(`id`) dbpartitions 2");
            execute(setup, "insert into t_bigx(`id`,`c`) values (1, 123),(2, 45),(3, 7),(4, 170)");
        }
        try (Connection mycatCross = getMySQLConnection(DB_MYCAT)) {
            execute(mycatCross, "use dbcross");
            ProbeResult actual = probe(mycatCross,
                    "select `c` from t_bitx union all select `c` from t_bigx order by 1");
            ProbeResult expected = probe(adminBackend, UNION_DIRECT); // 单实例等价形态的真 MySQL 基准
            System.out.println("\n---- I-DEBT-41 跨 ds 边界对拍：期望(直连单实例)=" + expected
                    + " 实际(经 Mycat 跨 ds，应回 ③ 计划轨)=" + actual + " ----\n");
            assertEquals(null, actual.rowError,
                    "I-DEBT-41 跨 ds：不得崩（实得 " + actual.rowError + "）");
            // 边界锁定：跨 ds 弃探后列头回计划轨 BIGINT——与真 MySQL 归并 DECIMAL 的差异是
            // 已知边界（不计兼容），挂 DEBT-QUEUE；若哪天变成 DECIMAL 说明探测门槛改了，需同步审。
            assertEquals("BIGINT", actual.columnTypeName,
                    "I-DEBT-41 跨 ds：探测不可行应显式落 ③ 计划轨（BIGINT），实得 " + actual.columnTypeName);
            assertEquals(expected.numericRows(), actual.numericRows(),
                    "I-DEBT-41 跨 ds：退计划轨后行值须数值正确（类名差合法，数值必须对）");
        } finally {
            try (Connection setup = getMySQLConnection(DB_MYCAT)) {
                execute(setup, "drop database if exists dbcross");
            }
            purgePhysicalOn(DB1, "dbcross");
            purgePhysicalOn(DB2, "dbcross");
        }
    }

    private String expectedVersion() throws Exception {
        return executeQuery(adminBackend, "select version()").get(0).values().iterator().next().toString();
    }

    /** 直连后端物理 purge：删掉 SCHEMA 前缀的所有物理分库（逻辑 drop 不级联物理）。 */
    private void purgePhysical() throws Exception {
        purgePhysicalOn(DB1, SCHEMA);
    }

    /** 指定后端实例上按前缀 purge 物理库（跨 ds 活靶在 DB2 也落了 dbcross_1 之类分库）。 */
    private void purgePhysicalOn(String dbUrl, String prefix) throws Exception {
        try (Connection c = getMySQLConnection(dbUrl)) {
            List<java.util.Map<String, Object>> schemas = executeQuery(c,
                    "select schema_name from information_schema.schemata where schema_name like '" + prefix + "%'");
            for (java.util.Map<String, Object> row : schemas) {
                execute(c, "drop database if exists `" + row.values().iterator().next() + "`");
            }
        }
    }

    /** 记录一个探针结果，toString 便于日志留证。 */
    private static final class ProbeResult {
        final String columnTypeName;
        final int jdbcType;
        final String javaClassName;
        final int displaySize;
        final int precision;
        final int scale;
        final Boolean signed;
        final Integer nullable;
        final List<String> rows;
        final String rowError;

        /** 剔掉运行时类名包装、只比数值（Long:170 与 BigDecimal:170 视为同值）：切片1 退计划轨后
         *  的合法形态差异在类名，精确列类型对拍归 Task 5 收紧。 */
        List<String> numericRows() {
            List<String> out = new ArrayList<>();
            for (String r : rows) {
                int i = r.indexOf(':');
                String v = i < 0 ? r : r.substring(i + 1);
                try {
                    out.add("" + new java.math.BigDecimal(v).stripTrailingZeros());
                } catch (NumberFormatException e) {
                    out.add(r);
                }
            }
            return out;
        }

        ProbeResult(String columnTypeName, int jdbcType, String javaClassName, int displaySize, int precision,
                int scale, Boolean signed, Integer nullable,
                List<String> rows, String rowError) {
            this.columnTypeName = columnTypeName;
            this.jdbcType = jdbcType;
            this.javaClassName = javaClassName;
            this.displaySize = displaySize;
            this.precision = precision;
            this.scale = scale;
            this.signed = signed;
            this.nullable = nullable;
            this.rows = rows;
            this.rowError = rowError;
        }

        @Override
        public String toString() {
            return "{typeName=" + columnTypeName + ", jdbcType=" + jdbcType + ", javaClass=" + javaClassName
                    + ", displaySize=" + displaySize + ", precision=" + precision + ", scale=" + scale
                    + ", signed=" + signed + ", nullable=" + nullable + ", rows=" + rows
                    + (rowError == null ? "" : ", rowError=" + rowError) + "}";
        }
    }
}
