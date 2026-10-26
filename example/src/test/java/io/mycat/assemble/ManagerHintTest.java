package io.mycat.assemble;

import org.junit.Assert;
import org.junit.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

@NotThreadSafe
@net.jcip.annotations.NotThreadSafe
public class ManagerHintTest implements MycatTest {

    @Test
    public void testShowBufferUsage() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            execute(mycatConnection,RESET_CONFIG);
            // 走 helper executeQuery（内部 Statement.execute()+getResultSet()），与同类其余 hint 查询一致。
            // 原裸 statement.executeQuery("/*+ mycat:... */") 会被 Connector/J 发送前静态分类守卫拒
            // （"cannot issue statements that do not produce result sets"）。断言保持中性：原方法无断言。
            executeQuery(mycatConnection, "/*+ mycat:showBufferUsage{}*/");
        }
    }

    @Test
    public void testShowUsers() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            execute(mycatConnection,RESET_CONFIG);
            List<Map<String, Object>> maps = executeQuery(mycatConnection,
                    "/*+ mycat:showUsers{}*/");
            Assert.assertTrue(!maps.isEmpty());
        }
    }

    @Test
    public void testShowSchemas() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            execute(mycatConnection,RESET_CONFIG);
            Assert.assertTrue(
                    executeQuery(mycatConnection,
                            "/*+ mycat:showSchemas{}*/").size() > 2);
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showSchemas{\"schemaName\":\"mysql\"}*/").size() == 1);
        }
    }

    @Test
    public void testShowSchedules() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
                    executeQuery(mycatConnection,
                            "/*+ mycat:showSchedules{}*/");
        }
    }

    @Test
    public void testShowClusters() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showClusters{}*/").size() > 0);
        }
    }

    @Test
    public void testShowDatasources() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showDatasources{}*/").size() > 0);
        }
    }

    @Test
    public void testShowHeartbeats() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showHeartbeats{}*/").size() > 0);
        }
    }

    @Test
    public void testShowHeartbeatStatus() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showHeartbeatStatus{}*/").size() > 0);
        }
    }

    @Test
    public void testShowInstances() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showInstances{}*/").size() > 0);
        }
    }

    @Test
    public void testShowReactors() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showReactors{}*/").size() > 0);
        }
    }

    @Test
    public void testShowThreadPools() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showThreadPools{}*/").size() >= 0);
        }
    }

    @Test
    public void testShowTables() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            execute(mycatConnection,RESET_CONFIG);
            Assert.assertTrue(executeQuery(mycatConnection,
                            "/*+ mycat:showTables{}*/").size() > 2);
            Assert.assertTrue(!executeQuery(mycatConnection,
                            "/*+ mycat:showTables{\"schemaName\":\"mysql\"}*/").isEmpty());
        }
    }

    @Test
    public void testShowConnections() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            executeQuery(mycatConnection,
                            "/*+ mycat:showConnections{}*/");
        }
    }

    /**
     * I-DEBT-57 口径订正（2026-10-26）：原预期 SQLException 钉的是 Mycat 旧错行为——纯注释语句
     * （含非 Mycat hint 形或不可识别内容）被当空语句报 1064 syntax error。真 MySQL 8.0 直连实证
     * （docker mysql:8.0）：`/* … *​/;` 与 `/* only a comment *​/` 均安静回 OK（rc=0），后续语句照常执行。
     * Mycat 已随 I-DEBT-57（`MycatdbCommand` statements.isEmpty→sendOk，回归锁
     * `ConnectionSmokeTest#commentOnlyQueryReturnsOkNotSyntaxError`）对齐该语义，本例旧预期即过时红
     * （“Expected exception but nothing thrown”）。改按真库语义正向断言：OK 且连接存活可续查。
     */
    @Test
    public void testCommentOnlyStatementReturnsOkNotSyntaxError() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            execute(mycatConnection, "/* mycat:showConnections{}*/");
            // 连接存活探针：旧病（1064/断连）下此步必炸
            List<Map<String, Object>> rows = executeQuery(mycatConnection, "select 1 as alive");
            Assert.assertEquals(1, ((Number) rows.get(0).values().iterator().next()).intValue());
        }
    }

}
