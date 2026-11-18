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
 * <p>只做前缀判定，不引解析器（成本与范围）。口径与 {@code HintHandler} 对齐：
 * hint 须是整条语句（hint-only），且接受 {@code SqlHints.unWrapperHint} 认可的加号/感叹号标记变体
 * ——{@code HintBuilder.build()} 实际发出的正是带标记、带尾分号的文本。
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
                if (isMycatHintBody(sql.substring(i + 2, bodyEnd))) {
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
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
            } else {
                return false;
            }
        }
        return true;
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
        return sql.regionMatches(true, i, AT_AT, 0, AT_AT.length());
    }
}