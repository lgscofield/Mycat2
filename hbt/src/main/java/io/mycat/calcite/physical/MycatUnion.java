/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite.physical;


import io.mycat.calcite.*;
import io.reactivex.rxjava3.core.Observable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.RxBuiltInMethod;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Union operator implemented in Mycat convention.
 */
public class MycatUnion extends Union implements MycatRel {
    protected MycatUnion(
            RelOptCluster cluster,
            RelTraitSet traitSet,
            List<RelNode> inputs,
            boolean all) {
        super(cluster, Objects.requireNonNull(traitSet).replace(MycatConvention.INSTANCE), inputs, all);
    }

    public MycatUnion(RelInput input) {
        super(input);
    }

    public static MycatUnion create(
            RelTraitSet traitSet,
            List<RelNode> inputs,
            boolean all) {
        return new MycatUnion(inputs.get(0).getCluster(), traitSet.replace(MycatConvention.INSTANCE), inputs, all);
    }

    public MycatUnion copy(
            RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
        return new MycatUnion(getCluster(), traitSet, inputs, all);
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner,
                                      RelMetadataQuery mq) {
        return super.computeSelfCost(planner, mq).multiplyBy(.1);
    }


    @Override
    public ExplainWriter explain(ExplainWriter writer) {
        writer.name("MycatUnion").into();
        for (RelNode input : getInputs()) {
            MycatRel rel = (MycatRel) input;
            rel.explain(writer);
        }
        return writer.ret();
    }

    @Override
    public Result implement(MycatEnumerableRelImplementor implementor, Prefer pref) {
        final BlockBuilder builder = new BlockBuilder();
        // MYCAT CHANGE (I-DEBT-59): union 是透传型 rel（concat/union 只转发子元素，不重录行），
        // 声明必须逐字等于子节点实发形态：以首个子的形态为目标，其余子行用 PhysType.convertTo 归一
        // （同形态时原样返回，不产生额外代码）。不得用三参 PhysTypeImpl.of 的二次 optimize——否则
        // stream 腿的单列容器行会塌成 SCALAR，终结端把整个 Object[] 再包一层（活库铁证：
        // select ... union all select ... limit 3 读到 [Ljava.lang.Object;@...）。
        // 详见 StreamMycatEnumerableRelImplementor#passThroughPhysType。
        JavaRowFormat targetFormat = null;
        PhysType physType = null;
        Expression unionExp = null;
        for (Ord<RelNode> ord : Ord.zip(inputs)) {
            EnumerableRel input = (EnumerableRel) ord.e;
            final Result result = implementor.visitChild(this, ord.i, input, pref);
            if (targetFormat == null) {
                targetFormat = result.format;
                physType = StreamMycatEnumerableRelImplementor.passThroughPhysType(
                        implementor.getTypeFactory(), getRowType(), targetFormat);
            }
            Expression childExp =
                    toEnumerate(builder.append(
                            "child" + ord.i,
                            result.block));
            childExp = result.physType.convertTo(childExp, targetFormat);

            if (unionExp == null) {
                unionExp = childExp;
            } else {
                unionExp = all
                        ? Expressions.call(RxBuiltInMethod.ENUMERABLE_OBSERVABLE_CONCAT.method, unionExp, childExp)
                        : Expressions.call(unionExp,
                        BuiltInMethod.UNION.method,
                        Expressions.list(childExp)
                                .appendIfNotNull(physType.comparer()));
            }
        }

        builder.add(unionExp);
        return implementor.result(physType, builder.toBlock());
    }

    @Override
    public boolean isSupportStream() {
        return all;
    }

    @Override
    public Result implementStream(StreamMycatEnumerableRelImplementor implementor, Prefer pref) {
        final BlockBuilder builder = new BlockBuilder();
        Expression unionExp = null;

        ArrayList<Result> results = new ArrayList<>();
        for (Ord<RelNode> ord : Ord.zip(inputs)) {
            EnumerableRel input = (EnumerableRel) ord.e;
            results.add(implementor.visitChild(this, ord.i, input, pref));
        }
        boolean toEnumerate = !results.stream().allMatch(result -> result.block.getType().getTypeName().contains("Observable"));
        if (toEnumerate) {
            // MYCAT CHANGE (I-DEBT-59, 含 M-1 归一): 本支子腿形态可能不一致。若混有 Observable 腿
            // （RxJava3 禁 null，其行按运行时契约恒为容器行 ARRAY），则 union 输出仍是 Observable，元素
            // 类型必须统一为容器行 → 目标形态取 ARRAY，并把 Enumerable 标量子腿 convertTo 归一（装箱对
            // Enumerable 合法；Observable 子已 ARRAY，convertTo 见同形态原样返回、不会给 Observable 套
            // Enumerable SELECT 错码，见 PhysTypeImpl#convertTo）。若全为 Enumerable 腿（无 Observable），
            // 则沿用首子形态并归一其余（同 implement() 的透传不变式：纯分片 View union 保持标量、不被硬抬
            // 成容器行——见锁 E）。否则 enumerableOrObservableConcat 会混装 Object[] 与 String，消费端按根
            // 声明 Object[] 解 String → 运行期 ClassCastException（离线 codegen 铁证见 spec §2.3 / M-1）。
            final boolean hasObservableChild = results.stream()
                    .anyMatch(r -> r.block.getType().getTypeName().contains("Observable"));
            final JavaRowFormat targetFormat =
                    hasObservableChild ? JavaRowFormat.ARRAY : results.get(0).format;
            final PhysType physType = StreamMycatEnumerableRelImplementor.passThroughPhysType(
                    implementor.getTypeFactory(), getRowType(), targetFormat);
            for (Ord<Result> ord : Ord.zip(results)) {
                Result result = ord.e;
                Expression childExp =
                        toEnumerate(builder.append(
                                "child" + ord.i,
                                result.block));
                // M-1: 各子腿归一到统一目标形态，保证声明==实发（同形态时 convertTo 原样返回）
                childExp = result.physType.convertTo(childExp, targetFormat);
                if (unionExp == null) {
                    unionExp = childExp;
                } else {
                    unionExp = all
                            ? Expressions.call(RxBuiltInMethod.ENUMERABLE_OBSERVABLE_CONCAT.method, unionExp, childExp)
                            : Expressions.call(unionExp,
                            BuiltInMethod.UNION.method,
                            Expressions.list(childExp)
                                    .appendIfNotNull(physType.comparer()));
                }
            }
            builder.add(unionExp);
            return implementor.result(physType, builder.toBlock());
        } else {
            for (Ord<Result> ord : Ord.zip(results)) {
                Result result = ord.e;
                Expression childExp =
                        (builder.append(
                                "child" + ord.i,
                                result.block));
                if (unionExp == null) {
                    unionExp = childExp;
                } else {
                    unionExp = Expressions.call(RxBuiltInMethod.OBSERVABLE_UNION_ALL.method, unionExp, childExp);
                }
            }
            builder.add(unionExp);
            // MYCAT CHANGE (I-DEBT-59): 两支子节点都已是 Observable（本 rel 的 toEnumerate 在
            // isSupportStream()=true 时是空操作）。Observable 态禁 null，而该态的行按运行时契约恒为
            // 容器行（getObservable 返 Observable<Object[]>；MycatCalc.implementStream 经落点 1 也恒发
            // Object[]），故仍强制容器行；混态残留风险见 spec §5。
            final PhysType physType = StreamMycatEnumerableRelImplementor.streamRowPhysType(
                    implementor.getTypeFactory(),
                    getRowType(),
                    pref, JavaRowFormat.ARRAY);
            return implementor.result(physType, builder.toBlock());
        }
    }
}