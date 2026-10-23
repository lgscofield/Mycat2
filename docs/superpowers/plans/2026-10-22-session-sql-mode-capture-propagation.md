# 会话 sql_mode 捕获与本地校验门控 实现计划（I-DEBT-54 D2b · v2）

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现本计划。步骤用 checkbox（`- [ ]`）跟踪。

> **计划 v2（2026-10-23，随 spec v2 脊柱反转重衍）**：v1 曾以"A 层向后端 diff-replay 传播"为核心任务（旧 Task 4）。实证推翻：mysqldump D2b 症状是 **Mycat 本地 Calcite `AggChecker` 抛 `is not being grouped`**（作用于 i_s 本地仿表，从不下推后端），且旧传播接线点（`RowSetQuery/AbstractMySqlConnectionImpl/syncState`）为死码。故本版**摘出 A 传播（PARK）**，把 **B 层本地校验门控升为主任务**，e2e 判据改为"本地 Calcite 报错消失"。Task 1-3 已提交且仍有效（喂 B 门控派生）。

**Goal:** 让 Mycat 每会话捕获 `SET sql_mode`、回读返真值，并让**本地 Calcite 分组校验的 `ONLY_FULL_GROUP_BY` 严格性跟随会话**——消除 mysqldump 默认口径 dump tablespaces 时因会话 `SET @@SQL_MODE=''` 被丢弃、Mycat 本地校验器仍按严格模式对 `i_s.FILES`/`i_s.PARTITIONS` 非严格 GROUP BY 抛 `CalciteContextException: ... is not being grouped` 的破面。

**Architecture:** 会话持一份原始 sql_mode 串（捕获+回读，在案），查询入口据该串派生 `containsOnlyFullGroupBy` 布尔，经 `SessionSqlMode` ThreadLocal 穿进 validator 唯一出生点 `MycatCalciteSupport.getValidatorConfig()`（生产路径 `DrdsSqlCompiler:384 → DrdsRunnerHelper.getSqlValidator:224` 均经此），门控 `AggChecker.notGroupExpr`。SPM 缓存保持会话无关（S2）。

**Tech Stack:** Java 21（LibericaJDK-21-Full）/ Maven 多模块 / Calcite fork（calcite-core）/ hbt（Druid+Calcite 编译）。

**Spec:** docs/superpowers/specs/2026-10-22-session-sql-mode-capture-propagation-design.md（v2）

## Global Constraints

- 全程中文注释沿用 `MYCAT CHANGE (D2b ...)` 标记惯例（与既有 fork 改动一致）。
- 缺省严格：未捕获到会话 sql_mode 时 `onlyFullGroupBy=true`，保 Calcite 原生行为与所有既有 golden 基线，零回归。
- 值语义：会话存**原始串**（可为 null=跟随后端默认模板）；校验门控只从中派生 `ONLY_FULL_GROUP_BY` 布尔（token 级精确匹配）。
- **非目标（本版不做）**：向后端传播会话 sql_mode（A 层）摘出本债——不承重 + 接线死码；解析级 sql_mode（ANSI_QUOTES 等）另立债；`SET GLOBAL sql_mode` 维持现状；不复通 `MySQLSynContext`/`syncState`/`initSqls` 等死码。
- 线程模型：编译/校验同线程读 ThreadLocal（沿用 `ParamHolder.CURRENT_THREAD_LOCAL` 既有惯例，见 SessionSqlMode:13）；入口须在编译前 `set`、`finally reset`。
- 构建/测试用 JDK 21（`$env:JAVA_HOME` 指 LibericaJDK-21-Full）；单模块 `mvn -o -pl <module> -am test`，全量回归根 `mvn install`。pwsh 带点参数整体加引号；中文 commit 走 `git commit -F UTF8文件`。
- 合入前单一 CodeReview 门禁；push/合并主干须用户拍板。

## Review Focus

（spec 隐含但非每任务都覆盖、最可能在真实使用中咬人的输入/条件；每行在对应任务补钉测）

1. **空串 `SET sql_mode=''`**：回读须返空串（非 null、非默认模板）；校验门须 `onlyFullGroupBy=false`、放行非严格 GROUP BY。→ Task 2 钉测 + Task 4 门控钉测。
2. **生产生效点真接通**：门控不能只在 validate-only harness 绿，须经生产编译入口（dataContext 在手→set→`DrdsSqlCompiler` 编译→finally reset）实际生效。→ Task 4 Step 入口接线钉测（走带会话上下文的编译入口，非裸 harness）。
3. **S2 缓存不串味**：同一 SQL 文本，宽松会话放行、严格会话拒绝（反之亦然），全局 plan cache 不得把跨会话接受性泄漏。→ Task 4 S2 钉测 + Task 5 交错 e2e。
4. **模式 token 精确匹配**：`STRICT_TRANS_TABLES`/含 `ONLY_FULL_GROUP_BY` 子串场景不得误判；大小写不敏感。→ Task 3 tokenize 单测（在案）。
5. **`SET GLOBAL sql_mode` 不误捕获**：global 域 SET 不得写进会话 `sessionSqlMode`。→ Task 1 钉测（在案）。

---

## 已提交（v1 期间完成，v2 下仍有效——Task 4/5 的门控输入源）

### Task 1: 会话 sql_mode 存储 + 捕获（write 腿） — ✅ 完成 `be815ae3b`
- Produces: `MycatDataContext.getSessionSqlMode()/setSessionSqlMode(String)`；`MySQLVariablesUtil.setVariable` 识别 session 域 `sql_mode` 落会话槽，`SET GLOBAL` 不入会话。测试 `MySQLVariablesUtilSqlModeTest` 3/3。

### Task 2: sql_mode 回读返会话真值（read 腿） — ✅ 完成 `955101c11`
- Consumes: `MycatDataContext.getSessionSqlMode()`。Produces: `@@session.sql_mode`/`SHOW VARIABLES` 显式设置后返会话值，未设回落静态模板。测试 `MycatDataContextSqlModeReadTest` 4/4。

### Task 3: `containsOnlyFullGroupBy` token 级判定（纯函数） — ✅ 完成 `4728c6b23`
- Produces: `SqlModeTokens.containsOnlyFullGroupBy(String raw):boolean`（逗号/空白切分+大写 trim 精确匹配；null→true 缺省严格、空串→false）。测试 `SqlModeTokensTest` 5/5。

---

## 待做（v2 主轨）

### Task 4: 会话态驱动本地校验门控（B 层核心，含第一刀随此提交 + 生产入口接线 + S2 钉测）

**Files:**
- Commit 既有工作树（未 commit）：`calcite-core/src/main/java/org/apache/calcite/sql/validate/SqlValidator.java`、`calcite-core/.../validate/AggChecker.java`、`hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java`、`hbt/src/main/java/io/mycat/calcite/SessionSqlMode.java`、`hbt/src/test/java/io/mycat/calcite/OnlyFullGroupBySqlModeTest.java`
- Modify: 查询编译入口——`hbt/src/main/java/io/mycat/DrdsSqlCompiler.java` 的调用方（`server/.../commands/MycatdbCommand.java` 或实际持有 dataContext 且紧邻编译调用的入口），编译前 `set`、`finally reset`
- Test: `hbt/src/test/java/io/mycat/calcite/OnlyFullGroupBySqlModeTest.java`（扩 S2 + 空串会话用例）

**Interfaces:**
- Consumes: `MycatDataContext.getSessionSqlMode()`（Task 1）、`SqlModeTokens.containsOnlyFullGroupBy(String)`（Task 3）、`SessionSqlMode.setOnlyFullGroupBy(boolean)/isOnlyFullGroupBy()/reset()`（第一刀）。
- Produces: 生产编译路径分组校验严格性跟随会话；validator 出生点 `MycatCalciteSupport.getValidatorConfig():200` 读 `SessionSqlMode.isOnlyFullGroupBy()`（已在工作树）；缺省与离线基线不变。

- [ ] **Step 1: 补失败测试**（扩展 `OnlyFullGroupBySqlModeTest`，走 validate-only 出生点，先钉纯机制）
  - `lenientSessionAcceptsNonStrictGroupBy`、`defaultStrictRejectsNonStrictGroupBy`、`lenientSessionStillChecksSelectDistinct` 三条**已在树**（确认存在且此前红→绿）。
  - 新增 `emptySessionModeStringIsLenient`：模拟会话 `sql_mode=""` → `containsOnlyFullGroupBy("")=false` → 门控放行 `select a, sum(b) from tgb group by c`。
  - 新增 S2 串味钉测 `crossSessionRejectionNotCached`：同一 SQL 文本，`setOnlyFullGroupBy(false)` 下 validate 放行、`reset()` 后（缺省严格）validate 抛 `notGroupExpr`——证明接受性由入口门控决定、非编进缓存键。
- [ ] **Step 2: 跑测试确认状态** — `mvn -o -pl hbt -am test "-Dtest=OnlyFullGroupBySqlModeTest"`。Expected: 既有树内机制腿绿，新 `emptySessionModeStringIsLenient`/`crossSessionRejectionNotCached` 若入口/派生未接则红（本 Step 仅测出生点，入口接线在 Step 4 后验）。
- [ ] **Step 3: 提交第一刀既有文件（机制层，缺省严格零回归）** — `git add` 五既有文件 + 扩展测试 → `git commit -F`（消息："feat(sql-mode): D2b-B 本地分组校验跟随会话(第一刀+空串/S2钉测)"）。跑 `mvn -o -pl hbt -am test "-Dtest=OnlyFullGroupBySqlModeTest,CalciteValidatorGoldenTest"` 确认 golden 零回归。
  - **范围护栏（务必）**：**只 `git add` 本债五个 D2b 文件**（`SqlValidator.java`、`AggChecker.java`、`MycatCalciteSupport.java`、`SessionSqlMode.java`、`OnlyFullGroupBySqlModeTest.java`）+ 扩展测试；**禁 `git add -A`/`git commit -am`**。工作树另有 **`I-DEBT-61`** 的 `calcite-core/.../SqlStdOperatorTable.java`、`StandardConvertletTable.java`（STDDEV/VARIANCE 总体vs样本 kind，实测标记 `MYCAT CHANGE (I-DEBT-61)`），与本债无关，**不得卷入本提交**。提交前 `git diff --cached --stat` 核对仅含预期文件。
- [ ] **Step 4: 生产入口接线 set/reset** — 在**持有前端 `MycatDataContext` 且紧邻编译调用**的查询入口（优先 `MycatdbCommand.executeQuery`/`execute`，实现者按实际调用链定位 `DrdsSqlCompiler` 的调用点），编译/校验前：
  ```java
  SessionSqlMode.setOnlyFullGroupBy(
      SqlModeTokens.containsOnlyFullGroupBy(dataContext.getSessionSqlMode()));
  try { /* 原编译/校验/执行 */ }
  finally { SessionSqlMode.reset(); }
  ```
  `dataContext.getSessionSqlMode()==null` → `containsOnlyFullGroupBy` 返 true（缺省严格，未 SET 会话零变化）。
- [ ] **Step 5: 坐实生产生效点（关键，防"仅 harness 绿"）** — 加/扩一个走**真实编译入口**（非裸 `DrdsRunnerHelper`）的测试或在 Step 6 e2e 覆盖：宽松会话下经入口编译一条非严格 GROUP BY 放行、严格会话拒绝。若入口难以单测实例化，则以 Task 5 活库 e2e 兜底并在此 Step 记录"生效点经 `DrdsSqlCompiler:384→getValidatorConfig:200` 静态坐实"。
- [ ] **Step 6: 编译全模块** — `mvn -o -pl hbt -am test-compile` 通过。
- [ ] **Step 7: 提交入口接线** — `git commit -F`（消息："feat(sql-mode): D2b-B 会话态穿入生产编译入口门控(set/reset)"）。

---

### Task 5: 活库端到端验收（判据级，Docker 后端，v2 判据=本地 Calcite 报错消失）

**Files:**
- Test: `example/src/test/java/io/mycat/compat/SqlModeSessionDumpSurfaceTest.java`
- 复用既有：`DumpSurfaceMatrixTest`、`DumpRestoreRoundTripMatrixTest` 的 8066 探测/`assumeInfraUp`/字节安全回灌模式

**Interfaces:**
- Consumes: Task 4 全部（回读 + 门控）。
- Produces: mysqldump D2b 症状消失与跨会话不串味的端到端铁证。

- [ ] **Step 1: 起 infra** — Docker 后端（`mysql:8.0`）+ Mycat（8066，JDK21 fat-jar 或 classpath 直启），后端 global sql_mode 含 `ONLY_FULL_GROUP_BY`。见 DEBT-QUEUE/构建记忆的启动与 settle 等待流程。
- [ ] **Step 2: 写 e2e 测试** —
  - 会话内 `SET sql_mode=''` → `SELECT @@session.sql_mode` 返 `''`（Task 2 判据）。
  - 经 Mycat 跑 `i_s.FILES`/`i_s.PARTITIONS` 上 mysqldump tablespaces 形态的非严格 GROUP BY 查询（含 `EXTRA`），宽松会话下**不再抛 `CalciteContextException: ... is not being grouped`**、正常返回（Task 4 门控判据，v2 核心）。
  - 两连接交错：连接 A `SET sql_mode=''`、连接 B 保持默认严格，B 上非严格查询**仍被拒**、A 上放行——证明会话态不跨会话泄漏、S2 缓存不投毒（Task 4 S2 判据）。
  - mysqldump 面：对目标库跑真 `mysqldump` **默认口径（不加 `--no-tablespaces`）**，**stderr 不再出现 tablespace GROUP BY 的 Calcite 报错**、dump 成功、字节安全回灌成功（复用 `DumpRestoreRoundTripMatrixTest` 管道）。
- [ ] **Step 3: 跑 e2e** — `mvn -o -pl example -am test "-Dtest=SqlModeSessionDumpSurfaceTest"`，PASS（infra 不在则 `assumeInfraUp` 跳过，但本地须实跑一次取铁证）。
- [ ] **Step 4: 全量回归** — 根 `mvn install`（JDK21），既有 golden/矩阵零新红。
- [ ] **Step 5: 回写台账 + 提交** — 更新 `docs/superpowers/specs/DEBT-QUEUE.md`：I-DEBT-54/D2b 第 28/54/56 行由"cosmetic 挂账"改"已修（本地校验跟随会话，mysqldump tablespaces 报错消失）"；仪表盘 M3 完成度相应更新。`git commit -F`（消息："test(sql-mode): D2b 活库端到端(mysqldump tablespaces 本地报错消失+回读真值+跨会话不串味)"）。

---

## PARK（v2 明确不在本计划承做，另立）

- **A 层向后端传播会话 sql_mode**：对 mysqldump D2b 不承重（症状在本地校验、i_s 不下推），接线点死码。若将来另立债：前置=先拿到"透明下推路径后端真报 1055"实测证据；实现=活路径 `VertxConnection`/`VertxMycatConnectionPool` + Vitess 式 per-query `SET_VAR(sql_mode='…')` hint（不碰复用连接态），**不**复活 `RowSetQuery/syncState/AbstractMySqlConnectionImpl`。
- **死码清理**：`RowSetQuery`/`AbstractMySqlConnectionImpl`/`MySQLTaskUtil.syncState` 及误挂 hook 的 `MySQLClientSession`——转独立 C 层工程卫生债挂号，不进 D2b 主线。

---

## 最终门禁
- Task 4/5 完成、Task 5 全量回归绿后，派发**单一 CodeReview 子代理**做整分支审查（most capable 模型），发现项一轮修复 + 一次 scoped 复审。
- push/合并主干：仅在用户拍板后执行。
