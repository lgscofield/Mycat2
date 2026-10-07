package io.mycat.executor;

import io.mycat.AsyncMycatDataContextImpl;
import io.mycat.DrdsSqlWithParams;
import io.mycat.MycatDataContext;
import io.mycat.api.collector.MysqlPayloadObject;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.calcite.*;
import io.mycat.calcite.spm.Plan;
import io.ordinate.engine.builder.CalciteCompiler;
import io.ordinate.engine.builder.RexConverter;
import io.ordinate.engine.function.bind.BindVariable;
import io.ordinate.engine.function.bind.IndexedParameterLinkFunction;
import io.ordinate.engine.function.bind.SessionVariable;
import io.ordinate.engine.physicalplan.PhysicalPlan;
import io.ordinate.engine.record.RootContext;
import io.reactivex.rxjava3.core.Observable;
import lombok.SneakyThrows;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.apache.calcite.runtime.Utilities;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.IClassBodyEvaluator;
import org.codehaus.commons.compiler.ICompilerFactory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

public class ExecutorProviderImpl implements ExecutorProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorProviderImpl.class);
    public static final ExecutorProviderImpl INSTANCE = new ExecutorProviderImpl();

    @Override
    public PrepareExecutor prepare(Plan plan) {
        CodeExecuterContext codeExecuterContext = plan.getCodeExecuterContext();
        PrepareExecutor bindable = codeExecuterContext.bindable;
        if (bindable != null) return bindable;

        try {

            return codeExecuterContext.bindable = PrepareExecutor.of((newMycatDataContext, mycatRowMetaData) -> {
                        DrdsSqlWithParams drdsSql = newMycatDataContext.getDrdsSql();
                        CalciteCompiler mycatCalciteCompiler = new CalciteCompiler();
                        PhysicalPlan factory = mycatCalciteCompiler.convert(plan.getMycatRel());
                       factory= fixCast(factory);
                        RexConverter rexConverter = mycatCalciteCompiler.getRexConverter();
                        Map<Integer, IndexedParameterLinkFunction> indexedMap = rexConverter.getIndexedParameterLinkFunctionMap();
                        List<Object> params = drdsSql.getParams();
                        if (!indexedMap.isEmpty()) {
                            for (int i = 0; i < params.size(); i++) {
                                Object o = params.get(i);
                                IndexedParameterLinkFunction indexedParameterLinkFunction = indexedMap.get(i);
                                if (indexedParameterLinkFunction != null) {
                                    BindVariable base = (BindVariable) indexedParameterLinkFunction.getBase();
                                    base.setObject(o);
                                }
                            }
                        }
                        List<SessionVariable> sessionMap = rexConverter.getSessionVariableFunctionMap();
                        for (SessionVariable sessionVariable : sessionMap) {
                            sessionVariable.setSession(newMycatDataContext.getContext());
                        }
                        AsyncMycatDataContextImpl.SqlMycatDataContextImpl sqlMycatDataContext =
                                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(newMycatDataContext.getContext(), plan.getCodeExecuterContext(), drdsSql);

                        RootContext rootContext = new RootContext(sqlMycatDataContext);
                        Observable<VectorSchemaRoot> schemaRootObservable = factory.execute(rootContext);
                        return PrepareExecutor.ArrowObservable.of(mycatRowMetaData, schemaRootObservable);
                    },
                    getArrayBindable(codeExecuterContext));
        } catch (Exception exception) {
            // MYCAT CHANGE (I-DEBT-11): 不再吞异常返回 null——曾致下游 prepare.getArrayBindable()/asRowBaseIterator()
            // 踩 NullPointerException，把真正的 codegen 成因（如 janino "Not a boolean expression"）掩盖成
            // "prepare is null"。改为向上传播原始异常，保留可诊断的根因链。
            LOGGER.error("prepare failed for plan: " + plan, exception);
            if (exception instanceof RuntimeException) {
                throw (RuntimeException) exception;
            }
            throw new RuntimeException("Failed to build PrepareExecutor: " + exception.getMessage(), exception);
        }
    }

    private PhysicalPlan fixCast(PhysicalPlan factory) {
        return factory;
    }

    @Override
    public RowBaseIterator runAsObjectArray(MycatDataContext context, DrdsSqlWithParams sqlStatement) {
        DrdsSqlWithParams drdsSql = sqlStatement;
        Plan plan = DrdsRunnerHelper.getPlan(drdsSql);
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl sqlMycatDataContext = new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(context, plan.getCodeExecuterContext(), drdsSql);
        PrepareExecutor prepareExecutor = prepare(plan);
        RowBaseIterator baseIterator = prepareExecutor.asRowBaseIterator(sqlMycatDataContext, plan.getMetaData());
        return baseIterator;
    }

    @NotNull
    private MycatBindable getArrayBindable(CodeExecuterContext codeExecuterContext) {
        MycatBindable bindable1 = MycatBindable.asObjectArray(getBindable(codeExecuterContext.getCodeContext()));
        return bindable1;
    }

    @SneakyThrows
    static MycatBindable getBindable(CodeContext codeContext) {
        ICompilerFactory compilerFactory;
        try {
            compilerFactory = CompilerFactoryFactory.getDefaultCompilerFactory();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to instantiate java compiler", e);
        }
        final IClassBodyEvaluator cbe = compilerFactory.newClassBodyEvaluator();
        cbe.setClassName(codeContext.getName());
        cbe.setExtendedClass(Utilities.class);
        cbe.setImplementedInterfaces(new Class[]{MycatBindable.class});
        cbe.setParentClassLoader(EnumerableInterpretable.class.getClassLoader());
        if (CalciteSystemProperty.DEBUG.value()) {
            // Add line numbers to the generated janino class
            cbe.setDebuggingInformation(true, true, true);
        }
        String code = codeContext.getCode();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(code);
        }
        try {
            return (MycatBindable) cbe.createInstance(new StringReader(code));
        } catch (Exception e) {
            // MYCAT CHANGE (I-DEBT-11): janino 编译失败时输出实际生成的源码（方法已 @SneakyThrows，异常原样透传），
            // 便于按报错行列号定位生成码缺陷（如 "Not a boolean expression"）。
            LOGGER.error("janino compile failed, generated code follows:\n" + code, e);
            throw e;
        }
    }

}
