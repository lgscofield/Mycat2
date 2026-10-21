package io.mycat.calcite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-39·tranche①（宽）：校验期 {@code MycatTypeSystem.deriveDecimalDivideType} 的 MySQL 口径类型派生锁。
 *
 * <p>决定本地 {@code /} 结果对外声明的列头类型/scale（客户端无缝的一环，非仅值对）：纯精确数值→
 * {@code DECIMAL(p, 被除数scale+div_precision_increment)}；近似型不扳 DECIMAL，交回 super→DOUBLE。
 * scale 公式据真实 MySQL 5.7.44 活库直连复拍（10/3 scale4、10.0/… scale5）。
 */
class MysqlTypeSystemDivideTest {

    @Test
    void intIntDerivesDecimalScale4() {
        RelDataType i = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
        RelDataType out = new MycatCalciteSupport.MycatTypeSystem()
                .deriveDecimalDivideType(MycatCalciteSupport.TypeFactory, i, i);
        assertEquals(SqlTypeName.DECIMAL, out.getSqlTypeName());
        assertEquals(4, out.getScale());
    }

    @Test
    void decimalScale1DerivesScale5() {
        RelDataType d = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.DECIMAL, 10, 1);
        RelDataType i = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
        RelDataType out = new MycatCalciteSupport.MycatTypeSystem()
                .deriveDecimalDivideType(MycatCalciteSupport.TypeFactory, d, i);
        assertEquals(SqlTypeName.DECIMAL, out.getSqlTypeName());
        assertEquals(5, out.getScale());
    }

    @Test
    void approximateDerivesNonDecimal() {
        RelDataType dbl = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.DOUBLE);
        RelDataType i = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
        RelDataType out = new MycatCalciteSupport.MycatTypeSystem()
                .deriveDecimalDivideType(MycatCalciteSupport.TypeFactory, dbl, i);
        // 近似不扳 DECIMAL，交回 super→DOUBLE（或 null）。
        assertTrue(out == null || out.getSqlTypeName() != SqlTypeName.DECIMAL,
                "近似型除法不得派生成 DECIMAL，实得 " + (out == null ? "null" : out.getSqlTypeName()));
    }
}
