package io.mycat.compat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * I-DEBT-54 D2b · 会话 sql_mode 驱动本地校验门控 活库端到端（判据级）。
 *
 * <p><b>v2 判据（脊柱反转后）</b>：mysqldump 默认口径 dump tablespaces 时，Mycat 本地
 * Calcite {@code AggChecker} 对 {@code information_schema.FILES} 仿表上 mysqldump 形态的
 * 非严格 GROUP BY（选 {@code EXTRA} 仅按 {@code TABLESPACE_NAME} 分组）抛
 * {@code CalciteContextException: ... is not being grouped}（铁证 .superpowers/d2-repro.err）。
 * 该仿表从不下推后端，症状纯在本地校验。D2b-B 门控修复后：会话
 * {@code SET sql_mode=''}（mysqldump 连接自带）下该查询应放行、不再报错；缺省严格会话仍应拒绝。
 *
 * <p>走纯 JDBC 经完整 Mycat 8066 执行链（握手→SET 捕获→编译→AggChecker→门控），复用
 * {@link DumpSurfaceMatrixTest} 的 8066 探测/NO_DATA 三层诚实门控，<b>绝不假绿</b>：infra 不在
 * 即 {@link #assumeInfraUp()} abort 记 NO_DATA，交 run-matrix 起 mycat+后端实跑取铁证。
 *
 * <p><b>S2 缓存不串味隔离</b>：两条探针查询用文本不同的 {@code WHERE 'probe_x'='probe_x'} 字面量
 * 使其落入<b>不同</b> plan cache 键——避免"宽松会话先编译并缓存、严格会话命中缓存跳过重校验
 * 而不误拒"（全局会话无关缓存的既定局限，见设计 S2），从而保证严格会话探针确定性走自身编译、
 * 真被 AggChecker 拒绝。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SqlModeSessionDumpSurfaceTest {

    private static final String PLAIN_URL = "jdbc:mysql://127.0.0.1:8066/mysql"
            + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true"
            + "&connectTimeout=3000&socketTimeout=15000&sslMode=DISABLED";
    private static final String USER = "root";
    private static final String PW = "123456";

    private static boolean infraDown = false;

    @BeforeAll
    public void probe() {
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            // 探测即可
        } catch (Exception e) {
            infraDown = true;
            System.out.println("[NO_DATA] 8066 无 Mycat 基础设施 → D2b 会话 sql_mode 门控 e2e 待 run-matrix 起 mycat+后端："
                    + e.getMessage());
        }
    }

    @AfterAll
    public void noop() {
        // 无 schema 变更（i_s 为全局仿表），无需清理
    }

    private void assumeInfraUp() {
        if (infraDown) {
            abort("8066 无 Mycat 基础设施 → D2b 门控 e2e NO_DATA（待 run-matrix 起 mycat+后端）");
        }
    }

    /** mysqldump tablespaces 形态：选 EXTRA 仅按 TABLESPACE_NAME 分组（非严格）。probeTag 使两连接落入不同缓存键。 */
    private static String tablespacesNonStrictGroupBy(String probeTag) {
        return "select TABLESPACE_NAME, EXTRA from information_schema.FILES"
                + " where '" + probeTag + "' = '" + probeTag + "'"
                + " group by TABLESPACE_NAME";
    }

    /** 完整消费结果集（mysqldump 逐行 mysql_store_result）；任何 SQLException 上抛。 */
    private static void runAndConsume(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
            try (java.sql.ResultSet rs = st.getResultSet()) {
                if (rs != null) {
                    while (rs.next()) {
                        rs.getObject(1);
                    }
                }
            }
        }
    }

    /** 判据①：会话 {@code SET sql_mode=''} 后回读返会话真值（空串，Task 2 read 腿在活链兑现）。 */
    @Test
    public void emptySessionSqlModeReadsBackAsEmpty() throws Exception {
        assumeInfraUp();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW);
                Statement st = c.createStatement()) {
            st.execute("SET sql_mode=''");
            try (java.sql.ResultSet rs = st.executeQuery("select @@session.sql_mode")) {
                assertTrue(rs.next(), "@@session.sql_mode 应有一行");
                String mode = rs.getString(1);
                assertEquals("", mode == null ? "" : mode.trim(),
                        "SET sql_mode='' 后会话回读应为空串（非默认模板），实际：'" + mode + "'");
            }
        }
    }

    /** 判据②（D2b-B 核心）：宽松会话（{@code SET sql_mode=''}）下 tablespaces 非严格 GROUP BY 放行、不报错。 */
    @Test
    public void lenientSessionDumpTablespacesGroupByNotRejected() throws Exception {
        assumeInfraUp();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            try (Statement st = c.createStatement()) {
                st.execute("SET sql_mode=''");
            }
            // 若门控未接通（生产入口未穿会话态），这里会抛 CalciteContextException: ... is not being grouped
            assertDoesNotThrow(
                    () -> runAndConsume(c, tablespacesNonStrictGroupBy("probe_lenient")),
                    "宽松会话（SET sql_mode=''）下 tablespaces 形态非严格 GROUP BY 不应被本地校验器误拒");
        }
    }

    /** 判据③（缺省严格）：默认严格会话上同形非严格 GROUP BY 仍应被拒。缓存隔离坐实修正：
     * MemPlanCache:69 以**参数化文本**为键，probeTag 字面量会被折成 '?' 失去隔离力（判据②
     * 转换修复后编译成功入缓存，同形严格会话命中即免校验放行——S2 反串味的真复现）。
     * 故改用换列序形态：标识符不进参数化，键必然独立，确定性走严格自身编译。 */
    @Test
    public void strictSessionStillRejectsNonStrictGroupBy() throws Exception {
        assumeInfraUp();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            // 不 SET sql_mode：新会话 getSessionSqlMode()==null → 派生严格
            try {
                runAndConsume(c, "select EXTRA, TABLESPACE_NAME from information_schema.FILES"
                        + " group by TABLESPACE_NAME");
                fail("缺省严格会话应拒绝 tablespaces 形态非严格 GROUP BY");
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                assertTrue(msg.contains("not being grouped") || msg.toLowerCase().contains("grouped")
                                || msg.contains("1140") || msg.contains("ONLY_FULL_GROUP_BY"),
                        "拒绝须因 ONLY_FULL_GROUP_BY 严格分组校验，实际：" + msg);
            }
        }
    }
}
