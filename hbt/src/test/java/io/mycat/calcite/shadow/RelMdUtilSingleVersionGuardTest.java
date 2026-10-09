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

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.junit.Test;

/**
 * I-DEBT-30 面③ Phase A 扩容（用户选 B）：{@code RelMdUtil} 影子归一守卫。
 *
 * <p>
 * 活体 e2e（{@code testComplexQuery}/{@code testCastFunction}）复现运行期
 * {@code NoSuchMethodError: RelMdUtil.isRelDefinitelyNotEmpty(RelMetadataQuery, RelNode)}——fork 规划期调用方
 * （{@code SubQueryRemoveRule}/{@code SqlToRelConverter}）需要该方法，而胜出类是 {@code hbt} 影子 {@code RelMdUtil}（缺此方法）。
 * 本锁正向钉死：删影子后 {@code RelMdUtil} 必须来自 fork（非 {@code hbt/target/classes}）且声明
 * {@code isRelDefinitelyNotEmpty}。删影子前本测红（影子胜出、反射取不到方法）。
 *
 * <p>
 * 注意：{@code RelMdRowCount}/{@code RelMdMaxRowCount} 影子属崩溃类家族（见 {@code SortParamRowCountTest} 注释），
 * 本轮**保留**，故本测不针对它们，仅锁 {@code RelMdUtil}。
 */
public class RelMdUtilSingleVersionGuardTest {

  @Test
  public void relMdUtilIsForkSourcedAndDeclaresIsRelDefinitelyNotEmpty() {
    String loc = RelMdUtil.class.getProtectionDomain().getCodeSource().getLocation().toString();
    assertTrue("RelMdUtil 应来自 calcite-core fork，而非 hbt 影子 target/classes（影子未删？），实为: " + loc,
        !loc.contains("hbt" + java.io.File.separator + "target")
            && !loc.contains("hbt/target/classes"));

    Method m;
    try {
      m = RelMdUtil.class.getMethod("isRelDefinitelyNotEmpty", RelMetadataQuery.class, RelNode.class);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(
          "归一到 fork 后 RelMdUtil 仍缺 fork 规划调用方所需的 isRelDefinitelyNotEmpty(RelMetadataQuery, RelNode)"
              + " → 活体 EXISTS/cast e2e 运行期 NoSuchMethodError；影子未真正归一", e);
    }
    assertTrue("isRelDefinitelyNotEmpty 返回类型应为 boolean",
        m.getReturnType() == boolean.class);
  }
}
