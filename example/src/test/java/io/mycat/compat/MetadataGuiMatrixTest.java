package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import io.mycat.config.ServerPersona;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * I-DEBT-29-EXT｜§7 第六列「元数据 GUI」全 7 persona 档扩面矩阵。
 * <p>
 * I-DEBT-29（已收口 {@code 039b97eb4}）只在 {@code persona=MYSQL_5_7} 一档取证过 DBeaver 元数据序列；
 * 元数据仿真按 persona 驱动（{@code PersonaCollations} 行集分 MySQL/MariaDB 系、版本串按画像自述），
 * <b>不可由 5.7 外推</b>。本类把同一套 12 步活体序列参数化到全 7 档，并叠加 persona 差分格：
 * <ul>
 *   <li><b>序列格</b>：DBeaver 21.2.5 元数据序列逐条重放（含招牌 {@code SELECT @@GLOBAL.x,@@GLOBAL.y}
 *       逗号形——druid reprint 吞逗号回归锁（I-DEBT-29 修法 A）在<b>非 5.7 画像</b>下必须同样成立）；</li>
 *   <li><b>P 格（persona 生效守卫）</b>：后端 {@code version()} 与 {@code -Dcompat.db} 目标版不符 →
 *       abort 记 NO_DATA（防串档）；Mycat 自述 {@code @@version} 必须以该 persona 版本串为前缀——
 *       若服务端忘传 {@code -Dmycat.persona}（缺省 MYSQL_5_7）冒充他档，此格即红；</li>
 *   <li><b>D 格（差分对拍，oracle=直连同版后端）</b>：D2 后端 utf8mb4 系 collation 必全部出现在 Mycat
 *       SHOW COLLATION（persona 仿真的<em>声明</em>是该版画像，后端真相即该画像应有的行集基准）；
 *       D3 Mycat 自报的 utf8mb4 默认 collation 必须能在其自身 SHOW COLLATION 登记集中找到（仿真面内部自洽）。</li>
 * </ul>
 * <p>
 * <b>激活条件（绝不假绿）</b>：8066 无 Mycat 或 3306 无后端 → {@link #assumeInfraUp()} 每格 abort 记
 * NO_DATA（响在报告里，不得静默 0 tests）。逐档实跑由一次性矩阵驱动脚本编排（同
 * {@code run-matrix.ps1} 配方：同版后端容器 + {@code -Dmycat.persona} 覆盖 + {@code -Dcompat.db} 传入）。
 * <b>本类是度量网</b>：红 = 揭出真实的 persona 元数据破口（按 spec §3 裁决树归票），不追求预先知道答案。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MetadataGuiMatrixTest implements MycatTest {

    private static final String PLAIN_URL = "jdbc:mysql://127.0.0.1:8066/mysql"
            + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true"
            + "&connectTimeout=3000&socketTimeout=30000&sslMode=DISABLED";
    private static final String USER = "root";
    private static final String PW = "123456";

    /** infra 探测失败/版本串档时置位：各 @Test 内 abort 记 NO_DATA。 */
    private static boolean infraDown = false;

    /** -Dcompat.db 传入的目标后端镜像（矩阵驱动逐档设置），如 mysql:8.4 / mariadb:10.11。 */
    private static final String COMPAT_DB = System.getProperty("compat.db", "");

    /** 镜像 → persona 映射（与矩阵驱动必须一致；persona 默认版本串即自述前缀基准）。 */
    private static ServerPersona personaOf(String compatDb) {
        return switch (compatDb) {
            case "mysql:5.7" -> ServerPersona.MYSQL_5_7;
            case "mysql:8.0" -> ServerPersona.MYSQL_8_0;
            case "mysql:8.4" -> ServerPersona.MYSQL_8_4;
            case "mysql:9.0" -> ServerPersona.MYSQL_9_X;
            case "mariadb:10.6" -> ServerPersona.MARIADB_10_6;
            case "mariadb:10.11" -> ServerPersona.MARIADB_10_11;
            case "mariadb:11.4" -> ServerPersona.MARIADB_11_4;
            default -> null;
        };
    }

    /** persona 默认版本串的点分前缀，如 "10.11.8-MariaDB" → "10.11"。 */
    private static String versionPrefix(ServerPersona p) {
        String v = p.defaultVersionString();
        int secondDot = v.indexOf('.', v.indexOf('.') + 1);
        return secondDot < 0 ? v : v.substring(0, secondDot);
    }

    private void assumeInfraUp() {
        if (infraDown) {
            abort("8066/3306 infra 不在或 compat.db 未提供 → 元数据 GUI 矩阵格 NO_DATA（待矩阵驱动起服）");
        }
    }

    /** 守卫：compat.db 已知 + 后端版本前缀相符，否则置 infraDown（每格显式 NO_DATA，不静默）。 */
    private void guardBackendVersion() throws Exception {
        if (infraDown) {
            return;
        }
        ServerPersona p = personaOf(COMPAT_DB);
        if (p == null) {
            infraDown = true;
            abort("未提供合法 -Dcompat.db（实测值 '" + COMPAT_DB + "'）→ NO_DATA，防无标注串档");
            return;
        }
        try (Connection backend = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/mysql?useUnicode=true&characterEncoding=utf8"
                        + "&connectTimeout=3000&socketTimeout=15000&sslMode=DISABLED"
                        + "&allowPublicKeyRetrieval=true", USER, PW)) {
            String v = String.valueOf(executeQuery(backend, "select version()").get(0).values().iterator().next());
            if (!v.startsWith(versionPrefix(p))) {
                infraDown = true;
                abort("后端实际 " + v + " 与目标档 " + COMPAT_DB + "（前缀 " + versionPrefix(p)
                        + "）不符 → 串档风险，本格记 NO_DATA 而非错档绿");
            }
        }
    }

    // ==================================================== P 格：persona 生效守卫

    /**
     * P1：Mycat 自述 @@version 必须与 persona 默认版本串同源（握手 serverVersion、VERSION()、@@version
     * 三宣称一致是"无缝替代"的元数据地基——DBeaver 等 GUI 读 @@version 判方言）。
     * 这同时坐实服务端 -Dmycat.persona 确实生效——忘传时缺省 MYSQL_5_7 会在这里现形（而非冒充他档冒绿）。
     * <b>I-DEBT-29-EXT 实证破口（2026-10-30 RED 目视）**：`@@version` 走
     * {@code MysqlVariableServiceImpl} 从后端 SHOW GLOBAL VARIABLES 拉表，透传后端真值（如 8.0.46），
     * 与 persona 宣称脱节——握手/VERSION() 说画像、@@version 说后端真相，三宣称自相矛盾。
     */
    @Test
    public void p1MycatVersionMatchesPersona() throws Exception {
        assumeInfraUp();
        guardBackendVersion();
        ServerPersona p = personaOf(COMPAT_DB);
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            String v = String.valueOf(executeQuery(c, "select @@version").get(0).values().iterator().next());
            System.out.println("[MetadataGui] " + COMPAT_DB + " persona=" + p + " mycat @@version=" + v);
            assertTrue(v.startsWith(p.defaultVersionString()),
                    "Mycat(" + COMPAT_DB + ") 的 @@version=" + v + " 必须以 persona " + p
                            + " 自述串 " + p.defaultVersionString() + " 为前缀（与握手/VERSION() 同源宣称）");
        }
    }

    /**
     * P2：VERSION() 函数与 @@version 变量两个宣称口必须一致。
     * 现状 VERSION() 走 {@code MycatVersionFunction}（persona 串）、@@version 走后端的 SHOW VARIABLES 透传，
     * 二者若不重合即"同一会话两种说法"，GUI 按哪个判都可能踩空。
     */
    @Test
    public void p2VersionFunctionAgreesWithVersionVariable() throws Exception {
        assumeInfraUp();
        guardBackendVersion();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            String varV = String.valueOf(executeQuery(c, "select @@version").get(0).values().iterator().next());
            String fnV = String.valueOf(executeQuery(c, "select version()").get(0).values().iterator().next());
            System.out.println("[MetadataGui] " + COMPAT_DB + " @@version=" + varV + " version()=" + fnV);
            assertEquals(fnV, varV, "同一会话内 version() 与 @@version 宣称必须一致（persona=" + personaOf(COMPAT_DB) + "）");
        }
    }

    // ==================================================== 序列格：DBeaver 元数据 12 步

    /**
     * S1：DBeaver 21.2.5 元数据序列整链重放（与 {@code ClientTest#testDBeaverMetadataSequenceLive} 同意图，
     * 参数化到 persona）。招牌 {@code @@GLOBAL.x,@@GLOBAL.y} 逗号格锁 I-DEBT-29 修法 A 跨画像成立，
     * 并做 1 行 2 列非空值断言（卖灯修复形）。
     */
    @Test
    public void s1DBeaverMetadataSequence() throws Exception {
        assumeInfraUp();
        guardBackendVersion();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            execute(c, "/* ApplicationName=DBeaver 21.2.5 - Main */ SET autocommit=1");
            executeQuery(c, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SELECT DATABASE()");
            executeQuery(c, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW ENGINES");
            executeQuery(c, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW CHARSET");
            executeQuery(c, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW COLLATION");
            List<Map<String, Object>> charsetCollation = executeQuery(c,
                    "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SELECT @@GLOBAL.character_set_server,@@GLOBAL.collation_server");
            assertEquals(1, charsetCollation.size(), "全局 charset/collation 查询应恰好 1 行");
            Map<String, Object> row = charsetCollation.get(0);
            assertEquals(2, row.size(), "应恰好 2 列");
            List<Object> values = new ArrayList<>(row.values());
            assertNotNull(values.get(0), "第 1 列（character_set_server）不得为 null");
            assertNotNull(values.get(1), "第 2 列（collation_server）不得为 null");
            executeQuery(c, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW PLUGINS");
            executeQuery(c, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW VARIABLES LIKE 'lower_case_table_names'");
            executeQuery(c, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ show databases");
            executeQuery(c, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SELECT * FROM information_schema.TABLES t\n" +
                    "WHERE\n" +
                    "\tt.TABLE_SCHEMA = 'information_schema'\n" +
                    "\tAND t.TABLE_NAME = 'CHECK_CONSTRAINTS'");
        }
    }

    /**
     * S2（I-DEBT-85 回归锁）：{@code getTables(typeList=[TABLE,VIEW])}——Connector/J 对非空 typeList
     * 生成无 GROUP BY 的裸 {@code HAVING TABLE_TYPE IN (...)}。真 MySQL/MariaDB 把该形按 WHERE 逐行
     * 处理（2026-11-10 一手实测）；修复前 Mycat 默认会话抛 notGroupExpr、宽松会话实现层越界崩。
     * 判据：带类型过滤的结果集 == 不带过滤结果集的客户端侧 TABLE/VIEW 子集（逐 (catalog,name,type) 比对）。
     */
    @Test
    public void s2GetTablesTypeListFilter() throws Exception {
        assumeInfraUp();
        guardBackendVersion();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            Set<String> all = new LinkedHashSet<>();
            try (ResultSet rs = c.getMetaData().getTables(null, null, "%", null)) {
                while (rs.next()) {
                    all.add(rs.getString("TABLE_CAT") + "/" + rs.getString("TABLE_NAME")
                            + "/" + rs.getString("TABLE_TYPE"));
                }
            }
            Set<String> expected = new LinkedHashSet<>();
            for (String key : all) {
                if (key.endsWith("/TABLE") || key.endsWith("/VIEW")) {
                    expected.add(key);
                }
            }
            Set<String> filtered = new LinkedHashSet<>();
            try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String type = rs.getString("TABLE_TYPE");
                    assertTrue("TABLE".equals(type) || "VIEW".equals(type),
                            "typeList 过滤后不得混入其他类型：" + type);
                    filtered.add(rs.getString("TABLE_CAT") + "/" + rs.getString("TABLE_NAME") + "/" + type);
                }
            }
            System.out.println("[MetadataGui] " + COMPAT_DB + " getTables all=" + all.size()
                    + " typeList[TABLE,VIEW]=" + filtered.size());
            assertEquals(expected, filtered,
                    "getTables(typeList) 必须等于无过滤结果集的 TABLE/VIEW 子集（裸 HAVING 按 WHERE 逐行语义）");
        }
    }

    // ==================================================== D 格：persona 差分对拍（oracle=直连同版后端）

    /** 提取 SHOW COLLATION 行集中出现的全部 collation 名（遍历每行所有单元格，取 utf8mb4 系 token）。 */
    private static Set<String> utf8mb4Collations(List<Map<String, Object>> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            for (Object cell : r.values()) {
                if (cell == null) {
                    continue;
                }
                for (String token : String.valueOf(cell).toLowerCase().split("[^a-z0-9_]+")) {
                    if (token.startsWith("utf8mb4")) {
                        names.add(token);
                    }
                }
            }
        }
        return names;
    }

    /**
     * D2：后端（同版真库）utf8mb4 系 collation 全集，Mycat SHOW COLLATION 必须全部报得出。
     * persona 仿真的声明就是"该版画像"，后端真相即该画像应有的行集基准；缺一个即仿真行集
     * 与该 persona 实际画像脱节（按 spec §3 裁决树归票：修法 A 正交的 persona 仿真破口）。
     */
    @Test
    public void d2Utf8mb4CollationsSupersetOfBackend() throws Exception {
        assumeInfraUp();
        guardBackendVersion();
        Set<String> backend;
        try (Connection b = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/mysql?useUnicode=true&characterEncoding=utf8"
                        + "&connectTimeout=3000&socketTimeout=30000&sslMode=DISABLED"
                        + "&allowPublicKeyRetrieval=true", USER, PW)) {
            backend = utf8mb4Collations(executeQuery(b, "SHOW COLLATION"));
        }
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            Set<String> mycat = utf8mb4Collations(executeQuery(c, "SHOW COLLATION"));
            List<String> missing = new ArrayList<>(backend);
            missing.removeAll(mycat);
            System.out.println("[MetadataGui] " + COMPAT_DB + " backend utf8mb4=" + backend.size()
                    + " mycat=" + mycat.size() + " missing=" + missing);
            assertTrue(missing.isEmpty(),
                    "Mycat(" + COMPAT_DB + ") SHOW COLLATION 缺后端真库存在的 utf8mb4 collation：" + missing);
        }
    }

    /**
     * D3：Mycat 自报的 utf8mb4 默认 collation（SHOW CHARSET）必须能在其自身 SHOW COLLATION 登记集中找到。
     * 仿真面内部自洽守卫——CHARSET 与 COLLATION 两仿真相互矛盾（如 charset 行指一个画像里不存在的
     * 默认 collation）会让 GUI 客户端拿到不一致元数据，此格专抓这种自相矛盾。
     */
    @Test
    public void d3CharsetDefaultCollationIsSelfConsistent() throws Exception {
        assumeInfraUp();
        guardBackendVersion();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            Set<String> collations = utf8mb4Collations(executeQuery(c, "SHOW COLLATION"));
            String utf8mb4Default = null;
            for (Map<String, Object> r : executeQuery(c, "SHOW CHARACTER SET")) {
                Set<String> tokens = utf8mb4Collations(List.of(r));
                // charset 行里 Description/Default collation 属 utf8mb4 系且非 charset 名本身的 token → 取默认 collation
                for (String t : tokens) {
                    if (!t.equals("utf8mb4")) {
                        utf8mb4Default = t;
                    }
                }
            }
            System.out.println("[MetadataGui] " + COMPAT_DB + " utf8mb4 default collation=" + utf8mb4Default
                    + " inShowCollation=" + (utf8mb4Default != null && collations.contains(utf8mb4Default)));
            assertNotNull(utf8mb4Default, "SHOW CHARACTER SET 应报出 utf8mb4 默认 collation");
            assertTrue(collations.contains(utf8mb4Default),
                    "Mycat(" + COMPAT_DB + ") CHARSET 自报的 utf8mb4 默认 collation '" + utf8mb4Default
                            + "' 必须存在于其 SHOW COLLATION 行集（仿真面自洽）");
        }
    }

    /**
     * D4：SHOW CHARACTER SET 的<b>列头</b>必须与同版后端真库逐列一致。
     * <b>I-DEBT-29-EXT-CONN 实证破口（2026-10-30 RED 目视）**：{@code ShowCharacterSetSQLHandler}
     * 把第三列别名为 {@code Default_collection}——任何引擎（MySQL 5.7/8.x、MariaDB 全系）的真表头都是
     * {@code Default collation}（i_s 表列名 DEFAULT_COLLATE_NAME 不等于 SHOW 表头），按列名读取的客户端会取空。
     * 本格以直连同版后端为 oracle，专抓自造表头这类"三宣称之外的第四宣称"失真。
     * <b>边界</b>：dotnet Connector/NET 的 {@code DBNull} 硬崩点<b>不在</b>此列，而在 {@code SHOW COLLATION}
     * 的 {@code Id} 列（MariaDB uca1400 模板行，见 I-DEBT-70）；本格只钉表头保真，不冒充该崩的修复。
     */
    @Test
    public void d4ShowCharacterSetHeadersMatchBackend() throws Exception {
        assumeInfraUp();
        guardBackendVersion();
        List<String> backendHeaders;
        List<String> mycatHeaders;
        try (Connection b = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/mysql?useUnicode=true&characterEncoding=utf8"
                        + "&connectTimeout=3000&socketTimeout=30000&sslMode=DISABLED"
                        + "&allowPublicKeyRetrieval=true", USER, PW)) {
            backendHeaders = new ArrayList<>(executeQuery(b, "SHOW CHARACTER SET").get(0).keySet());
        }
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            mycatHeaders = new ArrayList<>(executeQuery(c, "SHOW CHARACTER SET").get(0).keySet());
        }
        System.out.println("[MetadataGui] " + COMPAT_DB + " backend headers=" + backendHeaders
                + " mycat headers=" + mycatHeaders);
        assertEquals(backendHeaders, mycatHeaders,
                "Mycat(" + COMPAT_DB + ") SHOW CHARACTER SET 列头必须与同版后端真库逐列一致（客户端按列名读取）");
    }
}
