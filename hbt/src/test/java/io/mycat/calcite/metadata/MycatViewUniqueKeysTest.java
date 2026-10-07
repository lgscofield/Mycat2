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
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * I-DEBT-3 续 Task 7：MycatView uniqueKeys 透传归位基线。
 *
 * <p>
 * 锁的是"影子 {@code RelMdUniqueKeys.getUniqueKeys(MycatView, mq, ignoreNulls)} 供出什么"。影子删除、
 * 规则上提到 {@link MycatRelMetadataProvider} 的 provider 链前后各跑一次对比：归位必须与原影子一致
 * ——视图自身唯一键 = 透传其底层 {@code getRelNode()} 的唯一键。判别子用"按 id 分组的聚合"作底层，
 * 因 fork {@code getUniqueKeys(Aggregate)} 返回非空 group-set，可干净区分 fork 单独（MycatView 走
 * catch-all 返回 {@code null}）与归位后（透传底层非空唯一键）。
 */
public class MycatViewUniqueKeysTest {

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
        // 按 id(字段 0) 分组，聚合 group-set 构成非空唯一键。
        rb.aggregate(rb.groupKey(0));
        return rb.build();
    }

    private Distribution shardingDistribution() {
        return new Distribution(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    /** MycatView 的 uniqueKeys 应透传底层 getRelNode() 的 uniqueKeys（归位前后逐值一致）。 */
    @Test
    public void view_uniqueKeysDelegateToUnderlyingRelNode() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        RelNode agg = fiveRowValues(cluster);
        MycatView view = new MycatView(cluster.traitSet(), agg, shardingDistribution());

        RelMetadataQuery mq = cluster.getMetadataQuery();
        Set<ImmutableBitSet> underlying = mq.getUniqueKeys(view.getRelNode(), true);
        Assertions.assertNotNull(underlying, "底层聚合应有非空 group-set 唯一键(判别子成立)");
        Assertions.assertFalse(underlying.isEmpty(), "group-set 唯一键应非空");

        Set<ImmutableBitSet> fromView = mq.getUniqueKeys(view, true);

        Assertions.assertNotNull(fromView,
                "Mycat 语义：MycatView 的 uniqueKeys 不应为 null（fork 单独走 catch-all 才为 null）");
        Assertions.assertEquals(underlying, fromView,
                "MycatView 的 uniqueKeys 必须透传底层 getRelNode() 的 uniqueKeys");
    }
}
