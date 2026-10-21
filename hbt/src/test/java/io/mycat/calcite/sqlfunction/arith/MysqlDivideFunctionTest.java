package io.mycat.calcite.sqlfunction.arith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-39·tranche①（宽）：Mycat 本地 `/` 的 MySQL 除法语义锁（纯方法级，无管线依赖）。
 *
 * <p>权威口径取自真实 MySQL 5.7.44 活库直连复拍：10/3=3.3333、7/2=3.5000、10.0/4=2.50000（scale=被除数scale+4）、
 * 1/0=NULL、近似型→double。整型入参按 I-DEBT-60 恒 boxed Long 表征传入。
 */
class MysqlDivideFunctionTest {

    @Test
    void decimalQuotientMatchesMySQL() {
        assertEquals(new BigDecimal("3.3333"), MysqlDivideFunction.mysqlDivide(10L, 3L));
        assertEquals(new BigDecimal("3.5000"), MysqlDivideFunction.mysqlDivide(7L, 2L));
        assertEquals(new BigDecimal("2.50000"),
                MysqlDivideFunction.mysqlDivide(new BigDecimal("10.0"), 4L));
        assertEquals(new BigDecimal("3.33333"),
                MysqlDivideFunction.mysqlDivide(new BigDecimal("10.0"), 3L));
    }

    @Test
    void divideByZeroYieldsNull() {
        assertNull(MysqlDivideFunction.mysqlDivide(1L, 0L));
        assertNull(MysqlDivideFunction.mysqlDivide(new BigDecimal("1.0"), BigDecimal.ZERO));
    }

    @Test
    void nullOperandPropagates() {
        assertNull(MysqlDivideFunction.mysqlDivide(null, 3L));
        assertNull(MysqlDivideFunction.mysqlDivide(10L, null));
    }

    @Test
    void approximateStaysDouble() {
        Object r = MysqlDivideFunction.mysqlDivide(10.0d, 4.0d);
        assertTrue(r instanceof Double, "近似型除法结果应为 Double，实得 " + (r == null ? "null" : r.getClass()));
        assertEquals(2.5d, (Double) r, 1e-9);
        assertNull(MysqlDivideFunction.mysqlDivide(5.0d, 0.0d));
    }
}
