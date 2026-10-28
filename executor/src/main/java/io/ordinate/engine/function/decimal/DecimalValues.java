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
import io.ordinate.engine.record.Record;

import java.math.BigDecimal;

/**
 * 十进制向量腿的公共求值助手（I-DEBT-67 第二刀）。把任意标量操作数经 {@link Function#getAsObject(Record)}
 * 归一为 {@link BigDecimal}（{@code getAsObject} 对 {@code DECIMAL_TYPE} 走 {@code getDecimal}、整型走
 * {@code getLong}、浮点走 {@code getDouble}，并对空值返回 {@code null}），避免各 decimal 函数重复分派。
 */
public final class DecimalValues {

    /** Arrow 128 位十进制尾数（precision &le; 38），与既有 DecimalVector 分配一致。 */
    static final int BYTE_WIDTH = 128;

    /** MySQL {@code div_precision_increment} 默认标度，用于除法结果类型缺标度时兜底。 */
    static final int DEFAULT_DIV_SCALE = 4;

    private DecimalValues() {
    }

    static BigDecimal toBigDecimal(Function function, Record rec) {
        Object value = function.getAsObject(rec);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number || value instanceof CharSequence) {
            return new BigDecimal(value.toString().trim());
        }
        return new BigDecimal(value.toString().trim());
    }

    /** CAST 目标标度归一：Calcite 无标度哨兵（{@link Integer#MIN_VALUE}）视作 0，其余原样保留。 */
    static int normalizeCastScale(int scale) {
        return scale == Integer.MIN_VALUE || scale < 0 ? 0 : scale;
    }

    /** 除法结果标度兜底：非正标度回落到 {@code div_precision_increment} 默认。 */
    static int normalizeDivScale(int scale) {
        return scale <= 0 ? DEFAULT_DIV_SCALE : scale;
    }
}
