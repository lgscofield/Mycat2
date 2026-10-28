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

package io.ordinate.engine.function.column;

import io.ordinate.engine.function.ScalarFunction;
import io.ordinate.engine.record.Record;
import io.ordinate.engine.schema.InnerType;

import java.math.BigDecimal;

/**
 * 向量腿 DECIMAL 列读取器（I-DEBT-67 第一刀①）。此前 {@code ExecuteCompiler.column} 对
 * {@link InnerType#DECIMAL_TYPE} 无实现（落 {@code break} 后直坠 {@code throw}），致 ordinate
 * 向量化执行器（{@code mycat:setVector} hint 态）无法读取任何 DECIMAL 源列。
 *
 * <p>本类补齐"读取一个已存在的十进制列"这条最小竖切：运行态经 {@link Record#getObject(int)} 从
 * Arrow {@code DecimalVector} 读回精确 {@link BigDecimal}（scale 由向量 Field 决定）。
 * 范围严格限定于列读取，不含除法 / CAST-to-DECIMAL（后续刀）。
 */
public class DecimalColumn implements ScalarFunction, ColumnFunction {
    private final int columnIndex;
    boolean isNull;

    public DecimalColumn(int columnIndex) {
        this.columnIndex = columnIndex;
    }

    public static DecimalColumn newInstance(int columnIndex) {
        return new DecimalColumn(columnIndex);
    }

    @Override
    public BigDecimal getDecimal(Record rec) {
        isNull = rec.isNull(columnIndex);
        if (isNull) {
            return null;
        }
        return (BigDecimal) rec.getObject(columnIndex);
    }

    @Override
    public InnerType getType() {
        return InnerType.DECIMAL_TYPE;
    }

    @Override
    public int getColumnIndex() {
        return columnIndex;
    }

    @Override
    public boolean isNull(Record rec) {
        return isNull;
    }
}
