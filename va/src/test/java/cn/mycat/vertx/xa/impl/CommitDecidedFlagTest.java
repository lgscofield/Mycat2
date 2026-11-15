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
package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaLog;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.SqlResult;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.vertx.core.Future;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * I-DEBT-84 Phase2 阶段2 Task1（决定点归位 + R1 commitDecided）确定性回归锁。
 *
 * <p>驱动真实 {@link LocalXaSqlConnection#commit()} 混合路径（ds1 本地协调 + ds2 XA 参与者），
 * 用 Mockito 假连接 + 假 {@link XaLog} 钉住三件事：
 * <ol>
 *   <li><b>决定点归位</b>：本地协调 ds1 的 {@code commit;}（beforeCommit）必须先于参与者意向持久化
 *       {@code logCommitBeforeXaCommit}（writeCommitLog）——否则 ds1 提交前被 force-close 时参与者意向已
 *       durable，reaper 误提交参与者造成跨库不一致（spec §8.6 PROBE-A 坐实）。</li>
 *   <li><b>decided 不回滚</b>：ds1 已 commit（决定已做出）后 writeCommitLog 失败 ⇒ 不发 XA ROLLBACK、
 *       向前收敛交 reaper（ds1.xa_log 意向已 durable）。</li>
 *   <li><b>未 decided 回滚 + cancel 复位</b>：beforeCommit 失败（ds1 未 commit）⇒ 走 rollback 发 XA ROLLBACK，
 *       且 {@code isCommitDecided()==false}。</li>
 * </ol>
 */
public class CommitDecidedFlagTest {

    private MySQLManager manager;
    private XaLog xaLog;
    private NewMycatConnection ds1;
    private NewMycatConnection ds2;
    private final List<String> sqls1 = new ArrayList<>();
    private final List<String> sqls2 = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    /** 非空时 ds1 的 update 命中该谓词即失败（注入本地 commit; 失败）。 */
    private Predicate<String> ds1Failure;
    /** 非空时 ds2 的 update 命中该谓词即失败（注入参与者 XA ROLLBACK 失败，模拟 force-close 后后端冻结）。 */
    private Predicate<String> ds2Failure;
    /** 非空时 writeCommitLog 抛该异常（注入参与者意向持久化失败）。 */
    private AtomicReference<Throwable> writeCommitLogFailure;

    @Before
    public void setUp() {
        ds1Failure = null;
        ds2Failure = null;
        writeCommitLogFailure = new AtomicReference<>();
        ds1 = fakeConnection("ds1", sqls1);
        ds2 = fakeConnection("ds2", sqls2);

        manager = mock(MySQLManager.class);
        when(manager.getConnection("ds1")).thenReturn(Future.succeededFuture(ds1));
        when(manager.getConnection("ds2")).thenReturn(Future.succeededFuture(ds2));

        xaLog = mock(XaLog.class);
        when(xaLog.nextXid()).thenReturn("984001");
        when(xaLog.getExpires()).thenReturn(0L);
        when(xaLog.retryDelay()).thenReturn(0L);
        try {
            when(xaLog.logCommitBeforeXaCommit(anyString())).thenAnswer(inv -> {
                events.add("writeCommitLog");
                Throwable t = writeCommitLogFailure.get();
                if (t != null) {
                    throw t;
                }
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private NewMycatConnection fakeConnection(String target, List<String> sink) {
        NewMycatConnection c = mock(NewMycatConnection.class);
        when(c.getTargetName()).thenReturn(target);
        when(c.isClosed()).thenReturn(false);
        when(c.close()).thenReturn(Future.succeededFuture());
        when(c.abandonQuery()).thenReturn(Future.succeededFuture());
        when(c.update(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            sink.add(sql);
            events.add(target + ":" + sql);
            if ("ds1".equals(target) && ds1Failure != null && ds1Failure.test(sql)) {
                return Future.failedFuture(new IllegalStateException("injected ds1 failure: " + sql));
            }
            if ("ds2".equals(target) && ds2Failure != null && ds2Failure.test(sql)) {
                return Future.failedFuture(new IllegalStateException("injected ds2 failure: " + sql));
            }
            return Future.<SqlResult>succeededFuture(null);
        });
        return c;
    }

    private LocalXaSqlConnection newConnection() {
        return new LocalXaSqlConnection(MySQLIsolation.DEFAULT, () -> manager, xaLog);
    }

    /** begin + 借 ds1（本地协调）+ ds2（XA 参与者），把会话推进到可 commit 的混合态。 */
    private LocalXaSqlConnection driveToMixed() throws Exception {
        LocalXaSqlConnection c = newConnection();
        await(c.begin());
        await(c.getConnection("ds1"));
        await(c.getConnection("ds2"));
        return c;
    }

    private static <T> T await(Future<T> f) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        f.onComplete(ar -> {
            if (ar.succeeded()) {
                result.set(ar.result());
            } else {
                err.set(ar.cause());
            }
            latch.countDown();
        });
        assertTrue("future 未在 20s 内完成", latch.await(20, TimeUnit.SECONDS));
        if (err.get() != null) {
            throw new AssertionError("future 失败", err.get());
        }
        @SuppressWarnings("unchecked")
        T t = (T) result.get();
        return t;
    }

    private static boolean awaitFailure(Future<?> f) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        f.onComplete(ar -> {
            failed.set(ar.failed());
            latch.countDown();
        });
        assertTrue("future 未在 20s 内完成", latch.await(20, TimeUnit.SECONDS));
        return failed.get();
    }

    private boolean issuedXaRollback() {
        return sqls2.stream().anyMatch(s -> s.startsWith("XA ROLLBACK"));
    }

    /**
     * 决定点归位：本地协调 ds1 的 REPLACE/commit;（beforeCommit）必须先于参与者意向持久化 writeCommitLog。
     * 当前生产码 writeCommitLog 在前 ⇒ 本锁红。
     */
    @Test
    public void beforeCommitPrecedesWriteCommitLog() throws Exception {
        LocalXaSqlConnection c = driveToMixed();
        await(c.commit());

        int replaceIdx = indexOfEventStartingWith("ds1:REPLACE INTO mycat.xa_log");
        int writeIdx = events.indexOf("writeCommitLog");
        assertTrue("应观察到 ds1 REPLACE xa_log", replaceIdx >= 0);
        assertTrue("应观察到 writeCommitLog", writeIdx >= 0);
        assertTrue("本地协调决定（ds1 REPLACE/commit;）必须先于参与者意向持久化 writeCommitLog，"
                        + "实际 events=" + events,
                replaceIdx < writeIdx);
    }

    /**
     * decided 不回滚：ds1 已 commit（beforeCommit 成功）后 writeCommitLog 失败 ⇒ 决定已做出，
     * 不得对 prepared 参与者发 XA ROLLBACK，向前收敛交 reaper。当前生产码 writeCommitLog 在前且失败即 rollback ⇒ 红。
     */
    @Test
    public void decidedAfterDecision_failureDoesNotRollback() throws Exception {
        writeCommitLogFailure.set(new IllegalStateException("intent write fail"));
        LocalXaSqlConnection c = driveToMixed();

        boolean failed = awaitFailure(c.commit());

        assertTrue("writeCommitLog 失败须如实上报失败", failed);
        assertFalse("ds1 已 commit（decided）后不得对参与者发 XA ROLLBACK，实际 sqls2=" + sqls2,
                issuedXaRollback());
        assertTrue("决定已做出，commitDecided 须为 true", c.isCommitDecided());
        assertFalse("失败后会话态须归零", c.isInTransaction());
    }

    /**
     * 未 decided 回滚：beforeCommit（ds1 commit;）失败 ⇒ 决定未做出 ⇒ 走 rollback 对 prepared 参与者发 XA ROLLBACK。
     */
    @Test
    public void notDecided_failureRollsBack() throws Exception {
        ds1Failure = sql -> sql.equals("commit;");
        LocalXaSqlConnection c = driveToMixed();

        boolean failed = awaitFailure(c.commit());

        assertTrue("本地 commit; 失败须如实上报", failed);
        assertTrue("未 decided 须走 rollback 对参与者发 XA ROLLBACK，实际 sqls2=" + sqls2,
                issuedXaRollback());
        assertFalse("未 decided，commitDecided 须为 false", c.isCommitDecided());
    }

    /**
     * cancel 复位：beforeCommit 失败 ⇒ logCancelCommitBeforeXaCommit ⇒ commitDecided 复位 false。
     */
    @Test
    public void cancelResetsDecided() throws Exception {
        ds1Failure = sql -> sql.equals("commit;");
        LocalXaSqlConnection c = driveToMixed();

        awaitFailure(c.commit());

        assertFalse("beforeCommit 失败后 commitDecided 必须复位 false", c.isCommitDecided());
        assertFalse(c.isInTransaction());
    }

    /**
     * I-DEBT-84 C1（超时未决）：commit 腿已超时（force-close 弃用全部参与者）后，混合路径 ds1 commit; 失败、
     * ds2 XA ROLLBACK 亦失败。未决 transform 不得进入 rollback→kill→rollback 无限递归，须有界失败 + 复位会话态，
     * 未决收敛交 reaper。当前生产码未决 transform 无条件 rollback() ⇒ 递归（实测 748 次 XA ROLLBACK，仅因
     * 同步 StackOverflow 才停；真后端异步则无限 runaway）⇒ 红。超时档应完全跳过 rollback（0 次 XA ROLLBACK）。
     */
    @Test
    public void undecidedTimedOut_commitFailureTerminatesWithoutRollbackLoop() throws Exception {
        ds1Failure = sql -> sql.equals("commit;");
        ds2Failure = sql -> sql.startsWith("XA ROLLBACK");
        LocalXaSqlConnection c = driveToMixed();
        c.markCommitTimedOut();

        boolean failed = awaitFailure(c.commit());

        long xaRollbackCount = sqls2.stream().filter(s -> s.startsWith("XA ROLLBACK")).count();
        assertTrue("超时未决 commit 失败须有界上报", failed);
        assertFalse("超时未决失败后会话态须归零（不得卡在递归）", c.isInTransaction());
        assertTrue("超时未决须跳过 rollback 让位 reaper，不得递归发 XA ROLLBACK，实际次数=" + xaRollbackCount,
                xaRollbackCount == 0);
    }

    /**
     * I-DEBT-84 C1/I1（非超时未决，plan Review Focus #4 终止断言）：ds1 commit; 失败 + ds2 XA ROLLBACK 失败，
     * 即便未超时，rollback→kill→rollback 递归也须被终止守卫切断：XA ROLLBACK 至多发常数次（≤2），有界失败 +
     * 复位会话态。当前生产码 ⇒ 748 次递归 ⇒ 红。
     */
    @Test
    public void undecidedRollbackFailure_terminatesBounded() throws Exception {
        ds1Failure = sql -> sql.equals("commit;");
        ds2Failure = sql -> sql.startsWith("XA ROLLBACK");
        LocalXaSqlConnection c = driveToMixed();

        boolean failed = awaitFailure(c.commit());

        long xaRollbackCount = sqls2.stream().filter(s -> s.startsWith("XA ROLLBACK")).count();
        assertTrue("未决 commit 失败须有界上报", failed);
        assertFalse("rollback 失败后须终止递归并复位会话态", c.isInTransaction());
        assertTrue("rollback→kill→rollback 递归须被切断，XA ROLLBACK 次数须有界(≤2)，实际=" + xaRollbackCount,
                xaRollbackCount <= 2);
    }

    private int indexOfEventStartingWith(String prefix) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}