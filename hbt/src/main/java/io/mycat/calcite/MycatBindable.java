package io.mycat.calcite;

import io.reactivex.rxjava3.core.Observable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.runtime.NewMycatDataContext;

import java.lang.reflect.Modifier;

/**
 * Mycat 执行引擎自有的 bind 契约（I-DEBT-4）。
 *
 * <p>双态返回：流式计划（isSupportStream）的 bind 返回
 * {@code io.reactivex.rxjava3.core.Observable<Object[]>}，传统计划的 bind 返回
 * {@code org.apache.calcite.linq4j.Enumerable<Object[]>}；调用方以 instanceof 分叉
 * （见 PrepareExecutor 的 Observable/Enumerable 双态处理）。fork 的
 * {@code ArrayBindable.bind(DataContext) -> Enumerable} 单态签名表达不了该契约，
 * 方法名沿用 bind——被删的 hbt DataContext 副本当年靠改写 ROOT 参数类型为
 * NewMycatDataContext 寄生表达此契约。
 */
public interface MycatBindable {

    /** 生成代码里 root 参数的类型化常量（原寄生于 hbt DataContext 副本）。 */
    ParameterExpression ROOT = Expressions.parameter(Modifier.FINAL,
            NewMycatDataContext.class, "root");

    /**
     * @param root Mycat 类型化数据上下文
     * @return 流式计划为 {@code Observable<Object[]>}，传统计划为
     *         {@code Enumerable<Object[]>}（Object 返回以容纳双态）
     */
    Object bind(NewMycatDataContext root);

    Class getElementType();

    /**
     * 将非数组行元素升箱为 {@code Object[]}（双态）：原仅 ExecutorProviderImpl 私有且
     * 只处理 Enumerable 态（返回 Observable 时会 ClassCastException）；归位到契约接口后
     * Enumerable/Observable 两态均正确升箱（spec §4.3 / Review Focus 第 1 条）。
     */
    static MycatBindable asObjectArray(MycatBindable bindable) {
        if (bindable.getElementType().isArray()) {
            return bindable;
        }
        return new MycatBindable() {
            @Override
            public Class getElementType() {
                return Object[].class;
            }

            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object bind(NewMycatDataContext root) {
                Object out = bindable.bind(root);
                if (out instanceof Enumerable) {
                    return ((Enumerable) out).select(e -> new Object[] {e});
                }
                if (out instanceof Observable) {
                    return ((Observable) out).map(e -> new Object[] {e});
                }
                return out;
            }
        };
    }
}
