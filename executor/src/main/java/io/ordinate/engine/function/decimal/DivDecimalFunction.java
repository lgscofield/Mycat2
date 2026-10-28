/*
 *     Copyright (C) <2021>  <Junwen Chen>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.ordinate.engine.function.decimal;

import io.ordinate.engine.function.Function;
import io.ordinate.engine.function.ScalarFunction;
import io.ordinate.engine.record.Record;
import io.ordinate.engine.schema.InnerType;
import org.apache.arrow.vector.types.pojo.ArrowType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;

/**
 * DECIMAL 除法的向量腿实现（I-DEBT-67 第二刀）。此前 {@code RexConverter} 的 {@code case DIVIDE} 恒走
 * {@code call("/")}，操作数类型被 {@code convertColumnType} 折成 DOUBLE，落到 {@code DivDoubleFunctionFactory}
 * 产出 double（{@code 5.0}）。本函数在结果 {@code RelDataType} 为 DECIMAL 时接管：按 MySQL 除法语义以
 * {@code div_precision_increment} 标度做 {@link BigDecimal} 除法（{@code HALF_UP}，远离零），除零返回 NULL。
 *
 * <p>覆写 {@link #getOutputArrowType()} 携带逐列 {@code Decimal(precision,scale)}，与
 * {@link CastToDecimalFunction} 协同使 {@code select avg(id)} 端到端产出 {@code 5.0000}。
 */
public class DivDecimalFunction implements ScalarFunction {

    private final ScalarFunction left;
    private final ScalarFunction right;
    private final int precision;
    private final int scale;
    private boolean isNull;

    public DivDecimalFunction(ScalarFunction left, ScalarFunction right, int precision, int scale) {
        this.left = left;
        this.right = right;
        this.precision = precision;
        this.scale = DecimalValues.normalizeDivScale(scale);
    }

    @Override
    public BigDecimal getDecimal(Record rec) {
        BigDecimal dividend = DecimalValues.toBigDecimal(left, rec);
        BigDecimal divisor = DecimalValues.toBigDecimal(right, rec);
        if (dividend == null || divisor == null || divisor.signum() == 0) {
            // MySQL：任一操作数为 NULL，或除数为 0，结果 NULL
            isNull = true;
            return null;
        }
        isNull = false;
        return dividend.divide(divisor, scale, RoundingMode.HALF_UP);
    }

    @Override
    public InnerType getType() {
        return InnerType.DECIMAL_TYPE;
    }

    @Override
    public ArrowType getOutputArrowType() {
        return new ArrowType.Decimal(precision, scale, DecimalValues.BYTE_WIDTH);
    }

    @Override
    public boolean isNull(Record rec) {
        return isNull;
    }

    @Override
    public boolean isConstant() {
        return left.isConstant() && right.isConstant();
    }

    @Override
    public List<Function> getArgs() {
        return Arrays.asList(left, right);
    }
}
