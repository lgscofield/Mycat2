/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.calcite.sqlfunction.datefunction;

import org.apache.calcite.linq4j.function.Parameter;
import org.apache.calcite.mycat.MycatBuiltInMethodImpl;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

public class YearFunction extends MycatDateFunction {
    // MYCAT CHANGE (标量函数同族批量接入, 承 I-DEBT-32): 显式绑定 String 重载,
    // 使 operand family=CHARACTER 直配 information_schema 的 VARCHAR 列(否则 LocalDate→DATE
    // family 经方案 E 夺名后接不住 VARCHAR → SqlUnresolvedFunction)。双 overload 下
    // ScalarFunctionImpl.create(clazz,name) 靠 findMethod 取首个同名顺序不确定, 故显式取 Method。
    public static ScalarFunction scalarFunction = createStringScalarFunction();
    public static YearFunction INSTANCE = new YearFunction();

    private static ScalarFunction createStringScalarFunction() {
        try {
            Method method = YearFunction.class.getMethod("year", String.class);
            return ScalarFunctionImpl.create(method);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public YearFunction() {
        super("YEAR", scalarFunction);
    }

    public static Integer year(LocalDate date) {
        if (date==null) {
            return null;
        }
       return date.getYear();
    }

    /** 字符串入参形态: 宽松解析日期串(MySQL 非法日期→NULL 不抛), 委托既有 LocalDate 实现。 */
    public static Integer year(String dateText) {
        LocalDate date = MycatBuiltInMethodImpl.stringToDate(dateText);
        return date == null ? null : year(date);
    }
}

