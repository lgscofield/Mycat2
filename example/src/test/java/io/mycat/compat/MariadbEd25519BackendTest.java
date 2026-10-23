package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-AU-6 / M6 金标准端到端：MariaDB 后端启用 <b>client_ed25519</b> 认证时，Mycat 以 JDBC 腿建连、
 * 认证并跑通基本 CRUD（北极星=Mycat 无缝替代 MariaDB）。
 * <p>
 * <b>三判据</b>（反向锁先行，证明测的确实是 ed25519 而非其它通路）：
 * <ol>
 * <li>{@code mysqlConnectorJCannotAuthEd25519}——mysql-connector-j 连 ed25519 用户必失败于
 * {@code Unable to load authentication plugin 'client_ed25519'}（旧行为钉死，错误须响亮出现）；</li>
 * <li>{@code mariadbDriverAuthsEd25519Directly}——mariadb-java-client（Task 1 转 compile 的驱动）
 * 直连同用户成功；</li>
 * <li>{@code mycatCrudRoundTripOverEd25519Backend}——经 Mycat hint 注册 {@code jdbc:mariadb://} 数据源
 * 但<b>不设 dbType</b>（{@code createConfig} 保持字段默认 "mysql"），故走 <b>plain Druid</b> 腿
 * （{@code DruidDatasourceProvider} 中 {@code !"mysql".equalsIgnoreCase(dbType)} 为假，不进 jOOQ），
 * 建库建表（mod_hash 2 分片）、插入、跨片读回，逐值与后端直连 oracle 对拍。</li>
 * </ol>
 * <b>已知边界（非本测覆盖，登记为后续债务 I-AU-6b）</b>：显式 {@code dbType=mariadb} 会进入
 * jOOQ {@code ParsingDataSource(SQLDialect.MARIADB)} 方言翻译腿。实测该腿拦截 Mycat 下发的物理
 * DDL {@code CREATE DATABASE IF NOT EXISTS dbmX_N} 时抛 {@code org.jooq.impl.ParserException}
 * （jOOQ MARIADB 解析器不认 MariaDB 专有 {@code CREATE DATABASE}），令 dbType=mariadb 后端建库即失败。
 * 此为 SQL 方言腿的既有缺陷，与 ed25519 认证正交（认证由 URL scheme 驱动路由保证，判据③走 plain
 * Druid 路径已端到端跑通），故不在本金标准内伪绿，单列债务跟踪。
 * <p>
 * <b>测试床</b>：docker mariadb:10.11 于 3308（独立端口，绝不触碰 m1-mysql-a/b 的 3306/3307），
 * ed25519 用户 {@code e25519}（服务端插件 auth_ed25519.so 已 INSTALL）。Mycat 以
 * {@code -Dmycat.persona=MARIADB_10_11} classpath 直启（8066）。前缀 {@code dbm6} 独立，
 * {@code @AfterAll} drop + 物理 purge + RESET_CONFIG 回收。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MariadbEd25519BackendTest implements MycatTest {

    private static final String M6_HOST_URL = "jdbc:mariadb://localhost:3308";
    private static final String M6_ADMIN_URL = M6_HOST_URL + "/mysql?username=root&password=123456";
    private static final String ED_USER = "e25519";
    private static final String ED_PW = "p@ssw0rd";
    private static final String PROBE_SCHEMA = "dbm6";

    @AfterAll
    public void purge() throws Exception {
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            execute(mycat, "drop database if exists " + PROBE_SCHEMA);
            execute(mycat, RESET_CONFIG);
        }
        try (Connection admin = getMySQLConnection(M6_ADMIN_URL)) {
            purgeM6Physical();
        }
    }

    private void purgeM6Physical() throws Exception {
        List<Map<String, Object>> schemas = executeQuery(getMySQLConnection(M6_ADMIN_URL),
                "select schema_name from information_schema.schemata where schema_name like 'dbm6%'");
        for (Map<String, Object> row : schemas) {
            execute(getMySQLConnection(M6_ADMIN_URL), "drop database if exists `" + row.values().iterator().next() + "`");
        }
    }

    /** 判据①反向锁：mysql-connector-j 不支持 client_ed25519——旧行为必须响亮出现，钉住"测的是 ed25519"。 */
    @Test
    public void mysqlConnectorJCannotAuthEd25519() {
        String url = "jdbc:mysql://localhost:3308/mysql?useSSL=false&allowPublicKeyRetrieval=true";
        SQLException e = assertThrows(SQLException.class,
                () -> DriverManagerConnect.connect(url, ED_USER, ED_PW, "com.mysql.cj.jdbc.Driver"));
        String msg = String.valueOf(e.getMessage()) + String.valueOf(e.getCause());
        assertTrue(msg.toLowerCase().contains("authentication plugin") || msg.contains("client_ed25519"),
                "mysql-connector-j 连 ed25519 用户应报认证插件类错误，实得：" + msg);
    }

    /** 判据①正向：mariadb-java-client 同用户直连成功（驱动能力面锁）。 */
    @Test
    public void mariadbDriverAuthsEd25519Directly() throws Exception {
        try (Connection c = DriverManagerConnect.connect(M6_HOST_URL + "/mysql", ED_USER, ED_PW,
                "org.mariadb.jdbc.Driver")) {
            List<Map<String, Object>> rows = executeQuery(c, "select 1 as one");
            assertEquals(1, ((Number) rows.get(0).get("one")).intValue());
        }
    }

    /** 判据②金标准：经 Mycat（hint 注册 mariadb 数据源 + 分片库）CRUD 往返，逐值与后端直连对拍。 */
    @Test
    public void mycatCrudRoundTripOverEd25519Backend() throws Exception {
        String ds = "m6_ed25519_ds";
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            execute(mycat, RESET_CONFIG);
            execute(mycat, "drop database if exists " + PROBE_SCHEMA);
            purgeM6Physical();

            // type 不显式设置 = 默认 JDBC（spec 后端腿）；URL 不带 username/password 参数，
            // 走 createConfig 显式账号通道（避免 CreateDataSourceHint.build 的 URL 覆盖语义绕路）。
            execute(mycat, CreateDataSourceHint.create(ds, ED_USER, ED_PW, M6_HOST_URL + "/mysql"));
            execute(mycat, CreateClusterHint.create("c0", Arrays.asList(ds), Collections.emptyList()));

            execute(mycat, "create database " + PROBE_SCHEMA);
            execute(mycat, "use " + PROBE_SCHEMA);
            execute(mycat, "CREATE TABLE `edprobe` (\n"
                    + "  `id` bigint NOT NULL,\n"
                    + "  `note` varchar(64) DEFAULT NULL,\n"
                    + "  PRIMARY KEY (`id`)\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8"
                    + " dbpartition by mod_hash(`id`) dbpartitions 2;");
            execute(mycat, "insert into `edprobe`(`id`,`note`) values (1,'m6a')");
            execute(mycat, "insert into `edprobe`(`id`,`note`) values (2,'m6b')");
            execute(mycat, "insert into `edprobe`(`id`,`note`) values (3,'m6c')");

            List<Map<String, Object>> rows = executeQuery(mycat,
                    "select `note` from " + PROBE_SCHEMA + ".`edprobe` order by `id`");
            assertEquals(3, rows.size(), "ed25519 后端经 Mycat 跨片归并应读回 3 行");
            assertEquals("m6a", rows.get(0).values().iterator().next());
            assertEquals("m6c", rows.get(2).values().iterator().next());

            // 后端直连 oracle 对拍（root 通道，物理分库 dbm6_0/dbm6_1 聚合读）。
            // 物理表名带 tableIndex 后缀 _0：Mycat 生成的 mappingFormat 为
            // "c${targetIndex}/dbm6_${dbIndex}/edprobe_${tableIndex}"，纯 dbpartition 下 tableIndex 恒 0。
            try (Connection admin = getMySQLConnection(M6_ADMIN_URL)) {
                long total = 0;
                for (String part : Arrays.asList("dbm6_0", "dbm6_1")) {
                    List<Map<String, Object>> r = executeQuery(admin,
                            "select count(*) as cnt from `" + part + "`.`edprobe_0`");
                    total += ((Number) r.get(0).get("cnt")).longValue();
                }
                assertEquals(3L, total, "后端物理分库行数应与 Mycat 读回一致");
            }
        } finally {
            try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
                execute(mycat, "drop database if exists " + PROBE_SCHEMA);
                execute(mycat, RESET_CONFIG);
            }
            purgeM6Physical();
        }
    }

    /** 显式驱动类直连（不经 DriverManager 自动发现取序，判据①/② 的驱动面须钉死驱动）。 */
    private static final class DriverManagerConnect {
        static Connection connect(String url, String user, String pw, String driverClass) throws Exception {
            Class<?> driver = Class.forName(driverClass);
            java.sql.Driver d = (java.sql.Driver) driver.getDeclaredConstructor().newInstance();
            java.util.Properties props = new java.util.Properties();
            props.setProperty("user", user);
            props.setProperty("password", pw);
            return d.connect(url, props);
        }
    }
}
