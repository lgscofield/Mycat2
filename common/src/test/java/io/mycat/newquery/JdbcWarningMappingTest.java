package io.mycat.newquery;

import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.SQLWarning;
import java.util.List;

/**
 * I-DEBT-69 Blade D：JDBC 后端的 {@link SQLWarning} 链 → {@link WarningMessage} 列表。
 *
 * <p>背景：{@code MycatDataContext} 的诊断缓冲只认 Level/Code/Message 三元组，
 * 而 JDBC 只给出 {@code SQLWarning}（message/sqlState/vendorCode）。映射必须保序、
 * 且能防驱动返回自环链——否则采集侧会死循环而不是报错。
 *
 * <p>诚实边界：JDBC 只暴露 Warning 级条件，真库的 Note 级（如 1003）在 JDBC 面不可见，
 * 本映射统一标注 "Warning"，不做级别臆造。
 */
public class JdbcWarningMappingTest {

    @Test
    public void mapsWarningChainInProductionOrder() {
        SQLWarning first = new SQLWarning("Data truncated for column 'a' at row 1", "01000", 1265);
        SQLWarning second = new SQLWarning("Data too long for column 'b' at row 1", "01000", 1406);
        first.setNextWarning(second);

        List<WarningMessage> messages = NewMycatConnectionImpl.toWarningMessages(first);

        Assertions.assertEquals(2, messages.size(), "整条告警链都要采集");
        Assertions.assertEquals(new WarningMessage("Warning", 1265,
                "Data truncated for column 'a' at row 1"), messages.get(0), "必须保序");
        Assertions.assertEquals(new WarningMessage("Warning", 1406,
                "Data too long for column 'b' at row 1"), messages.get(1));
    }

    @Test
    public void noWarningYieldsEmptyList() {
        Assertions.assertTrue(NewMycatConnectionImpl.toWarningMessages(null).isEmpty(),
                "无告警必须返回空列表而非 null");
    }

    @Test
    public void selfReferencingChainTerminates() {
        SQLWarning cyclic = new SQLWarning("cycle", "01000", 1);
        cyclic.setNextWarning(cyclic);

        Assertions.assertEquals(1, NewMycatConnectionImpl.toWarningMessages(cyclic).size(),
                "驱动给出自环链时必须终止，不得死循环");
    }
}
