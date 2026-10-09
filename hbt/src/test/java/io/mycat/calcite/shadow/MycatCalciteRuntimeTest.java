package io.mycat.calcite.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.mycat.MycatCalciteRuntime;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-3 面③ Phase A Task 1：fork 中立 seam {@link MycatCalciteRuntime} 的契约锁。
 * 断"未装配=上游行为"（root=DataContext.ROOT / 动态参抛错 / 算子恒等）与"装配后钩子生效且可复位"。
 */
class MycatCalciteRuntimeTest {

  @AfterEach
  void reset() {
    MycatCalciteRuntime.install(null);
  }

  @Test
  void defaultRootIsDataContextRoot() {
    assertEquals(DataContext.ROOT, MycatCalciteRuntime.get().root());
  }

  @Test
  void defaultDynamicParamThrows() {
    assertThrows(AssertionError.class, () -> MycatCalciteRuntime.get().dynamicParamValue(0));
  }

  @Test
  void defaultRemapIsIdentity() {
    assertSame(SqlStdOperatorTable.EQUALS,
        MycatCalciteRuntime.get().remapOperator(SqlStdOperatorTable.EQUALS));
    assertFalse(MycatCalciteRuntime.isInstalled());
  }

  @Test
  void installSwapsHooksAndGetReflectsIt() {
    MycatCalciteRuntime.install(new MycatCalciteRuntime.Hooks() {
      @Override public ParameterExpression root() {
        return DataContext.ROOT;
      }

      @Override public Comparable<?> dynamicParamValue(int index) {
        return index;
      }

      @Override public SqlOperator remapOperator(SqlOperator op) {
        return SqlStdOperatorTable.NOT;
      }

      @Override public org.apache.calcite.linq4j.tree.Expression translateCastOverride(
          org.apache.calcite.rel.type.RelDataType sourceType,
          org.apache.calcite.rel.type.RelDataType targetType,
          org.apache.calcite.linq4j.tree.Expression operand) {
        return null;
      }
    });
    assertTrue(MycatCalciteRuntime.isInstalled());
    assertSame(SqlStdOperatorTable.NOT,
        MycatCalciteRuntime.get().remapOperator(SqlStdOperatorTable.EQUALS));
    assertEquals(0, MycatCalciteRuntime.get().dynamicParamValue(0));

    MycatCalciteRuntime.install(null);
    assertFalse(MycatCalciteRuntime.isInstalled());
    assertSame(SqlStdOperatorTable.EQUALS,
        MycatCalciteRuntime.get().remapOperator(SqlStdOperatorTable.EQUALS));
  }
}
