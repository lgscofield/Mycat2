package io.mycat.commands;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowErrorsStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowWarningsStatement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 诊断区替换钩子的入口判定（I-DEBT-69 Blade A 生命周期）。
 *
 * <p>活体真库语义：普通语句会替换诊断区（{@code SELECT 1/0; SELECT 5; SHOW WARNINGS} → 空行集），
 * 但<b>读回型诊断语句自身绝不能参与清空</b>，否则 {@code SHOW WARNINGS} 先清后读又回到恒空缺陷。
 * 本锁钉死这条边界——它是整个告警子系统最容易被写反的一行。
 */
class MycatdbCommandDiagnosticResetTest {

    @Test
    void warningAndErrorReadStatementsMustNotResetDiagnosticArea() {
        Assertions.assertTrue(MycatdbCommand.isDiagnosticReadStatement(new MySqlShowWarningsStatement()));
        Assertions.assertTrue(MycatdbCommand.isDiagnosticReadStatement(new MySqlShowErrorsStatement()));
        Assertions.assertTrue(MycatdbCommand
                .isDiagnosticReadStatement(SQLUtils.parseSingleMysqlStatement("SHOW COUNT(*) WARNINGS")));
    }

    @Test
    void ordinaryStatementsMustResetDiagnosticArea() {
        Assertions.assertFalse(MycatdbCommand.isDiagnosticReadStatement(parse("SELECT 1")));
        Assertions.assertFalse(MycatdbCommand.isDiagnosticReadStatement(parse("SHOW TABLES")));
        Assertions.assertFalse(MycatdbCommand.isDiagnosticReadStatement(parse("INSERT INTO t VALUES (1)")));
    }

    private static SQLStatement parse(String sql) {
        return SQLUtils.parseSingleMysqlStatement(sql);
    }
}
