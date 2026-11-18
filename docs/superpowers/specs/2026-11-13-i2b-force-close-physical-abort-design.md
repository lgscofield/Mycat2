# I2b · force-close 物理 abort 升级设计（spec）

> 架构级专轮。流程：取证 → **spec（本文）** → 用户拍板修复方向 → writing-plans → executing-plans（TDD + 活体矩阵）。
> 触发：用户拍板「I2b 架构升级开启」（2026-11-13）。承 I-DEBT-84 §8.7 遗留 I2b。
> 状态：**待拍板**。本文只立设计与候选案，不落生产码、不 commit。

## §1 背景与问题

Phase1 语句级 deadline（I-DEBT-84）为 statement / rollback / savepoint / commit 四腿共享一套 **force-close** 机制：deadline 触发后，`ReceiverImpl.forceCloseInFlight` 弃用（abandon）所有在途后端连接，使客户端 Future 有界返回 3013，不再无限挂死。

但 §8.7 取证发现：该 force-close **只把 Druid 池化连接 recycle 归还池，并不物理关闭后端 socket**。当后端被冻结（docker pause / 网络黑洞 / 后端 hang）时，卡在 `socketRead0` 的 worker 线程**不会因 recycle 解困**：

- 客户端虽已有界 3013（I-DEBT-84 已交付），但后端 worker 线程仍阻塞；
- `IOExecutor` 线程计数不回落（线程泄漏签名）；
- 后端残留孤儿会话（I-DEBT-80 孤儿签名）；
- 多轮冻结后池被占满（activeCount 不释放）→ 池耗尽。

I-DEBT-84 commit 腿已自洽交付「客户端有界 + 决定点 durability + 递归终止」；**worker 物理解困是共享 force-close 机制的纵深防御补强**，属架构级，故升级独立专轮（本文）。

## §2 根因（bytecode 取证坐实，2026-11-13）

### §2.1 共享 force-close 链路（生产现状）

```
ReceiverImpl.forceCloseInFlight (server/.../commands/ReceiverImpl.java:438-447)
  └─ loop: newMycatConnection.abandonConnection()                         (:442)
       └─ JdbcDatasourcePoolImpl 匿名子类.abandonConnection()             (server/.../commands/JdbcDatasourcePoolImpl.java:100-112)
            └─ defaultConnection.close()                                  (:102)
                 └─ DefaultConnection.close()                            (datasource/.../jdbc/datasource/DefaultConnection.java:103-114)
                      └─ connectionManager.closeConnection(this)          (:109)
                           └─ JdbcConnectionManager.closeConnection()     (datasource/.../jdbc/datasource/JdbcConnectionManager.java:183-208)
                                ├─ counter.decrementAndGet()              (:185  Mycat 自有计数)
                                └─ JdbcUtils.close(connection.connection) (:206  = DruidPooledConnection.close())
```

`DefaultConnection.connection`（`final Connection`，:41）即 `DruidPooledConnection`；`getRawConnection()`（:140）暴露之。正常释放走 `close()`（:80-97，亦 `defaultConnection.close()`），force-close 走 `abandonConnection()`（:100-112）——**两者当前都落到同一个 recycle**。

### §2.2 druid 1.2.28 `DruidPooledConnection` 三态（javap -c 实证）

| 方法 | bytecode 行为 | socket | 池账（activeCount） |
|---|---|---|---|
| `close()` | → `recycle()` → `dataSource.recycle(this)`；置 holder/conn=null、closed=true | **不关**（归还池复用） | recycle 内部处理 |
| `recycle()` | `if(!abandoned) dataSource.recycle(this)`；null 化 wrapper 引用 | **不关** | 归还 |
| `abort(Executor)` | `this.conn.abort(executor)`（直接委托物理连接） | **物理 abort** | **不减**、不标记 wrapper closed、不丢 holder |

⇒ **现状 force-close = `close()` = recycle，socket 不关，冻结 worker 不解困。**
⇒ **`abort(Executor)` 能物理杀 socket，但单独调用会泄漏 druid 池账**（activeCount 不减）→ 多轮冻结耗尽池。

### §2.3 物理 abort 解困链（connector-j，javap -c 实证）

```
DruidPooledConnection.abort(executor)
  └─ com.mysql.cj.jdbc.ConnectionImpl.abort(executor)
       └─ executor.execute( () -> abortInternal() )          // 异步跑在传入 executor，非阻塞调用线程
            └─ ConnectionImpl.abortInternal()
                 └─ NativeSession.forceClose()                // 硬关 socket，无 quit()/COM_QUIT
```

`forceClose()` 直接关闭底层 socket（区别于 `quit()` 的优雅 COM_QUIT）。从**另一线程**（deadline timer）forceClose  socket，会使阻塞在 `socketRead0` 的 worker 抛 `SocketException` 而解困。这是 JDBC 4.1 `abort` 的硬杀语义，**正是 worker-解困所需**。

关键约束：`abort(executor)` 的 executor **不能是已阻塞的 worker 线程**（否则 cleanup 排队在阻塞线程后，永不执行）。

### §2.4 池账归位 API（druid，javap -c 实证）

`DruidAbstractDataSource.discardConnection(java.sql.Connection)`：
```
if (!conn.isClosed()) conn.close();   // 物理 close（注意：非 abort）
lock { activeCount--; discardCount++; emptySignal(...); }   // 归位 + 唤醒补池
```
⇒ `abort(executor)` 后再 `discardConnection(physicalConn)`：因 abort 已 forceClose，`isClosed()==true` **短路掉第二次 close**（避免在冻结后端上二次阻塞），只做 `activeCount--` + 补池信号。**abort 解困 + discard 归位** 组合自洽。

### §2.5 无 socketTimeout 兜底（生产现状）

`DruidDatasourceProvider.createDataSource`（datasource/.../jdbc/DruidDatasourceProvider.java:67）`datasource.setUrl(url)` **原样透传后端 URL，不注入 socketTimeout**。⇒ 除非用户 URL 自带 socketTimeout，阻塞 read 无自终止兜底，完全依赖 force-close 物理解困。

### §2.6 不受影响路径

vertx 后端路径（`VertxMycatConnectionPool`）force-close 已物理 kill socket，不在本升级范围。本升级仅针对 **JDBC/Druid 后端路径**。

## §3 修复缝与候选案

### §3.1 缝

- **入口缝**：`JdbcDatasourcePoolImpl.abandonConnection()`（:100-112）——语义本就是「弃用、不复用」，当前误用 recycle。改为物理 abort 对其所有调用者（force-close + removeAbandoned 超时）语义正确。
- **持有缝**：`DefaultConnection` 持有 `DruidPooledConnection`（`getRawConnection()`），是发起 abort + discard 的天然位置。
- **正常释放 `close()`（:80-97）保持 recycle 不变**——只升级 force-close/abandon 路径，不动正常归还。

### §3.2 候选 A（推荐）· abandon 路径物理 abort + 池账归位

新增「强制弃用」路径，与正常 recycle 分离：

1. `ConnectionManager` 接口 + `JdbcConnectionManager` 新增 `abortConnection(DefaultConnection)`：
   - `counter.decrementAndGet()`（Mycat 自有计数，对齐 closeConnection:185）；
   - 取 `DruidPooledConnection pc = (DruidPooledConnection) conn.getRawConnection()`；
   - `pc.abort(abortExecutor)`（硬杀 socket，解困 worker）；
   - `druidDataSource.discardConnection(pc.getConnection())`（isClosed 短路 + activeCount-- + 补池）。
2. `DefaultConnection` 新增 `abort()`：CAS `closeFlag`（与 `close()` 共享同一 flag，确保 worker 解困后的自身 close 与 timer abort **只有一方生效**）→ `connectionManager.abortConnection(this)`。
3. `JdbcDatasourcePoolImpl.abandonConnection()`（:102）改调 `defaultConnection.abort()`（替换 `close()`）。`close()`（:84）不变。
4. `abortExecutor`：独立于 worker 的 executor（候选：专用单线程 / `IOExecutor` 新线程 / direct `Runnable::run` 跑在 timer 线程）。forceClose 非阻塞，direct 亦可，但需活体确认 timer 线程不被拖住。**实现期定，活体坐实。**

- **优点**：精准命中 force-close 语义；正常 recycle 零改动；abort+discard 组合池账自洽；全腿共享缝一次修复四腿受益。
- **代价/风险**：见 §4（race、池账、孤儿会话、全腿 blast、executor 选择）。

### §3.3 候选 B · socketTimeout 双保险（纵深防御）

`DruidDatasourceProvider:67` 注入 socketTimeout（URL 无显式值时补默认），使**任何**阻塞 read 在 socketTimeout 后自终止，独立于 deadline/abort。

- **优点**：兜底覆盖 abort 未触发的阻塞（如非 force-close 路径的慢查询挂死）；与 A 正交。
- **代价/风险**：改变**所有**查询行为——合法慢查询 > socketTimeout 会被误杀；默认值需权衡（过激误杀、过 lax 无效）；须可配 +  sane 默认。**属行为面变更，需单独权衡默认值。**

### §3.4 候选 C · A + B 组合

A 主解困 + B 兜底。最彻底，但 blast 最大（A 的全腿 force-close 变更 + B 的全查询 socketTimeout 变更）。

### §3.5 推荐

**候选 A 为主**（精准、正常路径零改动、池账自洽）。B 作为可选双保险，**默认值与是否纳入本轮交用户拍板**；若纳入，建议独立可配 + 保守默认，且与 A 分任务落地以便隔离 blast。

## §4 风险与待证

| # | 风险 | 说明 | 缓解/待证 |
|---|---|---|---|
| R1 | **池账泄漏** | abort 不减 activeCount；漏 discard → 多轮冻结耗尽池 | abort 后必 discardConnection；活体多轮冻结验 activeCount 归位、池可复用 |
| R2 | **worker/timer race** | worker 解困后自身 close()/recycle 与 timer abort+discard 并发 | 共享 closeFlag CAS 单方生效；活体多轮验无双重释放/无 IllegalStateException |
| R3 | **后端孤儿会话** | abort 硬杀 socket，后端需检测断连并清理会话 | 活体验后端 `processlist` 无孤儿（承 I-DEBT-80 签名） |
| R4 | **全腿 blast** | statement/rollback/savepoint/commit 共享 abandon 缝 | 四腿全量护栏 + 活体；正常 close() 不动以缩 blast |
| R5 | **executor 选择** | abort cleanup 跑在传入 executor；若选阻塞 worker 则永不执行 | 用独立/direct executor；活体验 timer 线程不被拖住、worker 计数回落 |
| R6 | **discard 二次 close 阻塞** | discardConnection 内含 close()；若 isClosed 未短路会在冻结后端二次阻塞 | abort 先 forceClose 使 isClosed=true 短路；活体坐实短路生效 |
| R7 | **非 Druid 连接** | getRawConnection 未必总是 DruidPooledConnection（测试假连接/其他池） | instanceof 守卫 + 回退；单测注入假连接验回退不崩 |

## §5 活体矩阵 mandate（承 I-DEBT-84 §8.4 活体③，docker pause 配方）

**硬门：改生产必重打 fat jar 再验；活体取证须真后端（m1-mysql-a:3306 / m1-mysql-b:3307）。**

1. **单 DS commit 冻结**：PREPARE/commit 期冻结后端 ⇒ 客户端 3013 有界 **+ IOExecutor 线程计数回落 + 后端无孤儿会话**（worker 真解困，非仅客户端有界）。
2. **多轮冻结不耗尽池**：连续 N 轮冻结-解冻 ⇒ activeCount 每轮归位、池可复用、第 N+1 轮正常取连接（R1）。
3. **解冻后恢复**：解冻后代理恢复服务、后端会话清理、无残留阻塞 worker。
4. **race 负控**：高并发下 worker 解困与 timer abort 并发 ⇒ 无双重释放/无异常（R2）。
5. **变异负控**：去掉 abort（退回 recycle）⇒ 活体①的「IOExecutor 计数回落」**必红**（证明 abort 是解困真因，非他因）。
6. **确定性单测**（注入假连接 + 可控 executor）：① abandon 走 abort 而非 recycle；② abort 后 discard 被调（池账归位）；③ closeFlag CAS 单方生效；④ 非 Druid 连接回退不崩（R7）。

## §6 拍板项（待用户定，本文不预设落地）

1. **修复方向**：A（推荐）/ B / C？
2. **范围**：全腿共享 abandon 缝（推荐，四腿一次受益）vs 仅 commit 腿先行？
3. **socketTimeout（B）**：是否纳入本轮？若纳入，默认值与可配策略？
4. **abortExecutor**：专用单线程 / IOExecutor 新线程 / direct（timer 线程）——倾向实现期活体定，是否认可？
5. **执行方式**：writing-plans → executing-plans inline（承前轮）？

---

### 取证附录（可复现，本地 javap，无 URL）

- fat jar：`server/target/server-1.22-release-jar-with-dependencies.jar`
- `javap -c -p com.alibaba.druid.pool.DruidPooledConnection`：`abort` → `conn.abort(executor)`；`close`/`recycle` → `dataSource.recycle`。
- `javap -c -p com.alibaba.druid.pool.DruidDataSource`：`discardConnection(Connection)` → `if(!isClosed())close()` + `activeCount--` + `emptySignal`。
- `javap -c -p com.mysql.cj.jdbc.ConnectionImpl`：`abort` → `executor.execute(abortInternal)`；`abortInternal` → `NativeSession.forceClose()`。
- 生产缝：`JdbcDatasourcePoolImpl.java:100-112`、`DefaultConnection.java:41/103-114/140`、`JdbcConnectionManager.java:183-208`、`DruidDatasourceProvider.java:67`、`ReceiverImpl.java:438-447`。