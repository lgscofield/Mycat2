package io.mycat.xa;

import cn.mycat.vertx.xa.ImmutableCoordinatorLog;
import cn.mycat.vertx.xa.ImmutableParticipantLog;
import cn.mycat.vertx.xa.State;
import cn.mycat.vertx.xa.impl.LocalXaMemoryRepositoryImpl;
import com.alibaba.druid.util.JdbcUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-84 事务腿 Phase 2 阶段 0：证明"提交意向"被真正持久化到后端 {@code mycat.xa_log}——这是让 Phase 2
 * reaper 能安全 {@code XA COMMIT} 已决事务的前提（历史上意向只存内存、xa_log 生产从不 INSERT，past-prepare
 * 的已决提交会被误回滚）。
 *
 * <p>用真后端 ds1（127.0.0.1:3306）经 {@link TestMySQLManagerImpl#getWriteableConnection} 驱动
 * {@link LocalXaMemoryRepositoryImpl} 的 writeCommitLog/cancelCommitLog/remove，断言 xa_log 行的落盘与消失，
 * 并校验新增计时/归属列 {@code create_time}、{@code mycat_id} 被正确写入。不启代理服务，纯跨进程 JDBC。
 */
public class XaCommitIntentPersistenceTest {

    private static final long MYCAT_ID = 7L;
    private static final String DS1 = "ds1";
    // 用高位区间 xid，避免与同库其他 XA 用例的主键冲突；每例前后显式清理。
    private static final String XID_PERSIST = "910000001";
    private static final String XID_CANCEL = "910000002";
    private static final String XID_REMOVE = "910000003";

    private TestMySQLManagerImpl manager;
    private LocalXaMemoryRepositoryImpl repository;

    @BeforeEach
    void setUp() throws Exception {
        manager = new TestMySQLManagerImpl(Collections.singletonList(XaTestSuite.demoConfig(DS1, 3306)));
        try (Connection c = manager.getWriteableConnection(DS1)) {
            XaTestSuite.initSchema(c);
        }
        repository = LocalXaMemoryRepositoryImpl.createLocalXaMemoryRepository(() -> manager, MYCAT_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = manager.getWriteableConnection(DS1)) {
            for (String xid : new String[]{XID_PERSIST, XID_CANCEL, XID_REMOVE}) {
                JdbcUtils.executeUpdate(c, "delete from `mycat`.`xa_log` where xid = ?", Collections.singletonList(xid));
            }
        }
    }

    private static ImmutableCoordinatorLog coordinator(String xid) {
        ImmutableParticipantLog participant =
                new ImmutableParticipantLog(DS1, System.currentTimeMillis() + 30_000L, State.XA_PREPARED);
        return new ImmutableCoordinatorLog(xid, new ImmutableParticipantLog[]{participant});
    }

    private int rowCount(String xid) throws Exception {
        try (Connection c = manager.getWriteableConnection(DS1)) {
            List<Map<String, Object>> rows = JdbcUtils.executeQuery(c,
                    "select xid, create_time, mycat_id from `mycat`.`xa_log` where xid = ?",
                    Collections.singletonList(xid));
            return rows.size();
        }
    }

    /** 阶段 0 承重：writeCommitLog 必须把提交意向连同计时/归属列落盘到参与者后端。 */
    @Test
    void writeCommitLogPersistsIntentWithColumns() throws Exception {
        repository.writeCommitLog(coordinator(XID_PERSIST));

        try (Connection c = manager.getWriteableConnection(DS1)) {
            List<Map<String, Object>> rows = JdbcUtils.executeQuery(c,
                    "select xid, create_time, mycat_id from `mycat`.`xa_log` where xid = ?",
                    Collections.singletonList(XID_PERSIST));
            assertEquals(1, rows.size(), "writeCommitLog 须在 xa_log 留下一行提交意向");
            Map<String, Object> row = rows.get(0);
            assertNotNull(row.get("create_time"), "create_time 计时列须写入（Phase 2 reaper 判超期用）");
            assertTrue(((Number) row.get("create_time")).longValue() > 0L, "create_time 须为正值时间戳");
            assertEquals(MYCAT_ID, ((Number) row.get("mycat_id")).longValue(), "mycat_id 归属列须写入");
        }
    }

    /** cancelCommitLog（决定回滚）须清除持久意向，避免 reaper 误把已取消的事务再次提交。 */
    @Test
    void cancelCommitLogClearsPersistedIntent() throws Exception {
        repository.writeCommitLog(coordinator(XID_CANCEL));
        assertEquals(1, rowCount(XID_CANCEL), "前置：意向已落盘");

        repository.cancelCommitLog(XID_CANCEL);
        assertEquals(0, rowCount(XID_CANCEL), "cancelCommitLog 须清除 xa_log 意向行");
    }

    /** remove（提交/回滚完成后的清理）须清除持久意向，xa_log 不留已完成事务的残行。 */
    @Test
    void removeClearsPersistedIntent() throws Exception {
        repository.writeCommitLog(coordinator(XID_REMOVE));
        assertEquals(1, rowCount(XID_REMOVE), "前置：意向已落盘");

        repository.remove(XID_REMOVE);
        assertEquals(0, rowCount(XID_REMOVE), "remove 须清除 xa_log 意向行");
    }
}
