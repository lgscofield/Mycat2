package io.mycat.calcite.sqlfunction.arith;

import io.mycat.MycatDataContext;
import io.mycat.beans.mycat.WarningMessage;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-97/F5：本地 codegen 除零的告警通道锁（方案B：诊断槽）。
 *
 * <p>值语义已由 {@link MysqlDivideFunctionTest} 锁定（除零→NULL）；本类锁的是告警腿：
 * {@code MysqlDivideFunction.mysqlDivide} 的除零分支读 {@link NewMycatDataContext} 诊断槽，
 * 槽内挂有 {@link MycatDataContext} 时按活体真库口径（SQLWARNING 1365 {@code Division by 0}，
 * 对齐远程腿 {@code QueryBackendWarningsTest} 的 {@code WarningMessage("Warning",1365,...)}）
 * 写会话告警缓冲，{@code @@warning_count}/SHOW WARNINGS 读腿（I-DEBT-69）自动带出。
 *
 * <p>对照格（与远程腿中继互斥不重叠）：除零值仍 NULL；非除零不产告警；NULL 操作数不产告警
 * （真库 {@code SELECT NULL/0} → NULL 无告警，除零诊断只在除数零且被除数非 NULL 时发生）；
 * 无槽降级静默不抛（远程/异步消费线程、无槽执行路径兼容）；槽按次累积（真库
 * {@code SELECT 1/0,1/0} 两条）；槽挂载/摘回协议（线程隔离、嵌套恢复、finally 摘净）。
 *
 * <p>离线锁：JDK 动态代理搭最小 {@link MycatDataContext}（default 方法经
 * {@link InvocationHandler#invokeDefault} 真跑），不连库、不起会话、不起 8066 实例。
 */
class MysqlDivideDiagnosticSlotTest {

    private static final WarningMessage DIVISION_BY_ZERO =
            new WarningMessage("Warning", 1365, "Division by 0");

    @Test
    void divideByZeroViaDiagnosticSlotProduces1365Warning() {
        List<WarningMessage> sink = new ArrayList<>();
        MycatDataContext ctx = newProxiedContext(sink);

        MycatDataContext previous = NewMycatDataContext.installDiagnosticContext(ctx);
        try {
            // 精确路：整型除零 → NULL + 1365（活体真库 SELECT 1/0 口径）
            assertNull(MysqlDivideFunction.mysqlDivide(1L, 0L), "除零值语义仍为 NULL（不被告警腿改动）");
            assertEquals(Collections.singletonList(DIVISION_BY_ZERO), sink,
                    "精确路除零必须经诊断槽入会话告警缓冲（1365 Division by 0）");

            // 精确路：DECIMAL 除零
            sink.clear();
            assertNull(MysqlDivideFunction.mysqlDivide(new BigDecimal("1.0"), BigDecimal.ZERO));
            assertEquals(Collections.singletonList(DIVISION_BY_ZERO), sink, "DECIMAL 除零同样产 1365");

            // 近似路：double 除零
            sink.clear();
            assertNull(MysqlDivideFunction.mysqlDivide(5.0d, 0.0d));
            assertEquals(Collections.singletonList(DIVISION_BY_ZERO), sink, "近似路除零同样产 1365");
        } finally {
            NewMycatDataContext.restoreDiagnosticContext(previous);
        }
    }

    @Test
    void nonDivideByZeroProducesNoWarning() {
        List<WarningMessage> sink = new ArrayList<>();
        MycatDataContext ctx = newProxiedContext(sink);

        MycatDataContext previous = NewMycatDataContext.installDiagnosticContext(ctx);
        try {
            assertEquals(new BigDecimal("3.3333"), MysqlDivideFunction.mysqlDivide(10L, 3L));
            assertEquals(2.5d, (Double) MysqlDivideFunction.mysqlDivide(5.0d, 2.0d), 1e-9);
            assertTrue(sink.isEmpty(), "非除零求值不得产任何告警");
        } finally {
            NewMycatDataContext.restoreDiagnosticContext(previous);
        }
    }

    @Test
    void nullOperandProducesNoWarning() {
        List<WarningMessage> sink = new ArrayList<>();
        MycatDataContext ctx = newProxiedContext(sink);

        MycatDataContext previous = NewMycatDataContext.installDiagnosticContext(ctx);
        try {
            // 真库 SELECT NULL/0 → NULL 且无告警（除零诊断只发生在除数零且被除数非 NULL）
            assertNull(MysqlDivideFunction.mysqlDivide(null, 0L));
            assertNull(MysqlDivideFunction.mysqlDivide(1L, null));
            assertTrue(sink.isEmpty(), "NULL 操作数短路不得产告警（对齐真库 NULL/0 无告警）");
        } finally {
            NewMycatDataContext.restoreDiagnosticContext(previous);
        }
    }

    @Test
    void noSlotDegradesSilently() {
        // 无槽执行路径（远程/异步消费线程、单测直调）：除零仍返 NULL，不抛、不产告警。
        assertNull(NewMycatDataContext.currentDiagnosticContext(), "前置：本线程无槽");
        assertNull(MysqlDivideFunction.mysqlDivide(1L, 0L));
        assertNull(MysqlDivideFunction.mysqlDivide(5.0d, 0.0d));
        assertNull(NewMycatDataContext.currentDiagnosticContext(), "无槽直调不得自行种槽或抛异常");
    }

    @Test
    void warningsAccumulatePerOccurrence() {
        List<WarningMessage> sink = new ArrayList<>();
        MycatDataContext ctx = newProxiedContext(sink);

        MycatDataContext previous = NewMycatDataContext.installDiagnosticContext(ctx);
        try {
            // 真库 SELECT 1/0, 1/0 → @@warning_count=2（按发生次数累积，不合并）
            MysqlDivideFunction.mysqlDivide(1L, 0L);
            MysqlDivideFunction.mysqlDivide(2L, 0L);
            assertEquals(2, sink.size(), "每次除零发生各计一条（真库 SELECT 1/0,1/0 → warning_count=2）");
            assertEquals(DIVISION_BY_ZERO, sink.get(0));
            assertEquals(DIVISION_BY_ZERO, sink.get(1));
        } finally {
            NewMycatDataContext.restoreDiagnosticContext(previous);
        }
    }

    @Test
    void evaluationDoesNotConsumeOrClearSlot() {
        List<WarningMessage> sink = new ArrayList<>();
        MycatDataContext ctx = newProxiedContext(sink);

        MycatDataContext previous = NewMycatDataContext.installDiagnosticContext(ctx);
        try {
            MysqlDivideFunction.mysqlDivide(1L, 0L);
            assertSame(ctx, NewMycatDataContext.currentDiagnosticContext(),
                    "求值不得消费/清空槽（同一语句内多次除零都要能读到会话）");
        } finally {
            NewMycatDataContext.restoreDiagnosticContext(previous);
        }
        assertNull(NewMycatDataContext.currentDiagnosticContext(), "restore 后槽必须摘净");
    }

    @Test
    void slotIsThreadConfined() throws Exception {
        List<WarningMessage> sink = new ArrayList<>();
        MycatDataContext ctx = newProxiedContext(sink);
        AtomicReference<MycatDataContext> seenOnOtherThread = new AtomicReference<>();

        MycatDataContext previous = NewMycatDataContext.installDiagnosticContext(ctx);
        try {
            Thread other = new Thread(() -> seenOnOtherThread.set(NewMycatDataContext.currentDiagnosticContext()),
                    "i-debt-97-slot-confine-probe");
            other.start();
            other.join(10_000);
            assertNull(seenOnOtherThread.get(),
                    "槽必须线程隔离——线程池复用形态的其他线程不得读到本会话上下文（串会话防线）");
        } finally {
            NewMycatDataContext.restoreDiagnosticContext(previous);
        }
    }

    @Test
    void installRestoreNestsWithoutCrosstalk() {
        List<WarningMessage> sinkA = new ArrayList<>();
        List<WarningMessage> sinkB = new ArrayList<>();
        MycatDataContext ctxA = newProxiedContext(sinkA);
        MycatDataContext ctxB = newProxiedContext(sinkB);

        MycatDataContext previousA = NewMycatDataContext.installDiagnosticContext(ctxA);
        try {
            assertSame(ctxA, NewMycatDataContext.currentDiagnosticContext());
            MysqlDivideFunction.mysqlDivide(1L, 0L);
            assertEquals(1, sinkA.size());

            // 嵌套求值形态：内层挂 B、内层摘回后外层 A 仍在（restore 恢复 previous 而非一刀切 remove）
            MycatDataContext previousB = NewMycatDataContext.installDiagnosticContext(ctxB);
            try {
                assertSame(ctxB, NewMycatDataContext.currentDiagnosticContext());
                MysqlDivideFunction.mysqlDivide(1L, 0L);
                assertEquals(1, sinkB.size(), "嵌套内层除零记到内层会话");
            } finally {
                NewMycatDataContext.restoreDiagnosticContext(previousB);
            }

            assertSame(ctxA, NewMycatDataContext.currentDiagnosticContext(), "内层摘回不得踩掉外层槽");
            MysqlDivideFunction.mysqlDivide(1L, 0L);
            assertEquals(2, sinkA.size(), "嵌套摘回后外层继续累积");
            assertEquals(1, sinkB.size(), "内层恰好一条，不串到外层");
        } finally {
            NewMycatDataContext.restoreDiagnosticContext(previousA);
        }
        assertNull(NewMycatDataContext.currentDiagnosticContext());
    }

    /**
     * 最小 {@link MycatDataContext} 桩（对齐 InternalArtifactWarningFilterTest 的代理手法）：
     * 诊断区两方法落到本地 sink，其余 default 方法真跑，非 default 返回空值。
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
                            return "fake-mycat-data-context";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        return 0;
    }
}
