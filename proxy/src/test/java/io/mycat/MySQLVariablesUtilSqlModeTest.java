package io.mycat;

import io.mycat.beans.mycat.TransactionType;
import io.mycat.runtime.MycatDataContextImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-54 D2b-A：会话 sql_mode 捕获（write 腿）。
 * 判据：session 域 SET sql_mode 把去引号后的原始串存进会话态；global 域不入会话（不误捕获）；
 * 走真实 MycatDataContextImpl 的 Lombok 访问器+字段，零 mock。
 * 构造测试子类把 switchTransaction 覆写为 no-op，绕开 MetaClusterCurrent 运行时单例（裸实例化会 NPE），
 * 不影响本用例（仅验证 sessionSqlMode 字段读写路径）。
 */
class MySQLVariablesUtilSqlModeTest {

    static class Ctx extends MycatDataContextImpl {
        @Override
        public void switchTransaction(TransactionType transactionSessionType) {
            // no-op：避免构造期依赖未初始化的运行时单例
        }
    }

    @Test
    void capturesSessionSqlModeStripsQuotes() {
        Ctx ctx = new Ctx();
        MySQLVariablesUtil.setVariable(ctx, "@@session.sql_mode", "''");
        Assertions.assertEquals("", ctx.getSessionSqlMode());
    }

    @Test
    void capturesRawListVerbatim() {
        Ctx ctx = new Ctx();
        MySQLVariablesUtil.setVariable(ctx, "sql_mode", "'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES'");
        Assertions.assertEquals("ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES", ctx.getSessionSqlMode());
    }

    @Test
    void globalScopeNotCapturedIntoSession() {
        Ctx ctx = new Ctx();
        MySQLVariablesUtil.setVariable(ctx, "@@global.sql_mode", "'STRICT_TRANS_TABLES'");
        Assertions.assertNull(ctx.getSessionSqlMode());
    }
}
