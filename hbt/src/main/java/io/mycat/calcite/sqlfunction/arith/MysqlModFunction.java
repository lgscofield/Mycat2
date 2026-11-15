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
package io.mycat.calcite.sqlfunction.arith;

import io.mycat.MycatDataContext;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.calcite.MycatScalarFunction;

import org.apache.calcite.mycat.MycatSqlDefinedFunction;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * I-DEBT-119：MySQL {@code MOD}/{@code %} 的 Mycat 自定义实现（值语义载体）。
 *
 * <p>经 {@code MycatCalciteRuntimeHooks.remapOperator} 在出码期把 {@code SqlStdOperatorTable.MOD}
 * 重映射到本函数（先例 {@link MysqlDivideFunction}）。真库口径：{@code 10 % 3 = 1}、
 * {@code 10.5 % 3 = 1.5}、{@code 1 % 0 = NULL} 且另产一条诊断 1365 {@code Division by 0}
 * （与 {@code /} 除法完全一致）——原生 {@code SqlFunctions.mod} 系返回原始类型无法表达 NULL、
 * 零除数直接 {@code ArithmeticException}，本地腿与远程腿语义分裂，本函数收口。
 *
 * <p>取整型路结果 SQL 型为 BIGINT（而非上游 {@code ReturnTypes.NULLABLE_MOD} 的 ARG1）：Mycat
 * 全栈整型运行表征恒 boxed Long（见 MysqlDivideFunctionTest 的 I-DEBT-60 注），BIGINT 与 Long
 * 对齐避免 codegen 装箱错配。DECIMAL 路沿用上游 {@code deriveDecimalModType}。
 */
public class MysqlModFunction extends MycatSqlDefinedFunction {

    public static ScalarFunction scalarFunction =
            MycatScalarFunction.create(MysqlModFunction.class, "mysqlMod", 2);

    public static final MysqlModFunction INSTANCE = new MysqlModFunction();

    public MysqlModFunction() {
        this(new MycatSqlDefinedFunction.GetOperandTypes(scalarFunction).invoke());
    }

    private MysqlModFunction(MycatSqlDefinedFunction.GetOperandTypes carried) {
        super("%", MysqlModFunction::deriveModType,
                carried.getOperandTypeInference(), carried.getOperandMetadata(),
                scalarFunction, SqlFunctionCategory.SYSTEM);
    }

    /**
     * MySQL {@code MOD}/{@code %} 的求值实现。参数声明为 {@code Object} 以免被宽松隐式 CAST
     * 强改成固定族，由本方法内部按运行时类型分派（对齐 {@code MysqlDivideFunction.mysqlDivide}）。
     *
     * @return 整型路/DECIMAL 路返回 {@code Long}/{@link BigDecimal}（sign 同被除数，Java 语义与真库一致）；
     *         近似型路返回 {@link Double}；任一操作数为 null 或除零返回 {@code null}。
     */
    public static Object mysqlMod(Object num, Object den) {
        if (num == null || den == null) {
            return null;
        }
        // 近似路：任一侧为浮点 → double 取模，除零 → NULL + 1365。
        if (num instanceof Double || num instanceof Float
                || den instanceof Double || den instanceof Float) {
            double d = ((Number) den).doubleValue();
            if (d == 0.0d) {
                emitDivisionByZeroWarning();
                return null;
            }
            return ((Number) num).doubleValue() % d;
        }
        // 精确路：BigDecimal divideAndRemainder 余数（对齐上游 SqlFunctions.mod(BigDecimal,BigDecimal)）。
        if (num instanceof BigDecimal || den instanceof BigDecimal
                || num instanceof BigInteger || den instanceof BigInteger) {
            BigDecimal bden = toBigDecimal(den);
            if (bden.signum() == 0) {
                emitDivisionByZeroWarning();
                return null;
            }
            return toBigDecimal(num).divideAndRemainder(bden)[1];
        }
        // 整型路（运行表征恒 Long，见 I-DEBT-60）。
        long lden = ((Number) den).longValue();
        if (lden == 0L) {
            // I-DEBT-119：真库 SELECT 1 % 0 → NULL + 1365；经 I-DEBT-97 诊断槽写入（槽空降级静默）。
            emitDivisionByZeroWarning();
            return null;
        }
        return ((Number) num).longValue() % lden;
    }

    /**
     * I-DEBT-119：除零诊断发射——读 {@code NewMycatDataContext.DIAGNOSTIC_CONTEXT_SLOT}，
     * 槽内挂有会话 {@link MycatDataContext} 时 {@code addWarning(Warning 1365 "Division by 0")}。
     * 三次防御同 {@code MysqlDivideFunction.emitDivisionByZeroWarning}（无槽静默、吞异常、NULL 短路不产告警）。
     */
    private static void emitDivisionByZeroWarning() {
        MycatDataContext ctx = NewMycatDataContext.currentDiagnosticContext();
        if (ctx == null) {
            return;
        }
        try {
            ctx.addWarning(new WarningMessage("Warning", 1365, "Division by 0"));
        } catch (Throwable e) {
            MycatDataContext.LOGGER.debug("I-DEBT-119: emit division-by-zero warning failed, degrade silently", e);
        }
    }

    private static BigDecimal toBigDecimal(Object x) {
        if (x instanceof BigDecimal) {
            return (BigDecimal) x;
        }
        if (x instanceof BigInteger) {
            return new BigDecimal((BigInteger) x);
        }
        return new BigDecimal(x.toString());
    }

    /**
     * {@code %} 结果 SQL 型：近似型 → DOUBLE；任一 DECIMAL → 上游 deriveDecimalModType；
     * 纯整型 → BIGINT（对齐 Long 运行表征，见类注释）。
     */
    private static RelDataType deriveModType(SqlOperatorBinding opBinding) {
        RelDataType t0 = opBinding.getOperandType(0);
        RelDataType t1 = opBinding.getOperandType(1);
        RelDataTypeFactory tf = opBinding.getTypeFactory();
        boolean nullable = t0.isNullable() || t1.isNullable();
        if (isApproximate(t0) || isApproximate(t1)) {
            return tf.createTypeWithNullability(tf.createSqlType(SqlTypeName.DOUBLE), nullable);
        }
        if (isDecimal(t0) || isDecimal(t1)) {
            RelDataType dec = tf.getTypeSystem().deriveDecimalModType(tf, t0, t1);
            return tf.createTypeWithNullability(dec, nullable);
        }
        return tf.createTypeWithNullability(tf.createSqlType(SqlTypeName.BIGINT), nullable);
    }

    private static boolean isApproximate(RelDataType type) {
        return SqlTypeFamily.APPROXIMATE_NUMERIC.contains(type);
    }

    private static boolean isDecimal(RelDataType type) {
        return type.getSqlTypeName() == SqlTypeName.DECIMAL;
    }

    @Override
    public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
        // 二元中缀 "%" 语法，保证任何 rel→SQL 重建（含下推还原）仍吐 a % b。
        org.apache.calcite.sql.SqlUtil.unparseBinarySyntax(this, call, writer, leftPrec, rightPrec);
    }
}
