/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite.shadow;

import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.junit.Test;

/**
 * I-DEBT-30 面③ 结构断言锁：删 hbt {@link RexToLixTranslator} 影子、归一到 fork 单版本后，
 * 同 FQCN 的唯一来源必须是 calcite-core fork（而非 hbt 影子 target），且 fork 原生声明与
 * {@code EnumUtils}/{@code MycatCalc} JOIN codegen 所调的 7 参
 * {@code translateCondition(..., boolean nullable)} 重载。
 *
 * <p>影子未删时胜出类来自 {@code hbt/target/classes}，本锁 codeSource 断言即红；删影子后
 * 来源为 fork jar，转绿。同时把 ABI 契约（缺 7 参重载→运行期 {@code NoSuchMethodError}）钉死。
 * （面② 先例：影子曾补此重载以免炸全部 JOIN；归一后由 fork 原生提供。）
 */
public class ShadowRexToLixTranslatorJoinConditionTest {

  @Test
  public void unifiedForkIsWinnerAndDeclaresSevenArg() throws Exception {
    // 归一正向断言：胜出类不得来自 hbt 影子 target/classes。
    String loc = RexToLixTranslator.class.getProtectionDomain().getCodeSource()
        .getLocation().toString();
    assertTrue("RexToLixTranslator 应来自 calcite-core fork，而非 hbt 影子 target/classes（影子未删？）",
        !loc.contains("hbt" + java.io.File.separator + "target")
            && !loc.contains("hbt/target/classes"));

    ClassLoader cl = RexToLixTranslator.class.getClassLoader();
    Class<?>[] params = {
        Class.forName("org.apache.calcite.rex.RexProgram", false, cl),
        Class.forName("org.apache.calcite.adapter.java.JavaTypeFactory", false, cl),
        Class.forName("org.apache.calcite.linq4j.tree.BlockBuilder", false, cl),
        Class.forName("org.apache.calcite.adapter.enumerable.RexToLixTranslator$InputGetter",
            false, cl),
        Class.forName("org.apache.calcite.linq4j.function.Function1", false, cl),
        Class.forName("org.apache.calcite.sql.validate.SqlConformance", false, cl),
        boolean.class,
    };

    Method m;
    try {
      m = RexToLixTranslator.class.getMethod("translateCondition", params);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(
          "归一到 fork 后 RexToLixTranslator 缺 EnumUtils/MycatCalc JOIN codegen 所调的 7 参 "
              + "translateCondition(..., boolean nullable) 重载 → 运行期 NoSuchMethodError", e);
    }
    assertNotNull(m);
    assertNotNull("返回类型应为 linq4j Expression", m.getReturnType());
    assertTrue("translateCondition 须为 static", Modifier.isStatic(m.getModifiers()));
  }

  private static void assertTrue(String msg, boolean cond) {
    if (!cond) {
      throw new AssertionError(msg);
    }
  }
}
