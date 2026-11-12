# I-DEBT-84 · Mycat 侧语句级超时 deadline（D-B'，兜住 R1c 实证的"等后端响应无界挂"）—— Spec 先行

> **状态**：🟢 实码推进中——statement/DML/SELECT/事务腿 Phase1 已落地（§7.6）；Phase2 阶段0+1（提交意向持久化 + 独立事务 reaper）已落地实证（§7.8）；**阶段2（commit 腿接 deadline）已落地实证（§8.7，2026-11-12，含决定点归位 §8.6 + C1 递归终止修复）；commit 待拍板。遗留 I2b（force-close=Druid recycle 非物理 abort，冻结后端 worker 解困）属 Phase1 全腿共享机制，升级独立拍板（§8.7）。**
> **分层**：C（引擎地基 / 生产稳健交界，承 I-DEBT-80/81/82/83 资源/可用性族）。
> **一句话**：给"Mycat 等后端响应"这条 Future 套一个 wall-clock deadline，使客户端在**后端僵死/TCP 黑 hole**（服务端超时也救不了的场景）下
> 仍在**有界**时间内拿到错误，兑现招牌"无缝替代 MySQL"的可用性底线；且**不得把"客户端解挂"变成"线程/连接泄漏"**。

---

## §0 北极星对齐

真 MySQL：后端僵死时客户端靠 `net_read_timeout`/`socketTimeout` 有界报错。I-DEBT-83 R1c 实测：Mycat **自身无任何读超时界**，
后端一旦被冻结，客户端无限挂；解冻后才靠后端自带的 `innodb_lock_wait_timeout` 脱困。D-B' 要把这个"界"补在 Mycat 侧，
不依赖运维给每个 datasource URL 手工配 `socketTimeout`（那是 I-DEBT-83 的 D-A1，属外部约束、非产品能力）。

---

## §1 语句执行链与悬挂点（file:line 实证）

| 点 | file:line | 事实 |
|---|---|---|
| 后端 SQL 投递 | `server/.../JdbcMySqlConnection.java:151-158` 等 | 一律 `ioExecutor.executeBlocking(promise -> 阻塞 JDBC; 完 promise)` |
| executeBlocking 语义 | `common/.../IOExecutor.java:66-81`/`:101-133` | cached 线程池跑阻塞码；**promise 仅在 handler 返回时完成**；无 timer；`interrupt/kill`(:34-60) **救不了阻塞 socket 读** |
| 语句结果 await | `server/.../ReceiverImpl.java:245→:256 sendOk`（写）、`:391`（读 EOF）、事务腿 `:138-182` | 客户端响应发出**排在语句 Future 完成之后** ⇒ 语句 Future 不完成 = 客户端不返回（R1c 现场） |

**悬挂本质**：后端冻结 → `innerExecute` 的 socket read 永不返回 → `promise` 永不完成 → await 它的响应腿永不发 → 客户端无限挂。
（对照 I-DEBT-83：这是**语句腿**，早于 `closeStatementState`/排空；故与 I-DEBT-83 的 D-B(close 超时) 是不同腿、不同票。）

---

## §2 deadline 能界什么、不能界什么（本票核心取舍）

- **能界（客户端）**：把 await 的语句 Future 与一个 `setTimer(N)` race——timer 先到即让**客户端 Future** 以超时错误完成 ⇒ 客户端 N 内有界返回。定时器源候选：`mySQLManager().setTimer`（`BaseXaSqlConnection.java:225/:376` 已有用法）或 `Vertx.setTimer`。
- **不能界（资源，除非补 force-close）**：IOExecutor 那个 worker 线程仍卡在 socket read 里；那条物理连接仍占用中。**deadline 本身解不了它们**。若不处理，超时 = 客户端解挂但**每次泄漏一个线程 + 一条物理连接**（正是 I-DEBT-80 孤儿签名）——把可用性问题偷换成泄漏问题，不可接受。
  - 解困唯一可靠手段：**由 timer 处理器强制关掉那条物理 socket**（Connector/J 阻塞读在 socket close 时抛错返回 → worker 解困、连接作废不入池）。需从语句句柄拿到底层 `java.sql.Connection` 并 close/discard；`Statement.cancel()` 走**另开连接发 KILL**，对冻结后端无效，不可依赖。
  - 退路：deadline 只管客户端，物理回收交给 `socketTimeout`（与 I-DEBT-83 D-A1 组合成"双保险"）——但那又回到依赖 URL 配置。

**⇒ 结论**：纯 Mycat 侧、零配置的完整方案 = **deadline（界客户端）+ 强制 close（回收线程/连接）** 两件一起；只做前者不算收口。

---

## §3 语义与参数（待 plan 定值，spec 先列约束）

- **错误呈现**：须伪装成 MySQL 风格错误码保 persona（候选 `ER_QUERY_TIMEOUT` 3024 / 或 Mycat 自有码），文案与"无缝替代"一致。
- **作用域**：全语句统一 deadline vs 仅"疑似长"语句？默认 N 取多大（远大于正常语句、远小于无限）？能否被会话变量/datasource 覆盖？
- **与既有旋钮的关系**：`DatasourceConfig.queryTimeout`(:40，Druid→`Statement.setQueryTimeout`) 是**服务端取消**，对冻结后端无效 ⇒ D-B' 与它正交、互补，不冲突。
- **事务/XA 交互**：deadline 触发的强制 close 落在事务参与者连接上如何回滚/隔离（承 I-DEBT-81 INV-3：事务态参与者不被排空）——需专门设计，勿误伤事务态。

---

## §4 插入点候选（择一，plan 定）

1. **Receiver 层统一包**：在 `MycatdbCommand.execute(...)` 返回的 Future 上套 `withDeadline(fut, N, onTimeout=强制close)`（一处覆盖读写+事务腿，面最小、最集中）。
2. **executeBlocking 层**：给 `IOExecutor` 加带 deadline 的 `executeBlockingWithTimeout`（贴近阻塞点、能直接握到底层连接做 force-close），但要改多个调用点。
3. **各语句结果 await 点分别包**（写 `:256`/读 `:391`/事务腿）：最贴响应语义但改动分散、易漏。

倾向 **1 + 需要 2 的 force-close 能力**（deadline 在 Receiver 统一界客户端，回收靠能握到连接的那层）。plan 阶段定。

---

## §5 取证与硬门

- **确定性单测锁**（仿 `XaExtraConnectionDrainTest`）：注入一条"永不 complete 的 executeBlocking handler"，断言 `withDeadline` 后的客户端 Future 在 `N+ε` 内以超时错误完成；断言 timer 不泄漏（语句正常先完成时 timer 被取消/变 no-op，用 `tryComplete` 幂等）。
- **变异负控**：去掉 deadline ⇒ 该锁必须红。
- **活体终检**：复用 I-DEBT-83 **R1c 行锁+`docker pause`** 配方——后端冻结下断言客户端**由 Mycat 自己在 N 内报错**（不再等后端解冻）、且**线程/连接被回收**（IOExecutor 计数回落、后端该会话被 close 而非留孤儿，`showDataSources` 的 `ActiveCount`/`CreateCount` 有界）。
- 改生产码必重打 fat jar 再验；全量护栏（L1-L4）+ `va`/`common`/`server` 模块测试绿；账本+DEBT-QUEUE+PROGRESS 对齐；**commit 不 push**。

---

## §6 拍板结果（2026-11-05 用户定，转 plan）

本票改动落**语句执行/响应主路径**，风险面比 I-DEBT-81/82 大。用户三岔路已定：
- **(a) deadline 值与作用域 = 已定**：全局默认 **30s**，且**以配置项暴露**（非硬编码）+ **datasource 级可覆盖**；会话级覆盖是否纳入留 plan 定。既兜住默认配置（可用性底线）、又免"改默认值要动码"。
- **(b) 资源回收深度 = 已定**：**deadline + 强制 close socket 两件一起做**（纯 Mycat 侧、零外部配置闭环；不留"客户端解挂却泄漏线程/连接"的 I-DEBT-80 孤儿面）。事务/XA 参与者 force-close 不误伤（承 INV-3）须在 plan 专门设计。
- **(c) 交付节奏 = 已定**：**先出 plan 再动码**（含具体 file:line 改动点 + 错误码选择 + 插入点定档 + 测试矩阵），plan 审阅通过后才实现。

（承 I-DEBT-83：G1 的悬挂已被 R1c 坐实在语句腿；本票是那枚悬挂的正解，非 I-DEBT-83 原位的 close 兜底。）

---

## §7 事务腿（commit/rollback/savepoint）设计定档（2026-11-06 对标同类后 refined-B）

### §7.0 触发
子项②（SELECT 流式腿）已收口（HEAD `1e4b5dda6`）。剩余事务腿须对齐同类口径，故先做一手对标取证再定实现，不凭猜选 A/B。

### §7.1 事务腿悬挂面实证（当前 HEAD 实读 `va/.../BaseXaSqlConnection.java` + `LocalXaSqlConnection.java`）
- **`begin()` = 不接 deadline**：`BaseXaSqlConnection.begin():70-80`/`LocalXaSqlConnection.begin():43-51` 仅置 `inTranscation=true`+写协调日志，**不碰后端 JDBC**（连接由后续 `getConnection()` 懒取）。无阻塞面=无物可界。
- **`commit()` = 有悬挂面**：`commitXa():271-351` 经 `connection.update("XA END/PREPARE/COMMIT")` 走 `ioExecutor.executeBlocking` 阻塞 JDBC，后端冻结则该 future 永不完成→客户端无界挂；**且**因是“永挂而非失败”，状态机自带的 `retryCommit()`/`rollback→kill+tryRecovery` 根本不触发。
- **`rollback()` / savepoint 腿 = 有悬挂面**：同理发 `XA END/ROLLBACK`；失败已有 `kill()+tryRecovery()`（`rollback():169-186`）。

### §7.2 同类对标（一手资料，非二手结论）
**Vitess**（架构最接近：proxy+shard）——vttablet 文档实锤两把独立刀：
- `--queryserver-config-query-timeout`(30s)：“If a query takes more than this timeout, **it will be killed**”。
- `--queryserver-config-transaction-timeout`(30s)：“a transaction will be **killed** if it takes longer than this value”（独立事务 reaper）。
- issue #21627（原句）：“A statement that is still executing when the deadline expires is not affected: **vttablet kills the connection, which rolls the transaction back.**” 且该 reaper **明确跳过已 PREPARE 的 2PC 事务**（不杀），交给 unresolved-transaction 恢复路径。

**ShardingSphere-Proxy**——靠 JDBC `Statement.cancel()` 优雅中断在途语句 + 断连时 `closeAllResources` 清理（issue #40375 侧证靠“取消+清理”而非吊客户端）；分布式 XA 交外部 Seata/XA recovery。

**结论**：同类**都不是纯 A**（“只界客户端、不动连接”），而是 **“超时→kill/回滚 + in-doubt 走 recovery”**。按用户规则（“都是 A 就直 A，否则要对齐”）⇒ **对齐 peers = refined-B**，非纯 A。Mycat 已内置同类机制（`kill`=abandonConnection + `xa_log`+`tryRecovery`+`retryCommit`），refined-B 是**复用既有路径**而非新造轮子，blast 远小于裸从外部强关。

### §7.3 与 peers 思维的三处差距（2026-11-06 复盘补入，机制名对齐 ≠ 思维对齐）
大原则三家一致（"光界客户端不够，必须真打断后端 + 给悬挂留 recovery 出路"），但下述三点 peers 想得比我们当前设计细/分层干净，须显式纳入：
- **① 打断的"温和优先·分级升级"**：Vitess/ShardingSphere 对**仍活着只是慢**的后端，先试**协作式取消**（MySQL `KILL QUERY` / JDBC `Statement.cancel()`）——让**后端自己**干净回滚、正确释放锁；确认叫不动才升级到丢 socket。我们当前 `abandonConnection` 一步跳到"丢 socket"极端档，**因为 R1c 动机现场是 `docker pause` 的冻结后端（cancel 本就无响应）**，从那个极端出发漏了"活但慢"这条更常见的轴应先走温和档。⇒ 正解=**cancel 优先、force-close 兜底**的两级；冻结后端自然落到第二级，语义不变。
- **② 独立后台事务 reaper（纵深防御）**：vttablet 的 `transaction_timeout` 是**与客户端 deadline 解耦的第二把刀**，专兜 deadline 因竞态没堵死的窗口（其 issue #21627 自认"BeginExecute 泄漏窗口未关死"，正靠这把 reaper 收尸）。我们把"界客户端"与"回收悬挂事务"**合成同一条 deadline**，少了一层独立保险；单机制一旦被竞态绕过即无兜底。⇒ 待评估引入**类 transaction_timeout 的后台事务 reaper**（详见 §7.5）。
- **③ 分层 vs 一体化（有意的取舍，非缺陷）**：Vitess 把**协调器(vtgate)** 与**物理连接所有者(vttablet)** 分两层，deadline 在上层、kill/reap 在下层，**2PC in-doubt 与超时路径天然解耦**。Mycat proxy **既当 xa_log 协调器、又当连接所有者**，deadline/force-close/recovery 全在一处——好处=自持闭环、零外部依赖（正是我们否掉 ShardingSphere 式"外包 Seata"的选择），代价=in-doubt 与超时强耦合、更难单测、且会冒出"timer 外部 abandon 与状态机 retryCommit 抢方向盘"这类同体竞态（§7.4 Phase 2 难点的本质即此）。⇒ 记录为设计自觉的取舍，不强行改分层，但每处耦合都要在实现里显式处理。

### §7.4 refined-B 分阶段（下轮 plan 直接取用，已并入 §7.3 ①②）
- **Phase 1（低风险、可本地自证）= rollback/savepoint 腿**：包 `StatementDeadline` 界客户端（超时回 3013）。onTimeout **按 §7.3① 分级**：先对参与者发协作式取消（`abandonQuery`/尽力 `KILL QUERY`），**确认仍在挂**再 `abandonConnection()` 丢 socket 兜底。因 rollback 路**未 PREPARE**，弃连接后 MySQL 对**非 prepared** XA 分支断连即**自动回滚**=正是期望结果（同 Vitess kill→rollback），无 in-doubt 风险。`begin()` 不接。
- **Phase 2（架构级、需活体 2PC 故障注入取证）= commit 腿**：超时同样界客户端+分级打断，但 past-prepare 会落 **in-doubt（prepared 分支跨断连存活）**，必须靠 `xa_log`+`tryRecovery`/`retryCommit` 收敛。难点=timer 外部 abandon 与状态机自身 `commitXa` 失败回调/`retryCommit`（setTimer 重连）抢方向盘（§7.3③ 同体耦合的直接产物）、及 `inTranscation` 标志与会话复用一致性。**若 §7.5 引入独立事务 reaper，则 commit 腿的 in-doubt 兜底可从"脆弱地挂在客户端 deadline 上"改挂到那条与客户端解耦的 reaper 上**，Phase 2 难度显著下降。需专门设计+活体取证才能诚实背书（确定性单测锁不住"不损坏 in-doubt"）。未过拍板不贸然改 commit 路径。
- **错误码**：沿用 3013（同 statement 腿）。

### §7.5 待评估项（需用户拍板，属高风险/架构级）
- **是否引入类 Vitess `transaction_timeout` 的独立后台事务 reaper**（§7.3②）：这是把 Phase 2 commit 腿的 in-doubt 兜底从"绑在客户端 deadline"解耦成"独立收尸线程扫 xa_log 里超期的 prepared/未决事务并 recovery"的关键杠杆，也更贴 peers 的纵深防御思维。代价=新增一个调度/扫描机制（Mycat 已有 `ScheduleUtil.getTimer()` + `xa_log`，落地面不算从零）。**建议评估后引入**，因其同时缓解 §7.3③ 的耦合难点。
- Phase 2 commit 腿的 abandon↔状态机重试竞态收口方式、in-doubt 后客户端语义（报"提交结果未知"还是确定 3013）——均需拍板后才投实现，不在本轮盲改 2PC 提交路径。

### §7.6 一手机制校正（2026-11-06 事务腿实现前取证，file:line 实测，修正 §7.3①/§7.4 的两处书面假设）
推进 Phase 1 前对当前 HEAD 实读，两处与 §7.3/§7.4 书面假设不符，须以实证为准：
- **① 的"协作式取消"在本 JDBC 栈对事务腿不存在**：`NewMycatConnectionImpl.abandonQuery():669-675` 仅 `JdbcUtils.close(resultSet)`，**不发 MySQL `KILL QUERY`、不 `Statement.cancel()`**——对**只读结果集流**可解困，但对事务腿真正悬挂的 `update("rollback;"/"XA ..."/savepoint)`（`NewMycatConnectionImpl` 经 `ioExecutor.executeBlocking` 的**阻塞同步 JDBC 调用**）**叫不动**。⇒ 事务腿唯一能打散阻塞 worker 的手段就是 `abandonConnection()`（`JdbcUtils.close(connection):664-665`）丢 socket。故 **Phase 1 直取 force-close，不做 §7.3① 的 cancel-优先两级**；cancel-优先要求新增第二条通道发异步 `KILL QUERY`（独立后续能力债，本票不做），且**对 rollback 而言 force-close 本就语义正确**（断连令 MySQL 自动回滚未 prepared 分支/本地事务=正是 rollback 意图），cancel-优先在此反是多余档。
- **本地单 DS `commit()` 也非"无 in-doubt"**：生产会话 = `SavepointSqlConnection(LocalXaSqlConnection)`（`MycatDataContextImpl:199-206`，默认 `JDBC_TRANSACTION_TYPE`）。`LocalXaSqlConnection.commit()` 单 DS 本地档走 `localSqlConnection.update("commit;"):74`——**无 PREPARE/无 xa_log**，但一旦后端"已提交后冻结"，客户端无从区分"已提交"与"未提交"，强关+回 3013 即误报失败=**与多 DS 2PC 同类的 in-doubt**（此前只把 in-doubt 系于多 DS commitXa 是窄了）。⇒ **commit 腿整体（本地单 DS + 多 DS 2PC）一律归入需拍板的 Phase 2，不塞进 Phase 1**。
- **savepoint 腿确为真悬挂面（非 no-op）**：基接口 `XaSqlConnection` 的 savepoint 默认方法返 `succeededFuture`（no-op），但生产被 `SavepointSqlConnection` 装饰器覆盖为真实 `connection.update(savepoint-sql)`（`SavepointSqlConnection:30-49,83-90`），可挂⇒ 纳入 Phase 1。
- **Phase 1 定案（据上修正）**：`rollback()`/`setSavepoint()`/`rollbackSavepoint()`/`releaseSavepoint()` 四腿包 `StatementDeadline`，onTimeout **直接 `forceCloseInFlight`**（对 `getExistedTranscationConnections()` 逐个 `abandonConnection()`），超时回 3013；`begin()` 不接；`commit()`（含本地单 DS）留 Phase 2。deadline 值沿用 datasource 级 `statementTimeout` 最小值 + 全局默认 30s 兜底（与 statement 腿一致）。

### §7.7 独立事务 reaper 取舍评估（2026-11-06，§7.5 落地可行性实证）
- **地基实锤存在**：`ScheduleUtil.getTimer()` 单例 `ScheduledExecutorService`（`ScheduleUtil:28-33`）可挂周期扫描；`MySQLManager.setTimer(delay, runnable)` 已被 `retryCommit()`/`retryRollback()` 使用（`BaseXaSqlConnection:225,376`）；`xa_log` 表 + `log.readXARecoveryLog()`/`tryRecovery()`（`BaseXaSqlConnection:191-213`）即 recovery 现成入口。⇒ reaper **不必从零造**，可复用既有定时器 + xa_log + recovery 三件套。
- **但它救的是 Phase 2，不是 Phase 1**：Phase 1（rollback/savepoint）的悬挂已被客户端 deadline + force-close **自洽收口**（未 prepared 分支断连自动回滚，无残留）。reaper 的价值在于兜"commit 已发出但结果未知/客户端 deadline 竞态漏堵"的**未决事务**——正是 Phase 2 的核心难点与 §7.3②③ 的耦合根源。
- **推荐（带代价）**：**评估结论=倾向引入，但作为 Phase 2 的前置解耦杠杆单独拍板，不与 Phase 1 捆绑**。选它：Phase 2 commit 的 in-doubt 兜底从“脆弱挂在客户端 deadline”改挂到与客户端解耦的后台收尸线，天然贴近 peers 纵深防御，且已有三件套使落地成本可控。代价与边界：① 新增一个常驻调度/扫描机制（全局单例 vs per-schema、扫描周期与 `xa_log.expires` 对齐）；② 语义须精确定性——仅扫**超期且未决**事务、走既有 `readXARecoveryLog`+`retryCommit`，禁与在线客户端事务抢方向盘；③ 活体 2PC 故障注入才能诚实背书“不损坏 in-doubt”，确定性单测锁不住。**因涉全新增子系统、有外溢（后台线程持续动后端事务），属须用户拍板项，本会话只出评估不擅自实现。**

### §7.8 Phase 2 阶段0+1 已落地实证（2026-11-06 实码，用户拍板后按 plan `I-DEBT-84_Phase2_Reaper` 执行）
> §7.7 的“只评估不实现”已被后续拍板推翻：用户批准先做阶段0+1（低风险、不碰在线 commit 正常路径）；阶段2（commit 腿接 deadline）仍未动，留独立子票。

**阶段 0（提交意向持久化，承 §7.7 前置“接通休眠钩子”）**：
- **DDL 单一真源**：新增 `va/.../xa/impl/XaLogSchema.java`（package-private）——`xa_log` 扩为 `(xid bigint PK, create_time bigint, mycat_id int)`；旧库升级用幂等 try-alter 逐列补建（MySQL 无 `add column if not exists`，重复列错误仅记日志跳过、不阻断），沿用原 `create ... if not exists` 宽容风格；意向写入 `insert ... on duplicate key update`（同 xid 重写不报错、仅刷新计时/归属）；清除为 `where xid=?` 条件删（并发双删安全）。`XaLogImpl` 启动扫描就地拼 DDL 改调 `XaLogSchema.ensureSchema`；测试侧 `XaTestSuite.initSchema` 同步扩列。
- **持久化覆写**：`LocalXaMemoryRepositoryImpl` 覆写 `writeCommitLog`（先 `super.put` 保留内存快路径，再向各参与者后端 INSERT 意向；**写失败抛 `IllegalStateException` ⇒ commit 腿明确失败，宁可拒绝提交不可假装持久**），`cancelCommitLog`/`remove` 对应条件 DELETE（失败仅 WARN，交 recovery 最终一致）；per-target `schemaEnsuredTargets` 幂等建表、失败回退下次重试；新工厂 `createLocalXaMemoryRepository(supplier, mycatId)`（旧单参重载兼容 mycatId=0），生产装配 `MycatRouterConfigOps:652` 传 `serverConfig.getMycatId()`。
- **取证**：`XaCommitIntentPersistenceTest`（真后端 ds1，不 mock）**3/0F/0E**——writeCommitLog 后 xa_log 可读回该 xid 且 `create_time>0`、`mycat_id` 写入；cancel/remove 后意向行消失。

**阶段 1（常驻 reaper，按 §7.3② 纵深防御 + §7.7 三件套复用）**：
- **新增 `va/.../xa/impl/XaReaper.java`**：每轮 ① 幂等 `ensureSchema`；② 各后端 `XA RECOVER` 收集 prepared 分支 xid→targets；③ `select xid from mycat.xa_log` 收集已决意向；④ 决策：命中意向⇒各分支 `XA COMMIT`+删意向；未决⇒仅当跨轮观察到滞留超 grace 才 `XA ROLLBACK`，否则本轮跳过；⑤ 收敛后 `retainAll` 清理计时表。单轮异常全捕获记 WARN 不杀常驻任务；`unknown XID` 等报错视为幂等成功静默。
- **设计修正（实现时一手取证推翻 plan 书面假设）**：plan 原拟未决分支靠 `create_time` 判超期——但未决事务根本不入 xa_log、无 `create_time` 可取；实改为 xa_log 仅承担“已决→COMMIT”判据，未决 grace 由内存 `undecidedFirstSeen` 跨轮计时（进程重启窗口由启动一次性 `readXARecoveryLog` eager 扫描兜底已决提交）；`create_time`/`mycat_id` 列仍落地（归属/审计/后续能力）。
- **不抢方向盘的收口**：在线协调器正常驱动的 2PC 在 grace（默认 60s = 2×语句超时 30s）窗口内不被碰；与在线并发命中同一 xid 靠 MySQL 幂等报错吞掉 + 条件删兼容；reaper 实质只接管“协调器已失联（进程重启/异常退出）致 xid 长期悬挂”的场景。启动一次性扫描 eager 语义不变。
- **配置与接线**：`ServerConfig.xaReaperIntervalMillis=30_000`（<=0 禁用，零常驻线程零开销）、`xaReaperGraceMillis=60_000`；`MycatRouterConfigOps` 在既有 hasXA 门（`:740-743` `readXARecoveryLog()` 后）调 `startXaReaper()` 挂载；进程级单例（static + 锁，config 热重载替换旧实例防重复挂）；supplier 每轮从 `MetaClusterCurrent` 取当前 `MySQLManager`，避免热重载后抱住旧连接池。
- **取证**：`XaReaperSchedulingTest`（va 层注入调度器+假扫描任务，不连库）**2/0F/0E**（启用态周期触发≥2 次；<=0 禁用零排程）；`XaReaperConvergenceTest`（真 XA 后端 + 注入时钟驱动 grace，确定性活体）**2/0F/0E**——场景 A 已决提交：prepared 分支+xa_log 意向 ⇒ 一轮 sweep 后 `XA COMMIT`、分支内写入对其它连接可见（未丢已提交数据）、意向行清除；场景 B+C 未决：grace 内不动（仍 prepared，不抢方向盘），过 grace 后 `XA ROLLBACK`、数据未生效。

---

## §8 Phase 2 阶段2（commit 腿接 deadline）详细设计（2026-11-10 取证完成；组合案已拍板认可，实现排 I-DEBT-85 之后）

> 承 §7.4/§7.6/§7.8：阶段0（意向持久化）+阶段1（常驻 reaper）已落地，本节是 commit 腿本体设计。**未过拍板不动 commit 路径。**

### §8.1 当前接线事实（2026-11-10 实读 HEAD）
- **裸奔点**：`server/.../commands/ReceiverImpl.java:169-174` `commit()` 无任何 deadline（Phase 1 只包了 rollback/savepoint 四腿，`:154/:181/:191/:201`）。
- **Phase 1 范式**：`withTransactionLegDeadline`（`ReceiverImpl:451-460`）= 快照参与者（`getExistedTranscationConnections()`）→ `statementDeadlineMillis(targets)`（datasource `statementTimeout` 最小值+全局 30s 兜底）→ `StatementDeadline.withDeadline`，onTimeout=`forceCloseInFlight`（`:434-443` 逐个 `abandonConnection()`）。
- **StatementDeadline**（`hbt/.../vertx/StatementDeadline.java:75-124`）：CAS 先到先得；超时先跑 onTimeout 再以 3013（`ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT`）失败；src 先完成则取消 timer；超时后 src 迟到完成变 no-op。
- **commit 三路径**（`LocalXaSqlConnection.commit():54-118`）：
  - **空事务**（`:65-71`）：no-op，无悬挂面。
  - **单 DS 本地**（`:72-90`）：`update("commit;")`，失败→`rollback()`+报原始 cause（I-DEBT-72）。无 PREPARE/无 xa_log，但"已提交后冻结"=in-doubt（§7.6）。
  - **多 DS 混合**（`:91-114`）：`super.commitXa(beforeCommit=REPLACE INTO mycat.xa_log)` → 本地 `commit;` → 删 xa_log；**失败→`rollback()`**（`:106`）。
- **commitXa 状态机**（`BaseXaSqlConnection:271-351`）：logParticipants → XA END → XA PREPARE（败→logPrepare(false)+tryFail）→ **`log.logCommitBeforeXaCommit`（阶段0已持久化意向到各参与者，写失败抛 IllegalStateException 拒提交）** → beforeCommit → 逐连接 XA COMMIT；**XA COMMIT 败→logCommit(false)+`retryCommit()`（`:357-379` setTimer 无限重试换新连接）**；成功→inTranscation=false+logCommit(true)+clearConnections。

### §8.2 核心危险：超时 force-close 会触发"已决提交被回滚"（必须设计防线）
多 DS 路径下，意向持久化后后端冻结 → deadline 超时 force-close → 在途 `XA COMMIT` 的 update 失败 → **两条既有失败腿都会抢错方向盘**：
1. `LocalXaSqlConnection.commit` 的 transform（`:100-107`）→ `rollback()` → 对 **prepared 分支发 XA ROLLBACK**（prepared 分支可回滚！）+ **删 xa_log 意向**（`LocalXaSqlConnection.rollback():175-176`）⇒ **已持久化"提交决定"的事务被回滚= durability 破口**。
2. `commitXa` 的 onFailure → `retryCommit()` 无限 setTimer 循环 ⇒ 客户端已拿 3013 走人，后台还在无限抢新连接重试（资源搅动+与 reaper 双主）。

### §8.3 设计定案候选（推荐案，待拍板）
**R1 提交决定标记（commitDecided）**：`BaseXaSqlConnection` 加 volatile 标志，在 `logCommitBeforeXaCommit` **成功返回后**置位（意向已 durable ⇒ 2PC 决定已做出，只能向前收敛）。`LocalXaSqlConnection.commit` 的失败 transform 改为：**decided → 不 rollback**，清会话态（inTranscation/xid/map）后如实报错；未 decided → 维持现 rollback 路径（未 PREPARE/未持久化，回滚正确）。
**R2 retryCommit 让位**：onTimeout 置取消标志，`retryCommit` 每轮重试前检查——已取消则退出循环，收敛交 reaper（意向在 xa_log，reaper 命中意向⇒XA COMMIT，§7.8 已实证）。避免双主+无限 churn。reaper grace（默认 60s）内不碰 ⇒ in-doubt 窗口 ≤ grace+interval，有界。
**R3 客户端语义**：超时统一回 **3013**（与其他腿一致、会话可复用）；decided 场景的 message 追加 "commit outcome unknown; recovery in background"（诚实告知结果未知，不假装失败）。**不**选关连接（2013 风格）——会话复用一致性代价大，且 MySQL 客户端对 ERR 的处理已成熟。
**R4 单 DS 本地腿**：无 xa_log/无 prepared，force-close 后后端状态自权威（未提交则断连自动回滚，已提交则无可回滚）；现有 transform→rollback() 在已弃连接上快速失败→abandon→态清理，**无需新代码**，只需包 deadline。客户端同样 3013（outcome unknown 文案）。
**R5 包裹点**：`ReceiverImpl.commit()` 整腿包 commit 版 deadline helper（复用 Phase 1 范式，onTimeout=forceCloseInFlight(participants)+置 R1/R2 标志）。deadline 口径不变（datasource 最小值+30s 兜底，<=0 禁用）。
**R6 不动的边界**：非超时的既有失败语义（beforeCommit 失败→cancelCommitLog→rollback）保持不变；`writeCommitLog` 自身失败（未 durable）→ 拒提交+rollback 正确。空事务/begin 不接。

### §8.4 测试矩阵（TDD）
- **确定性单测（va，注入假连接+可控 timer）**：① 永不完成的 update ⇒ 客户端 Future N+ε 内 3013（RED=无 deadline 挂死，测试超时判红）；② decided 后超时 ⇒ 不发 XA ROLLBACK、xa_log 意向保留、retryCommit 退出；③ 未 decided 超时 ⇒ 走 rollback；④ 正常先完成 ⇒ timer 取消不泄漏；⑤ 单 DS 超时 ⇒ 态清理会话可复用。
- **活体取证（docker pause 配方，承 R1c）**：① 多 DS：PREPARE 后冻结 ⇒ 客户端 3013 有界 ⇒ 解冻后 reaper 在 grace+interval 内 XA COMMIT、数据可见、意向清除（**不丢已决提交**）；② PREPARE 前冻结 ⇒ 3013 + 分支回滚；③ 单 DS commit; 冻结 ⇒ 3013 + 无连接/线程泄漏（IOExecutor 计数回落、后端无孤儿会话）；④ 变异负控：去包裹 ⇒ ①必红。
- **硬门**：重打 fat jar 再验；va/server/example 全量护栏；账本对齐；commit 不 push（待拍板）。

### §8.5 拍板结果（2026-11-10 用户定）
R1+R2+R3 组合案**认可**（decided 后失败不回滚向前收敛；retryCommit 超时让位 reaper；客户端 3013+"commit outcome unknown" 文案）；范围=单 DS 本地 + 多 DS 2PC 一起接，空事务/begin 不接。实现节奏：先 I-DEBT-85，本腿随后独立轮次。

### §8.6 混合路径决定点归位修正（2026-11-11 取证 + 拍板，偏离 §8.3 R1 置位点）
实现前探针实证（真后端 ds1:3306/ds2:3307，`I84MixedPathHoleProbe` 用完即删）发现 §8.3 R1 在**混合路径**（ds1 本地协调 + ds2 XA 参与者）有 durability 破口：生产顺序为 ds2 PREPARE → 阶段0 提前写 ds2 意向（durable）→ ds1 REPLACE xa_log（本地 tx）→ ds2 XA COMMIT → **ds1 `commit;`（真实原子决定点，却排最后）**。R1 把 `commitDecided` 置在阶段0 写意向成功后，远早于真实决定点 ⇒ ds1 提交前被 force-close 时本地回滚、ds2 意向仍 durable → reaper 误提交 ds2 = 跨库不一致（PROBE-A `local=false participant=true` 坐实；PROBE-B 修正顺序 `local=false participant=false` 一致）。纯多 DS XA 路径无此问题（决定点即 writeCommitLog）。
**修正（Option A，2026-11-11 用户拍板认可）**：决定点归位——`commitXa` 重排为 prepare→beforeCommit→writeCommitLog→XA COMMIT；混合 beforeCommit 扩容为"REPLACE xa_log + `commit;`"并在其成功后置 `commitDecided`；纯 XA 仍在 writeCommitLog 成功后置位。残留窗口（ds1 已 commit、writeCommitLog 未跑）靠 reaper 兜底：reaper `sweep` union 所有后端 xa_log（含 ds1），ds1 协调者意向已随 commit durable ⇒ 命中 ⇒ XA COMMIT ds2 ⇒ 一致。详见 plan `docs/superpowers/plans/2026-11-11-i-debt-84-commit-leg-deadline.md` §A。执行方式：Native 内联。

### §8.7 阶段2 落地实证（2026-11-12，Native 内联，commit 待拍板）
按 plan Task1-6 落地 R1+R2+R3+R4+R5+R6 + §8.6 决定点归位，全量护栏绿 + 活体取证：
- **生产码**：`BaseXaSqlConnection`（commitDecided/commitTimedOut/rollingBack volatile + commitXa 重排 + retryCommit 超时让位 + 四标志 impl）；`LocalXaSqlConnection`（混合 beforeCommit=REPLACE xa_log+commit; 决定点归位 + decided/超时未决不 rollback 守卫 + rollback 重入终止守卫）；`SavepointSqlConnection`/`MycatXaTranscation`（四标志委托穿透装饰链）；`ReceiverImpl.commit`（withCommitLegDeadline 包裹 + force-close + markCommitTimedOut 前置 + R3 outcome-unknown 文案，码恒 3013）。
- **C1 修复（fresh review 发现，Critical）**：未决混合 commit 失败 transform 无条件 `rollback()` → `Base.rollback` 失败分支 `kill()` → `Local.kill` `rollback()` → **rollback→kill→rollback 无限递归**（实测 748 次 XA ROLLBACK，同步测试仅因 StackOverflow 停；真后端异步则 runaway CPU/日志/worker/会话泄漏 = I-DEBT-80 孤儿签名）。force-close 弃用全部参与者使 XA ROLLBACK 必失败 ⇒ 在功能主目标场景（冻结后端+多 DS+未决超时）激活。修复：(A) 未决 transform 守卫扩 `commitDecided || commitTimedOut`（超时未决跳过 rollback、弃用+复位+如实报错，收敛交 reaper）；(B) `rollingBack` 重入守卫切断递归（重入走 forceAbandonAndReset，先置 inTranscation=false 使 super.kill 不再 rollback）。TDD：CommitDecidedFlagTest 2 新测试 RED（725/764 次）→GREEN（≤2/0 次）。
- **护栏**：va 28/0/0、proxy 33/0/0、server 179/0/0、example XA 簇 164/0/0 Skipped12；重打 clean fat jar（245185844 bytes）；活体探针（单 DS commit 冻结）`commit bounded 3013 in 4108ms (deadline=3000ms)` + 解冻后代理恢复、后端无孤儿（m1-mysql-a/b Up）。
- **遗留 I2b（升级独立拍板，非本轮）**：force-close 链 `abandonConnection→DefaultConnection.close→JdbcConnectionManager.closeConnection:206 JdbcUtils.close=DruidPooledConnection.close()=recycle`，**非物理 socket abort**；`DruidDatasourceProvider:67` 后端 URL 原样、代理不注入 socketTimeout ⇒ 冻结后端下阻塞 worker 不被 recycle 解困（除非用户 URL 自带 socketTimeout）。此为 **Phase1 全腿共享 force-close 机制**（statement/rollback/savepoint/commit 同链），改物理 abort（`DruidPooledConnection.abort()`）+ worker 解困活体矩阵（§8.4 活体③完整 mandate：IOExecutor 计数回落、后端无孤儿、多轮冻结不耗尽池）属架构级、超 commit 腿范围，据「架构级须 spec→plan→拍板」升级用户拍板。本轮 commit 腿 deadline 的客户端有界 3013 + 决定点 durability + 递归终止已自洽交付；worker 物理解困是共享机制的纵深防御补强。
- **deferred minors**：M2 withCommitLegDeadline/withTransactionLegDeadline DRY；M3 forceCloseInFlight 死参 AtomicBoolean；M4 新文件尾换行。
