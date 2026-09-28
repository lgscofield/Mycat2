/*
 * MYCAT ADDITION
 * This class does not exist in Calcite 1.42.0.
 * Purpose: MySQL exact numeric literal handling - always treats numerics as DECIMAL
 * Reason for org.apache.calcite package: Extends SqlNumericLiteral, must be in sql package
 */
package org.apache.calcite.sql;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;

import java.math.BigDecimal;

public class MysqlExactNumericLiteral extends SqlNumericLiteral {
    protected MysqlExactNumericLiteral(BigDecimal value,SqlParserPos pos) {
        super(value, value.precision(), value.scale(), true, pos);
    }

    public static MysqlExactNumericLiteral create(BigDecimal value,SqlParserPos pos){
        return new MysqlExactNumericLiteral(value, pos);
    }

    @Override
    public SqlTypeName getTypeName() {
        return SqlTypeName.DECIMAL;
    }

    @Override
    public RelDataType createSqlType(RelDataTypeFactory typeFactory) {
        return typeFactory.createSqlType(SqlTypeName.DECIMAL);
    }
}