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
package io.mycat.calcite.sqlfunction.arith;

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
 * I-DEBT-119：MOD 除零语义锁（真库口径 {@code SELECT 1 % 0} → NULL + Warning 1365 "Division by 0"，
 * 与除法 {@code MysqlDivideFunction} 完全一致；消除本地腿与远程腿语义分裂）。
 *
 * <p>取证记录（修法选型 b/a）：calcite-core {@code SqlFunctions.mod} 系（byte/short/int/long/BigDecimal）
 * 返回原始类型，<b>无法表达 NULL</b>，且其上游 {@code RexImpTable} 的 MOD implementor 走
 * {@code BuiltInMethod.MOD}；而本地 codegen 的算子出码统一经 {@code RexToLixTranslator:1512}
 * {@code remapOperator}。故取方案 a：新建 {@link MysqlModFunction}（复刻 MysqlDivideFunction 全套：
 * NULL 透传 + 除零 NULL + 诊断槽 1365），在 {@code MycatCalciteRuntimeHooks.remapOperator} 把
 * {@code SqlStdOperatorTable.MOD} 映射过去。原 {@code SqlFunctions.mod} 系保持上游原样（本测试
 * 顺带锁其现状：除零抛 {@link ArithmeticException}——remap 后 codegen 不再触达，仅供取证）。
 *
 * <p>注：票面原定锁放 calcite-core test，因修法落位 hbt（诊断槽 {@code MycatDataContext} 在
 * common/hbt 侧），锁随实现移到 hbt，类名保留票面命名。
 */
class SqlFunctionsModSemanticsTest {

    private List<WarningMessage> sink;
    private MycatDataContext previousSlot;

    @BeforeEach
    void installSlot() {
        sink = new ArrayList<>();
        MycatDataContext ctx = newProxiedContext(sink);
        previousSlot = NewMycatDataContext.installDiagnosticContext(ctx);
    }

    @AfterEach
    void restoreSlot() {
        NewMycatDataContext.restoreDiagnosticContext(previousSlot);
        assertNull(NewMycatDataContext.currentDiagnosticContext(), "restore 后槽必须摘净");
    }

    private static final WarningMessage DIVISION_BY_ZERO =
            new WarningMessage("Warning", 1365, "Division by 0");

    @Test
    void normalModUnchanged() {
        assertEquals(1L, MysqlModFunction.mysqlMod(10L, 3L), "整型 MOD 值语义不变（Long 运行表征）");
        assertTrue(sink.isEmpty(), "正常 MOD 不产告警");

        assertEquals(new BigDecimal("1.5"), MysqlModFunction.mysqlMod(new BigDecimal("10.5"), 3L),
                "DECIMAL MOD 值语义=BigDecimal remainder");
        assertEquals(1.0d, (Double) MysqlModFunction.mysqlMod(10.0d, 3.0d), 1e-12, "近似型 MOD→double（10.0%3=1.0）");
        assertTrue(sink.isEmpty());
    }

    @Test
    void modByZeroYieldsNullPlus1365() {
        // 真库 SELECT 1 % 0 → NULL + Warning 1365 "Division by 0"（与 / 除法同码同文案）
        assertNull(MysqlModFunction.mysqlMod(1L, 0L), "整型除零→NULL");
        assertEquals(Collections.singletonList(DIVISION_BY_ZERO), sink, "整型除零产 1365");

        sink.clear();
        assertNull(MysqlModFunction.mysqlMod(new BigDecimal("1.0"), BigDecimal.ZERO), "DECIMAL 除零→NULL");
        assertEquals(Collections.singletonList(DIVISION_BY_ZERO), sink);

        sink.clear();
        assertNull(MysqlModFunction.mysqlMod(5.0d, 0.0d), "近似型除零→NULL");
        assertEquals(Collections.singletonList(DIVISION_BY_ZERO), sink);
    }

    @Test
    void nullOperandPropagatesNoWarning() {
        // 真库 SELECT NULL % 0 → NULL 无告警（NULL 短路，与 / 除法口径一致）
        assertNull(MysqlModFunction.mysqlMod(null, 0L));
        assertNull(MysqlModFunction.mysqlMod(1L, null));
        assertTrue(sink.isEmpty(), "NULL 操作数短路不得产告警");
    }

    @Test
    void noSlotDegradesSilently() {
        NewMycatDataContext.restoreDiagnosticContext(previousSlot);
        previousSlot = null;
        assertNull(MysqlModFunction.mysqlMod(1L, 0L), "无槽路径值语义仍=NULL，不抛");
        assertTrue(sink.isEmpty());
        assertNull(NewMycatDataContext.currentDiagnosticContext(), "无槽直调不得自行种槽");
    }

    @Test
    void legacySqlFunctionsModStillThrows_documentation() {
        // 取证锁：calcite-core 原生 mod 系对零除数抛 ArithmeticException（真库=NULL）。
        // remap 后本地 codegen 不再触达此处；若未来有人移除 remap，此格先红。
        org.junit.jupiter.api.Assertions.assertThrows(ArithmeticException.class,
                () -> org.apache.calcite.runtime.SqlFunctions.mod(1, 0));
    }

    /**
     * 最小 {@link MycatDataContext} 桩（同 {@code MysqlDivideDiagnosticSlotTest} 手法）。
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
                            return "fake-mycat-data-context-mod";
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
