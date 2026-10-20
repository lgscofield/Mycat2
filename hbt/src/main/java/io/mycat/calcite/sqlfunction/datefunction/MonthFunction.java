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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

public class MonthFunction extends MycatDateFunction {
    // MYCAT CHANGE (标量函数同族批量接入, 承 I-DEBT-32): 显式绑定 String 重载使
    // operand family=CHARACTER 直配 i_s VARCHAR; 双 overload 下靠 findMethod 取首个同名顺序
    // 不确定, 故显式取 Method。
    public static ScalarFunction scalarFunction = createStringScalarFunction();
    public static MonthFunction INSTANCE = new MonthFunction();

    private static ScalarFunction createStringScalarFunction() {
        try {
            Method method = MonthFunction.class.getMethod("month", String.class);
            return ScalarFunctionImpl.create(method);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public MonthFunction() {
        super("MONTH",
                scalarFunction
        );
    }

    public static int month(LocalDate localDate) {
        return localDate.getMonth().getValue();
    }

    /** 字符串入参形态: 宽松解析(MySQL 非法/空→NULL); 既有 month(LocalDate) 无 null 守卫且返 primitive, 故此处兼容 null。 */
    public static Integer month(String dateText) {
        LocalDate date = MycatBuiltInMethodImpl.stringToDate(dateText);
        return date == null ? null : month(date);
    }
}
