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
import java.math.RoundingMode;

/**
 * I-DEBT-39·tranche①（宽）：MySQL `/` 除法的 Mycat 自定义实现（值语义载体）。
 *
 * <p>照 {@code <=>}→{@link io.mycat.calcite.sqlfunction.cmpfunction.StrictEqualFunction} 的既有先例，
 * 经 {@code MycatCalciteRuntimeHooks.remapOperator} 在出码期把 {@code SqlStdOperatorTable.DIVIDE}（即 MySQL
 * {@code /}）重映射到本函数，令本地计算：精确数值→十进制商（scale=被除数scale+div_precision_increment，
 * HALF_UP），除零→NULL，含近似型→double。{@code DIVIDE_INTEGER}（{@code DIV}）非本算子，天然不受影响。
 *
 * <p>口径据真实 MySQL 5.7.44 活库直连复拍：{@code 10/3=3.3333}、{@code 7/2=3.5000}、{@code 10.0/4=2.50000}、
 * {@code 1/0=NULL}。{@code div_precision_increment} 官方默认 4（范围 0–30，会话/全局可动态）；本票当常量 4，
 * 会话可变化为后续 tranche（见 spec §6）。
 */
public class MysqlDivideFunction extends MycatSqlDefinedFunction {

    /** MySQL 会话变量 {@code div_precision_increment} 的默认值（缺省 4）。 */
    public static final int DIV_PRECISION_INCREMENT = 4;

    public static ScalarFunction scalarFunction =
            MycatScalarFunction.create(MysqlDivideFunction.class, "mysqlDivide", 2);

    public static final MysqlDivideFunction INSTANCE = new MysqlDivideFunction();

    public MysqlDivideFunction() {
        this(new MycatSqlDefinedFunction.GetOperandTypes(scalarFunction).invoke());
    }

    private MysqlDivideFunction(MycatSqlDefinedFunction.GetOperandTypes carried) {
        super("/", MysqlDivideFunction::deriveDivideType,
                carried.getOperandTypeInference(), carried.getOperandMetadata(),
                scalarFunction, SqlFunctionCategory.SYSTEM);
    }

    /**
     * MySQL {@code /} 的求值实现。参数声明为 {@code Object} 以免被宽松隐式 CAST 强改成固定族，
     * 由本方法内部按运行时类型分派（对齐 {@code strictEqualAny(Object,Object)} 的写法）。
     *
     * <p>MYCAT CHANGE (I-DEBT-94/F5 → I-DEBT-97/F5) 设计要点：真库 {@code SELECT 1/0} 除返回 NULL
     * 外还会进一条诊断 1365 {@code Division by 0}（SHOW WARNINGS / @@warning_count 可见；默认
     * sql_mode 下 Level 为 SQLWARNING/"Warning"，见 {@code QueryBackendWarningsTest} 远程腿同码口径）。
     * 本地 codegen 除法经 remapOperator 出码为对本静态纯函数的无上下文调用
     * （{@code MysqlDivideFunction.mysqlDivide(a,b)}，见 MysqlDivideFunctionTest），拿不到会话
     * 诊断缓冲（dataContext）。I-DEBT-97 选方案B收口：执行器在 Bindable 求值边界（订阅边界，
     * 见 {@code PrepareExecutor} 的 I-DEBT-97 注释）把会话 {@link MycatDataContext} 装进
     * {@code NewMycatDataContext.DIAGNOSTIC_CONTEXT_SLOT}（{@code ThreadLocal}），本函数除零分支
     * 读槽写入 {@link #emitDivisionByZeroWarning()}——槽空（远程/异步消费线程、无槽执行路径）
     * 降级静默返 NULL，不抛不种槽。曾备选方案A（出码改 {@code root.mysqlDivide(a,b)} 触及
     * remap hook 与 codegen 模板，全算子回归面大）弃用；诊断槽线程串会话防线
     * （严格 try/finally + previous 恢复）见 {@code NewMycatDataContext} 的 I-DEBT-97 注释。
     * 值语义（除零→NULL）由 {@code MysqlDivideFunctionTest#divideByZeroYieldsNull} 与
     * {@code MysqlDivideDiagnosticSlotTest} 对照格锁定。
     *
     * @return 精确数值路返回 {@link BigDecimal}（scale=被除数scale+{@link #DIV_PRECISION_INCREMENT}，HALF_UP）；
     *         近似型路返回 {@link Double}；任一操作数为 null 或除零返回 {@code null}。
     */
    public static Object mysqlDivide(Object num, Object den) {
        if (num == null || den == null) {
            return null;
        }
        // 近似路：任一侧为浮点 → double 除法（MySQL double/整 → double），除零 → NULL。
        if (num instanceof Double || num instanceof Float
                || den instanceof Double || den instanceof Float) {
            double d = ((Number) den).doubleValue();
            if (d == 0.0d) {
                // I-DEBT-97/F5：真库此处另有 SQLWARNING 1365 "Division by 0" 入诊断区；经诊断槽写入
                // （槽空降级静默——远程/异步消费线程兼容），值语义（NULL）不变。
                emitDivisionByZeroWarning();
                return null;
            }
            return ((Number) num).doubleValue() / d;
        }
        // 精确路：BigDecimal 十进制商。
        BigDecimal bden = toBigDecimal(den);
        if (bden.signum() == 0) {
            // I-DEBT-97/F5：同上，经诊断槽写 1365 后返 NULL（槽空降级静默）。
            emitDivisionByZeroWarning();
            return null;
        }
        BigDecimal bnum = toBigDecimal(num);
        int scale = Math.max(bnum.scale() + DIV_PRECISION_INCREMENT, 0);
        return bnum.divide(bden, scale, RoundingMode.HALF_UP);
    }

    /**
     * MYCAT CHANGE (I-DEBT-97/F5)：除零诊断发射——读 {@code NewMycatDataContext.DIAGNOSTIC_CONTEXT_SLOT}
     * 诊断槽，槽内挂有会话 {@link MycatDataContext} 时 {@code addWarning(Warning 1365 "Division by 0")}
     * （口径对齐真库与远程腿 {@code QueryBackendWarningsTest} 的 {@code WarningMessage("Warning",1365,...)}），
     * 告警入会话缓冲后由既有 I-DEBT-69 读腿（SHOW WARNINGS / @@warning_count）自动带出。
     *
     * <p>三次防御保证"诊断通道不得反噬值语义/执行"：(1) 无槽返回 {@code null} 即静默降级，不抛不种槽；
     * (2) addWarning 自身异常（会话实现缺陷）被吞并 debug 记日志——告警腿故障不得把求值线程炸成
     * 语句失败；(3) 只在除数零且被除数非 {@code null} 的分支调用（NULL/0 短路不产告警，对齐真库）。
     * 每次除零发生各计一条（真库 {@code SELECT 1/0,1/0} → {@code @@warning_count}=2）。
     */
    private static void emitDivisionByZeroWarning() {
        MycatDataContext ctx = NewMycatDataContext.currentDiagnosticContext();
        if (ctx == null) {
            return;
        }
        try {
            ctx.addWarning(new WarningMessage("Warning", 1365, "Division by 0"));
        } catch (Throwable e) {
            MycatDataContext.LOGGER.debug("I-DEBT-97: emit division-by-zero warning failed, degrade silently", e);
        }
    }

    private static BigDecimal toBigDecimal(Object x) {
        if (x instanceof BigDecimal) {
            return (BigDecimal) x;
        }
        if (x instanceof BigInteger) {
            return new BigDecimal((BigInteger) x);
        }
        if (x instanceof Long || x instanceof Integer || x instanceof Short || x instanceof Byte) {
            return BigDecimal.valueOf(((Number) x).longValue());
        }
        return new BigDecimal(x.toString());
    }

    /**
     * {@code /} 结果 SQL 型：近似型 → DOUBLE；纯精确数值 → DECIMAL(scale=被除数scale+4)。
     * 与 {@code MycatTypeSystem.deriveDecimalDivideType}（Task 2）取值口径一致。
     */
    private static RelDataType deriveDivideType(SqlOperatorBinding opBinding) {
        RelDataType t0 = opBinding.getOperandType(0);
        RelDataType t1 = opBinding.getOperandType(1);
        RelDataTypeFactory tf = opBinding.getTypeFactory();
        boolean nullable = t0.isNullable() || t1.isNullable();
        if (isApproximate(t0) || isApproximate(t1)) {
            return tf.createTypeWithNullability(tf.createSqlType(SqlTypeName.DOUBLE), nullable);
        }
        int scale = Math.max(t0.getScale() + DIV_PRECISION_INCREMENT, 0);
        int precision = tf.getTypeSystem().getMaxPrecision(SqlTypeName.DECIMAL);
        RelDataType dec = tf.createSqlType(SqlTypeName.DECIMAL, precision, scale);
        return tf.createTypeWithNullability(dec, nullable);
    }

    private static boolean isApproximate(RelDataType type) {
        // 注：近似/精确型在 getFamily() 下均归声明族 NUMERIC，判子族归属用 SqlTypeFamily.contains，与 Task2 对齐。
        return SqlTypeFamily.APPROXIMATE_NUMERIC.contains(type);
    }

    @Override
    public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
        // 二元中缀 "/" 语法，保证任何 rel→SQL 重建（含下推还原）仍吐 a / b。
        org.apache.calcite.sql.SqlUtil.unparseBinarySyntax(this, call, writer, leftPrec, rightPrec);
    }
}
