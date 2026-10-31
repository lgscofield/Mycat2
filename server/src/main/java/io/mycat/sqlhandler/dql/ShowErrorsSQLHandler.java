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
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowErrorsStatement;
import io.mycat.MycatDataContext;
import io.mycat.Response;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.prototypeserver.mysql.MySQLResultSet;
import io.mycat.prototypeserver.mysql.PrototypeService;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.SQLRequest;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * I-DEBT-69 Blade D 余项：{@code SHOW ERRORS} / {@code SHOW COUNT(*) ERRORS} 读面。
 *
 * <p>与 {@link ShowWarningsSQLHandler} 对称，但多一层 {@code Level=="Error"} 过滤（真库取证
 * 2026-10-31 @ MySQL 8.0.46 / MariaDB 10.11.19：{@code SELECT 1/0} 后 {@code SHOW ERRORS} 为空、
 * {@code @@error_count}=0；产 Error 的语句后 {@code SHOW ERRORS} 才出 {@code Error/Code/Message}）。
 * <ul>
 *   <li>普通形态 = Level/Code/Message 三列（沿用既有 {@code getShowErrorsColumns()}，与真库对齐）。</li>
 *   <li>{@code COUNT(*)} 形态 = 单列单行，活体列头 {@code @@session.error_count}，值 = Error 级条件数。</li>
 *   <li>先按 Level 过滤，再受 {@code max_error_count} 截断（{@code LIMIT o,n} 在 Error 子集上切片），
 *       与 SHOW WARNINGS 的“不过滤”相反。</li>
 *   <li>读面只读不清（入口已由 {@code MycatdbCommand#isDiagnosticReadStatement} 放过 SHOW ERRORS）。</li>
 * </ul>
 */
public class ShowErrorsSQLHandler extends AbstractSQLHandler<MySqlShowErrorsStatement> {

    @Override
    protected Future<Void> onExecute(SQLRequest<MySqlShowErrorsStatement> request, MycatDataContext dataContext, Response response) {
        MySqlShowErrorsStatement statement = request.getAst();
        List<WarningMessage> warnings = dataContext.getWarnings();
        int maxErrorCount = ShowWarningsSQLHandler.resolveMaxErrorCount(dataContext);
        List<Object[]> rows = buildErrorRows(statement, warnings, maxErrorCount);

        MySQLResultSet mySQLResultSet;
        if (statement.isCount()) {
            // 活体列头：@@session.error_count（单列），类型沿用 Code 列的 INT32 口径。
            ColumnDefPacket countColumn = PrototypeService.createColumn("def", "", "", "",
                    "@@session.error_count", "", 63, 4, 3, 0x00a1, (byte) 0);
            mySQLResultSet = MySQLResultSet.create(Collections.singletonList(countColumn));
        } else {
            mySQLResultSet = MySQLResultSet.create(PrototypeService.getShowErrorsColumns());
        }
        mySQLResultSet.setRows(rows);
        return response.sendResultSet(mySQLResultSet.build());
    }

    /**
     * 纯函数：先过滤出 Error 级条件，再按 SHOW ERRORS 语义构造行集（无副作用，可离线单测）。
     *
     * @param statement     已解析的 SHOW ERRORS 语法树（决定 COUNT(*) 形态与 LIMIT）
     * @param warnings      本语句的全部诊断条件，按产生顺序
     * @param maxErrorCount 会话 {@code max_error_count}；负数表示不截断
     */
    static List<Object[]> buildErrorRows(MySqlShowErrorsStatement statement,
            List<WarningMessage> warnings, int maxErrorCount) {
        // 真库取证：SHOW ERRORS 只保留 Error 级，Warning/Note 全丢弃（与 SHOW WARNINGS 不过滤相反）。
        List<WarningMessage> errors = new ArrayList<>();
        for (WarningMessage w : warnings) {
            if ("Error".equals(w.getLevel())) {
                errors.add(w);
            }
        }
        if (statement.isCount()) {
            // 计数报 Error 级总数，与列表截断解耦
            return Collections.singletonList(new Object[]{errors.size()});
        }
        int cap = maxErrorCount < 0 ? errors.size() : Math.min(maxErrorCount, errors.size());
        int offset = 0;
        SQLLimit limit = statement.getLimit();
        if (limit != null) {
            offset = intValue(limit.getOffset(), 0);
            // 活体/官方语义：显式 LIMIT n 仍受 max_error_count 双重约束，取较小者
            int rowCount = intValue(limit.getRowCount(), Integer.MAX_VALUE);
            cap = Math.min(rowCount, cap);
        }
        List<Object[]> rows = new ArrayList<>();
        for (int i = offset; i < errors.size() && rows.size() < cap; i++) {
            WarningMessage e = errors.get(i);
            rows.add(new Object[]{e.getLevel(), e.getCode(), e.getMessage()});
        }
        return rows;
    }

    private static int intValue(SQLExpr expr, int defaultValue) {
        if (expr == null) {
            return defaultValue;
        }
        if (expr instanceof SQLIntegerExpr) {
            return ((SQLIntegerExpr) expr).getNumber().intValue();
        }
        try {
            return Integer.parseInt(expr.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
