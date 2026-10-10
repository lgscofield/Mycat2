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

import java.lang.reflect.Type;

/**
 * 类型表征统一 · 靶 1 守卫：{@link MycatRelDataTypeFactory#getJavaClass} 对 {@code SqlTypeName.FLOAT}
 * 必须返回 {@code Float.class}（nullable）/{@code float.class}（non-nullable），与 Mycat 运行时列值
 * {@code java.lang.Float} 对齐。
 *
 * <p>
 * fork {@code JavaTypeFactoryImpl.getJavaClass} 默认把 {@code FLOAT} 映射到 {@code Double.class}
 * （{@code // sic} 注释：SQL 标准 FLOAT≡DOUBLE），但 MySQL {@code float} 列经
 * {@code MycatMySQLRowMetaData}→{@code MycatDataType.FLOAT}→{@code Float.class}、
 * JDBC 返回 {@code java.lang.Float}。若本工厂仍吐 Double，janino 生成的裸列投影 codegen 会发
 * {@code (Double) Object[i]} 强转 {@code java.lang.Float} → ClassCastException（case6052）。
 * 与 {@code MycatTemporalJavaClassTest} 同构——本文件即钉死这条契约，防回归。
 *
 * <p>
 * 见 spec {@code docs/superpowers/specs/2026-10-10-type-representation-unification-design.md}。
 */
public class MycatFloatJavaClassTest {

    private Type javaClassOf(SqlTypeName typeName, boolean nullable) {
        RelDataType type = MycatCalciteSupport.TypeFactory.createSqlType(typeName);
        RelDataType adjusted = MycatCalciteSupport.TypeFactory
                .createTypeWithNullability(type, nullable);
        return MycatCalciteSupport.TypeFactory.getJavaClass(adjusted);
    }

    @Test
    public void floatNotNullMapsToPrimitiveFloat() {
        // createSqlType 默认非空（对齐 MycatTemporalJavaClassTest.nonTemporalMappingsUnaffected 的观察），
        // 非空数值分支返回 primitive（int.class/long.class/double.class），本例应返回 float.class。
        Assertions.assertEquals(float.class, javaClassOf(SqlTypeName.FLOAT, false));
    }

    @Test
    public void floatNullableMapsToFloatClass() {
        Assertions.assertEquals(Float.class, javaClassOf(SqlTypeName.FLOAT, true));
    }

    @Test
    public void doubleAndRealMappingsUnaffected() {
        // 护栏：本 override 只扳 FLOAT，DOUBLE/REAL 保持 fork 默认。
        Assertions.assertEquals(double.class, javaClassOf(SqlTypeName.DOUBLE, false));
        Assertions.assertEquals(float.class, javaClassOf(SqlTypeName.REAL, false));
    }
}
