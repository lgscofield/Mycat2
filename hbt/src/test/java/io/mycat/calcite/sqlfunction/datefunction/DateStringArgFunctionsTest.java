/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite.sqlfunction.datefunction;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

/**
 * 标量函数同族批量接入·静态单测（tranche 1+2, 承 I-DEBT-32）。
 *
 * <p>覆盖 11 个新接入 String 形参的日期族标量：YEAR/MONTH/DAYOFYEAR/QUARTER/WEEKDAY/
 * MONTHNAME/DAYNAME/LAST_DAY/TO_DAYS（tranche 1）+ WEEK/DATEDIFF（tranche 2）。
 * 验证三条不变式：
 * 1) String 与 LocalDate 两形态同解；2) MySQL 锚点值正确；3) null/非法串→NULL 不抛异常。
 */
public class DateStringArgFunctionsTest {

    // 2017-02-03 是周五，可同时锚定 weekday=4(Fri)/dayname=Friday/dayofyear=34 等。
    private static final LocalDate ANCHOR = LocalDate.of(2017, 2, 3);
    private static final LocalDate LEAP = LocalDate.of(2016, 2, 29);

    @Test
    public void stringFormMatchesLocalDateForm() {
        for (LocalDate d : new LocalDate[]{ANCHOR, LEAP, LocalDate.of(2016, 12, 31)}) {
            String s = d.toString();
            Assertions.assertEquals(YearFunction.year(d), YearFunction.year(s), "YEAR " + d);
            Assertions.assertEquals(MonthFunction.month(d), MonthFunction.month(s), "MONTH " + d);
            Assertions.assertEquals(DayOfYearFunction.dayOfYear(d), DayOfYearFunction.dayOfYear(s), "DAYOFYEAR " + d);
            Assertions.assertEquals(QuarterFunction.quarter(d), QuarterFunction.quarter(s), "QUARTER " + d);
            Assertions.assertEquals(WeekDayFunction.weekDay(d), WeekDayFunction.weekDay(s), "WEEKDAY " + d);
            Assertions.assertEquals(MonthNameFunction.monthName(d), MonthNameFunction.monthName(s), "MONTHNAME " + d);
            Assertions.assertEquals(DaynameFunction.dayname(d), DaynameFunction.dayname(s), "DAYNAME " + d);
            Assertions.assertEquals(LastDayFunction.lastDay(d), LastDayFunction.lastDay(s), "LAST_DAY " + d);
            Assertions.assertEquals(ToDaysFunction.toDays(d), ToDaysFunction.toDays(s), "TO_DAYS " + d);
            // tranche 2: WEEK 默认 Locale 环域, 只锚两形态同解; DATEDIFF 反身与跨日
            Assertions.assertEquals(WeekFunction.week(d), WeekFunction.week(s), "WEEK " + d);
            Assertions.assertEquals(WeekFunction.week(d, 3), WeekFunction.week(s, 3), "WEEK(mode=3) " + d);
            Assertions.assertEquals(0L, DateDiffFunction.dateDiff(s, s), "DATEDIFF reflexive " + d);
            Assertions.assertEquals(DateDiffFunction.dateDiff(d, d.minusDays(34)),
                    DateDiffFunction.dateDiff(s, d.minusDays(34).toString()), "DATEDIFF " + d);
        }
    }

    @Test
    public void mysqlAnchorValuesOnString() {
        // 2017-02-03 周五
        Assertions.assertEquals(2017, YearFunction.year("2017-02-03"));
        Assertions.assertEquals(2, MonthFunction.month("2017-02-03"));
        Assertions.assertEquals(34, DayOfYearFunction.dayOfYear("2017-02-03"));
        Assertions.assertEquals(1, QuarterFunction.quarter("2017-02-03"));
        Assertions.assertEquals(4, WeekDayFunction.weekDay("2017-02-03")); // MySQL WEEKDAY: Mon=0..Sun=6
        Assertions.assertEquals("February", MonthNameFunction.monthName("2017-02-03"));
        Assertions.assertEquals("Friday", DaynameFunction.dayname("2017-02-03"));
        Assertions.assertEquals(LocalDate.of(2017, 2, 28), LastDayFunction.lastDay("2017-02-03"));
        // 闰年 + 年末
        Assertions.assertEquals(60, DayOfYearFunction.dayOfYear("2016-02-29"));
        Assertions.assertEquals(LocalDate.of(2016, 2, 29), LastDayFunction.lastDay("2016-02-01"));
        Assertions.assertEquals(4, QuarterFunction.quarter("2016-12-31"));
        // datetime 串亦须宽松解析
        Assertions.assertEquals(2017, YearFunction.year("2017-02-03 13:45:00"));
        Assertions.assertEquals("Friday", DaynameFunction.dayname("2017-02-03 00:00:01"));
        // tranche 2: WEEK mode=3 为 ISO 周(locale 无关): 2017-02-03(周五)属 ISO 第 5 周
        Assertions.assertEquals(5, WeekFunction.week("2017-02-03", 3));
        // DATEDIFF: 天数差 a-b, 与 MySQL DATEDIFF(d1,d2)=d1-d2 同向(2017-01-01→2017-02-03 隔 33 天)
        Assertions.assertEquals(33L, DateDiffFunction.dateDiff("2017-02-03", "2017-01-01"));
        Assertions.assertEquals(-58L, DateDiffFunction.dateDiff("2016-12-31 10:00:00", "2017-02-27"));
    }

    @Test
    public void toDaysStringMatchesLocalDate() {
        // TO_DAYS 返 Long, 单独锚定两形态同解
        Assertions.assertEquals(ToDaysFunction.toDays(ANCHOR), ToDaysFunction.toDays(ANCHOR.toString()));
        Assertions.assertEquals(ToDaysFunction.toDays(LEAP), ToDaysFunction.toDays(LEAP.toString()));
    }

    @Test
    public void nullAndUnparseableReturnNull() {
        // null 与非法日期串：MySQL 语义返回 NULL（附 warning），不应抛异常
        Assertions.assertNull(YearFunction.year("nope"));
        Assertions.assertNull(MonthFunction.month((String) null));
        Assertions.assertNull(MonthFunction.month("varchar"));
        Assertions.assertNull(DayOfYearFunction.dayOfYear("bad"));
        Assertions.assertNull(QuarterFunction.quarter((String) null));
        Assertions.assertNull(WeekDayFunction.weekDay("bad"));
        // MONTHNAME 既有 LocalDate 形态无 null 守卫, String 入口须兼容 null/非法
        Assertions.assertNull(MonthNameFunction.monthName((String) null));
        Assertions.assertNull(MonthNameFunction.monthName("not-a-date"));
        Assertions.assertNull(DaynameFunction.dayname("bad"));
        Assertions.assertNull(LastDayFunction.lastDay((String) null));
        Assertions.assertNull(ToDaysFunction.toDays("bad"));
        // tranche 2: WEEK/DATEDIFF 的 null/非法守卫
        Assertions.assertNull(WeekFunction.week((String) null));
        Assertions.assertNull(WeekFunction.week("bad", 1));
        Assertions.assertNull(DateDiffFunction.dateDiff((String) null, "2017-02-03"));
        Assertions.assertNull(DateDiffFunction.dateDiff("2017-02-03", "not-a-date"));
    }
}
