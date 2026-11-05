package io.mycat.commands;

import cn.mycat.vertx.xa.XaSqlConnection;
import io.mycat.ExplainDetail;
import io.mycat.ExecuteType;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatDataContext;
import io.mycat.MycatException;
import io.mycat.TransactionSession;
import io.mycat.beans.mycat.MycatErrorCode;
import io.mycat.config.DatasourceConfig;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.SqlResult;
import io.mycat.proxy.session.MySQLServerSession;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-84（D-B'）：{@code ReceiverImpl.execute()} 的 UPDATE/INSERT 腿在语句超时 deadline 触发时
 * 必须（1）以 {@code MycatException(3013)} 界住客户端，（2）对登记在途的物理连接调用
 * {@code abandonConnection()} 做 force-close（先回收再报错）。
 *
 * <p>本锁覆盖 to "接线契约"（deadline 触发→force-close 被调用→客户端有界拿 3013），用 Mockito +
 * {@link MetaClusterCurrent} 注入假 {@code JdbcConnectionManager} 与永不完成的假连接，不连真库、不起服务。
 * 真实"close 物理 socket 是否真能打断阻塞读"由 I-DEBT-83 R1c 行锁活体终检背书（见账本），不在此假设。
 */
public class ReceiverStatementDeadlineForceCloseTest {

    private static final String RESOLVED_DS = "ds0";
    private static final long STATEMENT_TIMEOUT_MILLIS = 120L;

    private Map<Class, Object> savedContext;
    private MySQLServerSession session;
    private MycatDataContext dataContext;
    private Both xa;
    private NewMycatConnection fakeConn;
    private Promise<SqlResult> blockingUpdate;
    private final AtomicInteger abandonCounter = new AtomicInteger(0);

    /** ReceiverImpl 构造里把 {@code getTransactionSession()}（声明 {@link TransactionSession}）强转
     * {@link XaSqlConnection}，故假件必须同时是两者——用交叉接口让 mock 既过 thenReturn 类型又过运行时强转。 */
    private interface Both extends TransactionSession, XaSqlConnection {
    }

    @BeforeEach
    void setUp() {
        savedContext = new IdentityHashMap<>(MetaClusterCurrent.copyContext());

        // 假 datasource 配置：statementTimeout 设极小值以便测试内确定触发。
        DatasourceConfig cfg = new DatasourceConfig();
        cfg.setName(RESOLVED_DS);
        cfg.setStatementTimeout(STATEMENT_TIMEOUT_MILLIS);
        Map<String, DatasourceConfig> configMap = new HashMap<>();
        configMap.put(RESOLVED_DS, cfg);
        JdbcConnectionManager manager = Mockito.mock(JdbcConnectionManager.class);
        Mockito.when(manager.getConfigAsMap()).thenReturn(configMap);
        MetaClusterCurrent.register(JdbcConnectionManager.class, manager);

        // 永不完成的 update：模拟后端冻结时 statement.executeUpdate 卡在 socket read。
        blockingUpdate = Promise.promise();
        fakeConn = Mockito.mock(NewMycatConnection.class);
        Mockito.when(fakeConn.update(Mockito.anyString(), Mockito.anyList()))
                .thenReturn(blockingUpdate.future());
        // force-close 的语义：弃用连接（此处以失败完成那个卡住的 update 模拟 socket 被关→worker 解困）。
        Mockito.doAnswer(inv -> {
            abandonCounter.incrementAndGet();
            blockingUpdate.tryFail(new SQLException("force closed by statement deadline"));
            return null;
        }).when(fakeConn).abandonConnection();

        xa = Mockito.mock(Both.class);
        Mockito.when(xa.getConnection(RESOLVED_DS)).thenReturn(Future.succeededFuture(fakeConn));

        dataContext = Mockito.mock(MycatDataContext.class);
        Mockito.when(dataContext.isInTransaction()).thenReturn(false);
        Mockito.when(dataContext.isAutocommit()).thenReturn(true);
        Mockito.when(dataContext.getTransactionSession()).thenReturn(xa);
        Mockito.when(dataContext.resolveDatasourceTargetName(Mockito.anyString(), Mockito.anyBoolean()))
                .thenReturn(RESOLVED_DS);

        session = Mockito.mock(MySQLServerSession.class);
        Mockito.when(session.getDataContext()).thenReturn(dataContext);
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    @Test
    public void testUpdateLegTimesOutAndForceClosesInFlightConnection() throws Exception {
        ReceiverImpl receiver = new ReceiverImpl(session, 1, false);
        ExplainDetail detail = ExplainDetail.create(ExecuteType.UPDATE,
                Collections.singletonList("tbl"), "UPDATE db1.t SET v=v+1 WHERE id=1", null,
                Collections.emptyList());
        List<Object> emptyParams = detail.getParams();
        assertTrue(emptyParams.isEmpty());

        long start = System.nanoTime();
        ExecutionException ex = null;
        try {
            receiver.execute(detail).toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            ex = e;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(ex != null, "永不完成的 update 必须被语句 deadline 掐成失败（客户端有界返回）");
        assertTrue(elapsedMs < 2000, "应在远小于 3s 的预算内超时，实际 " + elapsedMs + " ms");
        assertInstanceOf(MycatException.class, ex.getCause(),
                "失败原因应为 MycatException（走 recover 发合法 ERR）");
        assertEquals(MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT,
                ((MycatException) ex.getCause()).getErrorCode(), "超时须报 3013");
        assertEquals(1, abandonCounter.get(),
                "deadline 触发须对登记的在途连接 force-close 恰好一次");
    }
}
