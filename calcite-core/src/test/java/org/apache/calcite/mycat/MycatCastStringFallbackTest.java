package org.apache.calcite.mycat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * F3 同族（I-DEBT-118）离线锁：calcite-core 侧 {@link MycatBuiltInMethodImpl}（本模块副本，
 * MycatBuiltInMethod 枚举的驻留依赖）string→数值路线的 MySQL 宽松语义——非法串=0 值、
 * 绝不抛 NumberFormatException；NULL 透传不变。告警面经 {@code MycatCalciteRuntime.Hooks#
 * castTruncatedWarning}（默认 no-op，Mycat 侧装配后走诊断槽），值语义在此锁死。
 */
public class MycatCastStringFallbackTest {

    @Test
    public void invalidStringsFallBackToZeroWithoutThrowing() {
        assertEquals((byte) 0, MycatBuiltInMethodImpl.stringToTinyint("abc"));
        assertEquals((short) 0, MycatBuiltInMethodImpl.stringToSmallint("x!"));
        assertEquals(0L, MycatBuiltInMethodImpl.stringToBigint("abc"));
        assertEquals(0L, MycatBuiltInMethodImpl.stringToBigint(""));
        assertEquals(0, BigDecimal.ZERO.compareTo(MycatBuiltInMethodImpl.stringToDecimal("abc")));
        assertEquals(0.0d, MycatBuiltInMethodImpl.stringToDouble("abc"), 0.0d);
        assertEquals(0.0d, MycatBuiltInMethodImpl.stringToFloat("abc"), 0.0d);
        assertEquals(0.0f, MycatBuiltInMethodImpl.stringToReal("abc"), 0.0f);
        assertEquals(0, BigDecimal.ZERO.compareTo(MycatBuiltInMethodImpl.bytestringToDecimal(
                new org.apache.calcite.avatica.util.ByteString("abc".getBytes()))));
    }

    @Test
    public void validStringsUnchanged() {
        assertEquals((byte) 42, MycatBuiltInMethodImpl.stringToTinyint("42"));
        assertEquals(42L, MycatBuiltInMethodImpl.stringToBigint("42"));
        assertEquals(new BigDecimal("3.14"), MycatBuiltInMethodImpl.stringToDecimal("3.14"));
        assertEquals(2.5d, MycatBuiltInMethodImpl.stringToDouble("2.5"), 1e-12);
        assertEquals(2.5f, MycatBuiltInMethodImpl.stringToReal("2.5"), 1e-12);
    }

    @Test
    public void nullPassesThrough() {
        assertNull(MycatBuiltInMethodImpl.stringToTinyint(null));
        assertNull(MycatBuiltInMethodImpl.stringToBigint(null));
        assertNull(MycatBuiltInMethodImpl.stringToDecimal(null));
        assertNull(MycatBuiltInMethodImpl.stringToDouble(null));
        assertNull(MycatBuiltInMethodImpl.stringToReal(null));
    }
}
