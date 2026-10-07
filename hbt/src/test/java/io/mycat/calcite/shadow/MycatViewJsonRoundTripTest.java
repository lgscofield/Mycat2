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
package io.mycat.calcite.shadow;

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
import org.apache.calcite.rel.externalize.RelJsonWriter;
import org.apache.calcite.rel.logical.LogicalSort;
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
 * I-DEBT-3 续 Task 8：MycatView 序列化承重基线（Review Focus #5）。
 *
 * <p>
 * {@link RelJsonWriter} 影子对 {@code MycatView} 的 {@code instanceof} 分支把 {@code inputs} 覆写为
 * 底层 {@code getRelNode()}，从而让被包装的整棵子计划随视图一起写入 SPM 基线 JSON。本测试锁死"序列化
 * MycatView 后，其内层计划（LocalSort + 其后代）确实出现在输出里"——这是判定影子是否承重的直接证据：
 * 若删除影子后 fork 版因 {@code MycatView.getInputs()} 为空而只写出视图本身，本断言即红。
 */
public class MycatViewJsonRoundTripTest {

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

    private Distribution shardingDistribution() {
        return new Distribution(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    /** 序列化包装 Sort 的 MycatView：内层 LocalSort 必须作为被写出的节点出现。 */
    @Test
    public void serialize_MycatViewWithInnerSort_EmitsInnerPlan() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        RelNode input = fiveRowValues(cluster);
        RelCollation collation = RelCollations.of(new RelFieldCollation(0, Direction.ASCENDING));
        Sort sort = LogicalSort.create(input, collation, null, null);
        MycatView view = new MycatView(cluster.traitSet(), sort, shardingDistribution());

        RelJsonWriter writer = new RelJsonWriter();
        view.explain(writer);
        String json = writer.asString();

        // 视图本身（canonical 名，不在 RelJson.PACKAGES）
        Assertions.assertTrue(json.contains("io.mycat.calcite.logical.MycatView"),
                "MycatView 应作为顶层节点写出");
        // 承重断言：内层被包装的计划必须随视图一起序列化
        Assertions.assertTrue(json.contains("io.mycat.calcite.localrel.LocalSort"),
                "MycatView 的内层 LocalSort 必须被序列化进基线 JSON（影子覆写 inputs 的语义）");
    }
}
