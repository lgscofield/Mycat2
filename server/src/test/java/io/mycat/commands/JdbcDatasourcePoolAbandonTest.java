package io.mycat.commands;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.pool.DruidStatementConnection;
import io.mycat.IOExecutor;
import io.mycat.MetaClusterCurrent;
import io.mycat.config.DatasourceConfig;
import io.mycat.datasource.jdbc.datasource.DefaultConnection;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.datasource.jdbc.datasource.JdbcDataSource;
import io.mycat.newquery.NewMycatConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I2b Task2 锁：{@link JdbcDatasourcePoolImpl} 匿名子类 {@code abandonConnection()}（四腿共享 force-close 缝）
 * 必须落 {@link DefaultConnection#abort()} 物理弃用，而非 {@code close()} recycle；正常 {@code close()} 仍 recycle 不变。
 *
 * <p>接线断言（Mockito mock Druid 件 + 假 JdbcConnectionManager，经 MetaClusterCurrent 注入），不连真库。
 * 建模生产三层嵌套 pc→DruidStatementConnection→物理：abandon 经缝剥到物理 abort（包装层 abort 先 statement.close 会阻塞）。
 * abandonConnection 经 ThreadMycatConnectionImplWrapper 的 IOExecutor.executeBlocking 异步执行，故用 timeout 等待。
 * 真实 socket 解困/池账归位由 Task3 活体矩阵背书。
 */
public class JdbcDatasourcePoolAbandonTest {

    private static final String DS = "ds0";
    private Map<Class, Object> savedContext;
    private JdbcConnectionManager manager;
    private DruidPooledConnection pc;
    private DruidStatementConnection dsc;
    private DruidDataSource ds;
    private Connection physical;
    private DefaultConnection dc;

    @BeforeEach
    void setUp() throws Exception {
        savedContext = new IdentityHashMap<>(MetaClusterCurrent.copyContext());
        DatasourceConfig cfg = new DatasourceConfig();
        cfg.setName(DS);
        cfg.setDbType("mysql");
        cfg.setRemoveAbandonedTimeoutSecond(0);   // timeId=null，免 Vertx 周期定时器
        pc = mock(DruidPooledConnection.class);
        dsc = mock(DruidStatementConnection.class);
        ds = mock(DruidDataSource.class);
        physical = mock(Connection.class);
        when(pc.getConnection()).thenReturn(dsc);        // pc.conn = DruidStatementConnection
        when(dsc.getConnection()).thenReturn(physical);  // dsc.conn = 物理 mysql ConnectionImpl
        JdbcDataSource jdbcDs = new JdbcDataSource(cfg, ds);
        manager = mock(JdbcConnectionManager.class);
        dc = new DefaultConnection(pc, jdbcDs, true, Connection.TRANSACTION_REPEATABLE_READ, manager);
        when(manager.getConnection(DS)).thenReturn(dc);
        MetaClusterCurrent.register(JdbcConnectionManager.class, manager);
        MetaClusterCurrent.register(IOExecutor.class, IOExecutor.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    @Test
    void abandonRoutesToPhysicalAbort() throws Exception {
        NewMycatConnection w = new JdbcDatasourcePoolImpl(DS).getConnection().result();
        w.abandonConnection();   // 经 IOExecutor.executeBlocking 异步
        verify(physical, timeout(3000)).abort(any(Executor.class));   // 缝剥到物理 forceClose
        verify(ds, timeout(3000)).discardConnection(physical);
        verify(pc, never()).abort(any(Executor.class));               // 包装层 abort 不用（statement.close 阻塞）
        verify(pc, never()).close();
    }

    @Test
    void normalCloseDoesNotAbort() throws Exception {
        NewMycatConnection w = new JdbcDatasourcePoolImpl(DS).getConnection().result();
        w.close().result();      // 正常释放仍走 defaultConnection.close()→manager.closeConnection
        verify(manager, timeout(3000)).closeConnection(dc);
        verify(pc, never()).abort(any(Executor.class));
        verify(physical, never()).abort(any(Executor.class));
    }
}