package io.mycat.calcite;

import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;

/**
 * I-DEBT-23c 回归锁：钉死 fork {@code RexBuilder.makeIntervalLiteral(BigDecimal)} 与
 * hbt PARK 影子 {@code org.apache.calcite.rex.RexLiteral} 的区间表示收口。
 *
 * <p>fork 约定：DAY-TIME 区间 value 为“毫秒数”(BigDecimal)，YEAR-MONTH 为“总月数”。
 * 影子旧实现把 DAY-TIME 强绑 {@code Duration}，导致 sql2rel 期 {@code RexLiteral.<init>}
 * 的 valueMatchesType 断言直接抛 IllegalArgumentException（DATE_ADD/ADDDATE INTERVAL DAY 全崩）。
 * 收口后应：可构造、且 {@code getValueAs(Duration/Period)} 还原出正确的时间量。
 */
public class MycatIntervalLiteralCoercionTest {

    private static SqlIntervalQualifier q(TimeUnit unit) {
        return new SqlIntervalQualifier(unit, null, SqlParserPos.ZERO);
    }

    private static BigDecimal leadingValue(String text, TimeUnit unit) {
        // 复现 MycatCalciteMySqlNodeVisitor.visit(SQLIntervalExpr)：INTERVAL 'N' <unit>
        SqlIntervalLiteral lit =
                SqlLiteral.createInterval(1, text, q(unit), SqlParserPos.ZERO);
        return lit.getValueAs(BigDecimal.class);
    }

    @Test
    public void dayTimeIntervalIsConstructableAndDurationCorrect() {
        BigDecimal millis = leadingValue("10", TimeUnit.DAY);
        Assertions.assertEquals(864000000L, millis.longValue(), "fork 约定 DAY 区间=毫秒数");

        RexLiteral r =
                MycatCalciteSupport.RexBuilder.makeIntervalLiteral(millis, q(TimeUnit.DAY));
        Duration d = r.getValueAs(Duration.class);
        Assertions.assertEquals(10L, d.toDays(), "INTERVAL 10 DAY 应还原为 10 天");
    }

    @Test
    public void hourTimeIntervalDurationCorrect() {
        BigDecimal millis = leadingValue("2", TimeUnit.HOUR);
        Assertions.assertEquals(7200000L, millis.longValue());
        RexLiteral r =
                MycatCalciteSupport.RexBuilder.makeIntervalLiteral(millis, q(TimeUnit.HOUR));
        Assertions.assertEquals(2L, r.getValueAs(Duration.class).toHours(),
                "INTERVAL 2 HOUR 应还原为 2 小时");
    }

    @Test
    public void yearMonthIntervalPeriodCorrect() {
        BigDecimal months = leadingValue("1", TimeUnit.YEAR);
        Assertions.assertEquals(12L, months.longValue(), "fork 约定 YEAR 区间=总月数");
        RexLiteral r =
                MycatCalciteSupport.RexBuilder.makeIntervalLiteral(months, q(TimeUnit.YEAR));
        Period p = r.getValueAs(Period.class);
        // 关键回归点：原影子误用 Period.ofYears(月数)=12 年；收口后应按月还原为 1 年。
        Assertions.assertEquals(12, p.toTotalMonths(), "INTERVAL 1 YEAR 应还原为 12 个月(=1 年)");
    }
}
