package io.mycat.calcite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.RoundingMode;

import org.junit.jupiter.api.Test;

/**
 * I-DEBT-66 R1（舍入模式面）：{@code MycatTypeSystem.roundingMode()} 必须是 MySQL/MariaDB 的
 * <b>HALF_UP（四舍五入）</b>，而非委托 {@code RelDataTypeSystem.DEFAULT} 的 DOWN（截断）。
 *
 * <p>该模式在出码期被烘进 fork 原生的 {@code *_DECIMAL_CAST_ROUNDING_MODE} 调用
 * （{@code RexToLixTranslator.getConvertExpression} 的 DECIMAL 目标腿），并影响
 * {@code ReduceDecimalsRule} 等算术降 scale 路。B1（{@code MycatCastOverride} 对 DECIMAL 目标
 * 让路 fork 原生）落地后，本锁的语义才真正到达值面：{@code cast(1.2355 as decimal(10,2))} 应得
 * {@code 1.24}（真库实测），DOWN 会得 {@code 1.23}（截断，与真库不符）。
 * 编码层（I-DEBT-65）亦已按 HALF_UP 口径补零，两处须同源。
 */
class MycatTypeSystemRoundingModeTest {

    @Test
    void roundingModeIsHalfUpForMysqlSemantics() {
        assertEquals(RoundingMode.HALF_UP,
                new MycatCalciteSupport.MycatTypeSystem().roundingMode(),
                "I-DEBT-66 R1：DECIMAL 舍入模式须为 HALF_UP（MySQL CAST/除法四舍五入），不得委托 DEFAULT 的 DOWN");
    }
}
