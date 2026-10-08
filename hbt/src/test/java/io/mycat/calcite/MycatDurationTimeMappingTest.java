package io.mycat.calcite;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * I-DEBT-23e 回归锁：钉死 {@link MycatRelDataTypeFactory} 的 Java→SQL 正向映射对
 * {@code java.time.Duration} 收口为 TIME，与本工厂 SQL→Java 反向映射 {@code TIME→Duration}
 * （I-DEBT-12 方向 A）对称。
 *
 * <p>历史断层：fork 以 {@code Duration} 作 TIME 的内部表示，但 Calcite 默认
 * {@code JavaTypeFactoryImpl.createJavaType} 不识别 {@code Duration} → 落到 {@code OTHER}，
 * 而 {@code OTHER} 的 {@code getJavaClass=String} 不可被 coercion，导致
 * {@code SELECT CURTIME() + 0} 校验期抛 {@code Cannot apply '+' to '<OTHER> + <INTEGER>'}
 * （{@code PushDownFunctionShardingTableTest#testTimeFunction} 红）。
 *
 * <p>收口后：{@code Duration.class} 派生为 {@code TIME}，从而 TIME + NUMERIC 命中
 * {@code MycatCalciteSupport.getValidatorConfig} 的 {@code implicitCast(TIME,NUMERIC)=DOUBLE}，
 * {@code +0} 走通；且 OTHER 兜底（未知类→String）、DATE/TIMESTAMP 方向均不受影响（零回归）。
 */
public class MycatDurationTimeMappingTest {

    @Test
    public void durationMapsToTime() {
        RelDataType t = MycatCalciteSupport.TypeFactory.createJavaType(Duration.class);
        Assertions.assertEquals(SqlTypeName.TIME, t.getSqlTypeName(),
                "I-DEBT-23e: createJavaType(Duration) 必须为 TIME，否则 CURTIME()+0 落 OTHER 校验失败");
    }

    @Test
    public void timeDurationRoundTripIsSymmetric() {
        // 正向 Duration→TIME
        RelDataType time = MycatCalciteSupport.TypeFactory.createJavaType(Duration.class);
        Assertions.assertEquals(SqlTypeName.TIME, time.getSqlTypeName());
        // 反向 TIME→Duration（I-DEBT-12 方向 A），双向对称
        Object javaClass = MycatCalciteSupport.TypeFactory.getJavaClass(
                MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.TIME));
        Assertions.assertEquals(Duration.class, javaClass,
                "TIME 的 Java 表示须为 Duration，与正向映射对称");
    }

    @Test
    public void otherTemporalAndUnknownUnaffected() {
        // DATE→LocalDate、TIMESTAMP→LocalDateTime 方向不受 23e 改动波及
        Assertions.assertEquals(LocalDate.class,
                MycatCalciteSupport.TypeFactory.getJavaClass(
                        MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.DATE)));
        Assertions.assertEquals(LocalDateTime.class,
                MycatCalciteSupport.TypeFactory.getJavaClass(
                        MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.TIMESTAMP)));
        // OTHER 兜底仍为 String（未知类不落到本改动）
        Assertions.assertEquals(String.class,
                MycatCalciteSupport.TypeFactory.getJavaClass(
                        MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.OTHER)));
        // 非 Duration 的普通类仍走默认（Integer→INTEGER），不被拦截
        Assertions.assertEquals(SqlTypeName.INTEGER,
                MycatCalciteSupport.TypeFactory.createJavaType(Integer.class).getSqlTypeName());
    }
}
