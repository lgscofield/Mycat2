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
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowWarningsStatement;
import io.mycat.MycatDataContext;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.prototypeserver.mysql.MySQLResultSet;
import io.mycat.prototypeserver.mysql.PrototypeService;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.SQLRequest;
import io.mycat.Response;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.mycat.prototypeserver.mysql.PrototypeService.getShowWarningsColumns;


/**
 * I-DEBT-69 Blade B：{@code SHOW WARNINGS} / {@code SHOW COUNT(*) WARNINGS} 读面。
 *
 * <p>此前恒返空行集（{@code setRows(Collections.emptyList())}），即客户端看不到任何告警明细。
 * 现在从会话诊断缓冲（Blade A：{@link MycatDataContext#getWarnings()}）出真实行集，
 * 且计数与明细同源。判据来自 2026-10-29 活体真库探针（MySQL 8.0 / 5.7 / MariaDB 10.11 一致）：
 * <ul>
 *   <li>普通形态 = Level/Code/Message 三列（列定义沿用既有 {@code getShowWarningsColumns()}，与真库对齐）。</li>
 *   <li>{@code COUNT(*)} 形态 = <b>单列单行</b>，活体列头为 {@code @@session.warning_count}，
 *       故不能沿用三列定义；值 = 条件总数。</li>
 *   <li>列表受 {@code max_error_count} 截断，<b>计数不受截断</b>。</li>
 *   <li>读面<b>只读不清</b>：真库 {@code SHOW WARNINGS} 后 {@code @@warning_count} 仍为原值，
 *       清空只发生在每条语句入口（见 {@code MycatdbCommand#execute}）。</li>
 * </ul>
 */
public class ShowWarningsSQLHandler extends AbstractSQLHandler<MySqlShowWarningsStatement> {

    /** 负数哨兵：调用方拿不到 max_error_count 时的保守退化（不截断，宁可多列也不臆造截断点）。 */
    static final int NO_TRUNCATION = -1;

    @Override
    protected Future<Void> onExecute(SQLRequest<MySqlShowWarningsStatement> request, MycatDataContext dataContext, Response response) {
        MySqlShowWarningsStatement statement = request.getAst();
        List<WarningMessage> warnings = dataContext.getWarnings();
        int maxErrorCount = resolveMaxErrorCount(dataContext);
        List<Object[]> rows = buildWarningRows(statement, warnings, maxErrorCount);

        MySQLResultSet mySQLResultSet;
        if (statement.isCount()) {
            // 活体列头：@@session.warning_count（单列）。类型沿用 Code 列的 INT32 口径，
            // 精确列元数据待 Docker infra 恢复后用 --column-type-info 对拍复核（Blade D 差分项）。
            ColumnDefPacket countColumn = PrototypeService.createColumn("def", "", "", "",
                    "@@session.warning_count", "", 63, 4, 3, 0x00a1, (byte) 0);
            mySQLResultSet = MySQLResultSet.create(Collections.singletonList(countColumn));
        } else {
            List<ColumnDefPacket> columnDefPacketList = getShowWarningsColumns();
            mySQLResultSet = MySQLResultSet.create(columnDefPacketList);
        }
        mySQLResultSet.setRows(rows);
        return response.sendResultSet(mySQLResultSet.build());
    }

    /**
     * 纯函数：从诊断缓冲构造 SHOW WARNINGS 行集（无副作用，可离线单测）。
     *
     * @param statement     已解析的 SHOW WARNINGS 语法树（决定 COUNT(*) 形态与 LIMIT）
     * @param warnings      本语句的诊断条件，按产生顺序
     * @param maxErrorCount 会话 {@code max_error_count}；{@link #NO_TRUNCATION} 表示不截断
     */
    static List<Object[]> buildWarningRows(MySqlShowWarningsStatement statement,
            List<WarningMessage> warnings, int maxErrorCount) {
        if (statement.isCount()) {
            // 活体：计数报条件总数，与列表截断解耦
            return Collections.singletonList(new Object[]{warnings.size()});
        }
        int cap = maxErrorCount < 0 ? warnings.size() : Math.min(maxErrorCount, warnings.size());
        int offset = 0;
        SQLLimit limit = statement.getLimit();
        if (limit != null) {
            offset = intValue(limit.getOffset(), 0);
            // 活体/官方语义：显式 LIMIT n 仍受 max_error_count 双重约束，取较小者
            int rowCount = intValue(limit.getRowCount(), Integer.MAX_VALUE);
            cap = Math.min(rowCount, cap);
        }
        List<Object[]> rows = new ArrayList<>();
        for (int i = offset; i < warnings.size() && rows.size() < cap; i++) {
            WarningMessage warning = warnings.get(i);
            rows.add(new Object[]{warning.getLevel(), warning.getCode(), warning.getMessage()});
        }
        return rows;
    }

    /**
     * 读会话 {@code max_error_count}。取不到（变量尚未接入 persona 默认值·Blade C 范畴）时
     * 返回 {@link #NO_TRUNCATION}，不臆造 64/1024 这类版本相关默认值。
     */
    static int resolveMaxErrorCount(MycatDataContext dataContext) {
        Object value;
        try {
            value = dataContext.getVariable(false, "@@session.max_error_count");
        } catch (Throwable t) {
            return NO_TRUNCATION;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return NO_TRUNCATION;
            }
        }
        return NO_TRUNCATION;
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
