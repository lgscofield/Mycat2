package io.mycat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.Temporal;

public class MycatTimeUtil {

    public static Duration timeStringToTimeDuration(String text) {
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return null;
        }
        int days = 0;
        int h = 0;
        int m = 0;
        int second = 0;
        int optional = text.lastIndexOf('.');
        int second_part = 0;
        if (optional != -1) {
            String substring = text.substring(optional + 1);
            second_part = Integer.parseInt(
                    substring
            ) * (int) Math.pow(10, 9 - substring.length());
            text = text.substring(0, optional);
        }
        switch (text.charAt(0)) {
            case '-': {//[-] DAYS [H]H:MM:SS
                // MYCAT CHANGE (I-DEBT-144): 修复负天数分支——原 parseInt(split[0]) 把 "-1 12" 整段当小时数抛 NFE；
                // 现按空格拆出天数、小时段单独解析（无空格时仍兼容 [-][H]H:MM:SS）。
                int suff = text.indexOf(" ");
                if (suff != -1) {
                    days = Integer.parseInt(text.substring(0, suff).trim());
                }
                String[] split = text.split(":");
                h = Integer.parseInt(suff == -1 ? split[0] : split[0].substring(suff + 1).trim());
                m = Integer.parseInt(split[1]);
                second = Integer.parseInt(split[2]);
                break;
            }
            default: {
                // MYCAT CHANGE (I-DEBT-144): 修复 "DAYS [H]H:MM:SS" 正天数前缀被丢弃的问题——
                // 原 text = split(" ")[1] 直接覆盖，days 恒 0；现拆出天数保留。
                if (text.contains(" ")) {
                    String[] daySplit = text.split(" ");
                    days = Integer.parseInt(daySplit[0].trim());
                    text = daySplit[1];
                }
                String[] split = text.split(":");
                split[0] = split[0].trim();
                if (split.length == 3) {
                    if (split[0].contains(" ")) {
                        //DAYS [H]H:MM:SS（不可达：上方已拆天数；防御保留并修正小时取段）
                        String[] s = split[0].split(" ");
                        days = Integer.parseInt(s[0]);
                        h = Integer.parseInt(s[1]);
                        m = Integer.parseInt(split[1]);
                        second = Integer.parseInt(split[2]);
                        break;
                    } else {
                        //[H]H:MM:SS
                        h = Integer.parseInt(split[0]);
                        m = Integer.parseInt(split[1]);
                        second = Integer.parseInt(split[2]);
                        break;
                    }
                } else if (split.length == 2) {
                    m = Integer.parseInt(split[0]);
                    second = Integer.parseInt(split[1]);
                    break;
                } else if (split.length == 1) {
                    String shor = split[0];
                    switch (shor.length()) {
                        case 1: {
                            //S
                            second = Integer.parseInt(shor);
                            break;
                        }
                        case 2: {
                            //[S]S
                            second = Integer.parseInt(shor);
                            break;
                        }
                        case 3: {
                            //MSS
                            // MYCAT CHANGE (I-DEBT-144): 原 substring(1,2) 只取 1 位秒——
                            // MySQL 简写 'MSS' 语义为分=首 1 位、秒=末 2 位，'123' → 00:01:23。
                            m = Integer.parseInt(shor.substring(0, 1));
                            second = Integer.parseInt(shor.substring(1, 3));
                            break;
                        }
                        case 4: {
                            //[M]MSS
                            m = Integer.parseInt(shor.substring(0, 2));
                            second = Integer.parseInt(shor.substring(2, 4));
                            break;
                        }
                        case 5: {
                            //HMMSS
                            h = Integer.parseInt(shor.substring(0, 1));
                            m = Integer.parseInt(shor.substring(1, 3));
                            second = Integer.parseInt(shor.substring(3, 5));
                            break;
                        }
                        case 6: {
                            //[H]HMMSS
                            h = Integer.parseInt(shor.substring(0, 2));
                            m = Integer.parseInt(shor.substring(2, 4));
                            second = Integer.parseInt(shor.substring(4, 6));
                            break;
                        }
                    }

                    break;
                }
            }
        }
        return Duration.ofDays(days).plusHours(h).plusMinutes(m).plusSeconds(second).plusNanos(second_part);

    }

    public static Temporal timestampStringToTimestamp(String s) {
        if (s == null) {
            return null;
        }
        int i = s.lastIndexOf(".");
        if (i == -1) {
            i = s.lastIndexOf(" ");
            if (i != -1) {
                return Timestamp.valueOf(s).toLocalDateTime();
            }
            if (s.contains("-")) {
                //SELECT DATE_FORMAT('2006-06-00', '%d'); unsupport
                return Date.valueOf(s).toLocalDate().atStartOfDay();
            }
            if (s.contains(":")) {
                return LocalTime.parse(s);
            }
            if (Character.isDigit(s.charAt(s.length() - 1))) {//只有数字则默认是秒
                return LocalTime.of(0, 0, Integer.parseInt(s));
            }
            throw new UnsupportedOperationException();
        } else {
            if (s.contains(" ")) {
                String[] uni = s.split(" ");
                return LocalDateTime.of(LocalDate.parse(uni[0]), LocalTime.parse(uni[1]));
            }
            return LocalTime.parse(s);
        }
    }
}
