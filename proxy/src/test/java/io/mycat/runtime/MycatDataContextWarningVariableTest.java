package io.mycat.runtime;

import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatException;
import io.mycat.MysqlVariableService;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * I-DEBT-69 Blade C：{@code @@warning_count} / {@code @@max_error_count} 会话域读腿。
 *
 * <p>真库判据（2026-10-31 活体 @ mysql 8.4.11，同库 mariadb 11.4.13 边界一致）：
 * <ul>
 *   <li>{@code SELECT 1/0; SELECT @@warning_count} → <b>1</b>：新语句执行期读到的是
 *       <b>上一条</b>语句结束时的诊断区条数；本语句产生的条件不参与本次读取。</li>
 *   <li>{@code SELECT 1/0; SELECT @@warning_count; SHOW WARNINGS} → 列表<b>为空</b>：
 *       那条无条件的 SELECT 已替换诊断区。</li>
 *   <li>{@code SET max_error_count=65536} → 65535、{@code =-1} → 0（clamp 到 [0,65535]）；
 *       {@code ='abc'} → ERROR 1232。</li>
 * </ul>
 *
 * <p>用 {@link MycatDataContextImpl} 薄子类绕运行时单例；注入 {@link MysqlVariableService} 桩，
 * 使"未覆盖 → 回落变量服务"这一腿可裸测（桩返回哨兵串）。
 */
class MycatDataContextWarningVariableTest {

    /** 桩：session/global 读均返回哨兵串，用于证"未被会话覆盖时回落到变量服务"。 */
    static class TemplateVariableService implements MysqlVariableService {
        @Override
        public Object getGlobalVariable(String name) {
            return "GLOBAL_TEMPLATE";
        }

        @Override
        public Object getSessionVariable(String name) {
            return "SESSION_TEMPLATE";
        }

        @Override
        public List<Object[]> getGlobalVariables() {
            return Collections.emptyList();
        }

        @Override
        public List<Object[]> getSessionVariables() {
            return Collections.emptyList();
        }
    }

    static class Ctx extends MycatDataContextImpl {
        @Override
        public void switchTransaction(TransactionType transactionSessionType) {
            // no-op：绕开构造期运行时单例
        }
    }

    private static Map<Class, Object> savedContext;

    @BeforeAll
    static void registerStub() {
        savedContext = MetaClusterCurrent.copyContext();
        MetaClusterCurrent.register(MysqlVariableService.class, new TemplateVariableService());
    }

    @AfterAll
    static void restoreContext() {
        MetaClusterCurrent.register(savedContext == null
                ? new java.util.IdentityHashMap<>() : savedContext);
    }

    private static WarningMessage div0() {
        return new WarningMessage("Warning", 1365, "Division by 0");
    }

    private static WarningMessage dataTooLong() {
        return new WarningMessage("Error", 1406, "Data too long for column");
    }

    /** 真库取证（MySQL 8.0.46 / MariaDB 10.11.19）：纯告警语句后 @@error_count=0（非 NULL）。 */
    @Test
    void errorCountZeroForWarningOnlyPreviousStatement() {
        Ctx ctx = new Ctx();
        ctx.addWarning(div0());
        ctx.clearWarnings();

        Assertions.assertEquals(0,
                ((Number) ctx.getVariable(false, "@@error_count")).intValue(),
                "上一条只有 Warning → @@error_count 必须为 0");
        Assertions.assertEquals(0,
                ((Number) ctx.getVariable(false, "@@session.error_count")).intValue(),
                "带 session 前缀同源");
    }

    /** 上一条语句含 1 个 Error 级 → @@error_count 读归档的 Error 级计数，与 warning_count（全量）解耦。 */
    @Test
    void errorCountReadsPreviousStatementErrorLevelOnly() {
        Ctx ctx = new Ctx();
        ctx.addWarning(div0());        // Warning
        ctx.addWarning(dataTooLong()); // Error
        ctx.clearWarnings();

        Assertions.assertEquals(1,
                ((Number) ctx.getVariable(false, "@@error_count")).intValue(),
                "@@error_count 只计 Error 级，不受 Warning/Note 影响");
        Assertions.assertEquals(2,
                ((Number) ctx.getVariable(false, "@@warning_count")).intValue(),
                "对照：@@warning_count 仍取全部条件数，二者互不污染");
    }

    /** 本语句执行期新增的 Error 不参与本次 @@error_count（与 warning_count 读腿同构）。 */
    @Test
    void currentStatementErrorsDoNotFeedErrorCountVariable() {
        Ctx ctx = new Ctx();
        ctx.clearWarnings();
        ctx.addWarning(dataTooLong());

        Assertions.assertEquals(0,
                ((Number) ctx.getVariable(false, "@@error_count")).intValue(),
                "新语句产生的 Error 不得喂给本语句的 @@error_count");
    }

    /** 无前置语句时 @@error_count 为 0，而不是回落变量服务或抛空。 */
    @Test
    void errorCountIsZeroWithoutPreviousStatement() {
        Ctx ctx = new Ctx();
        Assertions.assertEquals(0,
                ((Number) ctx.getVariable(false, "@@error_count")).intValue());
    }

    /** 上一条语句结束时 1 条 → 新语句入口归档后，@@warning_count 读 1（真库 D 用例）。 */
    @Test
    void warningCountReadsPreviousStatementCount() {
        Ctx ctx = new Ctx();
        ctx.addWarning(div0());
        ctx.clearWarnings();

        Assertions.assertEquals(1,
                ((Number) ctx.getVariable(false, "@@session.warning_count")).intValue(),
                "@@session.warning_count = 上一条语句的条件数");
        Assertions.assertEquals(1,
                ((Number) ctx.getVariable(false, "@@warning_count")).intValue(),
                "@@warning_count 与带 session 前缀同源");
    }

    /** 本语句执行期读到的仍是上一条语句（真库 B 用例：1/0 后读 count=1 而非 0/2）。 */
    @Test
    void currentStatementWarningsDoNotFeedWarningCountVariable() {
        Ctx ctx = new Ctx();
        ctx.clearWarnings();
        ctx.addWarning(div0());

        Assertions.assertEquals(1, ctx.getWarningCount(), "OK/EOF 出包仍取本语句条件数");
        Assertions.assertEquals(0,
                ((Number) ctx.getVariable(false, "@@warning_count")).intValue(),
                "新语句产生的条件不得喂给本语句的 @@warning_count");
    }

    /** 无前置语句时 @@warning_count 为 0，而不是回落变量服务。 */
    @Test
    void warningCountIsZeroWithoutPreviousStatement() {
        Ctx ctx = new Ctx();
        Assertions.assertEquals(0,
                ((Number) ctx.getVariable(false, "@@warning_count")).intValue());
    }

    /** SET max_error_count=1 → 会话覆盖，读回 1（真库 SET 后 @@max_error_count 立即变）。 */
    @Test
    void setMaxErrorCountOverridesSessionRead() {
        Ctx ctx = new Ctx();
        ctx.setVariable("max_error_count", new SQLIntegerExpr(1));

        Assertions.assertEquals(1,
                ((Number) ctx.getVariable(false, "@@session.max_error_count")).intValue(),
                "SET max_error_count 必须成为 @session.max_error_count 的权威值");
        Assertions.assertEquals(1,
                ((Number) ctx.getVariable(false, "@@max_error_count")).intValue(),
                "带不带 session 前缀读到同一覆盖值");
    }

    /** 真库 clamp：65536 → 65535、-1 → 0（2026-10-31 活体 mysql 8.4.11 / mariadb 11.4.13 一致）。 */
    @Test
    void setMaxErrorCountClampsToProtocolRange() {
        Ctx upper = new Ctx();
        upper.setVariable("max_error_count", new SQLIntegerExpr(65536));
        Assertions.assertEquals(65535,
                ((Number) upper.getVariable(false, "@@max_error_count")).intValue());

        Ctx lower = new Ctx();
        lower.setVariable("max_error_count", new SQLIntegerExpr(-1));
        Assertions.assertEquals(0,
                ((Number) lower.getVariable(false, "@@max_error_count")).intValue());
    }

    /** SET ... = DEFAULT → 撤销会话覆盖，回落变量服务（真库把变量恢复默认值）。 */
    @Test
    void setMaxErrorCountDefaultRestoresFallback() {
        Ctx ctx = new Ctx();
        ctx.setVariable("max_error_count", new SQLIntegerExpr(1));
        ctx.setVariable("max_error_count", "default");

        Assertions.assertEquals("SESSION_TEMPLATE",
                ctx.getVariable(false, "@@max_error_count"),
                "DEFAULT 应清除会话覆盖而不是存字符串 default");
    }

    /** 未 SET 时回落变量服务（有后端 → 后端值；无后端 → persona 兜底表值）。 */
    @Test
    void unsetMaxErrorCountFallsBackToVariableService() {
        Ctx ctx = new Ctx();
        Assertions.assertEquals("SESSION_TEMPLATE", ctx.getVariable(false, "@@max_error_count"));
    }

    /**
     * 真库 8.4.11 实拍：{@code SET max_error_count='abc'} → ERROR <b>1232</b>
     * （ER_WRONG_TYPE_FOR_VAR，消息 Incorrect argument type to variable 'max_error_count'）。
     * 活体旧行为是 1105 + Java 异常文本（{@code RuntimeException: IllegalArgumentException: ...}），
     * 客户端按错误码分支（Connector/J 抛 SQLException 的 errorCode）会判错，故须带码抛。
     */
    @Test
    void setMaxErrorCountRejectsNonNumericWithErrorCode1232() {
        Ctx ctx = new Ctx();
        MycatException e = Assertions.assertThrows(MycatException.class,
                () -> ctx.setVariable("max_error_count", new SQLCharExpr("abc")));
        Assertions.assertEquals(1232, e.getErrorCode(),
                "非数字 max_error_count 必须报 ER_WRONG_TYPE_FOR_VAR(1232)");
        Assertions.assertEquals("Incorrect argument type to variable 'max_error_count'",
                e.getMessage(), "消息与真库同名同句");
    }
}
