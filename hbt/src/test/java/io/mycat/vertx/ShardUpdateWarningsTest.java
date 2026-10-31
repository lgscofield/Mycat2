package io.mycat.vertx;

import cn.mycat.vertx.xa.XaSqlConnection;
import io.mycat.MycatDataContext;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.SqlResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-69 Blade D 余项（分片 DML 路径）：一条 INSERT/UPDATE 被拆到多个分片执行时，
 * 各分片后端产生的诊断条件都必须回传，并按 EachSQL 的原始顺序进入前端会话缓冲。
 *
 * <p>时序契约：告警必须在 {@code simpleUpdate} 返回的 future 完成之前写入缓冲。生产调用侧是
 * {@code simpleUpdate(...).eventually(closeStatementState).flatMap(result -> response.sendOk(...))}，
 * sendOk 严格下游于该 future，所以"future 完成时缓冲已就位"就等价于"OK 包的 warning 计数取得到"。
 *
 * <p>顺序契约：分片是并行下发的，完成次序不确定，因此缓冲顺序必须是 SQL 顺序而非完成顺序。
 *
 * <p>用 JDK 动态代理构造会话/事务/后端连接，不连库、不 mock 被测代码。
 */
public class ShardUpdateWarningsTest {

    private static final WarningMessage TRUNCATED_ROW1 =
            new WarningMessage("Warning", 1265, "Data truncated for column 'a' at row 1");
    private static final WarningMessage TRUNCATED_ROW2 =
            new WarningMessage("Warning", 1265, "Data truncated for column 'a' at row 2");

    /** 两个分片各带回一条告警：缓冲应有 2 条，且在 future 完成的观测点就已可见。 */
    @Test
    public void warningsFromEachShardAreBufferedBeforeReturnedFutureCompletes() throws Exception {
        List<VertxExecuter.EachSQL> eachSQLs = List.of(
                new VertxExecuter.EachSQL("db1_0", "insert ignore into wlk_1 values(1,'abcdef')", Collections.emptyList()),
                new VertxExecuter.EachSQL("db1_1", "insert ignore into wlk_2 values(2,'ghijkl')", Collections.emptyList()));
        Harness harness = new Harness(eachSQLs, List.of(
                SqlResult.of(1, 0, List.of(TRUNCATED_ROW1)),
                SqlResult.of(1, 0, List.of(TRUNCATED_ROW2))));

        List<WarningMessage> snapshot = new ArrayList<>();
        Future<long[]> future = VertxExecuter.simpleUpdate(harness.context, true, true, false, eachSQLs);
        // 先挂观测点，再放行后端：若告警是在 future 完成之后才写入，这里就取不到。
        future.onSuccess(longs -> snapshot.addAll(harness.buffered));
        harness.completeAll();
        long[] sum = awaitResult(future);

        assertEquals(List.of(TRUNCATED_ROW1, TRUNCATED_ROW2), snapshot);
        assertEquals(2L, sum[0], "分片影响行数累加不得被本次改动破坏");
    }

    /** 后发先至：第二个分片先完成，缓冲顺序仍须是 EachSQL 顺序。 */
    @Test
    public void lateShardCompletionStillKeepsSqlOrder() throws Exception {
        List<VertxExecuter.EachSQL> eachSQLs = List.of(
                new VertxExecuter.EachSQL("db1_0", "insert ignore into wlk_1 values(1,'abcdef')", Collections.emptyList()),
                new VertxExecuter.EachSQL("db1_1", "insert ignore into wlk_2 values(2,'ghijkl')", Collections.emptyList()));
        Harness harness = new Harness(eachSQLs, List.of(
                SqlResult.of(1, 0, List.of(TRUNCATED_ROW1)),
                SqlResult.of(1, 0, List.of(TRUNCATED_ROW2))));

        List<WarningMessage> snapshot = new ArrayList<>();
        Future<long[]> future = VertxExecuter.simpleUpdate(harness.context, true, true, false, eachSQLs);
        future.onSuccess(longs -> snapshot.addAll(harness.buffered));
        harness.complete(1);
        harness.complete(0);
        awaitResult(future);

        assertEquals(List.of(TRUNCATED_ROW1, TRUNCATED_ROW2), snapshot,
                "告警顺序必须随 EachSQL 顺序，不得随分片完成顺序");
    }

    /** 同一数据源上的多个分片（同 target、多条语句）也要逐条回传。 */
    @Test
    public void multipleShardsOnSameTargetAreAllCollectedInOrder() throws Exception {
        List<VertxExecuter.EachSQL> eachSQLs = List.of(
                new VertxExecuter.EachSQL("ds0", "insert ignore into wlk_0 values(1,'abcdef')", Collections.emptyList()),
                new VertxExecuter.EachSQL("ds0", "insert ignore into wlk_1 values(2,'ghijkl')", Collections.emptyList()));
        Harness harness = new Harness(eachSQLs, List.of(
                SqlResult.of(1, 0, List.of(TRUNCATED_ROW1)),
                SqlResult.of(1, 0, List.of(TRUNCATED_ROW2))));

        List<WarningMessage> snapshot = new ArrayList<>();
        Future<long[]> future = VertxExecuter.simpleUpdate(harness.context, true, true, false, eachSQLs);
        future.onSuccess(longs -> snapshot.addAll(harness.buffered));
        harness.completeAll();
        awaitResult(future);

        assertEquals(List.of(TRUNCATED_ROW1, TRUNCATED_ROW2), snapshot);
        assertEquals(List.of("insert ignore into wlk_0 values(1,'abcdef')",
                        "insert ignore into wlk_1 values(2,'ghijkl')"),
                harness.issuedSqls.get("ds0"), "同 target 复用连接须按序下发每条分片语句");
    }

    /** UPDATE 路径（走 c.update 而非 c.insert）同样要回传。 */
    @Test
    public void updatePathBuffersWarningsToo() throws Exception {
        List<VertxExecuter.EachSQL> eachSQLs = List.of(
                new VertxExecuter.EachSQL("db1_0", "update wlk_1 set a='abcdef' where id=1", Collections.emptyList()));
        Harness harness = new Harness(eachSQLs, List.of(SqlResult.of(1, 0, List.of(TRUNCATED_ROW1))));

        List<WarningMessage> snapshot = new ArrayList<>();
        Future<long[]> future = VertxExecuter.simpleUpdate(harness.context, false, true, false, eachSQLs);
        future.onSuccess(longs -> snapshot.addAll(harness.buffered));
        harness.completeAll();
        awaitResult(future);

        assertEquals(List.of(TRUNCATED_ROW1), snapshot);
    }

    /** 后端无告警时不得凭空追加。 */
    @Test
    public void shardsWithoutWarningsAddNothingToSession() throws Exception {
        List<VertxExecuter.EachSQL> eachSQLs = List.of(
                new VertxExecuter.EachSQL("db1_0", "insert into wlk_1 values(1,'abc')", Collections.emptyList()),
                new VertxExecuter.EachSQL("db1_1", "insert into wlk_2 values(2,'ghi')", Collections.emptyList()));
        Harness harness = new Harness(eachSQLs, List.of(SqlResult.of(1, 0), SqlResult.of(1, 0)));

        List<WarningMessage> snapshot = new ArrayList<>();
        Future<long[]> future = VertxExecuter.simpleUpdate(harness.context, true, true, false, eachSQLs);
        future.onSuccess(longs -> snapshot.addAll(harness.buffered));
        harness.completeAll();
        awaitResult(future);

        assertTrue(snapshot.isEmpty(), "无告警时不得写入会话缓冲：" + snapshot);
    }

    private static long[] awaitResult(Future<long[]> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /**
     * 测试夹具：把每个 EachSQL 的后端结果做成可控 {@link Promise}，按 EachSQL 出现顺序
     * 排队到所属 target 的连接上，并把会话告警缓冲收在内存里。
     */
    private static final class Harness {

        final List<WarningMessage> buffered = Collections.synchronizedList(new ArrayList<>());
        final Map<String, List<String>> issuedSqls = new LinkedHashMap<>();
        private final List<Promise<SqlResult>> promises = new ArrayList<>();
        private final List<SqlResult> results;
        final MycatDataContext context;

        Harness(List<VertxExecuter.EachSQL> eachSQLs, List<SqlResult> results) {
            this.results = results;
            Map<String, Deque<Future<SqlResult>>> queues = new LinkedHashMap<>();
            for (int i = 0; i < eachSQLs.size(); i++) {
                Promise<SqlResult> promise = Promise.promise();
                promises.add(promise);
                queues.computeIfAbsent(eachSQLs.get(i).getTarget(), t -> new ArrayDeque<>()).add(promise.future());
            }
            Map<String, NewMycatConnection> connections = new LinkedHashMap<>();
            for (String target : queues.keySet()) {
                Deque<Future<SqlResult>> queue = queues.get(target);
                connections.put(target, (NewMycatConnection) Proxy.newProxyInstance(
                        ShardUpdateWarningsTest.class.getClassLoader(), new Class[]{NewMycatConnection.class},
                        (proxy, method, args) -> dispatchConnection(target, method, args, queue)));
            }
            // 生产里同一对象既是 TransactionSession 又是 XaSqlConnection（simpleUpdate 会向下转型），
            // 夹具必须同时实现两者，否则调用点 checkcast 直接炸。
            XaSqlConnection session = (XaSqlConnection) Proxy.newProxyInstance(
                    ShardUpdateWarningsTest.class.getClassLoader(),
                    new Class[]{XaSqlConnection.class, io.mycat.TransactionSession.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getConnection":
                                return Future.succeededFuture(connections.get((String) args[0]));
                            case "begin":
                            case "commit":
                            case "rollback":
                            case "openStatementState":
                            case "closeStatementState":
                            case "close":
                                return Future.succeededFuture();
                            case "isAutocommit":
                                return true;
                            case "isInTransaction":
                                return false;
                            case "toString":
                                return "fake-xa-session";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
            context = (MycatDataContext) Proxy.newProxyInstance(ShardUpdateWarningsTest.class.getClassLoader(),
                    new Class[]{MycatDataContext.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getTransactionSession":
                                return session;
                            case "resolveDatasourceTargetName":
                                return args[0];
                            case "addWarning":
                                buffered.add((WarningMessage) args[0]);
                                return null;
                            case "getWarnings":
                                return new ArrayList<>(buffered);
                            case "isAutocommit":
                                return true;
                            case "isInTransaction":
                                return false;
                            case "toString":
                                return "fake-data-context";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        /** 放行第 index 个 EachSQL（EachSQL 顺序）的后端结果。 */
        void complete(int index) {
            promises.get(index).complete(results.get(index));
        }

        /** 按 EachSQL 顺序放行全部后端结果。 */
        void completeAll() {
            for (int i = 0; i < promises.size(); i++) {
                complete(i);
            }
        }

        private Object dispatchConnection(String target, Method method, Object[] args,
                                          Deque<Future<SqlResult>> queue) {
            switch (method.getName()) {
                case "insert":
                case "update":
                    issuedSqls.computeIfAbsent(target, t -> new ArrayList<>()).add((String) args[0]);
                    return queue.poll();
                case "getTargetName":
                    return target;
                case "isClosed":
                case "isQuerying":
                    return false;
                case "close":
                case "abandonQuery":
                    return Future.succeededFuture();
                case "toString":
                    return "fake-connection-" + target;
                case "hashCode":
                    return System.identityHashCode(target);
                case "equals":
                    return proxyEquals(target, args);
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private boolean proxyEquals(String target, Object[] args) {
            return args != null && args[0] instanceof NewMycatConnection
                    && ((NewMycatConnection) args[0]).getTargetName().equals(target);
        }
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
