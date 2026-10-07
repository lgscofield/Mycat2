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
 * I-DEBT-12 方向 A 守卫：{@link MycatRelDataTypeFactory#getJavaClass} 对时间类型必须返回
 * java.time，而非 fork {@code JavaTypeFactoryImpl} 默认的内部数值表示（DATE→Integer、
 * TIMESTAMP→Long）。
 *
 * <p>
 * 锁的是 codegen 裸列投影的 storage type 来源：物理行 {@code Object[]} 里时间列装的是 java.time
 * 对象（{@code BaseRowObservable.convertSqlValue}）、结果端也按 java.time 读（{@code MycatDataType}、
 * {@code AbstractObjectRowIterator.getTimestamp}）。若本工厂仍吐 Integer/Long，janino 生成码会对
 * java.time 值强转 {@code (Long)} → ClassCastException（case1）、DATE 走 {@code SqlFunctions.toInt}
 * → Cannot convert（testCastFunction）。此测试即钉死这条契约，防回归。
 *
 * <p>
 * 见 spec {@code docs/superpowers/specs/2026-10-07-i-debt-12-temporal-java-time-representation-design.md}。
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
        // 护栏：只扳时间，其它类型映射必须保持 fork 默认。
        // 注意 createSqlType 默认非空，fork 对非空数值返回 primitive（int.class/long.class）。
        Assertions.assertEquals(int.class, javaClassOf(SqlTypeName.INTEGER));
        Assertions.assertEquals(long.class, javaClassOf(SqlTypeName.BIGINT));
        Assertions.assertEquals(String.class, javaClassOf(SqlTypeName.VARCHAR));
    }
}
