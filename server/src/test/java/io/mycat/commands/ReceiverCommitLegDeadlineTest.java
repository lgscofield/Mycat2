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
 * I-DEBT-84 Phase2 阶段2（R5 commit 腿接 deadline + R3 客户端语义）：{@code ReceiverImpl.commit()} 在语句超时
 * deadline 触发时必须（1）以 {@code MycatException(3013)} 界住客户端（后端冻结时 XA COMMIT/本地 commit 永不完成
 * =无界挂的 I-DEBT-80 孤儿签名），（2）对已借出的事务参与者 {@code abandonConnection()} force-close 解困阻塞 worker，
 * （3）置 {@code markCommitTimedOut()} 使 va 轨 retryCommit 让位 reaper（不双主 churn）；快路径须取消定时器、
 * 不误弃连接、照常 writeOk。已决（{@code isCommitOutcomeUnknown()}=commitTimedOut&&commitDecided）超时场景，
 * 客户端文案须含 "commit outcome unknown"（3013 码不变，收敛交 reaper 后台恢复）。
 *
 * <p>仿 Phase 1 {@link ReceiverTransactionLegDeadlineTest} 的接线契约 harness：Mockito + {@link MetaClusterCurrent}
 * 注入假 {@code JdbcConnectionManager} 与永不完成的假参与者，不连真库、不起服务。va 层 decided 标志语义由
 * {@code CommitDecidedFlagTest} 钉死，本锁只验 Receiver 边界（deadline→force-close→markCommitTimedOut→3013/文案）。
 */
public class ReceiverCommitLegDeadlineTest {

    private static final String RESOLVED_DS = "ds0";
    private static final long STATEMENT_TIMEOUT_MILLIS = 120L;

    private Map<Class, Object> savedContext;
    private MySQLServerSession session;
    private MycatDataContext dataContext;
    private Both xa;
    private NewMycatConnection fakeConn;
    private Promise<Void> blockingLeg;
    private final AtomicInteger abandonCounter = new AtomicInteger(0);

    /** ReceiverImpl 构造把 getTransactionSession()（声明 TransactionSession）强转 XaSqlConnection，
     * 故假件必须同时是两者——交叉接口让 mock 既过 thenReturn 类型又过运行时强转。 */
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

        // 永不完成的腿 Future：模拟后端冻结时 commit（XA COMMIT/本地 commit;）卡在阻塞同步 JDBC。
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
     * T1（R5 主 RED）：commit 腿后端冻结（永不完成）⇒ deadline 掐成 3013，对参与者 force-close 恰好一次，
     * 置 markCommitTimedOut（va retryCommit 据此让位 reaper），客户端有界返回。
     * 当前生产码 commit 无 deadline ⇒ 永不完成 ⇒ get(3s) 超时（RED）。
     */
    @Test
    public void commitLegTimesOut_reports3013_forceCloses() throws Exception {
        Mockito.when(xa.commit()).thenReturn(blockingLeg.future());

        ReceiverImpl receiver = new ReceiverImpl(session, 1, false);
        long start = System.nanoTime();
        ExecutionException ex = null;
        try {
            receiver.commit().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            ex = e;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(ex != null, "永不完成的 commit 必须被语句 deadline 掐成失败（客户端有界返回）");
        assertTrue(elapsedMs < 2000, "应在远小于 3s 的预算内超时，实际 " + elapsedMs + " ms");
        assertInstanceOf(MycatException.class, ex.getCause(), "失败原因应为 MycatException");
        assertEquals(MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT,
                ((MycatException) ex.getCause()).getErrorCode(), "超时须报 3013");
        assertEquals(1, abandonCounter.get(), "deadline 触发须对已借出参与者 force-close 恰好一次");
        Mockito.verify(xa).markCommitTimedOut();
    }

    /**
     * T2（快路径对照锁，当前应绿）：commit 立即成功 ⇒ 取消定时器、不误弃连接（abandon==0）、writeOk 被调、无异常。
     */
    @Test
    public void fastCommit_noForceClose_writeOk() throws Exception {
        Mockito.when(xa.commit()).thenReturn(Future.succeededFuture());

        ReceiverImpl receiver = new ReceiverImpl(session, 1, false);
        receiver.commit().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);

        Mockito.verify(session).writeOk(Mockito.anyBoolean());
        assertEquals(0, abandonCounter.get(), "快路径成功不应触发任何 force-close（定时器已取消）");
        Mockito.verify(xa, Mockito.never()).markCommitTimedOut();
    }

    /**
     * T3（R3 文案 RED）：已决超时（isCommitOutcomeUnknown=true）⇒ 客户端 3013 文案含 "commit outcome unknown"，
     * 提示提交结果未知、后台 reaper 恢复（而非谎报成功/失败）。当前生产码无此文案 ⇒ RED。
     */
    @Test
    public void decidedTimeout_messageCarriesOutcomeUnknown() throws Exception {
        Mockito.when(xa.commit()).thenReturn(blockingLeg.future());
        Mockito.when(xa.isCommitOutcomeUnknown()).thenReturn(true);

        ReceiverImpl receiver = new ReceiverImpl(session, 1, false);
        ExecutionException ex = null;
        try {
            receiver.commit().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            ex = e;
        }

        assertTrue(ex != null, "已决超时 commit 必须被 deadline 掐成失败");
        assertInstanceOf(MycatException.class, ex.getCause());
        MycatException cause = (MycatException) ex.getCause();
        assertEquals(MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT, cause.getErrorCode(), "码仍为 3013");
        assertTrue(cause.getMessage() != null && cause.getMessage().contains("commit outcome unknown"),
                "已决超时文案须含 'commit outcome unknown'，实际: " + cause.getMessage());
    }
}