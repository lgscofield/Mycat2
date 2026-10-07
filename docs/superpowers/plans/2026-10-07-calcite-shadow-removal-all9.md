# Calcite 同 FQCN 影子副本全量归位删除（I-DEBT-3 续 · 剩余 9 类）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不动 calcite-core fork 源码的前提下，逐个把 hbt 内 9 个 `org.apache.calcite.*` 同 FQCN 影子的 Mycat 钩子搬到 fork 已有扩展点或 Mycat 自有类，删影子让 fork 1.42 胜出。

**Architecture:** 崩溃类（RexToLixTranslator/RelMdUtil）的旧影子签名落后于 fork 调用方 → 归位其 io.mycat 钩子后删影子即让 fork 完整版胜出。行为类（RelMd 元数据/RowCnt/RelJson/RexLiteral 参数）的钩子搬到 fork 扩展点：元数据经 `DrdsSqlCompiler` 已在组装的 provider 链（新 Mycat provider 加在 Default 前抢命中）、`<=>` 经 `MycatCalciteSupport` 的 RexImpTable 算子表承接。每类先过"fork 有无承接钩子的扩展点"可行性闸门；无 → 文档化挂账保留，不擅动 fork。

**Tech Stack:** JDK 21、Calcite 1.42 fork、Maven、JUnit 5、Docker m1-mysql-a/b。

**Spec:** `docs/superpowers/specs/2026-10-07-calcite-shadow-removal-all9-design.md`

## Global Constraints（逐字来自 spec，每个任务隐含包含）

- 不编辑 `calcite-core/**` 源码（编辑禁；重建编译允许）。所有归位在 hbt/server 侧完成。
- fork 保持零 io.mycat：门 `git grep -l "^import io.mycat" calcite-core/src/main/java` → 恒 0。
- 单元套件不得回归：`mvn -o -pl router,executor,hbt test` → router 15/0/0、hbt 36/0/0、executor 0，每类删后必仍全绿。
- join 全量 = 唯一裁判；每类删后 `NoSuchMethodError` 计数单调下降、无新增红签名。
- 不信任既有记录，凭当前实测（file:line + 实跑输出）判定"等价/已删"。
- TDD + 每类一提交；测毕立即 kill proxy + 停 mvn；后端仅 Docker m1-mysql-a/b。
- fat-jar 重建：`mvn -o -pl server -am install "-Dmaven.test.skip=true"`（JAVA_HOME=D:\Program Files\BellSoft\LibericaJDK-21-Full）；proxy：`java -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar server/target/server-1.22-release-jar-with-dependencies.jar`；每起 proxy 必验 8066 OwningProcess=新 PID。

## Review Focus（spec 隐含、下面任务未必逐条测到，最易咬人——每行落到 owning task 的测试）

1. **计划形状漂移**：删 RelMd*/RowCnt 影子后 join/GSI 代价估算若因 provider 命中顺序改变而漂 → Task 2/4/5/6 各在 G-join 断言既有 join 计划结果不变（非仅单元绿）。
2. **SPM 参数逐值等价**：`LIMIT ?`/参数化字面量的行数估算与字面量求值归位后须与旧影子逐值一致 → Task 3/4/5 的行为测试断言具体行数/取值。
3. **`<=>` 与 codegen root 等价**：算子表承接 `<=>` 后生成码语义不变；`MycatBindable.ROOT` 归位不得改元素形状 Object[] 契约 → Task 1 断言 `<=>` 查询结果 + 单列标量投影行形状。
4. **MycatView 元数据供出等价**：provider 给 MycatView 的 collation/uniqueKeys（尤其 merge-sort）须与影子 `instanceof MycatView` 分支逐条一致 → Task 6/7 断言 MycatView merge-sort collation。
5. **序列化往返**：RelJson/RelJsonWriter 删后 MycatView JSON 序列化/反序列化往返不破（SPM baseline 依赖）→ Task 8/9 断言往返等值。

---

## 共享结构（file structure）

- 新建 `hbt/src/main/java/io/mycat/calcite/metadata/MycatRelMetadataProvider.java`：承载 MycatView collation/uniqueKeys + Sort-with-dynamic-param rowcount 的 `MetadataHandler`，经 `ReflectiveRelMetadataProvider.reflectiveSource(...)` 暴露。Task 4 创建，Task 5/6/7 扩展。
- 修改 `hbt/src/main/java/io/mycat/DrdsSqlCompiler.java:540-542`（及 Volcano 侧同类组装）：把 `MycatRelMetadataProvider` 加在 `DefaultRelMetadataProvider.INSTANCE` **之前**。Task 4 首落。
- `hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java`：`<=>`/RexImpTable 算子实现表（Task 1 归位落点）。
- 删除目标（9）：`RexToLixTranslator`、`RelMdUtil`、`RexLiteral`、`RelMdRowCount`、`RelMdMaxRowCount`、`RelMdCollation`、`RelMdUniqueKeys`、`RelJsonWriter`、`RelJson`（均在 `hbt/src/main/java/org/apache/calcite/...`）。
- 账本 `docs/superpowers/...` 无关；留痕写 `.superpowers/sdd/2026-10-07-calcite-shadow-removal-all9/progress.md`（gitignore）。

**通用闸门（每类任务 Step 末都跑，命令一致，故只在各任务里引用命名 G-Unit/G-Join/G-Delete）：**
- **G-Unit**：`mvn -o -pl router,executor,hbt test` → 必须 router 15/0/0、hbt 36/0/0、executor 0。
- **G-Join**：重建 fat-jar + 起 proxy → `mvn -o -pl example test "-Dtest=io.mycat.sql.*"` + `CreateGsiTest#testGsiJoinConverges` → 无新增红、既有 join 结果不变；跑完 kill proxy。
- **G-Delete**：`git grep -l "^import io.mycat" calcite-core/src/main/java` → 空。

---

### Task 1: 删 RexToLixTranslator 影子（`<=>` + MycatBindable.ROOT）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java`（RexImpTable 算子表注册 `<=>`）
- Delete: `hbt/src/main/java/org/apache/calcite/adapter/enumerable/RexToLixTranslator.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/StrictEqualCodegenTest.java`

**Interfaces:**
- Consumes: fork `RexToLixTranslator`（删影子后由 calcite-core 完整版胜出）
- Produces: 无（叶子改动；Task 3 的 SqlBasicCall 连带崩随本类删除自消）

- [ ] **Step 1: 可行性闸门探针（只读，先判后动）** — 读 `MycatCalciteSupport.java`（约 L49 `import RexImpTable`、L112 算子 map）确认 `<=>` 可经该表注册；读 fork `RexToLixTranslator` 的构造/入口确认 `MycatBindable.ROOT`（影子 L178/L2408 用它替换 root 表达式）是否有外部注入口。**若 ROOT 无 fork 注入口 → 本类按默认挂账保留（不删、在账本记现场），仅 `<=>` 部分若能独立归位则先归位、跳过删除，进 Step 5 记录后终止本任务。**

- [ ] **Step 2: 写失败测试（RED = 当前崩溃查询跑通 + `<=>` 语义 + 行形状）**

`hbt` 侧构造含 `<=>` 与单列标量投影的 codegen 计划并断言结果等价；同时在 example 侧记录当前红：`UserCaseTest.case1`、`UserCaseTest.caseSelectSessionValueInt`（现抛 `RexToLixTranslator.translateCast` NoSuchMethodError）、`PushDownFunctionShardingTableTest.testCastFunction:325`（`SqlBasicCall.<init>` 连带）。测试须断：`a <=> b` 三值语义（NULL<=>NULL=true）与删影子前逐条一致；单列标量投影元素形状仍为 Object[]。

- [ ] **Step 3: 运行确认失败** — `mvn -o -pl example test "-Dtest=UserCaseTest#case1+caseSelectSessionValueInt"`（起 proxy）Expected: FAIL `NoSuchMethodError: RexToLixTranslator.translateCast(...)`。kill proxy。

- [ ] **Step 4: 归位 + 删除** — 在 `MycatCalciteSupport` 的 RexImpTable 算子表登记 `StrictEqualFunction.INSTANCE`（承接影子 L2638）；据 Step 1 结论处理 `MycatBindable.ROOT`（有注入口则经其传入；无则挂账不删本类）；删除 `hbt/.../enumerable/RexToLixTranslator.java`（仅当 `<=>` 与 ROOT 均已归位）。

- [ ] **Step 5: 运行确认通过 + 三闸门** — 重跑 Step 3 命令 Expected: PASS；跑 G-Unit、G-Join、G-Delete。挂账分支则账本记 `Task 1: parked (MycatBindable.ROOT 无 fork 注入口) — <现场 file:line> — <代价>`。

- [ ] **Step 6: Commit** — `git add -A hbt && git commit -m "refactor(hbt)!: 归位 <=>/ROOT 钩子并删 RexToLixTranslator 影子（I-DEBT-3续 Task1）"`（挂账分支改提交 `<=>` 归位部分，信息注明影子暂留）。

---

### Task 2: 删 RelMdUtil 影子（isRelDefinitelyEmpty + MycatCalciteSupport/ParamHolder）

**Files:**
- Modify: Mycat 自有 util（承接影子 RelMdUtil 内 `MycatCalciteSupport`/`ParamHolder` 用到的自定义静态方法；优先并入既有 `io.mycat.calcite.MycatCalciteSupport`）
- Delete: `hbt/src/main/java/org/apache/calcite/rel/metadata/RelMdUtil.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/RelMdUtilForkParityTest.java`

**Interfaces:**
- Consumes: fork `RelMdUtil`（删影子后完整版胜出，提供 `isRelDefinitelyEmpty` 等）
- Produces: Mycat 侧自定义元数据静态方法的新家（供后续任务引用；若无自定义则可删方法可省）

- [ ] **Step 1: 可行性闸门探针** — 三方对比 `hbt` 影子 `RelMdUtil` vs fork `calcite-core` `RelMdUtil`：列出影子**独有**（fork 没有）的 Mycat 方法（尤其调用 io.mycat 的）；确认 fork 版含影子缺的那些被 fork 调用的方法（如 `isRelDefinitelyEmpty`）。若影子无 Mycat 独有方法 → 可整删；若有 → 记录需搬的方法签名。

- [ ] **Step 2: 写失败测试** — `mvn -o -pl example` 记录现红：`UserCaseTest.case20220407:1714`（抛 `RelMdUtil.isRelDefinitelyEmpty` NoSuchMethodError）。`RelMdUtilForkParityTest`：对 Step 1 列出的 Mycat 独有方法建断言（迁移前后同入参同结果）。

- [ ] **Step 3: 运行确认失败** — 定向跑 case20220407（起 proxy）Expected: FAIL NoSuchMethodError。kill proxy。

- [ ] **Step 4: 归位 + 删除** — 把影子 RelMdUtil 内 Mycat 独有静态方法搬到 `io.mycat.calcite.MycatCalciteSupport`（或新 `io.mycat.calcite.metadata.MycatMdUtil`），调用方改指新家；删除 `hbt/.../rel/metadata/RelMdUtil.java`。

- [ ] **Step 5: 运行确认通过 + 三闸门** — case20220407 转 PASS；G-Unit、G-Join（重点：join 计划结果不变）、G-Delete。

- [ ] **Step 6: Commit** — `git commit -am "refactor(hbt)!: 上提 RelMdUtil Mycat 定制并删 RelMdUtil 影子（I-DEBT-3续 Task2）"`

---

### Task 3: 删 RexLiteral 影子（SPM 参数化字面量求值）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatRexExecutor.java`（承接参数解算）
- Delete: `hbt/src/main/java/org/apache/calcite/rex/RexLiteral.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/ParamLiteralEvalTest.java`

**Interfaces:**
- Consumes: fork `RexLiteral`（完整版胜出）
- Produces: 参数化字面量求值的 Mycat 侧路径（Task 4/5 的 Sort rowcount 复用同一解参来源）

- [ ] **Step 1: 可行性闸门探针** — 读影子 `RexLiteral.java:1348` 的 `ParamHolder.CURRENT_THREAD_LOCAL.get().getParams().get(index)` 在哪个方法、被谁触发；判 fork `RexLiteral` 有无同名方法/是否已在别处经 `MycatRexExecutor` 解参使该钩子冗余。**核心值类无扩展点可包：若该行为无法经 `MycatRexExecutor` 或 fork 简化器等价承接 → 本类默认挂账保留，账本记现场后终止。**

- [ ] **Step 2: 写失败测试** — `ParamLiteralEvalTest`：构造 `LIMIT ?`/参数化字面量场景，断言删影子前后参数解算结果逐值等价（Review Focus #2）。

- [ ] **Step 3: 运行确认失败**（若影子当前参与某红，记录之；否则以 Step 2 行为基线为绿起点，本类为等价迁移非崩溃修复）

- [ ] **Step 4: 归位 + 删除** — 把参数解算搬到 `MycatRexExecutor` 覆写路径；删除 `hbt/.../rex/RexLiteral.java`；调用方改指 fork `RexLiteral`。挂账分支：不删，账本记 `Task 3: parked (RexLiteral 值类无 fork 钩子) — <现场> — <代价>`。

- [ ] **Step 5: 运行确认通过 + 三闸门**（G-Unit 必绿；G-Join 断言参数化查询行数估算不变）

- [ ] **Step 6: Commit**（或删除挂账 + 文档）

---

### Task 4: 建 MycatRelMetadataProvider + 删 RelMdRowCount 影子

**Files:**
- Create: `hbt/src/main/java/io/mycat/calcite/metadata/MycatRelMetadataProvider.java`
- Modify: `hbt/src/main/java/io/mycat/DrdsSqlCompiler.java:540-542`（provider 加在 Default 前）
- Delete: `hbt/src/main/java/org/apache/calcite/rel/metadata/RelMdRowCount.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/SortParamRowCountTest.java`

**Interfaces:**
- Consumes: fork `ChainedRelMetadataProvider`/`ReflectiveRelMetadataProvider.reflectiveSource`/`DefaultRelMetadataProvider`
- Produces: `MycatRelMetadataProvider`（后续 Task 5/6/7 扩展该类，加 MaxRowCount/Collation/UniqueKeys handler 方法）

- [ ] **Step 1: 可行性闸门探针** — 确认影子 `RelMdRowCount.java:155` 的 `ParamHolder.CURRENT_THREAD_LOCAL` 估行可经"在 provider 链 Default 前注册一个含 `getRowCount(Sort, mq)` 的 Mycat handler"承接（provider 链首命中即胜 → Mycat handler 覆盖 Sort-with-param，其余委托 Default）。

- [ ] **Step 2: 写失败测试** — `SortParamRowCountTest`：构造 `LIMIT ?` 的 Sort，经 `RelMetadataQuery.getRowCount()` 断言取实参估行 == 旧影子值（钉死 Review Focus #2）。

- [ ] **Step 3: 运行确认失败**（红 = 未注册 provider 时估行不含参数实参，与影子不符）

- [ ] **Step 4: 建 provider + 挂链 + 删除** — 新建 `MycatRelMetadataProvider`（内含处理 `Sort` 的 rowCount handler，用 fork 参数解算路径读实参）；在 `DrdsSqlCompiler.java:541` **前**插入 `list.add(0, MycatRelMetadataProvider ...)`（或等价 reflectiveSource 包装）；删 `hbt/.../rel/metadata/RelMdRowCount.java`。

- [ ] **Step 5: 运行确认通过 + 三闸门**（G-Unit、G-Join 重点：join/GSI 代价不因新链漂、既有 join 结果不变）

- [ ] **Step 6: Commit** — `git commit -am "feat(hbt): 建 MycatRelMetadataProvider 承接参数化 Sort 估行并删 RelMdRowCount 影子（I-DEBT-3续 Task4）"`

---

### Task 5: MycatRelMetadataProvider 扩 MaxRowCount + 删 RelMdMaxRowCount 影子

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/metadata/MycatRelMetadataProvider.java`（加 `getMaxRowCount(Sort,...)`）
- Delete: `hbt/src/main/java/org/apache/calcite/rel/metadata/RelMdMaxRowCount.java`
- Test: 扩 `SortParamRowCountTest`

**Interfaces:** Consumes Task 4 的 `MycatRelMetadataProvider` + 挂链；Produces 无。

- [ ] **Step 1: 探针** — 影子 `RelMdMaxRowCount` 的 ParamHolder 用法与 Task 4 同形，确认并入 Mycat provider 的 maxRowCount handler 承接。
- [ ] **Step 2: 写失败测试** — `SortParamRowCountTest` 加断言：`LIMIT ?` 的 `getMaxRowCount` 与旧影子逐值等价。
- [ ] **Step 3: 运行确认失败** — 新断言 FAIL。
- [ ] **Step 4: 扩 handler + 删除** — 在 `MycatRelMetadataProvider` 加 maxRowCount handler；删 `hbt/.../rel/metadata/RelMdMaxRowCount.java`。
- [ ] **Step 5: 运行确认通过 + 三闸门**
- [ ] **Step 6: Commit** — `git commit -am "refactor(hbt)!: Mycat provider 承接 MaxRowCount 并删 RelMdMaxRowCount 影子（I-DEBT-3续 Task5）"`

---

### Task 6: MycatRelMetadataProvider 供 MycatView Collation + 删 RelMdCollation 影子

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/metadata/MycatRelMetadataProvider.java`（加 `getCollation(MycatView,...)`）
- Delete: `hbt/src/main/java/org/apache/calcite/rel/metadata/RelMdCollation.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/MycatViewCollationTest.java`

**Interfaces:** Consumes Task 4 provider；Produces 无（Task 7 继续扩）。

- [ ] **Step 1: 探针** — 影子 `RelMdCollation` 的 `instanceof MycatView → isMergeSort()/getRelNode()/getTraitSet().getCollation()` 分支，逐条列为须在 Mycat provider 复刻的 collation 供出规则（Review Focus #4）。
- [ ] **Step 2: 写失败测试** — `MycatViewCollationTest`：构造 merge-sort 的 MycatView，`RelMetadataQuery.collations(rel)` 断言 == 影子分支结果（含 merge-sort 有序性、底层 Sort 透传）。
- [ ] **Step 3: 运行确认失败**
- [ ] **Step 4: 扩 handler + 删除** — 在 `MycatRelMetadataProvider` 加 `getCollation(MycatView, mq)` handler（复刻影子规则）；删 `hbt/.../rel/metadata/RelMdCollation.java`。
- [ ] **Step 5: 运行确认通过 + 三闸门**（G-Join 重点：MycatView 相关 join/sort 计划 collation 不变）
- [ ] **Step 6: Commit** — `git commit -am "feat(hbt): provider 供 MycatView collation 并删 RelMdCollation 影子（I-DEBT-3续 Task6）"`

---

### Task 7: MycatRelMetadataProvider 供 MycatView UniqueKeys + 删 RelMdUniqueKeys 影子

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/metadata/MycatRelMetadataProvider.java`（加 `getUniqueKeys(MycatView,...)`）
- Delete: `hbt/src/main/java/org/apache/calcite/rel/metadata/RelMdUniqueKeys.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/MycatViewUniqueKeysTest.java`

**Interfaces:** Consumes Task 4 provider。

- [ ] **Step 1: 探针** — 列影子 `RelMdUniqueKeys` 的 `instanceof MycatView` 分支供出的唯一键规则。
- [ ] **Step 2: 写失败测试** — `MycatViewUniqueKeysTest`：对 MycatView 断言 `areColumnsUnique` == 影子结果。
- [ ] **Step 3: 运行确认失败**
- [ ] **Step 4: 扩 handler + 删除** — 加 `getUniqueKeys(MycatView, mq)` handler；删 `hbt/.../rel/metadata/RelMdUniqueKeys.java`。
- [ ] **Step 5: 运行确认通过 + 三闸门**
- [ ] **Step 6: Commit**

---

### Task 8: 删 RelJsonWriter 影子（MycatView 序列化）

**Files:**
- Modify: Mycat 侧序列化承接点（候选：MycatView 自带 toJson / Mycat 自有 RelJsonWriter 子类）
- Delete: `hbt/src/main/java/org/apache/calcite/rel/externalize/RelJsonWriter.java`
- Test: `hbt/src/test/java/io/mycat/calcite/shadow/MycatViewJsonRoundTripTest.java`

**Interfaces:** Consumes fork `RelJsonWriter`；Produces 序列化承接方式（Task 9 RelJson 复用）。

- [ ] **Step 1: 可行性闸门探针** — 读影子 `RelJsonWriter` 的 `MycatView` 分支（如何写 MycatView 的 JSON）；判 fork 是否有类型注册/多态 toJson 扩展点承接（`RelJsonWriter` 的 `json(Map)` 路径或 MycatView 自实现序列化）。**无扩展点 → 默认挂账保留。**
- [ ] **Step 2: 写失败测试** — `MycatViewJsonRoundTripTest`：MycatView 序列化→反序列化往返等值（Review Focus #5）。
- [ ] **Step 3: 运行确认失败**
- [ ] **Step 4: 归位 + 删除**（或挂账）
- [ ] **Step 5: 运行确认通过 + 三闸门**
- [ ] **Step 6: Commit**

---

### Task 9: 删 RelJson 影子（SchemaInfo/MycatCalciteSupport 反序列化）

**Files:**
- Modify: Mycat 侧反序列化承接点
- Delete: `hbt/src/main/java/org/apache/calcite/rel/externalize/RelJson.java`
- Test: 扩 `MycatViewJsonRoundTripTest`

**Interfaces:** Consumes Task 8 承接方式 + fork `RelJson`。

- [ ] **Step 1: 可行性闸门探针** — 读影子 `RelJson.java` 的 `io.mycat.SchemaInfo`/`MycatCalciteSupport` 用法（反序列化时按类型重建 MycatView/rel）；判 fork 有无 `RelJson` 类型映射扩展点。**无 → 挂账保留。**
- [ ] **Step 2: 写失败测试** — 往返测试补 MycatView 反序列化重建断言。
- [ ] **Step 3: 运行确认失败**
- [ ] **Step 4: 归位 + 删除**（或挂账）
- [ ] **Step 5: 运行确认通过 + 三闸门**
- [ ] **Step 6: Commit**

---

### Task 10: 收口核验与看板回写

**Files:**
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`（I-DEBT-3 行 + §131 B 组勾选 + 孪生对计数 + 变更日志）

- [ ] **Step 1: 孪生对归零核验** — `Glob hbt/**/org/apache/calcite/**/*.java` 仅剩 6 个 Mycat 真自增类；任何保留影子在 PROGRESS 明确挂账（类名 + 钩子现场 + "fork 缺扩展点"）。
- [ ] **Step 2: 全量 sweep 复验** — 起 proxy 跑 example 全量 + `mvn -o -pl router,executor,hbt test`；`NoSuchMethodError` 计数对比开工基线（应显著下降）；记录最终 `tests/failures/errors/skipped`。
- [ ] **Step 3: kill proxy**
- [ ] **Step 4: 回写 PROGRESS + Commit** — `git commit -am "docs(progress): I-DEBT-3续 影子归位删除收口（删<N>类/挂账<M>类、NoSuchMethodError 消解、单元全绿）"`

## Self-Review（本计划对 spec 的覆盖自查）

- **spec 覆盖**：9 类 → Task 1-9 逐类，Task 10 收口；可行性闸门 → 每类 Step 1；三件套回归门 → G-Unit/G-Join/G-Delete；默认不松绑编辑 fork → 各挂账分支；成功判据 → Task 10 Step 1。无缺口。
- **步骤扫描**：探针/测试/归位/删除/闸门/提交各为一动作。删除目标、归位落点（`DrdsSqlCompiler:540`、`MycatCalciteSupport`、新 `MycatRelMetadataProvider`）为真实 file:line；具体 handler 方法体、参数解算细节留执行者据 fork 实源写（探针步骤承接）。
- **类型一致**：`MycatRelMetadataProvider` 在 Task 4 定义、5/6/7 扩展；`getRowCount/getMaxRowCount/collations/areColumnsUnique` 名与 fork handler 接口一致。
- **风险**：Task 1/3/8/9 明确"无 fork 钩子则挂账"分支（RexLiteral 值类、codegen root、序列化），不与"必删"硬承诺矛盾——符合 spec 方向 A 尽力而为 + 逐类裁定。
