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
import org.apache.calcite.mycat.MycatCalciteRuntime;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
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
}
