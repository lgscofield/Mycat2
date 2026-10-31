package io.mycat.connection;

import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-69 Blade D 余项：JDBC 本地通道 {@code JdbcMycatDataContext} 的 {@code @@error_count} 读腿。
 *
 * <p>与 proxy 侧 {@code MycatDataContextImpl} 同构（同一真库判据：2026-10-31 活体
 * MySQL 8.0.46 / MariaDB 10.11.19），保证两条通道对 {@code SHOW ERRORS}/{@code @@error_count}
 * 的行为一致：纯告警 → 0，含 Error → Error 级条数，且与 {@code @@warning_count}（全量）互不污染。
 */
class JdbcMycatDataContextErrorCountTest {

    private static WarningMessage div0() {
        return new WarningMessage("Warning", 1365, "Division by 0");
    }

    private static WarningMessage dataTooLong() {
        return new WarningMessage("Error", 1406, "Data too long for column");
    }

    private static int intVar(JdbcMycatDataContext ctx, String name) {
        Object v = ctx.getVariable(false, name);
        Assertions.assertNotNull(v, name + " 不得回落为 null（真库返 0/正整数）");
        return ((Number) v).intValue();
    }

    @Test
    void errorCountZeroForWarningOnlyPreviousStatement() {
        JdbcMycatDataContext ctx = new JdbcMycatDataContext();
        ctx.addWarning(div0());
        ctx.clearWarnings();

        Assertions.assertEquals(0, intVar(ctx, "@@error_count"), "纯告警上一条 → @@error_count=0");
        Assertions.assertEquals(0, intVar(ctx, "@@session.error_count"), "带前缀同源");
    }

    @Test
    void errorCountReadsPreviousStatementErrorLevelOnly() {
        JdbcMycatDataContext ctx = new JdbcMycatDataContext();
        ctx.addWarning(div0());
        ctx.addWarning(dataTooLong());
        ctx.clearWarnings();

        Assertions.assertEquals(1, intVar(ctx, "@@error_count"), "只计 Error 级");
        Assertions.assertEquals(2, intVar(ctx, "@@warning_count"), "warning_count 仍取全量");
    }

    @Test
    void currentStatementErrorsDoNotFeedErrorCountVariable() {
        JdbcMycatDataContext ctx = new JdbcMycatDataContext();
        ctx.clearWarnings();
        ctx.addWarning(dataTooLong());

        Assertions.assertEquals(0, intVar(ctx, "@@error_count"), "本语句新增 Error 不参与本次读取");
    }

    @Test
    void errorCountIsZeroWithoutPreviousStatement() {
        JdbcMycatDataContext ctx = new JdbcMycatDataContext();
        Assertions.assertEquals(0, intVar(ctx, "@@error_count"), "无前置语句时为 0，不得 null");
    }
}
