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
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.util.BuiltInMethod;

import java.util.List;
import java.util.Objects;

/**
 * Minus operator implemented in Mycat convention.
 */
public class MycatMinus extends Minus implements MycatRel {
    protected MycatMinus(RelOptCluster cluster, RelTraitSet traitSet,
                      List<RelNode> inputs, boolean all) {
        super(cluster,Objects.requireNonNull(traitSet).replace(MycatConvention.INSTANCE), inputs, all);
    }
    protected MycatMinus(RelInput input) {
        super(input);
    }
    public static MycatMinus create(RelTraitSet traitSet,
                      List<RelNode> inputs, boolean all) {
        return new MycatMinus(inputs.get(0).getCluster(),traitSet.replace(MycatConvention.INSTANCE),inputs,all);
    }
    public MycatMinus copy(RelTraitSet traitSet, List<RelNode> inputs,
                           boolean all) {
        return new MycatMinus(getCluster(), traitSet, inputs, all);
    }

    @Override
    public ExplainWriter explain(ExplainWriter writer) {
        writer.name("MycatMinus").into();
        for (RelNode input : getInputs()) {
            MycatRel rel = (MycatRel) input;
            rel.explain(writer);
        }
        return writer.ret();
    }

    @Override
    public Result implement(MycatEnumerableRelImplementor implementor, Prefer pref) {
        final BlockBuilder builder = new BlockBuilder();
        // MYCAT CHANGE (I-DEBT-59): minus/except 不重录行（只转发保留下来的子元素），属透传型 rel：
        // 声明必须逐字等于子节点实发形态，并以首个子形态归一其余子行；不得用三参 PhysTypeImpl.of
        // 的二次 optimize，否则 stream 腿单列容器行塌回 SCALAR，终结端把 Object[] 再包一层。
        JavaRowFormat targetFormat = null;
        PhysType physType = null;
        Expression minusExp = null;
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

            if (minusExp == null) {
                minusExp = childExp;
            } else {
                minusExp =
                        Expressions.call(minusExp,
                                BuiltInMethod.EXCEPT.method,
                                Expressions.list(childExp)
                                        .appendIfNotNull(physType.comparer())
                                        .append(Expressions.constant(all)));
            }

            // Once the first input has chosen its format, ask for the same for
            // other inputs.
            pref = pref.of(result.format);
        }

        builder.add(minusExp);
        return implementor.result(physType, builder.toBlock());
    }
    @Override
    public boolean isSupportStream() {
        return false;
    }
}