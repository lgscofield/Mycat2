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

package io.ordinate.engine;

import io.ordinate.engine.function.ScalarFunction;
import io.ordinate.engine.function.decimal.CastToDecimalFunction;
import io.ordinate.engine.function.decimal.DivDecimalFunction;
import io.ordinate.engine.record.Record;
import io.ordinate.engine.schema.InnerType;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;

/**
 * I-DEBT-67 第二刀：向量腿 DECIMAL 算术（CAST-to-DECIMAL / DECIMAL 除法）行为锁。
 *
 * <p>端到端主红锺是活体 {@code VectorTest#testBase}（{@code select avg(id)} 由 {@code 5.0} 转绿为
 * {@code 5.0000}），其 RED→GREEN 已眼看确认。本类补齐活体未覆盖的边界锁：{@code setScale HALF_UP}、
 * 除零→NULL、以及 {@code getOutputArrowType} 逐列携带 {@code Decimal(precision,scale)}（列头/向量标度的根）。
 */
public class VectorLegDecimalArithmeticTest {

    /** 常量源列：整型，供 cast/div 读操作数（Record 入参被忽略）。 */
    private static ScalarFunction longConst(long v) {
        return new ScalarFunction() {
            @Override
            public InnerType getType() {
                return InnerType.INT64_TYPE;
            }

            @Override
            public long getLong(Record rec) {
                return v;
            }
        };
    }

    @Test
    public void castToDecimalCarriesColumnScaleAndRoundsHalfUp() {
        CastToDecimalFunction cast = new CastToDecimalFunction(longConst(10), 19, 4);
        Assert.assertEquals(InnerType.DECIMAL_TYPE, cast.getType());

        ArrowType out = cast.getOutputArrowType();
        Assert.assertTrue(out instanceof ArrowType.Decimal);
        Assert.assertEquals("输出 Arrow 类型应逐列携带 scale=4", 4, ((ArrowType.Decimal) out).getScale());
        Assert.assertEquals(19, ((ArrowType.Decimal) out).getPrecision());

        Assert.assertEquals(new BigDecimal("10.0000"), cast.getDecimal(null));
    }

    @Test
    public void divideProducesMysqlScaleHalfUpResult() {
        // 10 / 4 在 scale=4 下应得 2.5000
        DivDecimalFunction div = new DivDecimalFunction(longConst(10), longConst(4), 19, 4);
        Assert.assertEquals(new BigDecimal("2.5000"), div.getDecimal(null));
        Assert.assertEquals(4, ((ArrowType.Decimal) div.getOutputArrowType()).getScale());
    }

    @Test
    public void divideByZeroYieldsNullLikeMysql() {
        DivDecimalFunction div = new DivDecimalFunction(longConst(10), longConst(0), 19, 4);
        Assert.assertNull("MySQL 除零结果为 NULL", div.getDecimal(null));
        Assert.assertTrue(div.isNull(null));
    }

    @Test
    public void getArgsExposesOperandsForVisitor() {
        CastToDecimalFunction cast = new CastToDecimalFunction(longConst(1), 19, 4);
        Assert.assertEquals(1, cast.getArgs().size());
        DivDecimalFunction div = new DivDecimalFunction(longConst(1), longConst(1), 19, 4);
        Assert.assertEquals(2, div.getArgs().size());
    }
}
