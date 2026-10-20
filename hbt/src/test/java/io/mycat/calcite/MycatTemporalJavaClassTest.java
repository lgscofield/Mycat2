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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * I-DEBT-12 方向 A + I-DEBT-60（类型表征统一）守卫：{@link MycatRelDataTypeFactory#getJavaClass}
 * 对时间类型必须返回 java.time（而非 fork 默认的 Integer/Long 内部数值表示），
 * 对整数类型必须返回 long/Long（对齐 BaseRowObservable 统一解码为 Long 的 wire 真相源）。
 *
 * <p>
 * 锁的是 codegen 裸列投影的 storage type 来源：物理行 {@code Object[]} 里时间列装的是 java.time
 * 对象（{@code BaseRowObservable.convertSqlValue}）、整数列装的是 Long
 * （{@code LONG_VALUE_FACTORY.createFromLong}）、结果端也按此读取（{@code MycatDataType}）。
 * 若本工厂仍吐 Integer/int，janino 生成码会强转 Integer 撞 Long → ClassCastException。
 *
 * <p>
 * 见 spec {@code docs/superpowers/specs/2026-10-07-i-debt-12-temporal-java-time-representation-design.md}
 * 及 I-DEBT-60 类型表征统一专轮。
 */
public class MycatTemporalJavaClassTest {

    private Type javaClassOf(SqlTypeName typeName) {
        RelDataType type = MycatCalciteSupport.TypeFactory.createSqlType(typeName);
        return MycatCalciteSupport.TypeFactory.getJavaClass(type);
    }

    @Test
    public void dateMapsToLocalDate() {
        Assertions.assertEquals(LocalDate.class, javaClassOf(SqlTypeName.DATE));
    }

    @Test
    public void timestampMapsToLocalDateTime() {
        Assertions.assertEquals(LocalDateTime.class, javaClassOf(SqlTypeName.TIMESTAMP));
        Assertions.assertEquals(LocalDateTime.class,
                javaClassOf(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE));
    }

    @Test
    public void timeMapsToDuration() {
        // 对齐 MycatDataType.TIME→Duration 与结果端 getTime()→(Duration)
        Assertions.assertEquals(Duration.class, javaClassOf(SqlTypeName.TIME));
        Assertions.assertEquals(Duration.class,
                javaClassOf(SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE));
    }

    @Test
    public void nonTemporalMappingsUnaffected() {
        // 护栏：时间类扳齐 java.time，整数组扳齐 boxed Long（wire 真相源，I-DEBT-60）。
        // 物理行 Object[] 对 TINYINT/SMALLINT/INTEGER 恒装 LONG_VALUE_FACTORY 产出的 boxed Long，
        // 故 getJavaClass 忽略 logical nullability 恒返回 Long.class（避免 primitive 槽遇 null 崩）。
        // BIGINT 本 case 未覆写，走 fork 默认（非空 primitive long），暂留观察。
        Assertions.assertEquals(Long.class, javaClassOf(SqlTypeName.INTEGER));
        Assertions.assertEquals(long.class, javaClassOf(SqlTypeName.BIGINT));
        Assertions.assertEquals(Long.class, javaClassOf(SqlTypeName.TINYINT));
        Assertions.assertEquals(Long.class, javaClassOf(SqlTypeName.SMALLINT));
        Assertions.assertEquals(String.class, javaClassOf(SqlTypeName.VARCHAR));
    }

    @Test
    public void integerTypesMapToLongForWireTrack() {
        // I-DEBT-60: BaseRowObservable.getObjects 对 TINYINT/SMALLINT/INTEGER/BIGINT
        // 统一返回 Long。getJavaClass 必须与之一致，否则 codegen 生成的 (Integer)/(int)
        // 强转与 Object[] 中实际 Long 值撞车 → CCE。
        RelDataType nullableInt = MycatCalciteSupport.TypeFactory
                .createTypeWithNullability(
                        MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER), true);
        Assertions.assertEquals(Long.class, MycatCalciteSupport.TypeFactory.getJavaClass(nullableInt));
    }
}
