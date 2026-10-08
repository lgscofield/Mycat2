# Calcite 影子版本归一 · Phase A 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 hbt 下 `RexLiteral`、`RexToLixTranslator` 两个旧版影子拷贝，让 fork 1.42 原生类唯一胜出，其 Mycat 真定制改由一组 fork 中立 seam 注入，并加单一版本护栏。

**Architecture:** 在 `calcite-core`（fork）新增中立钩子面 `MycatCalciteRuntime`（`org.apache.calcite.mycat` 包，零 io.mycat），默认实现=上游行为；fork 的 `RexLiteral.findValue`/`RexToLixTranslator`（ROOT 三处 + `visitCall` 算子替换）改走该 seam。hbt 提供以 `ParamHolder`/`MycatBindable.ROOT`/`StrictEqualFunction` 为底的实现并在 `MycatCore` 启动装配；随后删除两个影子文件。

**Tech Stack:** Java 21（BellSoft Liberica JDK-21-Full）、Maven（离线 `-o`）、Calcite fork 1.42、JUnit5 + Mockito 5.17.0（结构/行为断言）、example 模块 JDBC e2e（活体 8066 + MySQL 后端）。

**Spec:** `docs/superpowers/specs/2026-10-08-calcite-shadow-version-unification-design.md`

## Global Constraints

- **fork 零 io.mycat**：`calcite-core` 内任何新增/修改文件不得 `import io.mycat.*`（seam 一律用 plain Calcite 类型：`ParameterExpression`/`SqlOperator`/`Comparable`）。校验：`Get-ChildItem -Recurse calcite-core/src/main/java | Select-String "^import io.mycat"` 命中数不增（基线 0）。
- **离线构建**：所有 mvn 带 `-o`；`JAVA_HOME=D:\Program Files\BellSoft\LibericaJDK-21-Full`。
- **PowerShell 传参**：含 `-D` 的 mvn 参数前用 `--%` 停解析，或整体加引号 `'-Dmaven.test.skip=true'`。
- **每次 mvn 后**：`git checkout -- server/src/main/resources/version.txt`（版本插件会改写它，不入库）。
- **重编前停 8066**：改动 hbt/calcite-core/example 后须先停旧 8066 再 `package`，再重启。
- **TDD**：每个 seam/行为改动先写红测，跑红，再实现，跑绿，提交。
- **合回 main / push 由用户拍板**：本计划在特性分支执行。

## Review Focus

（spec 隐含、但非任一任务直接测到、最可能咬人的失败模式——每条在下文对应任务补钉测）
1. **装配时序**：codegen 触发早于 `MycatCalciteRuntime.install` → 静默用上 `DataContext.ROOT` 默认，Mycat 定制丢失而测试可能仍绿。→ Task 2 装配红测 + fail-fast 哨兵。
2. **`<=>` 语义漂移**：fork 1.42 对 `<=>` 走标准 `NULL_SAFE_EQUAL`，与影子 `StrictEqualFunction` 可能不等价（Phase A 主证伪点）。→ Task 4 行为等值 e2e。
3. **空参/越界**：`ParamHolder` 未 `setData` 时 `getParams()` 为 null 或 index 越界 → 相对影子是 NPE 还是清晰错误。→ Task 3 边界测。
4. **fork codegen 结构差**：删影子=切到 1.42 版 `RexToLixTranslator`，除 ROOT/remap 外其余 codegen 路径行为可能变。→ Task 4 全量 JOIN/codegen e2e 兜。
5. **上游原版回潮**：未来某依赖传递拽入 `org.apache.calcite:calcite-core` 双份同 FQCN。→ Task 5 单一版本哨兵测。

---

## Task 1: 中立 seam 持有者 `MycatCalciteRuntime`（fork，默认=上游行为）

**Files:**
- Create: `calcite-core/src/main/java/org/apache/calcite/mycat/MycatCalciteRuntime.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/MycatCalciteRuntimeTest.java`

**Interfaces:**
- Produces:
  - `public final class MycatCalciteRuntime` 内嵌 `public interface Hooks { ParameterExpression root(); Comparable<?> dynamicParamValue(int index); SqlOperator remapOperator(SqlOperator op); }`
  - `public static void install(Hooks h)`；`public static Hooks get()`；`public static boolean isInstalled()`（Task 2/装配测用）
  - 默认 `Hooks`（`DEFAULT`，volatile static 持有）：`root()` 返回 `org.apache.calcite.data.DataContext.ROOT`；`dynamicParamValue(i)` 抛 `AssertionError("not a literal: dynamic param #" + i)`；`remapOperator(op)` 返回 `op`。
- Consumes: 无（叶子）。

- [ ] **Step 1: 写红测**（`MycatCalciteRuntimeTest`，JUnit5）
  - `defaultRootIsDataContextRoot`：未 install 时 `MycatCalciteRuntime.get().root() == DataContext.ROOT`。
  - `defaultDynamicParamThrows`：`assertThrows(AssertionError.class, () -> get().dynamicParamValue(0))`。
  - `defaultRemapIsIdentity`：`get().remapOperator(SqlStdOperatorTable.EQUALS) == EQUALS`。
  - `installSwapsAndGetReflectsIt`：install 一个桩 Hooks 后 `get()` 返回桩、`isInstalled()` 为 true（测试末尾还原 `install(null)`→回 DEFAULT）。

- [ ] **Step 2: 跑红**
  Run: `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"; mvn -o test -pl hbt --% -Dtest=MycatCalciteRuntimeTest`
  Expected: 编译失败/`cannot find symbol MycatCalciteRuntime`。

- [ ] **Step 3: 实现 `MycatCalciteRuntime`**（仅 `import org.apache.calcite.*` 与 `java.util.Objects`；`install(null)` 复位 DEFAULT；volatile 保证可见性；无 io.mycat）。

- [ ] **Step 4: 跑绿** 同 Step 2，Expected: `Tests run: 4, Failures: 0`。

- [ ] **Step 5: 闸门校验 + 提交**
  ```bash
  Get-ChildItem -Recurse calcite-core/src/main/java | Select-String "^import io.mycat" | Measure-Object  # 期望 0
  git add calcite-core/src/main/java/org/apache/calcite/mycat/MycatCalciteRuntime.java hbt/src/test/java/io/mycat/calcite/shadow/MycatCalciteRuntimeTest.java
  git commit -m "feat(i-debt-3): fork 中立 seam MycatCalciteRuntime（root/dynamicParam/remapOperator，默认上游行为）"
  git checkout -- server/src/main/resources/version.txt
  ```

## Task 2: hbt 侧钩子实现 + `MycatCore` 装配

**Files:**
- Create: `hbt/src/main/java/io/mycat/calcite/MycatCalciteRuntimeHooks.java`
- Modify: `server/src/main/java/io/mycat/MycatCore.java`（紧邻现有 L95 `MycatRxJavaErrorHandler.install()` 之后）
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/MycatCalciteRuntimeHooksTest.java`

**Interfaces:**
- Consumes: `MycatCalciteRuntime.Hooks`/`install`（Task 1）；hbt 的 `MycatBindable.ROOT`、`io.mycat.calcite.spm.ParamHolder.CURRENT_THREAD_LOCAL`、`io.mycat.calcite.sqlfunction.cmpfunction.StrictEqualFunction.INSTANCE`。
- Produces: `public final class MycatCalciteRuntimeHooks implements MycatCalciteRuntime.Hooks`（无参构造；`root()=MycatBindable.ROOT`；`dynamicParamValue(i)=(Comparable)ParamHolder.CURRENT_THREAD_LOCAL.get().getParams().get(i)`；`remapOperator(op)= op.getName().equalsIgnoreCase("<=>") ? StrictEqualFunction.INSTANCE : op`）；`public static void install()` 调 `MycatCalciteRuntime.install(new MycatCalciteRuntimeHooks())`。

- [ ] **Step 1: 写红测**（`MycatCalciteRuntimeHooksTest`）
  - `rootIsMycatBindableRoot`：`new MycatCalciteRuntimeHooks().root() == MycatBindable.ROOT`。
  - `dynamicParamReadsParamHolder`：`ParamHolder.CURRENT_THREAD_LOCAL.get().setData(List.of("a","b"), null)`，断 `hooks.dynamicParamValue(1).equals("b")`；`@AfterEach clear()`。
  - `remapOnlyForNullSafeEqual`：`hooks.remapOperator(EQUALS)==EQUALS`；对 `op.getName()` 为 `<=>` 的算子返回 `StrictEqualFunction.INSTANCE`（若测试期不易构造 `<=>` 算子，退化为断言 remap 对任意非 `<=>` 名恒等 + 直接断 `StrictEqualFunction` 名等于 `<=>` 的映射分支经反射调用）。
  - `installActivatesSeam`：`MycatCalciteRuntimeHooks.install()` 后 `MycatCalciteRuntime.isInstalled()` true 且 `get().root()==MycatBindable.ROOT`（末尾复位）。

- [ ] **Step 2: 跑红** `mvn -o test -pl hbt --% -Dtest=MycatCalciteRuntimeHooksTest` → 缺类。

- [ ] **Step 3: 实现 `MycatCalciteRuntimeHooks`** + 在 `MycatCore` L95 后加一行 `io.mycat.calcite.MycatCalciteRuntimeHooks.install();`。

- [ ] **Step 4: 跑绿** 同 Step 2，Expected 4/0/0。

- [ ] **Step 5: 提交**
  ```bash
  git add hbt/src/main/java/io/mycat/calcite/MycatCalciteRuntimeHooks.java server/src/main/java/io/mycat/MycatCore.java hbt/src/test/java/io/mycat/calcite/shadow/MycatCalciteRuntimeHooksTest.java
  git commit -m "feat(i-debt-3): hbt 侧 MycatCalciteRuntimeHooks 并于 MycatCore 装配（root/param/strictEqual）"
  git checkout -- server/src/main/resources/version.txt
  ```

## Task 3: `RexLiteral` 归一（fork 插 S2 分支 + 删影子 + 行为锁）

**Files:**
- Modify: `calcite-core/src/main/java/org/apache/calcite/rex/RexLiteral.java`（`findValue` L1320-1331）
- Delete: `hbt/src/main/java/org/apache/calcite/rex/RexLiteral.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/RexLiteralDynamicParamTest.java`

**Interfaces:**
- Consumes: `MycatCalciteRuntime.get().dynamicParamValue(int)`（Task 1）。
- Produces: fork `RexLiteral.value/intValue/stringValue(RexNode)` 对 `RexDynamicParam` 经 seam 解析（与影子等价行为）。

- [ ] **Step 1: 写测**（`RexLiteralDynamicParamTest`，JUnit5）
  装配 `MycatCalciteRuntimeHooks.install()`；构造 `RexDynamicParam(0)`；`ParamHolder` setData `List.of(42)`；断 `RexLiteral.intValue(node)==42`。默认 seam（未装钩）时 `assertThrows(AssertionError)`。

- [ ] **Step 2: 删影子** `git rm hbt/src/main/java/org/apache/calcite/rex/RexLiteral.java`（hbt 单测里影子原胜出，删后 fork 类生效）。

- [ ] **Step 3: 跑红** `mvn -o test -pl hbt --% -Dtest=RexLiteralDynamicParamTest` → fork `findValue` 无 `RexDynamicParam` 分支，`intValue` 抛 `AssertionError("not a literal")`，红。

- [ ] **Step 4: 改 fork `findValue`**：在方法体首加分支
  ```java
  if (node instanceof RexDynamicParam) {
    return MycatCalciteRuntime.get().dynamicParamValue(((RexDynamicParam) node).getIndex());
  }
  ```
  （`RexDynamicParam` 同包免 import；`MycatCalciteRuntime` 需 `import org.apache.calcite.mycat.MycatCalciteRuntime;`——fork 内非 io.mycat，闸门安全。）

- [ ] **Step 5: 跑绿** 同 Step 3，Expected 2/0/0；再 `mvn -o test -pl hbt` 全量 Expected 全绿（较基线零回归）。

- [ ] **Step 6: 提交**
  ```bash
  git add calcite-core/src/main/java/org/apache/calcite/rex/RexLiteral.java hbt/src/test/java/io/mycat/calcite/shadow/RexLiteralDynamicParamTest.java
  git commit -m "refactor(i-debt-3): RexLiteral 影子归一——fork findValue 经 seam 解动态参，删旧影子"
  git checkout -- server/src/main/resources/version.txt
  ```

## Task 4: `RexToLixTranslator` 归一（fork 三处 ROOT + visitCall remap，删影子，证伪风险2/4）

**Files:**
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexToLixTranslator.java`（L238、L1241、L1926 三处 `DataContext.ROOT`；`visitCall` 于 `RexImpTable.INSTANCE.get(operator)` 前）
- Delete: `hbt/src/main/java/org/apache/calcite/adapter/enumerable/RexToLixTranslator.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/ShadowRexToLixTranslatorJoinConditionTest.java`（改造为正向 fork-winner 锁）

**Interfaces:**
- Consumes: `MycatCalciteRuntime.get().root()`/`remapOperator(SqlOperator)`（Task 1）。
- Produces: fork `RexToLixTranslator` 生成码以 seam root 为准、`<=>` 经 remap 走 `StrictEqualFunction`；7 参 `translateCondition` 由 fork 原生提供（面② 影子重载随删影子作废）。

- [ ] **Step 1: 改造守卫测**：将 `ShadowRexToLixTranslatorJoinConditionTest` 从"影子缺方法锁"改为"fork 类即胜出类"正向断言——`Class.forName("org.apache.calcite.adapter.enumerable.RexToLixTranslator")` 反射取 7 参 `translateCondition(...boolean)` 必存在（fork 本就有），并断其 `getCodeSource()` 非来自 `hbt` 影子 target（影子删后不可能来自 hbt classes）。

- [ ] **Step 2: fork 三处 ROOT 改走 seam**：L238/L1241/L1926 的 `DataContext.ROOT` → `MycatCalciteRuntime.get().root()`（加 `import org.apache.calcite.mycat.MycatCalciteRuntime;`）。

- [ ] **Step 3: fork `visitCall` 加 remap**：在取 `RexImpTable.INSTANCE.get(operator)` **之前**插 `operator = MycatCalciteRuntime.get().remapOperator(operator);`（默认恒等=不改上游行为；Mycat 装钩后 `<=>`→`StrictEqualFunction`）。

- [ ] **Step 4: 删影子** `git rm hbt/src/main/java/org/apache/calcite/adapter/enumerable/RexToLixTranslator.java`。

- [ ] **Step 5: 单元护栏** `mvn -o test -pl hbt,router`（`--% -Dmaven.test.skip=false`）Expected hbt/router 全绿零回归（含改造守卫测）。

- [ ] **Step 6: e2e 证伪（风险2+4，最关键）**：重打 fat jar 并活体复跑
  ```bash
  # 停旧 8066 后：
  mvn -o package -pl server -am '-Dmaven.test.skip=true'
  git checkout -- server/src/main/resources/version.txt
  # 起 8066（root/123456），活体跑：
  #   testOptimizationProcedure（面② JOIN，须仍 1/0/0、无 NoSuchMethodError）
  #   含 <=> 的等值 e2e（风险2）+ testComplexQuery（风险4）
  ```
  Expected: JOIN 全链路 codegen 正常、`<=>` 结果与旧影子一致、无 `NoSuchMethodError`/行为回归。**若 `<=>` 或 JOIN 行为不等价**：按 spec §7 本类回退为"保留最小显式 fork diff"（仅把 `<=>` 分支硬编进 fork，非整类影子），并把真相写回 spec 修订；不阻塞后续。

- [ ] **Step 7: 提交**
  ```bash
  git add calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexToLixTranslator.java hbt/src/test/java/io/mycat/calcite/shadow/ShadowRexToLixTranslatorJoinConditionTest.java
  git commit -m "refactor(i-debt-3): RexToLixTranslator 影子归一——fork 三处ROOT+visitCall remap经seam，删旧影子"
  git checkout -- server/src/main/resources/version.txt
  ```

## Task 5: 单一版本护栏 + 全量 e2e + 看板收口

**Files:**
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/CalciteSingleVersionGuardTest.java`
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`（I-DEBT-3 / 面③ 收口行）

**Interfaces:**
- Consumes: fork `RexLiteral`/`RexToLixTranslator`（Task 3/4 后唯一来源）。

- [ ] **Step 1: 写哨兵测**（风险5；`maven-enforcer` 离线无缓存故用 codeSource 断言）
  `CalciteSingleVersionGuardTest`：取 `RexLiteral.class.getProtectionDomain().getCodeSource().getLocation().toString()`，断**不含**上游 Maven 布局 `org/apache/calcite/calcite-core/`（即非上游原版 jar），只可为 fork（`io/mycat/calcite-core` 或 reactor `calcite-core/target/classes`）。对 `RexToLixTranslator.class` 同样断言。

- [ ] **Step 2: 跑绿** `mvn -o test -pl hbt --% -Dtest=CalciteSingleVersionGuardTest` Expected 2/0/0（若绿说明 fork 唯一；红则上游回潮，排查依赖树）。

- [ ] **Step 3: 全量 e2e 复核** 活体 8066 复跑 `testOptimizationProcedure`、`testComplexQuery`、`case1`、`testCastFunction` Expected 全绿、无影子相关回归。

- [ ] **Step 4: 看板收口** 在 PROGRESS 记 I-DEBT-3 面③ Phase A 已 Evidenced-Done（`RexLiteral`/`RexToLixTranslator` 归一 + 单一版本护栏；Phase B 待做），订正 §4.4"enforcer 离线不可得→改 codeSource 哨兵"。

- [ ] **Step 5: 提交**
  ```bash
  git add hbt/src/test/java/io/mycat/calcite/shadow/CalciteSingleVersionGuardTest.java docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
  git commit -m "test(i-debt-3): 单一版本 codeSource 哨兵测 + 面③Phase A 看板收口"
  git checkout -- server/src/main/resources/version.txt
  ```

---

## 交付后

进入 `finishing-a-development-branch`：全绿后由用户选 合回 main / PR / 保留。Phase B（`RelMdUtil`(含方向1)/`RelJson`/`RelJsonWriter`/`RelMdRowCount`/`RelMdMaxRowCount`）另立计划，复用本范式。
