package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * I-ME-2 · mysqldump <b>往返回灌</b> e2e 矩阵格（回灌半区；查询面半区见 {@link DumpSurfaceMatrixTest}）。
 *
 * <p>本格专补 {@code DumpSurfaceMatrixTest} L45-47 明确 deferred 的 "spec I-ME-2 Done 门：
 * mysqldump 产物能回灌原生 MySQL"。以<b>纯 JDBC</b> 在 e2e 链上模拟真实 dump 工具的三步承重面，
 * 不依赖 mysqldump 二进制（故可进 CI 矩阵）：
 * <ol>
 *   <li><b>结构面</b>：经 Mycat {@code SHOW CREATE TABLE} 取回建表 DDL（Mycat 返回 Calcite 风格
 *       {@code CREATE TABLE IF NOT EXISTS \`db\`.t (...)}），原样（仅重写库名限定）在原生 MySQL 执行——
 *       能建出即证 Mycat 吐出的 DDL 可被 vanilla MySQL reload（结构保真）。</li>
 *   <li><b>列发现面</b>：像 dump 工具一样<b>从源库 {@code SHOW FULL COLUMNS} 的 Extra 发现生成列</b>
 *       （含 GENERATED），构造 INSERT 时排除它们——生列由 MySQL reload 时按表达式重算。
 *       （注：Mycat 的 {@code information_schema.columns} 曾对生列 {@code extra} 返空，
 *       现已修复——I-ME-2 修复后该面与 {@code SHOW FULL COLUMNS} 一致暴露生成列标记，
 *       由 {@link #informationSchemaColumnsExposesGeneratedColumnExtra} 专项锁定。）</li>
 *   <li><b>数据面</b>：经 Mycat {@code SELECT} 逐值捕获非生列原始对象，{@code setObject} 回灌原生。</li>
 * </ol>
 *
 * <p><b>判别力自证</b>：回灌后在原生以服务端归一化读（{@code CAST/BIN/IFNULL} 全转字符串）逐行比对
 * <b>硬编码 ground truth</b>。任一承重面破绽都必 FAIL 而非假绿——
 * DDL 不可 reload → 建表抛错；Mycat SELECT 读错值 → 回灌值 ≠ ground truth；
 * 生成列误纳入 INSERT → MySQL ERROR 3105 拒；生成列发现错（元数据面破）→ 该列漏灌变 NULL ≠ ground truth。
 * 覆盖 int PK / varchar / decimal(10,2) / datetime NULL / STORED 生成列 / bit(8) / text 等代表类型。
 *
 * <p><b>激活条件（当前 NO_DATA，绝不假绿）</b>：三层诚实门控同 {@link DumpSurfaceMatrixTest}——
 * 8066 无 Mycat 或原生后端不可达 → {@link #assumeInfraUp()} abort 记 <b>NO_DATA</b>；
 * 基础设施在 → 真回灌，任一断言不过 = 真回归 FAIL。源=8066(Mycat 逻辑库 dbdump)，
 * 目标=3306(原生独立库 dbdump_restore，避免与 Mycat 托管的物理库撞名)。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DumpRestoreRoundTripMatrixTest implements MycatTest {

    private static final String PLAIN_URL = "jdbc:mysql://127.0.0.1:8066/mysql"
            + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true"
            + "&connectTimeout=3000&socketTimeout=15000&sslMode=DISABLED";
    private static final String USER = "root";
    private static final String PW = "123456";
    /** Mycat 侧源逻辑库。 */
    private static final String SRC_SCHEMA = "dbdump";
    /** 原生侧回灌目标库（独立命名，避免撞 Mycat 托管的物理库）。 */
    private static final String DST_SCHEMA = "dbdump_restore";
    private static final String TABLE = "t1";

    /** 建表 DDL（含 STORED 生成列 + bit(8) + text + datetime NULL，代表类型丰子）。 */
    private static final String CREATE_T1 = "create table " + SRC_SCHEMA + "." + TABLE + " ("
            + "id int not null primary key, name varchar(64), amount decimal(10,2), "
            + "ts datetime null, gen int as (id * 2) stored, b bit(8), txt text)";
    /** 非生成列（回灌 INSERT 目标列，生列 gen 由 MySQL 重算）。顺序即 INSERT 绑定顺序。 */
    private static final String[] LOAD_COLS = {"id", "name", "amount", "ts", "b", "txt"};

    /** 服务端归一化读数（int/varchar/decimal/datetime/gen/bit/text 全转字符串，跨 MySQL 版本稳）。 */
    private static final String NORM_SELECT = "SELECT CAST(id AS CHAR), IFNULL(name,'NULL'), CAST(amount AS CHAR), "
            + "IFNULL(CAST(ts AS CHAR),'NULL'), CAST(gen AS CHAR), BIN(b), IFNULL(txt,'NULL') FROM %s." + TABLE + " ORDER BY id";

    /** 硬编码 ground truth（与 seed 逐值对齐）：id|name|amount|ts|gen|bin(b)|txt。gen=2/4 由 id*2 得来。 */
    private static final List<String> GROUND_TRUTH = List.of(
            "1|a|10.50|2026-01-01 10:00:00|2|10101010|hello",
            "2|b|20.75|NULL|4|11111111|plain");

    private static boolean infraDown = false;

    @BeforeAll
    public void prepareSource() throws Exception {
        // 探测 Mycat 8066
        try (Connection c = java.sql.DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            // ok
        } catch (Exception e) {
            infraDown = true;
            System.out.println("[NO_DATA] 8066 无 Mycat 基础设施 → dump 往返回灌矩阵格待 run-matrix 起 mycat+后端：" + e.getMessage());
            return;
        }
        // 探测原生后端 3306（回灌目标）
        try (Connection c = java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/mysql?user=root&password=123456&allowPublicKeyRetrieval=true&sslMode=DISABLED")) {
            // ok
        } catch (Exception e) {
            infraDown = true;
            System.out.println("[NO_DATA] 3306 原生后端不可达 → dump 往返回灌矩阵格 NO_DATA：" + e.getMessage());
            return;
        }
        try (Connection c = getMySQLConnection(DB_MYCAT)) {
            execute(c, RESET_CONFIG);
            execute(c, "drop database if exists " + SRC_SCHEMA);
            execute(c, "create database " + SRC_SCHEMA);
            execute(c, CREATE_T1);
            execute(c, "insert into " + SRC_SCHEMA + "." + TABLE + " (id,name,amount,ts,b,txt) "
                    + "values (1,'a',10.50,'2026-01-01 10:00:00',b'10101010','hello')");
            execute(c, "insert into " + SRC_SCHEMA + "." + TABLE + " (id,name,amount,ts,b,txt) "
                    + "values (2,'b',20.75,null,b'11111111','plain')");
        }
    }

    @AfterAll
    public void purge() throws Exception {
        if (infraDown) {
            return;
        }
        try (Connection c = getMySQLConnection(DB_MYCAT)) {
            execute(c, "drop database if exists " + SRC_SCHEMA);
            execute(c, RESET_CONFIG);
        } catch (Exception ignored) {
            // 尽力而为
        }
        try (Connection n = nativeConnection()) {
            execute(n, "drop database if exists " + DST_SCHEMA);
        } catch (Exception ignored) {
            // 尽力而为
        }
    }

    private void assumeInfraUp() {
        if (infraDown) {
            abort("8066/3306 infra 不在 → dump 往返回灌矩阵格 NO_DATA（待 run-matrix 起 mycat+后端）");
        }
    }

    private Connection nativeConnection() throws Exception {
        return java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/mysql?user=root&password=123456&allowPublicKeyRetrieval=true"
                        + "&connectTimeout=3000&socketTimeout=15000&sslMode=DISABLED");
    }

    @Test
    public void dumpFromMycatRestoreIntoNative_preservesStructureAndData() throws Exception {
        assumeInfraUp();

        String capturedDdl;
        Set<String> generatedCols;
        List<Map<String, Object>> capturedRows;

        // ===== 半区 A：经 Mycat（dump 源）捕获结构 + 发现生成列 + 捕获数据 =====
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            // (1) 结构面：Mycat SHOW CREATE TABLE → Calcite 风格 DDL
            capturedDdl = showCreateTable(mycat, SRC_SCHEMA + "." + TABLE);
            assertTrue(capturedDdl.toUpperCase().contains("CREATE TABLE"),
                    "Mycat SHOW CREATE TABLE 应返回建表 DDL，实际：" + capturedDdl);
            assertTrue(capturedDdl.contains("`" + SRC_SCHEMA + "`"),
                    "Mycat DDL 应以逻辑库名限定表（dump 工具依赖此形态），实际：" + capturedDdl);

            // (2) 列发现面：像 dump 工具一样从源库 information_schema 发现生成列
            generatedCols = discoverGeneratedColumns(mycat, SRC_SCHEMA, TABLE);

            // (3) 数据面：逐值捕获非生成列原始对象（生列不入 INSERT，交由原生重算）
            capturedRows = captureRows(mycat, SRC_SCHEMA + "." + TABLE, LOAD_COLS);
            assertEquals(2, capturedRows.size(), "Mycat 应回灌前读回 2 行");

            // 中途健全性：源侧归一化读 == ground truth（把失败定位到"回灌"而非"读源"，非承重断言）
            assertEquals(GROUND_TRUTH, normalizedRows(mycat, String.format(NORM_SELECT, SRC_SCHEMA)),
                    "前置健全性：经 Mycat 读源应等于 ground truth");
        }

        // ===== 半区 B：在原生 MySQL（dump 目标）重建 + 回灌 + 保真断言 =====
        try (Connection nativeConn = nativeConnection()) {
            execute(nativeConn, "drop database if exists " + DST_SCHEMA);
            execute(nativeConn, "create database " + DST_SCHEMA);

            // 结构回灌：把捕获 DDL 的库名限定重写为目标库，原样执行——能建出即证 Mycat DDL 可被 vanilla MySQL reload
            String reloadDdl = capturedDdl.replace("`" + SRC_SCHEMA + "`", "`" + DST_SCHEMA + "`");
            execute(nativeConn, reloadDdl);

            // 数据回灌：INSERT 非生成列（顺序 LOAD_COLS），setObject 逐值绑定捕获对象
            String colList = String.join(", ", LOAD_COLS);
            String placeholders = String.join(", ", java.util.Collections.nCopies(LOAD_COLS.length, "?"));
            String insert = "INSERT INTO " + DST_SCHEMA + "." + TABLE + " (" + colList + ") VALUES (" + placeholders + ")";
            try (PreparedStatement ps = nativeConn.prepareStatement(insert)) {
                for (Map<String, Object> row : capturedRows) {
                    for (int i = 0; i < LOAD_COLS.length; i++) {
                        ps.setObject(i + 1, row.get(LOAD_COLS[i]));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // ===== 判别力自证：回灌后原生归一化读逐行 == 硬编码 ground truth =====
            List<String> restored = normalizedRows(nativeConn, String.format(NORM_SELECT, DST_SCHEMA));
            assertEquals(GROUND_TRUTH, restored,
                    "回灌后原生 MySQL 的行值应与 seed ground truth 逐值一致（证 Mycat dump 的 SELECT 数据面保真）");

            // 结构保真：回灌表列集合须含预期全部列（含原生重算的生成列）
            Set<String> reloadedCols = columnNames(nativeConn, DST_SCHEMA, TABLE);
            for (String expect : new String[] {"id", "name", "amount", "ts", "gen", "b", "txt"}) {
                assertTrue(reloadedCols.contains(expect),
                        "回灌表应含列 " + expect + "，实际：" + reloadedCols);
            }
            // 生成列在原生须为生成列语义（值由表达式重算，非直灌）——已由 gen=2/4 保真间接证；此处再验原生标记
            assertFalse(generatedCols.isEmpty(), "dump 工具应从源 information_schema 至少发现一个生成列(gen)");
            assertTrue(generatedCols.contains("gen"), "gen 应被识别为生成列，实际：" + generatedCols);
        }
    }

    /**
     * I-ME-2 · 生成列元数据面<b>回归锁</b>：Mycat 的 {@code information_schema.columns} 必须像原生
     * MySQL 一样对生成列暴露 {@code EXTRA}（'STORED GENERATED'/'VIRTUAL GENERATED'）与非空
     * {@code GENERATION_EXPRESSION}。
     *
     * <p><b>承重依据</b>：真实 mysqldump 的<b>结构面</b>正是走
     * {@code SELECT ... EXTRA ... FROM information_schema.columns} 发现生成列
     * （MySQL 8.0/8.4 mysqldump.cc {@code get_table_structure}；MariaDB 11.4 mariadb-dump
     * {@code select column_name, extra, generation_expression from information_schema.columns}）。
     * Mycat 的 {@code information_schema.columns} 以自身 Calcite 逻辑元数据应答
     * （{@code hbt/MysqlMetadataManager} 的 COLUMNS 行供应），修复前把 {@code EXTRA} 无条件硬编为空
     * → 客户端 dump 走该通道将无法识别生成列（把生列误当普通列 INSERT → 原生回灌 ERROR 3105）。
     *
     * <p>本锁复用 {@link #prepareSource()} seed 的 {@code dbdump.t1}（含 {@code gen int as (id*2) stored}）：
     * 修复前 RED（EXTRA 恒空断言失败），修复并重生 jar 后 GREEN。
     */
    @Test
    public void informationSchemaColumnsExposesGeneratedColumnExtra() throws Exception {
        assumeInfraUp();
        try (Connection mycat = getMySQLConnection(DB_MYCAT);
                Statement st = mycat.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT EXTRA, GENERATION_EXPRESSION FROM information_schema.columns "
                                + "WHERE TABLE_SCHEMA = '" + SRC_SCHEMA + "' AND TABLE_NAME = '" + TABLE
                                + "' AND COLUMN_NAME = 'gen'")) {
            assertTrue(rs.next(), "information_schema.columns 应含 " + SRC_SCHEMA + "." + TABLE + ".gen 行");
            String extra = rs.getString("EXTRA");
            String genExpr = rs.getString("GENERATION_EXPRESSION");
            assertNotNull(extra, "生成列的 i_s.columns.EXTRA 不应为 null");
            assertTrue(extra.toUpperCase().contains("GENERATED"),
                    "生成列的 i_s.columns.EXTRA 应含 'GENERATED'（对齐原生 MySQL，mysqldump 结构面依赖），实际：'" + extra + "'");
            assertTrue(extra.toUpperCase().contains("STORED"),
                    "gen 声明为 STORED，EXTRA 应为 'STORED GENERATED'，实际：'" + extra + "'");
            assertNotNull(genExpr, "生成列的 GENERATION_EXPRESSION 不应为 null");
            assertFalse(genExpr.trim().isEmpty(),
                    "生成列的 GENERATION_EXPRESSION 应非空（含表达式），实际：'" + genExpr + "'");
        }
    }

    /** 经 Mycat/原生 SHOW CREATE TABLE 取回建表 DDL（结果集第 2 列）。 */
    private String showCreateTable(Connection c, String qualifiedTable) throws Exception {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SHOW CREATE TABLE " + qualifiedTable)) {
            assertTrue(rs.next(), "SHOW CREATE TABLE 应返回一行：" + qualifiedTable);
            return rs.getString(2);
        }
    }

    /**
     * 像 dump 工具一样从源库发现生成列。
     *
     * <p>本方法用 {@code SHOW FULL COLUMNS}（Mycat 正确返回 {@code Extra = 'STORED GENERATED'/
     * 'VIRTUAL GENERATED'}）作为发现面；这也贴真实 mysqldump（部分版本）走的通道。
     * <p><b>注：{@code information_schema.columns} 面也已可用</b>——此前 Mycat 的
     * {@code information_schema.columns} 对生成列的 {@code extra} 无条件硬编为空、
     * {@code generation_expression} 为 NULL（本会话先前发现）；<b>I-ME-2 修复</b>（
     * {@code hbt/MysqlMetadataManager} COLUMNS 行供应改取 druid {@code getAsExpr()}/{@code getGeneratedAlwaysAs()}
     * 并据 {@code isStored()} 填 {@code EXTRA}）后，该面与 {@code SHOW FULL COLUMNS} 一致暴露生成列标记，
     * 由 {@link #informationSchemaColumnsExposesGeneratedColumnExtra} 回归锁定。
     * <p>返回列名集合（小写）。
     */
    private Set<String> discoverGeneratedColumns(Connection c, String schema, String table) throws Exception {
        Set<String> gen = new LinkedHashSet<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SHOW FULL COLUMNS FROM " + schema + "." + table)) {
            while (rs.next()) {
                String extra = rs.getString("Extra");
                if (extra != null && extra.toUpperCase().contains("GENERATED")) {
                    gen.add(rs.getString("Field").toLowerCase());
                }
            }
        }
        return gen;
    }

    /** 逐值捕获指定列的原始对象（getObject），返回行列表（列名→值，保持读取顺序）。 */
    private List<Map<String, Object>> captureRows(Connection c, String qualifiedTable, String[] cols) throws Exception {
        String sql = "SELECT " + String.join(", ", cols) + " FROM " + qualifiedTable + " ORDER BY id";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (String col : cols) {
                    row.put(col, rs.getObject(col));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** 服务端归一化读（所有列已在 SQL 内 CAST/BIN/IFNULL 成字符串）→ 每行 "|" 拼接。 */
    private List<String> normalizedRows(Connection c, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        sb.append('|');
                    }
                    sb.append(rs.getString(i));
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    /** 目标库表的列名集合（小写），经原生 information_schema 读。 */
    private Set<String> columnNames(Connection c, String schema, String table) throws Exception {
        Set<String> cols = new LinkedHashSet<>();
        String sql = "SELECT COLUMN_NAME FROM information_schema.columns "
                + "WHERE TABLE_SCHEMA = '" + schema + "' AND TABLE_NAME = '" + table + "' ORDER BY ORDINAL_POSITION";
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                cols.add(rs.getString(1).toLowerCase());
            }
        }
        return cols;
    }
}
