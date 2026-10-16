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
package io.mycat.sqlhandler.dql;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowStatusStatement;
import io.mycat.MycatDataContext;
import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.prototypeserver.mysql.MySQLResultSet;
import io.mycat.prototypeserver.mysql.PrototypeService;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.SQLRequest;
import io.mycat.Response;
import io.vertx.core.Future;

import java.util.*;
import java.util.regex.Pattern;

import static io.mycat.prototypeserver.mysql.PrototypeService.getShowStatusColumns;


public class ShowStatusSQLHandler extends AbstractSQLHandler<MySqlShowStatusStatement> {

    @Override
    protected Future<Void> onExecute(SQLRequest<MySqlShowStatusStatement> request, MycatDataContext dataContext, Response response) {
        List<ColumnDefPacket> columnDefPacketList = getShowStatusColumns();
        MySQLResultSet mySQLResultSet = MySQLResultSet.create(columnDefPacketList);
        // I-DEBT-47：回报「客户端↔Mycat」前端 TLS 会话的加密态，而非恒空。仅排放能据会话事实支撑的
        // Ssl_* 状态变量（不回填空背的全部 status 变量——那是另一笔更大的债）。非 TLS 会话下这些行
        // 仍存在但值为空串，与 MySQL 语义一致（据 SHOW SESSION STATUS 探针判断会话是否加密的客户端/监控因此不再误判明文）。
        Map<String, String> tlsState = sslSessionStatus(dataContext);
        MySqlShowStatusStatement ast = request.getAst();
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : tlsState.entrySet()) {
            if (matchesLike(e.getKey(), ast.getLike())) {
                rows.add(new Object[]{e.getKey(), e.getValue()});
            }
        }
        mySQLResultSet.setRows(rows);
        return response.sendResultSet(mySQLResultSet.build());
    }

    /** 组装当前会话的 Ssl_* 状态变量（有序：Ssl_cipher 先、Ssl_version 后），值取自前端 TLS 回填的会话专用字段。 */
    private static Map<String, String> sslSessionStatus(MycatDataContext dataContext) {
        // I-DEBT-47：TLS 态取自会话字段（getSslCipher/getSslVersion），而非每语句被 MycatdbCommand.execute
        // 重置的 processStateMap——后者会在 SHOW STATUS 执行前被擦成空图，导致端到端恒空。
        String cipher = Objects.toString(dataContext.getSslCipher(), "");
        String version = Objects.toString(dataContext.getSslVersion(), "");
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("Ssl_cipher", cipher);
        ordered.put("Ssl_version", version);
        return ordered;
    }

    /**
     * 判断变量名是否匹配 {@code SHOW ... LIKE} 模式。无 LIKE 视为全匹配。
     * MySQL LIKE 语义：{@code %} 匹配任意串、{@code _} 匹配单字符、反斜杠转义，且默认对状态变量名大小写不敏感。
     */
    static boolean matchesLike(String variableName, SQLExpr like) {
        if (like == null) {
            return true;
        }
        String pattern = likePattern(like);
        if (pattern == null) {
            return true;
        }
        return likeToRegex(pattern).matcher(variableName).matches();
    }

    /** 从 LIKE 表达式抽模式串：优先 SQLCharExpr 的字面量，否则剥引号。 */
    private static String likePattern(SQLExpr like) {
        if (like instanceof SQLCharExpr) {
            return ((SQLCharExpr) like).getText();
        }
        String s = like.toString();
        if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** MySQL LIKE 模式编译为大小写不敏感的正则（% → .*，_ → .，其余按字面，反斜杠转义下一字符）。 */
    private static Pattern likeToRegex(String like) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            if (c == '\\' && i + 1 < like.length()) {
                re.append(Pattern.quote(String.valueOf(like.charAt(++i))));
            } else if (c == '%') {
                re.append(".*");
            } else if (c == '_') {
                re.append('.');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private String toNormalSQL(MySqlShowStatusStatement ast) {


        SQLExpr like = ast.getLike();
        SQLExpr where = ast.getWhere();

        String tableName = ast.isSession() ? "session_variables" : "global_variables";

        return generateSimpleSQL(
                Arrays.asList(
                        new String[]{"VARIABLE_NAME", "Variable_name"},
                        new String[]{"VARIABLE_VALUE", "Value"})
                , "information_schema", tableName, Optional.ofNullable(where).map(i -> i.toString()).orElse(null), "Variable_name like " + like).toString();
    }
}
