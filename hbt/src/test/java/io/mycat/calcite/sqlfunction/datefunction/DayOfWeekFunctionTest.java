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
 * I-DEBT-32 静态单测：MySQL {@code DAYOFWEEK} 语义 1=周日..7=周六。
 *
 * <p>纠偏回归：旧实现 {@code getValue()+1}（ISO Mon=1..Sun=7）会让周日 7→8 越界；
 * 新实现 {@code getValue()%7+1} 须对全 7 天给出 MySQL 一致值，且 LocalDate/String 两形态同解。
 */
public class DayOfWeekFunctionTest {

    // 2017-06-11 是周日，往后连续 7 天覆盖 Sunday..Saturday。
    private static final LocalDate SUNDAY = LocalDate.of(2017, 6, 11);

    @Test
    public void allSevenDaysOnLocalDate() {
        // MySQL DAYOFWEEK: 周日=1, 周一=2, ... 周六=7
        Assertions.assertEquals(1, DayOfWeekFunction.dayOfWeek(SUNDAY));           // Sun
        Assertions.assertEquals(2, DayOfWeekFunction.dayOfWeek(SUNDAY.plusDays(1))); // Mon
        Assertions.assertEquals(3, DayOfWeekFunction.dayOfWeek(SUNDAY.plusDays(2))); // Tue
        Assertions.assertEquals(4, DayOfWeekFunction.dayOfWeek(SUNDAY.plusDays(3))); // Wed
        Assertions.assertEquals(5, DayOfWeekFunction.dayOfWeek(SUNDAY.plusDays(4))); // Thu
        Assertions.assertEquals(6, DayOfWeekFunction.dayOfWeek(SUNDAY.plusDays(5))); // Fri
        Assertions.assertEquals(7, DayOfWeekFunction.dayOfWeek(SUNDAY.plusDays(6))); // Sat
    }

    @Test
    public void allSevenDaysOnStringMatchLocalDate() {
        for (int i = 0; i < 7; i++) {
            LocalDate d = SUNDAY.plusDays(i);
            Integer fromString = DayOfWeekFunction.dayOfWeek(d.toString()); // "yyyy-MM-dd"
            Integer fromLocalDate = DayOfWeekFunction.dayOfWeek(d);
            Assertions.assertEquals(fromLocalDate, fromString,
                    "String 与 LocalDate 两形态须同解，日期=" + d);
        }
        // 关键锚点：周日=1（旧公式会给出 8）
        Assertions.assertEquals(1, DayOfWeekFunction.dayOfWeek("2017-06-11"));
        // datetime 串亦须正确解析
        Assertions.assertEquals(5, DayOfWeekFunction.dayOfWeek("2017-06-15 13:45:00"));
    }

    @Test
    public void nullAndUnparseableReturnNull() {
        Assertions.assertNull(DayOfWeekFunction.dayOfWeek((String) null));
        Assertions.assertNull(DayOfWeekFunction.dayOfWeek((LocalDate) null));
        // 非法日期串：MySQL 语义返回 NULL（附 warning），不应抛异常
        Assertions.assertNull(DayOfWeekFunction.dayOfWeek("varchar"));
        Assertions.assertNull(DayOfWeekFunction.dayOfWeek("not-a-date"));
    }
}
