package io.mycat.sqlhandler.dql;

import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowErrorsStatement;
import io.mycat.beans.mycat.WarningMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SHOW ERRORS 读面行集构造（I-DEBT-69 Blade D 余项：与 SHOW WARNINGS 对称的 Error 级过滤）。
 *
 * <p>判据来源 = 2026-10-31 活体真库探针（MySQL 8.0.46 / MariaDB 10.11.19 一致）：
 * <ul>
 *   <li>{@code SELECT 1/0}（仅产 Warning 1365）后 {@code SHOW ERRORS} → <b>空行集</b>，
 *       {@code @@error_count}=0；{@code SHOW WARNINGS} 才见那条 Warning。</li>
 *   <li>产 Error 的语句后 {@code SHOW ERRORS} 出 {@code Error/Code/Message} 行，
 *       {@code SHOW COUNT(*) ERRORS} 出<b>单列单行</b>、列头 {@code @@session.error_count}、
 *       值 = Error 级条件数。</li>
 *   <li>{@code max_error_count} <b>只截断列表，不截断计数</b>；{@code LIMIT o,n} 在
 *       <b>过滤后的 Error 子集</b>上切片（真库先分级过滤再截断，与 SHOW WARNINGS 的不过滤相反）。</li>
 * </ul>
 *
 * <p>本类只锁纯函数 {@link ShowErrorsSQLHandler#buildErrorRows}，不触会话/网络。
 */
class ShowErrorsRowBuilderTest {

    private static List<WarningMessage> mixed() {
        return new ArrayList<>(Arrays.asList(
                new WarningMessage("Warning", 1365, "Division by 0"),
                new WarningMessage("Note", 1003, "Query rewritten"),
                new WarningMessage("Error", 1406, "Data too long")));
    }

    private static List<WarningMessage> onlyWarnings() {
        return new ArrayList<>(Arrays.asList(
                new WarningMessage("Warning", 1365, "Division by 0"),
                new WarningMessage("Note", 1003, "Query rewritten")));
    }

    private static List<WarningMessage> errors(int n) {
        List<WarningMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new WarningMessage("Error", 1000 + i, "e" + i));
        }
        return list;
    }

    private static MySqlShowErrorsStatement plain() {
        return new MySqlShowErrorsStatement();
    }

    private static MySqlShowErrorsStatement withLimit(int rowCount) {
        MySqlShowErrorsStatement stmt = plain();
        stmt.setLimit(new SQLLimit(rowCount));
        return stmt;
    }

    private static MySqlShowErrorsStatement withLimit(int offset, int rowCount) {
        MySqlShowErrorsStatement stmt = plain();
        SQLLimit limit = new SQLLimit(rowCount);
        limit.setOffset(offset);
        stmt.setLimit(limit);
        return stmt;
    }

    /** 普通形态只保留 Error 级行，Warning/Note 全丢弃（与 SHOW WARNINGS 的不过滤相反）。 */
    @Test
    void plainListingKeepsOnlyErrorLevelRows() {
        List<Object[]> rows = ShowErrorsSQLHandler.buildErrorRows(plain(), mixed(), 64);

        Assertions.assertEquals(1, rows.size(), "SHOW ERRORS must drop Warning/Note");
        Assertions.assertEquals(Arrays.asList("Error", 1406, "Data too long"),
                Arrays.asList(rows.get(0)));
    }

    /** 纯告警语句（无 Error）→ SHOW ERRORS 空行集（活体取证核心，此前假空恰好蒙对，须由过滤证明）。 */
    @Test
    void pureWarningBufferYieldsNoErrorRows() {
        Assertions.assertEquals(0,
                ShowErrorsSQLHandler.buildErrorRows(plain(), onlyWarnings(), 64).size(),
                "真库：纯告警语句 SHOW ERRORS 为空");
    }

    /** COUNT(*) 形态单列单行，值 = Error 级条件数（非全条件数）。 */
    @Test
    void countFormReportsErrorLevelTotalInSingleRow() {
        MySqlShowErrorsStatement stmt = plain();
        stmt.setCount(true);

        List<Object[]> rows = ShowErrorsSQLHandler.buildErrorRows(stmt, mixed(), 64);

        Assertions.assertEquals(1, rows.size(), "SHOW COUNT(*) ERRORS is a single-row result");
        Assertions.assertEquals(1, rows.get(0).length, "single column (@@session.error_count)");
        Assertions.assertEquals(1, rows.get(0)[0], "count = Error-level conditions only");
    }

    /** COUNT(*) 对纯告警缓冲报 0（活体 @@error_count=0）。 */
    @Test
    void countFormZeroForPureWarning() {
        MySqlShowErrorsStatement stmt = plain();
        stmt.setCount(true);

        List<Object[]> rows = ShowErrorsSQLHandler.buildErrorRows(stmt, onlyWarnings(), 64);

        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals(0, rows.get(0)[0]);
    }

    /** 计数与列表解耦：max_error_count=1 时计数仍报 Error 总数 3。 */
    @Test
    void countFormIsNotTruncatedByMaxErrorCount() {
        MySqlShowErrorsStatement stmt = plain();
        stmt.setCount(true);

        List<Object[]> rows = ShowErrorsSQLHandler.buildErrorRows(stmt, errors(3), 1);

        Assertions.assertEquals(3, rows.get(0)[0],
                "count must report total Error conditions, not the truncated listing size");
    }

    /** 列表受 max_error_count 截断，尾部丢弃。 */
    @Test
    void listingIsTruncatedByMaxErrorCount() {
        List<Object[]> rows = ShowErrorsSQLHandler.buildErrorRows(plain(), errors(3), 2);

        Assertions.assertEquals(2, rows.size());
        Assertions.assertEquals(1000, rows.get(0)[1]);
        Assertions.assertEquals(1001, rows.get(1)[1]);
    }

    /** 显式 LIMIT n 与 max_error_count 取较小者。 */
    @Test
    void explicitLimitIsCappedByMaxErrorCount() {
        Assertions.assertEquals(2,
                ShowErrorsSQLHandler.buildErrorRows(withLimit(10), errors(3), 2).size(),
                "LIMIT 10 must be capped by max_error_count=2");
        Assertions.assertEquals(1,
                ShowErrorsSQLHandler.buildErrorRows(withLimit(1), errors(3), 64).size(),
                "LIMIT 1 without exceeding max_error_count yields exactly 1 row");
    }

    /** 先按 Level 过滤，再在 Error 子集上按 LIMIT o,n 切片。 */
    @Test
    void limitWithOffsetSlicesAmongErrorsOnly() {
        List<WarningMessage> buf = new ArrayList<>(Arrays.asList(
                new WarningMessage("Warning", 1, "w"),
                new WarningMessage("Error", 2, "e0"),
                new WarningMessage("Error", 3, "e1")));

        List<Object[]> rows = ShowErrorsSQLHandler.buildErrorRows(withLimit(1, 1), buf, 64);

        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals(3, rows.get(0)[1], "过滤 Error 后 LIMIT 1,1 应跳过首个 Error 取第二个");
    }

    /** 偏移越过 Error 子集长度 → 空。 */
    @Test
    void offsetBeyondErrorSubsetYieldsNoRows() {
        Assertions.assertEquals(0,
                ShowErrorsSQLHandler.buildErrorRows(withLimit(10, 5), mixed(), 64).size());
    }

    /** 负数哨兵 = 不截断（读不到 max_error_count 的保守退化）。 */
    @Test
    void negativeMaxErrorCountMeansNoTruncation() {
        List<Object[]> rows = ShowErrorsSQLHandler.buildErrorRows(plain(), errors(3), -1);

        Assertions.assertEquals(3, rows.size(), "sentinel -1 must list every Error condition");
    }
}
