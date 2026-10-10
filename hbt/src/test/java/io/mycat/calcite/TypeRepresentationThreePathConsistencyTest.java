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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 类型表征统一 · 靶 2 / INV-TRU-1 结构守卫：aggregate accumulator 路径与 argument 路径
 * 对同一列推导出的 java 类必须一致，且都等于 {@code getJavaClass} 真相源（INV-TRU-2）。
 *
 * <p>
 * arg 侧根在 fork {@code EnumUtils.toInternal(RelDataType, boolean)}：硬编码按 SqlTypeName
 * 反推（TIMESTAMP→long、DATE/TIME→int），绕开 typeFactory，与 acc 侧（{@code getJavaClass}
 * 经 {@code MycatRelDataTypeFactory} override 得 java.time）错配 →
 * {@code SqlFunctions.greater(LocalDateTime, long)} NoSuchMethod（case7，见
 * {@code docs/superpowers/specs/2026-10-10-q1-evidence.md}）。修法 = 时间类返回 null，
 * 让上层回退默认 storage type（{@code typeFactory.getJavaClass}）。
 *
 * <p>
 * {@code toInternal} 为 fork 包私有静态方法，测试与 codegen 运行时走同一推导函数，按类名
 * 反射触达（同 classpath，无模块壁垒）。null 返回值即 Calcite 既有契约语义：
 * "we don't care; use the default storage type"——本守卫把 null 解析回真相源 java 类再比对。
 *
 * <p>
 * 见 spec {@code docs/superpowers/specs/2026-10-10-type-representation-unification-design.md}。
 */
public class TypeRepresentationThreePathConsistencyTest {

    private static RelDataType sqlType(SqlTypeName typeName, boolean nullable) {
        RelDataType t = MycatCalciteSupport.TypeFactory.createSqlType(typeName);
        return MycatCalciteSupport.TypeFactory.createTypeWithNullability(t, nullable);
    }

    /** 反射调 fork 包私有 {@code EnumUtils.toInternal(RelDataType, boolean)}，原样返回（可能 null）。 */
    private static Object rawToInternal(RelDataType type) throws Exception {
        Class<?> enumUtils = Class.forName("org.apache.calcite.adapter.enumerable.EnumUtils");
        Method m = enumUtils.getDeclaredMethod("toInternal", RelDataType.class, boolean.class);
        m.setAccessible(true);
        return m.invoke(null, type, false);
    }

    /** arg 路径的“有效 storage type”：toInternal 返 null 时上层回退 getJavaClass 真相源。 */
    private static Type effectiveArgType(RelDataType type) throws Exception {
        Object internal = rawToInternal(type);
        return internal == null
                ? MycatCalciteSupport.TypeFactory.getJavaClass(type)
                : (Type) internal;
    }

    @Test
    public void aggregateArgumentAgreesWithAccumulatorForTimestamp() throws Exception {
        RelDataType ts = sqlType(SqlTypeName.TIMESTAMP, false);
        Type acc = MycatCalciteSupport.TypeFactory.getJavaClass(ts); // 真相源 = LocalDateTime
        Type arg = effectiveArgType(ts);
        Assertions.assertEquals(acc, arg,
                "aggregate arg 推导（toInternal→回退）必须与 acc 路径（getJavaClass）一致");
        Assertions.assertEquals(LocalDateTime.class, arg);
    }

    @Test
    public void aggregateArgumentAgreesWithAccumulatorForDate() throws Exception {
        RelDataType date = sqlType(SqlTypeName.DATE, false);
        Type acc = MycatCalciteSupport.TypeFactory.getJavaClass(date); // 真相源 = LocalDate
        Type arg = effectiveArgType(date);
        Assertions.assertEquals(acc, arg, "DATE 列 acc/arg 两路径必须一致");
        Assertions.assertEquals(LocalDate.class, arg);
    }

    @Test
    public void nonTemporalStorageTypeUnaffected() throws Exception {
        // 护栏：DECIMAL 等非时间类 toInternal 本为 null（默认 storage type 语义），
        // 上层按 getJavaClass 得 BigDecimal，行为不变——本改不得误伤。
        RelDataType dec = sqlType(SqlTypeName.DECIMAL, true);
        Assertions.assertNull(rawToInternal(dec), "非时间类维持 null（默认 storage type）");
        Assertions.assertEquals(java.math.BigDecimal.class,
                MycatCalciteSupport.TypeFactory.getJavaClass(dec));
    }
}
