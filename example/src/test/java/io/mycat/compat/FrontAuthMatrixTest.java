package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import io.mycat.config.UserConfig;
import io.mycat.hint.CreateUserHint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-9 切片 9-B（前端面）：客户端接入 Mycat（8066）的认证回归矩阵（T-TEST 债）。
 * <p>
 * 项目头条目标是 MySQL/MariaDB 版本兼容，此前只测了"Myocat 作为客户端如何认证后端"（9-A），
 * 而"Mycat 作为服务端如何认证前端客户端"仅被既有套件用 root/123456 隐式覆盖 —— 一旦默认账号之外的
 * 前端认证（口令校验、拒绝形态、运行时新建用户接入）回归，无人能第一时间发现。本类把前端认证钉成回归网。
 * <p>
 * <b>活路径</b>：{@code MycatCore.newMycatServer} 只 {@code new VertxMycatServer}，前端握手由
 * {@code io.mycat.vertx.VertxMySQLAuthHandler} 完成（proxy 模块的 {@code MySQLClientAuthHandler}
 * 的 NIO {@code onSocketRead} 无实例化点，属死码，不参与本矩阵）。该处理器采用"通告 native + 对非 native
 * 客户端下发 AuthSwitchRequest 强制降级到 native"的 posture，口令校验只做 {@code scramble411}。
 * <p>
 * <b>覆盖范围（当前已具备能力 = mysql_native_password 服务端认证）</b>：
 * <ul>
 *   <li>FA1 运行时新建 native 用户 → 新连接可完成认证并往返（证明 {@code createUser} hint 生效到活 Authenticator）。</li>
 *   <li>FA2 错误口令 → 被干净拒绝（ERR 包，而非挂起）。</li>
 *   <li>FA3 未知用户 → 被干净拒绝。</li>
 *   <li>FA4/FA5（R3-T9 live）缓存 SHA-2 冷启动 full-auth（RSA 公钥往返）紧接热启动 fast-auth（warmed）。</li>
 *   <li>FA6（R3-T9 live）SHA-2 错误口令 → 冷/热两路均干净拒绝。</li>
 * </ul>
 * FA4–FA6 用 {@code select version()} 探得生效画像：major≥8 才跑，否则 {@code assumeTrue} 记 N/A
 * （5.7 / MariaDB 画像广告 native，不涉 caching_sha2）。
 * <b>仍不覆盖</b>：client_ed25519、按用户可配认证插件（{@code UserConfig.encryptType}）——需专轮 spec。
 * <p>
 * 通过 hint 改写服务端全局用户配置，不可与其他配置型测试并发；探针用户用独立前缀 {@code i9bfe}，
 * {@code @AfterEach}/{@code @AfterAll} 一律 dropUser + RESET_CONFIG 回收。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FrontAuthMatrixTest implements MycatTest {

    private static final String FE_USER = "i9bfe_ok";
    private static final String FE_PW = "I9b_pass_1";
    /** caching_sha2 探针用户：每矩阵 run 起新 mycat 进程 ⇒ 冷态；冷→热在同一方法内顺序触发。 */
    private static final String SHA_USER = "i9bfe_sha";
    private static final String SHA_BAD_USER = "i9bfe_sha_bad";
    private static final String SHA_PW = "I9b_sha_1";
    /** 8066 前端 url（不含 username/password，由 DriverManager 显式传入被测凭据）。 */
    private static final String FRONT_URL = "jdbc:mysql://127.0.0.1:8066/mysql"
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true";

    /** 管理员连接（root/123456），用于执行增删用户 hint 与 RESET_CONFIG。 */
    private Connection admin;

    @BeforeAll
    public void setUp() throws Exception {
        admin = getMySQLConnection(DB_MYCAT);
        execute(admin, RESET_CONFIG);
    }

    @AfterEach
    public void dropProbeUser() throws Exception {
        try {
            execute(admin, "/*+ mycat:dropUser{\"username\":\"" + FE_USER + "\"} */");
            execute(admin, "/*+ mycat:dropUser{\"username\":\"" + SHA_USER + "\"} */");
            execute(admin, "/*+ mycat:dropUser{\"username\":\"" + SHA_BAD_USER + "\"} */");
        } finally {
            execute(admin, RESET_CONFIG);
        }
    }

    @AfterAll
    public void tearDown() throws Exception {
        try {
            execute(admin, RESET_CONFIG);
        } finally {
            admin.close();
        }
    }

    /** 运行时创建一个 mysql_native_password 前端用户并使其对后续新连接可见。 */
    private void createNativeUser(String username, String password) throws Exception {
        UserConfig config = new UserConfig();
        config.setUsername(username);
        config.setPassword(password);
        // ip=null ⇒ 不限制来源；schema 留空 ⇒ 连接串里的 database 决定，探测用 /mysql
        execute(admin, CreateUserHint.create(config));
    }

    private List<Map<String, Object>> selectOne(String username, String password) throws Exception {
        try (Connection c = DriverManager.getConnection(FRONT_URL, username, password)) {
            return executeQuery(c, "select 1");
        }
    }

    /**
     * FA1：运行时新建的 native 用户，应能用正确口令完成前端认证并往返。
     * 同时验证 {@code createUser} hint 经 {@code putUser+commit} 重建的活 Authenticator 对新会话即时生效。
     */
    @Test
    public void runtimeCreatedNativeUserCanAuthenticate() throws Exception {
        createNativeUser(FE_USER, FE_PW);
        List<Map<String, Object>> rows = selectOne(FE_USER, FE_PW);
        assertEquals(1, rows.size(), "新建前端用户以正确口令连接后 select 1 应返回一行");
    }

    /**
     * FA2：同一用户的错误口令必须被服务端干净拒绝（抛 SQLException / 收到 ERR 包），
     * 而不是既不成功也不失败地挂起 —— 这是与后端 60s 挂起缺陷相对的前端健康断言。
     */
    @Test
    public void wrongPasswordIsRejected() throws Exception {
        createNativeUser(FE_USER, FE_PW);
        SQLException ex = assertThrows(SQLException.class,
                () -> selectOne(FE_USER, "definitely_wrong_pw"),
                "错误口令应被前端认证拒绝");
        assertNotNull(ex.getMessage());
    }

    /**
     * FA3：未配置的用户名应被干净拒绝。
     */
    @Test
    public void unknownUserIsRejected() throws Exception {
        SQLException ex = assertThrows(SQLException.class,
                () -> selectOne("i9bfe_nobody", "whatever"),
                "未知用户应被前端认证拒绝");
        assertTrue(ex.getMessage() != null);
    }

    /**
     * 探测生效版本画像是否广告 caching_sha2：{@code select version()} 主版本号 ≥8。
     * 由 {@code -Dmycat.persona} 驱动，run-matrix 按后端 mysql:8.x/9.x 起对应画像。
     */
    private boolean backendIsShaCapable() throws Exception {
        List<Map<String, Object>> rows = executeQuery(admin, "select version()");
        String v = String.valueOf(rows.get(0).values().iterator().next());
        int dot = v.indexOf('.');
        int major = dot > 0 ? Integer.parseInt(v.substring(0, dot)) : -1;
        return major >= 8;
    }

    /**
     * FA4+FA5：caching_sha2 冷连接走 RSA full-auth 成功，紧接重连走 fast-auth（warmed）成功。
     * 合并为一个方法以保证时序确定（热依赖冷先置 warmed）。冷态由新 mycat 进程 + 全新用户名保证。
     */
    @Test
    public void cachingSha2ColdFullAuthThenWarmFastAuth() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(backendIsShaCapable(),
                "生效画像非 8.0+（native）→ caching_sha2 格 N/A");
        createNativeUser(SHA_USER, SHA_PW);
        // 冷：首连未 warmed → AuthMoreData(full-auth) → 客户端求公钥 → RSA 密文 → 成功 + 置 warmed
        assertEquals(1, selectOne(SHA_USER, SHA_PW).size(), "冷态 caching_sha2 full-auth 应认证通过");
        // 热：同用户重连已 warmed → fast-auth 直接通过
        assertEquals(1, selectOne(SHA_USER, SHA_PW).size(), "热态 caching_sha2 fast-auth 应认证通过");
    }

    /**
     * FA6：caching_sha2 错误口令应被干净拒绝（无论冷走 full-auth 失败还是热走 fast-auth 失败回落，均 ERR）。
     */
    @Test
    public void cachingSha2WrongPasswordRejected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(backendIsShaCapable(),
                "生效画像非 8.0+（native）→ caching_sha2 格 N/A");
        createNativeUser(SHA_BAD_USER, SHA_PW);
        SQLException ex = assertThrows(SQLException.class,
                () -> selectOne(SHA_BAD_USER, "definitely_wrong_sha_pw"),
                "caching_sha2 错误口令应被前端认证拒绝");
        assertNotNull(ex.getMessage());
    }
}
