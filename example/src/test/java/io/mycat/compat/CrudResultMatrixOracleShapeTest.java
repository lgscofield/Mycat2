package io.mycat.compat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C12 oracle 舍入形态判定的离线守卫（I-DEBT-61 收口轮·mariadb CI 红 fix-forward）。
 *
 * <p>MariaDB 对<b>裸</b> STDDEV()/VARIANCE() 确定性舍入到 4 位小数（实测 10.11：STDDEV→1.9720、
 * VARIANCE→3.8889；STDDEV_POP 等显式后缀同为 4 位），而 Mycat 跨片本地归并（Calcite reduce）与
 * mysql-connector 通道返全精度。C12 的口径断言不能拿舍入 oracle 用相对 1e-5 硬比（必然假红），
 * 须识别"oracle 是舍入形态"并放宽到覆盖 ±0.5×10^-4 舍入误差的容差——而样本口径(n-1)偏差 ≫ 该
 * 容差，判别力不失。本锁钉住该形态判定的两个方向。
 */
class CrudResultMatrixOracleShapeTest {

    /** MariaDB 舍入态：scale≤4 且非精确 0（1.972/1.9720/3.8889）。 */
    @Test
    void roundedOracleIsDetected() {
        assertTrue(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("1.972")));
        assertTrue(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("1.9720")));
        assertTrue(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("3.8889")));
        assertTrue(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("4.6667")));
    }

    /** MySQL 全精度态（6+ 位小数）不判为舍入形态——此时仍用严容差，防放宽掩盖真实偏差。 */
    @Test
    void fullPrecisionOracleIsNotRounded() {
        assertFalse(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("1.9720265943665387")));
        assertFalse(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("3.8888888888888893")));
        assertFalse(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("1.234567")));
    }

    /** 整数值（scale≤0）是精确结果而非舍入态：n=2 {1,3} 的 STDDEV=2、VARIANCE=1 类场景必须保持严容差。 */
    @Test
    void integralOracleIsNotRounded() {
        assertFalse(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("0")));
        assertFalse(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("1")));
        assertFalse(CrudResultMatrixTest.isRoundedOracleShape(new BigDecimal("2")));
    }
}
