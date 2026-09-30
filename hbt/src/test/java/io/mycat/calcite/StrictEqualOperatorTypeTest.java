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
package io.mycat.calcite;

import io.mycat.calcite.sqlfunction.cmpfunction.StrictEqualFunction;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.runtime.CalciteException;
import org.apache.calcite.runtime.Resources;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidatorException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-3 Task 1：删 hbt 的 SqlValidatorImpl 副本之前，先把它的 {@code <=>} 特判变成算子自带的推导能力。
 *
 * <p>
 * 副本的特判位于 deriveType（hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java:5856-5862）：
 * {@code operator == StrictEqualFunction.INSTANCE} 时返回
 * {@code MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.BOOLEAN)}。createSqlType 默认不可空，
 * 所以要保住的现值是 {@code BOOLEAN NOT NULL}，而不只是“类型是 BOOLEAN”——可空性位会进入下发给 MySQL 客户端的字段元数据。
 *
 * <p>
 * 那个特判按对象身份比较，而 MycatOperatorTable.lookupOperatorOverloads 与 MycatCalciteSupport.builtIn 会每次新建实例，
 * 那些实例在副本下走不到特判、只能落回 operator.deriveType(...)。把推导上提到算子自身之后，所有实例行为一致。
 *
 * <p>
 * 本探针故意不经过任何 validator，只问算子自己的 SqlReturnTypeInference，因此它与“当前运行期加载的是副本还是 fork”无关。
 */
public class StrictEqualOperatorTypeTest {

    @Test
    public void strictEqualOperatorDerivesBooleanNotNull() {
        RelDataType intType = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
        SqlReturnTypeInference inference = StrictEqualFunction.INSTANCE.getReturnTypeInference();
        Assertions.assertNotNull(inference, "<=> 必须自带返回类型推导");
        RelDataType derived = inference.inferReturnType(new TwoOperandBinding(intType));
        Assertions.assertEquals("BOOLEAN NOT NULL", derived.getFullTypeString(),
                "算子层推导必须与 hbt 副本特判同值，否则删副本即丢语义");
    }

    /**
     * 够小的 SqlOperatorBinding：只喂给 inferReturnType 两个 INTEGER 操作数，不借助任何 validator/call。
     */
    static class TwoOperandBinding extends SqlOperatorBinding {

        private final RelDataType operandType;

        TwoOperandBinding(RelDataType operandType) {
            super(MycatCalciteSupport.TypeFactory, StrictEqualFunction.INSTANCE);
            this.operandType = operandType;
        }

        @Override
        public int getOperandCount() {
            return 2;
        }

        @Override
        public RelDataType getOperandType(int ordinal) {
            return operandType;
        }

        @Override
        public CalciteException newError(Resources.ExInst<SqlValidatorException> e) {
            // 推导路径不会走到这里；只是抽象方法必须实现。
            return new CalciteException("probe-binding", null);
        }
    }
}
