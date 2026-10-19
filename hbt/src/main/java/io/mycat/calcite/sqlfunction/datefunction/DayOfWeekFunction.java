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

import org.apache.calcite.mycat.MycatBuiltInMethodImpl;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;

public class DayOfWeekFunction  extends MycatDateFunction {
    // MYCAT CHANGE (I-DEBT-32): codegen 绑定显式指向 dayOfWeek(String) 重载，而非按名 create。
    // ScalarFunctionImpl.create(clazz, "dayOfWeek") 靠 findMethod 取 getMethods() 首个同名方法，
    // 存在 LocalDate/String 双重载时绑定顺序不确定；显式取 Method 保证 Mycat 算子的 operand
    // family 恒为 CHARACTER（String→VARCHAR），可直接匹配 information_schema 的 VARCHAR 列，
    // 不再因 DATE family 接不住 VARCHAR 而降为 SqlUnresolvedFunction。
    public static ScalarFunction scalarFunction = createStringScalarFunction();
    public static DayOfWeekFunction INSTANCE = new DayOfWeekFunction();

    private static ScalarFunction createStringScalarFunction() {
        try {
            Method method = DayOfWeekFunction.class.getMethod("dayOfWeek", String.class);
            return ScalarFunctionImpl.create(method);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public DayOfWeekFunction() {
        super("DAYOFWEEK", scalarFunction);
    }

    /**
     * MySQL DAYOFWEEK：1=周日..7=周六。java.time DayOfWeek 为 ISO（Mon=1..Sun=7），
     * 故取 {@code getValue()%7+1} 纠偏（旧式 {@code getValue()+1} 会让周日 7→8 越界）。
     */
    public static Integer dayOfWeek(LocalDate date) {
        if (date == null){
            return null;
        }
        return date.getDayOfWeek().getValue() % 7 + 1;
    }

    /**
     * 字符串入参形态：与兄弟 {@code DateFormatFunction}（吃 String + 内部解析）同惯例，
     * 使校验期 operand family=CHARACTER，直配 i_s 的 VARCHAR 列。沿用 MySQL 宽松语义：
     * 非法/不含日期成分的串返回 NULL（附 warning），不抛异常。
     */
    public static Integer dayOfWeek(String dateText) {
        if (dateText == null) {
            return null;
        }
        try {
            Temporal temporal = MycatBuiltInMethodImpl.timestampStringToTimestamp(dateText);
            if (temporal == null || !temporal.isSupported(ChronoField.DAY_OF_WEEK)) {
                return null;
            }
            return DayOfWeek.from(temporal).getValue() % 7 + 1;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

