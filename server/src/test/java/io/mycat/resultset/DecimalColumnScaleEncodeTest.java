package io.mycat.resultset;

import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.vertx.ResultSetMapping;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-65 route(b) 活库机制锁（钉真实编码路径）。
 * <p>Mycat 前端所有结果集（含 HBT/Calcite 本地求值）统一经 {@code ReceiverImpl} 的 Observable
 * 观察器落 {@link ResultSetMapping#concertToDirectTextResultSet} /
 * {@link ResultSetMapping#concertToDirectBinaryResultSet} 编码（非 {@code TextResultSetResponse}
 * 实例路径——后者仅存在于已注释的 {@code SQLExecuterWriter}，是死代码）。
 * <p>缺陷靶心：本地 {@code cast(10 as decimal(10,2))} 求值产出 scale-0 的 {@code BigDecimal("10")}，
 * 而列元数据 scale 已由类型推断正确表征为 2；旧编码 DECIMAL 落 text 的 {@code default} 分支
 * {@code Objects.toString} 丢尾零出 {@code "10"}，MariaDB ground truth 为 {@code "10.00"}。
 * {@code BigDecimal.compareTo} 数值相等会静默掩盖该表征偏离，故断言口径=客户端可见字节串。
 * <p>修法（安全变体·仅补零）：编码层仅在 {@code 值 scale < 列 scale} 时补尾零（升 scale 数值无损），
 * 当 {@code 值 scale > 列 scale} 时**绝不降 scale**——因 Mycat 计算/聚合列（如 {@code avg(decimal(20,4))}
 * 真值 scale=8、元数据低报为 4）的列 scale 不可靠，无条件下采会把已正确的 BigDecimal 截成错值
 * （活库 CI 实证 {@code avg(d)} 3.66666667 被截成 3.6667 的值保真回归）。CAST 超采样值的舍入到目标 scale
 * 属值生成腿（codegen 期 round），不在编码缝强裁。
 */
class DecimalColumnScaleEncodeTest {

    private static MycatRowMetaData decimalMeta(int scale) {
        return ResultSetBuilder.create()
                .addColumnInfo("t", "c", Types.DECIMAL, 10, scale)
                .build().getMetaData();
    }

    private static String textRow(int scale, Object value) {
        Function<Object[], byte[]> fn = ResultSetMapping.concertToDirectTextResultSet(decimalMeta(scale));
        return new String(fn.apply(new Object[]{value}), StandardCharsets.UTF_8);
    }

    private static String binaryRow(int scale, Object value) {
        Function<Object[], byte[]> fn = ResultSetMapping.concertToDirectBinaryResultSet(decimalMeta(scale));
        return new String(fn.apply(new Object[]{value}), StandardCharsets.UTF_8);
    }

    @Test
    void textPadsTrailingZerosToColumnScale() {
        // scale-0 值 + 列 scale=2 → 补零 "10.00"（修前落 default 出 "10"）
        String row = textRow(2, new BigDecimal("10"));
        assertTrue(row.contains("10.00"), "text 应补齐尾零，实际=" + row);
    }

    @Test
    void binaryPadsTrailingZerosToColumnScale() {
        String row = binaryRow(2, new BigDecimal("10"));
        assertTrue(row.contains("10.00"), "binary 应补齐尾零，实际=" + row);
    }

    @Test
    void textDoesNotTruncateOverscaleValue() {
        // 安全变体关键：值 scale(4) > 列 scale(2) 时**绝不降 scale**（不截断/不舍入），
        // 因计算/聚合列（如 avg(decimal)）元数据 scale 会低报，强裁会把已正确值截成错值。
        String row = textRow(2, new BigDecimal("1.2345"));
        assertTrue(row.contains("1.2345"), "text 不得截断超列 scale 的值（应保留 1.2345），实际=" + row);
    }

    @Test
    void binaryDoesNotTruncateOverscaleValue() {
        // 与 textDoesNotTruncateOverscaleValue 对称钉 binary 调用点：聚合列（avg(decimal(20,4))
        // 真值 scale=8、元数据低报为 4）走 binary 缝时，值 scale 高于列 scale 不得被下采截断，
        // 防 08b63feb9 无条件下采腐蚀 avg 值的 CI 回归在 binary 腿重演。
        String row = binaryRow(4, new BigDecimal("3.66666667"));
        assertTrue(row.contains("3.66666667"), "binary 不得截断超列 scale 的聚合值，实际=" + row);
    }

    @Test
    void textIsIdempotentForAlreadyScaledValue() {
        // 后端下推已带正确 scale 的列（BigDecimal("10.00") 值 scale=2）不变
        String row = textRow(2, new BigDecimal("10.00"));
        assertTrue(row.contains("10.00"), "text 幂等，实际=" + row);
    }

    @Test
    void textScaleZeroKeepsIntegerForm() {
        // decimal(10,0)：scale=0，BigDecimal("10") 保持 "10"（对齐 cast(10 as decimal(10,0))）
        String row = textRow(0, new BigDecimal("10"));
        assertTrue(row.contains("10"), "text scale0 保持整数形态，实际=" + row);
        // 不应出现小数点补零
        assertTrue(!row.contains("10."), "scale0 不应补小数位，实际=" + row);
    }

    @Test
    void scaleToColumnHelperSemantics() {
        // 仅补零语义：null 原样、scale<0 原样、值 scale>=列 scale 原样（不降）、值 scale<列 scale 补零
        assertEquals(null, ResultSetMapping.scaleToColumn(null, 2));
        assertEquals(new BigDecimal("10"), ResultSetMapping.scaleToColumn(new BigDecimal("10"), -1));
        assertEquals(new BigDecimal("10.00"), ResultSetMapping.scaleToColumn(new BigDecimal("10"), 2));
        // 关键回归锁：值 scale 高于列 scale 不得被截断（avg(decimal(20,4)) 元数据低报为 4 的场景）
        assertEquals(new BigDecimal("3.66666667"), ResultSetMapping.scaleToColumn(new BigDecimal("3.66666667"), 4));
        assertEquals(new BigDecimal("1.2345"), ResultSetMapping.scaleToColumn(new BigDecimal("1.2345"), 2));
    }
}

