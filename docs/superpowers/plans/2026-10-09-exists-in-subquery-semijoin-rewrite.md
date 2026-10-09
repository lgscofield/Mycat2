# EXISTS / IN 相关子查询半连接重写 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:executing-plans（本会话内联执行，推荐）或 superpowers:subagent-driven-development 逐任务实现。Steps 用 checkbox（`- [ ]`）跟踪。

**Goal:** 把 `WHERE [NOT] EXISTS (相关子查询)` 与 `WHERE x IN (子查询)` 在逻辑规划阶段去相关成 `LogicalJoin(SEMI/ANTI)`，复用 Mycat 已有的半连接算子与 codegen，使 `SqlFunctionTest#testComplexQuery` 的 L407/L408/L410 三条活体转绿。

**Architecture:** 在 `DrdsSqlCompiler.getSubQueryProgram()` 三条 `*_SUB_QUERY_TO_CORRELATE` 规则之后，新增一条有界 HepPlanner 去相关规则 `MycatExistsInSubQueryToSemiJoinRule`，匹配 `Correlate`+`Aggregate(全列分组=distinct)` 的 EXISTS/IN 坏形状，重建为 `LogicalJoin(SEMI|ANTI)`；下游 `MycatJoinRule` 依 `isSemiJoin()` 路由到 `MycatSemiHashJoin`/`MycatNestedLoopSemiJoin`，其 `implement` 委托 `MycatHashJoin.implementHashSemiJoin`（已存在的 `BuiltInMethod.SEMI_JOIN/ANTI_JOIN` codegen），绕开崩溃的 correlate+distinct 路径。全程 Mycat 侧（hbt），零 fork。

**Tech Stack:** Java 21、Apache Calcite 1.42（fork calcite-core）、JUnit 5、Maven（离线 `-o`）。

**Spec:** [docs/superpowers/specs/2026-10-09-exists-in-subquery-semijoin-rewrite-design.md](../specs/2026-10-09-exists-in-subquery-semijoin-rewrite-design.md) —— 计划从该 spec 论证，执行者两份都读。

## Global Constraints

- **零 fork**：`calcite-core` 内不得出现 `^import io.mycat`（闸门计数恒为 0）；规则、算子改动全部落在 `hbt`。
- **构建环境**：`JAVA_HOME=D:\Program Files\BellSoft\LibericaJDK-21-Full`；PowerShell 7 用绝对 Windows 路径；每次 `mvn` 之后 `git checkout -- server/src/main/resources/version.txt`；重编 fat jar 前先停 8066 上的 java 进程。
- **改 calcite-core 后**须 `mvn -o install -pl calcite-core '-Dmaven.test.skip=true'`（本计划主线不改 fork）。
- **fat jar**：`mvn -o package -pl server -am '-Dmaven.test.skip=true'`。
- **活体测**：`mvn -o test -pl example "-Dtest=SqlFunctionTest#testComplexQuery"`（同类多方法用 `+` 连接）。
- **提交**：中文 commit message 经 `.qoder/commit-msg.txt`（UTF-8）+ `git commit -F` 规避 PowerShell 乱码；`git add` 勿混入已 staged 删除路径（会整体 fatal）。
- **回落阶梯**：任务实测若证明 EXISTS/IN 形状无法等价重写为可执行 SEMI/ANTI join，或重写后仍崩，按 spec「回落阶梯」B（修 `MycatCorrelate`/Aggregate 实现者行格式）→ C（最小带注释 fork）推进，并把每次抉择记入 SDD ledger 的 `Ruling:` 行。

## Review Focus

（spec 隐含、但各任务测试未必覆盖、最可能伤到真实使用者的输入类——最终整支复审逐条核）

- **SEMI/ANTI codegen 的真实正确性**：`implementHashSemiJoin` 用 `generateAccessor(joinKeys)` 作键选择器；INNER JOIN 绿不代表 SEMI/ANTI 分支跑通过——须有直接执行测证（Task 3）。
- **分片外层 × 单实例内层的分布/物化**：去相关成 join 后，`MycatView` 分布与 `MatierialRewriter` 是否正确处理 SEMI 内层物化一次——活体 e2e（Task 4）暴露。
- **NULL 三值语义**：`x IN (含 NULL 子查询)` 与 `NOT IN` 的边界，以 L408/L410 期望值实证锁定，不臆测。
- **非等值/复合相关谓词**：本规则只处理相关键为等值的 EXISTS/IN；非等值形状必须**不改写**（保持原 correlate），不得误产错误 SEMI。
- **规划收敛**：新规则留在 `getSubQueryProgram` 有界 HepPlanner 内，不得进 Volcano Alternatives（I-DEBT-10 教训）。

---

### Task 1: 钉死坏形状 + 去相关规则（EXISTS→SEMI）

**Files:**
- Create: `hbt/src/main/java/io/mycat/calcite/rules/MycatExistsInSubQueryToSemiJoinRule.java`
- Modify: `hbt/src/main/java/io/mycat/DrdsSqlCompiler.java`（`getSubQueryProgram()` L670-677，三条 correlate 规则后 `addRuleInstance(...)`）
- Test: `hbt/src/test/java/io/mycat/calcite/rules/MycatExistsInSubQueryToSemiJoinRuleTest.java`

**Interfaces:**
- Consumes: fork `LogicalCorrelate`（`getJoinType()`、`getRight()`、`getVariableSet()`）、`LogicalAggregate`（`getGroupSet()`、`getAggCallList()` 空）、`LogicalFilter`、`RexCorrelVariable`；`LogicalJoin.create(left, right, condition, JoinRelType.SEMI)`。
- Produces: `public final class MycatExistsInSubQueryToSemiJoinRule extends RelOptRule`，暴露 `public static final MycatExistsInSubQueryToSemiJoinRule INSTANCE = new MycatExistsInSubQueryToSemiJoinRule();`；`onMatch` 把 EXISTS 形状 `Correlate`(右=`Aggregate(全列分组,无 aggCall)`→`Filter(corVar.字段 = 外层字段)`) 重写为 `LogicalJoin(left, 该 Filter 的 input 去掉 corVar 引用后的等价 innerRel, SEMI)`，等值相关谓词升为 join condition。

> **落点先决**：Step 1 之前先用活体 `explain` 导出 L407 查询在 `getSubQueryProgram` 之后的真实 RelNode 子树形状，贴进本任务的 ledger，据实调整 matcher 的 operation descriptor（本 spec/plan 依 `RelOptUtil.createExistsPlan` 与崩溃栈推断形状为 `Correlate→Aggregate(group all)→Filter(eq corVar)`；若实测不同，以实测为准改 matcher，记 `Ruling:`）。

- [ ] **Step 1: 导出真实坏形状（read-only 侦察，落 ledger）**

  停 8066 → 重建分支 fat jar → 起服务 → 对 8066 执行：
  ```
  explain select * from db1.travelrecord as t where EXISTS (select id from db1.company as c where t.id = c.id);
  ```
  记录返回的 MycatRel 子树文本，确认是否含 `MycatCorrelate`（当前），据此定稿 matcher。Run: `mvn -o package -pl server -am '-Dmaven.test.skip=true'` 后起 jar 经客户端执行 explain。
  Expected: 拿到计划树文本；当前显示 Correlate+Aggregate（或实测等价形状）。

- [ ] **Step 2: 写失败单测（EXISTS→SEMI）**

  `MycatExistsInSubQueryToSemiJoinRuleTest` 用 `RelBuilder` 构出 Step 1 定稿的 `Correlate+Aggregate(distinct)+Filter(eq corVar)` 形状，跑规则后断言根为 `LogicalJoin` 且 `getJoinType()==SEMI`、inner 不含 corVar 引用、等值条件进 join condition。
  ```java
  @Test void existsBecomesSemiJoin() {
      RelNode correlate = buildExistsCorrelateShape(); // 依 Step 1 实测定构树
      RelNode out = applyRule(correlate);              // HepPlanner + INSTANCE
      assertTrue(out instanceof LogicalJoin, "应重写为 LogicalJoin");
      assertEquals(JoinRelType.SEMI, ((Join) out).getJoinType());
  }
  ```
  Expected: 编译失败（`MycatExistsInSubQueryToSemiJoinRule` 未定义）→ 先建空壳类使编译通过，测试 RED（断言 `LogicalJoin` 失败，因规则尚未产出）。

- [ ] **Step 3: 运行验证 RED**

  Run: `mvn -o test -pl hbt "-Dtest=MycatExistsInSubQueryToSemiJoinRuleTest"`
  Expected: FAIL（根节点仍是 Correlate，非 LogicalJoin SEMI）。

- [ ] **Step 4: 实现 `MycatExistsInSubQueryToSemiJoinRule.onMatch`**

  匹配 Step 1 定稿形状；从内层 `Filter` 抽出 `corVar.col = outer.col` 等值谓词作 join keys；剔除 Aggregate 的全列 distinct（SEMI 天然每左行至多一次，无需去重）；`call.transformTo(LogicalJoin.create(left, newRight, semiCondition, JoinRelType.SEMI))`。非等值/复合不匹配则不改（保 correlate）。

- [ ] **Step 5: 运行验证 GREEN**

  Run: `mvn -o test -pl hbt "-Dtest=MycatExistsInSubQueryToSemiJoinRuleTest"`
  Expected: PASS。

- [ ] **Step 6: 注册进 getSubQueryProgram + 跑 hbt 全测**

  在 `getSubQueryProgram()` builder 三条 correlate 规则后 `.addRuleInstance(MycatExistsInSubQueryToSemiJoinRule.INSTANCE)`。
  Run: `mvn -o test -pl hbt`
  Expected: BUILD SUCCESS，无回归。

- [ ] **Step 7: Commit**
  ```bash
  git add hbt/src/main/java/io/mycat/calcite/rules/MycatExistsInSubQueryToSemiJoinRule.java hbt/src/main/java/io/mycat/DrdsSqlCompiler.java hbt/src/test/java/io/mycat/calcite/rules/MycatExistsInSubQueryToSemiJoinRuleTest.java
  git commit -F .qoder/commit-msg.txt   # 消息: feat(i-debt-30-aspect3): EXISTS 相关子查询去相关为 SEMI Join
  ```

---

### Task 2: NOT EXISTS→ANTI 与 IN→SEMI 映射

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/rules/MycatExistsInSubQueryToSemiJoinRule.java`
- Test: `hbt/src/test/java/io/mycat/calcite/rules/MycatExistsInSubQueryToSemiJoinRuleTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 1 的 `INSTANCE` 与 matcher 骨架。
- Produces: 同一规则扩到 `NOT EXISTS → JoinRelType.ANTI`、`x IN (subq) → JoinRelType.SEMI`（`x = subq.col` 作等值键）；`NOT IN` 的 NULL 语义**不在本规则处理**（保持原路径，见 Step 说明与风险）。

- [ ] **Step 1: 追加失败用例（ANTI + IN）**
  ```java
  @Test void notExistsBecomesAntiJoin() { /* 断 getJoinType()==ANTI */ }
  @Test void inSubQueryBecomesSemiJoin() { /* x IN (subq) → SEMI，键 x=col */ }
  ```
  Run: `mvn -o test -pl hbt "-Dtest=MycatExistsInSubQueryToSemiJoinRuleTest"`
  Expected: 新增两测 FAIL（现仅处理 EXISTS-SEMI）。

- [ ] **Step 2: 扩展 matcher**：识别 NOT EXISTS 的 correlate 形状 → ANTI；识别 `IN` 的 `= ANY` 形状 → SEMI。保持非等值不改写。

- [ ] **Step 3: 运行验证 GREEN**
  Run: `mvn -o test -pl hbt "-Dtest=MycatExistsInSubQueryToSemiJoinRuleTest"`
  Expected: 全部 PASS。

- [ ] **Step 4: Commit**（消息: `feat: NOT EXISTS→ANTI 与 IN→SEMI 去相关映射`）

---

### Task 3: 物理路由与 SEMI/ANTI codegen 执行正确性（验证 A 前提）

**Files:**
- Test: `hbt/src/test/java/io/mycat/calcite/rules/MycatSemiAntiJoinExecTest.java`（新建，直接构造物理计划并执行）
- Modify（仅在暴露缺陷时）: `hbt/src/main/java/io/mycat/calcite/rules/MycatJoinRule.java` / `hbt/.../physical/MycatHashJoin.java`

**Interfaces:**
- Consumes: `MycatJoinRule`（`isSemiJoin()` → `MycatSemiHashJoin`/`MycatNestedLoopSemiJoin`）、`MycatHashJoin.implementHashSemiJoin`。
- Produces: 证据表明去相关产出的 `LogicalJoin(SEMI/ANTI)` 经 CBO→物理后，`implementHashSemiJoin` 生成的执行码产出正确行集（左列、无整行 `Object[]`→标量误转）。

- [ ] **Step 1: 写执行断言单测（红先行）**：用 `RelBuilder` 造 `LogicalJoin(valuesL, valuesR, SEMI)`/`ANTI`，经 `optimizeWithCBO` 得物理树，断言含 `MycatSemiHashJoin` 或 `MycatNestedLoopSemiJoin`，并 bind 执行取结果，断言行集 = 期望（如 SEMI 每左行至多一次、ANTI 仅无匹配左行）。
  Run: `mvn -o test -pl hbt "-Dtest=MycatSemiAntiJoinExecTest"`
  Expected: FAIL 或 PASS——若 FAIL 暴露 `implementHashSemiJoin`/路由缺陷，进入 Step 2 修 Mycat 侧（仍零 fork）。

- [ ] **Step 2:（若 Step1 红）修物理路由/codegen**：定位键选择器或行格式问题，最小改动 Mycat 算子，复跑至 GREEN。

- [ ] **Step 3: 运行 hbt 全测**
  Run: `mvn -o test -pl hbt`
  Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**（消息: `test: 验证 SEMI/ANTI 物理路由与 codegen 正确执行`，含任何 Mycat 侧修复）

---

### Task 4: 活体 e2e 转绿 + 全量回归 + fork 闸门

**Files:**
- 无新增；验收 `example/src/test/java/io/mycat/sql/SqlFunctionTest.java` L407/L408/L410（现有）。

**Interfaces:**
- Consumes: Task 1-3 全部产物。
- Produces: 三条活体断言绿 + 零回归证据。

- [ ] **Step 1: 停 8066 → 重建 fat jar**
  Run: `mvn -o package -pl server -am '-Dmaven.test.skip=true'`; `git checkout -- server/src/main/resources/version.txt`
  Expected: BUILD SUCCESS。

- [ ] **Step 2: 起分支 jar，跑三条活体**
  起服务后 Run: `mvn -o test -pl example "-Dtest=SqlFunctionTest#testComplexQuery"`
  Expected: L407/L408/L410 转绿；`testComplexQuery` 仅剩 L406 标量子查询 `; todo`（非目标，不触发断言失败——若其当前即被跳过/注释则保持）。

- [ ] **Step 3: 全量回归**
  Run: `mvn -o test -pl hbt,router` + `mvn -o test -pl example "-Dtest=SqlFunctionTest#testOptimizationProcedure"`
  Expected: 全绿，无新红。

- [ ] **Step 4: fork 闸门**
  Run: `git grep -c "^import io.mycat" -- calcite-core/src/main/java`
  Expected: 无匹配（计数 0）。

- [ ] **Step 5: 收尾**：`git checkout -- server/src/main/resources/version.txt`；停服务。

- [ ] **Step 6: Commit**（若有文档/ledger 更新；消息: `test(i-debt-30-aspect3): EXISTS/IN 活体 e2e L407/408/410 转绿`）

---

### Task 5: 收尾——finishing-a-development-branch

- [ ] **Step 1** 全量套件复跑（`mvn -o test -pl hbt,router` + example 关键法）。
- [ ] **Step 2** 用 superpowers:finishing-a-development-branch 呈现 merge/PR/keep 三选项，交用户裁定。

---

## 假设与取舍

- 坏形状按 `RelOptUtil.createExistsPlan`（EXISTS 非 outerJoin→全列分组 Aggregate = distinct）与崩溃栈推断为 `Correlate→Aggregate(group all)→Filter(eq corVar)`；Task 1 Step 1 的活体 `explain` 为最终权威，据实校准 matcher，差异记 ledger `Ruling:`。
- 本规则仅覆盖**等值相关键**的 EXISTS/NOT EXISTS/IN；非等值/复杂形状保持原 correlate（不误产 SEMI），留回落阶梯。
- `NOT IN` 的严格 NULL 三值语义不在本轮（spec 非目标聚焦 L407/408/410 三条，L408 为 NOT EXISTS 非 NOT IN）。
- 分布/物化（分片外层×单实例内层）在 Task 3/4 实测暴露；若 SEMI 内层需物化，复用 `MatierialRewriter` 现有对 join 的处理，必要时扩，改动仍限 hbt。
