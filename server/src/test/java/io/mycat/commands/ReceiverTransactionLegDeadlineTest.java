package io.mycat.commands;

import cn.mycat.vertx.xa.XaSqlConnection;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatDataContext;
import io.mycat.MycatException;
import io.mycat.TransactionSession;
import io.mycat.beans.mycat.MycatErrorCode;
import io.mycat.config.DatasourceConfig;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.newquery.NewMycatConnection;
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
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-84 事务腿 Phase 1：{@code ReceiverImpl} 的 rollback/savepoint 四腿在语句超时 deadline 触发时
 * 必须（1）以 {@code MycatException(3013)} 界住客户端（后端冻结时 {@code update()} 永不完成=无界挂的
 * I-DEBT-80 孤儿签名），（2）对已借出的事务参与者调用 {@code abandonConnection()} 做 force-close 解困
 * 阻塞 worker；而快路径（参与者及时返回）须取消定时器、不误弃连接、照常 writeOk（不回归、不泄漏）。
 *
 * <p>本锁覆盖"接线契约"（deadline 触发→force-close 被调用→客户端有界拿 3013），用 Mockito +
 * {@link MetaClusterCurrent} 注入假 {@code JdbcConnectionManager} 与永不完成的假参与者，不连真库、不起服务。
 * commit 腿（本地单 DS + 多 DS 2PC）因 in-doubt 风险刻意不接 deadline，留 Phase 2 拍板（见 spec §7.6/§7.7）。
 */
public class ReceiverTransactionLegDeadlineTest {

    private static final String RESOLVED_DS = "ds0";
    private static final long STATEMENT_TIMEOUT_MILLIS = 120L;

    private Map<Class, Object> savedContext;
    private MySQLServerSession session;
    private MycatDataContext dataContext;
    private Both xa;
    private NewMycatConnection fakeConn;
    private Promise<Void> blockingLeg;
    private final AtomicInteger abandonCounter = new AtomicInteger(0);

    /** ReceiverImpl 构造把 {@code getTransactionSession()}（声明 {@link TransactionSession}）强转
     * {@link XaSqlConnection}，故假件必须同时是两者——交叉接口让 mock 既过 thenReturn 类型又过运行时强转。 */
    private interface Both extends TransactionSession, XaSqlConnection {
    }

    @BeforeEach
    void setUp() {
        savedContext = new IdentityHashMap<>(MetaClusterCurrent.copyContext());

        DatasourceConfig cfg = new DatasourceConfig();
        cfg.setName(RESOLVED_DS);
        cfg.setStatementTimeout(STATEMENT_TIMEOUT_MILLIS);
        Map<String, DatasourceConfig> configMap = new HashMap<>();
        configMap.put(RESOLVED_DS, cfg);
        JdbcConnectionManager manager = Mockito.mock(JdbcConnectionManager.class);
        Mockito.when(manager.getConfigAsMap()).thenReturn(configMap);
        MetaClusterCurrent.register(JdbcConnectionManager.class, manager);

        // 永不完成的腿 Future：模拟后端冻结时事务 update()（rollback;/savepoint）卡在阻塞同步 JDBC。
        blockingLeg = Promise.promise();

        fakeConn = Mockito.mock(NewMycatConnection.class);
        Mockito.when(fakeConn.getTargetName()).thenReturn(RESOLVED_DS);
        // force-close 语义：弃用连接（以失败完成那个卡住的腿模拟 socket 被关→worker 解困）。
        Mockito.doAnswer(inv -> {
            abandonCounter.incrementAndGet();
            blockingLeg.tryFail(new SQLException("force closed by statement deadline"));
            return null;
        }).when(fakeConn).abandonConnection();

        xa = Mockito.mock(Both.class);
        // 事务参与者已借出：getExistedTranscationConnections 返挂着的 fakeConn，供 deadline force-close。
        Mockito.when(xa.getExistedTranscationConnections()).thenReturn(Collections.singletonList(fakeConn));
        Mockito.when(xa.closeStatementState()).thenReturn(Future.succeededFuture());

        dataContext = Mockito.mock(MycatDataContext.class);
        Mockito.when(dataContext.getTransactionSession()).thenReturn(xa);

        session = Mockito.mock(MySQLServerSession.class);
        Mockito.when(session.getDataContext()).thenReturn(dataContext);
        Mockito.when(session.writeOk(Mockito.anyBoolean())).thenReturn(Future.succeededFuture());
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    /**
     * T1：rollback 腿后端冻结（永不完成）⇒ deadline 掐成 3013，且对参与者 force-close 恰好一次，客户端有界返回。
     */
    @Test
    public void testRollbackLegTimesOutReports3013AndForceClosesParticipant() throws Exception {
        Mockito.when(xa.rollback()).thenReturn(blockingLeg.future());

        ReceiverImpl receiver = new ReceiverImpl(session, 1, false);
        long start = System.nanoTime();
        ExecutionException ex = null;
        try {
            receiver.rollback().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            ex = e;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(ex != null, "永不完成的 rollback 必须被语句 deadline 掐成失败（客户端有界返回）");
        assertTrue(elapsedMs < 2000, "应在远小于 3s 的预算内超时，实际 " + elapsedMs + " ms");
        assertInstanceOf(MycatException.class, ex.getCause(), "失败原因应为 MycatException");
        assertEquals(MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT,
                ((MycatException) ex.getCause()).getErrorCode(), "超时须报 3013");
        assertEquals(1, abandonCounter.get(), "deadline 触发须对已借出参与者 force-close 恰好一次");
    }

    /**
     * T2：setSavepoint 腿同构——后端冻结 ⇒ 3013 + force-close 恰好一次。
     */
    @Test
    public void testSetSavepointLegTimesOutReports3013AndForceCloses() throws Exception {
        Mockito.when(xa.createSavepoint("s1")).thenReturn(blockingLeg.future());

        ReceiverImpl receiver = new ReceiverImpl(session, 1, false);
        ExecutionException ex = null;
        try {
            receiver.setSavepoint("s1").toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            ex = e;
        }

        assertTrue(ex != null, "永不完成的 SAVEPOINT 必须被 deadline 掐成失败");
        assertInstanceOf(MycatException.class, ex.getCause());
        assertEquals(MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT,
                ((MycatException) ex.getCause()).getErrorCode(), "超时须报 3013");
        assertEquals(1, abandonCounter.get(), "deadline 须对参与者 force-close 恰好一次");
    }

    /**
     * T3：快路径不回归——rollback 立即成功⇒取消定时器、不误弃连接（abandon==0）、writeOk 被调、无异常。
     */
    @Test
    public void testFastRollbackCompletesNormallyWithoutForceClose() throws Exception {
        Mockito.when(xa.rollback()).thenReturn(Future.succeededFuture());

        ReceiverImpl receiver = new ReceiverImpl(session, 1, false);
        Future<Void> fut = receiver.rollback();
        fut.toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);

        Mockito.verify(session).writeOk(Mockito.anyBoolean());
        assertEquals(0, abandonCounter.get(), "快路径成功不应触发任何 force-close（定时器已取消）");
    }
}
