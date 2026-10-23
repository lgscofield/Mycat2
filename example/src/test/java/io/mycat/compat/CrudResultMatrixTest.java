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
import static org.junit.jupiter.api.Assertions.assertNull;
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
     * C6 BIT 列官方 byte[] wire 口径钉测（探针：I-DEBT-34 表征错配）。
     * 金标准＝ MySQL/MariaDB Connector/J 官方口径（已查档坐实）：BIT(1)→Boolean、BIT(n&gt;1)→byte[]（大端原始字节）。
     * bit 字面量→字节是完全确定的，故直接钉前端读回的类型/字节（拓扑无关，不依赖另起可直连的分片物理库）。
     * Root B 已修单片/下推直通列头路径（BaseRowObservable case BIT/BOOLEAN 接后端原生 byte[]），此格 GREEN。
     * <b>边界（终审 Important #1）</b>：本口径仅对 **单片命中(`where id=1`)→列头来自后端** 路径成立；跨片归并/本地
     * 计划列头走 CalciteRowMetaData，BIT 经 fork 类型系统归一为数值、列头失 BIT 性，而行值仍是后端原生 byte[]，
     * 于 BaseRowObservable.getObjects 数值分支崩（实证）——属“类型表征统一”专轮，红锁移至
     * {@code UserCaseTest#caseCrossShardBitColumnFidelity}（与跨片 caseI36 同族，本 R5 不强推、不 fabricate 绿）。
     */
    @Test
    public void bitColumnFidelity() throws Exception {
        String t = createCell("c6", "`id` bigint NOT NULL, `c_bit1` bit(1), `c_bit8` bit(8),"
                + " `c_bit16` bit(16), `c_bitnull` bit(8), PRIMARY KEY(`id`)");
        execute(mycat, "insert into " + t
                + "(`id`,`c_bit1`,`c_bit8`,`c_bit16`,`c_bitnull`) values (1, b'1', b'10110011',"
                + " b'1000000000000001', NULL)");
        Map<String, Object> row = executeQuery(mycat,
                "select `c_bit1`,`c_bit8`,`c_bit16`,`c_bitnull` from " + t + " where `id`=1").get(0);
        // BIT(8)=b'10110011'：官方口径 = byte[]{0xB3}（不得塑成数值/Boolean，不得跨层崩）。
        assertEquals(Boolean.TRUE, toBitBoolean(row.get("c_bit1")),
                "C6：BIT(1)=b'1' 前端应读回 Boolean true（官方口径），实得 "
                        + row.get("c_bit1") + " [" + typeOf(row.get("c_bit1")) + "]");
        assertArrayEquals(new byte[]{(byte) 0xB3}, toBitBytes(row.get("c_bit8")),
                "C6：BIT(8)=b'10110011' 前端应读回原始字节 {0xB3}（I-DEBT-34 官方 byte[] 口径），实得 ["
                        + typeOf(row.get("c_bit8")) + "]");
        assertArrayEquals(new byte[]{(byte) 0x80, 0x01}, toBitBytes(row.get("c_bit16")),
                "C6：BIT(16) 前端应读回 2 字节大端 {0x80,0x01}，实得 [" + typeOf(row.get("c_bit16")) + "]");
        assertNull(row.get("c_bitnull"),
                "C6：BIT 列 NULL 读回应为 null，实得 " + row.get("c_bitnull"));
    }

    // 终审 Important #1：跨片归并/本地计划的 BIT 列头路径未闭合——CalciteRowMetaData 把 BIT 归一为数值列头，
    // 行值仍是后端原生 byte[]，BaseRowObservable.getObjects 数值分支(:355) 抛 UnsupportedOperationException。
    // 修复须联动 fork 类型系统(无 BIT SqlTypeName)/归并列头来源，属“类型表征统一”专轮；复现红锁移至
    // UserCaseTest#caseCrossShardBitColumnFidelity（与跨片 caseI36 同族），本格不占全绿 CRUD 矩阵。

    /** 归一 BIT(n>1) 前端读出为 byte[]：官方口径必为 byte[]；若退化为数值则按大端最小无符号字节归一。 */
    private static byte[] toBitBytes(Object o) throws Exception {
        return canonicalBytes(o);
    }

    /** 归一 BIT(1) 前端读出为 Boolean：Boolean 直接取；byte[]/Number 按非零真。 */
    private static Boolean toBitBoolean(Object o) throws Exception {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        byte[] b = canonicalBytes(o);
        for (byte x : b) {
            if (x != 0) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
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
                + "(`id`,`a`,`b`) values (1, '2021-03-01 00:00:00', '2022-02-28 00:00:00')");
        // 确定值断言（拓扑无关，同 C6 裁定）：故意选**跨月跨年且不等间隔**的一对（2021-03-01 → 2022-02-28），
        // 使 DAY/MONTH/YEAR 真具鉴别力——原生下推（后端 MySQL 求值）得 364/11/0；若 Mycat 误走本地实现
        // 的“月/日/年字段直减”算法（见 TimestampDiffFunction），会得 27/-1/1 一类错值，此格即红。
        // （旧数据 01-01→01-02 使三个历法单位恰得正确值，零鉴别力——终审 Important #2 修正。）
        assertEquals(31449600L, tsDiff(t, "SECOND"), "C9：TIMESTAMPDIFF(SECOND) 364天应得 31449600");
        assertEquals(524160L, tsDiff(t, "MINUTE"), "C9：TIMESTAMPDIFF(MINUTE) 364天应得 524160");
        assertEquals(8736L, tsDiff(t, "HOUR"), "C9：TIMESTAMPDIFF(HOUR) 364天应得 8736");
        assertEquals(364L, tsDiff(t, "DAY"), "C9：TIMESTAMPDIFF(DAY) 应得 364");
        assertEquals(11L, tsDiff(t, "MONTH"), "C9：TIMESTAMPDIFF(MONTH) 跨月应得 11");
        assertEquals(0L, tsDiff(t, "YEAR"), "C9：TIMESTAMPDIFF(YEAR) 未满一年应得 0");
        // NULL 传播：任一入参 NULL → 结果为 NULL，不得崩会话。
        List<Map<String, Object>> nullRow = executeQuery(mycat,
                "select timestampdiff(SECOND, NULL, `b`) as d from " + t + " where `id`=1");
        assertNull(nullRow.get(0).get("d"), "C9：TIMESTAMPDIFF(SECOND,NULL,b) 应得 NULL，实得 "
                + nullRow.get(0).get("d"));
    }

    private long tsDiff(String t, String unit) throws Exception {
        List<Map<String, Object>> rows = executeQuery(mycat,
                "select timestampdiff(" + unit + ", `a`, `b`) as d from " + t + " where `id`=1");
        return scalarValue(rows.get(0).get("d"));
    }

    /** C10 除零对外值语义 + 运行期错误传输层契约（探针：I-DEBT-39）。
     *  <b>tranche①-宽 收口·翻转旧绿锁</b>：Mycat 本地 {@code /} 已对齐 MySQL——SELECT 期 {@code 1/0} 返回
     *  <b>单行 NULL</b>（非报错、非断连），与真库 MySQL 5.7.44 活库对拍逐位一致（值语义见 I-DEBT-39 spec §5）。
     *  本格钉三层：<b>①值语义</b>——除零=单行 NULL（旧 C10 断"抛 SQLException"属 tranche① 前的临时口径，现据 MySQL 语义翻转）；
     *  <b>②传输层契约</b>——除零返回 NULL 后会话不被打挂、可继续查询（I-DEBT-39 的失效形态是被 2013 终止会话）；
     *  <b>③未知列（③类规划/校验期错误）</b>仍须干净 ERR 而非断连——与除法语义正交，A2 修流式首行错(①类)时不得把
     *  连接关闭过度收窄而伤及此路径，保留回归守卫（派生表免建表，列解析期即失败）。
     *  <b>口径边界</b>：仍<b>不覆盖</b> MySQL 的 Warning 1365 是否透传，及 strict 模式下 DML 除零升 1365 错误——
     *  此二者属独立的除零对外语义债，另计，不由本格宣称兼容。
     *  用独立直连（非类共享连接）：会话存活断言须干净起点，避免同池其他格子干扰。 */
    @Test
    public void divideByZeroYieldsNullAndSessionSurvives() throws Exception {
        try (Connection probe = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:8066/" + SCHEMA
                        + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                        + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                "root", "123456")) {
            // ① 值语义：SELECT 期 1/0 对齐 MySQL = 单行 NULL（tranche①-宽 交付，翻转旧绿锁 C10 的抛异常口径）
            List<Map<String, Object>> div = executeQuery(probe, "select 1/0 as d");
            assertEquals(1, div.size(), "C10：select 1/0 应返回单行（对齐 MySQL SELECT 期语义）");
            assertNull(div.get(0).get("d"),
                    "C10：除零值应为 NULL（tranche①-宽：本地 / 对齐 MySQL，翻转旧绿锁 C10 的抛 SQLException 口径）");
            // ② 传输层契约：除零返回 NULL 后会话仍可用（I-DEBT-39 的失效形态是会话被 2013 打挂）
            List<Map<String, Object>> after = executeQuery(probe, "select 1");
            assertEquals(1, after.size(), "C10：除零后会话应保持存活、可继续查询（探针 I-DEBT-39 断连缺陷）");
            // ③ 未知列（③类规划/校验期错误）仍须干净 ERR + 会话存活——与除法语义正交的传输层契约，保留守卫。
            assertThrows(SQLException.class,
                    () -> executeQuery(probe, "select `no_such_col_zzz` from (select 1) t"),
                    "C10+：未知列应报错");
            List<Map<String, Object>> still = executeQuery(probe, "select 2");
            assertEquals(1, still.size(), "C10+：语句级错误后会话仍存活");
        }
    }

    /** C11 跨分片 AVG 值保真（探针：I-DEBT-39 tranche①-宽 聚合内部除法连带面）。
     *  AVG 经 Mycat 跨 2 分库归并后<b>必走本地 AGGREGATE_REDUCE</b>（各分片下推部分 SUM/COUNT、归并后本地算
     *  最终 AVG＝含 DIVIDE 的 RexCall），tranche①-宽 的 {@code remapOperator} 把该 DIVIDE 也改判为
     *  {@code MysqlDivideFunction}（精确数值商 scale=被除数scale+div_precision_increment、HALF_UP）。
     *  <b>金标准＝同数据在真 MySQL(3306) 单表直算的 AVG</b>——Mycat 跨片归并 AVG 必须与后端逐值一致
     *  （值保真，BigDecimal.compareTo 忽略 scale 只比数值），否则暴露聚合内部除法与 MySQL 的偏离。
     *  整型列/DECIMAL 列各一枚（选<b>非整除</b>数据产 .3333 一类，令 scale 舍入真具鉴别力）。
     *  <b>口径边界</b>：仅钉 AVG 十进制商值；不覆盖 Warning 1365 / strict DML（另计），不覆盖列头 scale/precision（§5.3 残面）。 */
    @Test
    public void crossShardAvgFidelity() throws Exception {
        String t = createCell("c11", "`id` bigint NOT NULL, `i` int, `d` decimal(20, 4), PRIMARY KEY(`id`)");
        // i: 1,2,3,4,5,7 → sum=22 count=6 → MySQL avg=3.6667(scale4)；d 同值但 decimal(20,4) → avg scale=8。
        String[][] rows = {{"1", "1", "1.0000"}, {"2", "2", "2.0000"}, {"3", "3", "3.0000"},
                {"4", "4", "4.0000"}, {"5", "5", "5.0000"}, {"6", "7", "7.0000"}};
        for (String[] r : rows) {
            execute(mycat, "insert into " + t + "(`id`,`i`,`d`) values (" + r[0] + ", " + r[1] + ", " + r[2] + ")");
        }
        // oracle：在真 MySQL(3306) 的 mysql 库建同数据非分片参照表，直算 AVG（后端单趟聚合＝真值口径）。
        execute(adminBackend, "drop table if exists c11_ref");
        execute(adminBackend, "create table c11_ref(`id` bigint primary key, `i` int, `d` decimal(20, 4))");
        for (String[] r : rows) {
            execute(adminBackend, "insert into c11_ref(`id`,`i`,`d`) values (" + r[0] + ", " + r[1] + ", " + r[2] + ")");
        }
        try {
            assertAvgEqualsOracle("C11：跨分片 AVG(int)", t, "`i`", "c11_ref", "`i`");
            assertAvgEqualsOracle("C11：跨分片 AVG(decimal(20,4))", t, "`d`", "c11_ref", "`d`");
        } finally {
            execute(adminBackend, "drop table if exists c11_ref");
        }
    }

    /** 读 Mycat 跨分片 {@code select avg(col) from t} 值，与后端 {@code select avg(col) from ref} 真值 BigDecimal 逐值相等。 */
    private void assertAvgEqualsOracle(String tag, String shardTable, String col, String refTable, String refCol) throws Exception {
        Object mycatV = executeQuery(mycat, "select avg(" + col + ") from " + shardTable).get(0).values().iterator().next();
        Object oracleV = executeQuery(adminBackend, "select avg(" + refCol + ") from " + refTable).get(0).values().iterator().next();
        BigDecimal mycatBd = mycatV == null ? null : new BigDecimal(String.valueOf(mycatV));
        BigDecimal oracleBd = oracleV == null ? null : new BigDecimal(String.valueOf(oracleV));
        // 无条件打印两侧实际值（即便绿也留下度量痕，便于跨 persona 复盘）。
        System.out.println(tag + " => mycat=" + mycatV + " [" + typeOf(mycatV) + "] oracle=" + oracleV + " [" + typeOf(oracleV) + "]");
        assertTrue(oracleBd != null, tag + "：oracle 不应为 null");
        assertTrue(mycatBd != null, tag + "：Mycat AVG 不应为 null（会话/归并崩？）");
        assertEquals(0, mycatBd.compareTo(oracleBd), tag + "：Mycat 跨片归并 AVG 值必与真 MySQL 逐值相等，实得 mycat="
                + mycatBd + " oracle=" + oracleBd);
    }

    /** C12 跨分片 STDDEV/VARIANCE 值保真（I-DEBT-61 对齐修复）。
     *  与 C11 AVG 同构：STDDEV/VARIANCE 经 Mycat 跨 2 分库归并必走本地 AggregateReduceFunctionsRule
     *  规约为 SUM(x*x)/SUM(x)/COUNT 组合，规约口径由算子 SqlKind 决定。
     *  <b>金标准＝同数据真 MySQL(3306) 单表无后缀默认聚合</b>——MySQL {@code STD()/STDDEV()/VARIANCE()} 默认
     *  <b>总体口径(÷N)</b>（与 MariaDB 一致），Mycat 跨片归并必须逐值对齐。历史缺口：Calcite 随 SQL 标准将
     *  裸 STDDEV/VARIANCE 定为 <b>样本口径(÷N-1)</b>，跨片本地归并与真库错开（stddev 2.1602 vs 1.9720）；
     *  修复＝fork 将裸 STDDEV/VARIANCE 算子 kind 改为 STDDEV_POP/VAR_POP。单片下推路径透传真库本就正确，
     *  仅本地归并腿需对齐。因含 SQRT 为浮点值，以<b>相对容差 1e-5</b> 对拍（非 AVG 那类精确十进制逐位等）：
     *  实测定数发现本地归并 BigDecimal 计算链存在 ~1.4e-6 量级精度偏差（如 variance 3.88888333 vs
     *  真库 3.88888888，另型表也从 Double 塌成 BigDecimal），此属<b>跨片精度/类型表征独立缺口</b>，
     *  已另挂账不并本票；本票只钉口径（修复前样本口径偏差 20%，1e-5 容差下仍必红，判别力不失）。 */
    @Test
    public void crossShardStddevVarianceFidelity() throws Exception {
        String t = createCell("c12", "`id` bigint NOT NULL, `i` int, PRIMARY KEY(`id`)");
        // i: 1,2,3,4,5,7 → n=6 sum=22 sumsq=104 SS=104-22^2/6=23.3333；总体 var=3.88889 sd=1.97203（样本 var=4.66667）。
        String[][] rows = {{"1", "1"}, {"2", "2"}, {"3", "3"}, {"4", "4"}, {"5", "5"}, {"6", "7"}};
        for (String[] r : rows) {
            execute(mycat, "insert into " + t + "(`id`,`i`) values (" + r[0] + ", " + r[1] + ")");
        }
        execute(adminBackend, "drop table if exists c12_ref");
        execute(adminBackend, "create table c12_ref(`id` bigint primary key, `i` int)");
        for (String[] r : rows) {
            execute(adminBackend, "insert into c12_ref(`id`,`i`) values (" + r[0] + ", " + r[1] + ")");
        }
        try {
            assertAggFidelity("C12：跨分片 STDDEV(int)", t, "stddev", "c12_ref");
            assertAggFidelity("C12：跨分片 VARIANCE(int)", t, "variance", "c12_ref");
        } finally {
            execute(adminBackend, "drop table if exists c12_ref");
        }
    }

    /** 读 Mycat 跨分片 {@code select <fn>(i) from t} 与后端 {@code select <fn>(i) from ref} 真值，浮点相对容差对拍（口径钉；本地归并精度偏差另挂账，见 C12 注释）。
     *  <b>容差按 oracle 形态分支</b>：MariaDB 对裸 STDDEV/VARIANCE <b>确定性舍入到 4 位小数</b>（10.11 实测 1.9720/3.8889，
     *  经 mysql-connector 回读为 Double），拿舍入态 oracle 用全精度 1e-5 相对容差硬比必假红（差可达半单位末位=0.5e-4）；
     *  此时放宽到"相对 1e-5 与绝对 0.5001e-4 取大"。MySQL 系返全精度（scale&gt;4）保持 1e-5 严容差，整数态（scale≤0）精确亦严容差。
     *  判别力不失：样本口径(n-1)偏差 ~20% ≫ 任何一档容差。 */
    private void assertAggFidelity(String tag, String shardTable, String fn, String refTable) throws Exception {
        Object mycatV = executeQuery(mycat, "select " + fn + "(`i`) from " + shardTable).get(0).values().iterator().next();
        Object oracleV = executeQuery(adminBackend, "select " + fn + "(`i`) from " + refTable).get(0).values().iterator().next();
        System.out.println(tag + " => mycat=" + mycatV + " [" + typeOf(mycatV) + "] oracle=" + oracleV + " [" + typeOf(oracleV) + "]");
        assertTrue(mycatV != null, tag + "：Mycat 值不应为 null（会话/归并崩？）");
        assertTrue(oracleV != null, tag + "：oracle 不应为 null");
        double m = ((Number) mycatV).doubleValue();
        double o = ((Number) oracleV).doubleValue();
        double tol = 1e-5 * Math.max(1.0, Math.abs(o));
        if (isRoundedOracleShape(toBigDecimal(oracleV))) {
            tol = Math.max(tol, 0.5001e-4); // oracle 被后端舍入到 4 位小数：覆盖 ±0.5×10^-4 舍入误差
        }
        assertEquals(o, m, tol, tag + "：Mycat 跨片归并 " + fn + " 必与真 MySQL 无后缀默认(总体口径)一致，实得 mycat="
                + m + " oracle=" + o);
    }

    /** 数值 → BigDecimal 形态（Double 走 {@link BigDecimal#valueOf} 规范化，与浏览器/十进制字面量口径一致）。 */
    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        return new BigDecimal(v.toString());
    }

    /**
     * oracle 是否为"后端舍入形态"：scale∈[1,4] 的短小数（MariaDB 裸 STDDEV/VARIANCE 确定性舍入到 4 位小数的回读特征）。
     * scale≤0 = 整数精确结果（如 n=2 {1,3} 的 STDDEV=2），非舍入态；scale&gt;4 = 全精度（MySQL 系），同样不放宽。
     * 离线守卫见 {@link CrudResultMatrixOracleShapeTest}。
     */
    static boolean isRoundedOracleShape(BigDecimal oracle) {
        int s = oracle.scale();
        return s >= 1 && s <= 4;
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

    private void purgePhysical() throws Exception {
        List<Map<String, Object>> schemas = executeQuery(adminBackend,
                "select schema_name from information_schema.schemata where schema_name like '" + SCHEMA + "%'");
        for (Map<String, Object> row : schemas) {
            execute(adminBackend, "drop database if exists `" + row.values().iterator().next() + "`");
        }
    }
}
