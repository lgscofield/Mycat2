package io.mycat.calcite;

import com.alibaba.druid.sql.ast.SQLStatement;
import io.mycat.DrdsSqlWithParams;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * I-DEBT-31 + I-DEBT-32 回归锁：Mycat 校验期已知动态参 {@code ?} 的类型，
 * 上游 Calcite "永不 cast 动态参" 的约定在 Mycat 不成立，跨族比较 / 跨族函数入参
 * 应被就地包 CAST 后通过校验，而非抛 {@code Cannot apply ...}。
 *
 * <p>走 validate 脚手架（同 {@link CalciteValidatorGoldenTest#render}：preParse → getSqlValidator
 * → visit → validate），不依赖活体 8066，秒级红/绿。
 */
public class DynamicParamCoercionTest {

    private static final String DEFAULT_SCHEMA = "mycat";

    private static final CalciteCatalogReader CATALOG_READER =
            DrdsRunnerHelper.newCalciteCatalogReader(rootSchemaWithFixtureTables());

    private static org.apache.calcite.schema.SchemaPlus rootSchemaWithFixtureTables() {
        org.apache.calcite.schema.SchemaPlus root = CalciteSchema.createRootSchema(false).plus();
        final Map<String, Table> tables = new HashMap<>();
        tables.put("tbin", fixtureTable(tbinRowType()));
        root.add(DEFAULT_SCHEMA, new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
                return tables;
            }
        });
        return root;
    }

    /** tbin(id INT NOT NULL, blobcol VARBINARY(255)) */
    private static RelDataType tbinRowType() {
        RelDataTypeFactory.Builder builder = MycatCalciteSupport.TypeFactory.builder();
        builder.add("id", MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER));
        builder.add("blobcol", MycatCalciteSupport.TypeFactory.createTypeWithNullability(
                MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.VARBINARY, 255), true));
        return builder.build();
    }

    private static Table fixtureTable(final RelDataType rowType) {
        return new AbstractTable() {
            @Override
            public RelDataType getRowType(RelDataTypeFactory typeFactory) {
                return rowType;
            }
        };
    }

    /** 校验给定 SQL，返回结果行类型；校验期任何 CalciteException 原样抛出（供 assertThrows / assertDoesNotThrow）。 */
    private static RelDataType validateOk(String sql) {
        DrdsSqlWithParams drdsSql = DrdsRunnerHelper.preParse(sql, DEFAULT_SCHEMA);
        SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, CATALOG_READER);
        SQLStatement stmt = drdsSql.getParameterizedStatement();
        MycatCalciteMySqlNodeVisitor visitor = new MycatCalciteMySqlNodeVisitor();
        stmt.accept(visitor);
        SqlNode validated = validator.validate(visitor.getSqlNode());
        return validator.getValidatedNodeType(validated);
    }

    @Test
    public void dayOfWeekOnStringLiteralValidates() {
        // '2018-01-01' 经 preParse 参数化为 ?(VARCHAR)，DAYOFWEEK 目标 DATETIME，
        // 收口后 coerceOperandType（I-DEBT-32 经 builtinFunctionCoercion 走到此闸门）包 CAST→族门通过。
        Assertions.assertDoesNotThrow(() -> validateOk("SELECT DAYOFWEEK('2018-01-01')"));
    }

    @Test
    public void varBinaryEqualsStringLiteralCoerces() {
        // blobcol(VARBINARY) = '张三' → '张三' 参数化为 ?(VARCHAR)，
        // 收口后 coerceOperandType（I-DEBT-31 经 binaryComparisonCoercion 走到此闸门）包 CAST(? AS VARBINARY)→比较通过。
        Assertions.assertDoesNotThrow(
                () -> validateOk("SELECT id FROM tbin WHERE blobcol = '张三'"));
    }
}
