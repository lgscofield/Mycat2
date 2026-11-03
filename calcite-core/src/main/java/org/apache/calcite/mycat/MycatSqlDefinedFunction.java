/*
 * MYCAT ADDITION
 * This class does not exist in Calcite 1.42.0.
 * Purpose: Mycat custom SQL function base class with type inference support
 * Reason for org.apache.calcite package: Needs access to package-private members in Calcite SQL/schema packages
 */
package org.apache.calcite.mycat;

import org.apache.calcite.adapter.enumerable.CallImplementor;
import org.apache.calcite.adapter.enumerable.RexImpTable;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.schema.ImplementableFunction;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.*;
import org.apache.calcite.util.Util;

import java.util.List;
import java.util.Objects;

import static org.apache.calcite.jdbc.JavaTypeFactoryImpl.toSql;

public abstract class MycatSqlDefinedFunction extends SqlFunction implements CallImplementor {

    final ScalarFunction function;

    public MycatSqlDefinedFunction(String name,
                                   SqlReturnTypeInference returnTypeInference,
                                   SqlOperandTypeInference operandTypeInference,
                                   SqlOperandTypeChecker operandTypeChecker,
                                   ScalarFunction function,
                                   SqlFunctionCategory category) {
        super(name,
                new SqlIdentifier(name, SqlParserPos.ZERO),
                SqlKind.OTHER_FUNCTION,
                returnTypeInference,
                operandTypeInference,
                operandTypeChecker,
                category);
        this.function = function;
    }

    public MycatSqlDefinedFunction(String name,
                                   ScalarFunction function,
                                   SqlFunctionCategory category) {
        this(name, new GetOperandTypes(function).invoke(), category);
    }


    public MycatSqlDefinedFunction(String name,
                                   GetOperandTypes getOperandTypes,
                                   SqlFunctionCategory category) {
        this(name, getOperandTypes.getSqlReturnTypeInference(),
                getOperandTypes.getOperandTypeInference(),
                getOperandTypes.getOperandMetadata(), getOperandTypes.function, category);
    }

    public String getName() {
        return this.getSqlIdentifier().getSimple();
    }

    public static class GetOperandTypes {
        private ScalarFunction function;
        private SqlOperandTypeInference operandTypeInference;
        private SqlOperandMetadata operandMetadata;

        public GetOperandTypes(ScalarFunction function) {
            this.function = Objects.requireNonNull(function);
        }

        public SqlOperandTypeInference getOperandTypeInference() {
            return operandTypeInference;
        }

        public SqlOperandTypeChecker getOperandMetadata() {
            return operandMetadata;
        }

        public GetOperandTypes invoke() {
            final java.util.function.Function<RelDataTypeFactory, List<RelDataType>> argTypesFactory =
                    typeFactory -> function.getParameters()
                            .stream()
                            .map(o -> o.getType(typeFactory))
                            .collect(Util.toImmutableList());
            final java.util.function.Function<RelDataTypeFactory, List<SqlTypeFamily>> typeFamiliesFactory =
                    typeFactory -> argTypesFactory.apply(typeFactory)
                            .stream()
                            .map(type ->
                                    Util.first(type.getSqlTypeName().getFamily(),
                                            SqlTypeFamily.ANY))
                            .collect(Util.toImmutableList());
            final java.util.function.Function<RelDataTypeFactory, List<RelDataType>> paramTypesFactory =
                    typeFactory ->
                            argTypesFactory.apply(typeFactory)
                                    .stream()
                                    .map(type -> toSql(typeFactory, type))
                                    .collect(Util.toImmutableList());

            // Use a short-lived type factory to populate "typeFamilies" and "argTypes".
            // SqlOperandMetadata.paramTypes will use the real type factory, during
            // validation.
            final RelDataTypeFactory dummyTypeFactory = new JavaTypeFactoryImpl();
            final List<RelDataType> argTypes = argTypesFactory.apply(dummyTypeFactory);
            final List<SqlTypeFamily> typeFamilies =
                    typeFamiliesFactory.apply(dummyTypeFactory);

            operandTypeInference = InferTypes.explicit(argTypes);

            operandMetadata = OperandTypes.operandMetadata(typeFamilies, paramTypesFactory,
                    i -> function.getParameters().get(i).getName(),
                    i -> function.getParameters().get(i).isOptional());
            return this;
        }

        public SqlReturnTypeInference getSqlReturnTypeInference() {
            return CalciteCatalogReader.infer(function);
        }
    }

    public ScalarFunction getFunction() {
        return function;
    }

    @Override
    public Expression implement(RexToLixTranslator translator, RexCall call, RexImpTable.NullAs nullAs) {
        return ((ImplementableFunction)Objects.requireNonNull(function)).getImplementor().implement(translator, call, nullAs);
    }

    /**
     * MYCAT CHANGE (I-DEBT-23): MySQL 宽松隐式跨族 operand coercion。
     * Mycat 将字面量参数化为动态参数，而 Calcite 的 coerceOperandType 拒对 SqlDynamicParam 施加 CAST，
     * 致 bin('12')/CONV(16,..)/CRC32(123) 等“入参 Java 族与函数声明不符”的合法 MySQL 写法
     * 在 checkOperandTypes 阶段被拒（"Cannot apply ... to arguments of type ..."）。
     * 此处按 {@link #function} 声明的 Java 参数类型（与 {@code GetOperandTypes} 同源派生 SQL 目标类型）
     * 对族不匹配的 operand 就地包 CAST，再交回父类校验；作用域仅 Mycat 自定义函数、不涉 Calcite 内建。
     * 幂等：已匹配（相等或可直接赋值）的 operand 不包 CAST；包后类型即目标族、重检直接通过。
     */
    @Override
    public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
        if (super.checkOperandTypes(callBinding, false)) {
            return true;
        }
        mycatCoerceOperands(callBinding);
        if (super.checkOperandTypes(callBinding, false)) {
            return true;
        }
        // MYCAT CHANGE (I-DEBT-78): A-2 源侧免 coercion——CHARACTER 形参 × BINARY 实参方向不再包
        // 隐式 CAST(b AS VARCHAR)（见 mycatCoerceOperands 同向 skip），残余不匹配若全为此方向则直接
        // 放行，计划树保持裸 BINARY 操作数 → 跨片 deparse 天然无 CAST（后端 MySQL 原生字节语义），
        // 且隐式/显式 CAST 在 Rex 层不再同形，根治 I-DEBT-77 方向剥剥离无法区分显式 cast 的残留。
        // 保守门：仅 Mycat 自定义函数；固定参数量；其余不匹配一律回退原严格校验（拒得比原先不宽）。
        if (mycatBinaryOverCharacterTolerated(callBinding)) {
            return true;
        }
        return super.checkOperandTypes(callBinding, throwOnFailure);
    }

    private boolean mycatCoerceOperands(SqlCallBinding callBinding) {
        if (function == null) {
            return false;
        }
        final RelDataTypeFactory typeFactory = callBinding.getTypeFactory();
        final List<RelDataType> paramTypes = function.getParameters().stream()
                .map(o -> o.getType(typeFactory))
                .map(t -> toSql(typeFactory, t))
                .collect(Util.toImmutableList());
        final SqlCall sqlCall = callBinding.getCall();
        final int n = Math.min(sqlCall.operandCount(), paramTypes.size());
        boolean anyCast = false;
        for (int i = 0; i < n; i++) {
            final SqlNode operand = sqlCall.operand(i);
            if (operand.getKind() == SqlKind.ARGUMENT_ASSIGNMENT) {
                continue;
            }
            final RelDataType targetType = paramTypes.get(i);
            final SqlTypeName targetName = targetType.getSqlTypeName();
            if (targetName == SqlTypeName.ANY || targetName == SqlTypeName.NULL) {
                continue;
            }
            final RelDataType operandType = callBinding.getOperandType(i);
            if (operandType == null
                    || SqlTypeUtil.equalSansNullability(typeFactory, operandType, targetType)
                    || SqlTypeUtil.canCastFrom(targetType, operandType, false)) {
                continue;
            }
            // MYCAT CHANGE (I-DEBT-78): CHARACTER 形参 × BINARY 实参 = MySQL 原生字节语义方向，
            // 不包 coercion CAST，交由 mycatBinaryOverCharacterTolerated 直接放行（源侧免 coercion）。
            if (targetName.getFamily() == SqlTypeFamily.CHARACTER
                    && SqlTypeUtil.isBinary(operandType)) {
                continue;
            }
            final SqlNode cast = SqlStdOperatorTable.CAST.createCall(SqlParserPos.ZERO, operand,
                    SqlTypeUtil.convertTypeToSpec(targetType));
            sqlCall.setOperand(i, cast);
            anyCast = true;
        }
        return anyCast;
    }

    /**
     * MYCAT CHANGE (I-DEBT-78)：严格校验已败且 coercion 已尽力后的宽容终检——逐 operand 分类：
     * 相等（含刚被包 CAST 修正的）/同族（checker 本就接受）/ANY目标/空型 一律放行；
     * CHARACTER 形参 × BINARY 实参 为容忍方向（须至少出现一次才构成宽容理由）；
     * 其余任何不匹配 → 不容忍（回退严格报错）。只宽不拒：本方法仅在严格路已败后作为额外放行通道，
     * 不改变原有报错行为。
     */
    private boolean mycatBinaryOverCharacterTolerated(SqlCallBinding callBinding) {
        if (function == null) {
            return false;
        }
        final RelDataTypeFactory typeFactory = callBinding.getTypeFactory();
        final List<RelDataType> paramTypes = function.getParameters().stream()
                .map(o -> o.getType(typeFactory))
                .map(t -> toSql(typeFactory, t))
                .collect(Util.toImmutableList());
        final SqlCall sqlCall = callBinding.getCall();
        if (sqlCall.operandCount() != paramTypes.size()) {
            return false;
        }
        boolean anyTolerated = false;
        for (int i = 0; i < paramTypes.size(); i++) {
            final SqlNode operand = sqlCall.operand(i);
            if (operand.getKind() == SqlKind.ARGUMENT_ASSIGNMENT) {
                return false;
            }
            final RelDataType targetType = paramTypes.get(i);
            final SqlTypeName targetName = targetType.getSqlTypeName();
            final RelDataType operandType = callBinding.getOperandType(i);
            if (operandType == null || targetName == SqlTypeName.ANY || targetName == SqlTypeName.NULL) {
                continue;
            }
            if (SqlTypeUtil.equalSansNullability(typeFactory, operandType, targetType)
                    || operandType.getSqlTypeName().getFamily() == targetName.getFamily()) {
                continue;
            }
            if (targetName.getFamily() == SqlTypeFamily.CHARACTER
                    && SqlTypeUtil.isBinary(operandType)) {
                anyTolerated = true;
                continue;
            }
            return false;
        }
        return anyTolerated;
    }
}
