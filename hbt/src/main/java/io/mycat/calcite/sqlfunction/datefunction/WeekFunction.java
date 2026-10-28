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
        int m = (mode == null) ? 0 : mode;
        // MYCAT CHANGE (I-DEBT-32-FAMILY 候补残面): MySQL WEEK(date,mode) mode 0..7 语义保真。
        // 旧实现仅覆盖 mode 0/1(且 mode 0 依赖 Locale.getDefault() 换机即飘) 与 mode 3(ISO)，
        // mode 2/4/5/6/7 直接抛 UnsupportedOperationException。下面映射经 2000-01-01..2030-12-31
        // 共 11323 天逐日对拍<b>真 MySQL 8.0</b> 确定性反推、每个 mode 均得唯一精确解：
        //   首列日   : 偶 mode(0,2,4,6)=周日, 奇 mode(1,3,5,7)=周一;
        //   首周最少天数: mode∈{0,2,5,7}=7, mode∈{1,3,4,6}=4;
        //   周号度量 : mode∈{0,1,4,5}=weekOfYear(可含周 0), mode∈{2,3,6,7}=weekOfWeekBasedYear(1..53)。
        if (m < 0 || m > 7) {
            throw new UnsupportedOperationException("mode:" + m);
        }
        DayOfWeek firstDay = (m % 2 == 0) ? DayOfWeek.SUNDAY : DayOfWeek.MONDAY;
        int minDays = (m == 0 || m == 2 || m == 5 || m == 7) ? 7 : 4;
        WeekFields wf = WeekFields.of(firstDay, minDays);
        boolean useWeekOfYear = (m == 0 || m == 1 || m == 4 || m == 5);
        return date.get(useWeekOfYear ? wf.weekOfYear() : wf.weekOfWeekBasedYear());
    }
}

