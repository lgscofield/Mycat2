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
import java.time.LocalDate;

public class DayOfYearFunction extends MycatDateFunction {
    // MYCAT CHANGE (标量函数同族批量接入, 承 I-DEBT-32): 显式绑定 String 重载使 operand
    // family=CHARACTER 直配 i_s VARCHAR; 双 overload 下靠 findMethod 取首个同名顺序不确定。
    public static ScalarFunction scalarFunction = createStringScalarFunction();
    public static DayOfYearFunction INSTANCE = new DayOfYearFunction();

    private static ScalarFunction createStringScalarFunction() {
        try {
            Method method = DayOfYearFunction.class.getMethod("dayOfYear", String.class);
            return ScalarFunctionImpl.create(method);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public DayOfYearFunction() {
        super("DAYOFYEAR", scalarFunction);
    }

    public static Integer dayOfYear(LocalDate date) {
        if (date == null){
            return null;
        }
        return date.getDayOfYear();
    }

    /** 字符串入参形态: 宽松解析(MySQL 非法日期→NULL 不抛), 委托既有 LocalDate 实现。 */
    public static Integer dayOfYear(String dateText) {
        LocalDate date = MycatBuiltInMethodImpl.stringToDate(dateText);
        return date == null ? null : dayOfYear(date);
    }
}
