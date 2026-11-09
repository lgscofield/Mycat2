package io.mycat;

import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-94 F4：会话告警缓冲被内部解析失败污染——{@code SHOW WARNINGS} 在全新会话吐出
 * {@code org.apache.calcite.runtime.CalciteContextException: At line 0, column 0: Column '$$' not found in any table}
 * 一类内部错误行（真库直连为空；活体出处 audit/2026-11-08-capability-audit/logs/E0-05-mycat-boot2.log
 * 16:16:18 会话序：客户端探针 {@code select $$} 失败 → 同会话首条 {@code SHOW WARNINGS} 即见该行）。
 *
 * <p>取证（I-DEBT-94）：污染唯一写入点 = {@code MycatDataContext#recordStatementError}（I-DEBT-69
 * Blade F 唯一 default 实现，server 的 {@code VertxSessionImpl} 与 proxy 的
 * {@code MySQLProxyServerSession} 两处 ERR 出口都汇到这里）。{@code select $$} 的失败消息是
 * {@code recover} 腿的 {@code cause.toString()}（单行）或外层 catch 的堆栈串，两者都含
 * {@code org.apache.calcite.runtime.CalciteContextException} 与
 * {@code Column '$$' not found in any table} 两个稳定标记——即「内部 Calcite 校验器把探针裸标识符
 * {@code $$} 当列名」这一伪影类。真库对裸 {@code $$}（未引标识符）要么报 1054 形态的用户列错，
 * 要么无此语句；都不会把 Java 异常文本灌进诊断区，故该类消息不得入会话告警缓冲。
 *
 * <p>修法选型（取证后最小面）：在 {@code recordStatementError} 单点过滤，一处覆盖 server/proxy
 * 两条 ERR 出口、SHOW WARNINGS / SHOW ERRORS / @@warning_count / @@error_count 全部读腿与计数；
 * ERR 包本身照发（语句对客户端的失败应答不受本过滤影响）。真用户错误（重复键、数据截断等）
 * 仍照 Blade F 活体口径入诊断区——本类第 3/4 用例是对照锁。
 *
 * <p>离线锁：JDK 动态代理搭最小 {@link MycatDataContext}（default 方法经
 * {@link InvocationHandler#invokeDefault} 真跑），不连库、不起会话。
 */
public class InternalArtifactWarningFilterTest {

    /** 活体 F4 行原文（audit E0-05-mycat-boot2.log:16:16:18.370 recover 腿 cause.toString() 单行形态）。 */
    private static final String AUDIT_ARTIFACT_SINGLE_LINE =
            "org.apache.calcite.runtime.CalciteContextException: At line 0, column 0: Column '$$' not found in any table";

    /** 外层 catch（MycatVertxMySQLHandler.handle0 的 setLastMessage(throwable)）堆栈串形态。 */
    private static final String AUDIT_ARTIFACT_STACK_TRACE =
            "org.apache.calcite.runtime.CalciteContextException: At line 0, column 0: Column '$$' not found in any table\n"
                    + "\tat org.apache.calcite.sql.validate.SqlValidatorImpl.newValidationError(SqlValidatorImpl.java:6419)\n"
                    + "\tat io.mycat.DrdsSqlCompiler.getRelRoot(DrdsSqlCompiler.java:408)\n"
                    + "\t... 64 common frames omitted\n"
                    + "Caused by: org.apache.calcite.sql.validate.SqlValidatorException: Column '$$' not found in any table\n"
                    + "\tat org.apache.calcite.runtime.Resources$ExInst.ex(Resources.java:605)\n"
                    + "\t... 64 common frames omitted";

    @Test
    public void internalCalciteDollarArtifactIsNotRecorded() {
        List<WarningMessage> sink = newCtxSink();
        MycatDataContext ctx = newProxiedContext(sink);

        ctx.recordStatementError(0, AUDIT_ARTIFACT_SINGLE_LINE);

        assertEquals(Collections.emptyList(), ctx.getWarnings(),
                "内部 Calcite '$$' 伪影错误不得入会话告警缓冲（F4：全新会话 SHOW WARNINGS 恒空）");
        assertTrue(sink.isEmpty());
    }

    @Test
    public void internalCalciteDollarArtifactStackTraceFormIsNotRecorded() {
        MycatDataContext ctx = newProxiedContext(newCtxSink());

        ctx.recordStatementError(0, AUDIT_ARTIFACT_STACK_TRACE);

        assertEquals(Collections.emptyList(), ctx.getWarnings(),
                "堆栈串形态的同源消息同样属于伪影类，不得入缓冲");
    }

    @Test
    public void genuineUserErrorStillRecorded() {
        MycatDataContext ctx = newProxiedContext(newCtxSink());

        ctx.recordStatementError(1062, "Duplicate entry '1' for key 't.PRIMARY'");

        List<WarningMessage> warnings = ctx.getWarnings();
        assertEquals(1, warnings.size(), "真用户错误仍按 Blade F 口径入诊断区");
        assertEquals("Error", warnings.get(0).getLevel());
        assertEquals(1062, warnings.get(0).getCode());
        assertEquals("Duplicate entry '1' for key 't.PRIMARY'", warnings.get(0).getMessage());
    }

    @Test
    public void nullMessageStillRecordedAsEmptyString() {
        MycatDataContext ctx = newProxiedContext(newCtxSink());

        ctx.recordStatementError(1105, null);

        List<WarningMessage> warnings = ctx.getWarnings();
        assertEquals(1, warnings.size(), "Blade F 原口径：null 消息兜底空串，不因过滤改动回退");
        assertEquals("Error", warnings.get(0).getLevel());
        assertEquals("", warnings.get(0).getMessage());
    }

    @Test
    public void unknownColumnArtifactOfClassShapeIsNotRecordedOnlyForDollarDollar() {
        // 同族但非 '$$' 的 Calcite 校验错误（真用户裸列拼错）：真库 1054 也会在诊断区留 Error 行，
        // 必须继续记录——过滤面只能钉死 '$$' 伪影类，不得扩大到全部 CalciteContextException。
        MycatDataContext kept = newProxiedContext(newCtxSink());
        kept.recordStatementError(0,
                "org.apache.calcite.runtime.CalciteContextException: At line 0, column 0: Column 'unkown_col' not found in any table");
        assertEquals(1, kept.getWarnings().size(),
                "非 '$$' 的 Calcite 校验错误是真用户错误，必须照常入诊断区");
    }

    private static List<WarningMessage> newCtxSink() {
        return new ArrayList<>();
    }

    /**
     * 最小 {@link MycatDataContext} 桩：诊断区三方法落到本地 sink，其余 default 方法真跑
     * （被测入口 {@code recordStatementError} 正是 default 实现），非 default 返回空值。
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
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}
