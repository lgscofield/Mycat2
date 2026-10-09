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
import io.mycat.calcite.MycatCalciteSupport;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.Holder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * EXISTS 相关子查询去相关为 SEMI Join 的规则单测。
 *
 * <p>判别子来自活体 {@code explain}（2026-10-09）钉死的真实坏形状：
 * {@code Correlate(inner) → Project → Aggregate(group=[{0}], 无 aggCall=distinct) → Project(i=[true])
 * → Filter(=($cor.id, $0)) → 内层扫描}。该形状走 MycatCorrelate+Aggregate distinct codegen，
 * 触发 {@code Cannot convert Object[] to long}。本规则应把它重写为等价的 {@code LogicalJoin(SEMI)}
 * ——半连接天然"每左行至多一次"，无需 aggregate/distinct，绕开坏 codegen。
 */
public class MycatExistsInSubQueryToSemiJoinRuleTest {

    private static RelBuilder createRelBuilder() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        RelOptSchema schema = new CalciteCatalogReader(
                CalciteSchema.createRootSchema(false, false),
                Collections.<String>emptyList(),
                MycatCalciteSupport.TypeFactory,
                MycatCalciteSupport.calciteConnectionConfig);
        return MycatCalciteSupport.relBuilderFactory.create(cluster, schema);
    }

    private static RelNode idScan(RelBuilder rb, String name) {
        RelDataTypeFactory.FieldInfoBuilder fib = MycatCalciteSupport.TypeFactory.builder();
        fib.add("id", SqlTypeName.INTEGER);
        return rb.transientScan(name, fib.build()).build();
    }

    private RelNode applyRule(RelNode root) {
        HepProgramBuilder builder = new HepProgramBuilder();
        builder.addRuleInstance(MycatExistsInSubQueryToSemiJoinRule.INSTANCE);
        HepPlanner hep = new HepPlanner(builder.build());
        hep.setRoot(root);
        return hep.findBestExp();
    }

    /** EXISTS 的 correlate 形状 → 应重写为 SEMI Join（红先行：规则未产出前本测失败）。 */
    @Test
    void existsBecomesSemiJoin() {
        RelBuilder rb = createRelBuilder();
        RelNode left = idScan(rb, "T");
        RelNode right = idScan(rb, "C");

        final Holder<RexCorrelVariable> v = Holder.of(null);
        RelNode tree = rb
                .push(left)
                .variable(v)
                .push(right)
                // 相关等值谓词：外层 T.id（经相关变量）= 内层 C.id（$0）
                .filter(rb.equals(rb.field(v.get(), "id"), rb.field(0)))
                // EXISTS 指示列：常量 true
                .project(rb.literal(true))
                // 全列分组、无聚合调用 = distinct 折叠（真实 createExistsPlan 产物）
                .aggregate(rb.groupKey(0))
                .correlate(JoinRelType.INNER, v.get().id)
                // correlate 之上“只投影左列、丢弃 EXISTS 指示列”的 Project（等价 select t.*）
                .project(rb.field(0))
                .build();

        // 前提校验：根为 correlate 之上的 Project，与活体逻辑树一致
        Assertions.assertTrue(tree instanceof Project,
                "根应为 correlate 之上的 Project，实际=" + tree.getClass().getSimpleName());

        RelNode out = applyRule(tree);

        Assertions.assertTrue(out instanceof Project,
                "改写后根仍为 Project，实际=" + out.getClass().getSimpleName() + "\n" + RelOptUtil.toString(out));
        RelNode joinInput = ((Project) out).getInput();
        Assertions.assertTrue(joinInput instanceof Join,
                "Project 输入应重写为 LogicalJoin，实际=" + joinInput.getClass().getSimpleName()
                        + "\n" + RelOptUtil.toString(out));
        Assertions.assertEquals(JoinRelType.SEMI, ((Join) joinInput).getJoinType(),
                "EXISTS 应重写为 SEMI Join");
    }

    /** NOT EXISTS 的 LEFT correlate + IS NULL(指示列) 形状 → 应重写为 ANTI Join。 */
    @Test
    void notExistsBecomesAntiJoin() {
        RelBuilder rb = createRelBuilder();
        RelNode left = idScan(rb, "T");
        RelNode right = idScan(rb, "C");

        final Holder<RexCorrelVariable> v = Holder.of(null);
        RelNode tree = rb
                .push(left)
                .variable(v)
                .push(right)
                .filter(rb.equals(rb.field(v.get(), "id"), rb.field(0)))
                .project(rb.literal(true))
                .aggregate(rb.groupKey(0))
                // NOT EXISTS：LEFT correlate（保留全左行，指示列 $1 无匹配时为 NULL）
                .correlate(JoinRelType.LEFT, v.get().id)
                // 上层 Filter(IS NULL 指示列 $1) = 取无匹配者
                .filter(rb.isNull(rb.field(1)))
                // 再 Project 只留左列（等价 select t.*）
                .project(rb.field(0))
                .build();

        Assertions.assertTrue(tree instanceof Project,
                "根应为 correlate 之上的 Project，实际=" + tree.getClass().getSimpleName());

        RelNode out = applyRule(tree);

        Assertions.assertTrue(out instanceof Project,
                "改写后根仍为 Project，实际=" + out.getClass().getSimpleName() + "\n" + RelOptUtil.toString(out));
        RelNode joinInput = ((Project) out).getInput();
        Assertions.assertTrue(joinInput instanceof Join,
                "Project 输入应重写为 LogicalJoin，实际=" + joinInput.getClass().getSimpleName()
                        + "\n" + RelOptUtil.toString(out));
        Assertions.assertEquals(JoinRelType.ANTI, ((Join) joinInput).getJoinType(),
                "NOT EXISTS 应重写为 ANTI Join");
    }

    /**
     * 相关子查询含“等值键 + 另一个非等值相关谓词”的混合形状：
     * {@code where c.id = t.id and t.id > c.id}。只有等值项能升为 join key，
     * 非等值相关谓词仍引用被移除的相关变量（$cor），无法安全下放到右子树。
     * 按 Review Focus ④（非等值/复合相关必须**不改写**），本规则应 bail，保持原 Correlate。
     */
    @Test
    void mixedCorrelatedPredicateIsNotRewritten() {
        RelBuilder rb = createRelBuilder();
        RelNode left = idScan(rb, "T");
        RelNode right = idScan(rb, "C");

        final Holder<RexCorrelVariable> v = Holder.of(null);
        RelNode tree = rb
                .push(left)
                .variable(v)
                .push(right)
                // 合取：等值相关键 + 一个非等值相关谓词（两者均引用 $cor）
                .filter(rb.call(org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS,
                                rb.field(v.get(), "id"), rb.field(0)),
                        rb.call(org.apache.calcite.sql.fun.SqlStdOperatorTable.GREATER_THAN,
                                rb.field(v.get(), "id"), rb.field(0)))
                .project(rb.literal(true))
                .aggregate(rb.groupKey(0))
                .correlate(JoinRelType.INNER, v.get().id)
                .project(rb.field(0))
                .build();

        RelNode out = applyRule(tree);

        // 不应被改写：Project 的输入仍为 Correlate（非 SEMI Join），避免产生悬空相关变量的非法计划
        RelNode joinInput = ((Project) out).getInput();
        Assertions.assertFalse(joinInput instanceof Join,
                "含非等值相关谓词的混合形状不应被改写为 Join，实际="
                        + joinInput.getClass().getSimpleName() + "\n" + RelOptUtil.toString(out));
    }
}
