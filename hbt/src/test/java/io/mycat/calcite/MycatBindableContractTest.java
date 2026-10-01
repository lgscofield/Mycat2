package io.mycat.calcite;

import io.reactivex.rxjava3.core.Observable;
import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.ClassDeclaration;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.apache.calcite.runtime.Utilities;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.BuiltInMethod;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.IClassBodyEvaluator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * I-DEBT-4 合同测试：Mycat 执行引擎生成类必须实现自有双态 bind 契约，
 * 不再寄生被删的 hbt DataContext 副本 ROOT。
 */
public class MycatBindableContractTest {

    /** Enumerable 态 Result：block 先 root.getSessionVariable("probe")，再 return 空 enumerable（形态同 l1-server.log:484-542）。 */
    static EnumerableRel.Result enumerableResult() throws Exception {
        BlockBuilder builder = new BlockBuilder();
        builder.add(Expressions.statement(Expressions.call(MycatBindable.ROOT,
                NewMycatDataContext.class.getMethod("getSessionVariable", String.class),
                Expressions.constant("probe"))));
        builder.add(Expressions.return_(null, Expressions.call(
                BuiltInMethod.AS_ENUMERABLE.method, Expressions.newArrayInit(Object[].class))));
        return buildResult(builder);
    }

    static EnumerableRel.Result buildResult(BlockBuilder builder) {
        RelDataType rowType = MycatCalciteSupport.TypeFactory.builder()
                .add("PROBE", SqlTypeName.INTEGER)
                .build();
        // optimize=false：固定 ARRAY 形态，javaRowClass 恒为 Object[]
        PhysType physType = PhysTypeImpl.of(MycatCalciteSupport.TypeFactory,
                rowType, JavaRowFormat.ARRAY, false);
        return new EnumerableRel.Result(builder.toBlock(), physType, JavaRowFormat.ARRAY);
    }

    /** JDK Proxy 假 MycatRel：isSupportStream→true、implementStream→result，其余调用即报错。 */
    static MycatRel fakeRel(EnumerableRel.Result result) {
        return (MycatRel) Proxy.newProxyInstance(MycatRel.class.getClassLoader(),
                new Class<?>[] {MycatRel.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isSupportStream":
                            return true;
                        case "implementStream":
                            return result;
                        default:
                            throw new UnsupportedOperationException("unexpected MycatRel call: " + method.getName());
                    }
                });
    }

    @Test
    public void generatedClassShouldDeclareBindWithMycatTypedRoot() throws Exception {
        StreamMycatEnumerableRelImplementor implementor =
                new StreamMycatEnumerableRelImplementor(new HashMap<>());
        ClassDeclaration cd = implementor.implementHybridRoot(fakeRel(enumerableResult()),
                EnumerableRel.Prefer.ARRAY);
        String code = Expressions.toString(cd.memberDeclarations, "\n", false);
        Assertions.assertTrue(
                code.contains("public Object bind(final org.apache.calcite.runtime.NewMycatDataContext root)"),
                "生成码应声明 Mycat 自有契约的 bind（Object 返回 + NewMycatDataContext 参数），实际：\n" + code);
    }

    /** janino 现编，三行配置复刻 ExecutorProviderImpl:118-122（实现接口换为 MycatBindable）。 */
    static MycatBindable compile(EnumerableRel.Result result) throws Exception {
        StreamMycatEnumerableRelImplementor implementor =
                new StreamMycatEnumerableRelImplementor(new HashMap<>());
        ClassDeclaration cd = implementor.implementHybridRoot(fakeRel(result),
                EnumerableRel.Prefer.ARRAY);
        String code = Expressions.toString(cd.memberDeclarations, "\n", false);
        IClassBodyEvaluator cbe =
                CompilerFactoryFactory.getDefaultCompilerFactory().newClassBodyEvaluator();
        cbe.setClassName(cd.name);
        cbe.setExtendedClass(Utilities.class);
        cbe.setImplementedInterfaces(new Class[] {MycatBindable.class});
        cbe.setParentClassLoader(EnumerableInterpretable.class.getClassLoader());
        return (MycatBindable) cbe.createInstance(new StringReader(code));
    }

    static NewMycatDataContext fakeContext(List<String> recorded) {
        return (NewMycatDataContext) Proxy.newProxyInstance(
                NewMycatDataContext.class.getClassLoader(),
                new Class<?>[] {NewMycatDataContext.class}, (proxy, method, args) -> {
                    if ("getSessionVariable".equals(method.getName())) {
                        recorded.add((String) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(
                            "unexpected NewMycatDataContext call: " + method.getName());
                });
    }

    @Test
    public void compiledBindableShouldBehaveAsContract() throws Exception {
        MycatBindable b = compile(enumerableResult());
        Assertions.assertEquals(Object[].class, b.getElementType());
        List<String> recorded = new ArrayList<>();
        Object out = b.bind(fakeContext(recorded));
        Assertions.assertTrue(out instanceof Enumerable, "传统计划 bind 应返回 Enumerable，实际：" + out.getClass());
        Assertions.assertTrue(recorded.contains("probe"), "block 内 root.getSessionVariable(\"probe\") 应被执行");
        Assertions.assertFalse(((Enumerable<?>) out).iterator().hasNext());
    }

    @Test
    public void observableStateShouldSurviveBind() throws Exception {
        BlockBuilder builder = new BlockBuilder();
        builder.add(Expressions.return_(null, Expressions.call(
                Observable.class.getMethod("just", Object.class),
                Expressions.newArrayInit(Object[].class))));
        MycatBindable b2 = compile(MycatBindableContractTest.buildResult(builder));
        List<String> recorded = new ArrayList<>();
        Object out = b2.bind(fakeContext(recorded));
        Assertions.assertTrue(out instanceof Observable,
                "流式计划 bind 应返回 Observable，实际：" + out.getClass());
    }

    /** 钉子⑤：非数组标量 Enumerable 经 asObjectArray 逐行升箱为长度 1 的 Object[]。 */
    @Test
    public void asObjectArrayShouldBoxScalarEnumerable() {
        MycatBindable scalar = new MycatBindable() {
            @Override
            public Class getElementType() {
                return Integer.class;
            }

            @Override
            public Object bind(NewMycatDataContext root) {
                return Linq4j.asEnumerable(new Integer[] {1, 2, 3});
            }
        };
        MycatBindable wrapped = MycatBindable.asObjectArray(scalar);
        Assertions.assertEquals(Object[].class, wrapped.getElementType());
        Object out = wrapped.bind(fakeContext(new ArrayList<>()));
        Assertions.assertTrue(out instanceof Enumerable, "Enumerable 态应仍返 Enumerable");
        Iterator<?> it = ((Enumerable<?>) out).iterator();
        int n = 0;
        while (it.hasNext()) {
            Object row = it.next();
            Assertions.assertTrue(row instanceof Object[], "升箱后每行必为 Object[]");
            Assertions.assertEquals(1, ((Object[]) row).length);
            n++;
        }
        Assertions.assertEquals(3, n);
    }

    /** 钉子⑥：非数组标量 Observable 经 asObjectArray 逐行升箱（旧 wrapper 此处会 ClassCastException）。 */
    @Test
    public void asObjectArrayShouldBoxScalarObservable() {
        MycatBindable scalar = new MycatBindable() {
            @Override
            public Class getElementType() {
                return Integer.class;
            }

            @Override
            public Object bind(NewMycatDataContext root) {
                return Observable.just(1, 2, 3);
            }
        };
        MycatBindable wrapped = MycatBindable.asObjectArray(scalar);
        Object out = wrapped.bind(fakeContext(new ArrayList<>()));
        Assertions.assertTrue(out instanceof Observable, "Observable 态应仍返 Observable");
        @SuppressWarnings("unchecked")
        List<Object[]> rows = ((Observable<Object[]>) out).toList().blockingGet();
        Assertions.assertEquals(3, rows.size());
        for (Object[] row : rows) {
            Assertions.assertEquals(1, row.length);
        }
    }
}
