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
package io.mycat.calcite.metadata;

import com.google.common.collect.ImmutableList;
import io.mycat.DrdsSqlCompiler;
import io.mycat.calcite.MycatCalciteSupport;
import io.mycat.calcite.spm.ParamHolder;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-3 续 Task 4/5：参数化 LIMIT 的行数估算归位基线。
 *
 * <p>
 * 锁的是"影子在位时 Mycat 现在估出什么行数"。影子 {@code RelMdRowCount}/{@code RelMdMaxRowCount}
 * 删除、逻辑上提到 {@code MycatMdRowCount}/{@code MycatMdMaxRowCount} 前后各跑一次，逐条对比：
 * 归位必须与原影子逐值等价。
 *
 * <p>
 * 判别点：动态 offset（{@code LIMIT ?} 的偏移为参数）时，影子 {@code getRowCount(Sort)} 直接早返回
 * 输入行数（不解析参数、不减 offset），而 fork 版会经 {@code RexLiteral.intValue} 解析参数后相减。
 * 两者对 5 行 + offset=参数(2) 分别得 5.0 与 3.0 —— 这条差值即证明归位保住了 Mycat 语义、没退回 fork 行为。
 */
public class SortParamRowCountTest {

    @AfterEach
    public void clearParams() {
        ParamHolder.CURRENT_THREAD_LOCAL.get().clear();
    }

    private RelNode fiveRowValues(RelOptCluster cluster) {
        RelOptSchema schema = new CalciteCatalogReader(
                CalciteSchema.createRootSchema(false, false),
                Collections.<String>emptyList(),
                MycatCalciteSupport.TypeFactory,
                MycatCalciteSupport.calciteConnectionConfig);
        RelBuilder rb = MycatCalciteSupport.relBuilderFactory.create(cluster, schema);
        RelDataTypeFactory.FieldInfoBuilder fib = MycatCalciteSupport.TypeFactory.builder();
        fib.add("id", SqlTypeName.INTEGER);
        RelDataType rowType = fib.build();
        RexBuilder rex = cluster.getRexBuilder();
        List<ImmutableList<RexLiteral>> tuples = new ArrayList<>();
        RelDataType intType = rowType.getFieldList().get(0).getType();
        for (int i = 0; i < 5; i++) {
            tuples.add(ImmutableList.of((RexLiteral) rex.makeLiteral(i, intType, true)));
        }
        rb.values(tuples, rowType);
        return rb.build();
    }

    private Sort dynamicOffsetSort(RelOptCluster cluster) {
        RelNode input = fiveRowValues(cluster);
        RexDynamicParam offset = (RexDynamicParam) cluster.getRexBuilder().makeDynamicParam(
                MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER), 0);
        return LogicalSort.create(input, RelCollations.EMPTY, offset, null);
    }

    /** 动态 offset 场景：影子 getRowCount(Sort) 不解析参数、直接返回输入行数 5.0。 */
    @Test
    public void rowCount_WithDynamicOffset_ReturnsInputRowCountUnresolved() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        Sort sort = dynamicOffsetSort(cluster);
        ParamHolder.CURRENT_THREAD_LOCAL.get()
                .setData(Collections.singletonList(2), Collections.emptyList());

        RelMetadataQuery mq = cluster.getMetadataQuery();
        Double rowCount = mq.getRowCount(sort);
        Assertions.assertEquals(5.0D, rowCount, 0.0D,
                "Mycat 语义：动态 offset 时不解析参数，行估 = 输入 5");
    }

    /** 动态 offset 场景：影子 getMaxRowCount(Sort) 解析参数（offset=2），5-2=3.0。 */
    @Test
    public void maxRowCount_WithDynamicOffset_ResolvesParam() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        Sort sort = dynamicOffsetSort(cluster);
        ParamHolder.CURRENT_THREAD_LOCAL.get()
                .setData(Collections.singletonList(2), Collections.emptyList());

        RelMetadataQuery mq = cluster.getMetadataQuery();
        Double maxRowCount = mq.getMaxRowCount(sort);
        Assertions.assertEquals(3.0D, maxRowCount, 0.0D,
                "Mycat 语义：动态 offset 解析为 2，max = 5-2 = 3");
    }
}
