package io.mycat.compat;

import io.mycat.assemble.MycatTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M1 兼容性冒烟测试：验证"MySQL/MariaDB 客户端眼中的 L1 连接是否达标"。
 * 不断言分片 CRUD（那由现有 example 测试覆盖），只钉住连接层最小面：
 * 连上 Mycat(8066) -> SELECT 1 -> SHOW DATABASES 非空。
 * 版本来自 -Dcompat.db（CI 矩阵注入），仅用于报告标识。
 */
public class ConnectionSmokeTest implements MycatTest {

    @Test
    public void connectAndBasicRoundTrip() throws Exception {
        String db = System.getProperty("compat.db", "unknown");
        System.out.println("[compat-smoke] target db = " + db + ", mycat url = " + DB_MYCAT);

        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            assertNotNull(mycat, "无法连上 Mycat 前端");

            List<Map<String, Object>> one = executeQuery(mycat, "SELECT 1");
            assertFalse(one.isEmpty(), "SELECT 1 无结果");

            List<Map<String, Object>> dbs = executeQuery(mycat, "SHOW DATABASES");
            assertFalse(dbs.isEmpty(), "SHOW DATABASES 返回空 —— 前端握手/元数据未通");
        }
    }

    /**
     * I-DEBT-57 回归锁：纯注释/无语句查询（含 mariadb-dump 下发的 {@code /*M!100100 ... *}{@code /}
     * MariaDB 条件注释，以及任意 {@code /* ... *}{@code /}）必须像真 MySQL 一样返回 Query OK，
     * 而非抛 1105 Illegal syntax（此前真二进制 dump 链 11.4 档卡点）。同时守卫：真·非法 SQL
     * 仍必须报错——放行空语句表不得吞掉 ParserException 类的真实语法错误。
     * 本类在 CI compat 矩阵全 7 persona 档跑，故逐档统一钉住该行为。
     */
    @Test
    public void commentOnlyQueryReturnsOkNotSyntaxError() throws Exception {
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            assertNotNull(mycat, "无法连上 Mycat 前端");
            // 纯注释/无语句：真 MySQL 返回 OK，Mycat 不得抛（execute() 走 Statement.execute，不剥注释、原样发）
            execute(mycat, "/*M!100100 SET @@MAX_STATEMENT_TIME=0.000000 */");
            execute(mycat, "/* just a comment */");
            execute(mycat, "/*!40101 SET @@SQL_MODE='' */");

            // 守卫：真实语法错误必须仍抛，不被空语句放行策略掩盖
            assertThrows(SQLException.class,
                    () -> execute(mycat, "SELEC bad syntax zzz"),
                    "真·非法 SQL 必须仍抛，不能被空语句放行吞掉");
            assertThrows(SQLException.class,
                    () -> execute(mycat, "/*M!100100 */ SELECT FROM WHERE"),
                    "注释前缀不得掩盖真实语法错误");
        }
    }
}
