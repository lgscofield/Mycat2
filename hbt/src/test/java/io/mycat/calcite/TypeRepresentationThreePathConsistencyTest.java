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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Stream;

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

    /**
     * 全列对账矩阵（spec 附录 A · INV-TRU-1）：每个 SqlTypeName 的 accumulator 路径
     * （{@code getJavaClass} 真相源）与 aggregate argument 路径（{@code toInternal}→回退）
     * 必须都等于 Mycat 运行时真实 java 类。投影/排序路径同样以 {@code getJavaClass} 为
     * storage type 来源（{@code PhysTypeImpl}），故本矩阵即锁死三路径一致的单点真相源。
     *
     * <p>
     * 每类型两分支（Review Focus #3）：non-nullable 走 primitive（int/float/…），nullable
     * 走包装类（Integer/Float/…）——靶 1 FLOAT 的 {@code isNullable() ? Float.class :
     * float.class} 两分支都要被钉。
     *
     * <p>
     * unsigned 变体不在此列：Calcite {@code SqlTypeName} 层不区分符号（Mycat 的 signed/unsigned
     * 在 {@code MycatRelDataType} 直接给 Class，非经 SqlTypeName→getJavaClass），属值域/精度
     * 问题，与 BLOB 值域同类（spec 标 I-DEBT-27 不扩）。
     */
    static Stream<Arguments> columnTypeMatrix() {
        return Stream.of(
                // 布尔/整数（fork primitive 默认，Mycat 不接管）——[non-nullable, nullable]
                Arguments.of(SqlTypeName.BOOLEAN, false, boolean.class),
                Arguments.of(SqlTypeName.BOOLEAN, true, Boolean.class),
                Arguments.of(SqlTypeName.TINYINT, false, byte.class),
                Arguments.of(SqlTypeName.TINYINT, true, Byte.class),
                Arguments.of(SqlTypeName.SMALLINT, false, short.class),
                Arguments.of(SqlTypeName.SMALLINT, true, Short.class),
                Arguments.of(SqlTypeName.INTEGER, false, int.class),
                Arguments.of(SqlTypeName.INTEGER, true, Integer.class),
                Arguments.of(SqlTypeName.BIGINT, false, long.class),
                Arguments.of(SqlTypeName.BIGINT, true, Long.class),
                // 浮点：FLOAT=本轮靶 1 override（两分支皆须钉）；REAL/DOUBLE 保持 fork 默认
                Arguments.of(SqlTypeName.FLOAT, false, float.class),
                Arguments.of(SqlTypeName.FLOAT, true, Float.class),
                Arguments.of(SqlTypeName.REAL, false, float.class),
                Arguments.of(SqlTypeName.REAL, true, Float.class),
                Arguments.of(SqlTypeName.DOUBLE, false, double.class),
                Arguments.of(SqlTypeName.DOUBLE, true, Double.class),
                Arguments.of(SqlTypeName.DECIMAL, false, BigDecimal.class),
                Arguments.of(SqlTypeName.DECIMAL, true, BigDecimal.class),
                // 时间：I-DEBT-12 方向 A java.time override（override 在 nullable 判定之前，两支同值）
                Arguments.of(SqlTypeName.DATE, false, LocalDate.class),
                Arguments.of(SqlTypeName.DATE, true, LocalDate.class),
                Arguments.of(SqlTypeName.TIME, false, Duration.class),
                Arguments.of(SqlTypeName.TIME, true, Duration.class),
                Arguments.of(SqlTypeName.TIMESTAMP, false, LocalDateTime.class),
                Arguments.of(SqlTypeName.TIMESTAMP, true, LocalDateTime.class),
                // 字符（String 无 primitive/包装之分，两支同值）
                Arguments.of(SqlTypeName.CHAR, false, String.class),
                Arguments.of(SqlTypeName.CHAR, true, String.class),
                Arguments.of(SqlTypeName.VARCHAR, false, String.class),
                Arguments.of(SqlTypeName.VARCHAR, true, String.class));
        // BINARY/VARBINARY 不入矩阵：fork getJavaClass 返回 ByteString，与 Mycat 运行时
        // byte[]（MycatDataType.BINARY）不一致——真实漏格，但修复须联动 translateLiteral
        // 的 new_(ByteString) 字面量路径与结果端 getBytes，超本轮 override 边界，挂账
        // （关联 I-DEBT-27 case14 BLOB 值域族）。由下方 knownGap 测试钉住现状防静默漂移。
        // BIT 不入本矩阵：fork SqlTypeName 无 BIT，Types.BIT 归一 BOOLEAN，而 MySQL
        // BIT(n>1) 列运行时是 Long——声明/运行时错配挂账，由下方 knownGap 测试钉现状。
    }

    @Test
    public void knownGapBinaryColumnMapsToByteStringNotByteArray() throws Exception {
        // 记录已发现漏格（Task 4 全列对账实测）：现状 ByteString，非 Mycat 运行时 byte[]。
        // 挂账修复后本测试改为断言 byte[].class。
        RelDataType binary = sqlType(SqlTypeName.BINARY, false);
        Assertions.assertEquals(org.apache.calcite.avatica.util.ByteString.class,
                MycatCalciteSupport.TypeFactory.getJavaClass(binary),
                "BINARY 漏格现状钉住（挂账：与 MycatDataType.BINARY=byte[] 不一致，"
                        + "修复需联动字面量/结果端，见 ledger Task 4 Ruling）");
    }

    /**
     * BIT 漏格现状钉（final review #3 追认，实测收窄）。活链取证：MySQL BIT(n&gt;1) 列经
     * {@code MycatDataType.BIT→JDBCType.BIT→Types.BIT}，而本 fork 的 {@code SqlTypeName} 枚举
     * <b>无 BIT</b>，{@code JDBC_TYPE_TO_NAME} 把 {@code Types.BIT} 归 {@code BOOLEAN}
     * （SqlTypeName.java L300）→ codegen 面 {@code getJavaClass} 得 boolean/Boolean；
     * 但行运行时值是 {@code Long}（{@code MycatDataType.BIT.getJavaClass()=Long.TYPE}，
     * {@code BaseRowObservable.getObjects} case BIT）——与靶 1 FLOAT 同型的声明/运行时错配。
     * 本测试钉住"BIT 列在 codegen 面即 BOOLEAN 表征"这一现状事实。挂账不夹带：无 e2e 基线
     * 覆盖 BIT 列形状，修复方向（声明面扳 Long vs 行面扳 Boolean）需先定表征归属，另轮立债。
     * BIT(1) 行值本身是 Boolean，与 BOOLEAN 表征一致，不受影响。
     */
    @Test
    public void knownGapBitColumnIsRepresentedAsBooleanNotLong() throws Exception {
        // fork 无 SqlTypeName.BIT（枚举不存在），Types.BIT 归一为 BOOLEAN：
        RelDataType asBitColumn = sqlType(SqlTypeName.BOOLEAN, true);
        Assertions.assertEquals(Boolean.class,
                MycatCalciteSupport.TypeFactory.getJavaClass(asBitColumn),
                "BIT 列 codegen 面现状 = BOOLEAN 表征（挂账：行运行时是 Long，"
                        + "声明/运行时错配同 FLOAT 形状，见 ledger Final review Ruling）");
        // 现状钉的第二脚：fork SqlTypeName 枚举确无 BIT（若上游日后补 BIT 枚举，本脚变红提醒复核归一表）
        Assertions.assertThrows(NoSuchFieldException.class,
                () -> SqlTypeName.class.getDeclaredField("BIT"),
                "fork SqlTypeName 枚举应无 BIT（Types.BIT 归 BOOLEAN 的前提）");
    }

    /**
     * Review Focus #2 结构钉：MIN/MAX/ANY_VALUE 在 {@code RexImpTable} 注册同一
     * {@code MinMaxImplementor}——靶 2 修的 acc/arg 推导对该 implementor 生效即三聚合等价受益，
     * 本钉防"日后有人给某一支换实现器而类型守卫不再响"。{@code aggMap} 为私有字段，
     * 反射读取（同 classpath，无模块壁垒）。
     */
    @Test
    public void minMaxAnyValueShareSameAggImplementor() throws Exception {
        Class<?> rexImpTable = Class.forName("org.apache.calcite.adapter.enumerable.RexImpTable");
        Object table = rexImpTable.getField("INSTANCE").get(null);
        java.lang.reflect.Field aggMapField = rexImpTable.getDeclaredField("aggMap");
        aggMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<?, ?> aggMap = (java.util.Map<?, ?>) aggMapField.get(table);
        Class<?> expectedImpl = Class
                .forName("org.apache.calcite.adapter.enumerable.RexImpTable$MinMaxImplementor");
        for (org.apache.calcite.sql.SqlAggFunction agg
                : new org.apache.calcite.sql.SqlAggFunction[]{
                        org.apache.calcite.sql.fun.SqlStdOperatorTable.MIN,
                        org.apache.calcite.sql.fun.SqlStdOperatorTable.MAX,
                        org.apache.calcite.sql.fun.SqlStdOperatorTable.ANY_VALUE}) {
            Object supplier = aggMap.get(agg);
            Assertions.assertNotNull(supplier, agg + " 必须在 RexImpTable.aggMap 注册");
            // 经公共接口 java.util.function.Supplier 调用，避免反射包私有实现类的 IllegalAccessException
            Object implementor = ((java.util.function.Supplier<?>) supplier).get();
            Assertions.assertEquals(expectedImpl, implementor.getClass(),
                    agg + " 必须与 MIN/MAX/ANY_VALUE 共享 MinMaxImplementor（Review Focus #2）");
        }
    }

    @ParameterizedTest
    @MethodSource("columnTypeMatrix")
    public void allColumnTypesAgreeAcrossAccumulatorAndArgumentPaths(
            SqlTypeName typeName, boolean nullable, Class<?> expectedJavaClass) throws Exception {
        RelDataType type = sqlType(typeName, nullable);
        // 真相源：aggregate accumulator / projection / sort 都取此值
        Type acc = MycatCalciteSupport.TypeFactory.getJavaClass(type);
        Assertions.assertEquals(expectedJavaClass, acc,
                typeName + (nullable ? " (nullable)" : " (non-nullable)")
                        + " 的 getJavaClass 真相源必须是 Mycat 运行时类 " + expectedJavaClass);
        // arg 路径：toInternal→(null 回退)getJavaClass，须与真相源一致
        Type arg = effectiveArgType(type);
        Assertions.assertEquals(acc, arg,
                typeName + " 的 aggregate arg 路径必须与 accumulator 一致 (INV-TRU-1)");
    }
}
