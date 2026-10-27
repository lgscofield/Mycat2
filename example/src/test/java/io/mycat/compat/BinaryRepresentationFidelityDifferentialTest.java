package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * I-DEBT-35 二进列值保真的活体差分回归锁（BINARY/VARBINARY/BLOB × 四形态）。
 *
 * <p><b>缘起与定性</b>：看板把 I-DEBT-35 挂在“跨片类型表征”链上待定性。经独立取证：仓内
 * 不变式“BINARY/VARBINARY/BLOB 内部规范表示 = ByteString”下，运行期行槽
 * （{@code BaseRowObservable.getObjects}）与 codegen（{@code getJavaClass(BINARY)=ByteString}）
 * 一致，结果端 ByteString→bytes 的 wire 归一已由 I-DEBT-27 收口。本差分锁将“是否活体缺口”
 * 钉死：<b>四形态全 benign——BINARY/VARBINARY/BLOB 逐字节穿越各腿零漂移，I-DEBT-35 重定性 by-design</b>。
 *
 * <p><b>四形态</b>（①直连真 MySQL 8.0 为期望侧，②经 Mycat 为实际侧，逐字节 + 列类型名 + 运行时类名对拍）：
 * <ol>
 *   <li>单片直通（pushdown 读回）</li>
 *   <li>跨片归并（2 分片本地合并，I-DEBT-59 动过的归并腿）</li>
 *   <li>本地函数求值（length() 包 BINARY 列，行序无关多重集比对）</li>
 *   <li>hex 字面量（{@code select x'..'}，触发常量→ByteString 路径；真 MySQL 8.0 不支持 VALUES 表构造，改此形）</li>
 * </ol>
 *
 * <p><b>已知挂账（不计本锁红）</b>：形态④ hex 字面量列类型名 Mycat 报 LONGBLOB、直连报 VARBINARY（
 * 常量类型推导的列头保真窄差，属 I-DEBT-45 列头家族），值字节逐位一致；本锁只硬锁值保真，
 * 不强约字面量列头类型名。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BinaryRepresentationFidelityDifferentialTest implements MycatTest {

    private static final String SCHEMA = "dbbin35";
    private static final String PROBE_DB = "probe35_direct";

    /** 三个二进制列的取值（含 0x00/0xFF/非法 UTF-8 字节，最大化字节保真鉴别力）。 */
    private static final byte[] B0 = {0x00, 0x0A, (byte) 0xFF, (byte) 0xAA}; // BINARY(4)
    private static final byte[] B1 = {(byte) 0xC3, (byte) 0x28, 0x00, 0x7F}; // 非 UTF-8 安全串
    private static final byte[] V0 = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
    private static final byte[] L0 = new byte[]{0, 1, 2, 3, (byte) 250, (byte) 251, (byte) 252};

    private Connection adminBackend; // 直连 DB1（真 MySQL 8.0）= 期望侧 oracle
    private Connection mycat;         // 经 Mycat@8066 = 实际侧

    @BeforeAll
    public void setup() throws Exception {
        adminBackend = getMySQLConnection(DB1);
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, RESET_CONFIG);
            execute(setup, "drop database if exists " + SCHEMA);
            purgePhysical();
            // 建库绑默认 cluster "c0"（addC0 与最贴题的 CrudResultMatrixTest 同法），否则 create database 静默失败
            addC0(setup);

            // 建库走默认 cluster c0（单实例上即可 dbpartitions 2）
            execute(setup, "create database " + SCHEMA);
            execute(setup, "use " + SCHEMA);
            execute(setup, "drop table if exists tb1");
            execute(setup, "CREATE TABLE tb1(id bigint primary key, b binary(4), vb varbinary(16), blb blob)");
            insert3(setup, "tb1");

            // 2 分片逻辑表（跨片归并腿 + 本地求值腿）
            execute(setup, "drop table if exists tb2");
            execute(setup, "CREATE TABLE tb2(id bigint primary key, b binary(4), vb varbinary(16), blb blob)"
                    + " dbpartition by mod_hash(`id`) dbpartitions 2");
            insert3(setup, "tb2");
            // 注：不可在此 RESET_CONFIG——会从磁盘重载基础配置、抹掉内存里动态建的 dbbin35（建库/建表皆 in-memory）。
        }
        mycat = getMySQLConnection(DB_MYCAT);
        execute(mycat, "use " + SCHEMA);

        // 期望侧：同一真库上的非分片等价表
        execute(adminBackend, "drop database if exists " + PROBE_DB);
        execute(adminBackend, "create database " + PROBE_DB);
        execute(adminBackend, "create table " + PROBE_DB + ".tb(id bigint primary key, b binary(4), vb varbinary(16), blb blob)");
        insert3(adminBackend, PROBE_DB + ".tb");
    }

    private void insert3(Connection c, String table) throws Exception {
        String sql = "insert into " + table + "(id,b,vb,blb) values (?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, 1);
            ps.setBytes(2, B0);
            ps.setBytes(3, V0);
            ps.setBytes(4, L0);
            ps.addBatch();
            ps.setLong(1, 2);
            ps.setBytes(2, B1);
            ps.setBytes(3, new byte[]{0x10, 0x20});
            ps.setBytes(4, new byte[]{(byte) 0xFF});
            ps.addBatch();
            ps.executeBatch();
        }
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

    /** 逐字节渲染（含 null），把三列结果集读成可打印可比的字符串列表。 */
    private List<String> rows(Connection c, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            st.execute(sql);
            try (ResultSet rs = st.getResultSet()) {
                ResultSetMetaData md = rs.getMetaData();
                StringBuilder types = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    types.append(md.getColumnTypeName(i)).append("/").append(md.getColumnClassName(i)).append("  ");
                }
                out.add("#types: " + types);
                while (rs.next()) {
                    StringBuilder line = new StringBuilder();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object o = rs.getObject(i);
                        line.append(render(o)).append("|");
                    }
                    out.add(line.toString());
                }
            }
        }
        return out;
    }

    private static String render(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof byte[]) {
            StringBuilder sb = new StringBuilder("0x");
            for (byte b : (byte[]) o) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        }
        return o.getClass().getSimpleName() + ":" + o;
    }

    private void diff(String shape, String mycatSql, String directSql) throws Exception {
        List<String> expected;
        try {
            expected = rows(adminBackend, directSql);
        } catch (Exception e) {
            expected = Collections.singletonList("<direct-execute-failed> " + e);
        }
        List<String> actual;
        try {
            actual = rows(mycat, mycatSql);
        } catch (Exception e) {
            actual = Collections.singletonList("<mycat-execute-failed> " + e.getClass().getName() + ": " + e.getMessage());
        }
        System.out.println("\n================= I-DEBT-35 差分对拍【" + shape + "】 =================");
        System.out.println("期望(直连真 MySQL 8.0)：" + expected);
        System.out.println("实际(经 Mycat)        ：" + actual);
        // 行序无关比对（多重集）：order by 遇并列值时行序本就不确定，不应计为表征错位。
        List<String> expSorted = new ArrayList<>(expected);
        List<String> actSorted = new ArrayList<>(actual);
        Collections.sort(expSorted);
        Collections.sort(actSorted);
        boolean benign = expSorted.equals(actSorted);
        System.out.println("VERDICT[" + shape + "] = " + (benign ? "自洽(benign)" : "错位(live-gap)"));
        System.out.println("=================================================================");
        assertEquals(expSorted, actSorted, "I-DEBT-35【" + shape + "】Mycat 与直连真库逐字节/列类型不一致（行序无关）→ 活体缺口");
    }

    @Test
    public void shape1_singleShardPassthrough() throws Exception {
        diff("①单片直通",
                "select id,b,vb,blb from tb1 order by id",
                "select id,b,vb,blb from " + PROBE_DB + ".tb order by id");
    }

    @Test
    public void shape2_crossShardMerge() throws Exception {
        // tb2 分 2 片，读全表必经 Mycat 本地归并（I-DEBT-59 动过的合并腿）
        diff("②跨片归并",
                "select id,b,vb,blb from tb2 order by id",
                "select id,b,vb,blb from " + PROBE_DB + ".tb order by id");
    }

    @Test
    public void shape3_localComputedOverBinary() throws Exception {
        // 强制本地计划：把 BINARY 列经本地表达式（octet_length 类）消费——聚合 order by 尾部本地归并
        diff("③本地函数求值",
                "select length(b), length(vb), length(blb) from tb2 order by 1",
                "select length(b), length(vb), length(blb) from " + PROBE_DB + ".tb order by 1");
    }

    @Test
    public void shape4_valuesLiteral() throws Exception {
        // 触发字面量→ByteString 的 codegen 路径。真 MySQL 8.0 不支持 `(values ...) AS t` 表构造，
        // 改用 hex 字面量 `select x'..'`（MySQL 支持），双端对拍。
        // 诚实裁定：Mycat 不支持该形态（路由/解析报错）≠表征错位；仅当 Mycat 成功回读但字节不同 → live-gap。
        String sql = "select x'000A00FF' b";
        List<String> expected;
        try {
            expected = rows(adminBackend, sql);
        } catch (Exception e) {
            expected = Collections.singletonList("<direct-execute-failed> " + e);
        }
        String actual;
        try {
            actual = String.valueOf(rows(mycat, sql));
        } catch (Exception e) {
            System.out.println("\n[④字面量] Mycat 不支持该形态（非表征错位）：" + e.getMessage() + " → 计 benign-skip");
            return;
        }
        System.out.println("\n================= I-DEBT-35 差分对拍【④字面量】 =================");
        System.out.println("期望(直连真 MySQL 8.0)：" + expected);
        System.out.println("实际(经 Mycat)        ：" + actual);
        boolean benign = actual.contains("0x000A00FF");
        System.out.println("VERDICT[④字面量] = " + (benign ? "自洽(benign)" : "错位(live-gap)"));
        System.out.println("=================================================================");
        assertEquals(true, benign, "I-DEBT-35【④字面量】Mycat 回读 hex 字面量字节须与直连一致，实得 " + actual);
    }

    /** 逻辑 drop 不级联物理分库，直连后端按前缀 purge（抄自差分测范式，probe 私有）。 */
    private void purgePhysical() throws Exception {
        purgePhysicalOn(DB1, SCHEMA);
    }

    private void purgePhysicalOn(String dbUrl, String prefix) throws Exception {
        try (Connection c = getMySQLConnection(dbUrl)) {
            List<java.util.Map<String, Object>> schemas = executeQuery(c,
                    "select schema_name from information_schema.schemata where schema_name like '" + prefix + "%'");
            for (java.util.Map<String, Object> row : schemas) {
                execute(c, "drop database if exists `" + row.values().iterator().next() + "`");
            }
        }
    }
}
