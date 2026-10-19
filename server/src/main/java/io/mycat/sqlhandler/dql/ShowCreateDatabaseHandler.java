package io.mycat.sqlhandler.dql;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLCreateDatabaseStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowCreateDatabaseStatement;
import io.mycat.MycatDataContext;
import io.mycat.Response;
import io.mycat.prototypeserver.mysql.MySQLResultSet;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.SQLRequest;
import io.vertx.core.Future;

import java.util.ArrayList;

import static io.mycat.prototypeserver.mysql.PrototypeService.getShowCreateDatabaseColumns;

public class ShowCreateDatabaseHandler extends AbstractSQLHandler<MySqlShowCreateDatabaseStatement> {
    @Override
    protected Future<Void> onExecute(SQLRequest<MySqlShowCreateDatabaseStatement> request, MycatDataContext dataContext, Response response) {
        // I-DEBT-58（Design A）：委托 prototype 单一实现（透传读回后端真实库定义 + 裸兜底），
        // 不再自拼裸 CREATE DATABASE 丢库级字符集。handleSql 对该类型恒命中，orElseGet 仅防御兜底。
        MySQLResultSet mySQLResultSet = getPrototypeService().handleSql(request.getAst()).orElseGet(() -> {
            MySQLResultSet fallback = MySQLResultSet.create(getShowCreateDatabaseColumns());
            String database = SQLUtils.normalize(request.getAst().getDatabase().toString());
            SQLCreateDatabaseStatement sqlCreateDatabaseStatement = new SQLCreateDatabaseStatement();
            sqlCreateDatabaseStatement.setDatabase(database);
            ArrayList<Object[]> objects = new ArrayList<>();
            objects.add(new Object[]{database, sqlCreateDatabaseStatement.toString()});
            fallback.setRows(objects);
            return fallback;
        });
        return response.sendResultSet(mySQLResultSet.build());
    }
}
