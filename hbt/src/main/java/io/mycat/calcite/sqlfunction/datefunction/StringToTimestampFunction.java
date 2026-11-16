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

import io.mycat.MycatTimeUtil;
import org.apache.calcite.mycat.MycatBuiltInMethodImpl;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;

public class StringToTimestampFunction extends MycatDateFunction {
    public static ScalarFunction scalarFunction = ScalarFunctionImpl.create(StringToTimestampFunction.class,
            "stringToTimestamp");
    public static StringToTimestampFunction INSTANCE = new StringToTimestampFunction();

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TS_NANO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    /**
     * MYCAT CHANGE (I-DEBT-144): TIME 简写族白名单——纯数字([.frac])、"D HH:MM:SS(.frac)"、
     * "[H]H:MM[:SS](.frac)"；防止非 TIME 字符串（如非法日期 "2020-13-99"）落进
     * MycatTimeUtil 后被静默解析为 0。
     */
    private static final String TIME_ABBREVIATION_PATTERN =
            "^-?\\d+(\\.\\d+)?$" +
            "|^-?\\d+\\s+\\d{1,2}:\\d{1,2}:\\d{1,2}(\\.\\d+)?$" +
            "|^-?\\d{1,2}:\\d{1,2}(:\\d{1,2})?(\\.\\d+)?$";

    public StringToTimestampFunction() {
        super("stringToTimestamp", scalarFunction);
    }

    /**
     * MYCAT CHANGE (I-DEBT-144): 修复三连伤——
     * ① 旧实现全部解析后恒 return null（函数永远返回 NULL）；
     * ② 旧实现裸 parseInt/charAt 对非法输入抛 AIOOBE/NFE；
     * ③ 旧实现 MSS 3 位分支 substring(1,2) 只取 1 位秒（与 MycatTimeUtil.timeStringToTimeDuration
     *    同源同病，common 侧已随本票修复）。
     * 语义对齐 MycatBuiltInMethodImpl.stringToTimestamp：datetime/date 交给
     * MycatBuiltInMethodImpl.timestampStringToTimestamp 解析；TIME 简写族
     * （[S]S/[M]MSS/[H]HMMSS/[D]D [H]H:MM:SS 等）交给 MycatTimeUtil.timeStringToTimeDuration；
     * 非法输入一律返回 NULL，不抛异常。
     */
    public static String stringToTimestamp(String text) {
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            Temporal temporal = MycatBuiltInMethodImpl.timestampStringToTimestamp(text);
            if (temporal instanceof LocalDateTime) {
                return formatTimestamp((LocalDateTime) temporal);
            }
            if (temporal instanceof LocalDate) {
                return formatTimestamp(((LocalDate) temporal).atStartOfDay());
            }
            if (temporal instanceof LocalTime) {
                return formatDuration(Duration.between(LocalTime.MIDNIGHT, (LocalTime) temporal));
            }
            return null;
        } catch (Throwable ignore) {
            // fall through：不是 datetime/date/time 带分隔符形态，尝试 TIME 简写族
        }
        try {
            if (!text.matches(TIME_ABBREVIATION_PATTERN)) {
                return null;
            }
            Duration duration = MycatTimeUtil.timeStringToTimeDuration(text);
            if (duration == null) {
                return null;
            }
            return formatDuration(duration);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String formatTimestamp(LocalDateTime dateTime) {
        if (dateTime.getNano() == 0) {
            return TS_FORMAT.format(dateTime);
        }
        return TS_NANO_FORMAT.format(dateTime);
    }

    private static String formatDuration(Duration duration) {
        boolean negative = duration.isNegative();
        Duration abs = negative ? duration.negated() : duration;
        long days = abs.toDays();
        long hours = abs.toHours() % 24;
        int minutes = (int) (abs.toMinutes() % 60);
        int seconds = (int) (abs.getSeconds() % 60);
        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('-');
        }
        if (days > 0) {
            sb.append(String.format("%d %02d:%02d:%02d", days, hours, minutes, seconds));
        } else {
            sb.append(String.format("%02d:%02d:%02d", hours, minutes, seconds));
        }
        if (abs.getNano() != 0) {
            sb.append(String.format(".%09d", abs.getNano()));
        }
        return sb.toString();
    }
}