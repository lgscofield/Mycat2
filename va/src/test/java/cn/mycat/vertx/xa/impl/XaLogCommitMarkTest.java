package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.ImmutableCoordinatorLog;
import cn.mycat.vertx.xa.ImmutableParticipantLog;
import cn.mycat.vertx.xa.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MYCAT CHANGE (I-DEBT-115): 事务面卫生两案锁——
 * ① XaLogImpl.logCommitBeforeXaCommit / Repository.cancelCommitLog 把不可变对象 withCommit(...)
 *    的返回值丢弃 → commitMarked 恒不落库（prepare 完成标记丢失，恢复决策面误判）；
 * ② ImmutableCoordinatorLog.computeMinState 对空参与者数组 allMatch 恒真 → 返回 XA_COMMITED；
 * ③ XaLogImpl.checkState 对 get(xid)==null 恒 true。
 */
public class XaLogCommitMarkTest {

    private static ImmutableCoordinatorLog oneParticipantLog(State state) {
        return new ImmutableCoordinatorLog("xid-1",
                new ImmutableParticipantLog[]{new ImmutableParticipantLog("ds1", 0, state)});
    }

    ////////////////////////////////////////////////////////////////
    // ① withCommit 丢弃
    ////////////////////////////////////////////////////////////////

    @Test
    public void logCommitBeforeXaCommitMustPersistCommitMark() {
        MemoryRepositoryImpl repository = new MemoryRepositoryImpl();
        XaLogImpl xaLog = new XaLogImpl(repository, 0, null);
        repository.put("xid-1", oneParticipantLog(State.XA_PREPARED));

        ImmutableCoordinatorLog logged = xaLog.logCommitBeforeXaCommit("xid-1");
        ImmutableCoordinatorLog stored = repository.get("xid-1");
        assertTrue(logged.mayContains(State.XA_COMMITED), "返回对象必须带 commitMarked");
        assertTrue(stored.mayContains(State.XA_COMMITED), "落库对象必须带 commitMarked");
        assertFalse(stored.mayContains(State.XA_PREPARED),
                "commitMarked 置位后 mayContains 只认 XA_COMMITED");
    }

    @Test
    public void cancelCommitLogMustPersistCommitMarkFalse() {
        MemoryRepositoryImpl repository = new MemoryRepositoryImpl();
        repository.put("xid-1", oneParticipantLog(State.XA_PREPARED).withCommit(true));

        repository.cancelCommitLog("xid-1");
        ImmutableCoordinatorLog stored = repository.get("xid-1");
        assertFalse(stored.mayContains(State.XA_COMMITED),
                "取消 commit 标记后不得再呈 XA_COMMITED 意向");
        assertTrue(stored.mayContains(State.XA_PREPARED),
                "取消后回落到参与者真实状态");
    }

    ////////////////////////////////////////////////////////////////
    // ② computeMinState 空数组 vacuous-true
    ////////////////////////////////////////////////////////////////

    @Test
    public void computeMinStateOnEmptyParticipantsMustNotBeCommitEd() {
        ImmutableCoordinatorLog empty = new ImmutableCoordinatorLog("xid-1",
                new ImmutableParticipantLog[]{});
        assertEquals(State.XA_INITED, empty.computeMinState(),
                "空参与者数组必须显式返回中性态，不得 vacuous-true 返回 XA_COMMITED");
    }

    @Test
    public void computeMinStateNormalSemanticsUnchanged() {
        assertEquals(State.XA_COMMITED,
                new ImmutableCoordinatorLog("x", new ImmutableParticipantLog[]{
                        new ImmutableParticipantLog("a", 0, State.XA_COMMITED),
                        new ImmutableParticipantLog("b", 0, State.XA_COMMITED)}).computeMinState());
        assertEquals(State.XA_PREPARED,
                new ImmutableCoordinatorLog("x", new ImmutableParticipantLog[]{
                        new ImmutableParticipantLog("a", 0, State.XA_PREPARED),
                        new ImmutableParticipantLog("b", 0, State.XA_COMMITED)}).computeMinState());
        assertEquals(State.XA_INITED,
                new ImmutableCoordinatorLog("x", new ImmutableParticipantLog[]{
                        new ImmutableParticipantLog("a", 0, State.XA_INITED),
                        new ImmutableParticipantLog("b", 0, State.XA_PREPARED)}).computeMinState());
    }

    ////////////////////////////////////////////////////////////////
    // ③ checkState null 协调者
    ////////////////////////////////////////////////////////////////

    @Test
    public void checkStateWithMissingCoordinatorLogMustBeFalse() {
        MemoryRepositoryImpl repository = new MemoryRepositoryImpl();
        XaLogImpl xaLog = new XaLogImpl(repository, 0, null);
        assertFalse(xaLog.checkState("missing-xid", true, State.XA_COMMITED),
                "缺失协调者日志时不得默认放行（恒 true）");
    }

    @Test
    public void checkStateWithRecordedLogUnchanged() {
        MemoryRepositoryImpl repository = new MemoryRepositoryImpl();
        repository.put("xid-1", oneParticipantLog(State.XA_PREPARED));
        XaLogImpl xaLog = new XaLogImpl(repository, 0, null);
        // 记录态 PREPARED ≠ 目标 COMMITED → true 分支为 false；succeed=false 时与"非目标态"匹配 → true
        assertFalse(xaLog.checkState("xid-1", true, State.XA_COMMITED));
        assertTrue(xaLog.checkState("xid-1", false, State.XA_COMMITED));
    }
}
