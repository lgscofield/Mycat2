package io.mycat.runtime;

import io.mycat.MycatDataContext;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 会话告警缓冲（I-DEBT-69 Blade A 地基）单元锁。
 *
 * <p>断言语义全部来自 2026-10-29 活体真库探针（MySQL 8.0 / 5.7 / MariaDB 10.11 结论一致），
 * 非凭记忆猜测：
 * <ul>
 *   <li><b>诊断区持“最近一条语句”的条件</b>：同一条语句内多条告警按产生顺序累积，
 *       下一条语句入口清空（等价“替换”）——见 clearWarnings 用例。</li>
 *   <li><b>{@code warning_count} = 本语句条件总数，不被 {@code max_error_count} 截断</b>
 *       （活体：{@code SET max_error_count=1; SELECT 1/0,2/0,3/0} → {@code @@warning_count}=3）；
 *       截断只作用于列表，属读面行为（Blade B），故缓冲侧必须存全量。</li>
 *   <li><b>计数与明细同源</b>：{@code getWarningCount()} 必须等于 {@code getWarnings().size()}，
 *       不得各算各的。</li>
 * </ul>
 */
public class SessionWarningBufferTest {

    /** 覆写 switchTransaction 为 no-op：绕构造期运行时单例（同包既有测试既定姿势）。 */
    static class Ctx extends MycatDataContextImpl {
        @Override
        public void switchTransaction(TransactionType transactionSessionType) {
            // no-op
        }
    }

    @Test
    public void warningsAccumulateInInsertionOrderAndCountSharesBufferSource() {
        Ctx ctx = new Ctx();
        Assertions.assertEquals(0, ctx.getWarningCount(), "fresh session must have no warning");
        Assertions.assertTrue(ctx.getWarnings().isEmpty(), "fresh buffer must be empty");

        ctx.addWarning(new WarningMessage("Warning", 1365, "Division by 0"));
        ctx.addWarning(new WarningMessage("Note", 1003, "Query rewritten"));
        ctx.addWarning(new WarningMessage("Error", 1406, "Data too long"));

        List<WarningMessage> warnings = ctx.getWarnings();
        Assertions.assertEquals(3, warnings.size(), "all produced conditions must be buffered");
        Assertions.assertEquals("Warning", warnings.get(0).getLevel(),
                "buffer must preserve production order");
        Assertions.assertEquals(1365, warnings.get(0).getCode());
        Assertions.assertEquals("Division by 0", warnings.get(0).getMessage());
        Assertions.assertEquals("Note", warnings.get(1).getLevel());
        Assertions.assertEquals("Error", warnings.get(2).getLevel());

        Assertions.assertEquals(ctx.getWarnings().size(), ctx.getWarningCount(),
                "warning_count must be same source as the buffer");
    }

    @Test
    public void clearWarningsResetsBothBufferAndCount() {
        Ctx ctx = new Ctx();
        ctx.addWarning(new WarningMessage("Warning", 1265, "Data truncated"));
        Assertions.assertEquals(1, ctx.getWarningCount());

        ctx.clearWarnings();

        Assertions.assertTrue(ctx.getWarnings().isEmpty(), "buffer must be empty after clear");
        Assertions.assertEquals(0, ctx.getWarningCount(), "count must follow the cleared buffer");

        // 清空后重新累积仍自洽（对应真库“下一条语句替换诊断区”）
        ctx.addWarning(new WarningMessage("Warning", 1365, "Division by 0"));
        Assertions.assertEquals(1, ctx.getWarnings().size());
        Assertions.assertEquals(1, ctx.getWarningCount());
    }

    /**
     * 活体钉死：计数不受 max_error_count 影响，缓冲必须存全量，
     * 否则读面无从按 max_error_count 截断列表而同时报出全量计数。
     * （max_error_count 的会话变量支持属 Blade C，这里只锁缓冲侧不变量）
     */
    @Test
    public void bufferKeepsAllConditionsRegardlessOfMaxErrorCount() {
        MycatDataContext ctx = new Ctx();
        ctx.addWarning(new WarningMessage("Warning", 1365, "Division by 0"));
        ctx.addWarning(new WarningMessage("Warning", 1365, "Division by 0"));
        ctx.addWarning(new WarningMessage("Warning", 1365, "Division by 0"));

        Assertions.assertEquals(3, ctx.getWarningCount(),
                "count must NOT be truncated by max_error_count (live MySQL: 3)");
        Assertions.assertEquals(3, ctx.getWarnings().size());
    }

    /** 缓冲对外必须是只读视图：读面不得改写诊断区（防 SHOW WARNINGS 顺手 clear 掉）。 */
    @Test
    public void getWarningsReturnsReadOnlyView() {
        Ctx ctx = new Ctx();
        ctx.addWarning(new WarningMessage("Warning", 1365, "Division by 0"));
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ctx.getWarnings().clear());
        Assertions.assertThrows(NullPointerException.class,
                () -> ctx.addWarning(null));
    }
}
