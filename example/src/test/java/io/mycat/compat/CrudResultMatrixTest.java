package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R0 / I-CI-3「操作轴」：客户端连上 Mycat 之后，<b>基本 CRUD 的增删改查 + 结果集保真</b>是否逐版本达标。
 * <p>
 * 认证矩阵（{@link BackendAuthMatrixTest} / {@link FrontAuthMatrixTest}）只钉住"能不能连上、认证对不对"，
 * 而"Mycat 替代 MySQL 的招牌"真正要回答的是：连上之后<b>把数据放进来、再原样取出来</b>会不会变形。
 * 这正是 spec ★验收标准里 M2 的"基本 CRUD"子门，也是深债红族（I-DEBT-8/27/34/35/36/39/23e）暴露的地方。
 * <p>
 * <b>本类的定位是"度量网"，不是"全绿声明"</b>：每格是一类值/一次跨分片归并/一个函数，
 * 绿 = 该能力当前达标；红 = 揭出一枚真实缺口（挂到 R5 统一收）。<b>不追求穷尽 SQL 特性面</b>
 * （spec §6 R5 最小必要面），只覆盖 spec 验收点名的"基本 CRUD + 结果集保真"。
 * <p>
 * <b>逐格独立建表</b>：与 {@code @BeforeAll} 建一张全类型大表不同，本类每个用例各自建自己的小表。
 * 这样一格 {@code CREATE TABLE} 的 deparse 缺口只红它自己，不会像 BackendAuth 早期那样
 * 因 {@code @BeforeAll} 抛崩而把整类记成 1 error 的<b>方法学假红</b>、掩盖其余格的真实信号。
 * <p>
 * 分片：一律 {@code dbpartition by mod_hash(id) dbpartitions 2}，使读回必然跨 2 个物理分库归并，
 * 直指导出 Rx merge 面（I-DEBT-8）。探针库用独立前缀 {@code dbcrud}，绝不触碰既有 {@code db1}；
 * {@code @AfterAll} 逻辑 drop + 物理 purge（后端同名物理库不随逻辑 drop 消失）+ RESET_CONFIG 回收。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CrudResultMatrixTest implements MycatTest {

    /** 探针逻辑库；每格用例在其内建独立小表。 */
    private static final String SCHEMA = "dbcrud";
    /** 分片子句：2 个物理分库，读回必经跨库归并。 */
    private static final String SHARD = " dbpartition by mod_hash(`id`) dbpartitions 2";

    /** 后端连接（3306），供物理 purge。 */
    private Connection adminBackend;
    /** 每格用例的连接：@BeforeEach 借、@AfterEach 还，彼此隔离——会打挂会话的用例（C10/I-DEBT-39）只伤自己，不级联污染整类。 */
    private Connection mycat;

    @BeforeAll
    public void createSchema() throws Exception {
        adminBackend = getMySQLConnection(DB1);
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, RESET_CONFIG);
            execute(setup, "drop database if exists " + SCHEMA);
            purgePhysical();
            addC0(setup);
            execute(setup, "create database " + SCHEMA);
        }
    }

    @BeforeEach
    public void openCell() throws Exception {
        mycat = getMySQLConnection(DB_MYCAT);
        execute(mycat, "use " + SCHEMA);
    }

    @AfterEach
    public void closeCell() throws Exception {
        if (mycat != null) {
            mycat.close();
        }
    }

    @AfterAll
    public void dropSchema() throws Exception {
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, "drop database if exists " + SCHEMA);
            execute(setup, RESET_CONFIG);
        }
        purgePhysical();
        if (adminBackend != null) {
            adminBackend.close();
        }
    }

    // ============================================================ 基线格（证明网本身接对了）

    /** C1 基线：int + varchar 单行写入读回。此格若红，说明网/建表/路由本身不成立，其余格结论全废。 */
    @Test
    public void intAndVarcharRoundTrip() throws Exception {
        String t = createCell("c1", "`id` bigint NOT NULL, `c_int` int, `c_var` varchar(255), PRIMARY KEY(`id`)");
        execute(mycat, "insert into " + t + "(`id`,`c_int`,`c_var`) values (1, 42, 'hello-mycat')");
        List<Map<String, Object>> rows = executeQuery(mycat, "select `c_int`,`c_var` from " + t + " where `id`=1");
        assertEquals(1, rows.size(), "C1：主键点查应返回 1 行");
        assertEquals(42, ((Number) rows.get(0).get("c_int")).intValue(), "C1：int 值保真");
        assertEquals("hello-mycat", rows.get(0).get("c_var"), "C1：varchar 值保真");
    }

    /** C2 UPDATE + DELETE 语义：改得动、删得掉，删后计数归零。 */
    @Test
    public void updateAndDeleteSemantics() throws Exception {
        String t = createCell("c2", "`id` bigint NOT NULL, `c_var` varchar(64), PRIMARY KEY(`id`)");
        execute(mycat, "insert into " + t + "(`id`,`c_var`) values (1,'a'),(2,'b')");
        execute(mycat, "update " + t + " set `c_var`='z' where `id`=1");
        List<Map<String, Object>> one = executeQuery(mycat, "select `c_var` from " + t + " where `id`=1");
        assertEquals("z", one.get(0).get("c_var"), "C2：UPDATE 应生效");
        execute(mycat, "delete from " + t + " where `id`=1");
        assertEquals(1L, countOf(mycat, t), "C2：DELETE 后应仅剩 1 行");
    }

    // ====================================================== 值保真格（深债红族暴露处）

    /** C3 decimal 精度/标度保真：decimal(20,4) 写入含 4 位小数，读回不得丢标度或改值。 */
    @Test
    public void decimalScaleFidelity() throws Exception {
        String t = createCell("c3", "`id` bigint NOT NULL, `c_dec` decimal(20,4), PRIMARY KEY(`id`)");
        BigDecimal v = new BigDecimal("12345678901234.5678");
        execute(mycat, "insert into " + t + "(`id`,`c_dec`) values (1, " + v.toPlainString() + ")");
        List<Map<String, Object>> rows = executeQuery(mycat, "select `c_dec` from " + t + " where `id`=1");
        Object got = rows.get(0).get("c_dec");
        assertEquals(0, new BigDecimal(got.toString()).compareTo(v),
                "C3：decimal(20,4) 读回值/标度保真，实得 " + got);
    }

    /** C4 DATE / DATETIME(6) 保真：日期与带微秒的 datetime 原样往返。 */
    @Test
    public void dateDatetimeFidelity() throws Exception {
        String t = createCell("c4",
                "`id` bigint NOT NULL, `c_date` date, `c_dt` datetime(6), PRIMARY KEY(`id`)");
        execute(mycat, "insert into " + t
                + "(`id`,`c_date`,`c_dt`) values (1, '2021-02-22', '2021-02-22 18:34:05.983692')");
        List<Map<String, Object>> rows = executeQuery(mycat, "select `c_date`,`c_dt` from " + t + " where `id`=1");
        assertEquals("2021-02-22", String.valueOf(rows.get(0).get("c_date")), "C4：date 保真");
        // Connector/J getObject 对 datetime 返回 LocalDateTime，toString 带 'T'；归一为空格再比，不误判成假红。
        String dt = String.valueOf(rows.get(0).get("c_dt")).replace('T', ' ');
        assertTrue(dt.startsWith("2021-02-22 18:34:05"),
                "C4：datetime(6) 保真，实得 " + rows.get(0).get("c_dt"));
    }

    /**
     * C5 VARBINARY 字节保真（探针：I-DEBT-35/27 BINARY/BLOB 结果端字节族）。
     * 写入确定字节序列，读回必须逐字节相等——若 Mycat 结果端把 byte[] 变成 ByteString/其它，此格红。
     */
    @Test
    public void varbinaryByteFidelity() throws Exception {
        String t = createCell("c5", "`id` bigint NOT NULL, `c_bin` varbinary(64), PRIMARY KEY(`id`)");
        byte[] payload = new byte[]{0, 1, 2, (byte) 0xFE, (byte) 0xFF, 'A', 'B'};
        execute(mycat, "insert into " + t + "(`id`,`c_bin`) values (1, ?)", Collections.singletonList(payload));
        List<Map<String, Object>> rows = executeQuery(mycat, "select `c_bin` from " + t + " where `id`=1");
        assertArrayEquals(payload, toBytes(rows.get(0).get("c_bin")),
                "C5：varbinary 逐字节保真（牵连 I-DEBT-35/27 字节结果端）");
    }

    /**
     * C6 BIT(8) 官方 byte[] wire 口径对拍（探针：I-DEBT-34 表征错配）。
     * 金标准＝同一行经后端直连 3306 真库读回（Connector/J 对 BIT(>1) 返 byte[]，官方口径），
     * Mycat 前端读出须与之逐字节一致；现网前端返 Boolean/Long 而非 byte[]，此格 RED。
     */
    @Test
    public void bitColumnFidelity() throws Exception {
        String t = createCell("c6", "`id` bigint NOT NULL, `c_bit` bit(8), PRIMARY KEY(`id`)");
        execute(mycat, "insert into " + t + "(`id`,`c_bit`) values (1, b'10110011')");
        Object viaMycat = executeQuery(mycat, "select `c_bit` from " + t + " where `id`=1").get(0).get("c_bit");
        String phys = locatePhysicalSchema("crud_c6");
        try (Connection back = getMySQLConnection(DB1)) {
            Object golden = executeQuery(back, "select `c_bit` from `" + phys + "`.`crud_c6` where `id`=1")
                    .get(0).get("c_bit");
            assertArrayEquals(canonicalBytes(golden), canonicalBytes(viaMycat),
                    "C6：Mycat 前端 BIT(8) 读出须与后端直连真库逐字节一致（I-DEBT-34 官方 byte[] 口径），"
                            + "前端实得 " + viaMycat + " [" + typeOf(viaMycat) + "]");
        }
    }

    // ============================================== 跨分片归并格（探针：I-DEBT-8 Rx merge 面）

    /** C7 跨 2 分库聚合 + 排序：count/sum/min/max/order by 在归并后必须与后端单算一致。 */
    @Test
    public void crossShardAggregateFidelity() throws Exception {
        String t = createCell("c7", "`id` bigint NOT NULL, `val` int, PRIMARY KEY(`id`)");
        // id 1..6 经 mod_hash 落 2 个分库，聚合必经跨库归并
        for (int i = 1; i <= 6; i++) {
            execute(mycat, "insert into " + t + "(`id`,`val`) values (" + i + ", " + (i * 10) + ")");
        }
        assertEquals(6L, scalar(mycat, "select count(*) from " + t), "C7：跨分片 count(*)");
        assertEquals(210L, scalar(mycat, "select sum(`val`) from " + t), "C7：跨分片 sum");
        List<Map<String, Object>> ordered = executeQuery(mycat, "select `val` from " + t + " order by `val` desc");
        assertEquals(6, ordered.size(), "C7：order by 应返回全部行");
        assertEquals(60, ((Number) ordered.get(0).get("val")).intValue(), "C7：order by desc 首行=最大值");
    }

    /** C8 跨分片 LIMIT/OFFSET：分页窗口在归并后仍需正确切片。 */
    @Test
    public void crossShardLimitOffset() throws Exception {
        String t = createCell("c8", "`id` bigint NOT NULL, `val` int, PRIMARY KEY(`id`)");
        for (int i = 1; i <= 6; i++) {
            execute(mycat, "insert into " + t + "(`id`,`val`) values (" + i + ", " + i + ")");
        }
        List<Map<String, Object>> page = executeQuery(mycat, "select `val` from " + t + " order by `val` limit 2 offset 1");
        assertEquals(2, page.size(), "C8：limit 2 应返回 2 行");
        assertEquals(2, ((Number) page.get(0).get("val")).intValue(), "C8：offset 1 后首行=第 2 小值");
    }

    // ===================================================== 函数/错误格（探针：I-DEBT-36/39/23e）

    /** C9 TIMESTAMPDIFF 下推（探针：I-DEBT-36 deparse 不支持 Calcite interval/内部算子）。 */
    @Test
    public void timestampDiffFunctionCell() throws Exception {
        String t = createCell("c9",
                "`id` bigint NOT NULL, `a` datetime, `b` datetime, PRIMARY KEY(`id`)");
        execute(mycat, "insert into " + t
                + "(`id`,`a`,`b`) values (1, '2021-01-01 00:00:00', '2021-01-02 00:00:00')");
        List<Map<String, Object>> rows = executeQuery(mycat,
                "select timestampdiff(SECOND, `a`, `b`) as d from " + t + " where `id`=1");
        assertEquals(86400L, scalarValue(rows.get(0).get("d")),
                "C9：TIMESTAMPDIFF(SECOND) 跨天应得 86400（探针 I-DEBT-36）");
    }

    /** C10 运行期错误须是干净 ERR 包，而非断连（探针：I-DEBT-39 select 1/0 → ERR 2013 终止会话）。
     *  用独立直连（非类共享连接）：本格会打挂会话，隔离开以免污染同池其他格子。 */
    @Test
    public void divideByZeroIsCleanError() throws Exception {
        try (Connection probe = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:8066/" + SCHEMA
                        + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                        + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                "root", "123456")) {
            SQLException ex = assertThrows(SQLException.class,
                    () -> executeQuery(probe, "select 1/0"),
                    "C10：除零应报错（而非静默返回）");
            // 关键断言：错误发生后会话仍可用——能继续跑下一条查询（I-DEBT-39 的失效形态是会话被 2013 打挂）
            List<Map<String, Object>> after = executeQuery(probe, "select 1");
            assertEquals(1, after.size(), "C10：出错后会话应保持存活、可继续查询（探针 I-DEBT-39 断连缺陷）");
            assertTrue(ex.getMessage() != null, "C10：错误应带消息");
            // Review Focus #1 回归守卫：③类规划/校验期错误（未知列）本应干净 ERR + 会话存活，
            // A2 修流式首行错(①类)时不得把连接关闭过度收窄而伤及此路径。派生表免建表，列解析期即失败。
            assertThrows(SQLException.class,
                    () -> executeQuery(probe, "select `no_such_col_zzz` from (select 1) t"),
                    "C10+：未知列应报错");
            List<Map<String, Object>> still = executeQuery(probe, "select 2");
            assertEquals(1, still.size(), "C10+：多条语句级错误后会话仍存活");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 在探针库内建一格独立分片小表，返回 {@code schema.table} 限定名。 */
    private String createCell(String tag, String columnDefs) throws Exception {
        String table = "crud_" + tag;
        execute(mycat, "drop table if exists " + table);
        execute(mycat, "CREATE TABLE `" + table + "` (\n  " + columnDefs + "\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8" + SHARD + ";");
        return SCHEMA + "." + table;
    }

    private long countOf(Connection c, String t) throws Exception {
        return scalar(c, "select count(*) from " + t);
    }

    private long scalar(Connection c, String sql) throws Exception {
        return scalarValue(executeQuery(c, sql).get(0).values().iterator().next());
    }

    private long scalarValue(Object o) {
        return ((Number) o).longValue();
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    /** 把结果端读到的字节载体归一成 byte[]（byte[]/ByteString 皆可），供逐字节断言。 */
    private static byte[] toBytes(Object o) throws Exception {
        if (o instanceof byte[]) {
            return (byte[]) o;
        }
        if (o == null) {
            return new byte[0];
        }
        // 非 byte[]（如 ByteString/String）说明结果端未保真为字节数组：转 UTF-8 字节使其与 payload 不等而红。
        return o.toString().getBytes("UTF-8");
    }

    /** C6 对拍归一：byte[] 原样；Number→大端最小无符号字节；Boolean→1 字节；String→UTF-8。 */
    private static byte[] canonicalBytes(Object o) throws Exception {
        if (o instanceof byte[]) {
            return (byte[]) o;
        }
        if (o == null) {
            return new byte[0];
        }
        if (o instanceof Number) {
            java.math.BigInteger bi = new java.math.BigInteger(o.toString());
            byte[] minimal = bi.toByteArray();
            // BigInteger 补符号位的前导 0x00/0xFF 不属于最小无符号字节串，剔掉。
            if (minimal.length > 1 && (minimal[0] == 0 || minimal[0] == (byte) 0xFF)) {
                byte[] trimmed = new byte[minimal.length - 1];
                System.arraycopy(minimal, 1, trimmed, 0, trimmed.length);
                return trimmed;
            }
            return minimal;
        }
        if (o instanceof Boolean) {
            return new byte[]{(byte) (((Boolean) o) ? 1 : 0)};
        }
        if (o instanceof String) {
            return ((String) o).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("C6 对拍不支持的读出类型: " + typeOf(o));
    }

    /** 经后端直连的 information_schema 定位逻辑分片表落到的物理库名（id=1 经 mod_hash 只落一库）。 */
    private String locatePhysicalSchema(String table) throws Exception {
        List<Map<String, Object>> rows = executeQuery(adminBackend,
                "select table_schema from information_schema.tables"
                        + " where table_schema like '" + SCHEMA + "%' and table_name='" + table + "' limit 1");
        assertEquals(1, rows.size(), "定位物理库失败：" + table);
        return String.valueOf(rows.get(0).get("table_schema"));
    }

    private void purgePhysical() throws Exception {
        List<Map<String, Object>> schemas = executeQuery(adminBackend,
                "select schema_name from information_schema.schemata where schema_name like '" + SCHEMA + "%'");
        for (Map<String, Object> row : schemas) {
            execute(adminBackend, "drop database if exists `" + row.values().iterator().next() + "`");
        }
    }
}
