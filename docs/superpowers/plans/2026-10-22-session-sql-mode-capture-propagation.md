# 会话 sql_mode 捕获与传播 实现计划（I-DEBT-54 D2b）

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现本计划。步骤用 checkbox（`- [ ]`）跟踪。

**Goal:** 让 Mycat 每会话捕获客户端 `SET sql_mode`，回读返真值、下推原样传播到后端连接（multiplexing-safe）、本地/跨片校验按会话严格性放宽——消除 mysqldump 因 `SET @@SQL_MODE=''` 被丢弃而撞后端 `ONLY_FULL_GROUP_BY` 报 1055。

**Architecture:** 分层两阶段。A 层（症状根）：前端会话持一份原始 sql_mode 串，捕获→回读→传播；传播在下推万能漏斗 `RowSetQuery.runTextQuery` 处对后端连接做 diff-replay（ProxySQL 式）。B 层（跨片窄面）：会话态驱动 `SessionSqlMode` ThreadLocal 门控 `AggChecker`（第一刀已在树），SPM 缓存保持会话无关（S2）。

**Tech Stack:** Java 21（LibericaJDK-21-Full）/ Maven 多模块 / Calcite fork（calcite-core）/ hbt（Druid+Calcite 编译）/ proxy+server（NIO 代理与执行器）。

**Spec:** docs/superpowers/specs/2026-10-22-session-sql-mode-capture-propagation-design.md

## Global Constraints

- 全程中文注释沿用 `MYCAT CHANGE (D2b ...)` 标记惯例（与既有 fork 改动一致）。
- 缺省严格：未捕获到会话 sql_mode 时 `onlyFullGroupBy=true`，保 Calcite 原生行为与所有既有 golden 基线，零回归。
- 值语义：会话存**原始串**（可为 null=跟随后端默认）；传播**原样透传**（含所有模式）；校验门控只从中派生 `ONLY_FULL_GROUP_BY` 布尔。
- **非目标**：解析级 sql_mode（ANSI_QUOTES 等）不做，另立债；`SET GLOBAL sql_mode` 维持现状（不进会话捕获、不回放下发）。
- 后端连接是复用/交换的，回收不重置 SQL 态 → 传播必须"每次下推 diff-replay"，不得"建立时设一次"。
- 构建/测试用 JDK 21；单模块跑测试用 `mvn -q -pl <module> -am test`（跨模块依赖先 `-am`），全量回归用根 `mvn -q install`。
- 合入前单一 CodeReview 子代理门禁；push/合并主干须用户拍板。

## Review Focus

（spec 隐含但非每个任务都覆盖、最可能在真实使用中咬人的输入/条件，先列最可能者；每行在对应任务补钉测）

1. **空串 `SET sql_mode=''`**：回读须返空串（非 null、非默认模板），传播须发 `SET sql_mode=''`，校验门须 `onlyFullGroupBy=false`。→ Task 2 / Task 4 / Task 5 钉测。
2. **后端连接跨会话复用**：会话 A 置宽松、会话 B 默认严格，交错下推同池连接，B 查询前须把连接 sql_mode 拉回严格，A 的态不得漂给 B。→ Task 4 helper 单测 + Task 6 活库串味 e2e。
3. **内部查询路径不误 replay**：心跳/元数据/XA 等未经前端会话绑定的下推（desired=null）不得被塞 `SET sql_mode`。→ Task 4 helper 单测（null 跳过）。
4. **模式 token 精确匹配**：`STRICT_TRANS_TABLES`/含 `ONLY_FULL_GROUP_BY` 子串场景不得误判；大小写不敏感。→ Task 3 tokenize 单测。
5. **`SET GLOBAL sql_mode` 不误捕获**：global 域 SET 不得写进会话 `sessionSqlMode`。→ Task 1 钉测。

---

### Task 1: 会话 sql_mode 存储 + 捕获（write 腿）

**Files:**
- Modify: `common/src/main/java/io/mycat/MycatDataContext.java`（加接口方法）
- Modify: `proxy/src/main/java/io/mycat/runtime/MycatDataContextImpl.java`（字段+get/set）
- Modify: `common/src/main/java/io/mycat/MySQLVariablesUtil.java`（setVariable 加 sql_mode 分支）
- Test: `common/src/test/java/io/mycat/MySQLVariablesUtilSqlModeTest.java`

**Interfaces:**
- Produces: `MycatDataContext.getSessionSqlMode() : String`（null=未设）；`MycatDataContext.setSessionSqlMode(String)`；`MySQLVariablesUtil.setVariable` 识别 `sql_mode`（session 域）落到 `setSessionSqlMode`，`@@global.sql_mode`/`SET GLOBAL` 不改会话态。

- [ ] **Step 1: 写失败测试**（`MySQLVariablesUtilSqlModeTest`）
  - `setVariable(ctx,"sql_mode","''")` 后 `ctx.getSessionSqlMode()` 返 `""`（空串，去外层引号后）。
  - `setVariable(ctx,"@@session.sql_mode","ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES")` 存原样串。
  - `setVariable(ctx,"@@global.sql_mode","x")` → `getSessionSqlMode()` 仍为 null（global 不入会话）。
  （用轻量 fake MycatDataContext 或 Mockito 验证 setSessionSqlMode 被调。）
- [ ] **Step 2: 跑测试确认失败** — `mvn -q -pl common -am test -Dtest=MySQLVariablesUtilSqlModeTest`，FAIL（方法未定义）。
- [ ] **Step 3: 实现**
  - `MycatDataContext`：加 `String getSessionSqlMode();` 与 `void setSessionSqlMode(String sqlMode);`。
  - `MycatDataContextImpl`（proxy:字段区 L40-60 附近）：加 `private String sessionSqlMode;` + getter/setter。
  - `MySQLVariablesUtil.setVariable`（L9-37）加分支：target 去 `@@`、`session.`/`global.` 前缀后 `equalsIgnoreCase("sql_mode")`；仅 session/无前缀域（global 域直接 return 不改会话态）调 `dataContext.setSessionSqlMode(SQLUtils.normalize(value))`（normalize 去外层引号，空串→`""`）。沿用既有 `SQLUtils.normalize(Objects.toString(text))`。
- [ ] **Step 4: 跑测试确认通过** — 同 Step 2，PASS。
- [ ] **Step 5: 提交** — `git add` 四文件 + `git commit -m "feat(sql_mode): D2b-A 会话 sql_mode 存储与捕获"`（消息写入 UTF-8 文件用 `-F` 防 pwsh 乱码）。

---

### Task 2: sql_mode 回读返会话真值（read 腿）

**Files:**
- Modify: `proxy/src/main/java/io/mycat/runtime/MycatDataContextImpl.java:163-189`（getVariable）
- Test: `proxy/src/test/java/io/mycat/runtime/MycatDataContextSqlModeReadTest.java`

**Interfaces:**
- Consumes: `MycatDataContext.getSessionSqlMode()`（Task 1）。
- Produces: `SELECT @@session.sql_mode` / `SHOW VARIABLES LIKE 'sql_mode'` 在会话显式设置后返该值。

- [ ] **Step 1: 写失败测试** — 设 `ctx.setSessionSqlMode("")` 后 `ctx.getVariable(false,"@@session.sql_mode")` 返 `""`；`setSessionSqlMode(null)`（未设）时返既有静态模板值（非空、行为不变）。
- [ ] **Step 2: 跑测试确认失败** — `mvn -q -pl proxy -am test -Dtest=MycatDataContextSqlModeReadTest`，FAIL。
- [ ] **Step 3: 实现** — `getVariable(boolean global,String target)`（L163）在落到 `variableService.getSessionVariable` 之前插：`if (!global && target!=null && 去前缀后.equalsIgnoreCase("sql_mode") && getSessionSqlMode()!=null) return getSessionSqlMode();`。未设（null）保持原路径。
- [ ] **Step 4: 跑测试确认通过** — 同 Step 2，PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(sql_mode): D2b-A @@session.sql_mode 回读返会话真值"`（`-F` 文件）。

---

### Task 3: `containsOnlyFullGroupBy` token 级判定（纯函数）

**Files:**
- Create: `hbt/src/main/java/io/mycat/calcite/SqlModeTokens.java`
- Test: `hbt/src/test/java/io/mycat/calcite/SqlModeTokensTest.java`

**Interfaces:**
- Produces: `SqlModeTokens.containsOnlyFullGroupBy(String sqlModeRaw) : boolean`（逗号/空白分割 + 大写 trim 精确匹配 token `ONLY_FULL_GROUP_BY`；null→true=缺省严格；空串→false）。

- [ ] **Step 1: 写失败测试** — 断言：
  - `containsOnlyFullGroupBy("ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES")` == true
  - `containsOnlyFullGroupBy("STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION")` == false（不得被子串/相似名误判）
  - `containsOnlyFullGroupBy("")` == false；`containsOnlyFullGroupBy(null)` == true（缺省严格）
  - `containsOnlyFullGroupBy("only_full_group_by")` == true（大小写不敏感）
  - `containsOnlyFullGroupBy("  ONLY_FULL_GROUP_BY , STRICT_TRANS_TABLES ")` == true（空白/逗号混分隔）
- [ ] **Step 2: 跑测试确认失败** — `mvn -q -pl hbt -am test -Dtest=SqlModeTokensTest`，FAIL。
- [ ] **Step 3: 实现** — 用 `[,\s]+` 切分 raw，逐 token `trim().toUpperCase()` 与 `"ONLY_FULL_GROUP_BY"` `equals`；null→true、无 token 命中→false。
- [ ] **Step 4: 跑测试确认通过** — 同 Step 2，PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(sql_mode): D2b-B ONLY_FULL_GROUP_BY token 级判定"`（`-F` 文件）。

---

### Task 4: 后端连接 sql_mode diff-replay 传播（A 层核心）

**Files:**
- Modify: `proxy/src/main/java/io/mycat/proxy/session/MySQLClientSession.java`（加 `appliedSqlMode` 字段 + get/set）
- Modify: `hbt/src/main/java/io/mycat/AsyncMycatDataContextImpl.java:208-230`（getConnection 成功后把前端期望值打给后端连接）
- Modify: `server/src/main/java/io/mycat/vertxmycat/RowSetQuery.java:108-167`（runTextQuery 前置 diff-replay）
- Create: `server/src/main/java/io/mycat/vertxmycat/SqlModeSyncGuard.java`（diff-replay helper）
- Test: `server/src/test/java/io/mycat/vertxmycat/SqlModeSyncGuardTest.java`

**Interfaces:**
- Consumes: `MycatDataContext.getSessionSqlMode()`（Task 1）。
- Produces:
  - `MySQLClientSession.getAppliedSqlMode()/setAppliedSqlMode(String)`（后端连接当前实际 sql_mode，null=未知）。
  - `MySQLClientSession.getDesiredSqlMode()/setDesiredSqlMode(String)`（本次绑定前端会话的期望值，null=内部查询/未绑）。
  - `SqlModeSyncGuard.needsSync(MySQLClientSession backend) : boolean`（desired!=null 且 desired 文本 != applied）。
  - `SqlModeSyncGuard.buildSetSql(String desired) : String`（`SET sql_mode='...'`，单引号按 MySQL `''` 双写转义）。

- [ ] **Step 1: 写 helper 失败测试**（`SqlModeSyncGuardTest`，不触网络）
  - `needsSync`：desired=null → false；desired=`""` 且 applied=null → true；desired=`""` 且 applied=`""` → false；desired=`"X"`、applied=`"Y"` → true。
  - `buildSetSql("")` == `"SET sql_mode=''"`；`buildSetSql("a'b")` == `"SET sql_mode='a''b'"`（转义）。
- [ ] **Step 2: 跑测试确认失败** — `mvn -q -pl server -am test -Dtest=SqlModeSyncGuardTest`，FAIL。
- [ ] **Step 3: 实现 helper + 连接字段**
  - `MySQLClientSession`：加 `appliedSqlMode`/`desiredSqlMode` 两字段 + getter/setter（不进 `hashCode/equals` 的复用键，纯运行时态）。
  - `SqlModeSyncGuard`：`needsSync`/`buildSetSql` 按 Step 1 语义。
- [ ] **Step 4: 跑 helper 测试确认通过** — PASS。
- [ ] **Step 5: 接线 getConnection 打期望值**（`AsyncMycatDataContextImpl.getConnection` L208-230）— 在返回的 `NewMycatConnection` 完成链里，取 `context`（前端 `MycatDataContext`）的 `getSessionSqlMode()`，写到其后端 `MySQLClientSession.setDesiredSqlMode(...)`。（若该层拿不到裸 `MySQLClientSession`，则在 `AbstractMySqlConnectionImpl` 暴露其 `mySQLClientSession` 引用后 set；实现者按此意图接，e2e 兜底验证。）
- [ ] **Step 6: 接线 runTextQuery diff-replay**（`RowSetQuery.runTextQuery` L108-167）— 在发业务 SQL 前：`if (SqlModeSyncGuard.needsSync(session))` 则先用 `ResultSetHandler.DEFAULT.request(session, COM_QUERY, encode(buildSetSql(desired)), cb)` 发 SET，`cb.onFinished` 里 `session.setAppliedSqlMode(desired)` 后再走原业务 `resultSetHandler.request(...)`；`needsSync` 为 false 时行为与今日完全一致（零额外往返）。SET 失败按现有 `onErrorPacket` 路径抛出、不静默。业务与 SET 编码同用既有 `MySQLSqlTextCodec.encode`。
- [ ] **Step 7: 编译全模块** — `mvn -q -pl server -am test-compile` 通过（无接错签名）。
- [ ] **Step 8: 提交** — `git commit -m "feat(sql_mode): D2b-A 后端连接 sql_mode diff-replay 传播(下推串味安全)"`（`-F` 文件）。

---

### Task 5: 会话态驱动跨片校验门控（B 层，含第一刀随此提交）

**Files:**
- Modify: 查询入口——`server/src/main/java/io/mycat/commands/MycatdbCommand.java`（executeQuery/execute 处，编译前按会话设 `SessionSqlMode`，finally reset）
- Commit 既有工作树（未 commit）：`calcite-core/.../SqlValidator.java`、`calcite-core/.../AggChecker.java`、`hbt/.../MycatCalciteSupport.java`、`hbt/.../SessionSqlMode.java`、`hbt/src/test/.../OnlyFullGroupBySqlModeTest.java`
- Test: `hbt/src/test/java/io/mycat/calcite/OnlyFullGroupBySqlModeTest.java`（已有，补 S2 串味钉测）

**Interfaces:**
- Consumes: `MycatDataContext.getSessionSqlMode()`、`SqlModeTokens.containsOnlyFullGroupBy`（Task 3）、`SessionSqlMode.setOnlyFullGroupBy/reset`（第一刀）。
- Produces: 跨片/本地路径分组校验严格性跟随会话；缺省与离线基线不变。

- [ ] **Step 1: 写失败测试**（扩展 `OnlyFullGroupBySqlModeTest`）
  - `SessionSqlMode.setOnlyFullGroupBy(false)` 下，`select a, sum(b) from t group by c` 校验通过（不抛 notGroupExpr）。
  - 缺省/`setOnlyFullGroupBy(true)` 下同查询抛。
  - **S2 串味钉测**：同一 SQL 文本，宽松会话（false）编译放行、严格会话（true）校验拒绝——证明缓存未把跨会话接受性泄漏（不新增缓存键，门控在入口）。
- [ ] **Step 2: 跑测试确认失败/部分** — `mvn -q -pl hbt -am test -Dtest=OnlyFullGroupBySqlModeTest`；第一刀腿应已绿（工作树在），新串味用例若缺入口接线则红。
- [ ] **Step 3: 实现入口接线** — 代理查询入口（`MycatdbCommand.executeQuery`/`execute`，dataContext 在手）在编译/校验前 `SessionSqlMode.setOnlyFullGroupBy(SqlModeTokens.containsOnlyFullGroupBy(dataContext.getSessionSqlMode()))`；查询结束 `finally { SessionSqlMode.reset(); }`（防虚拟/池化线程复用串味，与 SessionSqlMode 注释一致）。
- [ ] **Step 4: 跑测试确认通过** — PASS。
- [ ] **Step 5: 提交（含第一刀既有文件）** — `git add` 五个既有改动 + 入口文件 + 测试 → `git commit -m "feat(sql_mode): D2b-B 跨片分组校验跟随会话 + S2 缓存不编键(含第一刀)"`（`-F` 文件）。

---

### Task 6: 活库端到端验收（判据级，Docker 后端）

**Files:**
- Test: `example/src/test/java/io/mycat/compat/SqlModeSessionDumpSurfaceTest.java`
- 复用既有：`DumpSurfaceMatrixTest`、`DumpRestoreRoundTripMatrixTest` 的 8066 探测/`assumeInfraUp` 模式

**Interfaces:**
- Consumes: Task 1-5 全部。
- Produces: mysqldump 症状消失与不串味的端到端铁证。

- [ ] **Step 1: 起 infra** — Docker 后端（`mysql:8.0`，global sql_mode 含 `ONLY_FULL_GROUP_BY`）+ Mycat（8066，JDK21 fat-jar 或 classpath 直启），后端默认严格。见 DEBT-QUEUE/构建记忆的启动与 settle 等待流程。
- [ ] **Step 2: 写 e2e 测试** —
  - 会话内 `SET sql_mode=''` → `SELECT @@session.sql_mode` 返 `''`（Task 2 判据）。
  - 依赖非严格 GROUP BY 的查询（`select a,sum(b) from t group by c`）经 Mycat 放行返回，不再 1055（Task 4 传播判据）。
  - 两连接交错：连接 A `SET sql_mode=''`、连接 B 保持默认严格，B 上非严格查询须仍被拒/或按 B 的严格放行合法查询——证明复用不串味（Task 4 串味判据）。
  - mysqldump 面：对目标库跑真 `mysqldump`，**stderr 不再出现 1055**、dump 成功、回灌成功（复用 `DumpRestoreRoundTripMatrixTest` 字节安全管道，见记忆）。
- [ ] **Step 3: 跑 e2e** — `mvn -q -pl example -am test -Dtest=SqlModeSessionDumpSurfaceTest`，PASS（infra 不在则 `assumeInfraUp` 跳过，但本地须实跑一次取铁证）。
- [ ] **Step 4: 全量回归** — 根 `mvn -q install`（JDK21），既有 golden/矩阵零新红。
- [ ] **Step 5: 回写台账 + 提交** — 更新 `docs/superpowers/specs/DEBT-QUEUE.md` I-DEBT-54 状态与仪表盘；`git commit -m "test(sql_mode): D2b 活库端到端(mysqldump 1055 消失+回读真值+复用不串味)"`（`-F` 文件）。

---

## 最终门禁
- 全部任务完成、Task 6 全量回归绿后，派发**单一 CodeReview 子代理**做整分支审查（most capable 模型），发现项一轮修复 + 一次 scoped 复审。
- push/合并主干：仅在用户拍板后执行。
