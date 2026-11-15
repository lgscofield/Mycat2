package io.mycat.calcite;

import io.mycat.calcite.spm.ParamHolder;
import io.mycat.calcite.sqlfunction.arith.MysqlDivideFunction;
import io.mycat.calcite.sqlfunction.arith.MysqlModFunction;
import io.mycat.calcite.sqlfunction.cmpfunction.StrictEqualFunction;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.mycat.MycatCalciteRuntime;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

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
    // I-DEBT-39·tranche①（宽）：MySQL {@code /}（{@code SqlStdOperatorTable.DIVIDE}）在出码期重映射到
    // {@link MysqlDivideFunction}（十进制商 + 除零→NULL + 近似→double）。按算子实例判（非 SqlKind，
    // 因 {@code SqlKind.DIVIDE} 同时容纳 {@code DIVIDE_INTEGER}="DIV"），否则误伤 DIV。
    if (op == SqlStdOperatorTable.DIVIDE) {
      return MysqlDivideFunction.INSTANCE;
    }
    // MYCAT CHANGE (I-DEBT-119)：MySQL {@code MOD}/{@code %}（{@code SqlStdOperatorTable.MOD}）重映射到
    // {@link MysqlModFunction}（NULL 透传 + 除零→NULL+诊断槽 1365，与 / 除法同口径）。原生
    // {@code SqlFunctions.mod} 系返回原始类型无法表达 NULL、零除数抛 ArithmeticException。
    if (op == SqlStdOperatorTable.MOD) {
      return MysqlModFunction.INSTANCE;
    }
    return "<=>".equalsIgnoreCase(op.getName()) ? StrictEqualFunction.INSTANCE : op;
  }

  @Override
  public Expression translateCastOverride(RelDataType sourceType, RelDataType targetType,
      Expression operand) {
    // Mycat 宽松（MySQL 语义）CAST 分派：命中则回 X_TO_Y 调用，未命中回 null 交 fork 原生。
    return MycatCastOverride.translate(sourceType, targetType, operand);
  }

  /**
   * MYCAT CHANGE (I-DEBT-118)：calcite-core 侧 MycatBuiltInMethodImpl 非法串宽松转数（回 0 值）
   * 的告警通道——经 I-DEBT-97 诊断槽写会话 Warning 1292 "Truncated incorrect X value: 'v'"
   * （真库 CAST('abc' AS SIGNED) 口径；先例 MysqlDivideFunction.emitDivisionByZeroWarning）。
   * 无槽（远程/异步消费线程）降级静默；addWarning 异常吞并 debug，不反噬求值线程。
   */
  @Override
  public void castTruncatedWarning(String inputValue, String targetType) {
    io.mycat.MycatDataContext ctx =
        org.apache.calcite.runtime.NewMycatDataContext.currentDiagnosticContext();
    if (ctx == null) {
      return;
    }
    try {
      ctx.addWarning(new io.mycat.beans.mycat.WarningMessage("Warning", 1292,
          "Truncated incorrect " + targetType + " value: '" + inputValue + "'"));
    } catch (Throwable e) {
      io.mycat.MycatDataContext.LOGGER.debug(
          "I-DEBT-118: emit cast truncated warning failed, degrade silently", e);
    }
  }

  /** 装配本实现到中立 seam（幂等：重复调用只是再次 set 同一语义）。 */
  public static void install() {
    MycatCalciteRuntime.install(new MycatCalciteRuntimeHooks());
  }
}
