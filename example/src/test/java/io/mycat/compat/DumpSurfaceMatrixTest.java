package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.abort;

/**
 * I-ME-2 · mysqldump 查询面 e2e 矩阵格（建格半区；进程内列覆盖锁见 hbt {@code DumpSurfaceRegressionTest}）。
 *
 * <p>回放清单逐条锚定 <b>官网 mysqldump 源码</b>（非臆测）：
 * MySQL 8.0 {@code client/mysqldump.cc}（github mysql/mysql-server 8.0）——
 * 会话面 {@code SET SESSION information_schema_stats_expiry=0}(1727)、
 * {@code select @@collation_database}(1259)、{@code SET SESSION character_set_results}(1418)、
 * {@code SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ}+{@code START TRANSACTION
 * <span>&#42;</span>!40100 WITH CONSISTENT SNAPSHOT<span>&#42;</span>}(5386-5390)；
 * 发现面 {@code SHOW DATABASES}(4567)、{@code SHOW CREATE DATABASE IF NOT EXISTS}(4699)、
 * {@code SHOW TABLES LIKE}(4996)、{@code show table status like}(3338)；
 * 结构面 {@code show create table}(2852)、{@code SHOW FIELDS FROM}(2914)、
 * 回退列查询 {@code SELECT ... FROM `INFORMATION_SCHEMA`.`COLUMNS`}(2790-2807)；
 * 对象面 {@code SHOW TRIGGERS LIKE}(3557)、{@code SHOW EVENTS}(2379)、
 * {@code SHOW FUNCTION/PROCEDURE STATUS WHERE Db}(2588)、{@code SHOW VARIABLES LIKE}(4534)；
 * dump 选项面 {@code information_schema.views}(5882)、{@code COLUMN_STATISTICS}(3652)、
 * {@code INFORMATION_SCHEMA.FILES}(4363-4462)、{@code INFORMATION_SCHEMA.PARTITIONS}(4287)、
 * {@code SHOW MASTER STATUS}(5160)。
 * MariaDB 11.4 差异面（{@code client/mysqldump.cc} MariaDB/server 11.4）——
 * {@code SELECT ... FROM INFORMATION_SCHEMA.TRIGGERS}(3914)、
 * {@code SELECT table_name, table_type FROM INFORMATION_SCHEMA.TABLES}(4672)、
 * {@code SHOW FULL TABLES}(4675)、生成列列查询 {@code information_schema.columns}(3383)。
 *
 * <p><b>判据</b>：mysqldump 对 server 错误是致命的（mysql_query_with_error_report → abort dump），
 * 故每格 = "该语句经完整 Mycat 执行不报错且结果集可完整消费"。列覆盖正确性由进程内锁已钉，
 * 本格补 e2e：真实握手/路由/执行链上这些查询是否兑现。
 *
 * <p><b>激活条件（当前 NO_DATA，绝不假绿）</b>：三层诚实门控同 {@link TlsAxisMatrixTest}——
 * 8066 无 Mycat（或后端 3306 未起）→ {@link #assumeInfraUp()} abort 记 <b>NO_DATA</b>；
 * 基础设施在 → 真回放，任一查询报错 = 真回归 FAIL。真 dump 二进制的往返回灌
 * （spec I-ME-2 Done 门"mysqldump 产物能回灌原生 MySQL"）属 run-matrix infra 专轮，
 * 见 {@code 2026-10-13-tls-compat-matrix.md} 同级的 provision 清单。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DumpSurfaceMatrixTest implements MycatTest {

    private static final String PLAIN_URL = "jdbc:mysql://127.0.0.1:8066/mysql"
            + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true"
            + "&connectTimeout=3000&socketTimeout=15000&sslMode=DISABLED";
    private static final String USER = "root";
    private static final String PW = "123456";
    private static final String SCHEMA = "dbdump";

    /** infra 探测失败时置位：各 @Test 内 abort 记 NO_DATA（每格都须在报告里响，不得 @BeforeAll 静默 0 tests）。 */
    private static boolean infraDown = false;

    @BeforeAll
    public void prepareSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            // 探测即可
        } catch (Exception e) {
            infraDown = true;
            System.out.println("[NO_DATA] 8066 无 Mycat 基础设施 → dump 面矩阵格待 run-matrix 起 mycat+后端：" + e.getMessage());
            return;
        }
        try (Connection c = getMySQLConnection(DB_MYCAT)) {
            execute(c, RESET_CONFIG);
            execute(c, "drop database if exists " + SCHEMA);
            execute(c, "create database " + SCHEMA);
            execute(c, "use " + SCHEMA);
            execute(c, "create table t1 (id int not null primary key, name varchar(64), "
                    + "amount decimal(10,2) default null, ts timestamp null, gen int as (id * 2), "
                    + "key idx_name(name))");
            execute(c, "insert into t1 (id, name, amount) values (1, 'a', 10.5), (2, 'b', 20.75)");
        }
    }

    @AfterAll
    public void purgeSchema() throws Exception {
        try (Connection c = getMySQLConnection(DB_MYCAT)) {
            execute(c, "drop database if exists " + SCHEMA);
            execute(c, RESET_CONFIG);
        } catch (Exception ignored) {
            // infra 未起时 @BeforeAll 已 abort，清理尽力而为
        }
    }

    /** infra 不在 → 每格各自 abort 记 NO_DATA（surefire 报告呈 skipped+原因，须响不得静默）；schema 准备阶段真失败不受此门控吞掉。 */
    private void assumeInfraUp() {
        if (infraDown) {
            abort("8066 无 Mycat 基础设施 → dump 面矩阵格 NO_DATA（待 run-matrix 起 mycat+后端）");
        }
    }

    /** 逐条回放：以 mysqldump 客户端语义（错误=致命），任何 SQLException 即本格 FAIL。 */
    private void replayDumpQuery(Connection c, String sql, String sourceAnchor) {
        try (java.sql.Statement st = c.createStatement()) {
            st.execute(sql);
            // 结果集必须完整消费（mysqldump 全部 mysql_store_result）
            try (java.sql.ResultSet rs = st.getResultSet()) {
                if (rs != null) {
                    while (rs.next()) {
                        rs.getObject(1);
                    }
                }
            }
        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail(
                    "mysqldump 面查询回放失败 [" + sourceAnchor + "]: " + sql + " -> " + e.getMessage(), e);
        }
    }

    @Test
    public void mysql80DumpSessionAndDiscoverySurface() throws Exception {
        assumeInfraUp();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            List<String[]> surface = new ArrayList<>();
            surface.add(new String[]{"SET SESSION information_schema_stats_expiry=0", "mysqldump80.cc:1727"});
            surface.add(new String[]{"select @@collation_database", "mysqldump80.cc:1259"});
            surface.add(new String[]{"SET SESSION character_set_results = 'binary'", "mysqldump80.cc:1418"});
            surface.add(new String[]{"SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ", "mysqldump80.cc:5386"});
            surface.add(new String[]{"START TRANSACTION /*!40100 WITH CONSISTENT SNAPSHOT */", "mysqldump80.cc:5390"});
            surface.add(new String[]{"SHOW DATABASES", "mysqldump80.cc:4567"});
            surface.add(new String[]{"SHOW VARIABLES LIKE 'version'", "mysqldump80.cc:4534"});
            for (String[] q : surface) {
                replayDumpQuery(c, q[0], q[1]);
            }
        }
    }

    @Test
    public void mysql80DumpStructureSurface() throws Exception {
        assumeInfraUp();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            execute(c, "use " + SCHEMA);
            List<String[]> surface = new ArrayList<>();
            surface.add(new String[]{"SHOW CREATE DATABASE IF NOT EXISTS `" + SCHEMA + "`", "mysqldump80.cc:4699"});
            surface.add(new String[]{"SHOW TABLES LIKE 't1'", "mysqldump80.cc:4996"});
            surface.add(new String[]{"SHOW FULL TABLES", "mariadb-dump114.cc:4675"});
            surface.add(new String[]{"show table status like 't1'", "mysqldump80.cc:3338"});
            surface.add(new String[]{"show create table `" + SCHEMA + "`.`t1`", "mysqldump80.cc:2852"});
            surface.add(new String[]{"SHOW FIELDS FROM `" + SCHEMA + "`.`t1`", "mysqldump80.cc:2914"});
            surface.add(new String[]{"SELECT `COLUMN_NAME` AS `Field`, `COLUMN_TYPE` AS `Type`, `IS_NULLABLE` AS `Null`, "
                    + "`COLUMN_KEY` AS `Key`, `COLUMN_DEFAULT` AS `Default`, `EXTRA` AS `Extra`, `COLUMN_COMMENT` AS `Comment` "
                    + "FROM `INFORMATION_SCHEMA`.`COLUMNS` WHERE TABLE_SCHEMA = '" + SCHEMA + "' AND TABLE_NAME = 't1' "
                    + "ORDER BY ORDINAL_POSITION", "mysqldump80.cc:2790-2807"});
            surface.add(new String[]{"select column_name, extra, generation_expression, data_type "
                    + "from information_schema.columns where table_schema='" + SCHEMA + "' and table_name='t1' "
                    + "order by ordinal_position", "mariadb-dump114.cc:3383"});
            for (String[] q : surface) {
                replayDumpQuery(c, q[0], q[1]);
            }
        }
    }

    @Test
    public void mysql80DumpObjectAndOptionsSurface() throws Exception {
        assumeInfraUp();
        try (Connection c = DriverManager.getConnection(PLAIN_URL, USER, PW)) {
            execute(c, "use " + SCHEMA);
            List<String[]> surface = new ArrayList<>();
            surface.add(new String[]{"SHOW TRIGGERS LIKE 't1'", "mysqldump80.cc:3557"});
            surface.add(new String[]{"SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = '" + SCHEMA + "'", "mariadb-dump114.cc:3914"});
            surface.add(new String[]{"SHOW EVENTS", "mysqldump80.cc:2379"});
            surface.add(new String[]{"SHOW FUNCTION STATUS WHERE Db = '" + SCHEMA + "'", "mysqldump80.cc:2588"});
            surface.add(new String[]{"SHOW PROCEDURE STATUS WHERE Db = '" + SCHEMA + "'", "mysqldump80.cc:2588"});
            surface.add(new String[]{"SELECT CHECK_OPTION, DEFINER, SECURITY_TYPE, CHARACTER_SET_CLIENT, COLLATION_CONNECTION "
                    + "FROM information_schema.views WHERE table_name='v_none' AND table_schema='" + SCHEMA + "'", "mysqldump80.cc:5882"});
            surface.add(new String[]{"SELECT SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, HISTOGRAM FROM information_schema.COLUMN_STATISTICS "
                    + "WHERE SCHEMA_NAME = '" + SCHEMA + "'", "mysqldump80.cc:3652"});
            surface.add(new String[]{"SELECT DISTINCT TABLESPACE_NAME FROM INFORMATION_SCHEMA.PARTITIONS "
                    + "WHERE TABLESPACE_NAME IS NOT NULL AND TABLE_SCHEMA = '" + SCHEMA + "'", "mysqldump80.cc:4287"});
            surface.add(new String[]{"SELECT FILE_NAME, FILE_TYPE, LOGFILE_GROUP_NAME, TABLESPACE_NAME, ENGINE, "
                    + "TOTAL_EXTENTS, INITIAL_SIZE, MAXIMUM_SIZE, AUTOEXTEND_SIZE FROM INFORMATION_SCHEMA.FILES "
                    + "WHERE FILE_TYPE = 'DATAFILE'", "mysqldump80.cc:4455"});
            surface.add(new String[]{"select 1 from information_schema.tables where table_schema='" + SCHEMA + "'"
                    + " and table_name='t1' and table_type='SYSTEM VERSIONED'", "mariadb-dump114.cc:3157"});
            surface.add(new String[]{"SELECT table_name, table_type FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='" + SCHEMA + "'", "mariadb-dump114.cc:4672"});
            surface.add(new String[]{"SHOW MASTER STATUS", "mysqldump80.cc:5160（--source-data 轴，I-ME-4 判据复用本格）"});
            for (String[] q : surface) {
                replayDumpQuery(c, q[0], q[1]);
            }
        }
    }
}
