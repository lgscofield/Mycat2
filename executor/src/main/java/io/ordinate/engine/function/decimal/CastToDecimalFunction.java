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
import java.util.Collections;
import java.util.List;

/**
 * CAST-to-DECIMAL 的向量腿实现（I-DEBT-67 第二刀）。此前 {@code RexConverter} 把 DECIMAL
 * 系统性折叠为 DOUBLE（{@code convertColumnType} 中 {@code case DECIMAL: return DOUBLE_TYPE}），
 * 致 {@code mycat:setVector} hint 态下 {@code select avg(id)} 的 {@code CAST(x AS DECIMAL(19,4))}
 * 投成 double，最终列值塌为 {@code 5.0} 而非 {@code 5.0000}。
 *
 * <p>本函数按 Calcite {@code RelDataType} 携带的逐列 {@code precision/scale} 构造，覆写
 * {@link #getOutputArrowType()} 返回 {@code Decimal(precision,scale)}，使 Project 输出 schema、
 * DecimalVector 分配标度与列头 scale 忠实。求值经 {@link DecimalValues#toBigDecimal} 取操作数值后
 * {@code setScale(scale, HALF_UP)}。范围限定于 CAST-to-DECIMAL，不含通用算术（见 {@link DivDecimalFunction}）。
 */
public class CastToDecimalFunction implements ScalarFunction {

    private final ScalarFunction arg;
    private final int precision;
    private final int scale;
    private boolean isNull;

    public CastToDecimalFunction(ScalarFunction arg, int precision, int scale) {
        this.arg = arg;
        this.precision = precision;
        this.scale = DecimalValues.normalizeCastScale(scale);
    }

    @Override
    public BigDecimal getDecimal(Record rec) {
        BigDecimal value = DecimalValues.toBigDecimal(arg, rec);
        if (value == null) {
            isNull = true;
            return null;
        }
        isNull = false;
        return value.setScale(scale, java.math.RoundingMode.HALF_UP);
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
        return arg.isConstant();
    }

    @Override
    public List<Function> getArgs() {
        return Collections.singletonList(arg);
    }
}
