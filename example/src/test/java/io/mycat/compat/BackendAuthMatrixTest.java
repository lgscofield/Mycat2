package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import io.mycat.config.DatasourceConfig;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * I-DEBT-9 切片 9-A：后端认证矩阵（T-TEST 债 —— 项目头条目标是 MySQL/MariaDB 版本兼容，
 * 而"Mycat 作为 MySQL 客户端如何完成后端认证"此前 0 覆盖）。
 * <p>
 * 本仓有两套互不相干的后端认证实现，必须分别钉住：
 * <ul>
 *   <li>{@code DatasourceConfig.type=NATIVE} → Mycat 自带的 MySQL 协议客户端
 *       （{@code io.mycat.mysqlclient.command.ConnectHandler}）。</li>
 *   <li>{@code type=JDBC} → Connector/J（当前 e2e 配置 ds0/ds1 实际在跑的通路）。</li>
 * </ul>
 * 账号侧覆盖 caching_sha2_password 的两种服务端状态：其内存缓存可用
 * {@code FLUSH PRIVILEGES} 主动清空（已实测），于是"冷缓存 ⇒ 非加密 TCP 上必须走 RSA full-auth"、
 * "热缓存 ⇒ 直接认证成功"成为两条可确定性构造、报文形态不同的分支。
 * <p>
 * <b>回退证伪</b>：{@code MycatMySQLManagerImpl} 在建 NATIVE 池失败时会 {@code recover()} 成
 * JDBC 池（静默降级），单看"查询成功"无法区分是谁在服务。故 NATIVE 用例的 URL 一律带
 * {@code socketFactory=无效类名} —— 这是 Connector/J 专属参数（建连时反射实例化该类，必抛 SQLException，
 * 不依赖任何时序），自带协议客户端只取 host/port/库名、完全忽略之。于是 NATIVE 用例的任何成功
 * 只能由自带协议客户端产出；G1 用例反向证明这条毒药确实能打死 JDBC 通路。
 * <p>
 * 本类通过 hint 改写服务端全局配置并增删容器账号，不可与其他配置型测试并发；
 * 探针库用独立的 {@code dbauth}，绝不触碰既有 {@code db1}。
 * 注意：NATIVE 认证走不通时自带客户端不等 OK/ERR 之外的任何包，
 * 池首连既不成功也不失败，{@code MycatMySQLManagerImpl} 固定等 60s 后抛 TimeoutException ——
 * 相应用例耗时约 1 分钟属预期，不是用例卡死。
 * <p>
 * 实测（手工 CLI 探针）已把两件事分开：NATIVE 通路上 caching_sha2 <b>热缓存（fast-auth）能通</b>，
 * 建库/建表/单行 DML/单分片与多分片查询均正常；<b>冷缓存（需 RSA full-auth）与服务端下发
 * auth-switch 时必挂 60s</b>（{@code ConnectHandler} 的 AUTH 状态只认 OK/ERR，0xFE/0x01 分支从未实现）。
 * 另有两个与本类正交的 NATIVE 执行链缺陷，已被 CLI 探针钉住并另案挂账，故不在此混测：
 * 多值 insert（同一语句跨分片写）会打挂前端会话；多数据源下分片 CREATE TABLE 报成功却只在部分
 * 分区建表。于是 J1~J3 走完整分片往返（JDBC 执行链已被既有套件证明），N1~N3 只走
 * {@code 单数据源 + 单库 + select 1} 的纯认证通路。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BackendAuthMatrixTest implements MycatTest {

    private static final String PW = "I9a_pass_1";
    private static final String USER_NATIVE = "i9a_native";
    private static final String USER_SHA2 = "i9a_sha2";
    /** 探针库前缀。每格用例用独立的 {@code dbauth_<tag>}，避免上一格（尤其挂起 60s 的格子）留下的半成品配置串味。 */
    private static final String PROBE_PREFIX = "dbauth";

    /**
     * 不含 username/password：CreateDataSourceHint.build() 会用 URL 参数覆盖显式传入的账号。
     * allowPublicKeyRetrieval 供 Connector/J 完成 caching_sha2 冷缓存的 RSA full-auth；
     * 自带协议客户端忽略该参数。
     */
    private static final String CLEAN_URL = "jdbc:mysql://127.0.0.1:3306/mysql"
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai";
    /**
     * 同上但带 Connector/J 专属毒药参数：Connector/J 必然建连失败，自带协议客户端不受影响。
     * <p>
     * 毒药取<b>无效 {@code socketFactory} 类名</b>而非旧的 {@code socketTimeout=1}：后者是 1ms 读超时，
     * 成败取决于套接字缓冲/池热冷/主机负载——同一份代码在 CI runner 上会因连接已热、响应已在缓冲区
     * 而<b>读不超时、往返成功</b>（分片 CREATE TABLE 的 DDL 扇出错误又被 Mycat 吞掉，末条 SELECT 仍返 2 行）→
     * G1 假红（{@code nothing was thrown}，见 I-CI-6 诊断）；换到别的后端/时序又在 60s 池等待后抛 → 非确定性。
     * {@code socketFactory} 由 Connector/J 在每次物理建连时反射实例化，类不存在即<b>确定性地</b>在 connect 阶段抛
     * {@link SQLException}，不依赖任何时序；自带协议客户端只取 host/port/库名、忽略该参数，故 N1~N3 的
     * “成功必属自带协议客户端”前提不受影响。G1 反向证明这条毒药能打死 JDBC 通路。
     */
    private static final String JDBC_DEAD_URL = CLEAN_URL + "&socketFactory=io.mycat.compat.NoSuchSocketFactory__poison";
    /** 纯 Connector/J 直连（绕开 Mycat），用于确定性焐热服务端 caching_sha2 缓存并自检账号可用。 */
    private static final String WARM_URL = "jdbc:mysql://127.0.0.1:3306/mysql"
            + "?username=" + USER_SHA2 + "&password=" + PW
            + "&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai";

    private Connection adminBackend;
    /** 本轮建过的探针逻辑库，@AfterAll 统一回收。 */
    private final List<String> createdSchemas = new java.util.ArrayList<>();

    /**
     * 后端认证能力探测结果（I-CI 本地矩阵可信化）：账号能否在本后端被建出，取决于该后端是否存在对应认证插件。
     * false ⇒ 依赖它的用例用 {@link org.junit.jupiter.api.Assumptions} 记为 N/A（skipped）而非红——
     * 落实计划 Review Focus“合法的不支持 ≠ 网失败”：5.7/MariaDB 无 caching_sha2_password、9.0 移除 mysql_native_password
     * 属后端客观能力，不是 Mycat 兼容失效。旧实现无条件用 MySQL 8.0 专有 {@code identified with <plugin> by} DDL，
     * 在其余 6 档后端 @BeforeAll 即语法崩 → 整类 1 error 的<b>方法学假红</b>，掩盖真实信号。
     */
    private boolean nativeAccountOk;
    private boolean sha2AccountOk;
    private String backendVersion;

    @BeforeAll
    public void createBackendAccounts() throws Exception {
        adminBackend = getMySQLConnection(DB1);
        backendVersion = String.valueOf(
                executeQuery(adminBackend, "select version()").get(0).values().iterator().next());
        boolean mariadb = backendVersion.toUpperCase().contains("MARIADB");
        execute(adminBackend, "drop user if exists '" + USER_NATIVE + "'@'%'");
        execute(adminBackend, "drop user if exists '" + USER_SHA2 + "'@'%'");
        nativeAccountOk = tryCreateUser(USER_NATIVE, nativeDdl(mariadb));
        sha2AccountOk = tryCreateUser(USER_SHA2, sha2Ddl(mariadb));
        System.out.println("[BackendAuthMatrix] 后端=" + backendVersion
                + " native可用=" + nativeAccountOk + " sha2可用=" + sha2AccountOk);
    }

    /** native 建号：MySQL 用 {@code identified with mysql_native_password}；MariaDB 默认认证即 native，用 {@code identified by}。 */
    private String nativeDdl(boolean mariadb) {
        return mariadb
                ? "create user '" + USER_NATIVE + "'@'%' identified by '" + PW + "'"
                : "create user '" + USER_NATIVE + "'@'%' identified with mysql_native_password by '" + PW + "'";
    }

    /** sha2 建号：仅 MySQL≥8.0 有 caching_sha2_password；MariaDB/5.7 无此插件 → 返回 null 记 N/A。 */
    private String sha2Ddl(boolean mariadb) {
        if (mariadb || majorVersion(backendVersion) < 8) {
            return null;
        }
        return "create user '" + USER_SHA2 + "'@'%' identified with caching_sha2_password by '" + PW + "'";
    }

    /**
     * 尽力建号并授权；后端缺该插件/语法时捕获异常记为不可用（N/A），绝不让 @BeforeAll 抛崩整类。
     * 这是把“网可信”和“产品红”分开的关键：setup 阶段的能力缺失属测试前置，不是被测对象失败。
     */
    private boolean tryCreateUser(String user, String ddl) {
        if (ddl == null) {
            return false;
        }
        try {
            execute(adminBackend, ddl);
            execute(adminBackend, "grant all on *.* to '" + user + "'@'%'");
            return true;
        } catch (Exception e) {
            System.out.println("[BackendAuthMatrix] 后端不支持账号 " + user + "：" + e.getMessage() + " → 相关格子记 N/A");
            return false;
        }
    }

    private static int majorVersion(String version) {
        String head = version.split("[.\\-]")[0];
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @AfterAll
    public void dropBackendAccounts() throws Exception {
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            for (String schema : createdSchemas) {
                execute(mycat, "drop database if exists " + schema);
            }
            execute(mycat, RESET_CONFIG);
        }
        purgePhysicalSchemas();
        execute(adminBackend, "drop user if exists '" + USER_NATIVE + "'@'%'");
        execute(adminBackend, "drop user if exists '" + USER_SHA2 + "'@'%'");
        adminBackend.close();
    }

    // ============================================================ JDBC 后端（当前实况通路）

    /** J1：mysql_native_password + JDBC 后端。同账号在 NATIVE 用例里失败时，本用例即"非账号伪影"的对照。 */
    @Test
    public void nativePasswordUserOnJdbcBackend() throws Exception {
        assumeTrue(nativeAccountOk, "后端无 mysql_native_password 账号能力（如 9.0 移除）→ N/A");
        roundTripThroughDatasource("j_native", USER_NATIVE,
                DatasourceConfig.DatasourceType.JDBC, CLEAN_URL);
    }

    /** J2：caching_sha2 冷缓存 + JDBC 后端 —— Connector/J 取服务器公钥完成 RSA full-auth。 */
    @Test
    public void cachingSha2ColdUserOnJdbcBackend() throws Exception {
        assumeTrue(sha2AccountOk, "后端无 caching_sha2_password 插件（5.7/MariaDB）→ N/A");
        coolSha2Cache();
        roundTripThroughDatasource("j_sha2cold", USER_SHA2,
                DatasourceConfig.DatasourceType.JDBC, CLEAN_URL);
    }

    /** J3：caching_sha2 热缓存 + JDBC 后端。 */
    @Test
    public void cachingSha2WarmUserOnJdbcBackend() throws Exception {
        assumeTrue(sha2AccountOk, "后端无 caching_sha2_password 插件（5.7/MariaDB）→ N/A");
        warmSha2Cache();
        roundTripThroughDatasource("j_sha2warm", USER_SHA2,
                DatasourceConfig.DatasourceType.JDBC, CLEAN_URL);
    }

    // ==================================================== NATIVE 后端（Mycat 自带协议客户端）

    /**
     * N1：mysql_native_password 账号 + NATIVE 后端。
     * MySQL 8.0 握手包通告的是服务端默认插件 caching_sha2_password，与该账号的实际插件不同，
     * 服务端为此下发 AuthSwitchRequest(0xFE) 要求换插件重算 —— 钉自带客户端是否处理该报文。
     */
    @Test
    public void nativePasswordUserOnNativeBackend() throws Exception {
        assumeTrue(nativeAccountOk, "后端无 mysql_native_password 账号能力（如 9.0 移除）→ N/A");
        authOnlyThroughNative("n_native", USER_NATIVE);
    }

    /**
     * N2：caching_sha2 冷缓存 + NATIVE 后端。
     * 非加密 TCP 上服务端必然下发 AuthMoreData(0x01)+full-auth(0x04)，
     * 客户端要请求公钥并做 RSA 才能登录 —— MySQL 8.0 默认认证插件，兼容性地基。
     */
    @Test
    public void cachingSha2ColdUserOnNativeBackend() throws Exception {
        assumeTrue(sha2AccountOk, "后端无 caching_sha2_password 插件（5.7/MariaDB）→ N/A");
        coolSha2Cache();
        authOnlyThroughNative("n_sha2cold", USER_SHA2);
    }

    /**
     * N3：caching_sha2 热缓存 + NATIVE 后端。
     * 缓存命中时服务端不再要求 full-auth，直接判定通过（fast-auth 路径）。
     */
    @Test
    public void cachingSha2WarmUserOnNativeBackend() throws Exception {
        assumeTrue(sha2AccountOk, "后端无 caching_sha2_password 插件（5.7/MariaDB）→ N/A");
        warmSha2Cache();
        authOnlyThroughNative("n_sha2warm", USER_SHA2);
    }

    // ================================================================ 回退证伪自检（harness guard）

    /**
     * G1：证明 {@code socketTimeout=1} 足以打死 Connector/J 通路。
     * 本类用 N1~N3 的"成功必属自带协议客户端"推理完全依赖这条前提；若此用例不过，
     * 说明毒药无效，NATIVE 用例的结论全部作废，必须换判别手段。
     */
    @Test
    public void poisonUrlKillsJdbcBackend() {
        assumeTrue(nativeAccountOk, "后端无 mysql_native_password 账号能力（如 9.0 移除）→ N/A");
        assertThrows(SQLException.class, () ->
                roundTripThroughDatasource("g_deadjdbc", USER_NATIVE,
                        DatasourceConfig.DatasourceType.JDBC, JDBC_DEAD_URL));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 用指定账号/URL 建两个同类型数据源并绑到 c0/c1（分片表的两个库分区都会用到），
     * 保证探针流量 100% 经过被测通路，不会漏到配置里既有的 root 数据源上。
     */
    private void roundTripThroughDatasource(String tag, String backendUser,
                                            DatasourceConfig.DatasourceType type, String url)
            throws Exception {
        String ds0 = "i9a_" + tag + "_ds0";
        String ds1 = "i9a_" + tag + "_ds1";
        String probeSchema = PROBE_PREFIX + "_" + tag;
        createdSchemas.add(probeSchema);

        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            execute(mycat, RESET_CONFIG);
            execute(mycat, "drop database if exists " + probeSchema);
            purgePhysicalSchemas();

            for (String name : Arrays.asList(ds0, ds1)) {
                DatasourceConfig config = CreateDataSourceHint.createConfig(name, backendUser, PW, url);
                config.setType(type.name());
                execute(mycat, CreateDataSourceHint.create(config));
            }
            execute(mycat, CreateClusterHint.create("c0", Arrays.asList(ds0), Collections.emptyList()));
            execute(mycat, CreateClusterHint.create("c1", Arrays.asList(ds1), Collections.emptyList()));

            execute(mycat, "create database " + probeSchema);
            execute(mycat, "use " + probeSchema);
            execute(mycat, "CREATE TABLE `authprobe` (\n"
                    + "  `id` bigint NOT NULL AUTO_INCREMENT,\n"
                    + "  `note` varchar(64) DEFAULT NULL,\n"
                    + "  PRIMARY KEY (`id`)\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8"
                    + " dbpartition by mod_hash(id) tbpartition by mod_hash(id) tbpartitions 2 dbpartitions 2;");
            // 逐条单行 insert：多值 insert 在 NATIVE 通路上会打挂会话（正交缺陷，另案 I-DEBT-9B），
            // 本类只钉"认证能否完成 + 数据能否往返"，不让该缺陷污染认证结论。
            execute(mycat, "insert into `authprobe`(`id`,`note`) values (1,'i9a');");
            execute(mycat, "insert into `authprobe`(`id`,`note`) values (2,'i9b');");

            List<Map<String, Object>> rows = executeQuery(mycat,
                    "select note from " + probeSchema + ".authprobe order by id");
            assertEquals(2, rows.size(),
                    type + " 后端 + 账号 " + backendUser + "：往返应返回 2 行");
            assertEquals("i9a", rows.get(0).values().iterator().next(),
                    type + " 后端 + 账号 " + backendUser + "：往返值不符");
        } finally {
            try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
                execute(mycat, "drop database if exists " + probeSchema);
                execute(mycat, RESET_CONFIG);
            }
        }
    }

    /**
     * NATIVE 认证专用格：只建 1 个 NATIVE 数据源 + 1 个 cluster + 1 个单库逻辑库，
     * 然后跑一条必然落到该数据源上的查询。
     * <p>
     * 刻意不做分片建表：实测（CLI 手工探针）NATIVE 通路在多数据源下的分片 DDL 扇出不可靠
     * —— CREATE TABLE 报成功却只在部分分区建表，随后 DML 直接打挂前端会话。那是与本类
     * 正交的执行链缺陷（另案挂账），若混进来会把"认证能不能过"钉成假结论。
     * 认证过与不过是二值的：过 → 查询正常返回；不过 → 池首连既不 OK 也不 ERR，60s 后抛
     * TimeoutException（{@code MycatMySQLManagerImpl} 固定等 1 分钟）。
     */
    private void authOnlyThroughNative(String tag, String backendUser) throws Exception {
        String ds0 = "i9a_" + tag + "_ds0";
        String probeSchema = PROBE_PREFIX + "_" + tag;
        createdSchemas.add(probeSchema);

        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            execute(mycat, RESET_CONFIG);
            execute(mycat, "drop database if exists " + probeSchema);
            purgePhysicalSchemas();

            DatasourceConfig config = CreateDataSourceHint.createConfig(ds0, backendUser, PW, JDBC_DEAD_URL);
            config.setType(DatasourceConfig.DatasourceType.NATIVE.name());
            execute(mycat, CreateDataSourceHint.create(config));
            execute(mycat, CreateClusterHint.create("c0", Arrays.asList(ds0), Collections.emptyList()));

            execute(mycat, "create database " + probeSchema);
            execute(mycat, "use " + probeSchema);
            List<Map<String, Object>> rows = executeQuery(mycat, "select 1");
            assertEquals(1, rows.size(),
                    "NATIVE 后端 + 账号 " + backendUser + "：认证应完成并返回一行");
        } finally {
            try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
                execute(mycat, "drop database if exists " + probeSchema);
                execute(mycat, RESET_CONFIG);
            }
        }
    }

    /**
     * 物理清掉探针分库。服务端逻辑库 drop 不会带走后端里同名的物理库，
     * 残留会让下一格用例在 insert 上撞主键，把认证结论污染成数据问题（实测踩过）。
     */
    private void purgePhysicalSchemas() throws Exception {
        List<Map<String, Object>> schemas =
                executeQuery(adminBackend, "select schema_name from information_schema.schemata"
                        + " where schema_name like '" + PROBE_PREFIX + "%'");
        for (Map<String, Object> row : schemas) {
            execute(adminBackend, "drop database if exists `" + row.values().iterator().next() + "`");
        }
    }

    /** 清空服务端 caching_sha2 内存缓存，使下一次认证必然走 full-auth。 */
    private void coolSha2Cache() throws Exception {
        execute(adminBackend, "flush privileges");
    }

    /**
     * 焐热服务端 caching_sha2 缓存：Connector/J 以 allowPublicKeyRetrieval 完成一次 full-auth。
     * 兼作账号可用性自检 —— 这里登不上说明测试构造本身不成立，应直接暴露而非让后续用例假失败。
     */
    private void warmSha2Cache() throws Exception {
        try (Connection warm = getMySQLConnection(WARM_URL)) {
            assertFalse(executeQuery(warm, "select 1").isEmpty(),
                    "预热登录失败：caching_sha2 账号构造不成立");
        }
    }
}
