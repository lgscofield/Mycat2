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
        if (mycatCoerceOperands(callBinding)) {
            return super.checkOperandTypes(callBinding, throwOnFailure);
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
            final SqlNode cast = SqlStdOperatorTable.CAST.createCall(SqlParserPos.ZERO, operand,
                    SqlTypeUtil.convertTypeToSpec(targetType));
            sqlCall.setOperand(i, cast);
            anyCast = true;
        }
        return anyCast;
    }
}
