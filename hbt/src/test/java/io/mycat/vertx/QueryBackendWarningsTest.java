package io.mycat.vertx;

import io.mycat.beans.mycat.WarningMessage;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.NewMycatConnectionImpl;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-69 Blade D 余项（Query 路径）：后端 SELECT 产生的诊断条件必须随结果流回传，
 * 且在结果流结束（onComplete）之前送达，否则 SHOW WARNINGS 与 OK/EOF 的 warning 计数都为空。
 *
 * <p>用 JDBC 动态代理构造"后端语句带告警链"的最小场景，不连库、不 mock 被测代码。
 */
public class QueryBackendWarningsTest {

    @Test
    public void plainStatementWarningsReachSinkBeforeCompletion() {
        SQLWarning chain = new SQLWarning("Division by 0", "01000", 1365);
        chain.setNextWarning(new SQLWarning("Data truncated for column 'a' at row 1", "01000", 1265));
        assertWarningsReachSink(connectionReturning(chain), Collections.emptyList(), List.of(
                new WarningMessage("Warning", 1365, "Division by 0"),
                new WarningMessage("Warning", 1265, "Data truncated for column 'a' at row 1")));
    }

    @Test
    public void preparedStatementWarningsReachSinkBeforeCompletion() {
        SQLWarning chain = new SQLWarning("Division by 0", "01000", 1365);
        assertWarningsReachSink(connectionReturning(chain), Collections.singletonList(1), List.of(
                new WarningMessage("Warning", 1365, "Division by 0")));
    }

    @Test
    public void noBackendWarningsYieldEmptyList() {
        assertWarningsReachSink(connectionReturning(null), Collections.emptyList(), Collections.emptyList());
    }

    private static void assertWarningsReachSink(Connection connection,
                                                List<Object> params,
                                                List<WarningMessage> expected) {
        NewMycatConnection mycatConnection = new NewMycatConnectionImpl("ds0", false, connection);
        List<WarningMessage> received = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean deliveredBeforeCompletion = new AtomicBoolean(false);

        VertxExecuter.runQueryOutputAsMysqlPayloadObject(
                        Future.succeededFuture(mycatConnection), "select 1", params, warnings -> {
                            deliveredBeforeCompletion.set(!completed.get());
                            received.addAll(warnings);
                        })
                .doOnComplete(() -> completed.set(true))
                .test()
                .assertComplete();

        assertEquals(expected, received);
        assertTrue(deliveredBeforeCompletion.get(), "告警必须在结果流结束前送达，否则 EOF/OK 的 warning 计数取不到");
    }

    private static Connection connectionReturning(SQLWarning warning) {
        return (Connection) Proxy.newProxyInstance(QueryBackendWarningsTest.class.getClassLoader(),
                new Class[]{Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "createStatement":
                            return statement(Statement.class, warning);
                        case "prepareStatement":
                            return statement(PreparedStatement.class, warning);
                        case "toString":
                            return "fake-connection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object statement(Class<?> statementType, SQLWarning warning) {
        ResultSet resultSet = resultSet();
        return Proxy.newProxyInstance(QueryBackendWarningsTest.class.getClassLoader(),
                new Class[]{statementType}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "executeQuery":
                        case "execute":
                            return resultSet;
                        case "getWarnings":
                            return warning;
                        case "toString":
                            return "fake-statement";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "close":
                        case "setFetchSize":
                        case "setObject":
                        case "setMaxRows":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static ResultSet resultSet() {
        ResultSetMetaData metaData = metaData();
        return (ResultSet) Proxy.newProxyInstance(QueryBackendWarningsTest.class.getClassLoader(),
                new Class[]{ResultSet.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMetaData":
                            return metaData;
                        case "next":
                            return false;
                        case "isClosed":
                            return false;
                        case "getObject":
                            return null;
                        case "toString":
                            return "fake-result-set";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "close":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static ResultSetMetaData metaData() {
        return (ResultSetMetaData) Proxy.newProxyInstance(QueryBackendWarningsTest.class.getClassLoader(),
                new Class[]{ResultSetMetaData.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getColumnCount":
                            return 1;
                        case "getColumnLabel":
                        case "getColumnName":
                            return "c";
                        case "toString":
                            return "fake-meta-data";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        return null;
    }
}
