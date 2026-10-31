package io.mycat.newquery;

import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-69 Blade D：{@link SqlResult} 必须把后端语句产生的诊断条件带回调用方。
 *
 * <p>判据（真库口径）：后端执行 {@code INSERT IGNORE} 截断时，OK 包 warnings 计数与
 * {@code SHOW WARNINGS} 列表同源；本类只钉“结果对象携带告警”这一层，不让计数与列表各算各的。
 */
public class SqlResultWarningsTest {

    @Test
    public void freshResultCarriesNoWarnings() {
        SqlResult result = new SqlResult();

        Assertions.assertTrue(result.getWarnings().isEmpty(), "新建结果不得凭空带告警");
        Assertions.assertEquals(0, result.getWarningCount(), "无告警时计数必须为 0");
    }

    @Test
    public void warningCountSharesWarningListSource() {
        SqlResult result = new SqlResult();
        result.setWarnings(List.of(
                new WarningMessage("Warning", 1265, "Data truncated for column 'a' at row 1"),
                new WarningMessage("Warning", 1406, "Data too long for column 'b' at row 1")));

        Assertions.assertEquals(2, result.getWarningCount(), "计数必须与列表同源，不得单独维护");
        Assertions.assertEquals(1265, result.getWarnings().get(0).getCode());
    }

    @Test
    public void settingNullWarningsDegradesToEmptyList() {
        SqlResult result = new SqlResult();
        result.setWarnings(null);

        Assertions.assertEquals(Collections.emptyList(), result.getWarnings(),
                "null 必须退化为空列表，读腿不得 NPE");
        Assertions.assertEquals(0, result.getWarningCount());
    }
}
