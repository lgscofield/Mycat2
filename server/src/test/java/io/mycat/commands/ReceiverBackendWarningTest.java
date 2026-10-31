package io.mycat.commands;

import io.mycat.beans.mycat.WarningMessage;
import io.mycat.newquery.SqlResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-69 Blade D：一条前端 DML 可能落到多个后端数据源，各自带回自己的诊断条件。
 *
 * <p>判据：前端会话缓冲必须是**所有后端结果按处理顺序拼接**，且顺序稳定——否则
 * {@code SHOW WARNINGS} 的行序与真库（按条件产生顺序）不一致。
 */
public class ReceiverBackendWarningTest {

    @Test
    public void collectsWarningsFromEveryBackendResult() {
        SqlResult first = SqlResult.of(1, 0, List.of(
                new WarningMessage("Warning", 1265, "truncated a")));
        SqlResult second = SqlResult.of(1, 0, List.of(
                new WarningMessage("Warning", 1406, "too long b"),
                new WarningMessage("Warning", 1265, "truncated c")));

        List<WarningMessage> merged = ReceiverImpl.collectBackendWarnings(Arrays.asList(first, second));

        Assertions.assertEquals(3, merged.size(), "每个后端结果的告警都要进前端缓冲");
        Assertions.assertEquals(1265, merged.get(0).getCode());
        Assertions.assertEquals(1406, merged.get(1).getCode());
        Assertions.assertEquals("truncated c", merged.get(2).getMessage());
    }

    @Test
    public void noWarningsYieldsEmptyList() {
        List<WarningMessage> merged = ReceiverImpl.collectBackendWarnings(
                Collections.singletonList(SqlResult.of(1, 0)));

        Assertions.assertTrue(merged.isEmpty(), "无告警时不得凭空造行");
    }

    @Test
    public void emptyBackendResultsYieldEmptyList() {
        Assertions.assertTrue(ReceiverImpl.collectBackendWarnings(Collections.emptyList()).isEmpty());
    }
}
