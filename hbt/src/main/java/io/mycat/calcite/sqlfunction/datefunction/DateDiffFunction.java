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

import org.apache.calcite.linq4j.function.Parameter;
import org.apache.calcite.mycat.MycatBuiltInMethodImpl;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;

import java.lang.reflect.Method;
import java.time.*;

public class DateDiffFunction extends MycatDateFunction {
    // MYCAT CHANGE (标量函数同族批量接入 tranche 2, 承 I-DEBT-32): 显式绑定双 String 重载使
    // operand family=CHARACTER 直配 i_s VARCHAR; 否则 DATEDIFF(VARCHAR,VARCHAR) 无法本地落码。
    public static ScalarFunction scalarFunction = createStringScalarFunction();
    public static DateDiffFunction INSTANCE = new DateDiffFunction();

    private static ScalarFunction createStringScalarFunction() {
        try {
            Method method = DateDiffFunction.class.getMethod("dateDiff", String.class, String.class);
            return ScalarFunctionImpl.create(method);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public DateDiffFunction() {
        super("DATEDIFF",
                scalarFunction
        );
    }

    public static Long dateDiff(LocalDate date0, LocalDate date1) {
        if (date0 == null || date1 == null) {
            return null;
        }
        long f = date0.toEpochDay();
        long f2 = date1.toEpochDay();
        return f - f2;
    }

    /** 字符串入参形态: 宽松解析(任一非法/NULL→NULL, 合 MySQL 语义), 委托既有 LocalDate 实现。 */
    public static Long dateDiff(String dateText0, String dateText1) {
        LocalDate date0 = MycatBuiltInMethodImpl.stringToDate(dateText0);
        LocalDate date1 = MycatBuiltInMethodImpl.stringToDate(dateText1);
        return dateDiff(date0, date1);
    }
}
