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
 * <p>修法：编码层按列 scale 归一（短补尾零、长 {@code HALF_UP} 舍入），对下推自带正确 scale 的列幂等。
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
    void textRoundsOverscaleHalfUp() {
        // 超采样 1.2345 → 列 scale=2 HALF_UP → "1.23"
        String row = textRow(2, new BigDecimal("1.2345"));
        assertTrue(row.contains("1.23"), "text 应 HALF_UP 舍入到 1.23，实际=" + row);
        // 1.2355 → 1.24（半舍远离零）
        String row2 = textRow(2, new BigDecimal("1.2355"));
        assertTrue(row2.contains("1.24"), "text 应 HALF_UP 舍入到 1.24，实际=" + row2);
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
        // 直接钉 helper 语义：null 原样、scale<0 原样、相等原样、短补、长舍
        assertEquals(null, ResultSetMapping.scaleToColumn(null, 2));
        assertEquals(new BigDecimal("10"), ResultSetMapping.scaleToColumn(new BigDecimal("10"), -1));
        assertEquals(new BigDecimal("10.00"), ResultSetMapping.scaleToColumn(new BigDecimal("10"), 2));
        assertEquals(new BigDecimal("1.23"), ResultSetMapping.scaleToColumn(new BigDecimal("1.23"), 2));
        assertEquals(new BigDecimal("1.24"), ResultSetMapping.scaleToColumn(new BigDecimal("1.235"), 2));
    }
}
