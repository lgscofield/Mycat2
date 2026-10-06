# I-DEBT-10 planner 自触发不收敛修复 实施计划（纠偏版 v2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Mycat 服务端 Calcite 优化器在「GSI + LEFT JOIN + 相关子查询（分布不可合并）」下的规则自触发不收敛（I-DEBT-10），使触发查询在有限步内产出计划并返回，且不改变任何正常（可收敛）join 的最终计划。

**Architecture:** 实测（干净 proxy + 实时 planner 日志，call# 达 13,816,313 且飙升）坐实**主失控者是 `MycatExtraSortRule`**（靠 `join.copy` 自我供料 + `pushSort` 幂等守卫失效），不是初稿所指的 `JoinViewRule`。故主修 = 给 `MycatExtraSortRule` 加收敛守卫；`JoinViewRule` 的乙（已写）/甲降为**次级加固**。守卫变体以红测 + `call#` 是否有界为**唯一裁断**，落不定按具名 fallback 阶梯迭代（不凭读码断言一枪命中）。

**Tech Stack:** JDK 21（BellSoft Liberica 21-Full）、Calcite fork（VolcanoPlanner / RelRule）、JUnit 4（`org.junit.Test`）、Maven（offline Nexus）、Docker 后端 m1-mysql-a:3306 / b:3307（root/123456）。

**Spec:** `docs/superpowers/specs/2026-10-06-i10-joinviewrule-nontermination-design.md`（§根因已按 2026-10-06 实测纠偏；诊断 `.superpowers/sdd/2026-10-06-i7b-diagnosis/i10_rootcause.md`；本计划恢复记录 `.superpowers/sdd/2026-10-06-i10-joinviewrule-nontermination/progress.md`）

## Global Constraints
- 改动**只在 hbt 模块**；不动 `calcite-core` fork；不采用 planner matchLimit（丙，治标）。
- 一切结论以**当前实测**为凭（运行时日志 / 定向跑），不凭本计划推断；仅 Evidenced-Done。
- 不 merge / 不 push / 不发布；不做不可逆操作。
- **主修/甲/乙 的最终形态以 join 全量回归为唯一裁判**：任何守卫若引发正常 join 回归，收窄其触发面至不收敛子集；收窄记录入账本 Ruling。
- 环境（Windows 原生起 proxy + mvn，绕开 WSL 脆弱网络）：`$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"`；mvn 传 `"-Dmaven.repo.local=D:\Nexus\repository"`（**每个 `-D`/含 `.` 的参数整体加引号**，pwsh 会拆坏）；起 proxy `java "-Duser.timezone=Asia/Shanghai" "-Dfile.encoding=UTF-8" -jar server/target/server-1.22-release-jar-with-dependencies.jar`（后台）。
- **每次改 hbt 源码后必须** `mvn -B -o -pl hbt -am install` + `mvn -B -o -pl server -am package`（重新 shade fat-jar）并**重启 proxy** 才生效；判结果直接读 `example/target/surefire-reports/TEST-*.xml`（勿依赖 pwsh 重定向日志，易 UTF-16 污染）。
- **跑测前确保干净环境**：无残留 proxy/maven java 进程（`Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 排除 IDE 的 redhat.java）；红测挂死会拖住 proxy 侧失控 planner，**测毕立即 kill proxy**，防 call# 无界烧垮机器（历史已烧垮过 WSL VM）。
- 从属 6 处连接泄漏不在本计划修（无可观测绿信号），基线重生阶段另行收编。

## Review Focus（spec 隐含、须由测试钉住的输入类）
- 非 GSI 的相关子查询（`EXISTS` / `IN` 子查询下推）不得因主修/甲/乙回归 → Task 4 断言相关用例结果不变。
- 其它 join 类型（INNER / RIGHT / SEMI / ANTI / FULL）× 各分布组合不得因主修（MycatExtraSortRule 语义变更）回归 → Task 4 join 全量回归覆盖，重点含 sort-merge join 的计划选型不变。
- MycatExtraSortRule 的 `RelNode(anyInputs)` 变体恒匹配是自我供料放大器 → 主修须证明它不再产出「输入未变却仍 transformTo」的等价新节点。

---

### Task 1: 建立收敛红测（已完成）

**Files:**
- Modify: `example/src/test/java/io/mycat/gsi/CreateGsiTest.java`（新增 `testGsiJoinConverges`，守护线程 + `Future.get(60s)`，TimeoutException→Assert.fail）

- [x] **Step 1–3:** 落点=example 定向黑盒（DrdsSqlCompiler 脱机编译需全量元数据，A 方案不可行）；RED 已实证（查询挂死、planner 不收敛）。
- [x] **Step 4: Commit** — 已入库 `7eb27410e`（compaction 前摘要所称 `0b1f606f2` 系失真、从未存在，已凭 git 校正补提）。

**Produces:** 可复跑的「触发查询 60s 内返回」收敛门，供 Task 2/3 作红→绿门。当前状态：含乙 jar 下仍 RED（见下诊断）。

---

### Task 2: 主修——MycatExtraSortRule 收敛守卫（假设驱动，红测裁断）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/rules/MycatExtraSortRule.java:52-100`（`onMatch` + `pushSort`）
- 保留（次级加固，已在工作区）：`hbt/src/main/java/io/mycat/calcite/rewriter/SQLRBORewriter.java:335-343`（乙）
- Test: 复用 Task 1 `testGsiJoinConverges`

**Interfaces:**
- Consumes: `MycatView.getRelNode()`、`MycatView.changeTo(RelNode)`、`RelNode.getTraitSet().getCollation()`、`join.copy(...)`
- Produces: MycatExtraSortRule 对「输入无需再排序 / 视图已满足目标 collation」不再产出等价新节点，使自触发环有界

- [ ] **Step 1: 守卫主变体 (a)+(b)**

(a) `onMatch`：先算 `newLeft = pushSort(left, collations.get(0))`、`newRight = pushSort(right, collations.get(1))`；若 `newLeft == left && newRight == right`（引用同一），**`return` 不调 `transformTo`**；否则才 `transformTo(join.copy(traitSet, [newLeft, newRight]))`。
(b) `pushSort` MycatView 分支：包 sort 前判「视图有效 collation 已满足目标」——若 `relNode.getTraitSet().getCollation()` 已含/等于 target，**或** `mycatView.getRelNode()` 已是同 collation 的 `Sort`——则**返回原视图引用不变**（现有仅比 outer traitSet，补 inner-is-Sort 判据）。
（`Distribution`/`RelCollations`/`Sort` 已可见，缺则补 import。保持既有 catch-log 包裹。）

- [ ] **Step 2: 重建 + 干净起 proxy + 定向复跑**

```
mvn -B -o -pl hbt -am install
mvn -B -o -pl server -am package
# kill 任何残留 proxy java，再起新 proxy（含主修），等 8066 LISTEN
mvn -B -o clean test -pl example "-Dtest=CreateGsiTest#testGsiJoinConverges"
```
Expected（判据）：
- 转绿：`surefire` XML `failures="0"`、testcase 秒级返回；
- 或仍红：testcase `AssertionError`（60s 未返回）→ **立即 kill proxy**，抓该查询的 planner 日志确认 `call#` 是否仍有界/哪条规则仍无限 fire。

- [ ] **Step 3: 若仍红 → fallback 阶梯（逐个上、每次重跑判据，记 Ruling）**

- F1（相关子查询放大器）：若日志显示 `call#` 仍随 MycatView 重展开无界增长，则给 MycatExtraSortRule 的 `Join` operand 收窄——`b0.operand(Join.class).predicate(j -> /* 仅未排过目标 collation 的 join */)`，阻断对「已带目标排序的 join」再排一次；重跑判据。
- F2（operand 约定排除物理 join，粗粒度，慎用）：`b0.operand(Join.class).predicate(j -> j.getConvention() != MycatConvention.INSTANCE)`。**风险高**（可能整体禁用物理 join 的 merge-sort）→ 必须紧接 Task 4 回归证实零差异，否则回撤。
- 若 F1/F2 皆不能收敛且回归有损 → 停止，升级回 systematic-debugging/brainstorming（可能触及 fork planner 终止保证，属更重路径），不强行加丙掩盖。

- [ ] **Step 4: Commit（主修 + 乙一并）**

```
git add hbt/src/main/java/io/mycat/calcite/rules/MycatExtraSortRule.java hbt/src/main/java/io/mycat/calcite/rewriter/SQLRBORewriter.java
git commit -m "fix(hbt): I-DEBT-10 主修——MycatExtraSortRule 收敛守卫(断 join.copy 自喂) + 乙 tryMergeJoin 次级加固"
```

---

### Task 3: 甲——JoinViewRule 匹配面排除 Mycat 物理 join（次级加固，可选）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/LocalRules.java:288-295`（`JoinViewRuleConfig.withOperandFor`）
- Test: 复用 Task 1；Task 4 回归裁断

**Interfaces:**
- Consumes: `join.getConvention()`、`MycatConvention.INSTANCE`
- Produces: JoinViewRule 不再吃自身产出的 Mycat 物理 join

- [ ] **Step 1: 决策是否叠加**（凭 Task 2 后红测/日志）：若 Task 2 主修后 JoinViewRule 已非失控者且回归无碍，甲作为纵深加固保留；若甲引发正常 join 回归则**不落地甲**（记 Ruling）。

- [ ] **Step 2: 若落地** — `b0.operand(Join.class).predicate(j -> j.getConvention() != MycatConvention.INSTANCE)`（其后两 MycatView 谓词不变）；重建 + 定向复跑保持绿。

- [ ] **Step 3: Commit（若落地）**
```
git add hbt/src/main/java/io/mycat/calcite/localrel/LocalRules.java
git commit -m "fix(hbt): I-DEBT-10 甲——JoinViewRule 匹配面排除 Mycat 物理 join（次级加固）"
```

---

### Task 4: join 全量回归 + 收敛确认（Review Focus 钉桩）

**Files:**
- Test: 复用既有 `example` join/gsi 用例；无新生产代码（除非回归暴露缺陷）

**Interfaces:**
- Consumes: Task 2/3 改后 planner 行为
- Produces: 「无新增红 + 正常 join 计划/结果零差异」的 Evidenced-Done 回归证据

- [ ] **Step 1: 全量定向回归**（干净起 proxy）
```
mvn -B -o test -pl example -Dtest=CreateGsiTest,BkaJoinExecuteTest,AssembleTest
```
Expected: 相对修复前基线**无新增红**；`testGsiJoinConverges` 绿；含相关子查询（EXISTS/IN）用例结果不变；含 sort-merge join 的用例计划选型不变。

- [ ] **Step 2: 计划零差异抽查** — 对若干正常 join（可合并分布、非 GSI）比对修复前后最终计划（EXPLAIN/断言计划串），确认主修/甲/乙**只影响不收敛路径**、正常 join 计划无变化。

- [ ] **Step 3: 留痕登记 + Commit** — 在 `docs/superpowers/specs/2026-10-02-bounded-debt-records.md` 追加 §10（I-DEBT-10 完成记录：主修 MycatExtraSortRule 守卫 + 次级 甲/乙 最终形态、收敛实测 call# 有界、join 回归结果）；更新看板 `2026-09-30-mysql-mariadb-compat-PROGRESS.md`（I-DEBT-10→✅）。
```
git add docs/superpowers/specs/2026-10-02-bounded-debt-records.md docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -m "docs(debt): I-DEBT-10 完成入簿——MycatExtraSortRule 收敛守卫（主修）+ 甲/乙 次级 + join 回归证据"
```

---

## 完成判据
- Task 1 红测修复前 RED（超时挂死）、主修落地后 GREEN（60s 返回）。
- 主修（+ 视情况甲/乙）后 join 全量回归相对修复前无新增红、正常 join 计划零差异。
- `mycat.log` 中该查询 `call#`/`rel#` 有界（对齐实测：修复前 1381 万级）。
- 基线重生与 6 处连接泄漏收编为后续独立回合，不在本计划内宣告完成。

## v1→v2 变更（实测纠偏，2026-10-06）
- 主失控者由 `JoinViewRule` 改为 `MycatExtraSortRule`（实时 planner 日志 call#=13,816,313 无限 fire 者为后者）；甲/乙降为次级加固。
- 新增 Task 2 主修 = MycatExtraSortRule 收敛守卫（onMatch no-op + pushSort 幂等），配 fallback 阶梯；判据强调「凭红测/call# 有界裁断，不凭读码断言」，纠正上一版「乙应能断环」的读码误判。
