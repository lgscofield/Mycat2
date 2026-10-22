package io.mycat.runtime;

import io.mycat.MetaClusterCurrent;
import io.mycat.MysqlVariableService;
import io.mycat.beans.mycat.TransactionType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-54 D2b-A：@@session.sql_mode 回读返会话真值（read 腿）。
 * 判据：会话显式设过 sql_mode 时，SELECT @@session.sql_mode / SHOW VARIABLES 返该值（含空串）；
 * 未设时落回既有静态模板（行为不变）；global 读不经会话态。
 * 用 MycatDataContextImpl 薄子类绕运行时单例；注入 MysqlVariableService 桩以裸测回退路径。
 */
class MycatDataContextSqlModeReadTest {

    /** 桩：任意 session 变量返回模板哨兵，global 返回 global 哨兵。 */
    static class TemplateVariableService implements MysqlVariableService {
        @Override
        public Object getGlobalVariable(String name) {
            return "GLOBAL_TEMPLATE";
        }

        @Override
        public Object getSessionVariable(String name) {
            return "TEMPLATE_SQL_MODE";
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

    @BeforeAll
    static void registerStub() {
        MetaClusterCurrent.register(MysqlVariableService.class, new TemplateVariableService());
    }

    @Test
    void unsetFallsBackToTemplate() {
        Ctx ctx = new Ctx();
        // 未设：sessionSqlMode==null，落回静态模板（行为不变）
        Assertions.assertEquals("TEMPLATE_SQL_MODE", ctx.getVariable(false, "@@session.sql_mode"));
    }

    @Test
    void explicitEmptyIsReadBack() {
        Ctx ctx = new Ctx();
        ctx.setSessionSqlMode("");
        Assertions.assertEquals("", ctx.getVariable(false, "@@session.sql_mode"));
    }

    @Test
    void setListIsReadBackAcrossPrefixForms() {
        Ctx ctx = new Ctx();
        String mode = "STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION";
        ctx.setSessionSqlMode(mode);
        Assertions.assertEquals(mode, ctx.getVariable(false, "@@session.sql_mode"));
        Assertions.assertEquals(mode, ctx.getVariable(false, "sql_mode"));
        Assertions.assertEquals(mode, ctx.getVariable(false, "@@sql_mode"));
    }

    @Test
    void globalReadNotAffectedBySessionState() {
        Ctx ctx = new Ctx();
        ctx.setSessionSqlMode("");
        // global 读走 getGlobalVariable，不经会话态
        Assertions.assertEquals("GLOBAL_TEMPLATE", ctx.getVariable(true, "sql_mode"));
    }
}
