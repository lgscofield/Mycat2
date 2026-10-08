package org.apache.calcite.mycat;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.sql.SqlOperator;

/**
 * I-DEBT-3 面③ Phase A：Calcite fork 里的中立运行时钩子面。
 *
 * <p>历史上有若干 Mycat 定制以"整类影子拷贝"（同 FQCN，旧版 Calcite）覆盖 fork，运行期靠打包顺序
 * 胜出，接缝处签名/行为错配即 {@code NoSuchMethodError}。本类是把这些可数的定制点收拢成三个中立
 * 钩子（root / 动态参 / 算子替换），默认实现=上游 Calcite 行为；Mycat 侧（hbt）在启动时装配自己的
 * 实现，从而删掉整类影子、统一到 fork 单一版本。
 *
 * <p>闸门：本类位于 calcite-core，只依赖 plain Calcite 类型，绝不 import {@code io.mycat.*}。
 */
public final class MycatCalciteRuntime {

  /** 三个可替换钩子；默认实现复刻上游行为，Mycat 侧装配后改判。 */
  public interface Hooks {
    /** 生成代码里 root 变量所用 {@link ParameterExpression}（默认 {@link DataContext#ROOT}）。 */
    ParameterExpression root();

    /** 动态参 {@code ?} 在常量求值期的当前值（默认：视为非字面量抛错，对齐上游 {@code findValue}）。 */
    Comparable<?> dynamicParamValue(int index);

    /** RexCall 翻译前的算子替换（默认恒等；Mycat 侧把 {@code <=>} 映射为严格相等实现）。 */
    SqlOperator remapOperator(SqlOperator op);
  }

  private static final Hooks DEFAULT = new Hooks() {
    @Override public ParameterExpression root() {
      return DataContext.ROOT;
    }

    @Override public Comparable<?> dynamicParamValue(int index) {
      throw new AssertionError("not a literal: dynamic param #" + index);
    }

    @Override public SqlOperator remapOperator(SqlOperator op) {
      return op;
    }
  };

  private static volatile Hooks hooks = DEFAULT;

  private MycatCalciteRuntime() {
  }

  /** 装配 Mycat 钩子；传 {@code null} 复位为上游默认。 */
  public static void install(Hooks h) {
    hooks = (h == null) ? DEFAULT : h;
  }

  /** 当前生效钩子（永不为 {@code null}）。 */
  public static Hooks get() {
    return hooks;
  }

  /** 是否已装配非默认钩子（供装配时序守卫测）。 */
  public static boolean isInstalled() {
    return hooks != DEFAULT;
  }
}
