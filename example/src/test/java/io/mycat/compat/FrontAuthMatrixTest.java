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
 * </ul>
 * <b>不覆盖（正交特性级缺口，见看板 I-DEBT-37 前端面）</b>：服务端原生 caching_sha2_password
 * fast/full-auth、RSA 公钥协商、client_ed25519、按用户可配认证插件（{@code UserConfig.encryptType}）
 * —— 这些需要专轮 spec，且非 0xFE 降级通路所能服务，只有拒绝降级的非主流客户端才暴露，故不在本回归网塞断言。
 * <p>
 * 通过 hint 改写服务端全局用户配置，不可与其他配置型测试并发；探针用户用独立前缀 {@code i9bfe}，
 * {@code @AfterEach}/{@code @AfterAll} 一律 dropUser + RESET_CONFIG 回收。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FrontAuthMatrixTest implements MycatTest {

    private static final String FE_USER = "i9bfe_ok";
    private static final String FE_PW = "I9b_pass_1";
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
}
