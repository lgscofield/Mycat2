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
import com.google.common.collect.ImmutableSet;
import io.mycat.DrdsSqlCompiler;
import io.mycat.calcite.MycatCalciteSupport;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.metadata.RelMdUtil;
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
 * I-DEBT-3 续 Task 6 终审 Critical #1 复现守卫。
 *
 * <p>
 * 删除 {@code RelMdCollation} 影子后 fork 1.42 胜出，其 catch-all
 * {@code collations(RelNode, mq)} 对 fork 未专门处理的逻辑节点（LogicalJoin / LogicalAggregate /
 * LogicalUnion / LogicalCorrelate 等）返回 {@code null}（"未知"）；而仍 PARK 的 hbt 影子
 * {@link RelMdUtil#checkInputForCollationAndLimit} 直接 for-each {@code mq.collations(input)}
 * 未防 null —— 一旦在 {@code SORT_JOIN_TRANSPOSE} 路径上把这类逻辑节点当输入，即抛 NPE。
 *
 * <p>
 * 本测试锁定"输入为 null-collations 逻辑节点时 checkInputForCollationAndLimit 不崩溃"，
 * 归位前后逐值等价（影子旧行为 {@code []→不进循环} ≡ 修复后 {@code null→跳过}）。
 */
public class CollationNullGuardReproTest {

    private RelNode valuesInput(RelOptCluster cluster) {
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

    /** 构造 LogicalJoin：fork RelMdCollation 无 Join handler，必落 catch-all 返 null。 */
    private RelNode joinInput(RelOptCluster cluster) {
        RelNode left = valuesInput(cluster);
        RelNode right = valuesInput(cluster);
        RexBuilder rex = cluster.getRexBuilder();
        return LogicalJoin.create(left, right, ImmutableList.of(), rex.makeLiteral(true),
                ImmutableSet.of(), JoinRelType.INNER);
    }

    @Test
    public void checkInputForCollationAndLimit_NullCollationsInput_ValueEquivalentToEmptyList() {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        RelNode join = joinInput(cluster);
        RelMetadataQuery mq = cluster.getMetadataQuery();

        // 前提坐实：fork 1.42 catch-all 对 LogicalJoin 返回 null（影子旧版返回空列表，契约相反）
        Assertions.assertNull(mq.collations(join),
                "fork RelMdCollation catch-all 对 LogicalJoin 应返回 null（触发本复现的前提）");

        // 分支 A（旧影子 [] ≡ 修复后 null 跳过循环）：请求非空 collation，输入无已知 collation
        // → alreadySorted 起始 false 且 for-each 不进入（[] / null 皆然）→ false；
        //   fetch=null → alreadySmaller 恒 true → 整体确定性地为 false。加 null 防护前此处抛 NPE。
        RelCollation required = RelCollations.of(new RelFieldCollation(0, Direction.ASCENDING));
        boolean sortedFalse =
                RelMdUtil.checkInputForCollationAndLimit(mq, join, required, null, null);
        Assertions.assertFalse(sortedFalse,
                "null-collations 输入 + 非空请求 collation 应与旧 [] 语义逐值等价：alreadySorted=false → 整体 false（且不得抛 NPE）");

        // 分支 B：空 collation 请求 → alreadySorted 起始 true（与 collations() 返回 [] 或 null 无关）→
        //   fetch=null → alreadySmaller 恒 true → 整体确定性地为 true。锁定 null 防护未破坏空请求短路。
        boolean sortedTrue =
                RelMdUtil.checkInputForCollationAndLimit(mq, join, RelCollations.EMPTY, null, null);
        Assertions.assertTrue(sortedTrue,
                "空 collation 请求应逐值等价于旧 [] 语义：alreadySorted=true → 整体 true");
    }
}
