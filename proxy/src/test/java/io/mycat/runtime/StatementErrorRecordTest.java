package io.mycat.runtime;

import io.mycat.beans.mycat.TransactionType;
import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Blade F（I-DEBT-69 Error 级生产者）数据面锁：{@code recordStatementError}。
 *
 * <p>断言语义来自 2026-11-01 双档活体探针（scratch/probe*.txt，MySQL 8.0.46 与 MariaDB 10.11 一致）：
 * <ul>
 *   <li>C1：语句级失败必进诊断区，Level={@code Error}、Code 与 ERR 包同源（1062）、Message 同源；
 *       wc=1/ec=1（warning_count 为全量条件数、error_count 为 Error 子集）。</li>
 *   <li>D0：Error 条件与同语句此前告警的"替换"由语句入口 clearWarnings 承担（既有锁），
 *       生产者只负责把 Error 写进缓冲。</li>
 *   <li>null message 兜底：{@code WarningMessage} 对 message requireNonNull，生产者入口必须兜底，
 *       否则错误出口自身抛 NPE 掩盖原始错误。</li>
 * </ul>
 */
public class StatementErrorRecordTest {

    /** 覆写 switchTransaction 为 no-op：绕构造期运行时单例（同包既有测试既定姿势）。 */
    static class Ctx extends MycatDataContextImpl {
        @Override
        public void switchTransaction(TransactionType transactionSessionType) {
            // no-op
        }
    }

    @Test
    public void recordStatementErrorAppendsErrorLevelCondition() {
        Ctx ctx = new Ctx();
        ctx.recordStatementError(1062, "Duplicate entry '1' for key 't.id'");

        List<WarningMessage> warnings = ctx.getWarnings();
        Assertions.assertEquals(1, warnings.size(), "failed statement must put exactly one condition into diagnostics");
        WarningMessage err = warnings.get(0);
        Assertions.assertEquals("Error", err.getLevel(), "statement failure is Error level (live probe C1)");
        Assertions.assertEquals(1062, err.getCode(), "code must share source with the ERR packet");
        Assertions.assertEquals("Duplicate entry '1' for key 't.id'", err.getMessage());
        Assertions.assertEquals(1, ctx.getWarningCount(), "@@warning_count counts ALL conditions incl. Error (C1: wc=1)");
    }

    @Test
    public void nullMessageFallsBackToEmptyStringWithoutThrowing() {
        Ctx ctx = new Ctx();
        ctx.recordStatementError(1105, null);

        List<WarningMessage> warnings = ctx.getWarnings();
        Assertions.assertEquals(1, warnings.size(), "null message must still be recorded, never swallowed");
        Assertions.assertEquals("Error", warnings.get(0).getLevel());
        Assertions.assertEquals("", warnings.get(0).getMessage(), "null message falls back to empty string");
    }

    @Test
    public void recordedErrorIsArchivedAsErrorCountOnNextStatement() {
        Ctx ctx = new Ctx();
        ctx.addWarning(new WarningMessage("Warning", 1265, "Data truncated"));
        ctx.recordStatementError(1406, "Data too long for column 'note' at row 1");

        // 下一条语句入口替换（归档 previous*），error_count = Error 子集数（C2: wc=1,ec=1）
        ctx.clearWarnings();
        Assertions.assertEquals(2, ctx.getPreviousWarningCount(),
                "previousWarningCount archives the FULL condition count incl. Error");
        Assertions.assertEquals(1, ctx.getPreviousErrorCount(),
                "previousErrorCount archives only Error-level subset");
        Assertions.assertTrue(ctx.getWarnings().isEmpty(), "buffer replaced, not replayed");
    }
}
