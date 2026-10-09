package io.mycat.calcite.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.mycat.calcite.MycatBindable;
import io.mycat.calcite.MycatCalciteRuntimeHooks;
import io.mycat.calcite.spm.ParamHolder;
import io.mycat.calcite.sqlfunction.cmpfunction.StrictEqualFunction;
import java.util.Arrays;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.mycat.MycatCalciteRuntime;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-3 面③ Phase A Task 2：hbt 侧钩子实现 {@link MycatCalciteRuntimeHooks} 的行为锁。
 * 断三钩子忠实复刻旧影子定制：root=MycatBindable.ROOT / 动态参读 ParamHolder / {@code <=>} 映射严格相等。
 */
class MycatCalciteRuntimeHooksTest {

  private final MycatCalciteRuntimeHooks hooks = new MycatCalciteRuntimeHooks();

  @AfterEach
  void reset() {
    ParamHolder.CURRENT_THREAD_LOCAL.get().clear();
    MycatCalciteRuntime.install(null);
  }

  @Test
  void rootIsMycatBindableRoot() {
    assertSame(MycatBindable.ROOT, hooks.root());
  }

  @Test
  void dynamicParamReadsParamHolder() {
    ParamHolder.CURRENT_THREAD_LOCAL.get()
        .setData(Arrays.<Object>asList("a", "b"), null);
    assertEquals("b", hooks.dynamicParamValue(1));
  }

  @Test
  void remapOnlyForNullSafeEqual() {
    // 名为 <=> 的非 INSTANCE 算子 → 映射为 StrictEqualFunction.INSTANCE
    SqlOperator nullSafe = mock(SqlOperator.class);
    when(nullSafe.getName()).thenReturn("<=>");
    assertSame(StrictEqualFunction.INSTANCE, hooks.remapOperator(nullSafe));
    // 其它算子恒等
    assertSame(SqlStdOperatorTable.EQUALS, hooks.remapOperator(SqlStdOperatorTable.EQUALS));
  }

  @Test
  void installActivatesSeam() {
    MycatCalciteRuntimeHooks.install();
    assertTrue(MycatCalciteRuntime.isInstalled());
    assertSame(MycatBindable.ROOT, MycatCalciteRuntime.get().root());
  }

  // ---- I-DEBT-30 面③ Task6 扩容(CAST)：宽松 CAST seam 行为锁 ----

  private static RelDataType typeOf(SqlTypeName name) {
    RelDataType t = mock(RelDataType.class);
    when(t.getSqlTypeName()).thenReturn(name);
    return t;
  }

  /** DEFAULT（未装配 Mycat 钩子）：translateCastOverride 必须返回 null，保证上游严格语义零改变。 */
  @Test
  void defaultSeamLeavesCastToForkNative() {
    MycatCalciteRuntime.install(null);
    org.apache.calcite.linq4j.tree.Expression operand =
        org.apache.calcite.linq4j.tree.Expressions.constant(1);
    assertEquals(null,
        MycatCalciteRuntime.get()
            .translateCastOverride(typeOf(SqlTypeName.INTEGER), typeOf(SqlTypeName.DATE), operand));
  }

  /** Mycat 钩子：int→date 不得走 fork 严格 EnumUtils（旧影子运行期即崩 Cannot cast int to LocalDate），须命中 X_TO_Y 分派。 */
  @Test
  void mycatSeamHandlesLenientIntToDate() {
    org.apache.calcite.linq4j.tree.Expression operand = Expressions.constant(1);
    org.apache.calcite.linq4j.tree.Expression out = hooks
        .translateCastOverride(typeOf(SqlTypeName.INTEGER), typeOf(SqlTypeName.DATE), operand);
    assertTrue(out != null,
        "int→date 应由 Mycat 宽松 CAST 分派接管（非 null），否则归一后退回 fork 严格转换 → Cannot cast int to LocalDate");
  }
}
