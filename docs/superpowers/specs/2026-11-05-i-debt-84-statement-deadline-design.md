# I-DEBT-84 · Mycat 侧语句级超时 deadline（D-B'，兜住 R1c 实证的"等后端响应无界挂"）—— Spec 先行

> **状态**：🔴 Spec 先行（未实现、未取证）。承 I-DEBT-83 §7 活体取证判决 + 用户拍板"立 D-B' 新票"。
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

## §6 待用户拍板（实现前）

本票改动落**语句执行/响应主路径**，风险面比 I-DEBT-81/82 大。请定：
- **(a) deadline 值与作用域**：全局统一默认 N（建议先给保守值，如 30s，与会话/datasource 可覆盖）？还是默认关、仅显式配了才生效（保守但没兜住默认配置）？
- **(b) 资源回收深度**：是否接受"deadline + 强制 close socket"两件一起做（完整但复杂）；或先只做界客户端、回收依赖 D-A1 socketTimeout 配置（简单但把泄漏风险交运维）。
- **(c) 交付节奏**：先出 plan（含具体 file:line 改动点 + 错误码选择 + 测试矩阵）再动码；还是就 §4 插入点先拍一个方向我再细化。

（承 I-DEBT-83：G1 的悬挂已被 R1c 坐实在语句腿；本票是那枚悬挂的正解，非 I-DEBT-83 原位的 close 兜底。）
