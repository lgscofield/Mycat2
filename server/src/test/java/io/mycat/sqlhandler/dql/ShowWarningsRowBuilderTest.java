package io.mycat.sqlhandler.dql;

import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowWarningsStatement;
import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * SHOW WARNINGS 读面行集构造（I-DEBT-69 Blade B）单元锁。
 *
 * <p>判据来源 = 2026-10-29 活体真库探针（MySQL 8.0 / 5.7 / MariaDB 10.11 一致）：
 * <ul>
 *   <li>普通形态出 Level/Code/Message 三列，按产生顺序。</li>
 *   <li>{@code SHOW COUNT(*) WARNINGS} 出<b>单列单行</b>，列名 {@code @@session.warning_count}，
 *       值为条件总数（活体列头即为此名，故不能沿用三列定义）。</li>
 *   <li>{@code max_error_count} <b>只截断列表，不截断计数</b>
 *       （活体：{@code SET max_error_count=1; SELECT 1/0,2/0,3/0} → {@code @@warning_count}=3）。</li>
 *   <li>{@code SHOW WARNINGS LIMIT n} 受 {@code max_error_count} 双重约束（取较小者）；
 *       {@code LIMIT o,n} 从偏移切片。</li>
 * </ul>
 */
class ShowWarningsRowBuilderTest {

    private static List<WarningMessage> three() {
        return new ArrayList<>(Arrays.asList(
                new WarningMessage("Warning", 1365, "Division by 0"),
                new WarningMessage("Note", 1003, "Query rewritten"),
                new WarningMessage("Error", 1406, "Data too long")));
    }

    private static MySqlShowWarningsStatement plain() {
        return new MySqlShowWarningsStatement();
    }

    @Test
    void plainListingEmitsLevelCodeMessageInProductionOrder() {
        List<Object[]> rows = ShowWarningsSQLHandler.buildWarningRows(plain(), three(), 64);

        Assertions.assertEquals(3, rows.size());
        Assertions.assertEquals(Arrays.asList("Warning", 1365, "Division by 0"),
                Arrays.asList(rows.get(0)));
        Assertions.assertEquals("Note", rows.get(1)[0]);
        Assertions.assertEquals("Error", rows.get(2)[0]);
        Assertions.assertEquals(1406, rows.get(2)[1]);
    }

    @Test
    void countFormEmitsSingleRowWithTotalCount() {
        MySqlShowWarningsStatement stmt = plain();
        stmt.setCount(true);

        List<Object[]> rows = ShowWarningsSQLHandler.buildWarningRows(stmt, three(), 64);

        Assertions.assertEquals(1, rows.size(), "SHOW COUNT(*) WARNINGS is a single-row result");
        Assertions.assertEquals(1, rows.get(0).length, "single column (@@session.warning_count)");
        Assertions.assertEquals(3, rows.get(0)[0]);
    }

    /** 计数与列表解耦：截断只作用于列表（活体 @@warning_count=3 而 max_error_count=1）。 */
    @Test
    void countFormIsNotTruncatedByMaxErrorCount() {
        MySqlShowWarningsStatement stmt = plain();
        stmt.setCount(true);

        List<Object[]> rows = ShowWarningsSQLHandler.buildWarningRows(stmt, three(), 1);

        Assertions.assertEquals(3, rows.get(0)[0],
                "count must report total conditions, not the truncated listing size");
    }

    /** 列表受 max_error_count 截断，尾部丢弃。 */
    @Test
    void listingIsTruncatedByMaxErrorCount() {
        List<Object[]> rows = ShowWarningsSQLHandler.buildWarningRows(plain(), three(), 2);

        Assertions.assertEquals(2, rows.size());
        Assertions.assertEquals("Warning", rows.get(0)[0]);
        Assertions.assertEquals("Note", rows.get(1)[0]);
    }

    /** 显式 LIMIT n 与 max_error_count 取较小者。 */
    @Test
    void explicitLimitIsCappedByMaxErrorCount() {
        MySqlShowWarningsStatement stmt = plain();
        stmt.setLimit(new SQLLimit(10));

        Assertions.assertEquals(2, ShowWarningsSQLHandler.buildWarningRows(stmt, three(), 2).size(),
                "LIMIT 10 must be capped by max_error_count=2");
        Assertions.assertEquals(1, ShowWarningsSQLHandler.buildWarningRows(withLimit(1), three(), 64).size(),
                "LIMIT 1 without exceeding max_error_count yields exactly 1 row");
    }

    /** LIMIT o,n 从偏移切片。 */
    @Test
    void limitWithOffsetSlicesFromOffset() {
        List<Object[]> rows = ShowWarningsSQLHandler.buildWarningRows(withLimit(1, 1), three(), 64);

        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("Note", rows.get(0)[0], "LIMIT 1,1 must skip the first condition");
    }

    @Test
    void offsetBeyondSizeYieldsNoRows() {
        Assertions.assertEquals(0,
                ShowWarningsSQLHandler.buildWarningRows(withLimit(10, 5), three(), 64).size());
    }

    /** 负数 = 不截断哨兵（读不到 max_error_count 时的保守退化，见 handler 注释）。 */
    @Test
    void negativeMaxErrorCountMeansNoTruncation() {
        List<Object[]> rows = ShowWarningsSQLHandler.buildWarningRows(plain(), three(), -1);

        Assertions.assertEquals(3, rows.size(), "sentinel -1 must list every condition");
    }

    @Test
    void emptyBufferYieldsNoListingRowButCountFormStillReportsZero() {
        List<Object[]> rows = ShowWarningsSQLHandler
                .buildWarningRows(plain(), Collections.emptyList(), 64);
        Assertions.assertEquals(0, rows.size());

        MySqlShowWarningsStatement count = plain();
        count.setCount(true);
        List<Object[]> countRows = ShowWarningsSQLHandler
                .buildWarningRows(count, Collections.emptyList(), 64);
        Assertions.assertEquals(1, countRows.size());
        Assertions.assertEquals(0, countRows.get(0)[0]);
    }

    private static MySqlShowWarningsStatement withLimit(int rowCount) {
        MySqlShowWarningsStatement stmt = plain();
        stmt.setLimit(new SQLLimit(rowCount));
        return stmt;
    }

    private static MySqlShowWarningsStatement withLimit(int offset, int rowCount) {
        MySqlShowWarningsStatement stmt = plain();
        SQLLimit limit = new SQLLimit(rowCount);
        limit.setOffset(offset);
        stmt.setLimit(limit);
        return stmt;
    }
}
