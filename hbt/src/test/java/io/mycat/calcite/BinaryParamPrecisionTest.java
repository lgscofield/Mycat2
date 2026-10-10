package io.mycat.calcite;

import com.alibaba.druid.sql.ast.SQLStatement;
import io.mycat.DrdsSqlWithParams;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

/**
 * I-DEBT-8 真·残面回归锁：被参数化的二进制字面量（如 {@code X'4D7953514C'}）在动态参类型
 * 解析时须携带<b>实际字节长度</b>作 precision，否则 {@code DrdsRunnerHelper.resolveDynamicParam}
 * 以 {@code createSqlType(SqlTypeName.BINARY)} 落默认 precision=1，codegen 依此发出
 * {@code SqlFunctions.truncate(bytes, 1)}，把 5 字节静默截成首字节（e2e 靶：SqlFunctionTest.testCharFunction）。
 *
 * <p>走 validate 脚手架（同 {@link DynamicParamCoercionTest}：preParse → getSqlValidator → visit → validate），
 * 不依赖活体 8066，秒级红/绿。钉的是<b>类型精度</b>这一根因层，而非线上截断症状。
 */
public class BinaryParamPrecisionTest {

    private static final String DEFAULT_SCHEMA = "mycat";

    private static final CalciteCatalogReader CATALOG_READER =
            DrdsRunnerHelper.newCalciteCatalogReader(rootSchemaWithDefault());

    private static org.apache.calcite.schema.SchemaPlus rootSchemaWithDefault() {
        org.apache.calcite.schema.SchemaPlus root = CalciteSchema.createRootSchema(false).plus();
        final Map<String, Table> tables = Collections.emptyMap();
        root.add(DEFAULT_SCHEMA, new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
                return tables;
            }
        });
        return root;
    }

    /** preParse → validate，返回 SELECT 结果行类型。 */
    private static RelDataType validateRowType(String sql) {
        DrdsSqlWithParams drdsSql = DrdsRunnerHelper.preParse(sql, DEFAULT_SCHEMA);
        SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, CATALOG_READER);
        SQLStatement stmt = drdsSql.getParameterizedStatement();
        MycatCalciteMySqlNodeVisitor visitor = new MycatCalciteMySqlNodeVisitor();
        stmt.accept(visitor);
        SqlNode validated = validator.validate(visitor.getSqlNode());
        return validator.getValidatedNodeType(validated);
    }

    @Test
    public void binaryLiteralParamKeepsFullByteLengthAsPrecision() {
        // X'4D7953514C' = 5 字节 "MySQL"。参数化为 ?0(byte[]) 后，其解析类型的 precision 必须 = 5，
        // 否则下游 codegen truncate 到 1。当前 createSqlType(BINARY) 默认 precision=1 → 本测试 RED。
        RelDataType col = validateRowType("SELECT X'4D7953514C'").getFieldList().get(0).getType();
        Assertions.assertEquals(SqlTypeName.BINARY, col.getSqlTypeName(),
                "二进制字面量参数应解析为 BINARY 族（钉住被截前的前置事实）");
        Assertions.assertEquals(5, col.getPrecision(),
                "BINARY 动态参 precision 必须等于绑定字节长度 5，否则 codegen truncate(...,1) 截断值");
    }
}
