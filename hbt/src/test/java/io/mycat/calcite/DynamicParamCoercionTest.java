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
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
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

    /** 校验给定 SQL，返回校验后（含 coercion 就地改写）的 SqlNode 树根。 */
    private static SqlNode validateNode(String sql) {
        DrdsSqlWithParams drdsSql = DrdsRunnerHelper.preParse(sql, DEFAULT_SCHEMA);
        SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, CATALOG_READER);
        SQLStatement stmt = drdsSql.getParameterizedStatement();
        MycatCalciteMySqlNodeVisitor visitor = new MycatCalciteMySqlNodeVisitor();
        stmt.accept(visitor);
        return validator.validate(visitor.getSqlNode());
    }

    /** 递归查找树中是否存在 “CAST(x)” 且其被 cast 实参为动态参 ? 的节点。 */
    private static boolean hasCastOverDynamicParam(SqlNode node) {
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            if (call.getKind() == SqlKind.CAST && call.getOperandList().size() > 0
                    && call.getOperandList().get(0) instanceof SqlDynamicParam) {
                return true;
            }
        }
        if (node instanceof SqlCall) {
            for (SqlNode op : ((SqlCall) node).getOperandList()) {
                if (hasCastOverDynamicParam(op)) {
                    return true;
                }
            }
        } else if (node instanceof SqlNodeList) {
            for (SqlNode n : (SqlNodeList) node) {
                if (hasCastOverDynamicParam(n)) {
                    return true;
                }
            }
        }
        return false;
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

    @Test
    public void knownTypeDynamicParamIsCastInjected() {
        // 正控制：'张三' 经 preParse 参数化为已知型 ?(VARCHAR)，coerceOperandType 应就地对它包 CAST，
        // 证实递归查找器非偏 false，并钉住“已知型动态参会被 cast”这一侧。
        SqlNode node = validateNode("SELECT id FROM tbin WHERE blobcol = '张三'");
        Assertions.assertTrue(hasCastOverDynamicParam(node),
                "已知型(VARCHAR)动态参应被就地包 CAST，实际树：\n" + node);
    }

    /**
     * 终审 Important-3：spec §4 承诺“未知型(ANY)动态参仍保守拒 cast、不扩面”，此承重守卫此前仅代码分支、无断言锁。
     * 实测：裸 {@code ?}（无建议型→resolveDynamicParam 派生 ANY）在比较面不报错也不被包 CAST；
     * coerceOperandType 的 ANY 分支 return false。可观测语义即“不对 ANY 参注入 CAST”（非抛异常），
     * 与正控制 knownTypeDynamicParamIsCastInjected 仅差该守卫，二者共同辨别 ANY 分支。
     */
    @Test
    public void anyTypedDynamicParamNotCastInjected() {
        SqlNode node = validateNode("SELECT id FROM tbin WHERE blobcol = ?");
        Assertions.assertFalse(hasCastOverDynamicParam(node),
                "ANY 动态参不得被包 CAST（承重守卫：不扩面），实际树：\n" + node);
    }

    /**
     * 终审 Important-1：{@code coerceOperandType} 系公共闸门，{@code rowTypeCoercion} 的 VALUES 分支（INSERT）亦经此，
     * 非仅比较/内置函数两面。锁此新面行为不崩：{@code INSERT ... VALUES(?, ?)} 的跨族已知型参数可被包 CAST 通过校验。
     */
    @Test
    public void insertValuesCrossTypeParamCoerces() {
        Assertions.assertDoesNotThrow(
                () -> validateOk("INSERT INTO tbin(id, blobcol) VALUES(1, '张三')"));
    }

    /** 终审 Important-1：IN 列表（{@code inListCoercion}）共享同一闸门，锁跨族已知型参数不崩。 */
    @Test
    public void inListCrossTypeParamCoerces() {
        Assertions.assertDoesNotThrow(
                () -> validateOk("SELECT id FROM tbin WHERE id IN ('1')"));
    }
}
