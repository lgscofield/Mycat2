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
import org.apache.calcite.util.Static;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * D2b 第一刀回归锁：分组校验的 ONLY_FULL_GROUP_BY 严格性跟随会话 sql_mode。
 *
 * <p>Calcite 的 {@code AggChecker} 历史上对非严格 GROUP BY（裸列不在 GROUP BY 中）恒抛
 * {@code notGroupExpr}，与 MySQL 按会话 {@code sql_mode} 动态开关该模式的语义错位。按生态原则
 * 「代理不应比后端严」，Mycat 让 validator 依会话 sql_mode 决定是否放宽。
 *
 * <p>走 validate-only 脚手架（同 {@link DynamicParamCoercionTest}：preParse → getSqlValidator
 * → visit → validate），不依赖活体 8066，秒级红/绿。会话态用 {@link SessionSqlMode} 模拟。
 */
public class OnlyFullGroupBySqlModeTest {

    private static final String DEFAULT_SCHEMA = "mycat";

    private static final CalciteCatalogReader CATALOG_READER =
            DrdsRunnerHelper.newCalciteCatalogReader(rootSchemaWithFixtureTables());

    /** mysqldump tablespaces Q1 的形状：裸列 + 聚合 + GROUP BY（EXTRA 类比 a 不在 GROUP BY）。 */
    private static final String NON_STRICT_GROUP_BY =
            "select a, sum(b) from tgb group by c";

    private static org.apache.calcite.schema.SchemaPlus rootSchemaWithFixtureTables() {
        org.apache.calcite.schema.SchemaPlus root = CalciteSchema.createRootSchema(false).plus();
        final Map<String, Table> tables = new HashMap<>();
        tables.put("tgb", fixtureTable(tgbRowType()));
        root.add(DEFAULT_SCHEMA, new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
                return tables;
            }
        });
        return root;
    }

    /** tgb(a INT, b INT, c INT) */
    private static RelDataType tgbRowType() {
        RelDataTypeFactory factory = MycatCalciteSupport.TypeFactory;
        RelDataTypeFactory.Builder builder = factory.builder();
        builder.add("a", factory.createSqlType(SqlTypeName.INTEGER));
        builder.add("b", factory.createSqlType(SqlTypeName.INTEGER));
        builder.add("c", factory.createSqlType(SqlTypeName.INTEGER));
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

    /** validate-only：preParse → getSqlValidator → visit → validate，异常原样上抛。 */
    private static SqlNode validate(String sql) {
        DrdsSqlWithParams drdsSql = DrdsRunnerHelper.preParse(sql, DEFAULT_SCHEMA);
        SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, CATALOG_READER);
        SQLStatement stmt = drdsSql.getParameterizedStatement();
        MycatCalciteMySqlNodeVisitor visitor = new MycatCalciteMySqlNodeVisitor();
        stmt.accept(visitor);
        return validator.validate(visitor.getSqlNode());
    }

    @AfterEach
    public void resetSessionSqlMode() {
        SessionSqlMode.reset();
    }

    /** 缺省（未穿会话态）=严格：非严格 GROUP BY 应抛 notGroupExpr，锁住既有行为与 golden 基线。 */
    @Test
    public void defaultStrictRejectsNonStrictGroupBy() {
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> validate(NON_STRICT_GROUP_BY));
        Assertions.assertTrue(containsGroupError(ex),
                "应因 ONLY_FULL_GROUP_BY 严格校验抛 notGroupExpr，实际：" + ex);
    }

    /** 宽松会话（关闭 ONLY_FULL_GROUP_BY）：同一查询应放行——这正是被误拒的用户遗留查询面。 */
    @Test
    public void lenientSessionAcceptsNonStrictGroupBy() {
        SessionSqlMode.setOnlyFullGroupBy(false);
        Assertions.assertDoesNotThrow(() -> validate(NON_STRICT_GROUP_BY));
    }

    /** 放宽只作用于 GROUP BY，不碰 SELECT DISTINCT 校验：DISTINCT 违规在宽松会话仍应抛。 */
    @Test
    public void lenientSessionStillChecksSelectDistinct() {
        SessionSqlMode.setOnlyFullGroupBy(false);
        // order by 一个未出现在 distinct select list 中的聚合，走 distinct 分支，不受本旋钮放宽。
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> validate("select distinct a from tgb order by sum(b)"));
        Assertions.assertNotNull(ex);
    }

    /**
     * 生产入口的派生腿：会话 {@code sql_mode=""} 经 {@link SqlModeTokens#containsOnlyFullGroupBy}
     * 派生为 false（不含该 token）→ 门控放行非严格 GROUP BY。把 Task 3 的 token 判定缝进门控，
     * 覆盖 Review Focus #1「空串须宽松、放行非严格 GROUP BY」——mysqldump 的 {@code SET @@SQL_MODE=''}
     * 正是空串形态。
     */
    @Test
    public void emptySessionModeStringIsLenient() {
        String sessionSqlMode = "";
        Assertions.assertFalse(SqlModeTokens.containsOnlyFullGroupBy(sessionSqlMode),
                "空串会话应派生为宽松（不含 ONLY_FULL_GROUP_BY）");
        SessionSqlMode.setOnlyFullGroupBy(SqlModeTokens.containsOnlyFullGroupBy(sessionSqlMode));
        Assertions.assertDoesNotThrow(() -> validate(NON_STRICT_GROUP_BY));
    }

    /**
     * S2 串味钉测（门控级）：同一 SQL 文本，宽松会话放行、复位为缺省严格后拒绝——证明接受性
     * 由入口逐次经 {@link SessionSqlMode} 门控决定，非编进 validator/config 的常驻态。对应
     * Review Focus #3：全局 plan cache 保持会话无关，接受性放查询入口。
     */
    @Test
    public void crossSessionRejectionNotCached() {
        SessionSqlMode.setOnlyFullGroupBy(false);
        Assertions.assertDoesNotThrow(() -> validate(NON_STRICT_GROUP_BY),
                "宽松会话应放行非严格 GROUP BY");
        SessionSqlMode.reset();
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> validate(NON_STRICT_GROUP_BY));
        Assertions.assertTrue(containsGroupError(ex),
                "复位为缺省严格后同一查询应重新被拒，实际：" + ex);
    }

    private static boolean containsGroupError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && (m.contains("not being grouped")
                    || m.contains(Static.RESOURCE.notGroupExpr("x").toString()))) {
                return true;
            }
        }
        return false;
    }
}
