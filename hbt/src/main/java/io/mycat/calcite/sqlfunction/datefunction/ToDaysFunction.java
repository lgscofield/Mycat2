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

import org.apache.calcite.mycat.MycatBuiltInMethodImpl;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.ChronoPeriod;
import java.time.temporal.ChronoUnit;

public class ToDaysFunction extends MycatDateFunction {
    // MYCAT CHANGE (标量函数同族批量接入, 承 I-DEBT-32): 显式绑定 String 重载使 operand
    // family=CHARACTER 直配 i_s VARCHAR; 双 overload 下靠 findMethod 取首个同名顺序不确定。
    public static ScalarFunction scalarFunction = createStringScalarFunction();
    public static ToDaysFunction INSTANCE = new ToDaysFunction();

    private static ScalarFunction createStringScalarFunction() {
        try {
            Method method = ToDaysFunction.class.getMethod("toDays", String.class);
            return ScalarFunctionImpl.create(method);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public ToDaysFunction() {
        super("TO_DAYS",
                scalarFunction
        );
    }

    public static Long toDays(LocalDate date) {
        if (date==null) {
            return null;
        }
        //@todo
        LocalDate startDate = LocalDate.of(0, 1, 1);
        return (ChronoUnit.DAYS.between(startDate, date));
    }

    /** 字符串入参形态: 宽松解析(MySQL 非法日期→NULL 不抛), 委托既有 LocalDate 实现。 */
    public static Long toDays(String dateText) {
        LocalDate date = MycatBuiltInMethodImpl.stringToDate(dateText);
        return date == null ? null : toDays(date);
    }
}
