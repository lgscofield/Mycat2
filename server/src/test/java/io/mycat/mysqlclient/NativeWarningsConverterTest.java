package io.mycat.mysqlclient;

import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-69 Blade G（刀2）：NATIVE 池 SHOW WARNINGS 往返行 → WarningMessage 转换锁。
 *
 * <p>输入为后端结果集行（Level/Code/Message 三列，文本协议解码后为 Object[]）；
 * 输出进入 SqlResult.warnings，维持「计数由列表派生」契约——行数与 OK 包计数不一致时
 * 只如实保留行数，绝不合成假条目补差（宁挂账不伪绿）。
 */
public class NativeWarningsConverterTest {

    @Test
    public void convertsThreeColumnRowsInOrder() {
        List<Object[]> rows = List.of(
                new Object[]{"Warning", "1265", "Data truncated for column 'a' at row 1"},
                new Object[]{"Note", "1003", "Query rewritten"});

        List<WarningMessage> warnings = VertxConnection.convertNativeWarningRows(rows);

        Assertions.assertEquals(2, warnings.size());
        Assertions.assertEquals("Warning", warnings.get(0).getLevel());
        Assertions.assertEquals(1265, warnings.get(0).getCode());
        Assertions.assertEquals("Data truncated for column 'a' at row 1", warnings.get(0).getMessage());
        Assertions.assertEquals("Note", warnings.get(1).getLevel());
        Assertions.assertEquals(1003, warnings.get(1).getCode());
    }

    @Test
    public void nullAndUnparseableCellsDegradeWithoutThrowing() {
        List<Object[]> rows = List.of(
                new Object[]{null, null, null},
                new Object[]{"Warning", "not-a-number", null});

        List<WarningMessage> warnings = VertxConnection.convertNativeWarningRows(rows);

        Assertions.assertEquals(2, warnings.size(), "行不得被静默吞掉，缺省值必须可用");
        Assertions.assertEquals("Warning", warnings.get(0).getLevel(), "null Level 退 Warning");
        Assertions.assertEquals(0, warnings.get(0).getCode());
        Assertions.assertEquals("", warnings.get(0).getMessage());
        Assertions.assertEquals(0, warnings.get(1).getCode(), "非数字码退化 0，不得 NumberFormatException");
        Assertions.assertEquals("", warnings.get(1).getMessage());
    }

    @Test
    public void nullRowsYieldEmptyList() {
        Assertions.assertEquals(Collections.emptyList(), VertxConnection.convertNativeWarningRows(null));
        Assertions.assertEquals(Collections.emptyList(),
                VertxConnection.convertNativeWarningRows(Collections.emptyList()));
    }

    /**
     * 真库活体格抓出的形状：MySQL SHOW WARNINGS 的 Code 列是数值列（charset 63），
     * {@code StringArrayDecoder} 对 charset 63 列返回装箱 {@code Byte[]}（ASCII 字节），
     * 而非 String。若直接 String.valueOf 得 {@code [Ljava.lang.Byte;} → 解析退化 0（伪绿）。
     * 本锁钉真实后端形态：Byte[] 单元格必须按 ASCII 还原为数字。
     */
    @Test
    public void decodesByteArrayCellsFromRealNumericCodeColumn() {
        Byte[] code = new Byte[]{ (byte) '1', (byte) '2', (byte) '6', (byte) '5' };
        Byte[] level = new Byte[]{ (byte) 'W', (byte) 'a', (byte) 'r', (byte) 'n', (byte) 'i', (byte) 'n', (byte) 'g' };
        List<Object[]> rows = java.util.Collections.singletonList(
                new Object[]{ level, code, "Data truncated for column 'v' at row 1" });

        List<WarningMessage> warnings = VertxConnection.convertNativeWarningRows(rows);

        Assertions.assertEquals(1, warnings.size());
        Assertions.assertEquals(1265, warnings.get(0).getCode(), "Byte[] 形态的 Code 必须按 ASCII 还原为 1265，不得退 0");
        Assertions.assertEquals("Warning", warnings.get(0).getLevel(), "Byte[] 形态的 Level 也须还原");
        Assertions.assertEquals("Data truncated for column 'v' at row 1", warnings.get(0).getMessage());
    }

    @Test
    public void decodesPrimitiveByteArrayCells() {
        byte[] code = "1365".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<Object[]> rows = java.util.Collections.singletonList(new Object[]{ "Warning", code, "Division by 0" });

        List<WarningMessage> warnings = VertxConnection.convertNativeWarningRows(rows);

        Assertions.assertEquals(1365, warnings.get(0).getCode(), "byte[] 形态的 Code 同样须还原");
    }
}
