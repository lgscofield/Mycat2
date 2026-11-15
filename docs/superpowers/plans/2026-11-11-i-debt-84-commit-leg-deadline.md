# I-DEBT-84 Phase 2 阶段2 · commit 腿接 deadline 实现 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `ReceiverImpl.commit()` 整腿套语句级 wall-clock deadline，使后端冻结/TCP 黑洞时客户端在有界时间内拿 3013，且超时 force-close 不损坏 in-doubt（已决提交不被回滚、未决交 reaper 收敛）。

**Architecture:** 复用 Phase 1 范式（`StatementDeadline.withDeadline` + `forceCloseInFlight`）包 commit 腿；在 `BaseXaSqlConnection` 加 `commitDecided` volatile 标志（R1）使"已决提交"失败后只向前收敛不回滚；`retryCommit` 检查取消标志让位 reaper（R2）；客户端 3013 + "commit outcome unknown" 文案（R3）。**关键修正**：混合路径（本地协调 ds1 + XA 参与者 ds2）的真实原子决定点是 ds1 本地 `commit;`，须把参与者意向持久化与 XA COMMIT 移到该决定点之后（见 §A，偏离已拍板 §8.3，需先拍板）。

**Tech Stack:** Java 21（LibericaJDK-21-Full）、Vert.x Future、Druid JDBC、MySQL 8.0.46 / MariaDB 10.11.19（docker m1-mysql-a:3306 / m1-mysql-b:3307 / m6-mariadb-a:3308，root/123456）、JUnit5 + Mockito（va/server 有，example 无）。

**Spec:** `docs/superpowers/specs/2026-11-05-i-debt-84-statement-deadline-design.md` §8（§8.3 R1-R6 已拍板；§8.4 测试矩阵）。本 plan 的 §A 是对 §8.3 的一处必要修正，须用户拍板后才动 commit 路径。

## Global Constraints

- 错误码沿用 `MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT`（3013），与 statement/rollback/savepoint 腿一致。
- deadline 口径不变：datasource `statementTimeout` 最小值 + 全局默认 30s 兜底；`<=0` 禁用（零开销透传）。
- 范围：单 DS 本地 + 多 DS 2PC（纯 XA + 混合）一起接；空事务 / `begin()` 不接。
- 非超时的既有失败语义不变（beforeCommit 失败→cancelCommitLog→rollback；writeCommitLog 自身失败→拒提交+rollback）。
- 改生产码必重打 fat jar 再活体验；全量护栏 va/server/example 绿；账本+DEBT-QUEUE+PROGRESS 对齐。
- **commit 不 push；每次 commit 须用户显式拍板，一次批准不延续。**
- 长护栏（25-30min）用 Monitor persistent + 日志，不用 Bash 后台（10min 上限静默杀）。
- `JAVA_HOME="D:/Program Files/BellSoft/LibericaJDK-21-Full"`；`mvn -o`；`-Dtest` 须配 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 不 stage `.qoder/skills/*`、`.qoder/commit-msg.txt`（预存非本轮产物）。

## Review Focus

spec 隐含、但各任务测试未必覆盖、最可能咬人的五类输入/失败模式（最可能在前）：

1. **混合路径决定点错位**（§A）：ds1 本地 commit 前被 force-close，参与者意向已提前 durable → reaper 误提交参与者、本地已回滚 = 跨库不一致。Task 1 的真后端一致性测试钉死。
2. **已决提交被回滚**：意向 durable 后超时，失败 transform 走 rollback 对 prepared 分支发 XA ROLLBACK + 删意向 = durability 破口。Task 1 的 decided→不 rollback 测试钉死。
3. **retryCommit 与 reaper 双主 churn**：客户端已拿 3013 走人，后台仍无限 setTimer 抢新连接重试。Task 2 的取消让位测试钉死。
4. **rollback→kill→close→rollback 递归**：XA ROLLBACK 失败时 `rollback()` 失败分支 `kill()`→`close(consumer)`→`if(inTranscation) rollback()` 可能自递归（`BaseXaSqlConnection:168-185/413-418`）。Task 1 须验证 decided 路径不再触发该递归，并加一条未决 rollback 失败的栈深/终止断言。
5. **force-close 是否真解困**：`abandonConnection()`→Druid `DruidPooledConnection.close()` 可能是 recycle 而非物理 close，冻结后端下 worker 是否真抛错解困需活体证明（Task 5 docker pause + IOExecutor 计数回落 + 后端无孤儿会话）。

---

## §A 设计修正（偏离已拍板 §8.3，须先拍板）

### A.1 实证破口（探针已跑，用完即删）

临时探针 `I84MixedPathHoleProbe`（真后端 ds1:3306/ds2:3307，纯 JDBC + 既有 `XaReaper.sweep`，不碰生产码）两场景均断言通过：

- **PROBE-A**（当前顺序）：ds1 本地 tx 内 INSERT + REPLACE xa_log；ds2 XA PREPARE + **阶段0 提前写参与者意向**；force-close ds1（提交前）→ 本地回滚、ds2 意向仍 durable → `sweep` 命中意向 XA COMMIT ds2。结果 `local(ds1)=false participant(ds2)=true` ⇒ **跨库不一致坐实**。
- **PROBE-B**（修正顺序）：同上但 ds1 提交前**不写**参与者意向；force-close ds1 → 无任何意向 durable → `sweep` 未决 grace=0 立即 XA ROLLBACK ds2。结果 `local=false participant=false` ⇒ **一致，修正成立**。

### A.2 根因（file:line 实读 HEAD）

混合路径 `LocalXaSqlConnection.commit():91-114` 调 `super.commitXa(beforeCommit=ds1 REPLACE xa_log)`，`BaseXaSqlConnection.commitXa():271-351` 内部顺序为：

1. ds2 XA END/PREPARE
2. `log.logCommitBeforeXaCommit(xid):311` → `LocalXaMemoryRepositoryImpl.writeCommitLog():64-84` → **ds2 意向立即 durable**（阶段0）
3. `beforeCommit.apply():317` → ds1 REPLACE xa_log（**仍在 ds1 本地 tx 内，未 durable**）
4. ds2 **XA COMMIT**`:330-332`（参与者已提交）
5. 回到 `LocalXaSqlConnection.commit` 的 `.compose:96` → ds1 **`commit;`**（真实原子决定点：业务数据 + 协调者 xa_log 一起 durable）
6. ds1 delete xa_log`:97`

**真实决定点是第 5 步（ds1 commit;），却排在最后。** 已拍板 §8.3 R1 把 `commitDecided` 置在第 2 步（`logCommitBeforeXaCommit` 成功后）——远早于真实决定。两个不一致窗口：
- **W1**（PROBE-A）：force-close 落在第 2 步后、第 4 步前 → ds2 prepared+意向 durable、ds1 回滚 → reaper 提交 ds2。
- **W2**：第 4 步成功、第 5 步失败/冻结 → ds2 已提交、ds1 回滚 → 直接不一致（无需 reaper）。

纯多 DS XA 路径（`BaseXaSqlConnection.commit():264-266`，无 localSqlConnection）的决定点**就是** `logCommitBeforeXaCommit`（writeCommitLog durable 到各参与者），R1 在该路径正确。**破口仅在混合路径。**

### A.3 修正案（推荐 Option A：决定点归位）

把混合路径的真实决定点（ds1 本地 `commit;`）移到参与者意向持久化与 XA COMMIT **之前**：

1. ds2 XA END/PREPARE
2. ds1 REPLACE xa_log（本地 tx）
3. **ds1 `commit;`** ← 决定点（durable）。**此处置 `commitDecided=true`**。
4. `writeCommitLog` → ds2 意向 durable（决定之后）
5. ds2 XA COMMIT
6. cleanup（ds1 delete xa_log + 参与者意向删）

落地手法（最小改动）：
- **`commitXa` 重排**：把 `beforeCommit.apply()` 移到 `log.logCommitBeforeXaCommit()` **之前**（prepare → beforeCommit → writeCommitLog → XA COMMIT）。纯 XA 路径 beforeCommit 是 no-op，重排后语义不变（writeCommitLog 仍在 XA COMMIT 前）。
- **混合 beforeCommit 扩容**：`LocalXaSqlConnection.commit():93-99` 的 beforeCommit 从"仅 REPLACE xa_log"改为"REPLACE xa_log + `commit;`"，并删去外层 `.compose:96` 的重复 `commit;`（决定点收进 beforeCommit）。beforeCommit 成功（ds1 已 commit）后**立即置 `commitDecided=true`**，关闭"ds1 已 commit 但 writeCommitLog 未跑"窗口。
- **`commitDecided` 置位规则**：混合路径在 beforeCommit（ds1 commit;）成功后置位；纯 XA 路径在 `logCommitBeforeXaCommit` 成功后置位（commitXa 统一在 writeCommitLog 成功后也置位，幂等）。`logCancelCommitBeforeXaCommit`（beforeCommit 失败）重置 false。
- **残留窗口靠 reaper 兜底**：ds1 commit 后、writeCommitLog 前若 force-close，ds2 无意向但 **ds1.xa_log 意向已随 ds1 commit durable**；reaper `sweep():163-176` 对**所有**后端（含 ds1）union `select xid from xa_log` ⇒ 命中 ds1 意向 ⇒ XA COMMIT ds2 ⇒ 一致。（已实读 reaper 确认 union 覆盖 ds1。）

### A.4 与 §8.3 的偏差及代价

- §8.3 R1 原文"在 logCommitBeforeXaCommit 成功返回后置位"对混合路径**不安全**，须改为"在真实决定点（混合=ds1 commit;，纯 XA=writeCommitLog）置位"。这是本轮唯一偏离已拍板设计处。
- 代价：`commitXa` 内部顺序重排 + 混合 beforeCommit 扩容，blast 比"只加标志"大；但这是混合路径 durability 正确的必要条件，PROBE-A/B 已实证。
- 已知小清理缺口（非本轮阻塞）：协调器死后 reaper 只删 prepared 分支目标（ds2）的意向，ds1.xa_log 残留行无人清——xid 唯一故无害，留后续清理债。

**⇒ 拍板门：Option A 已于 2026-11-11 用户拍板认可（Native 内联执行）；spec §8.6 已补记。Task 1 重排可执行。**

---

## Task 1: 决定点归位 + R1 commitDecided 标志（va）

**Files:**
- Modify: `va/src/main/java/cn/mycat/vertx/xa/XaSqlConnection.java`（加 default 标志读写方法）
- Modify: `va/src/main/java/cn/mycat/vertx/xa/SavepointSqlConnection.java`（委托新方法）
- Modify: `va/src/main/java/cn/mycat/vertx/xa/impl/BaseXaSqlConnection.java:271-351`（commitXa 重排 + 标志置位/重置 + 失败 transform 让位）
- Modify: `va/src/main/java/cn/mycat/vertx/xa/impl/LocalXaSqlConnection.java:91-114`（混合 beforeCommit 扩容 + 决定点置位 + 失败 transform decided→不 rollback）
- Test: `va/src/test/java/cn/mycat/vertx/xa/impl/CommitDecidedFlagTest.java`（假连接 + 可控 timer，确定性）
- Test: `example/src/test/java/io/mycat/xa/MixedPathCommitConsistencyTest.java`（真后端 ds1/ds2，复现 PROBE-A/B 为回归锁）

**Interfaces:**
- Consumes: `XaLogSchema.insertCommitIntent/deleteCommitIntent`、`XaReaper.sweep(Map<String,Connection>)`、`XaTestSuite.initSchema/getMySQLConnection/DB1/DB2`。
- Produces:
  - `XaSqlConnection` 新增 `default void markCommitDecided() {}` / `default boolean isCommitDecided() { return false; }` / `default void markCommitTimedOut() {}` / `default boolean isCommitOutcomeUnknown() { return false; }`（后两者 Task 2/3 用，先一并定义避免改接口两次）。
  - `BaseXaSqlConnection` 加 `protected volatile boolean commitDecided` + `protected volatile boolean commitTimedOut`，覆写四方法。
  - `SavepointSqlConnection` 委托四方法到 `connection`。

- [ ] **Step 1: 写真后端混合路径一致性回归锁（RED）**

`MixedPathCommitConsistencyTest`（example，真后端，仿 `LocalXaCommitFailureTest` 探针助手）：
- `mixedPath_forceCloseBeforeLocalCommit_staysConsistent`：ds1 autocommit=false INSERT(id1)+REPLACE xa_log；ds2 XA START/INSERT(id2)/END/PREPARE；**按修正顺序不提前写 ds2 意向**；`ds1.close()`（模拟 force-close 提交前）；`sweepBoth()`（grace=0）；断言 `rowVisible(ds1,id1)==false && rowVisible(ds2,id2)==false`（一致回滚）。
- `mixedPath_forceCloseAfterLocalCommit_convergesCommit`：ds1 INSERT+REPLACE xa_log+`commit;`（决定点已过）；ds2 PREPARE + 写意向；`ds1.close()`；`sweepBoth()`；断言 `rowVisible(ds1,id1)==true && rowVisible(ds2,id2)==true`（一致提交，不丢已决）。
- 助手 `raw(url)` 用 `DriverManager.getConnection(url, props{user=root,password=123456})`（URL 的 `username=` 不被 Connector/J 识别，须显式 props）；`sweepBoth` 用手写匿名 `MySQLManager`（example 无 Mockito）+ `new XaReaper(()->mgr, 30000, 0, singleThreadScheduler, System::currentTimeMillis)`。
- 当前生产码下第一例应红（W1 不一致）——但本测试直连 JDBC 复现顺序，RED 体现为"按当前生产顺序写意向则不一致"。先以修正顺序写测试钉住目标语义。

- [ ] **Step 2: 跑测试确认 RED**

Run: `mvn -o -B -pl example test -Dtest=MixedPathCommitConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译/断言失败体现破口（或先以探针顺序复现 PROBE-A 红）。

- [ ] **Step 3: 写 va 层 decided 标志确定性测试（RED）**

`CommitDecidedFlagTest`（va，假 `NewMycatConnection` + 注入 timer）：
- `decidedAfterDecision_failureDoesNotRollback`：驱动到决定点后置 `commitDecided`，注入 XA COMMIT 失败 → 断言**未发 XA ROLLBACK**、`isCommitDecided()==true`、会话态归零。
- `notDecided_failureRollsBack`：决定点前失败 → 断言走 rollback（发 XA ROLLBACK）。
- `cancelResetsDecided`：beforeCommit 失败 → `logCancelCommitBeforeXaCommit` → `isCommitDecided()==false`。

- [ ] **Step 4: 跑 va 测试确认 RED**

Run: `mvn -o -B -pl va test -Dtest=CommitDecidedFlagTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（标志/重排未实现）。

- [ ] **Step 5: 实现接口标志 + 委托 + commitXa 重排 + 决定点置位**

按 §A.3：
- `XaSqlConnection` 加四 default 方法；`SavepointSqlConnection` 委托。
- `BaseXaSqlConnection`：加 `commitDecided`/`commitTimedOut` volatile + 覆写；`commitXa` 把 `beforeCommit.apply()` 移到 `logCommitBeforeXaCommit()` 之前；writeCommitLog 成功后 `commitDecided=true`；`future.onFailure`（beforeCommit 失败）`logCancelCommitBeforeXaCommit` + `commitDecided=false`。
- `LocalXaSqlConnection.commit` 混合档：beforeCommit = `REPLACE xa_log` compose `commit;`，成功后 `commitDecided=true`；删外层重复 `commit;`；失败 transform 改 `if (commitDecided) { 清会话态; return failedFuture(cause); } else { return rollback().transform(...); }`。单 DS 档失败 transform 同理（R4：单 DS 无 prepared，decided 标志恒 false，维持 rollback）。
- 验证 Review Focus #4：decided 路径不再进 `rollback()`，杜绝 rollback→kill→close→rollback 递归。

- [ ] **Step 6: 跑 va + example 测试确认 GREEN**

Run: `mvn -o -B -pl va test -Dtest=CommitDecidedFlagTest -Dsurefire.failIfNoSpecifiedTests=false` 及 `mvn -o -B -pl example test -Dtest=MixedPathCommitConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 7: 跑 va/example 全量确认无回归**

Run: `mvn -o -B -pl va,example test`
Expected: 全绿（任何红按名汇报）。

- [ ] **Step 8: Commit（须用户拍板）**

```bash
git add va/src/main/java/cn/mycat/vertx/xa/XaSqlConnection.java va/src/main/java/cn/mycat/vertx/xa/SavepointSqlConnection.java va/src/main/java/cn/mycat/vertx/xa/impl/BaseXaSqlConnection.java va/src/main/java/cn/mycat/vertx/xa/impl/LocalXaSqlConnection.java va/src/test/java/cn/mycat/vertx/xa/impl/CommitDecidedFlagTest.java example/src/test/java/io/mycat/xa/MixedPathCommitConsistencyTest.java
git commit -m "fix(I-DEBT-84): commit腿决定点归位+R1 commitDecided(混合路径本地commit为决定点,参与者意向/XA COMMIT后置)"
```

---

## Task 2: R2 retryCommit 超时让位 reaper（va）

**Files:**
- Modify: `va/src/main/java/cn/mycat/vertx/xa/impl/BaseXaSqlConnection.java:357-379`（retryCommit 入口 + setTimer 前检查取消标志）
- Test: `va/src/test/java/cn/mycat/vertx/xa/impl/RetryCommitYieldTest.java`

**Interfaces:**
- Consumes: Task 1 的 `commitTimedOut` volatile + `markCommitTimedOut()`。
- Produces: `retryCommit()` 在 `commitTimedOut==true` 时立即以失败/取消完成（不再 setTimer 循环），收敛交 reaper。

- [ ] **Step 1: 写让位测试（RED）**

`RetryCommitYieldTest`：注入假 `MySQLManager.setTimer` 计数 + 假连接 XA COMMIT 恒失败；置 `markCommitTimedOut()`；触发 retryCommit → 断言 `setTimer` 调用次数==0（不无限重试）、retryCommit future 在预算内完成（不挂）。负控：未置标志 → setTimer 被调（维持原重试）。

- [ ] **Step 2: 跑确认 RED**

Run: `mvn -o -B -pl va test -Dtest=RetryCommitYieldTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（仍无限 setTimer）。

- [ ] **Step 3: 实现让位**

`retryCommit()` 入口与 `all.onFailure` 的 setTimer 前各加 `if (commitTimedOut) { promise.tryFail(...); return; }`。

- [ ] **Step 4: 跑确认 GREEN + va 全量**

Run: `mvn -o -B -pl va test`
Expected: 全绿。

- [ ] **Step 5: Commit（须用户拍板）**

```bash
git add va/src/main/java/cn/mycat/vertx/xa/impl/BaseXaSqlConnection.java va/src/test/java/cn/mycat/vertx/xa/impl/RetryCommitYieldTest.java
git commit -m "fix(I-DEBT-84): R2 retryCommit超时让位reaper(取消标志止无限setTimer churn)"
```

---

## Task 3: R3 客户端语义 3013 + outcome unknown 文案（va + server）

**Files:**
- Modify: `va/src/main/java/cn/mycat/vertx/xa/impl/BaseXaSqlConnection.java`（decided 超时失败 cause 追加文案）
- Modify: `server/src/main/java/io/mycat/commands/ReceiverImpl.java:169-174`（commit 腿超时文案收口，Task 4 包裹时一并）
- Test: `server/src/test/java/io/mycat/commands/ReceiverCommitLegDeadlineTest.java`（Task 4 共建）

**Interfaces:**
- Consumes: Task 1 `isCommitOutcomeUnknown()`（= commitTimedOut && commitDecided）。
- Produces: decided 超时场景客户端拿 `MycatException(3013, "... commit outcome unknown; recovery in background")`；未 decided/单 DS 超时拿标准 3013。

- [ ] **Step 1: 写文案断言测试（RED）**

在 Task 4 的 `ReceiverCommitLegDeadlineTest` 加 `decidedTimeout_messageCarriesOutcomeUnknown`：mock decided 超时 → 断言 cause message 含 "commit outcome unknown"。

- [ ] **Step 2: 跑确认 RED**

Run: `mvn -o -B -pl server test -Dtest=ReceiverCommitLegDeadlineTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL。

- [ ] **Step 3: 实现文案**

`StatementDeadline.withDeadline` 的 onTimeout 里据 `isCommitOutcomeUnknown()` 决定失败 cause 文案（或在 commit 腿 recover 链追加 suffix）。保持 3013 码不变。

- [ ] **Step 4: 跑确认 GREEN**

Run: `mvn -o -B -pl server test -Dtest=ReceiverCommitLegDeadlineTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit（须用户拍板，与 Task 4 合并）**

---

## Task 4: R5 ReceiverImpl.commit 包裹 deadline（server）

**Files:**
- Modify: `server/src/main/java/io/mycat/commands/ReceiverImpl.java:169-174`（commit 整腿包 commit 版 deadline helper）
- Modify: `server/src/main/java/io/mycat/commands/ReceiverImpl.java:451-460`（新增 `withCommitLegDeadline`，onTimeout=forceCloseInFlight + `markCommitTimedOut()`）
- Test: `server/src/test/java/io/mycat/commands/ReceiverCommitLegDeadlineTest.java`（复用 Phase 1 `Both` 交叉接口 harness）

**Interfaces:**
- Consumes: Phase 1 `withTransactionLegDeadline`/`forceCloseInFlight`/`statementDeadlineMillis`；Task 1 `markCommitTimedOut()`；Task 3 文案。
- Produces: `ReceiverImpl.commit()` 超时 → 3013 + force-close 参与者 + 置 commitTimedOut；快路径取消 timer、writeOk、abandon==0。

- [ ] **Step 1: 写 commit 腿 deadline 测试（RED）**

`ReceiverCommitLegDeadlineTest`（仿 `ReceiverTransactionLegDeadlineTest`）：
- `commitLegTimesOut_reports3013_forceCloses`：`xa.commit()` 返永不完成 blockingLeg → 断言 3013、abandonCounter==1、`markCommitTimedOut` 被调、elapsed<2000ms。
- `fastCommit_noForceClose_writeOk`：`xa.commit()` 立即成功 → abandon==0、writeOk 被调。
- `decidedTimeout_messageCarriesOutcomeUnknown`（Task 3）。

- [ ] **Step 2: 跑确认 RED**

Run: `mvn -o -B -pl server test -Dtest=ReceiverCommitLegDeadlineTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（commit 无 deadline，永不完成 → get(3s) 超时）。

- [ ] **Step 3: 实现包裹**

`commit():172` 改 `return withCommitLegDeadline(transactionSession.commit().eventually(...).flatMap(...));`。新增 `withCommitLegDeadline`：快照参与者 → statementDeadlineMillis → `StatementDeadline.withDeadline(leg, deadline, () -> { forceCloseInFlight(participants, ...); transactionSession.markCommitTimedOut(); return succeededFuture(); })`。

- [ ] **Step 4: 跑确认 GREEN + server 全量**

Run: `mvn -o -B -pl server test`
Expected: 全绿。

- [ ] **Step 5: Commit（须用户拍板，含 Task 3）**

```bash
git add server/src/main/java/io/mycat/commands/ReceiverImpl.java server/src/test/java/io/mycat/commands/ReceiverCommitLegDeadlineTest.java va/src/main/java/cn/mycat/vertx/xa/impl/BaseXaSqlConnection.java
git commit -m "fix(I-DEBT-84): R5 commit腿接deadline+R3 3013/outcome-unknown文案(复用Phase1范式)"
```

---

## Task 5: 活体取证 + 全量护栏 + 重打 jar

**Files:**
- Test: `example/src/test/java/io/mycat/xa/CommitLegLiveFreezeTest.java`（docker pause 配方，承 R1c）
- 产物: 重打 fat jar

**Interfaces:**
- Consumes: Task 1-4 生产码；docker m1-mysql-a/b；`XaTestSuite` 真后端助手。
- Produces: 活体证据（客户端有界 3013、reaper grace+interval 内收敛、IOExecutor 计数回落、后端无孤儿会话）。

- [ ] **Step 1: 写活体冻结测试**

`CommitLegLiveFreezeTest`（docker pause 配方）：
- ① 多 DS PREPARE 后冻结 ds2 ⇒ 客户端 3013 有界 ⇒ 解冻后 reaper 在 grace+interval 内 XA COMMIT、数据可见、意向清除（不丢已决）。
- ② PREPARE 前冻结 ⇒ 3013 + 分支回滚。
- ③ 单 DS `commit;` 冻结 ⇒ 3013 + 无连接/线程泄漏（IOExecutor 计数回落、后端 `show processlist` 无孤儿）。
- ④ 混合路径决定点前冻结 ⇒ 一致回滚（§A PROBE-B 活体版）；决定点后冻结 ⇒ 一致提交。
- ⑤ 变异负控：去包裹 ⇒ ①必红。

- [ ] **Step 2: 重打 fat jar**

Run: `mvn -o -B -DskipTests package`（Monitor persistent + 日志，>10min）
Expected: BUILD SUCCESS，产出新 fat jar。

- [ ] **Step 3: 跑活体测试 + 全量护栏**

Run: `mvn -o -B -pl va,server,example test`（Monitor persistent + 日志）
Expected: 全绿；活体①-⑤证据落日志。任何红按名汇报。

- [ ] **Step 4: 汇报证据（不 commit，待 Task 6 拍板）**

---

## Task 6: 收口回写两板 + 请示 commit

**Files:**
- Modify: 债务总账 / DEBT-QUEUE / PROGRESS（I-DEBT-84 阶段2 状态据实）
- Modify: spec §8 状态行 + §8.5 补记 §A 修正拍板结果

- [ ] **Step 1: 对账看板+代码+活体后据实回写**（列开放项前必三方对账）。
- [ ] **Step 2: spec §8 状态行更新为阶段2 已落地；§8.5 补记 §A 决定点归位修正。**
- [ ] **Step 3: 请示用户 commit（显式拍板，不 push）。**

---

## Self-Review

- **Spec 覆盖**：§8.3 R1(Task1)/R2(Task2)/R3(Task3)/R4(Task1 单 DS 档)/R5(Task4)/R6(Global Constraints 非超时语义不变)；§8.4 测试矩阵①-⑤(Task1/4)+活体①-④(Task5)；§A 修正贯穿 Task1。空事务/begin 不接（Global Constraints）。
- **类型一致**：`markCommitDecided/isCommitDecided/markCommitTimedOut/isCommitOutcomeUnknown` 四方法 Task1 定义、Task2/3/4 消费，签名一致；`commitDecided/commitTimedOut` volatile 同名。
- **Review Focus**：#1→Task1 真后端锁；#2→Task1 decided 测试；#3→Task2 让位测试；#4→Task1 Step5 递归验证；#5→Task5 活体。
- **比例**：plan 以签名/测试名/断言/精确值为主，未转录实现体；§A 为必要设计取证（探针实证），非冗余。