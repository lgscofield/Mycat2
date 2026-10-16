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
 */
package io.mycat.calcite.rules;

import io.mycat.DrdsSqlCompiler;
import io.mycat.MysqlMetadataManager;
import io.mycat.calcite.MycatCalciteSupport;
import io.mycat.calcite.table.MycatLogicTable;
import io.mycat.calcite.table.MycatTableScan;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * I-DEBT-54/D1：恒空仿表子树折叠规则单测。
 *
 * <p>判别子来自真 mysqldump 的默认查询形状：{@code Project(JSON_EXTRACT…) → 恒空 i_s 仿表扫描}。
 * 旧行为该 Project 进入 MycatProject codegen，JSON_EXTRACT 无实现者 → "Unable to implement MycatProject"。
 * 规则把全叶恒空的子树折叠为空 {@link LogicalValues}，绕开投影 codegen；语义忠实
 * （真 MySQL 无直方图统计时该查询本就返回 0 行）。
 *
 * <p>说明：本测在 hbt 规则级 harness 内构造真实 {@link MycatTableScan}（包
 * {@link MysqlMetadataManager#COLUMN_STATISTICS_TABLE_HANDLER} 恒空仿表），与兄弟规则测
 * {@code MycatExistsInSubQueryToSemiJoinRuleTest} 同构。原计划置于 internalexample 的离线规划器
 * 测因该模块 test classpath 拿不到 hbt（example 把 hbt 设 test scope 不传递，属先于本改动存在的
 * 模块级断裂）而无法编译，故迁移至此。
 */
public class EmptyVisualTableSubtreeRuleTest {

    private static RelBuilder createRelBuilder() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        CalciteSchema rootSchema = CalciteSchema.createRootSchema(false, false);
        // 注册真恒空 i_s 仿表：MycatLogicTable 包 VisualTableHandler（scanAll 永远 empty）
        rootSchema.add("COLUMN_STATISTICS",
                new MycatLogicTable(MysqlMetadataManager.COLUMN_STATISTICS_TABLE_HANDLER));
        // 注册含实数据的 i_s 仿表（ENGINES）：同为 VisualTableHandler 但 scanAll 有行，
        // isStaticallyEmpty=false——用于锁死折叠判别面（不得以 isVisual 为键）。
        rootSchema.add("ENGINES",
                new MycatLogicTable(MysqlMetadataManager.ENGINES_TABLE_HANDLER));
        RelOptSchema schema = new CalciteCatalogReader(
                rootSchema,
                Collections.<String>emptyList(),
                MycatCalciteSupport.TypeFactory,
                MycatCalciteSupport.calciteConnectionConfig);
        return MycatCalciteSupport.relBuilderFactory.create(cluster, schema);
    }

    private RelNode applyRule(RelNode root) {
        HepProgramBuilder builder = new HepProgramBuilder();
        builder.addRuleInstance(EmptyVisualTableSubtreeRule.INSTANCE);
        HepPlanner hep = new HepPlanner(builder.build());
        hep.setRoot(root);
        return hep.findBestExp();
    }

    /** 恒空仿表之上的 Project（含 JSON_EXTRACT 型投影）应被折叠为空 Values，列头保真。 */
    @Test
    void projectOverStaticallyEmptyVisualFoldsToValues() {
        RelBuilder rb = createRelBuilder();
        RelNode visualScan = MycatTableScan.ofTable(rb.scan("COLUMN_STATISTICS").build());
        // 模拟 mysqldump 投影：取两列（COLUMN_NAME + JSON_EXTRACT 输出列的等价投影）
        RelNode tree = rb.push(visualScan)
                .project(rb.field(0), rb.field(1))
                .build();
        Assertions.assertTrue(tree instanceof Project,
                "根应为 Project，实际=" + tree.getClass().getSimpleName());
        Assertions.assertEquals(2, tree.getRowType().getFieldCount());

        RelNode out = applyRule(tree);

        Assertions.assertTrue(out instanceof LogicalValues,
                "全叶恒空的子树应折叠为 LogicalValues，实际=" + out.getClass().getSimpleName()
                        + "\n" + RelOptUtil.toString(out));
        Assertions.assertEquals(0, ((LogicalValues) out).getTuples().size(),
                "折叠结果应为空（0 行）");
        Assertions.assertEquals(2, out.getRowType().getFieldCount(),
                "折叠后列头仍应保真为 2 列");
    }

    /**
     * 混合子树（恒空仿表 + 真实后端表叶子）不得折叠——严格谓词的守卫。
     * 反例若被误折叠会丢失真实表的数据，属正确性缺陷。
     */
    @Test
    void mixedSubtreeWithRealLeafIsNotFolded() {
        RelBuilder rb = createRelBuilder();
        RelNode visualScan = MycatTableScan.ofTable(rb.scan("COLUMN_STATISTICS").build());
        RelDataTypeFactory.FieldInfoBuilder fib = MycatCalciteSupport.TypeFactory.builder();
        fib.add("id", SqlTypeName.INTEGER);
        RelNode realScan = rb.transientScan("REAL_TABLE", fib.build()).build();

        RelNode tree = rb.push(visualScan)
                .push(realScan)
                .join(JoinRelType.INNER, rb.literal(true))
                .project(rb.field(0))
                .build();

        RelNode out = applyRule(tree);

        Assertions.assertFalse(out instanceof LogicalValues,
                "掺入非恒空叶子的混合子树不应被折叠，实际=" + out.getClass().getSimpleName()
                        + "\n" + RelOptUtil.toString(out));
    }

    /**
     * 含实数据的仿表（ENGINES，scanAll 有行、isStaticallyEmpty=false）投影不得折叠——
     * 锁死折叠只以“静态恒空”为键，而非以 isVisual 为键（否则丢真数据）。
     */
    @Test
    void nonEmptyVisualLeafIsNotFolded() {
        RelBuilder rb = createRelBuilder();
        RelNode enginesScan = MycatTableScan.ofTable(rb.scan("ENGINES").build());
        RelNode tree = rb.push(enginesScan)
                .project(rb.field(0))
                .build();

        RelNode out = applyRule(tree);

        Assertions.assertFalse(out instanceof LogicalValues,
                "含实数据仿表的子树不应被折叠，实际=" + out.getClass().getSimpleName()
                        + "\n" + RelOptUtil.toString(out));
    }

    /**
     * 恒空仿表之上的无分组聚合（grand aggregate，如 {@code SELECT COUNT(*) FROM <i_s 恒空表>}）
     * 不得折叠——终审 Critical：空输入的 grand aggregate 恒返回恰好 1 行（COUNT→0、
     * MIN/MAX/SUM→NULL），折叠成空 Values 会把 1 行静默变成 0 行（无声错结果）。
     */
    @Test
    void grandAggregateOverStaticallyEmptyVisualIsNotFolded() {
        RelBuilder rb = createRelBuilder();
        RelNode visualScan = MycatTableScan.ofTable(rb.scan("COLUMN_STATISTICS").build());
        RelNode agg = rb.push(visualScan)
                .aggregate(rb.groupKey(), rb.count(false, "cnt", rb.field(0)))
                .build();
        // 手工造非恒等 Project（将聚合输出列 cnt 复制为两列），绕开 RelBuilder 对恒等投影的化简，
        // 确保根为 Project 以真实触发规则（模拟 SELECT COUNT(*)+计算投影 over 恒空 i_s 表）
        RelDataType cntType = agg.getRowType().getFieldList().get(0).getType();
        RelDataType projType = agg.getCluster().getTypeFactory().builder()
                .add("cnt", cntType).add("cnt2", cntType).build();
        java.util.List<org.apache.calcite.rex.RexNode> refs = com.google.common.collect.ImmutableList.of(
                new org.apache.calcite.rex.RexInputRef(0, cntType),
                new org.apache.calcite.rex.RexInputRef(0, cntType));
        RelNode tree = org.apache.calcite.rel.logical.LogicalProject.create(agg,
                com.google.common.collect.ImmutableList.of(), refs, projType);
        Assertions.assertTrue(tree instanceof Project,
                "构造根应为非恒等 Project，实际=" + tree.getClass().getSimpleName());

        RelNode out = applyRule(tree);

        Assertions.assertFalse(out instanceof LogicalValues,
                "含无分组聚合的子树不应折叠为空 Values（grand aggregate 对空输入返 1 行），实际="
                        + out.getClass().getSimpleName() + "\n" + RelOptUtil.toString(out));
    }

    /**
     * 直接锁判别面：恒空叶 true、实数据叶 false（不依赖 HEP 派发，回归时快速定位）。
     */
    @Test
    void leafPredicateDiscriminatesStaticallyEmpty() {
        RelBuilder rb = createRelBuilder();
        RelNode emptyLeaf = MycatTableScan.ofTable(rb.scan("COLUMN_STATISTICS").build());
        RelNode dataLeaf = MycatTableScan.ofTable(rb.scan("ENGINES").build());
        Assertions.assertTrue(EmptyVisualTableSubtreeRule.allLeavesAreStaticallyEmptyVisual(emptyLeaf),
                "COLUMN_STATISTICS 恒空叶子应判 true");
        Assertions.assertFalse(EmptyVisualTableSubtreeRule.allLeavesAreStaticallyEmptyVisual(dataLeaf),
                "ENGINES 实数据叶子应判 false");
    }
}
