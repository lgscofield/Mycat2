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
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.rewriter.Distribution;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-3 续 Task 6：MycatView merge-sort 的 collation 供出归位基线（Review Focus #4）。
 *
 * <p>
 * 锁的是"影子 {@code RelMdCollation} 的 {@code instanceof MycatView} 分支供出什么"。影子删除、规则
 * 上提到 {@link MycatRelMetadataProvider} 的 provider 链前后各跑一次，逐条对比：归位必须与原影子一致
 * ——merge-sort 视图透传底层 {@link Sort} 的 collation；非 merge-sort 且 traitSet 无 collation 时返回
 * 空列表（"确定无序"），而非 fork catch-all 的 {@code null}（"未知"）。
 */
public class MycatViewCollationTest {

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

    /** 空表列表的 Distribution 其 type() 默认返回 SHARDING，足以触发 merge-sort 判定。 */
    private Distribution shardingDistribution() {
        return new Distribution(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    /** merge-sort 视图：底层 Sort 带非空 collation，MycatView 应透传该 collation。 */
    @Test
    public void mergeSortView_PassesThroughUnderlyingSortCollation() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        RelNode input = fiveRowValues(cluster);
        RelCollation collation = RelCollations.of(
                new RelFieldCollation(0, Direction.ASCENDING));
        Sort sort = LogicalSort.create(input, collation, null, null);
        MycatView view = new MycatView(cluster.traitSet(), sort, shardingDistribution());
        Assertions.assertTrue(view.isMergeSort(), "构造应为 merge-sort 视图");

        RelMetadataQuery mq = cluster.getMetadataQuery();
        List<RelCollation> result = mq.collations(view);

        Assertions.assertNotNull(result, "Mycat 语义：MycatView 的 collation 不应为 null");
        Assertions.assertEquals(1, result.size(), "应透传底层 Sort 的单一 collation");
        Assertions.assertEquals(collation, result.get(0),
                "供出的 collation 必须与底层 Sort 逐字段一致");
    }

    /**
     * 非 merge-sort 视图（底层非 Sort）：MycatView 的 collation 必须供出"确定无序"——非 null
     * 的空列表（或仅含空 collation），以区别于 fork RelMdCollation catch-all 对未知节点的
     * {@code null}（"未知"）。判别子：摘掉 COLLATION_SOURCE 后 MycatView 落 fork catch-all 即 null，本用例转红。
     */
    @Test
    public void nonMergeSortView_ReturnsDefinitelyUnordered_NotNull() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        RelNode input = fiveRowValues(cluster);
        // 底层为 values（非 Sort）+ SHARDING distribution → isMergeSort() 为 false，走非 merge-sort 分支。
        MycatView view = new MycatView(cluster.traitSet(), input, shardingDistribution());
        Assertions.assertFalse(view.isMergeSort(), "底层非 Sort，构造应为非 merge-sort 视图");

        RelMetadataQuery mq = cluster.getMetadataQuery();
        List<RelCollation> result = mq.collations(view);

        // 核心契约差：Mycat 供出确定无序（非 null），而非 fork catch-all 的 null（未知）。
        Assertions.assertNotNull(result,
                "Mycat 语义：非 merge-sort MycatView 的 collation 应为确定无序（非 null），区别于 fork catch-all 的 null");
        // 且“无序”：空列表，或仅含无字段排序项的 collation。
        Assertions.assertTrue(result.isEmpty()
                        || result.stream().allMatch(c -> c.getFieldCollations().isEmpty()),
                "确定无序：供出列表应为空，或其元素的字段排序项均为空");
    }
}
