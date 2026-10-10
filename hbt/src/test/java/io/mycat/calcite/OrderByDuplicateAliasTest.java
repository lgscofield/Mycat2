/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite;

import com.alibaba.druid.sql.ast.SQLStatement;
import io.mycat.DrdsSqlWithParams;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.runtime.CalciteContextException;
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
 * I-DEBT-26 回归锁：ORDER BY 名字命中 select 列表中<b>重复出现的同一列</b>时，须按 MySQL 语义
 * 回退到基表列解析、<b>不</b>判歧义（e2e 靶：{@code AssembleTest}/{@code PstmtAssembleTest} 对
 * information_schema.triggers 里 {@code EVENT_OBJECT_TABLE} 选两次 + {@code ORDER BY event_object_table}，
 * Calcite 抛 {@code Column 'event_object_table' is ambiguous}，真 MySQL 8.0.46 实测接受）。
 *
 * <p>
 * 同时钉住 MySQL 的另一侧语义作防过放宽守卫：两个<b>不同列</b>被显式起<b>同一别名</b>、
 * {@code ORDER BY} 引用该别名时，MySQL 仍报 {@code Column 'a' in order clause is ambiguous}（实测 P3 复现），
 * 故修复绝不可无差别吞掉所有歧义异常。
 *
 * <p>
 * 走 validate-only 脚手架（同 {@link CalciteValidatorGoldenTest}：preParse → getSqlValidator → visit → validate），
 * 秒级红/绿，不依赖活体 8066。
 */
public class OrderByDuplicateAliasTest {

    private static final String DEFAULT_SCHEMA = "mycat";

    private static final CalciteCatalogReader CATALOG_READER =
            DrdsRunnerHelper.newCalciteCatalogReader(rootSchemaWithFixtureTables());

    /** fixture 表 t1(id INT NOT NULL, name VARCHAR(64), age INT)，同 CalciteValidatorGoldenTest 的可空性口径。 */
    private static org.apache.calcite.schema.SchemaPlus rootSchemaWithFixtureTables() {
        org.apache.calcite.schema.SchemaPlus root = CalciteSchema.createRootSchema(false).plus();
        RelDataTypeFactory.Builder b = MycatCalciteSupport.TypeFactory.builder();
        b.add("id", MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER));
        b.add("name", nullableVarchar());
        b.add("age", nullableVarchar());
        RelDataType rowType = b.build();
        final Map<String, Table> tables = new HashMap<>();
        tables.put("t1", new AbstractTable() {
            @Override
            public RelDataType getRowType(RelDataTypeFactory typeFactory) {
                return rowType;
            }
        });
        root.add(DEFAULT_SCHEMA, new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
                return tables;
            }
        });
        return root;
    }

    private static RelDataType nullableVarchar() {
        return MycatCalciteSupport.TypeFactory.createTypeWithNullability(
                MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.VARCHAR, 64), true);
    }

    /** preParse → validate，返回结果行类型；校验期抛出的异常原样向上传播。 */
    private static RelDataType validateRowType(String sql) {
        DrdsSqlWithParams drdsSql = DrdsRunnerHelper.preParse(sql, DEFAULT_SCHEMA);
        SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, CATALOG_READER);
        SQLStatement stmt = drdsSql.getParameterizedStatement();
        MycatCalciteMySqlNodeVisitor visitor = new MycatCalciteMySqlNodeVisitor();
        stmt.accept(visitor);
        SqlNode validated = validator.validate(visitor.getSqlNode());
        return validator.getValidatedNodeType(validated);
    }

    /** I-DEBT-26：同一列在 select 列表重复出现，ORDER BY 引用其名须正常通过（当前 RED：抛 ambiguous）。 */
    @Test
    public void orderByDuplicateSameColumnResolvesLikeMysql() {
        RelDataType rowType = validateRowType("SELECT name, name FROM t1 ORDER BY name");
        Assertions.assertEquals(2, rowType.getFieldCount(),
                "两个 name 输出列都应保留，ORDER BY 不改变投影宽度");
    }

    /** 守卫（MySQL P3）：不同列共享同一显式别名，ORDER BY 引用该别名仍须判歧义——修复不得无差别吞异常。 */
    @Test
    public void distinctColumnsSharingAliasStayAmbiguous() {
        CalciteContextException ex = Assertions.assertThrows(CalciteContextException.class,
                () -> validateRowType("SELECT id AS x, name AS x FROM t1 ORDER BY x"),
                "不同列起同一别名，MySQL 报 ambiguous，mycat 必须一致，不能被去重修复误放行");
        Assertions.assertTrue(ex.getMessage() != null && ex.getMessage().contains("ambiguous"),
                "异常须是列歧义类：" + ex.getMessage());
    }
}
