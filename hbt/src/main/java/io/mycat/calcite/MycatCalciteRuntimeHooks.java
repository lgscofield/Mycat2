package io.mycat.calcite;

import io.mycat.calcite.spm.ParamHolder;
import io.mycat.calcite.sqlfunction.cmpfunction.StrictEqualFunction;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.mycat.MycatCalciteRuntime;
import org.apache.calcite.sql.SqlOperator;

/**
 * I-DEBT-3 面③ Phase A Task 2：{@link MycatCalciteRuntime.Hooks} 的 Mycat 实现。
 *
 * <p>忠实复刻旧整类影子（{@code RexLiteral}/{@code RexToLixTranslator}）里的三处真定制，作为钩子注入 fork，
 * 从而得以删除旧版影子拷贝、统一到 fork 单一版本：
 * <ul>
 *   <li>{@link #root()}：生成代码的 root 变量绑到 {@link MycatBindable#ROOT}（类型 {@code NewMycatDataContext}），
 *       替 fork 默认的 {@code DataContext.ROOT}；</li>
 *   <li>{@link #dynamicParamValue(int)}：常量求值期从 {@link ParamHolder} 取当前动态参 {@code ?} 的值；</li>
 *   <li>{@link #remapOperator(SqlOperator)}：把 MySQL 空安全等 {@code <=>} 映射为 {@link StrictEqualFunction}。</li>
 * </ul>
 *
 * <p>由 {@code MycatCore} 启动装配（见 {@link #install()}）；未装配时 fork 走 {@code MycatCalciteRuntime} 的上游默认。
 */
public final class MycatCalciteRuntimeHooks implements MycatCalciteRuntime.Hooks {

  @Override
  public ParameterExpression root() {
    return MycatBindable.ROOT;
  }

  @Override
  public Comparable<?> dynamicParamValue(int index) {
    return (Comparable<?>) ParamHolder.CURRENT_THREAD_LOCAL.get().getParams().get(index);
  }

  @Override
  public SqlOperator remapOperator(SqlOperator op) {
    return "<=>".equalsIgnoreCase(op.getName()) ? StrictEqualFunction.INSTANCE : op;
  }

  /** 装配本实现到中立 seam（幂等：重复调用只是再次 set 同一语义）。 */
  public static void install() {
    MycatCalciteRuntime.install(new MycatCalciteRuntimeHooks());
  }
}
