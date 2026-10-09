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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 EXISTS 相关子查询去相关后的 {@code Correlate}+{@code Aggregate(distinct)} 坏形状重写为
 * 等价的 {@code LogicalJoin(SEMI)}，交由 Mycat 已有的半连接算子（{@code MycatSemiHashJoin} →
 * {@code MycatHashJoin.implementHashSemiJoin}）执行，绕开 correlate+distinct codegen 触发的
 * {@code Cannot convert Object[] to long}。
 *
 * <p>匹配的真实形状（活体 explain 2026-10-09 钉死）：
 * <pre>
 * Correlate(joinType=INNER, one correlation)
 *   left                                  ← 外层
 *   Project* → Aggregate(groupSet=全部列, 无 aggCall = distinct)
 *                → Project* → Filter(相关等值 ∧ 可选其它谓词) → R   ← 内层
 * </pre>
 * 半连接天然"每左行至多一次"，故丢弃 Aggregate/Project(true) 指示列折叠，仅把相关等值升为
 * join condition、其它谓词留在 R 上的 Filter。非等值/无相关等值键则**不改写**（保 correlate），
 * 避免误产错误 SEMI。
 */
public class MycatExistsInSubQueryToSemiJoinRule extends RelOptRule {

    public static final MycatExistsInSubQueryToSemiJoinRule INSTANCE =
            new MycatExistsInSubQueryToSemiJoinRule();

    private MycatExistsInSubQueryToSemiJoinRule() {
        // 匹配 EXISTS/NOT EXISTS 坏形状（落点选在 correlate 之上「只投影左列、丢弃 EXISTS 指示列」的
        // Project，而非裸 Correlate）：裸 Correlate 的 rowType=左+右指示列，而 SEMI/ANTI join 只出左列，
        // 直接 transformTo 会被 HepPlanner 判类型不符；此 Project 的 rowType 恰等左列（丢弃了指示列），
        // 故保留 Project、仅把输入换成 SEMI/ANTI join 即类型自洽。
        // operand 放宽到任意 Project，内部按子节点区分：INNER Correlate → EXISTS(SEMI)；
        // Filter(IS NULL 指示列) over LEFT Correlate → NOT EXISTS(ANTI)。any() = ANY_CHILDREN。
        super(operand(Project.class, any()), "MycatExistsInSubQueryToSemiJoinRule");
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Project project = call.rel(0);
        RelNode child = unwrap(project.getInput());
        Correlate correlate;
        final JoinRelType joinType;
        if (child instanceof Correlate) {
            // EXISTS：Project(左列) over INNER Correlate —— INNER 已滤掉无匹配的左行，等价 SEMI
            correlate = (Correlate) child;
            if (correlate.getJoinType() != JoinRelType.INNER) {
                return;
            }
            joinType = JoinRelType.SEMI;
        } else if (child instanceof Filter) {
            // NOT EXISTS：Project(左列) over Filter(IS NULL 指示列) over LEFT Correlate，等价 ANTI
            Filter nf = (Filter) child;
            RelNode fc = unwrap(nf.getInput());
            if (!(fc instanceof Correlate)) {
                return;
            }
            correlate = (Correlate) fc;
            if (correlate.getJoinType() != JoinRelType.LEFT) {
                return;
            }
            final int indicatorFloor = unwrap(correlate.getLeft()).getRowType().getFieldCount();
            for (RexNode c : RelOptUtil.conjunctions(nf.getCondition())) {
                if (!isIndicatorIsNull(c, indicatorFloor)) {
                    return; // 含其它谓词则不改写（保安全）
                }
            }
            joinType = JoinRelType.ANTI;
        } else {
            return;
        }
        if (correlate.getVariablesSet().size() != 1) {
            return;
        }
        final CorrelationId corr = correlate.getCorrelationId();
        final RelNode left = unwrap(correlate.getLeft());
        final int leftCount = left.getRowType().getFieldCount();
        final RexBuilder rexBuilder = correlate.getCluster().getRexBuilder();

        // 纯 WHERE EXISTS 判别：上层 Project 丢弃了 correlate 右侧的指示列（表达式全为左列引用）。
        // 若引用了指示列（SELECT EXISTS 标志列）则不改写，避免破坏输出。
        for (RexNode e : project.getProjects()) {
            if (!(e instanceof RexInputRef) || ((RexInputRef) e).getIndex() >= leftCount) {
                return;
            }
        }

        // 右子树：剥掉外层 Project/HepRelVertex 找 Aggregate（须为纯 distinct：无 aggCall）
        RelNode right = stripProjects(correlate.getRight());
        if (!(right instanceof Aggregate)) {
            return;
        }
        Aggregate agg = (Aggregate) right;
        if (!agg.getAggCallList().isEmpty()) {
            return; // 含聚合调用则非"是否存在"折叠，不可等价转 SEMI
        }

        // Aggregate 输入：剥掉 Project 找 Filter
        RelNode underAgg = stripProjects(agg.getInput());
        if (!(underAgg instanceof Filter)) {
            return;
        }
        Filter filter = (Filter) underAgg;
        RelNode base = unwrap(filter.getInput()); // R

        // 拆 Filter 条件：相关等值 → join key；其它 → 留在 R 上的 Filter
        List<RexNode> conjuncts = RelOptUtil.conjunctions(filter.getCondition());
        List<Integer> leftKeys = new ArrayList<>();
        List<Integer> rightKeys = new ArrayList<>();
        List<RexNode> residual = new ArrayList<>();
        for (RexNode c : conjuncts) {
            int[] pair = matchCorrelationEquality(c, corr, base.getRowType().getFieldCount());
            if (pair != null) {
                leftKeys.add(pair[0]);
                rightKeys.add(pair[1]);
            } else {
                residual.add(c);
            }
        }
        if (leftKeys.isEmpty()) {
            return; // 无相关等值键（非等值/复杂相关）→ 不改写
        }

        RelNode rightRel = base;
        if (!residual.isEmpty()) {
            RexNode residualCond = RexUtil.composeConjunction(rexBuilder, residual, true);
            rightRel = LogicalFilter.create(base, residualCond);
        }

        // 构造 combined 帧上的 SEMI join condition
        List<RexNode> conds = new ArrayList<>();
        for (int i = 0; i < leftKeys.size(); i++) {
            int lIdx = leftKeys.get(i);
            int rIdx = rightKeys.get(i);
            RelDataType lt = left.getRowType().getFieldList().get(lIdx).getType();
            RelDataType rt = rightRel.getRowType().getFieldList().get(rIdx).getType();
            conds.add(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
                    rexBuilder.makeInputRef(lt, lIdx),
                    rexBuilder.makeInputRef(rt, leftCount + rIdx)));
        }
        RexNode joinCond = RexUtil.composeConjunction(rexBuilder, conds, false);

        Join join = LogicalJoin.create(left, rightRel, ImmutableList.of(), joinCond,
                ImmutableSet.of(), joinType);
        // 保留原 Project（其表达式只引用左列，索引在 SEMI/ANTI join 输出 [0,leftCount) 内不变），
        // 仅把输入换成 join → 行类型与原 Project 一致，HepPlanner 接受。
        RelNode newProject = ((LogicalProject) project)
                .copy(correlate.getCluster().traitSet(), join, project.getProjects(),
                        project.getRowType());
        call.transformTo(newProject);
    }

    /**
     * 匹配 {@code =($cor.field, $i)} 形式的相关等值。返回 {leftFieldIndex, rightFieldIndex}，
     * 不匹配返回 null。correlation 变量类型即外层 rowType，其字段索引直接映射到左列。
     */
    private static int[] matchCorrelationEquality(RexNode node, CorrelationId corr, int rightFieldCount) {
        if (!(node instanceof RexCall)) {
            return null;
        }
        RexCall call = (RexCall) node;
        if (call.getOperator() != SqlStdOperatorTable.EQUALS || call.getOperands().size() != 2) {
            return null;
        }
        RexNode a = call.getOperands().get(0);
        RexNode b = call.getOperands().get(1);
        RexFieldAccess fa = asCorrelationField(a, corr);
        RexInputRef ref = fa != null ? asInputRef(b, rightFieldCount) : null;
        if (ref == null) {
            fa = asCorrelationField(b, corr);
            ref = fa != null ? asInputRef(a, rightFieldCount) : null;
        }
        if (fa == null || ref == null) {
            return null;
        }
        return new int[]{fa.getField().getIndex(), ref.getIndex()};
    }

    private static RexFieldAccess asCorrelationField(RexNode node, CorrelationId corr) {
        if (node instanceof RexFieldAccess) {
            RexFieldAccess fa = (RexFieldAccess) node;
            if (fa.getReferenceExpr() instanceof RexCorrelVariable
                    && ((RexCorrelVariable) fa.getReferenceExpr()).id.equals(corr)) {
                return fa;
            }
        }
        return null;
    }

    private static RexInputRef asInputRef(RexNode node, int fieldCount) {
        if (node instanceof RexInputRef) {
            int idx = ((RexInputRef) node).getIndex();
            if (idx >= 0 && idx < fieldCount) {
                return (RexInputRef) node;
            }
        }
        return null;
    }

    /** 判别 {@code IS NULL($k)}，其中 $k 指向 correlate 右侧（EXISTS 指示列，索引 >= leftCount）。 */
    private static boolean isIndicatorIsNull(RexNode node, int leftCount) {
        if (node instanceof RexCall) {
            RexCall call = (RexCall) node;
            if (call.getOperator() == SqlStdOperatorTable.IS_NULL && call.getOperands().size() == 1) {
                RexNode op = call.getOperands().get(0);
                return op instanceof RexInputRef && ((RexInputRef) op).getIndex() >= leftCount;
            }
        }
        return false;
    }

    private static RelNode stripProjects(RelNode node) {
        RelNode n = unwrap(node);
        while (n instanceof Project) {
            n = unwrap(((Project) n).getInput());
        }
        return n;
    }

    /** HepPlanner 会把子节点包成 {@link HepRelVertex}，遍历前逐层解包回真实 RelNode。 */
    private static RelNode unwrap(RelNode node) {
        RelNode n = node;
        while (n instanceof HepRelVertex) {
            n = ((HepRelVertex) n).getCurrentRel();
        }
        return n;
    }
}
