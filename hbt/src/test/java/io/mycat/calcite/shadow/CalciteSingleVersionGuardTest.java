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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.rex.RexLiteral;
import org.junit.Test;

/**
 * I-DEBT-30 面③ 单一版本护栏哨兵测（风险5）。
 *
 * <p>
 * 归一前 fat jar 混载两套 Calcite：旧影子拷贝（{@code hbt/src/main/java/org/apache/calcite/**}）与
 * fork {@code calcite-core 1.42}，同 FQCN 靠类加载顺序影子胜出，运行期接缝签名/行为错配 {@code NoSuchMethodError}。
 * Phase A 删 {@code RexLiteral}/{@code RexToLixTranslator} 影子、归一到 fork 单版本后，这两个类的唯一来源
 * 必须是 calcite-core fork——既不得回到 {@code hbt/target/classes}（影子残留），也不得是上游
 * {@code org/apache/calcite/calcite-core/} 布局的原始 jar（未来误加依赖导致的双版本回潮）。
 *
 * <p>
 * 原计划用 {@code maven-enforcer} bannedDependencies 挡上游，但离线 {@code .m2} 无 enforcer 缓存插件不可得，
 * 故降级为运行期 codeSource 断言（spec §4.4 订正）。断言谓词 {@link #isUpstreamLayout(String)} /
 * {@link #isForkLayout(String)} 用合成串自证其判别力（否则哨兵可能因永远为真而空转）——这是本测的
 * 非空转证明：谓词若有缺陷，合成用例即红。真实类断言在当前干净绿树上为绿（fork 胜出），其守卫的是未来
 * 双版本回潮这一无法在良构树上人为复现的失败态。
 */
public class CalciteSingleVersionGuardTest {

  @Test
  public void forkIsSingleSourceForNormalizedClasses() {
    assertForkOnlySource(RexLiteral.class);
    assertForkOnlySource(RexToLixTranslator.class);
  }

  /** 判别谓词自证：上游布局串判为上游、fork/reactor 串判为 fork，反之亦然——谓词坏则此测红。 */
  @Test
  public void predicatesDiscriminateUpstreamVsFork() {
    String upstreamJar =
        "file:/home/u/.m2/repository/org/apache/calcite/calcite-core/1.42.0/calcite-core-1.42.0.jar";
    String forkJar =
        "file:/home/u/.m2/repository/io/mycat/calcite-core/1.42.0-mycat/calcite-core-1.42.0-mycat.jar";
    String forkReactor = "file:/d/Workspaces/IdeaProjects/Mycat2/calcite-core/target/classes/";
    String hbtShadow = "file:/d/Workspaces/IdeaProjects/Mycat2/hbt/target/classes/";

    assertTrue("上游 maven 布局必须被判为上游", isUpstreamLayout(upstreamJar));
    assertFalse("fork jar 不得误判为上游", isUpstreamLayout(forkJar));
    assertFalse("fork reactor classes 不得误判为上游", isUpstreamLayout(forkReactor));

    assertTrue("fork jar 须判为 fork 来源", isForkLayout(forkJar));
    assertTrue("fork reactor classes 须判为 fork 来源", isForkLayout(forkReactor));
    assertFalse("上游 jar 不得判为 fork", isForkLayout(upstreamJar));
    assertFalse("hbt 影子 classes 不得判为 fork", isForkLayout(hbtShadow));
  }

  private static void assertForkOnlySource(Class<?> clazz) {
    String loc = clazz.getProtectionDomain().getCodeSource().getLocation().toString();
    assertFalse(clazz.getSimpleName()
            + " 不应来自上游 calcite-core 原始 jar（双版本回潮），实为: " + loc,
        isUpstreamLayout(loc));
    assertTrue(clazz.getSimpleName()
            + " 应来自 calcite-core fork（io/mycat/calcite-core 或 reactor calcite-core/target/classes），实为: "
            + loc,
        isForkLayout(loc));
  }

  /** 上游 Maven 仓库布局：{@code .../org/apache/calcite/calcite-core/...}。 */
  private static boolean isUpstreamLayout(String loc) {
    return loc.contains("org/apache/calcite/calcite-core/")
        || loc.contains("org" + java.io.File.separator + "apache" + java.io.File.separator
            + "calcite" + java.io.File.separator + "calcite-core" + java.io.File.separator);
  }

  /** fork 来源：发布坐标 {@code io/mycat/calcite-core}，或 reactor 直编 {@code calcite-core/target/classes}。 */
  private static boolean isForkLayout(String loc) {
    boolean forkCoordinate =
        loc.contains("io/mycat/calcite-core") || loc.contains("io" + java.io.File.separator
            + "mycat" + java.io.File.separator + "calcite-core");
    boolean forkReactor = loc.contains("calcite-core" + java.io.File.separator + "target")
        || loc.contains("calcite-core/target");
    return forkCoordinate || forkReactor;
  }
}
