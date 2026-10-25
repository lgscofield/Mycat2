package io.mycat.util;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLDataType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlTableIndex;
import io.mycat.beans.mycat.MycatRowMetaData;

import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Optional;

public class MycatRowMetaDataImpl implements MycatRowMetaData {
    final List<SQLColumnDefinition> columnInfo;
    final List<MySqlTableIndex> indexList;
    final String tableName;
    final String schemaName;
    final int columnCount;
    private MySqlCreateTableStatement mySqlCreateTableStatement;


    public MycatRowMetaDataImpl(MySqlCreateTableStatement mySqlCreateTableStatement) {
        this.mySqlCreateTableStatement = mySqlCreateTableStatement;

        this.columnInfo = this.mySqlCreateTableStatement.getColumnDefinitions();
        this.indexList = this.mySqlCreateTableStatement.getMysqlIndexes();
        this.tableName = SQLUtils.normalize(this.mySqlCreateTableStatement.getTableName());
        this.schemaName = Optional.ofNullable(this.mySqlCreateTableStatement.getSchema()).map(n -> SQLUtils.normalize(n)).orElse(null);
        this.columnCount = columnInfo.size();
    }

    @Override
    public int getColumnCount() {
        return columnCount;
    }

    @Override
    public boolean isAutoIncrement(int column) {
        return columnInfo.get(column).isAutoIncrement();
    }

    @Override
    public boolean isCaseSensitive(int column) {
        return false;
    }

    @Override
    public boolean isNullable(int column) {
        return !columnInfo.get(column).containsNotNullConstraint();
    }

    @Override
    public boolean isSigned(int column) {
        return true;
    }

    @Override
    public int getColumnDisplaySize(int column) {
        return columnInfo.get(column).getColumnName().length();
    }

    @Override
    public String getColumnName(int column) {
        return columnInfo.get(column).computeAlias();
    }

    @Override
    public String getSchemaName(int column) {
        return schemaName;
    }

    // MYCAT CHANGE (I-DEBT-66 Fix-Meta-2)：DDL 解析腿与 JDBC 腿（convertfromDatabaseMetaData）对齐，
    // 从列定义 SQLDataType 的参数读声明 precision/scale。旧实现硬编码 0：分片表走 schema 配置的
    // createTableSQL 文本时 SimpleColumnInfo 精度标度全丢，CalciteConvertors.getRelDataType 的
    // precision>0 守卫（Fix-Meta）不触发，DECIMAL 列塌回 (19,0) → 跨片 AVG 归并腿伪降 scale
    //（spec §2.8 因果链最后缺口，C11 活库回归红 mycat=3.6667 oracle=3.66666667）。
    @Override
    public int getPrecision(int column) {
        // decimal(20,4)/decimal(10)/varchar(30) 等：首参=precision/长度；无参（datetime、裸 decimal）→ 0（维持现状语义）。
        return typeArgument(column, 0);
    }

    @Override
    public int getScale(int column) {
        return typeArgument(column, 1);
    }

    private int typeArgument(int column, int index) {
        SQLDataType dataType = columnInfo.get(column).getDataType();
        if (dataType == null) {
            return 0;
        }
        List<SQLExpr> arguments = dataType.getArguments();
        if (arguments == null || arguments.size() <= index) {
            return 0;
        }
        SQLExpr arg = arguments.get(index);
        // 仅接受整数字面量参数；非常规表达式保守返 0，不引入噪声。
        return arg instanceof SQLIntegerExpr ? (int) ((SQLIntegerExpr) arg).getNumber().longValue() : 0;
    }

    @Override
    public String getTableName(int column) {
        return tableName;
    }

    @Override
    public int getColumnType(int column) {
        return columnInfo.get(column).jdbcType();
    }

    @Override
    public String getColumnLabel(int column) {
        return columnInfo.get(column).getColumnName();
    }

    @Override
    public ResultSetMetaData metaData() {
        return null;
    }

    @Override
    public boolean isPrimaryKey(int column) {
        return columnInfo.get(column).isPrimaryKey();
    }

    @Override
    public boolean isUniqueKey(int column) {
        return isPrimaryKey(column) || mySqlCreateTableStatement.isUNI(columnInfo.get(column).getColumnName());
    }
}