/**
 * Copyright [2021] [chen junwen]
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.xa;

import cn.mycat.vertx.xa.ImmutableCoordinatorLog;
import cn.mycat.vertx.xa.ImmutableParticipantLog;
import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.State;
import cn.mycat.vertx.xa.impl.LocalXaMemoryRepositoryImpl;
import cn.mycat.vertx.xa.impl.XaReaper;
import com.alibaba.druid.util.JdbcUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-84 事务腿 Phase 2 阶段 1：真 XA 后端上坐实 {@link XaReaper} 的收敛语义与"不抢方向盘"收口
 * （这是活体行为，单测桩锁不住、唯真机可证）。
 *
 * <p>三场景（均在 ds1=127.0.0.1:3306 真 XA）：
 * <ul>
 *   <li><b>A 已决提交</b>：分支 XA PREPARE + xa_log 落提交意向 ⇒ 一轮 sweep 后该分支被 {@code XA COMMIT}、
 *       意向清除、分支内写入的数据对其它连接可见（证明 past-prepare 已决提交不再被误回滚）。</li>
 *   <li><b>B 未决·grace 内</b>：分支 prepared 但无意向 ⇒ 首轮 sweep 只登记 firstSeen、<b>不动它</b>
 *       （仍处 prepared，交在线协调器收敛，不抢方向盘）。</li>
 *   <li><b>C 未决·过 grace</b>：同 xid 滞留超 grace ⇒ 下一轮 {@code XA ROLLBACK}，分支消失、数据未生效。</li>
 * </ul>
 *
 * <p>用可注入时钟（{@link AtomicLong}）驱动 grace，无需真实等待；直接调 {@link XaReaper#sweep(Map)} 避开
 * {@code getWriteableConnectionMap} 的重装配依赖。
 */
public class XaReaperConvergenceTest {

    private static final String DS1 = "ds1";
    private static final long MYCAT_ID = 7L;
    private static final long GRACE = 60_000L;

    private static final String XID_COMMIT = "920000101";
    private static final String XID_UNDECIDED = "920000102";

    private TestMySQLManagerImpl manager;
    private LocalXaMemoryRepositoryImpl repository;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    @BeforeEach
    void setUp() throws Exception {
        manager = new TestMySQLManagerImpl(Collections.singletonList(XaTestSuite.demoConfig(DS1, 3306)));
        try (Connection c = manager.getWriteableConnection(DS1)) {
            XaTestSuite.initSchema(c);
        }
        repository = LocalXaMemoryRepositoryImpl.createLocalXaMemoryRepository(() -> manager, MYCAT_ID);
        clock.set(System.currentTimeMillis());
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = manager.getWriteableConnection(DS1)) {
            for (String xid : new String[]{XID_COMMIT, XID_UNDECIDED}) {
                // 若仍悬挂则回滚清理；已被收敛则报 unknown XID，吞掉。
                try {
                    JdbcUtils.execute(c, "XA ROLLBACK '" + xid + "';");
                } catch (SQLException ignore) {
                    // already resolved
                }
                JdbcUtils.execute(c, "delete from `mycat`.`xa_log` where xid = '" + xid + "';");
            }
            JdbcUtils.execute(c, "delete from db1.travelrecord where user_id like 'xa_reaper_%';");
        }
    }

    /** 在 ds1 上把一个带标记写入的分支推到 PREPARED（prepared 后关连接不影响分支驻留）。 */
    private void prepareBranch(String xid, String markerUser) throws Exception {
        Connection c = manager.getWriteableConnection(DS1);
        try {
            JdbcUtils.execute(c, "SET autocommit=1;");
            JdbcUtils.execute(c, "XA START '" + xid + "';");
            JdbcUtils.execute(c, "insert into db1.travelrecord(user_id) values('" + markerUser + "');");
            JdbcUtils.execute(c, "XA END '" + xid + "';");
            JdbcUtils.execute(c, "XA PREPARE '" + xid + "';");
        } finally {
            JdbcUtils.close(c);
        }
    }

    private boolean stillPrepared(String xid) throws Exception {
        try (Connection c = manager.getWriteableConnection(DS1)) {
            List<Map<String, Object>> rows = JdbcUtils.executeQuery(c, "XA RECOVER", Collections.emptyList());
            for (Map<String, Object> row : rows) {
                if (xid.equals(row.get("data"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private int markerCount(String markerUser) throws Exception {
        try (Connection c = manager.getWriteableConnection(DS1)) {
            List<Map<String, Object>> rows = JdbcUtils.executeQuery(c,
                    "select count(*) as c from db1.travelrecord where user_id = '" + markerUser + "'",
                    Collections.emptyList());
            return ((Number) rows.get(0).get("c")).intValue();
        }
    }

    private XaReaper newReaper() {
        return new XaReaper(() -> manager, 1_000L, GRACE,
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(), clock::get);
    }

    private Map<String, Connection> sweepMap() throws Exception {
        Map<String, Connection> map = new HashMap<>();
        map.put(DS1, manager.getWriteableConnection(DS1));
        return map;
    }

    private static ImmutableCoordinatorLog coordinator(String xid) {
        ImmutableParticipantLog p =
                new ImmutableParticipantLog(DS1, System.currentTimeMillis() + GRACE, State.XA_PREPARED);
        return new ImmutableCoordinatorLog(xid, new ImmutableParticipantLog[]{p});
    }

    /** 场景 A：已决提交——xa_log 有意向 ⇒ reaper 据意向 XA COMMIT，数据落地、意向清除。 */
    @Test
    void decidedPreparedBranchGetsCommittedByReaper() throws Exception {
        String marker = "xa_reaper_a_" + XID_COMMIT;
        prepareBranch(XID_COMMIT, marker);
        assertTrue(stillPrepared(XID_COMMIT), "前置：分支应处于 prepared");
        // 持久化提交意向（阶段 0 能力）。
        repository.writeCommitLog(coordinator(XID_COMMIT));

        XaReaper reaper = newReaper();
        reaper.sweep(sweepMap());

        assertFalse(stillPrepared(XID_COMMIT), "已决提交应被 reaper XA COMMIT，不再出现在 XA RECOVER");
        assertEquals(1, markerCount(marker), "分支内写入应随提交对其它连接可见（未丢已提交数据）");
        try (Connection c = manager.getWriteableConnection(DS1)) {
            List<Map<String, Object>> rows = JdbcUtils.executeQuery(c,
                    "select xid from `mycat`.`xa_log` where xid = '" + XID_COMMIT + "'", Collections.emptyList());
            assertEquals(0, rows.size(), "提交后 xa_log 意向行应被清除");
        }
    }

    /** 场景 B+C：未决——grace 内不动（不抢方向盘），过 grace 才回滚。 */
    @Test
    void undecidedBranchIsGraceGatedThenRolledBack() throws Exception {
        String marker = "xa_reaper_b" + XID_UNDECIDED;
        prepareBranch(XID_UNDECIDED, marker);
        assertTrue(stillPrepared(XID_UNDECIDED), "前置：分支应处于 prepared");
        XaReaper reaper = newReaper();

        // 首轮：无意向 + 未达 grace（firstSeen 刚登记）⇒ 不得动它（在线协调器仍可能自行收敛）。
        reaper.sweep(sweepMap());
        assertTrue(stillPrepared(XID_UNDECIDED), "grace 内 reaper 不得回滚未决分支（不抢方向盘）");

        // 时钟推过 grace ⇒ 次轮判协调器失联 ⇒ XA ROLLBACK。
        clock.addAndGet(GRACE + 1_000L);
        reaper.sweep(sweepMap());
        assertFalse(stillPrepared(XID_UNDECIDED), "过 grace 未决分支应被 XA ROLLBACK");
        assertEquals(0, markerCount(marker), "回滚后分支内写入不得生效");
    }
}
