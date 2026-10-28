/**
 * Copyright (C) <2026>  <chen junwen>
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
 * I-DEBT-32-FAMILY 候补残面：MySQL {@code WEEK(date, mode)} mode 0..7 语义保真回归锁。
 *
 * <p>旧实现仅覆盖 mode 0/1（且 mode 0 依赖 {@code Locale.getDefault()}，换机即飘）、
 * mode 3（ISO），mode 2/4/5/6/7 直接 {@code throw UnsupportedOperationException}。
 *
 * <p>期望值全部取自闭卷 11323 天（2000-01-01..2030-12-31）逐日对拍<b>真 MySQL 8.0</b>
 * （docker {@code week-m80}）后确定性地反推出的 {@code WeekFields} 映射，跨年边界周号
 * 为 mode 语义最敏感处，故测试集集中于年初/年末窗口并含一枚年中锚点。
 */
public class WeekFunctionModeTest {

    // date -> MySQL WEEK(date, mode) for mode 0..7（真库地面真相）
    private static final Object[][] GT = {
        {"2000-01-01", new int[]{0, 0, 52, 52, 0, 0, 52, 52}},
        {"2008-12-31", new int[]{52, 53, 52, 1, 53, 52, 53, 52}},
        {"2012-01-01", new int[]{1, 0, 1, 52, 1, 0, 1, 52}},
        {"2016-01-01", new int[]{0, 0, 52, 53, 0, 0, 52, 52}},
        {"2018-01-01", new int[]{0, 1, 53, 1, 1, 1, 1, 1}},
        {"2019-01-01", new int[]{0, 1, 52, 1, 1, 0, 1, 53}},
        {"2021-01-01", new int[]{0, 0, 52, 53, 0, 0, 53, 52}},
        {"2023-01-01", new int[]{1, 0, 1, 52, 1, 0, 1, 52}},
        {"2026-10-18", new int[]{42, 42, 42, 42, 42, 41, 42, 41}},
        {"2026-12-31", new int[]{52, 53, 52, 53, 52, 52, 52, 52}},
    };

    @Test
    public void allModesMatchMysqlGroundTruth() {
        for (Object[] row : GT) {
            LocalDate d = LocalDate.parse((String) row[0]);
            int[] expect = (int[]) row[1];
            for (int mode = 0; mode <= 7; mode++) {
                Assertions.assertEquals(expect[mode], (int) WeekFunction.week(d, mode),
                        "WEEK(" + d + ", " + mode + ")");
            }
        }
    }

    @Test
    public void stringFormAgreesWithLocalDate() {
        for (Object[] row : GT) {
            String ds = (String) row[0];
            int[] expect = (int[]) row[1];
            for (int mode = 0; mode <= 7; mode++) {
                Assertions.assertEquals(expect[mode], (int) WeekFunction.week(ds, mode),
                        "WEEK('" + ds + "', " + mode + ") 字符串形态须与 LocalDate 同解");
            }
        }
    }

    @Test
    public void nullModeDefaultsToModeZero() {
        for (Object[] row : GT) {
            LocalDate d = LocalDate.parse((String) row[0]);
            int[] expect = (int[]) row[1];
            Assertions.assertEquals(expect[0], (int) WeekFunction.week(d),
                    "WEEK(" + d + ") 缺省 mode 须等价 mode 0");
        }
    }

    @Test
    public void nullAndUnparseableReturnNull() {
        Assertions.assertNull(WeekFunction.week((LocalDate) null, 2));
        Assertions.assertNull(WeekFunction.week((String) null, 5));
        Assertions.assertNull(WeekFunction.week("not-a-date", 7));
    }
}
