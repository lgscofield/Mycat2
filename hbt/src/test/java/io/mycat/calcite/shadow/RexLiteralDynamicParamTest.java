package io.mycat.calcite.shadow;

import io.mycat.calcite.MycatCalciteRuntimeHooks;
import io.mycat.calcite.spm.ParamHolder;
import org.apache.calcite.mycat.MycatCalciteRuntime;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

/**
 * I-DEBT-30 面③：删 {@code RexLiteral} 影子后，fork {@code findValue} 对动态参 {@code ?N}
 * 经中立 seam 取值（Mycat 侧走 ParamHolder），替代旧影子里的 io.mycat 直连。
 *
 * <p>同时钉死"未装配钩子=上游行为"：动态参不应被当作字面量，默认 seam 应抛。
 */
public class RexLiteralDynamicParamTest {

  private static RelDataType intType() {
    return new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT)
        .createSqlType(SqlTypeName.INTEGER);
  }

  @BeforeEach
  void installHooks() {
    MycatCalciteRuntimeHooks.install();
  }

  @Test
  void dynamicParamResolvesViaSeam() {
    ParamHolder.CURRENT_THREAD_LOCAL.get()
        .setData(Arrays.asList((Object) new BigDecimal(42)),
            Collections.singletonList(SqlTypeName.INTEGER));

    Comparable<?> v = RexLiteral.value(new RexDynamicParam(intType(), 0));

    Assertions.assertEquals(new BigDecimal(42), v,
        "?0 应经 seam 从 ParamHolder 取回 42");
  }

  @Test
  void dynamicParamWithoutHooksThrowsUpstreamBehavior() {
    // 卸载钩子（install(null) 恢复默认上游行为）后，动态参不应被当作字面量。
    MycatCalciteRuntime.install(null);

    Assertions.assertThrows(AssertionError.class,
        () -> RexLiteral.value(new RexDynamicParam(intType(), 0)),
        "默认 seam 应抛 not a literal: dynamic param #0");
  }
}
