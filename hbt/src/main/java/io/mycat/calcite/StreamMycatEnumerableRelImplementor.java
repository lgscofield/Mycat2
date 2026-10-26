package io.mycat.calcite;

import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.reactivex.rxjava3.core.Observable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.tree.*;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.RxBuiltInMethod;

import java.lang.reflect.Modifier;
import java.util.*;

public class StreamMycatEnumerableRelImplementor extends MycatEnumerableRelImplementor{

    public StreamMycatEnumerableRelImplementor(Map<String, Object> internalParameters) {
        super(internalParameters);
    }

    /**
     * MYCAT CHANGE (I-DEBT-59)：Observable 态的行形态不变式——单列投影不得塌成 SCALAR。
     *
     * <p>RxJava3 的 {@code Observable} 契约禁止 null item，而 stream 腿投影出码唯一发射
     * null 的缝是 {@code RxBuiltInMethod.OBSERVABLE_SELECT}（{@code RxBuiltInMethodImpl.select}
     * 内部即 {@code input.map(...)}）。
     * fork 的 {@code JavaRowFormat.optimize} 对 {@code getFieldCount()==1} 无条件把 ARRAY 降为
     * SCALAR（与 {@link EnumerableRel.Prefer} 无关），于是 {@code physType.record(...)} 直出裸标量：
     * 投影值为 null 时 {@code .map} 返回 null，运行期抛
     * {@code NullPointerException: The mapper function returned a null value}（活库取证：i_s 虚表上
     * 连纯投影 {@code select DAYOFWEEK(DATA_TYPE) ...} 都崩，非仅 distinct/聚合）。
     *
     * <p>修法 A＝恒保容器行形态：null 只作行内元素（{@code Object[]} 本身非 null），裸标量在终结处
     * 解箱（{@code MycatBindable} 契约本就要求 {@code Observable<Object[]>}）。与 I-DEBT-19
     * （{@code MycatView.implementMergeSort} 强制 {@code optimize=false}）同法，且与既有绿路径
     * 「多列 distinct 生成 {@code new Object[]{...}} + {@code JavaRowFormat.ARRAY.comparer()
     * = Linq4j.arrayComparer()}」同形，故 distinct 不会退化成引用等值。
     *
     * <p>只在 {@code optimize} 结果为 SCALAR 时改判 ARRAY；LIST/CUSTOM/多列 ARRAY 原样保留，
     * 聚合 key 与多列路径零漂移。Enumerable 态（{@code implement()}）不调用本方法——Linq4j
     * {@code Enumerable} 容 null，改它属无收益扩面。
     */
    public static PhysType streamRowPhysType(JavaTypeFactory typeFactory, RelDataType rowType,
            EnumerableRel.Prefer pref, JavaRowFormat childFormat) {
        JavaRowFormat format = pref.prefer(childFormat).optimize(rowType);
        if (format == JavaRowFormat.SCALAR) {
            format = JavaRowFormat.ARRAY;
        }
        return PhysTypeImpl.of(typeFactory, rowType, format, false);
    }

    /**
     * MYCAT CHANGE (I-DEBT-59)：透传型 rel 的行形态不变式——声明必须逐字等于子节点实发形态。
     *
     * <p>「透传型」指不重录行、直接把子节点元素转发出去的 rel：{@code MycatUnion}、{@code MycatMemSort}、
     * {@code MycatTopN}、{@code MycatMinus}、{@code MycatIntersect}（对比 {@code MycatCalc}/聚合/
     * join 的 {@code implementHashJoin}——它们用 {@code physType.record(...)}/{@code joinSelector}
     * 自行造行，声明即实发，不在此列）。
     *
     * <p>根节点的 {@code getElementType()} 取自根 physType 的声明（见 {@link #implementHybridRoot}），
     * 而 {@link #visitChild} 只要子节点 {@code isSupportStream()} 就调 {@code implementStream}——
     * 所以**根即便是 Enumerable（limit 回落的 MemSort/TopN），流经的元素也可能是 stream 腿容器行
     * {@code Object[]}**。此时若用三参 {@code PhysTypeImpl.of(..., format)}（= {@code optimize=true}）
     * 声明，fork 的 {@code JavaRowFormat.optimize} 会把单列 ARRAY 塌回 SCALAR，声明(Long) 与实发(Object[])
     * 不符，终结端 {@code MycatBindable.asObjectArray} 就把整个 {@code Object[]} 当成一个列的值再包一层，
     * 客户端读到 {@code [Ljava.lang.Object;@...} 而非该列值（活库铁证：
     * {@code select ... union all select ... limit 3} 返数组字串；离线 codegen 铁证：根
     * {@code return java.lang.Long.class;} 而体内是 {@code toEnumerable(RxBuiltInMethodImpl.select(...))}）。
     *
     * <p>本方法只关掉二次 optimize，不改变任何已自洽的计划：子节点声明 SCALAR 时仍声明 SCALAR
     * （纯 Enumerable 单列腿零漂移），声明 ARRAY/LIST/CUSTOM 时原样沿用。多子节点的集合操作须先把
     * 各子行 {@code PhysType.convertTo} 归一到同一形态（同形态时 convertTo 原样返回，不产生额外代码）。
     */
    public static PhysType passThroughPhysType(JavaTypeFactory typeFactory, RelDataType rowType,
            JavaRowFormat childFormat) {
        return PhysTypeImpl.of(typeFactory, rowType, childFormat, false);
    }

    public ClassDeclaration implementHybridRoot(MycatRel rootRel, EnumerableRel.Prefer prefer) {
        EnumerableRel.Result result;
        try {
            if (rootRel.isSupportStream()){
                result = rootRel.implementStream(this,prefer);
            }else {
                result = rootRel.implement((EnumerableRelImplementor) this, prefer);
            }
        } catch (RuntimeException e) {
            IllegalStateException ex = new IllegalStateException("Unable to implement "
                    + RelOptUtil.toString(rootRel, SqlExplainLevel.ALL_ATTRIBUTES));
            ex.addSuppressed(e);
            throw ex;
        }
        final List<MemberDeclaration> memberDeclarations = new ArrayList<>();
        new TypeRegistrar(memberDeclarations).go(result);

        // This creates the following code
        // final Integer v1stashed = (Integer) root.get("v1stashed")
        // It is convenient for passing non-literal "compile-time" constants
        final Collection<Statement> stashed =
                Collections2.transform(stashedParameters.values(),
                        input -> Expressions.declare(Modifier.FINAL, input,
                                Expressions.convert_(
                                        Expressions.call(MycatBindable.ROOT,
                                                BuiltInMethod.DATA_CONTEXT_GET.method,
                                                Expressions.constant(input.name)),
                                        input.type)));

        final BlockStatement block = Expressions.block(
                Iterables.concat(
                        stashed,
                        result.block.statements));
        memberDeclarations.add(
                Expressions.methodDecl(
                        Modifier.PUBLIC,
                       Object.class,
                        "bind",
                        Expressions.list(MycatBindable.ROOT),
                        block));
        memberDeclarations.add(
                Expressions.methodDecl(Modifier.PUBLIC, boolean.class,
                        "isObservable",
                        ImmutableList.of(),
                        Blocks.toFunctionBlock(
                                Expressions.return_(null,
                                        Expressions.constant(block.getType() instanceof Observable)))));
        memberDeclarations.add(
                Expressions.methodDecl(Modifier.PUBLIC, Class.class,
                        BuiltInMethod.TYPED_GET_ELEMENT_TYPE.method.getName(),
                        ImmutableList.of(),
                        Blocks.toFunctionBlock(
                                Expressions.return_(null,
                                        Expressions.constant(result.physType.getJavaRowType())))));
        return Expressions.classDecl(Modifier.PUBLIC,
                "Baz",
                null,
                Collections.singletonList(MycatBindable.class),
                memberDeclarations);
    }

    @Override
    public EnumerableRel.Result visitChild(EnumerableRel parent, int ordinal, EnumerableRel child, EnumerableRel.Prefer prefer) {
       if (child instanceof MycatRel){
           MycatRel childRel = (MycatRel) child;
           if (childRel.isSupportStream()){
               return childRel.implementStream(this,prefer);
           }
       }
        return super.visitChild(parent, ordinal, child, prefer);
    }
}
