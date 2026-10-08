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
 * I-DEBT-30 面② 结构断言锁：hbt PARK 影子 {@link RexToLixTranslator} 必须声明与 fork
 * 一致的 7 参 {@code translateCondition(..., boolean nullable)} 重载。
 *
 * <p>运行期影子胜出（与 fork 同 FQCN），而 fork 的 {@code EnumUtils} JOIN codegen 生成
 * sort-merge/hash JOIN 谓词时按 7 参描述符调用；影子仅有 6 参 → 链接期
 * {@code NoSuchMethodError}，炸全部 JOIN。此锁把"运行期载入类的 ABI 契约"钉死：
 * 缺该签名即红。（对齐 I-DEBT-18 影子补 {@code RelJson.create()} 同类先例。）
 */
public class ShadowRexToLixTranslatorJoinConditionTest {

  @Test
  public void shadowDeclaresSevenArgTranslateCondition() throws Exception {
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
          "影子 RexToLixTranslator 缺 fork EnumUtils JOIN codegen 所调的 7 参 "
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
