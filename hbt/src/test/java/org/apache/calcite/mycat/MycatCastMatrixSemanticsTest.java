/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package org.apache.calcite.mycat;

import io.mycat.MycatDataContext;
import io.mycat.beans.mycat.WarningMessage;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-118：CAST 矩阵 string→数值路线的 MySQL 宽松语义锁（同 F3 签名换目标类型）。
 *
 * <p>背景：{@code MycatBuiltInMethodImpl} 的 stringToTinyint/stringToSmallint/stringToBigint/
 * stringToDecimal/stringToDouble/stringToFloat/stringToReal（及 ByteString 同族）对非法串裸
 * {@code parse*}/{@code new BigDecimal}——非法串直接 {@code NumberFormatException} 杀会话。
 * F3 已修 calcite-core {@code SqlFunctions.toInt/toLong} 同病（MYCAT CHANGE 标记：非法=0+告警 1292，
 * 绝不抛）；本票对齐 MycatBuiltInMethodImpl 系。真库口径：{@code CAST('abc' AS SIGNED)}=0 + Warning 1292
 * {@code Truncated incorrect INTEGER value: 'abc'}；DECIMAL 同理 0；浮点 0.0。NULL 透传语义不变。
 *
 * <p>告警通道 = I-DEBT-97 诊断槽（{@code NewMycatDataContext.currentDiagnosticContext()}，先例
 * {@code MysqlDivideFunction.emitDivisionByZeroWarning}）；无槽执行路径降级静默，不抛不种槽。
 * 每格矩阵：每类型 × {非法串 / 合法串 / null} 三格；非法格断言 0 值 + 1292 告警，合法格断言不产告警。
 */
class MycatCastMatrixSemanticsTest {

    private List<WarningMessage> sink;
    private MycatDataContext ctx;
    private MycatDataContext previousSlot;

    @BeforeEach
    void installSlot() {
        sink = new ArrayList<>();
        ctx = newProxiedContext(sink);
        previousSlot = NewMycatDataContext.installDiagnosticContext(ctx);
    }

    @AfterEach
    void restoreSlot() {
        NewMycatDataContext.restoreDiagnosticContext(previousSlot);
        assertNull(NewMycatDataContext.currentDiagnosticContext(), "restore 后槽必须摘净");
    }

    private static final String TRUNCATED = "Truncated incorrect";

    @Test
    void stringToTinyint_matrix() {
        assertEquals((byte) 42, MycatBuiltInMethodImpl.stringToTinyint("42"), "合法串不变");
        assertTrue(sink.isEmpty(), "合法串不得产告警");

        sink.clear();
        assertEquals((byte) 0, MycatBuiltInMethodImpl.stringToTinyint("abc"), "非法串→0（真库 CAST('abc' AS SIGNED)=0）");
        assertEquals(1, sink.size(), "非法串必须产 1292 告警");
        assertEquals(1292, sink.get(0).getCode());
        assertTrue(sink.get(0).getMessage().startsWith(TRUNCATED), "告警文案=Truncated incorrect ...，实际=" + sink.get(0).getMessage());

        sink.clear();
        assertNull(MycatBuiltInMethodImpl.stringToTinyint(null), "NULL 透传语义不变");
        assertTrue(sink.isEmpty(), "NULL 短路不产告警");
    }

    @Test
    void stringToSmallint_matrix() {
        assertEquals((short) -42, MycatBuiltInMethodImpl.stringToSmallint("-42"), "合法串不变");
        assertTrue(sink.isEmpty());

        sink.clear();
        assertEquals((short) 0, MycatBuiltInMethodImpl.stringToSmallint("x!"), "非法串→0");
        assertEquals(1292, sink.get(0).getCode());

        sink.clear();
        assertNull(MycatBuiltInMethodImpl.stringToSmallint(null));
        assertTrue(sink.isEmpty());
    }

    @Test
    void stringToBigint_matrix() {
        assertEquals(420000000000L, MycatBuiltInMethodImpl.stringToBigint("420000000000"), "合法串不变");
        assertTrue(sink.isEmpty());

        sink.clear();
        assertEquals(0L, MycatBuiltInMethodImpl.stringToBigint("abc"), "非法串→0（同 F3 SqlFunctions.toLong 口径）");
        assertEquals(1292, sink.get(0).getCode());

        // 空串同非法（真库 CAST('' AS SIGNED)=0+1292）
        sink.clear();
        assertEquals(0L, MycatBuiltInMethodImpl.stringToBigint(""));
        assertEquals(1, sink.size());

        sink.clear();
        assertNull(MycatBuiltInMethodImpl.stringToBigint(null));
        assertTrue(sink.isEmpty());
    }

    @Test
    void stringToDecimal_matrix() {
        assertEquals(new BigDecimal("3.14"), MycatBuiltInMethodImpl.stringToDecimal("3.14"), "合法串不变");
        assertTrue(sink.isEmpty());

        sink.clear();
        assertEquals(0, BigDecimal.ZERO.compareTo(MycatBuiltInMethodImpl.stringToDecimal("abc")),
                "非法串→0（真库 CAST('abc' AS DECIMAL)=0.00，scale 由 CAST 后处理统一施加）");
        assertEquals(1292, sink.get(0).getCode());

        sink.clear();
        assertNull(MycatBuiltInMethodImpl.stringToDecimal(null));
        assertTrue(sink.isEmpty());
    }

    @Test
    void stringToDouble_matrix() {
        assertEquals(2.5d, MycatBuiltInMethodImpl.stringToDouble("2.5"), 1e-12, "合法串不变");
        assertTrue(sink.isEmpty());

        sink.clear();
        assertEquals(0.0d, MycatBuiltInMethodImpl.stringToDouble("abc"), 0.0d, "非法串→0.0");
        assertEquals(1292, sink.get(0).getCode());

        sink.clear();
        assertNull(MycatBuiltInMethodImpl.stringToDouble(null));
        assertTrue(sink.isEmpty());
    }

    @Test
    void stringToFloat_matrix() {
        assertEquals(2.5d, MycatBuiltInMethodImpl.stringToFloat("2.5"), 1e-12, "合法串不变");
        assertTrue(sink.isEmpty());

        sink.clear();
        assertEquals(0.0d, MycatBuiltInMethodImpl.stringToFloat("abc"), 0.0d, "非法串→0.0");
        assertEquals(1292, sink.get(0).getCode());

        sink.clear();
        assertNull(MycatBuiltInMethodImpl.stringToFloat(null));
        assertTrue(sink.isEmpty());
    }

    @Test
    void stringToReal_matrix() {
        assertEquals(2.5f, MycatBuiltInMethodImpl.stringToReal("2.5"), 1e-12, "合法串不变");
        assertTrue(sink.isEmpty());

        sink.clear();
        assertEquals(0.0f, MycatBuiltInMethodImpl.stringToReal("abc"), 0.0f, "非法串→0.0f");
        assertEquals(1292, sink.get(0).getCode());

        sink.clear();
        assertNull(MycatBuiltInMethodImpl.stringToReal(null));
        assertTrue(sink.isEmpty());
    }

    @Test
    void byteStringFamily_matrix() {
        // ByteString 同族中会裸 parse 的 4 个方法（byteStringToTinyint/byteStringToBigint 走 bytes[0]
        // 直取、不 parse，不在本票族内）
        sink.clear();
        assertEquals(0, BigDecimal.ZERO.compareTo(MycatBuiltInMethodImpl.bytestringToDecimal(toBs("abc"))), "ByteString 非法串→0");
        assertEquals(1292, sink.get(0).getCode());

        sink.clear();
        assertEquals(0.0d, MycatBuiltInMethodImpl.byteStringToDouble(toBs("abc")), 0.0d, "ByteString double 非法串→0.0");
        assertEquals(1292, sink.get(0).getCode());

        sink.clear();
        assertEquals(0.0d, MycatBuiltInMethodImpl.byteStringToFloat(toBs("abc")), 0.0d, "ByteString float 非法串→0.0");
        assertEquals(1292, sink.get(0).getCode());

        sink.clear();
        assertEquals(0.0f, MycatBuiltInMethodImpl.byteStringToReal(toBs("abc")), 0.0f, "ByteString real 非法串→0.0f");
        assertEquals(1292, sink.get(0).getCode());

        // ByteString 合法串不变
        sink.clear();
        assertEquals(new BigDecimal("1.5"), MycatBuiltInMethodImpl.bytestringToDecimal(toBs("1.5")));
        assertTrue(sink.isEmpty(), "合法 ByteString 不产告警");
    }

    @Test
    void warningsAccumulatePerOccurrence() {
        // 与 MysqlDivideFunction 先例一致：按发生次数累积
        MycatBuiltInMethodImpl.stringToBigint("abc");
        MycatBuiltInMethodImpl.stringToDouble("xyz");
        assertEquals(2, sink.size(), "每次非法 CAST 各计一条");
    }

    @Test
    void noSlotDegradesSilently() {
        NewMycatDataContext.restoreDiagnosticContext(previousSlot);
        previousSlot = null; // @AfterEach 不再重复摘
        assertNull(NewMycatDataContext.currentDiagnosticContext(), "前置：无槽");
        assertEquals(0L, MycatBuiltInMethodImpl.stringToBigint("abc"), "无槽路径值语义仍=0");
        assertNull(MycatBuiltInMethodImpl.stringToBigint(null));
        assertNull(NewMycatDataContext.currentDiagnosticContext(), "无槽直调不得自行种槽");
    }

    private static org.apache.calcite.avatica.util.ByteString toBs(String s) {
        return new org.apache.calcite.avatica.util.ByteString(s.getBytes());
    }

    /**
     * 最小 {@link MycatDataContext} 桩（同 {@code MysqlDivideDiagnosticSlotTest} 手法）：
     * 诊断区两方法落到本地 sink，其余 default 方法真跑。
     */
    private static MycatDataContext newProxiedContext(List<WarningMessage> sink) {
        return (MycatDataContext) Proxy.newProxyInstance(MycatDataContext.class.getClassLoader(),
                new Class<?>[]{MycatDataContext.class}, (Object proxy, Method method, Object[] args) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    switch (method.getName()) {
                        case "addWarning":
                            sink.add((WarningMessage) args[0]);
                            return null;
                        case "getWarnings":
                            return Collections.unmodifiableList(sink);
                        case "toString":
                            return "fake-mycat-data-context-cast";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }
}
