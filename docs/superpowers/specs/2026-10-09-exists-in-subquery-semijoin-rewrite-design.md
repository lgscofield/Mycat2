# EXISTS / IN 相关子查询半连接重写 设计

**状态：** 待用户终审（brainstorming 产出，尚未 writing-plans）
**关联债务：** I-DEBT-30 面③ 后续（影子版本归一 Phase A 揭示的既存功能缺口）
**分支背景：** `feat/i-debt-30-shadow-unification-phase-a` 删 RelMdUtil 影子后，规划期早爆被解开，暴露更内层的 EXISTS/IN 相关子查询 codegen 崩溃。

## Goal

让 Mycat2 代理在**分片表作外层、单实例表作内层**的场景下，正确执行
`WHERE [NOT] EXISTS (相关子查询)` 与 `WHERE x IN (子查询)`，使
`example/SqlFunctionTest#testComplexQuery` 的三条子查询断言
（L407 EXISTS / L408 NOT EXISTS / L410 IN）转绿，且不改动 fork（calcite-core）。

## 成功判定（Definition of Done）

- `testComplexQuery` 的 L407 / L408 / L410 三条活体转绿（连 8066）。
- **零回归**：既有绿测保持绿——`mvn -o test -pl hbt,router`（hbt 81/0/0 + router
  BUILD SUCCESS）、`SqlFunctionTest#testOptimizationProcedure`（JOIN）、CAST 相关
  e2e、聚合类用例全绿。
- **fork 零改动**为主线达成项；仅在回落阶梯 C 触发时才允许带注释 + seam 的最小 fork diff。
- fork 闸门维持：`calcite-core` 内 `^import io.mycat` 计数 = 0。

## 非目标

- L406 标量子查询 `SELECT (subquery) FROM ...`（作者原标 `; todo`）——本轮完成后再单独做一轮**分析评估**（非实现）。
- 跨物理实例的 EXISTS 下推优化。
- 执行性能调优。
- 通用 `ANY/SOME/ALL` 量词子查询（本轮只做 EXISTS / NOT EXISTS / IN）。

## 背景与根因（实证）

### 崩溃现象（当前分支，活体）

`testComplexQuery` L407 执行时代理侧流式执行抛：

```
org.apache.calcite.runtime.CalciteException: Cannot convert [Ljava.lang.Object;@... to long
  at org.apache.calcite.runtime.SqlFunctions.toLong(SqlFunctions.java:5210)
  at Baz$1$1$1.moveNext
  at org.apache.calcite.linq4j.EnumerableDefaults.distinct(EnumerableDefaults.java:463)
  at org.apache.calcite.linq4j.DefaultEnumerable.distinct(DefaultEnumerable.java:207)
  ...
  at io.mycat.calcite.PrepareExecutor...
```

生成的 LIX：外层 hash join 的 keySelector `SqlFunctions.toLong(v[0])` 把一行的第 0 列当标量
long，但该列运行时实为嵌套 `Object[]`；内层相关子查询被规整成
`RxBuiltInMethodImpl.toEnumerable(child).distinct()`，其 `current()` 返回
`FlatLists.COMPARABLE_EMPTY_LIST`。

### 非本分支回归（main 基线实证，2026-10-09）

`git checkout main` 重建 fat jar、活体跑同一 `testComplexQuery`：同样红，但崩在**更外层规划期**：

```
java.lang.NoSuchMethodError: 'boolean org.apache.calcite.rel.metadata.RelMdUtil.isRelDefinitelyNotEmpty(...)'
```

即：EXISTS 在 main（旧影子胜出）上**从未跑绿**，只是在 RelMdUtil 影子缺 fork 调用方方法时
于规划阶段提前崩溃，从未走到 codegen。本分支删除 RelMdUtil 影子解开了规划期早爆，才把
失败**推进**到 codegen 层。故本设计处理的是一个**既存功能缺口**，非影子归一引入的回归；
"回退删影子"只会把它重新藏回 RelMdUtil 早爆之后。

### 架构现状（相关子查询执行链）

- **子查询去相关（fork）**：`DrdsSqlCompiler.getSubQueryProgram()` 注册
  `CoreRules.FILTER_SUB_QUERY_TO_CORRELATE` / `PROJECT_SUB_QUERY_TO_CORRELATE` /
  `JOIN_SUB_QUERY_TO_CORRELATE`。EXISTS 谓词 → `LogicalFilter(EXISTS)` → 转成
  `LogicalCorrelate`，右侧为 `RelOptUtil.createExistsPlan` 产出的
  `LogicalAggregate(group by 全部列 = 等价 DISTINCT)`。
- **物理转换（Mycat）**：`MycatCorrelateRule` 把 `Correlate` → `MycatCorrelate`；
  `MycatCorrelate.implement` 经 `EnumerableDefaults.correlateJoin` + 右侧聚合的 codegen
  生成执行码。分片外层 travelrecord 走 union-all 拆片，内层 company 单实例下推。
- **专用半连接算子（Mycat，已存在）**：`MycatJoinRule.tryHashJoin/tryNLJoin` 依
  `join.isSemiJoin()` 路由到 `MycatSemiHashJoin` / `MycatNestedLoopSemiJoin`；二者
  `implement` 分别委托到 `MycatHashJoin` / `MycatNestedLoopJoin`（**INNER JOIN 已活体绿**）。

### 根因判定

坏点特定于"EXISTS 落成 Correlate + Aggregate(distinct) 后，codegen 对**单字段行**的
flatten 与 fork 1.42 的 `FlatLists`/`PhysType` 行表示不一致"，把整行 `Object[]` 误当标量喂给
`toLong`。同表的 INNER JOIN 绿，说明**走 join 算子即可绕开该坏路径**。

## 架构（方案 A：重写为 SEMI/ANTI Join，复用绿算子）

核心：把 EXISTS/IN 相关子查询在**逻辑规划阶段**规整成
`LogicalJoin(joinType = SEMI | ANTI)`（相关键作等值连接条件），交由 Mycat 已有的
`MycatSemiHashJoin`/`MycatNestedLoopSemiJoin` → `MycatHashJoin`/`MycatNestedLoopJoin`
执行；**半连接天然"每左行至多一次"，无需 aggregate/distinct**，从而绕开崩溃的
单字段行路径。**全程 Mycat 侧，不动 fork。**

### 重写落点

在 `DrdsSqlCompiler` 的规划里新增/启用一条**去相关规则**，把
`LogicalCorrelate`（EXISTS/IN 形状，右侧为 Filter/Aggregate 的半连接模式）改写为
`LogicalJoin(SEMI/ANTI)`。落点选定：

- **首选**：放入阶段一的**有界 HepPlanner 确定性下推 pass**（`runLogicalPushdownFixedPoint`
  附近），受 `matchLimit` 保护，避免触发 I-DEBT-10 已根治的 Volcano 规划爆炸。
- 备选（非首选）：采用 Calcite LEFT_MARK join（1.36+ 的 EXISTS/IN 表达），新增
  `MycatLeftMarkJoin` 物理算子 + `MycatLeftMarkJoinRule`。改动面更大，仅当首选落点
  无法在不破坏现有去相关处成立时启用。

> 精确落点（是拦在 `FILTER_SUB_QUERY_TO_CORRELATE` 之前直接产出 join，还是在其之后把
> correlate 形状再规整成 join）由**实现计划的第一个 TDD 任务钉死 RelNode 实测形状后定稿**。

### 语义映射

| SQL | 关系形状 | 物理算子 | 去重 |
|---|---|---|---|
| `WHERE EXISTS (corr)` | SEMI Join on 相关键 | `MycatSemiHashJoin`/`MycatNestedLoopSemiJoin` | SEMI 天然每左行一次 |
| `WHERE NOT EXISTS (corr)` | ANTI Join on 相关键 | 同上（ANTI） | ANTI 天然 |
| `WHERE x IN (subq)` | SEMI Join(`x = subq.col`) | 同上 | 需处理 NULL |

- **相关键**：来自子查询内引用外层行的等值谓词（`t.id = c.id`）；支持复合多列。
- **IN 的三值/NULL 语义**：MySQL 中 `x IN (子查询)` 当子查询含 NULL 且 x 不匹配任何非 NULL 值时结果为
  `UNKNOWN`（行被过滤掉）；`NOT IN` 更严格。实现以 `checkValue` 的期望值（如 L410 期望
  `(1,999,...)`）为准逐条锁死，测试驱动确定边界，不臆测。

## 测试策略（TDD 红先行）

1. **计划层单测（hbt）**：以现有 hbt 计划/codegen 测试 harness 构造三查询，
   断言最终 RelNode 含 `MycatSemiHashJoin`/`MycatNestedLoopSemiJoin`（或 `LogicalJoin`
   的 SEMI/ANTI），**不含** Correlate+Aggregate(distinct)。
   RED：现状断到 Correlate → 先失败。
2. **SEMI/ANTI 执行正确性单测**：验证绿 join 算子对 SEMI/ANTI 的 implement 是否正确
   （INNER 已绿不代表 SEMI 已测）；若此处暴露缺陷，修复集中在 Mycat 算子侧（仍零 fork）。
3. **codegen 单测**：重写后生成的 LIX 不再出现把整行当标量的 `toLong(v[0])`（直接跑通生成）。
4. **活体 e2e**：连 8066，`SqlFunctionTest#testComplexQuery` 的 L407/L408/L410 转绿；
   `git checkout -- server/src/main/resources/version.txt` 收尾。
5. **全量回归**：`mvn -o test -pl hbt,router` 全绿 + `testOptimizationProcedure`(JOIN)/CAST/
   聚合类保持绿；fork 闸门 `import io.mycat`=0。

## 回落阶梯

- **A（主线）**：重写 EXISTS/IN → SEMI/ANTI join，复用绿算子，零 fork。
- **B（A 不可行时）**：若某 EXISTS/IN 形状无法等价重写为可执行的 SEMI/ANTI join（或重写后
  仍崩），改为修 Mycat 物理实现者层——`MycatCorrelate`/Aggregate 实现者对单字段行的
  flatten / keyProjection 生成，直击行表示 bug。覆盖面更广但风险面更大，须回归保护。
- **C（仅兜底）**：若根因确在 fork 共享类（`PhysType`/`EnumerableAggregateImplementor`）
  且无法在 Mycat 侧旁路，加最小显式 fork diff（优先 seam 扩展点 + 注释清楚），
  与"最小改 fork"原则一致但作为最后手段。

阶梯推进规则：实现计划第一个任务先钉死 RelNode 坏形状 + 验证绿 join 算子对 SEMI/ANTI
的可行性；据实测在 A→B→C 间择一，并把选择记入 SDD ledger 的 `Ruling:`。

## 风险与开放问题

1. **SEMI/ANTI 在 Mycat join 算子的执行是否已正确**（INNER 绿不代表 SEMI 绿）——
   开放问题，测试策略 #2 专门验证；若否，实际修复重心落在算子侧（A 仍零 fork）。
2. **分片外层 × 单实例内层的分布/物化交互**：重写为 join 后，`MycatView` 分布与
   `MatierialRewriter` 物化是否正确处理 SEMI（内层需物化一次）——计划任务实测。
3. **规划收敛**：新规则必须留在有界 HepPlanner pass 内，不得把子查询重写引入 Volcano
   的 Alternatives 生长（I-DEBT-10 教训）。
4. **IN 的 NULL 语义边界**：以期望值实证锁定，不臆测。
5. **去正确性等价**：SEMI 重写必须保证相关谓词恰为连接键；非等值/复杂相关谓词本轮不支持
   （落到回落阶梯或明确拒绝并报告）。

## 关键文件（预期改动，最终以计划为准）

- `hbt/src/main/java/io/mycat/DrdsSqlCompiler.java`：规划 pass 内新增去相关/子查询→join 规则注册。
- `hbt/src/main/java/io/mycat/calcite/rules/`：新增规则类（如 `MycatSubQueryToSemiJoinRule`）。
- `hbt/src/main/java/io/mycat/calcite/MycatRules.java`：注册新规则。
- `hbt/src/test/java/io/mycat/...`：计划/codegen 单测。
- `example/src/test/java/io/mycat/sql/SqlFunctionTest.java`：验收 L407/L408/L410（现有）。
