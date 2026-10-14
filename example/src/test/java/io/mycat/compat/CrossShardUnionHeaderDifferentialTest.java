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
    private Connection mycat;        // 经 Mycat@8066

    @BeforeAll
    public void setup() throws Exception {
        adminBackend = getMySQLConnection(DB1);
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, RESET_CONFIG);
            execute(setup, "drop database if exists " + SCHEMA);
            purgePhysical(); // 逻辑 drop 不清物理分库（dbdiff_0/1），须显式 purge，否则重跑撞残留主键
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
            execute(setup, "insert into t_bigint(`id`,`c`) values (1, 123),(2, 45),(3, 7)");
            // 临时取证（I-DEBT-41，合入前删）：确认直插物理字节落库无误
            System.out.println("PHYS-HEX-0:" + executeQuery(adminBackend,
                    "select id, hex(c) hx from dbdiff.t_bit_0"));
        }
        mycat = getMySQLConnection(DB_MYCAT);
        execute(mycat, "use " + SCHEMA);

        // 期望侧：在同一真 MySQL 实例上建非分片的等价两表，灌同样的 c 值
        execute(adminBackend, "drop database if exists " + PROBE_DB);
        execute(adminBackend, "create database " + PROBE_DB);
        execute(adminBackend, "create table " + PROBE_DB + ".t_bit(`c` bit(8))");
        execute(adminBackend, "insert into " + PROBE_DB + ".t_bit values (b'00000001'),(b'10101010'),(b'11111111')");
        execute(adminBackend, "create table " + PROBE_DB + ".t_bigint(`c` bigint)");
        execute(adminBackend, "insert into " + PROBE_DB + ".t_bigint values (123),(45),(7)");
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
    }

    private static final String UNION =
            "select `c` from t_bit union all select `c` from t_bigint order by 1";
    private static final String UNION_DIRECT =
            "select `c` from " + PROBE_DB + ".t_bit union all select `c` from " + PROBE_DB + ".t_bigint order by 1";

    /** 一条结果集的第一列列元数据 + 各行 getObject 值（含运行时 Java 类），封装成可打印/可比的记录。
     *  列头先取（header seam），逐行读异常隔离（decode seam 崩溃也保留列头证据），错误本身计入差异。 */
    private ProbeResult probe(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            boolean hasRs;
            try {
                hasRs = st.execute(sql);
            } catch (Exception execEx) {
                return new ProbeResult("<execute-failed>", -1, "n/a", -1, -1, Collections.emptyList(),
                        execEx.getClass().getName() + ": " + execEx.getMessage());
            }
            if (!hasRs) {
                return new ProbeResult("NO_RESULTSET", -1, "n/a", -1, -1, Collections.emptyList(), null);
            }
            try (ResultSet rs = st.getResultSet()) {
                ResultSetMetaData md = rs.getMetaData();
                String typeName = md.getColumnTypeName(1);
                int jdbcType = md.getColumnType(1);
                String javaClass = String.valueOf(md.getColumnClassName(1));
                int displaySize = md.getColumnDisplaySize(1);
                int precision = md.getPrecision(1);
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
                return new ProbeResult(typeName, jdbcType, javaClass, displaySize, precision, vals, rowError);
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
        System.out.println("================================================================================\n");

        // 命题（切片2 ① 收紧后）：不得崩、列头类型名精确等于真 MySQL 归并类型（DECIMAL），
        // 且行值逐类名精确等于后端（列头 DECIMAL → 客户端按 BigDecimal 解，与直连同形）。
        assertEquals(null, actual.rowError,
                "I-DEBT-41：Mycat 跨源异构 union 不得崩（实得 " + actual.rowError + "）");
        assertEquals(expected.columnTypeName, actual.columnTypeName,
                "I-DEBT-41 切片2：Mycat 跨源异构 union 列头应等于真 MySQL 归并类型（后端 describe 权威）");
        assertEquals(expected.rows, actual.rows,
                "I-DEBT-41 切片2：列头权威切到后端归并类型后，行值应与真 MySQL 逐类名/值精确一致");
    }

    private String expectedVersion() throws Exception {
        return executeQuery(adminBackend, "select version()").get(0).values().iterator().next().toString();
    }

    /** 直连后端物理 purge：删掉 SCHEMA 前缀的所有物理分库（逻辑 drop 不级联物理）。 */
    private void purgePhysical() throws Exception {
        List<java.util.Map<String, Object>> schemas = executeQuery(adminBackend,
                "select schema_name from information_schema.schemata where schema_name like '" + SCHEMA + "%'");
        for (java.util.Map<String, Object> row : schemas) {
            execute(adminBackend, "drop database if exists `" + row.values().iterator().next() + "`");
        }
    }

    /** 记录一个探针结果，toString 便于日志留证。 */
    private static final class ProbeResult {
        final String columnTypeName;
        final int jdbcType;
        final String javaClassName;
        final int displaySize;
        final int precision;
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
                List<String> rows, String rowError) {
            this.columnTypeName = columnTypeName;
            this.jdbcType = jdbcType;
            this.javaClassName = javaClassName;
            this.displaySize = displaySize;
            this.precision = precision;
            this.rows = rows;
            this.rowError = rowError;
        }

        @Override
        public String toString() {
            return "{typeName=" + columnTypeName + ", jdbcType=" + jdbcType + ", javaClass=" + javaClassName
                    + ", displaySize=" + displaySize + ", precision=" + precision + ", rows=" + rows
                    + (rowError == null ? "" : ", rowError=" + rowError) + "}";
        }
    }
}
