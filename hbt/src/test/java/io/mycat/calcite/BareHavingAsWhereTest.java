package io.mycat.calcite;

import com.alibaba.druid.sql.ast.SQLStatement;
import io.mycat.DrdsSqlCompiler;
import io.mycat.DrdsSqlWithParams;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * I-DEBT-85 回归锁：无 GROUP BY 且无聚合的 HAVING，MySQL/MariaDB 实测按 WHERE 逐行处理
 * （ ONLY_FULL_GROUP_BY 开启也放行、多行返回）；HAVING 引用不在 select list 的列则报 1054
 * "Unknown column 'X' in 'having clause'"（宽松会话也拒）。
 *
 * <p>修复前 Mycat 两层崩：默认会话 AggChecker 误当聚合查询抛 notGroupExpr；宽松会话放行后
 * HAVING 裸列未穿过聚合投影，MycatFilter.toMycatCalc 注册输入越界（IndexOutOfBoundsException）。
 * 正解=validator 早期把该形 HAVING 归一为 WHERE（别名展开），不可解析引用报 1054 风格错误。
 *
 * <p>脚手架同 {@link OnlyFullGroupBySqlModeTest}：preParse → getSqlValidator → visit → validate
 * （+convert），不依赖活体 8066。
 */
public class BareHavingAsWhereTest {

    private static final String DEFAULT_SCHEMA = "mycat";

    private static final CalciteCatalogReader CATALOG_READER =
            DrdsRunnerHelper.newCalciteCatalogReader(rootSchemaWithFixtureTables());

    /** Connector/J getTables(typeList) 完整形：CASE 别名 + 裸 HAVING IN + ORDER BY 底层列。 */
    private static final String CONNECTOR_J_SHAPE =
            "SELECT TABLE_SCHEMA AS TABLE_CAT, TABLE_NAME,"
                    + " CASE WHEN TABLE_TYPE='BASE TABLE' THEN 'TABLE' ELSE TABLE_TYPE END AS TABLE_TYPE,"
                    + " TABLE_COMMENT AS REMARKS FROM tgui"
                    + " WHERE TABLE_NAME LIKE '%' HAVING TABLE_TYPE IN ('TABLE','VIEW',NULL,NULL,NULL)"
                    + " ORDER BY TABLE_TYPE, TABLE_SCHEMA, TABLE_NAME";

    private static org.apache.calcite.schema.SchemaPlus rootSchemaWithFixtureTables() {
        org.apache.calcite.schema.SchemaPlus root = CalciteSchema.createRootSchema(false).plus();
        final Map<String, Table> tables = new HashMap<>();
        tables.put("tgui", fixtureTable(guiRowType()));
        root.add(DEFAULT_SCHEMA, new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
                return tables;
            }
        });
        return root;
    }

    private static RelDataType guiRowType() {
        RelDataTypeFactory factory = MycatCalciteSupport.TypeFactory;
        RelDataTypeFactory.Builder builder = factory.builder();
        builder.add("TABLE_SCHEMA", factory.createSqlType(SqlTypeName.VARCHAR, 64));
        builder.add("TABLE_NAME", factory.createSqlType(SqlTypeName.VARCHAR, 64));
        builder.add("TABLE_TYPE", factory.createSqlType(SqlTypeName.VARCHAR, 64));
        builder.add("TABLE_COMMENT", factory.createSqlType(SqlTypeName.VARCHAR, 255));
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

    private static RelRoot validateAndConvert(String sql) {
        DrdsSqlWithParams drdsSql = DrdsRunnerHelper.preParse(sql, DEFAULT_SCHEMA);
        SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, CATALOG_READER);
        SQLStatement stmt = drdsSql.getParameterizedStatement();
        MycatCalciteMySqlNodeVisitor visitor = new MycatCalciteMySqlNodeVisitor();
        stmt.accept(visitor);
        SqlNode sqlNode = visitor.getSqlNode();
        SqlNode validated = validator.validate(sqlNode);
        SqlToRelConverter converter = new SqlToRelConverter(
                (rowType, queryString, schemaPath, viewPath) -> {
                    throw new UnsupportedOperationException("no views in this harness");
                },
                validator, CATALOG_READER, DrdsSqlCompiler.newCluster(),
                MycatCalciteSupport.config.getConvertletTable(),
                MycatCalciteSupport.sqlToRelConverterConfig);
        return converter.convertQuery(validated, false, true);
    }

    @AfterEach
    public void resetSessionSqlMode() {
        SessionSqlMode.reset();
    }

    /** ① Connector/J 完整形：严格默认会话须放行且不再产生聚合计划（MySQL 实测逐行 WHERE 语义）。 */
    @Test
    public void connectorJShapePassesStrictDefault() {
        RelRoot root = Assertions.assertDoesNotThrow(() -> validateAndConvert(CONNECTOR_J_SHAPE),
                "裸 HAVING 无 GROUP BY 应按 WHERE 归一放行（修复前：notGroupExpr TABLE_SCHEMA）");
        Assertions.assertFalse(RelOptUtil.toString(root.rel).contains("Aggregate"),
                "归一后查询非聚合，计划不得含 Aggregate：" + RelOptUtil.toString(root.rel));
    }

    /** ② HAVING 引 select 裸列名：同样归一放行。 */
    @Test
    public void havingOnSelectColumnNamePasses() {
        Assertions.assertDoesNotThrow(() -> validateAndConvert(
                "SELECT TABLE_NAME FROM tgui HAVING TABLE_NAME LIKE 'db'"));
    }

    /** ③ HAVING 引不在 select list 的底层列：MySQL 报 1054（默认会话），不得报 notGroupExpr。 */
    @Test
    public void unresolvableHavingColumnRejectedAsUnknownColumn() {
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> validateAndConvert("SELECT TABLE_NAME FROM tgui HAVING TABLE_TYPE IN ('TABLE','VIEW')"));
        Assertions.assertTrue(hasMessage(ex, "Unknown column 'TABLE_TYPE' in 'having clause'"),
                "应按 MySQL 1054 报 Unknown column in having clause，实际：" + ex);
    }

    /** ④ 同形 lax 会话：MySQL 也报 1054——不得放行（修复前 lax 放行后实现层越界崩）。 */
    @Test
    public void unresolvableHavingColumnLaxStillRejected() {
        SessionSqlMode.setOnlyFullGroupBy(false);
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> validateAndConvert("SELECT TABLE_NAME FROM tgui HAVING TABLE_TYPE IN ('TABLE','VIEW')"));
        Assertions.assertTrue(hasMessage(ex, "Unknown column 'TABLE_TYPE' in 'having clause'"),
                "lax 会话同形也应按 1054 拒绝，实际：" + ex);
    }

    /** ⑤ 守卫：HAVING 含聚合 ⇒ 不改写，聚合路径保持（单组语义）。 */
    @Test
    public void aggregateHavingUnchanged() {
        Assertions.assertDoesNotThrow(() -> validateAndConvert(
                "SELECT COUNT(*) FROM tgui HAVING COUNT(*) > 0"));
    }

    /** ⑥ 守卫：聚合查询（HAVING 含聚合）里的裸 select 列，严格会话仍拒——归一不得误伤。 */
    @Test
    public void aggregateQueryBareColumnStillRejected() {
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> validateAndConvert("SELECT TABLE_NAME FROM tgui HAVING COUNT(*) > 0"));
        Assertions.assertTrue(hasMessage(ex, "not being grouped"),
                "聚合查询的裸列仍应被 ONLY_FULL_GROUP_BY 拒，实际：" + ex);
    }

    /** ⑦ 守卫：有 GROUP BY 的 HAVING 不改写。 */
    @Test
    public void groupByHavingUnchanged() {
        Assertions.assertDoesNotThrow(() -> validateAndConvert(
                "SELECT TABLE_TYPE, COUNT(*) FROM tgui GROUP BY TABLE_TYPE HAVING COUNT(*) > 0"));
    }

    /** ⑧ SELECT *：全部 FROM 列都在 select list，HAVING 引底层列合法（MySQL 放行）。 */
    @Test
    public void starSelectHavingPasses() {
        Assertions.assertDoesNotThrow(() -> validateAndConvert(
                "SELECT * FROM tgui HAVING TABLE_TYPE = 'TABLE'"));
    }

    /** ⑨ WHERE 与 HAVING 并存：归一后 AND 合并，正常校验。 */
    @Test
    public void whereAndHavingMerged() {
        Assertions.assertDoesNotThrow(() -> validateAndConvert(
                "SELECT TABLE_NAME FROM tgui WHERE TABLE_SCHEMA='mysql' HAVING TABLE_NAME LIKE 'db'"));
    }

    private static boolean hasMessage(Throwable t, String needle) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}