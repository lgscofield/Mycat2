package io.mycat.sqlhandler.dql;

import io.mycat.MycatDataContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * {@code max_error_count} 读会话变量的落点（I-DEBT-69 Blade B 读面的截断入参）。
 *
 * <p>取证背景：{@code MysqlVariableServiceImpl} 有两条装配路径 —— 配了后端数据源时会话变量表来自真库
 * {@code SHOW SESSION VARIABLES}（<b>含</b> max_error_count，值随版本 8.0=1024 / 5.7=64 / MariaDB=64）；
 * 无后端时走硬编码兜底表，该表<b>不含</b>此键。因此"取不到值"是真实存在的分支，
 * 而此时的选择必须是保守退化（不截断），而不是臆造某个版本的默认值。
 */
class ShowWarningsMaxErrorCountTest {

    @Test
    void missingVariableFallsBackToNoTruncation() {
        Assertions.assertEquals(ShowWarningsSQLHandler.NO_TRUNCATION,
                ShowWarningsSQLHandler.resolveMaxErrorCount(contextReturning(null)),
                "absent max_error_count must not invent a version-specific default");
    }

    @Test
    void numericStringIsParsed() {
        Assertions.assertEquals(1024,
                ShowWarningsSQLHandler.resolveMaxErrorCount(contextReturning("1024")));
    }

    @Test
    void numberValueIsUsedDirectly() {
        Assertions.assertEquals(64,
                ShowWarningsSQLHandler.resolveMaxErrorCount(contextReturning(Integer.valueOf(64))));
    }

    @Test
    void nonNumericValueFallsBackToNoTruncation() {
        Assertions.assertEquals(ShowWarningsSQLHandler.NO_TRUNCATION,
                ShowWarningsSQLHandler.resolveMaxErrorCount(contextReturning("NOT_A_NUMBER")));
    }

    /** 变量查询抛异常不得把读面带崩：退化路径同样是不截断。 */
    @Test
    void throwingVariableLookupFallsBackToNoTruncation() {
        Assertions.assertEquals(ShowWarningsSQLHandler.NO_TRUNCATION,
                ShowWarningsSQLHandler.resolveMaxErrorCount(contextThrowing()));
    }

    /** 只回应 getVariable 的极小 fake，其余方法返回类型默认值。 */
    private static MycatDataContext contextReturning(Object variableValue) {
        return proxy((proxy, method, args) -> {
            if (isMaxErrorCountLookup(method, args)) {
                return variableValue;
            }
            return defaultValue(method);
        });
    }

    private static MycatDataContext contextThrowing() {
        return proxy((proxy, method, args) -> {
            if (isMaxErrorCountLookup(method, args)) {
                throw new IllegalStateException("variable service unavailable");
            }
            return defaultValue(method);
        });
    }

    private static boolean isMaxErrorCountLookup(Method method, Object[] args) {
        if (!"getVariable".equals(method.getName()) || args == null || args.length != 2) {
            return false;
        }
        return Boolean.FALSE.equals(args[0]) && "@@session.max_error_count".equals(args[1]);
    }

    private static Object defaultValue(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt == int.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == byte.class) {
            return (byte) 0;
        }
        if (rt == short.class) {
            return (short) 0;
        }
        if (rt == double.class) {
            return 0d;
        }
        if (rt == float.class) {
            return 0f;
        }
        if (rt == char.class) {
            return (char) 0;
        }
        return null;
    }

    private static MycatDataContext proxy(InvocationHandler handler) {
        return (MycatDataContext) Proxy.newProxyInstance(MycatDataContext.class.getClassLoader(),
                new Class<?>[] { MycatDataContext.class }, handler);
    }
}
