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
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowCharacterSetStatement;
import io.mycat.MycatDataContext;
import io.mycat.Response;
import io.mycat.calcite.DrdsRunnerHelper;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.SQLRequest;
import io.vertx.core.Future;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;


public class ShowCharacterSetSQLHandler extends AbstractSQLHandler<MySqlShowCharacterSetStatement> {

    @Override
    protected Future<Void> onExecute(SQLRequest<MySqlShowCharacterSetStatement> request, MycatDataContext dataContext, Response response) {
        String sql = toNormalSQL(request.getAst());
        return DrdsRunnerHelper.runOnDrds(dataContext, DrdsRunnerHelper.preParse(sql, dataContext.getDefaultSchema()), response);
    }

    private String toNormalSQL(MySqlShowCharacterSetStatement ast) {
        SQLExpr where = ast.getWhere();
        SQLExpr pattern = ast.getPattern();
        List<String[]> projects = Arrays.asList(
                new String[]{"CHARACTER_SET_NAME", "Charset"},//utf8
                new String[]{"DESCRIPTION", "Description"},//UTF-8 Unicode
                // I-DEBT-29-EXT-CONN：真库（MySQL 5.7/8.x、MariaDB 全系）SHOW CHARACTER SET 第三列表头均为
                // "Default collation"；原别名 "Default_collection" 属自造表头（i_s 表列名 DEFAULT_COLLATE_NAME
                // 不等于 SHOW 表头），按列名读取的客户端会取空。带空格别名按 ShowCollationSQLHandler 先例加反引号。
                // 注：dotnet Connector/NET 的 DBNull 硬崩不在此列，而在 SHOW COLLATION 的 Id 列（见 I-DEBT-70）。
                new String[]{"DEFAULT_COLLATE_NAME", "`Default collation`"},//utf8mb4_0900_ai_ci
                new String[]{"MAXLEN", "Maxlen"});//4
        return generateSimpleSQL(projects,
                "information_schema", "CHARACTER_SETS",
                Optional.ofNullable(where).map(i -> i.toString()).orElse(null),
                Optional.ofNullable(pattern).map(i -> "CHARACTER_SET_NAME like " + i).orElse(null))
                .toString();
    }
}
