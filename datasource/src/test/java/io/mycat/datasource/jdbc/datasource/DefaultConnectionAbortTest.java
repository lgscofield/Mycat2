package io.mycat.datasource.jdbc.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.pool.DruidStatementConnection;
import io.mycat.ConnectionManager;
import io.mycat.config.DatasourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I2b Task1 锁：{@link DefaultConnection#abort()} 物理弃用——区别于正常 {@code close()}（recycle 归还池）。
 * <ul>
 * <li>生产嵌套 DruidPooledConnection.conn = DruidStatementConnection，其 conn = 物理 mysql ConnectionImpl。
 *     包装层 abort（DruidPooledConnection.abort→conn.abort→DruidStatementConnection.abort）<b>先 statement.close()</b>
 *     抢 mysql session 锁，被冻结 query worker 持锁阻塞 ⇒ socket 永不杀。故必须<b>剥到物理连接</b>直接
 *     {@code physical.abort(Executor)}（mysql forceClose 先无锁杀 socket 解困 worker，后加锁清理）。</li>
 * <li>{@code DruidDataSource.discardConnection(physical)} 归位 activeCount（abort 本身不减），防多轮冻结耗尽池。</li>
 * <li>closeFlag CAS 单方生效：abort 幂等，且 abort 后 close() no-op（worker 解困后自身 close 与 abandon abort 并发安全）。</li>
 * <li>非 Druid 连接（mongodb/假件）无包装可剥，直接 physical.abort()。</li>
 * </ul>
 * 纯接线断言（Mockito mock Druid 件，建模生产三层嵌套），不连真库；真实 socket 解困/池账归位由 Task3 活体矩阵背书。
 */
class DefaultConnectionAbortTest {

    private DruidPooledConnection pc;
    private DruidStatementConnection dsc;
    private DruidDataSource ds;
    private Connection physical;
    private JdbcDataSource jdbcDs;
    private DefaultConnection dc;

    @BeforeEach
    void setUp() throws Exception {
        pc = mock(DruidPooledConnection.class);
        dsc = mock(DruidStatementConnection.class);
        ds = mock(DruidDataSource.class);
        physical = mock(Connection.class);
        DatasourceConfig cfg = new DatasourceConfig();
        cfg.setName("ds0");
        jdbcDs = new JdbcDataSource(cfg, ds);
        jdbcDs.counter.getAndIncrement();   // 借出计数 1
        when(pc.getConnection()).thenReturn(dsc);        // pc.conn = DruidStatementConnection
        when(dsc.getConnection()).thenReturn(physical);  // dsc.conn = 物理 mysql ConnectionImpl
        dc = new DefaultConnection(pc, jdbcDs, true,
                Connection.TRANSACTION_REPEATABLE_READ, mock(ConnectionManager.class));
    }

    @Test
    void abortPeelsToPhysicalNotWrapperAbort() throws Exception {
        dc.abort();
        verify(physical).abort(any(Executor.class));      // 物理 forceClose 到达（先杀 socket）
        verify(pc, never()).abort(any(Executor.class));   // 包装层 abort 不用（statement.close 阻塞）
        verify(dsc, never()).abort(any(Executor.class));
        verify(pc, never()).close();
        verify(pc, never()).recycle();
    }

    @Test
    void abortDiscardsPhysicalForPoolAccounting() throws Exception {
        dc.abort();
        verify(ds).discardConnection(physical);
        assertEquals(0, jdbcDs.getUsedCount());   // counter 1→0
    }

    @Test
    void abortStillDiscardsWhenPhysicalAbortThrows() throws Exception {
        // forceClose 先无锁杀 socket，其后清理阶段若抛异常：discard 必须仍在 finally 里归位 activeCount，
        // 否则冻结多轮后 Druid activeCount 泄漏→池耗尽（本票 Review Focus 2 的失败面）。
        doThrow(new RuntimeException("post-forceClose cleanup boom")).when(physical).abort(any(Executor.class));
        assertDoesNotThrow(dc::abort);            // 外层吞异常（对齐 close()）
        verify(ds).discardConnection(physical);   // abort 抛仍 discard
        assertEquals(0, jdbcDs.getUsedCount());
    }

    @Test
    void abortIsIdempotentViaCloseFlag() throws Exception {
        dc.abort();
        dc.abort();
        dc.close();
        // closeFlag CAS 单方生效：三调用合计 physical.abort / discardConnection / counter 各恰好一次，无双重释放。
        verify(physical, times(1)).abort(any(Executor.class));
        verify(ds, times(1)).discardConnection(physical);
        assertEquals(0, jdbcDs.getUsedCount());          // counter 1→0（decrementAndGet 在 CAS 内，二次 no-op）
        verify(pc, never()).close();                     // closeFlag 已置，close() no-op
    }

    @Test
    void nonDruidConnectionAbortsPhysically() throws Exception {
        Connection plain = mock(Connection.class);  // 非 Druid 包装，无层可剥
        DefaultConnection dc2 = new DefaultConnection(plain, jdbcDs, true,
                Connection.TRANSACTION_REPEATABLE_READ, mock(ConnectionManager.class));
        assertDoesNotThrow(dc2::abort);
        verify(plain).abort(any(Executor.class));   // 直接物理 abort
        verify(plain, never()).close();
    }
}