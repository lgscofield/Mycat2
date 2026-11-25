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
package io.mycat.mycatmysql;

/**
 * I-PR-1 D2：控制面 SQL 豁免准入闸。许可耗尽时运维仍须能发 hint 查线程/杀线程，
 * 否则「越忙越查不了」——准入闸最容易把运维自己锁在门外的一处。
 *
 * <p>不引解析器（成本与范围）。口径与 {@code HintHandler} 对齐：
 * hint 须是整条语句（hint-only），且接受 {@code SqlHints.unWrapperHint} 认可的加号/感叹号标记变体
 * ——{@code HintBuilder.build()} 实际发出的正是带标记、带尾分号的文本。
 * {@code show @@} 同样只豁免单条语句，分号后若还有可执行内容必须落回准入闸。
 * MySQL/MariaDB 版本注释也是可执行内容，不得当作无执行效果的尾部噪声。
 * 若「带 hint 前缀」就豁免，任何客户端给 DML 挂个假 hint 即可永久绕过准入闸。
 *
 * @author chen junwen
 */
public final class ControlPlaneSql {

    private static final String HINT_PREFIX = "mycat:";
    private static final String SHOW = "show";
    private static final String AT_AT = "@@";

    private ControlPlaneSql() {
    }

    public static boolean isControlPlane(String sql) {
        if (sql == null) {
            return false;
        }
        int len = sql.length();
        int i = 0;
        boolean sawHint = false;
        while (i < len) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                int bodyEnd = end < 0 ? len : end;
                boolean mycatHint = isMycatHintBody(sql.substring(i + 2, bodyEnd));
                if (isExecutableComment(sql, i) && !mycatHint) {
                    return false;
                }
                if (mycatHint) {
                    sawHint = true;
                }
                i = end < 0 ? len : end + 2;
            } else {
                break;
            }
        }
        if (sawHint) {
            return isTrailingNoiseOnly(sql, i);
        }
        return isShowAtAt(sql, i);
    }

    private static boolean isMycatHintBody(String body) {
        String text = body.trim();
        if (!text.isEmpty() && (text.charAt(0) == '+' || text.charAt(0) == '!')) {
            text = text.substring(1).trim();
        }
        return text.regionMatches(true, 0, HINT_PREFIX, 0, HINT_PREFIX.length());
    }

    /** hint 之后只容许空白、注释与分号：{@code HintBuilder} 产物带尾分号，而挂 DML 的必须落回闸内。 */
    private static boolean isTrailingNoiseOnly(String sql, int from) {
        int i = from;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == ';') {
                i++;
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                if (isExecutableComment(sql, i)) {
                    return false;
                }
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean isExecutableComment(String sql, int start) {
        int body = start + 2;
        if (body >= sql.length()) {
            return false;
        }
        char c = sql.charAt(body);
        return c == '!' || (c == 'M' && body + 1 < sql.length() && sql.charAt(body + 1) == '!');
    }

    private static boolean isShowAtAt(String sql, int from) {
        if (!sql.regionMatches(true, from, SHOW, 0, SHOW.length())) {
            return false;
        }
        int i = from + SHOW.length();
        int len = sql.length();
        while (i < len && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (!sql.regionMatches(true, i, AT_AT, 0, AT_AT.length())) {
            return false;
        }
        i += AT_AT.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '#' || (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-')) {
                return false;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                if (isExecutableComment(sql, i)) {
                    return false;
                }
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
            } else if (c == ';') {
                return isTrailingNoiseOnly(sql, i + 1);
            } else {
                i++;
            }
        }
        return true;
    }
}
