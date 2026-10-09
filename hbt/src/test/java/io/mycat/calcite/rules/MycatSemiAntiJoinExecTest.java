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
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * SEMI/ANTI 去相关产物的物理路由验证（Task 3）。
 *
 * <p>去相关规则产出 {@code LogicalJoin(SEMI|ANTI)} 后，物理选型应把它交给你正确的半连接实现。
 * 活体 {@code explain}（2026-10-09）证明：ANIT join（{@code isSemiJoin()==false}）被
 * {@link MycatMergeJoinRule} 路由到 {@code MycatSortMergeJoin(joinType=[anti])}，其运行期码
 * {@code EnumerableDefaults.mergeJoin} 对连接键为 NULL 的左行会整批丢弃（仅 LEFT 豁免），
 * 而 {@code NOT EXISTS} 对左键为 NULL 的行必须输出——去相关前 LEFT Correlate+IS NULL 指示列路径是正确的。
 * 故 ANTI 不应走 merge join，须回落 hash/nested-loop 半连接（其 null 语义经源码核对正确）。
 */
public class MycatSemiAntiJoinExecTest {

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

    /** ANTI join 不应被路由到排序归并半连接（merge join 对 null 键左行会整批丢弃，NOT EXISTS 语义错）。 */
    @Test
    void antiIsNotRoutedToSortMergeJoin() {
        DrdsSqlCompiler.RBO_MERGE_JOIN = true; // 默认即 true，显式置位以明确前置条件
        RelBuilder rb = createRelBuilder();
        RelNode left = idScan(rb, "T");
        RelNode right = idScan(rb, "C");
        rb.push(left).push(right);
        // USING 等值连接：=($0, $1)（左 T.id = 右 C.id），与去相关产出的 ANTI Join 形态一致
        rb.join(JoinRelType.ANTI, "id");
        Join join = (Join) rb.build();
        Assertions.assertEquals(JoinRelType.ANTI, join.getJoinType(), "构树前提");

        RelNode physical = MycatMergeJoinRule.INSTANCE.tryMycatSortMergeJoin(join, false);
        Assertions.assertNull(physical,
                "ANTI 不应产出 MycatSortMergeJoin（merge 对 null 键左行丢弃），实际=" + physical);
    }
}
