package io.mycat.calcite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-39·tranche②（聚合连带面）：校验期 {@code MycatTypeSystem.deriveAvgAggType} 的 MySQL 口径类型派生锁。
 *
 * <p>跨分片归并 AVG 必走本地 {@code AGGREGATE_REDUCE}（部分 SUM/COUNT 归并后本地算最终 AVG＝含 DIVIDE 的
 * RexCall）。若外层 AVG 型定成 DOUBLE，则归并中间累加器塌成 Double，{@code remapOperator} 重映射出的
 * {@code MysqlDivideFunction} 拿到 Double 分子→命中近似路→返回<b>未舍入</b> double（如 {@code AVG(int)}=
 * {@code 3.6666666666666665}），偏离真 MySQL {@code 3.6667}（DECIMAL scale4）。
 * <p>本锁钉住修复口径：<b>精确数值→{@code DECIMAL(p, 参数scale+div_precision_increment)}</b>（与
 * {@link MysqlTypeSystemDivideTest 除法} 同口径，令归并内部除法走精确 BigDecimal 路）；近似数值→DOUBLE 不变。
 * scale 公式据真实 MySQL 5.7.44：{@code AVG(int)}→scale4、{@code AVG(decimal(20,4))}→scale8。
 */
class MysqlTypeSystemAvgTest {

    @Test
    void intArgDerivesDecimalScale4() {
        RelDataType i = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
        RelDataType out = new MycatCalciteSupport.MycatTypeSystem().deriveAvgAggType(
                MycatCalciteSupport.TypeFactory, i);
        assertEquals(SqlTypeName.DECIMAL, out.getSqlTypeName(), "AVG(int) 应派生 DECIMAL（对齐 MySQL 精确数值商）");
        assertEquals(4, out.getScale(), "AVG(int) scale 应=参数scale(0)+div_precision_increment(4)");
    }

    @Test
    void decimalScale4ArgDerivesScale8() {
        RelDataType d = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.DECIMAL, 20, 4);
        RelDataType out = new MycatCalciteSupport.MycatTypeSystem().deriveAvgAggType(
                MycatCalciteSupport.TypeFactory, d);
        assertEquals(SqlTypeName.DECIMAL, out.getSqlTypeName(), "AVG(decimal) 应派生 DECIMAL");
        assertEquals(8, out.getScale(), "AVG(decimal(20,4)) scale 应=4+4=8");
    }

    @Test
    void approximateArgDerivesDouble() {
        RelDataType dbl = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.DOUBLE);
        RelDataType out = new MycatCalciteSupport.MycatTypeSystem().deriveAvgAggType(
                MycatCalciteSupport.TypeFactory, dbl);
        assertEquals(SqlTypeName.DOUBLE, out.getSqlTypeName(), "AVG(double) 近似数值应保持 DOUBLE（MySQL 口径）");
    }

    @Test
    void bigintArgDerivesDecimalScale4() {
        RelDataType l = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.BIGINT);
        RelDataType out = new MycatCalciteSupport.MycatTypeSystem().deriveAvgAggType(
                MycatCalciteSupport.TypeFactory, l);
        assertTrue(out.getSqlTypeName() == SqlTypeName.DECIMAL && out.getScale() == 4,
                "AVG(bigint) 应派生 DECIMAL scale4，实得 " + out.getSqlTypeName() + "(" + out.getScale() + ")");
    }
}
