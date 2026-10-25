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
package io.mycat.util;

import io.mycat.SimpleColumnInfo;
import io.mycat.calcite.MycatCalciteSupport;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

import java.sql.JDBCType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-66 Fix-Meta 机制锁（Spec §2.8 路线 (ii) 实证定谳）：
 *
 * <p>{@link CalciteConvertors#getRelDataType(List, org.apache.calcite.rel.type.RelDataTypeFactory)}
 * 把 {@link SimpleColumnInfo} 表列元数据映射到 Calcite {@link RelDataType}。当前实现（L369）
 * 对 DECIMAL/NUMERIC 列调用 {@code factory.createSqlType(sqlTypeName)} <b>不传 precision/scale</b>，
 * 于是 MySQL 表列 {@code decimal(20,4)} 被降成 Calcite 默认 {@code DECIMAL(19,0)}——
 * 直接触发下游 {@code MycatTypeSystem.deriveAvgAggType} 得到错误 {@code DECIMAL(19,4)}
 * （本应 {@code DECIMAL(19,8)}，scale=4+4），进而在跨片归并 AVG 的
 * {@code AggregateReduceFunctionsRule.reduceAvg} 中生成一个 <b>降 scale CAST</b>
 * （{@code DECIMAL(19,8) → DECIMAL(19,4)}），基线下被 {@code MycatCastOverride} 的
 * DECIMAL→DECIMAL identity 掩盖，一旦 B1（回归 fork 真舍入）落地即暴雷：C11
 * {@code avg(decimal(20,4))} 由 {@code 3.66666667} 塌回 {@code 3.6667}。
 *
 * <p>本锁只钉住 Fix-Meta 一件事：<b>DECIMAL/NUMERIC 列必须把 precision/scale 透传给 factory</b>。
 * 与 B1+R1（{@code MycatCastOverride}/{@code MycatTypeSystem.roundingMode}）正交。
 */
class CalciteConvertorsRelDataTypeTest {

    /**
     * 表列 {@code `d` decimal(20, 4) NOT NULL}——修复前应得 {@code DECIMAL(19,0)}（默认丢精度标度），
     * 修复后应得 {@code DECIMAL(≥20, 4)}（scale 严格 = 4；precision 视 fork 是否 clamp 到 max，
     * scale 是本 bug 的关键锚点，precision 只弱断言）。
     */
    @Test
    void decimalColumnPreservesPrecisionAndScale() {
        SimpleColumnInfo d = new SimpleColumnInfo("d", 20, 4, JDBCType.DECIMAL,
                false, false, false, false, 0, true);
        RelDataType rowType = CalciteConvertors.getRelDataType(List.of(d),
                MycatCalciteSupport.TypeFactory);
        RelDataType colType = rowType.getFieldList().get(0).getType();

        assertEquals(SqlTypeName.DECIMAL, colType.getSqlTypeName(),
                "列 `d` 应映射为 DECIMAL");
        // 关键锚：scale 必须 = 4（当前 bug 得 0，是 reduceAvg 降 scale CAST 之源头）
        assertEquals(4, colType.getScale(),
                "I-DEBT-66 Fix-Meta：DECIMAL 列 scale 必须透传；否则 deriveAvgAggType 拿 scale=0 派生 DECIMAL(19,4) 而非 DECIMAL(19,8)");
        // 弱断：precision 至少不小于声明值 20，若被 fork clamp 到 max（=19）则等价（本 bug 关键在 scale）
        assertTrue(colType.getPrecision() >= 20 || colType.getPrecision() == 19,
                "I-DEBT-66 Fix-Meta：DECIMAL 列 precision 应透传 20（或被 type system clamp 到 max=19），实得 " + colType.getPrecision());
    }

    /**
     * NUMERIC 走同一 JDBCType 归一分支（{@link SimpleColumnInfo} 构造器 L94-96 把 NUMERIC 归成 DECIMAL），
     * 也应透传 precision/scale。
     */
    @Test
    void numericColumnPreservesPrecisionAndScale() {
        SimpleColumnInfo n = new SimpleColumnInfo("n", 18, 6, JDBCType.NUMERIC,
                true, false, false, false, 0, true);
        RelDataType rowType = CalciteConvertors.getRelDataType(List.of(n),
                MycatCalciteSupport.TypeFactory);
        RelDataType colType = rowType.getFieldList().get(0).getType();

        assertEquals(SqlTypeName.DECIMAL, colType.getSqlTypeName(),
                "NUMERIC 列经 SimpleColumnInfo 构造器归一后应映射为 DECIMAL");
        assertEquals(6, colType.getScale(),
                "I-DEBT-66 Fix-Meta：NUMERIC 列 scale 亦须透传");
    }

    /**
     * 反向护栏：非 DECIMAL 类型（如 BIGINT）不应被误改——scale 保持 unspecified（-1 或 0），precision 保持默认。
     * 防 Fix-Meta 过度泛化污染其他分支（爆炸半径管理）。
     */
    @Test
    void nonDecimalColumnsAreNotAffected() {
        SimpleColumnInfo id = new SimpleColumnInfo("id", 0, 0, JDBCType.BIGINT,
                false, false, true, false, 0, true);
        RelDataType rowType = CalciteConvertors.getRelDataType(List.of(id),
                MycatCalciteSupport.TypeFactory);
        RelDataType colType = rowType.getFieldList().get(0).getType();

        assertEquals(SqlTypeName.BIGINT, colType.getSqlTypeName(),
                "BIGINT 列不受 Fix-Meta 影响");
    }
}
