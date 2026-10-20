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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.WeekFields;
import java.util.Locale;

public class WeekFunction extends MycatDateFunction {
    // MYCAT CHANGE (标量函数同族批量接入 tranche 2, 承 I-DEBT-32): 显式绑定 String+可选 mode 重载
    // 使首参 family=CHARACTER 直配 i_s VARCHAR; 可选参保留 WEEK(date,mode) 二参 arity。
    public static ScalarFunction scalarFunction = createStringScalarFunction();
    public static WeekFunction INSTANCE = new WeekFunction();

    private static ScalarFunction createStringScalarFunction() {
        try {
            Method method = WeekFunction.class.getMethod("week", String.class, Integer.class);
            return ScalarFunctionImpl.create(method);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public WeekFunction() {
        super("WEEK", scalarFunction);
    }

    public static Integer week(LocalDate date){
        return week(date,null);
    }

    /** 字符串入参形态: 宽松解析(MySQL 非法日期→NULL 不抛), 委托既有 LocalDate 实现。 */
    public static Integer week(String dateText) {
        return week(dateText, null);
    }

    /** 字符串+mode 形态: 可选模数与 LocalDate 版同义。 */
    public static Integer week(String dateText, @Parameter(name = "mode", optional = true) Integer mode) {
        LocalDate date = MycatBuiltInMethodImpl.stringToDate(dateText);
        return date == null ? null : week(date, mode);
    }

    public static Integer week(LocalDate date, @Parameter(name = "mode", optional = true) Integer mode) {
        if (date==null) {
            return null;
        }
        if (mode == null){
            mode = 0;
        }
        LocalDate localDate = date;
        int offset;
        switch (mode){
            case 0:
                offset = 0;
                break;
            case 1:
                offset = 0;
                break;
            case 3:
                return date.get(WeekFields.ISO.weekOfWeekBasedYear());
              //  return localDate.get(WeekFields.of(Locale.getDefault()).weekOfYear())-1;
            case 2:
            case 4:
            case 5:
            case 6:
            case 7:
            default:
                throw new UnsupportedOperationException("mode:"+mode);
        }
        int weekNumber = localDate.get(WeekFields.of(Locale.getDefault()).weekOfYear()) - offset;
        return weekNumber;
    }
}

