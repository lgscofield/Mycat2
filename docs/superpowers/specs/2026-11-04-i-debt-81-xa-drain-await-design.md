## I-DEBT-81 · XA 语句级排空必须被等待（extraConnections 跨语句累积）— 设计与 RED 锚定

日期：2026-11-04　票：I-DEBT-81　前置：I-DEBT-80（六格取证已收口，判决见其 spec §6）
类型：产品码缺陷（`va` 归还路径 + `hbt` 调用侧 `finally` 安全）　Spec 先行 → **已收口，实测见 §8**（example 基线 5E→0E；共三处生产码：`BaseXaSqlConnection`/`LocalXaSqlConnection`/`VertxExecuter`+`VertxUpdateExecuter`+新 `StatementStateReclaim`）。

### 1. 现象（只引用已实测的事实，不引用推测）

- 基线：`example` 全量 `550 / 0F / 5E / 37skip`；5E 全部来自 `XaBackendConnectionTest`，抛点在 `@Before`
  （`GetConnectionTimeoutException: wait millis 30005, active 65, maxActive 1000, createErrorCount 7`）。
- 格 C（非 XA 对照，同 17 条 × 400 查询，只差 `transaction_policy='proxy'`）：`17/0F/0E`，后端 `Threads_connected` 峰值 14，
  测试期 `close ,but pool count is` **0 命中**。
- 格 B/D/E（XA）：关池时始终带 borrowed（`ds0 37-47 / ds1 37-47 / prototypeDs 126-127`），峰值 128-224。
- 格 E 借用栈：**84/84 全落 `LocalXaSqlConnection.getConnection:145`**，`BaseXaSqlConnection.getConnection` 0 命中。
  ⇒ 脏的是 autocommit 非事务分支的 `extraConnections`，不是事务态参与者。

### 2. 机制钉桩（全部为当前码 `file:line`，本轮重读坐实）

| # | 位置 | 事实 |
|---|---|---|
| M1 | `proxy/.../MycatDataContextImpl.java:195-206` | `transaction_policy='xa'`→`JDBC_TRANSACTION_TYPE`→`LocalXaSqlConnection`，外面再包 `SavepointSqlConnection`；**生产路径不实例化 `BaseXaSqlConnection`**（它是 `LocalXa`/`OnePhaseXa` 的父类） |
| M2 | `va/.../LocalXaSqlConnection.java:145-148` | 非事务分支：每条语句无条件 `mySQLManager.getConnection(targetName)` 借一条并 `extraConnections.add`，不复用、不入 `map` |
| M3 | `va/.../BaseXaSqlConnection.java:445-455` | `closeStatementState()` 最后一行 `return future.onComplete(event -> clearConnections().onComplete(...))`：`onComplete` 处理器返回值被丢弃 ⇒ **返回的 Future 在排空腿被真正发起之前就完成**（fire-and-forget） |
| M4 | `va/.../BaseXaSqlConnection.java:478-498` | 排空腿 `clearConnections()` 用 `future.compose(unused -> extraConnection.close())` **逐条串链**（队头阻塞），`extraConnections.clear()` 挂在链尾 `:484` ⇒ 链不走完就永不减计数 |
| M5 | `common/.../NewMycatConnectionImpl.java:643-651` | `close()` = `future.transform(...)`，挂在**上一条语句的 Future** 上，本身无超时；语句跑在 `IOExecutor` 阻塞线程池里 ⇒ 单条归还的完成时刻不可预测 |
| M6 | `server/.../ReceiverImpl.java:388-396` | SELECT 路径 `onComplete()` 里 `closeStatementState().onComplete(event -> writeRowEndPacket(...))` ⇒ **客户端 EOF 的发出时刻只取决于 M3 那个不等待的 Future**；M3 修好后这里天然就等 |
| M7 | `va/.../LocalSqlConnection.java:184-200`（对照锁） | proxy 轨的 `closeStatementState()` **在方法体内就地 compose 出归还链并 `return future`** ⇒ 语义就是"排空被等待"，格 C 干净与此一致 |
| M9 | `va/.../LocalXaSqlConnection.java:276-283`（**§8.4 补锁时实测挖出，非设计阶段已知**） | `getAllConnections()` 先算 `super.getAllConnections()`（=`map`∪`extraConnections`）、容量也按 `+1` 预留，**却从未把它并入返回值**，只返回 `localSqlConnection` ⇒ XA 轨语句结束时 `BaseXaSqlConnection.closeStatementState():446` 对 extras/参与者**一条 `abandonQuery()` 都不发**（后端结果集不被放弃），且 `server/.../VertxMycatServer.java:327` 的 `TRANSCATION_SMAPSHOT` 少掉全部参与者 |
| M10 | `hbt/.../VertxExecuter.java:428` + `VertxUpdateExecuter.java:323`（**§8.5 普查时挖出，本票改动外溢**） | GLOBAL/索引表 update 展开的 `finally { ...closeStatementState()....get(1, TimeUnit.SECONDS); }`（外层 `@SneakyThrows`）：排空被真等待后，超时/失败的 checked 异常会从 `finally` 抛出、吞掉 `try` 里已算好的 `return res` ⇒ 已用 `StatementStateReclaim.awaitQuietly` 收口 |

因果闭合：M2 每语句加一条 → M3 使排空不被等待 → 下一条语句在上一轮归还尚未落地时又借新的一条 → M4 的链尾 `clear()` 追不上增速 → 会话存活期内占用单调上升 → 撞后端 `max_connections` 后 `getConnection` 等满 `maxWait 30s` 抛出（格 B 签名）。
抬容量（格 D）只是把天花板推后，占用曲线不变（峰值 14→224，关池仍带 borrowed）。

### 3. 要钉住的不变式（本票的"正确"定义）

- **INV-1（核心）**：`closeStatementState()` 返回的 Future 完成之时，本轮 `getConnection()` 借出的连接必须**全部已发起完成归还**，
  且 `extraConnections` 不含这些已归还项。等价于把 M7 的既有语义补齐到 M3。
- **INV-2（有界）**：排空腿不得把 N 条连接的归还**串行化**为 N 倍延迟；单条归还失败只记录、不得中断其余归还，也不得把失败伪装成成功之外的行为。
- **INV-3（不回归）**：事务态（`inTransaction=true`）期间参与者连接（`map`/`localSqlConnection`）绝不被排空——这是 `clearConnections():485-487` 现有分支的保护，必须原样保留。

### 4. 修法（选型与理由）

**采用**：在 `BaseXaSqlConnection` 内改两处，语义向 `LocalSqlConnection` 看齐。

1. `closeStatementState():450` 把"发起排空且不等待"改为"排空完成后才完成"，即 `future.eventually(unused -> clearConnections())`
   后再挂 `xid` 收尾；`xid` 清理条件 `!inTranscation` 原样保留。
   > **落地形式与初稿的偏差（如实记录）**：实际实现用的是 `Promise drained` + 嵌套 `onComplete`，失败原因显式取原 `future` 的
   > `event.cause()`，而不是初稿写的 `eventually`。两者在本场景语义等价（原失败 + 排空成功 ⇒ 结果仍失败且 cause 不变），
   > 选前者的理由是"结果来源写在一行里、不依赖 `eventually` 的失败优先级规则"，属可读性取舍，不是语义差异。
   > **如实记缺口 → 缺口已补**：初稿写下"R1/R2/R3 都走全部归还成功的形态，失败传播这一格没有单元锁覆盖，随 G1 票一并补"。
   > 本轮没有留成文字，直接补了 R4；**R4 首跑即红并挖出 M9**（判决过程与修复见 §8.4）。失败传播自 §8.4 起由 R4 覆盖。
2. `clearConnections():478-498` 的 `extraConnections` 与 `map` 两条腿都改为**并行 join**，并对每条 `close()` 单独 `recover`＋`warn` 记日志；
   快照待排空列表，完成后 `extraConnections.removeAll(snapshot)`（不用 `clear()`，避免抹掉排空期间新借的连接）。
   顺带去掉 `:491-493` 对 `map` 的重复关闭（`executeTranscationConnection(c -> c.close())` 已经关过，重复 close 是无意义动作）。

**否决（写进"明令禁止"）**：

- 抬 `max_connections`（格 D 已证只推后天花板）；常开 `removeAbandoned`（格 E 已证 borrowed 所在池已关、检视任务不跑）；
- 把 `@Before` 的 `readXARecoveryLog` hint 改成整类一次；给类加 `@Ignore` 或修注解语种把红藏起来（那是 I-DEBT-79 护栏要抓的对象）；
- 在 M2 处改成"autocommit 也复用 map 里的连接"——那会改变跨片并发语义，本票不碰；
- 给 `close()` 加超时强杀（需要引入定时器来源，`MySQLManager.setTimer` 是候选，但会把本票范围扩到取消语义）→ **挂账后续票**，见 §7。

### 5. RED 锚定

**5.1 单元级（确定性、不需要活体 infra，`va` 模块，JUnit4＋Mockito，沿用 `XaRecoveryIdempotencyTest` 写法）**
测试替身：`MySQLManager.getConnection` 每次返回一条新 fake `NewMycatConnection`；fake 的 `close()` 返回一个由
`ScheduledExecutorService` 在固定延迟后才完成的 Future（模拟 M5 的异步排队归还）；`abandonQuery()` 返回已成功的 Future（模拟同步）。

- R1 `xaAutocommitDrainIsAwaited`：`LocalXaSqlConnection` 非事务态借 5 条 → 调 `closeStatementState()` 并等其完成 →
  断言完成瞬间 `closedCount == 5` 且 `extraConnections` 不含它们。**当前必红**（完成瞬间为 0）。
- R2 `proxy轨对照已等待`（同一形状跑 `LocalSqlConnection`）：断言完成瞬间全部归还。**当前应绿**，作用是锁住"目标语义=M7"，
  防止把 R1 用"再塞一个 sleep"之类假修法糊过去。
- R3 `drainIsNotSerializedByCloseLatency`：20 条、每条延迟 100ms → 断言完成瞬间 20 条全归还 **且** 总耗时 < 1000ms
  （串行链约 2000ms）。当前红，并行 join 后才绿。

**5.2 活体级（GREEN 之后的验收硬门，承 I-DEBT-80 已锚定的三条件，走 `ci/run-example-guard.ps1` ＋ sampler）**

- L1 `showDataSources` 的 `USE_CON` 在整类跑完后回落到 0；
- L2 后端 `Threads_connected` 相对会话开始前**基线的差分**回落（不看绝对值，避免池世代错位，见 I-DEBT-80 spec F18-F19 的自我推翻）；
- L3 全程 `JdbcDataSource.close` 不得打印非零的 `close ,but pool count is`；
- L4 `example` 全量基线 `550 / 0F / 5E / 37skip` 中的 5E 必须消失或**如实改小并给出新签名**；任何"新增红/新增跳过"都算失败。

### 6. 边界（明写不做什么）

- 不改测试写法、不动 `@Disabled` 注解语种问题（那是 I-DEBT-79 与新事实 F17 的范围）。
- 不碰 `.github/workflows/*`，不动 CI 门（需用户拍板）。
- 不修 UPDATE/INSERT 路径 `ReceiverImpl.java:232-233` 的调用侧丢弃（`.onSuccess(unused -> closeStatementState())` 返回值被扔）——
  **新事实 M8/F24**：即便 M3 修好，写路径的响应仍不等排空。本票只修 SELECT 侧不变式；写路径另立票，避免混装。
- 不改 `LocalXaSqlConnection.commit/rollback` 的 I-DEBT-72 语义（失败不还池、`abandonConnection`）。

### 7. 收口硬门与挂账

- 硬门：R1/R3 先红→改码→R1/R2/R3 全绿→`va` 全模块测试绿→全量护栏（L1-L4）→账本与看板对齐→commit（不 push）。
- 挂账（本票不做，已在 §4 否决理由里写明）：
  - G1 单条归还的**超时兜底**（防 `close()` 永久悬挂拖死 `eventually`）；候选定时器来源 `MySQLManager.setTimer`。
  - G2 写路径 M8/F24 的调用侧等待。
  - G3 `close()` 与语句 Future 的耦合（M5）是否应改为显式"归还前必须无在途语句"。

### 8. 实测收口（2026-11-04，逐条为原始输出摘录，非推测）

改动面：生产码仅 `va/.../BaseXaSqlConnection.java`（`closeStatementState` 等待排空、`clearConnections` 快照＋并行 join、
新增 `joinCloses`/`joinVoidFutures`）；新增测试 `va/.../XaExtraConnectionDrainTest.java`（JUnit4＋Mockito，3 条）。

**8.1 单元链**

| 阶段 | 实测 |
|---|---|
| RED（改码前） | `Tests run: 3, Failures: 2`：R1 `排空 Future 完成时已归还的连接数 expected:<5> but was:<0>`、R3 `expected:<20> but was:<0>`；R2（proxy 轨对照锁）绿 ⇒ 红点正落在 M3 |
| GREEN | `XaExtraConnectionDrainTest 3/3`；`va` 模块 `15/0F/0E` |
| 变异负控 | 把 `joinCloses` 临时改回串行 compose ⇒ **只有 R3 红**（`排空耗时 2129ms 不应是 20 段串行延迟`），R1/R2 仍绿 ⇒ INV-1（等待）与 INV-2（不串行）被两把独立的锁钉住，不是同一断言的副产品；探针已删，`git diff` 复核无残留 |
| 多模块回归 | config 30 / common 49 / router 18 / datasource 7 / va 15 / proxy 31 / hbt 299 / executor 6 / server 164 = **619 条 0F/0E**，BUILD SUCCESS（9:37 min） |

**8.2 活体硬门（`ci/run-example-guard.ps1 -Persona MYSQL_8_0`，带修复重打的 jar mtime 05:37:09，姿态门 `front=8.0.34 / back=8.0.46` OK）**

- **L4 前半·单类**：`XaBackendConnectionTest` `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 233.8 s`
  （I-DEBT-80 格 B 同配方为 `17/0F/3E`）。
- **L2 后端差分**（`sample-backend.csv`）：起跑基线 `Threads_connected=1`；全程峰值 **14**；`Connection_errors_max_connections`
  绝对值 452→452（**一次都没撞容量**，对照格 B 峰值 128/`err_delta=446`、格 D 224、格 E 147/`err_delta=6`）；跑完回落 **1**。
- **L1**（`sample-showdatasources.txt`）：`USE_CON` 随负载在 0-6 振荡、与 `EXIST_CON` 同值、跑完回 0 ——
  如实写法是**不累积**（不是"恒为 0"）；旧故障形态是新一代池报 ≤3 而后端挂着 128-224 条旧世代孤儿，两者不可混为一谈。
- **L3**（`target/ci-guard/proxy-MYSQL_8_0.log` 25,987,870 B 逐关键字计数）：
  `close ,but pool count is` **0 命中**（格 B/D/E 每轮 3 条带 borrowed 37/47/127）；`return xa connection to pool failed` 0
  ⇒ 并行归还无静默失败；`GetConnectionTimeoutException` 0；`Could not create connection` 0；
  既有守卫文案 `localSqlConnection is not closed` / `xa connection map is not empty` 0 / 0 ⇒ INV-3 未破。
- **L4 全量**：`[WARNING] Tests run: 550, Failures: 0, Errors: 0, Skipped: 37` + `BUILD SUCCESS` + **红类清单为空**
  ⇒ **`example` 基线由 `550/0F/5E/37skip` 变为 `550/0F/0E/37skip`**，§5.2 预言的"5E 消失"兑现；
  全量期间后端峰值 `Threads_connected=71`（容量 151）、`err_delta` 全程 0、跑完回 1。
- 噪音已追不因本票而豁免：全量 proxy 日志另有 13 次 `GetConnectionTimeoutException`＋39 次 `Could not create connection`，
  根因链挖到 `Caused by: WrongArgumentException: Failed loading the class 'io.mycat.compat.NoSuchSocketFactory__poison'`
  ⇒ 出自 `example/src/test/java/io/mycat/compat/BackendAuthMatrixTest.java:86` 的故意毒化负控；
  签名 `active 0, creating 1, createErrorCount 7` 与泄漏签名 `active 65` 方向相反 ⇒ **预存在噪音、非本票回归**，已随本票留痕。

**8.3 如实挂着的未做项（不写成本票完成）**

- 写路径 M8/F24（`ReceiverImpl.java:232-233` 丢弃 `closeStatementState()` 返回值）**未修**：本票只把 SELECT 腿的等待补齐。
  本轮 0E 说明该套件形态下够用，但写路径的泄漏窗口客观存在 ⇒ 仍归 G2，应另立票。
- DEBT-QUEUE 立案时列的修法候选「`LocalXaSqlConnection.closeStatementState()` 补 `extraConnections` 守卫」**本票未做**：
  该守卫只能以日志文案形式断言，而本仓无日志断言设施；与其加一把失明的锁，不如随 G2 票以行为断言一并覆盖。
  已同步记入队列行，不作为"已落实"记账。
- 未做（需用户拍板）：I-DEBT-79 刀②（把护栏接进 CI PR 门）、刀③（全局 abort→`NO_DATA` 语义）。
- 卫生复核：护栏 `finally` 杀掉本轮 8066（`KILL 8066 pid 45916`，现 8066 无监听）；后端 `max_connections=151`、
  `Threads_connected=1` 与取证期临时改动的回退态一致，未被本轮污染。

**8.4 补锁时挖出的第二枚真缺陷（M9）与判决过程**

§8.3 初稿把"排空腿失败原因的原样传播"记成未覆盖缺口。补这道锁的成本很低，于是补了——**锁一跑就红，且红得与我的预期相反**，
这枚缺陷就是这么掉出来的：

1. 新增 R4 `testR4_statementLegFailureStillDrainsAndKeepsCause`：让 fakes 的 `abandonQuery()` 以固定 `IllegalStateException` 失败，
   借 3 条后排空，断言 ①完成瞬间 3 条都已归还 ②失败原因原样传播。
2. **首跑 `Tests run: 4, Failures: 1`**，实得 `expected same:<...abandonQuery boom> was not:<null>` ⇒ Future 以**成功**完成。
3. 我先怀疑自己的实现吞了失败，也怀疑 Vert.x 的 `join` 语义，**没有靠推理下结论**：临时探针 `ZzJoinSemanticsProbeTest` 实测
   （用完即删，含 `va/target/test-classes` 里的残留 class）⇒
   `join(List:1failed+1ok) -> failed=true cause=NoStackTraceThrowable:boom-a`、`join(F1,F2) -> failed=true`、
   `all(...)`、`failed.flatMap` 全部 `failed=true` ⇒ **join/flatMap 都会传播失败，我的实现也没吞**。
4. 真根因在**上一格**：`LocalXaSqlConnection.getAllConnections():276-283` 算出 `super.getAllConnections()` 却从未并入返回值
   （容量按 `allConnections.size() + 1` 预留，漏并是笔误）⇒ 非事务态 `localSqlConnection==null` 时返回**空列表** ⇒
   `stopResultSet` 为空 ⇒ 没有 abandonQuery 被调用 ⇒ 既没有失败可传播，也没有结果集被放弃（M9）。
   对照 `LocalSqlConnection.getAllConnections():176-181`（proxy 轨）明确并了 `map.values() + extraConnections` ⇒ 差异即缺陷。
5. **修法一行**：`resList.addAll(allConnections);`（带 M9 注释）。安全依据：`NewMycatConnectionImpl.abandonQuery():669-675`
   只做"`resultSet != null` 则关闭并置空、返回已成功的 Future"，幂等且对空闲连接无副作用。
6. **RED→GREEN**：R4 由红（cause=null）转绿；`XaExtraConnectionDrainTest 4/4`、`va 16/0F/0E`。
   R4 同时是 M9 的回归锁：把并集删掉它必红（本轮已实测该形态）。
7. 附带修好的观测面：`show` 的事务快照 `TRANSCATION_SMAPSHOT`（`VertxMycatServer:327`）此前在 XA autocommit 会话下恒为 `[]`。

**因 M9 是第二处产品码改动，本轮重新背书**（数字见 §8.5）：重打 fat jar → 全量护栏 + 采样器差分 → `proxy/hbt` 模块回归。

**8.5 本票改动外溢出的第三格：调用侧 `finally` 里的阻塞等待（静态取证 → 已落地）**

等背书跑的同时，对 `closeStatementState()` 做全仓普查（25 命中）逐格定性"谁在等这个 Future"：

- 事务控制腿 `ReceiverImpl.java:139/148/156/165/174/183`（`.eventually`）、`sendOk` 腿 `:256`、SELECT 腿 `:391-395`、
  `ObservablePlanImplementorImpl.java:72`（`.eventually`）、`VertxSessionImpl.java:135/210`（`return` 出去）**都在等** ⇒ 本票修好排空后，
  这些路径语义自动变强（"归还完再写 EOF"），无需改动。
- 唯一不等者是写路径 `ReceiverImpl.java:232-233` ⇒ 已记 M8/F24，归 G2。
- **另发现两格形态不同**：`hbt/.../VertxExecuter.java:428` 与 `hbt/.../VertxUpdateExecuter.java:323` 在
  `finally { ...closeStatementState().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS); }` 里**同步阻塞等 1 秒**，
  两处外层方法均带 `@SneakyThrows`（`VertxExecuter.java:308-309`、`VertxUpdateExecuter.java:165-166`）。

风险不来自"阻塞"本身（该阻塞是预存在写法），而来自本票让排空**真的会失败传播**：`TimeoutException`/`ExecutionException` 是 checked
且被 `@SneakyThrows` 原样抛出，而它出自 `finally` ⇒ **会吞掉 `try` 里已经算好的 `return res`**（GLOBAL 索引表 update 的展开结果直接丢失，
表现为语句失败而非降级）。本票之前 `abandonQuery()` 在 XA 轨根本没被调用（M9），失败面近乎为零，所以这格一直没炸；
M9 修完后它变成可达路径 ⇒ **本票有义务把它一起收口**，不能留给运气。

处置方案（第三处改动，最小面）：`hbt` 新增小工具 `io.mycat.vertx.StatementStateReclaim.awaitQuietly(Future<Void>, long timeoutMillis)`：

- `InterruptedException` ⇒ 恢复中断标志后返回（不吞中断语义）；
- `TimeoutException` / `ExecutionException` / 其它异常 ⇒ 只 `LOGGER.warn` 记原因，**不向上抛**，因此 `finally` 不再覆盖 `return res`；
- 语义即"尽力等一等，等不到不影响语句结果"——与这两处调用点的原意（加速回收资源）一致，且比旧写法更保守（旧写法会炸返回值）。
- 两处调用点各替换一行，不改其它逻辑；helper 配确定性单测（不等后端）：
  ①50ms 完成的 future + 10ms 预算 ⇒ 不抛且方法正常返回；②以异常完成的 future ⇒ 不抛；③预算内完成 ⇒ 不抛。

**落地实况（本票已按上述方案收口，非仅计划）**：

- 新增 `hbt/.../StatementStateReclaim.java`：`awaitQuietly(Future<Void>, long)`，`null` 直接返回；`InterruptedException` 恢复中断标志后
  warn；`TimeoutException` warn；其余（含 `ExecutionException`）走 `catch (Exception)` warn——**任何分支都不抛**，故 `finally` 不可能吞返回值。
- 两处调用点各换成 `StatementStateReclaim.awaitQuietly(context.getTransactionSession().closeStatementState(), 1000);`，
  并删掉两文件因此失去唯一用途的 `import java.util.concurrent.TimeUnit;`（VertxExecuter/VertxUpdateExecuter 各一行）。
- 新增确定性单测 `hbt/.../StatementStateReclaimTest.java`（JUnit5，**7 条 0F/0E**）：成功/null/失败 future 均不抛、超时预算掐住等待（`<500ms`）、
  `invokeWithDrainFinally` 端到端证明失败排空时 `return "res"` 仍存活、`swallowDemo` 反向对照证"若 finally 真抛则返回值必丢"。
- **第三处已查、如实划界外**：`common/.../TransactionSession.java:73` 的 `deliverTo` default 方法也有 `closeStatementState()....get(1, TimeUnit.MINUTES)`，
  但它**不在 `finally`**、且超时/失败即"不交付新事务会话"是 fail-safe 正确语义（没有 `try` 里待吞的返回值）⇒ 不改，仅留痕。
- 因这是**第三处生产码改动**（hbt），已重打 fat jar + 重跑全量护栏（`-pl server,example -am test` 连带跑 hbt update 路径单测 + example e2e）背书，
  数字记入 sdd 账本 `2026-11-04-i-debt-81-verify-srr/`。

**8.6 R5：INV-2「逐条容错 + 并行」的独立语义保真锁（补锁，实测驱动）**

§5.1 的 R1/R3 钉住 INV-1（等待）与 INV-2 的「不串行」，但**没有一格单独钉住「单条归还失败不得阻塞其余归还」**。
本轮补 R5 `testR5_singleCloseFailureDoesNotBlockTheRestOfDrain`：借 4 条，让第 2 条的 `close()` 以固定原因失败，断言
①四条 `close()` **全部被发起**（`closeInvokeCount==4`）②三条成功归还（`closedCount==3`）③`closeStatementState()` 结果仍成功
（归还失败被 `joinCloses` 的逐条 `recover` 吸收，不上抛给语句响应）④排空后 `extraConnections` 不残留。

变异负控（`run-r5-mutation.ps1`，用完即回退，`git diff` 复核无残留）：

- **serial（把 `joinCloses` 改回逐条惰性 compose 串行链 = M4 旧形态）** ⇒ **R5 红**（`四条归还必须全部发起 expected:<4> but was:<2>`，
  第 2 条失败短路后续不再发起）**且 R3 红**（`排空耗时 2171ms`）⇒ R5 是独立于 R3 的锁，专门抓「失败即短路、后续不归还」这一 INV-2 面。
- **nolock（去掉逐条 `recover`）** ⇒ **R5 绿**：如实记录这不是漏锁——`closeStatementState()` 的返回结果**恒取语句腿 `future`**（`BaseXaSqlConnection:461-464`），
  排空腿 `clearConnections()` 的成败不进入公开结果，故 `recover` 的失败吸收在本票公开的 `closeStatementState` 缝上**不可观测**；
  能在该缝上观测到的只有「并行发起 + 不阻塞」，而这已被 serial 负控证实由 R5 钉住。若将来要让归还失败影响调用方语义，属 G1（超时兜底）/G3（关池耦合）新票范围，不在本锁。

**8.7 护栏脚本硬化（背书现场挖出的仪器坑）**

本轮第三次背书启动后，`guard-20261105-085654.log` 一路 `Connection refused`（3307/3308）却照跑——根因是
`ci/run-example-guard.ps1` 旧写法只 `docker start` 单个 `$BackendContainer`（默认 `m1-mysql-a`＝3306），而 example 全套还用
3307（分片库 m1-mysql-b）与 3308（MariaDB 轨 m6-mariadb-a）；WSL/Docker Desktop 重启后那两个容器停在 `Exited` ⇒ proxy 连不上后端、
测试仍跑完 = **假红温床**（正是 I-DEBT-79 要堵的污染向量）。硬化：新增 `-BackendContainers`（默认三档）、逐个 `docker start`＋`mysqladmin ping` 就绪等待，
任一把不上即 `ABORT(backend)` 不跑测（宁 NO_DATA 不假红）；`-BackendContainer` 单值仍兼容旧调用方。
