/*
 * MYCAT CHANGE (I-DEBT-F7) 离线锁：会话 sql_mode 向 JDBC 腿后端物理连接的借出时传导。
 *
 * 活体证据：P-B-06（audit/2026-11-08-capability-audit）——前端会话 SET sql_mode='' 后，
 * 截断 INSERT 在后端仍报 1406（Connector/J MysqlDataTruncation）：会话 sql_mode 止步
 * 于 mycat 内存层，JDBC 腿借出的物理连接从未收到 SET。
 *
 * 本锁用假 DataSource/假 Connection（动态代理，只记录发出的 SQL，不真连）驱动真实
 * 借出路径 JdbcConnectionManager.getConnection，断言：
 *   1. 会话借出窗口内借出、物理连接上次对齐值≠会话值 → 恰发一次 SET sql_mode='<会话值>'；
 *   2. 池复用同一物理连接且值一致 → 不重复发（连接级标记防重）；
 *   3. 值不同 → 重新对齐；
 *   4. 窗口外借出（心跳/元数据等无会话语义借出）→ 不发；
 *   5. 会话从未 SET（null）→ 不发；
 *   6. 非 mysql dbType（如 mariadb）→ 不发；
 *   7. 值内含引号/反斜杠 → 转义后不逃出字面量。
 * 纯离线，不建真池、不连真库。
 */
package io.mycat.datasource.jdbc.datasource;

import io.mycat.config.DatasourceConfig;
import io.mycat.config.ServerConfig;
import io.mycat.datasource.jdbc.DatasourceProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class SessionSqlModeAlignerBorrowLockTest {

    // ---------- 假件：记录 SQL 的 Connection / DataSource（模拟池复用返回同一物理连接） ----------

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0;
    }

    /** 假 DataSource：每次 getConnection 返回同一假连接，模拟池对同一物理连接的复用。 */
    private static javax.sql.DataSource newPooledFakeDataSource(Connection pooledConnection) {
        ClassLoader cl = SessionSqlModeAlignerBorrowLockTest.class.getClassLoader();
        return (javax.sql.DataSource) Proxy.newProxyInstance(cl, new Class<?>[]{javax.sql.DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("getConnection")) {
                        return pooledConnection;
                    }
                    return primitiveDefault(method.getReturnType());
                });
    }

    private static DatasourceConfig newConfig(String dbType) {
        DatasourceConfig config = new DatasourceConfig();
        config.setName("ds");
        if ("mysql".equals(dbType)) {
            config.setUrl("jdbc:mysql://127.0.0.1:3306/db1?useSSL=false");
        } else {
            config.setUrl("jdbc:mariadb://127.0.0.1:3306/db1");
            config.setDbType(dbType);
        }
        return config;
    }

    private static JdbcConnectionManager newManager(DatasourceConfig config, Connection pooledConnection) {
        JdbcDataSource fakeJdbcDataSource = new JdbcDataSource(config, newPooledFakeDataSource(pooledConnection));
        DatasourceProvider fakeProvider = new DatasourceProvider() {
            @Override
            public JdbcDataSource createDataSource(DatasourceConfig dataSource) {
                return fakeJdbcDataSource;
            }

            @Override
            public void closeDataSource(JdbcDataSource dataSource) {
            }

            @Override
            public void init(ServerConfig config) {
            }
        };
        Map<String, DatasourceConfig> datasources = new HashMap<>();
        datasources.put(config.getName(), config);
        return new JdbcConnectionManager(datasources, fakeProvider);
    }

    private static Connection newBoundRecordingConnection(List<String> executedSqls) {
        ClassLoader cl = SessionSqlModeAlignerBorrowLockTest.class.getClassLoader();
        Statement recordingStatement = (Statement) Proxy.newProxyInstance(cl, new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (("execute".equals(name) || "executeUpdate".equals(name) || "executeQuery".equals(name))
                            && args != null && args.length > 0 && args[0] instanceof String) {
                        executedSqls.add((String) args[0]);
                        return "executeQuery".equals(name) ? null : ("execute".equals(name) ? Boolean.TRUE : 0);
                    }
                    return primitiveDefault(method.getReturnType());
                });
        InvocationHandler handler = (proxy, method, args) -> {
            if ("createStatement".equals(method.getName())) {
                return recordingStatement;
            }
            return primitiveDefault(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class}, handler);
    }

    private static List<String> setSqlModes(List<String> sqls) {
        List<String> res = new ArrayList<>();
        for (String sql : sqls) {
            if (sql != null && sql.toUpperCase().startsWith("SET SQL_MODE")) {
                res.add(sql);
            }
        }
        return res;
    }

    // ---------- 锁本体 ----------

    @Test
    void sessionSqlModeEmptyAlignsExactlyOnceOnBorrow() throws Exception {
        List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());
        Connection pooled = newBoundRecordingConnection(executedSqls);
        DatasourceConfig config = newConfig("mysql");
        JdbcConnectionManager manager = newManager(config, pooled);
        // 会话 SET sql_mode=''（合法值：清空全部模式）→ 首次借出必须恰好发一次 SET
        SessionSqlModeAligner.beginSessionBorrow("");
        try {
            manager.getConnection("ds");
            manager.getConnection("ds");
        } finally {
            SessionSqlModeAligner.endSessionBorrow();
        }
        List<String> sets = setSqlModes(executedSqls);
        Assertions.assertEquals(Collections.singletonList("SET sql_mode=''"), sets,
                "借出时必须按会话值对齐，且同一物理连接复用不得重复 SET");
    }

    @Test
    void differentSessionSqlModeRealigns() throws Exception {
        List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());
        Connection pooled = newBoundRecordingConnection(executedSqls);
        DatasourceConfig config = newConfig("mysql");
        JdbcConnectionManager manager = newManager(config, pooled);
        SessionSqlModeAligner.beginSessionBorrow("");
        try {
            manager.getConnection("ds");
        } finally {
            SessionSqlModeAligner.endSessionBorrow();
        }
        SessionSqlModeAligner.beginSessionBorrow("ANSI_QUOTES");
        try {
            manager.getConnection("ds");
        } finally {
            SessionSqlModeAligner.endSessionBorrow();
        }
        List<String> sets = setSqlModes(executedSqls);
        Assertions.assertEquals(java.util.Arrays.asList("SET sql_mode=''", "SET sql_mode='ANSI_QUOTES'"), sets,
                "会话值变化必须重新对齐该物理连接");
    }

    @Test
    void borrowWithoutSessionCarrierEmitsNothing() throws Exception {
        List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());
        Connection pooled = newBoundRecordingConnection(executedSqls);
        DatasourceConfig config = newConfig("mysql");
        JdbcConnectionManager manager = newManager(config, pooled);
        // 不开借出窗口（心跳/元数据等借出形态）
        manager.getConnection("ds");
        Assertions.assertTrue(setSqlModes(executedSqls).isEmpty(),
                "无会话语义的借出不得触碰后端 sql_mode");
    }

    @Test
    void sessionNeverSetEmitsNothing() throws Exception {
        List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());
        Connection pooled = newBoundRecordingConnection(executedSqls);
        DatasourceConfig config = newConfig("mysql");
        JdbcConnectionManager manager = newManager(config, pooled);
        SessionSqlModeAligner.beginSessionBorrow(null);
        try {
            manager.getConnection("ds");
        } finally {
            SessionSqlModeAligner.endSessionBorrow();
        }
        Assertions.assertTrue(setSqlModes(executedSqls).isEmpty(),
                "会话从未 SET sql_mode 时存量行为必须零变化");
    }

    @Test
    void nonMysqlDatasourceEmitsNothing() throws Exception {
        List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());
        Connection pooled = newBoundRecordingConnection(executedSqls);
        DatasourceConfig config = newConfig("mariadb");
        JdbcConnectionManager manager = newManager(config, pooled);
        SessionSqlModeAligner.beginSessionBorrow("");
        try {
            manager.getConnection("ds");
        } finally {
            SessionSqlModeAligner.endSessionBorrow();
        }
        Assertions.assertTrue(setSqlModes(executedSqls).isEmpty(),
                "非 mysql dbType 的 JDBC 后端不下发 sql_mode");
    }

    @Test
    void literalEscapingNeverBreaksOutOfQuotedValue() throws Exception {
        List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());
        Connection pooled = newBoundRecordingConnection(executedSqls);
        DatasourceConfig config = newConfig("mysql");
        JdbcConnectionManager manager = newManager(config, pooled);
        SessionSqlModeAligner.beginSessionBorrow("A'B\\C");
        try {
            manager.getConnection("ds");
        } finally {
            SessionSqlModeAligner.endSessionBorrow();
        }
        Assertions.assertEquals(Collections.singletonList("SET sql_mode='A''B\\\\C'"), setSqlModes(executedSqls),
                "值内引号/反斜杠必须转义，不得逃出字面量");
    }

    @Test
    void carrierMustNotLeakAcrossBorrowWindows() throws Exception {
        // 窗口纪律：endSessionBorrow 后同线程再借出（心跳形态）不得残留上一窗口的会话值
        List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());
        Connection pooled = newBoundRecordingConnection(executedSqls);
        DatasourceConfig config = newConfig("mysql");
        JdbcConnectionManager manager = newManager(config, pooled);
        SessionSqlModeAligner.beginSessionBorrow("");
        try {
            manager.getConnection("ds");
        } finally {
            SessionSqlModeAligner.endSessionBorrow();
        }
        manager.getConnection("ds");
        List<String> sets = setSqlModes(executedSqls);
        Assertions.assertEquals(1, sets.size(), "窗口结束后载体必须清零，后续借出不得残留对齐");
    }
}
