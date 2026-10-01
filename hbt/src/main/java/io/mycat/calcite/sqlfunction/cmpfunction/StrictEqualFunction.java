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
package io.mycat.calcite.sqlfunction.cmpfunction;

import io.mycat.calcite.MycatCalciteSupport;
import io.mycat.calcite.MycatScalarFunction;

import org.apache.calcite.mycat.MycatSqlDefinedFunction;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

public class StrictEqualFunction extends MycatSqlDefinedFunction {
    public static ScalarFunction scalarFunction = MycatScalarFunction.create(StrictEqualFunction.class,
            "strictEqual", 2);
    public static StrictEqualFunction INSTANCE = new StrictEqualFunction();


    /**
     * MYCAT CHANGE (I-DEBT-3): 结果类型推导由算子自己携带。
     *
     * <p>
     * hbt 的 SqlValidatorImpl 副本在 deriveType 里对 {@code <=>} 特判返回
     * {@code createSqlType(SqlTypeName.BOOLEAN)}（不可空）；而沿用父类的
     * {@code CalciteCatalogReader.infer(function)} 只能得可空的 {@code BOOLEAN}（实测：删副本前的探针输出
     * {@code expected: <BOOLEAN NOT NULL> but was: <BOOLEAN>}）。可空性位会进入下发给 MySQL 客户端的字段元数据，
     * 所以下沉到这里的 inference 与副本同值，而不是只对齐 {@code SqlTypeName}。
     *
     * <p>
     * 副本的特判按对象身份比较（{@code operator == INSTANCE}），每次新建的实例走不到它；上提到 inference 后所有实例一致。
     * 操作数类型推断与元信息保持与父类 3 参构造器同样的取值方式（{@link MycatSqlDefinedFunction.GetOperandTypes}）。
     *
     * <p>
     * 仅上提 inference 不够：{@code SqlFunction.deriveType} 会走 {@code SqlUtil.lookupRoutine → canCastFrom}，
     * 对 NULL/未定型动态参数（SqlTypeName.OTHER）在 {@code SqlTypeMappingRule.canApplyFrom} 踩 assert 抛
     * {@code AssertionError: No assign rules for OTHER defined}（Task 5 fork 侧重采实测）。副本当年在
     * {@code DeriveTypeVisitor.visit(SqlCall)} 里直接 return BOOLEAN、从不对实参做类型指派校验，故不触发；
     * 这里覆写 {@code deriveType} 短路，忠实复刻副本语义（见下）。
     */
    public StrictEqualFunction() {
        this(new MycatSqlDefinedFunction.GetOperandTypes(scalarFunction).invoke());
    }

    private StrictEqualFunction(MycatSqlDefinedFunction.GetOperandTypes carried) {
        super("<=>", opBinding -> MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.BOOLEAN),
                carried.getOperandTypeInference(), carried.getOperandMetadata(), scalarFunction, SqlFunctionCategory.SYSTEM);
    }

    public static Boolean strictEqual(BigDecimal b0, BigDecimal b1) {
        if (b0 == null && b1 == null) {
            return true;
        }
        if (b0 == null && b1 != null) {
            return null;
        }
        if (b0 != null && b1 == null) {
            return null;
        }
        return b0.stripTrailingZeros().equals(b1.stripTrailingZeros());
    }

    public static Boolean strictEqualAny(Object left, Object right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null && right != null) {
            return null;
        }
        if (left != null && right == null) {
            return null;
        }
        return Objects.equals(left, right);
    }

    public static Boolean strictEqual(Object[] left, Object[] right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null && right != null) {
            return null;
        }
        if (left != null && right == null) {
            return null;
        }
        return Arrays.equals(left, right);
    }

    /**
     * MYCAT CHANGE (I-DEBT-3): 复刻 hbt 副本 {@code DeriveTypeVisitor.visit(SqlCall)} 的短路——
     * {@code <=>} 直接返回不可空 BOOLEAN，不进入父类 {@code SqlFunction.deriveType} 的 routine 查找与实参类型指派校验
     * （那会在 OTHER 类型上抛 {@code AssertionError: No assign rules for OTHER defined}）。
     */
    @Override
    public RelDataType deriveType(SqlValidator validator, SqlValidatorScope scope, SqlCall call) {
        return MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }

    @Override
    public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
        SqlUtil.unparseBinarySyntax(this, call, writer, leftPrec, rightPrec);
    }
}
