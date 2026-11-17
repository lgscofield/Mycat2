# I-PR-3 虚拟线程 spike — 静态 pinning 清单（活体代码坐实，非引旧 plan）

日期：2026-11-17 · 探针性质：**零产品码改动** · HEAD=`1c6a5313a`

## 0. 计数口径纠正（旧 plan 虚高）

旧 `docs/superpowers/plans/2026-09-28-phase2-virtual-threads.md` 声称「55 文件 / 146 处 synchronized」。
本轮按**当前代码**重数，两处必须剔除：

| 剔除项 | 理由（已坐实） |
|---|---|
| 仓内 `vertx/` 模块（含 `io/vertx/core/impl/future/FutureImpl.java` 10 处） | **不在 root pom `<modules>`**，且 `io.mycat:vertx` 仅出现在 dependencyManagement（pom.xml:293），**无任何模块依赖它** ⇒ 休眠死码。运行时用的是 Maven `io.vertx:vertx-core:4.5.14` |
| 注释行 / 整类注释死码 | `FutureNewMycatConnectionImpl` 5 处全注释、`NewVertxConnectionImpl` 3 处全注释、`PlanCacheImpl` 2 处全注释、`MycatWorkerProcessor` 整文件注释、`FileRepositoryImpl`/`XaRepository` 整类注释 |

**修正后活体计数：140 处 / 61 文件**（仅 reactor 模块 `*/src/main`，剔除注释行）。

## 1. 判定准则

`synchronized` 本身**不等于** pinning 风险。JDK21 下 VT 在 monitor 内**阻塞**才 pin carrier。
故分级准则 = **临界区内是否含阻塞 IO（JDBC / socket / 磁盘 / 池借用等待）**。

## 2. P0 — 临界区内含阻塞 JDBC（真 pinning 风险，共 4 文件 / 22 处）

### 2.1 `common/.../newquery/NewMycatConnectionImpl.java` — 6 处【主查询路径，最高优先】

| 行 | 形态 | 临界区内的阻塞操作 |
|---|---|---|
| :109 | `public synchronized void prepareQuery(sql, params, collector)` | `statement.executeQuery(sql)` + **`while(resultSet.next())` 全流式循环** + `collector.onRow` |
| :199 | `synchronized (NewMycatConnectionImpl.this)` | 结果集回调内 |
| :298 | `synchronized (NewMycatConnectionImpl.this)` | 结果集回调内 |
| :444 | `public synchronized Future<List<Object>> call(String sql)` | JDBC call |
| :496 | `public synchronized Future<SqlResult> insert(...)` | JDBC insert |
| :595 | `public synchronized Future<SqlResult> update(...)` | JDBC update |

**后果**：VT 下**每条流式查询从 executeQuery 到流结束全程 pin 住一个 carrier**。
carrier 池默认 = `availableProcessors`，故并发流式查询数被硬限在核数量级 ⇒ VT 在主查询路径**不产生并发收益**，
除非先把这 6 处换成 `ReentrantLock`（语义等价：本就是 per-connection 串行化，JDBC 连接非线程安全）。

### 2.2 `hbt/.../calcite/spm/DbPlanManagerPersistorImpl.java` — 13 处【SPM 持久化】

`checkStore():39` / `loadBaseline():71` / `deleteBaseline():119` / `listPlan():136` 等**方法级 synchronized**
内含 `manager.getConnection(prototype)`（池借用）+ `JdbcUtils.execute/executeQuery/executeUpdate`
（`CREATE DATABASE IF NOT EXISTS mycat`、`CREATE TABLE spm_baseline`、`SELECT * FROM mycat.spm_baseline`、`DELETE`）。
单文件 synchronized 最多，且全是阻塞 JDBC。

### 2.3 `plug/.../sequence/DefaultPrototypeGenerator.java` — 3 处【序列号生成】

`:108 public synchronized Value getNumber()` 内含
`connectionManager.getConnection(targetName)` → `prepareStatement(nextSql)` → `executeQuery()` → `resultSet.next()`。
另 :69 / :92 为短临界区（value 赋值），P1 性质。

### 2.4 ~~`datasource/.../transactionsession/TransactionSessionTemplate.java` — 2 处~~ ❌ **本轮自我纠正：整个类是死码，不属 P0**

> **原判定（已撤回）**：曾把 `:131 close()` 与 `:200 synchronized(jdbcConnectionManager)` 列为 P0，
> 并称 `:200`「全局锁内含阻塞池借用 ⇒ I-PR-1 加界后的争用放大点，spec 必须一并裁定」。
> **该判定错误**，错在只 grep 了 `synchronized` 的形状、**没验类的活性**——正是本轮批评旧 plan 的同一个毛病。

**坐实过程（三步，全仓含测试）**：

| 证据 | 结果 |
|---|---|
| `TransactionSessionTemplate.java:19` | `public **abstract** class` ⇒ 必须有子类才能实例化 |
| 全仓 `extends TransactionSessionTemplate` | **唯一命中** = `LocalTransactionSession.java:19`，且该行**以 `//` 开头** |
| `LocalTransactionSession.java` 非注释行数 | **0**（整文件注释） |
| 全仓 `TransactionSessionTemplate` 引用 | 除自身定义外**零命中**（含测试） |
| `DruidDatasourceProvider.java:204-207`（会 `new LocalTransactionSession(context)` 的 `createSession`） | **四行全注释** |

⇒ **整条 `TransactionSessionTemplate` / `LocalTransactionSession` / `createSession` 链死透，不在任何活路径上。**

**活体的 `TransactionSession` 实现是谁（已坐实）**：
`proxy/src/main/java/io/mycat/runtime/MycatXaTranscation.java:17`
`public class MycatXaTranscation implements XaSqlConnection, TransactionSession`。
**该文件 `synchronized` 命中数 = 0**（`.wait(`/`.notify` 亦 0）⇒ **活体事务会话根本不是 pinning 风险**。
其 `:80 getConnection(targetName)` 直接委托 `connection.getConnection(targetName)`，**无锁包裹**。

**对 I-PR-1 的影响（重要）**：
- 原「spec 必须裁定 `:200` 全局锁」**这条要求撤销**——没有这个活体靶。
- 原「释放腿会被全局监视器挡在借用等待之后」的自锁死推演**失去该论据**；
  自锁死风险仍可由「有界线程全部卡在饱和池借用」这一**更一般**的机制成立，但**本轮无活体锁证据支撑**，
  须降级为**待证推演**，不得作为裁定的硬依据。
- **P0 计数由 4 文件 / 22 处 修正为 3 文件 / 22 处**（见 §2.5）。

### 2.5 P0 修正后清单（3 文件 / 22 处）

| 文件 | 处数 | 活性坐实 |
|---|---|---|
| `common/.../newquery/NewMycatConnectionImpl.java` | **6** | 主查询路径，`ThreadMycatConnectionImplWrapper` 直接委托 ⇒ **活体，最高优先** |
| `hbt/.../calcite/spm/DbPlanManagerPersistorImpl.java` | **13** | `server/.../config/MycatRouterConfigOps.java:602` 与 `:684` **两处 `new DbPlanManagerPersistorImpl()`** ⇒ **活体** |
| `plug/.../sequence/DefaultPrototypeGenerator.java` | **3** | **配置门控活体**：全仓零静态消费者，但 `SequenceGenerator.java:50-58 create(clazz,args)` 用 `Class.forName(clazz)` + `getDeclaredConstructor(**Map.class**)` + `newInstance(args)` 反射装载，签名与该类 `:52 DefaultPrototypeGenerator(Map<String,Object> config)` **完全吻合**；`SequenceGenerator` 由 `MycatRouterConfigOps:49` import ⇒ **序列规则配置指名该类时即活**。**零静态消费者是反射的预期现象，不得据此判死** |
| ~~`TransactionSessionTemplate`~~ | ~~2~~ | ❌ **死码，剔除**（§2.4） |

**合计 6 + 13 + 3 = 22 处 / 3 文件。**（处数巧合不变，文件数由 4 → 3。）

## 3. P1 — 短临界区 / 非阻塞（Future 赋值、map 操作、内存态）

| 文件 | 处数 | 定性依据 |
|---|---|---|
| `server/.../mysqlclient/VertxMycatConnectionPool.java` | 7 | :175/:208/:263 仅包 `queryCloseFuture` 赋值与 `isComplete` 判断=非阻塞。**且属 native（vertx-mysql-client）路径**，经 `MycatNativeDatasourcePool:44` 进入，仅 `-Dserver=native` 激活；默认生产=JDBC/Druid |
| `server/.../mysqlclient/VertxPoolConnectionImpl.java` | 5 | 同上，native 路径 |
| `server/.../mysqlclient/VertxConnection.java` | 4 | 同上 |
| `va/.../xa/impl/XaLogImpl.java` | 6 | `synchronized(xaRepository)` 包 `put/get/replace`。**活体 Repository 实现 = `MemoryRepositoryImpl`/`LocalXaMemoryRepositoryImpl`/`KvRepositoryImpl`；`FileRepositoryImpl` 与 `XaRepository` 整类注释死码** ⇒ 非阻塞磁盘 IO（旧 plan 将其列高风险系误判） |
| `hbt/.../AsyncMycatDataContextImpl.java` | 6 | 上下文态 |
| `server/.../commands/SqlResultSetService.java` | 4 | `clear/dropByName/addIfNotPresent/snapshot` 包 cache 与 map 操作，无 IO |
| `server/.../commands/ReceiverImpl.java` | 2 | 语句态 |
| `server/.../mycatmysql/MycatVertxMySQLHandler.java` | 2 |  handler 态 |
| `va/.../xa/impl/XaReaper.java` | 2 | `start()/stop()` 生命周期 |
| `va/.../xa/impl/BaseXaSqlConnection.java` | 1 | `Collections.synchronizedMap` **包装器**（非 synchronized 块），临界区=单次 map 操作 |
| `server/.../commands/JdbcDatasourcePoolImpl.java` | 1 | `Collections.synchronizedList` 包装器 |

## 4. P2 — 工具 / 缓存 / 配置 / 第三方 fork（低 pinning 风险）

`ReplicaDataSourceSelector` 8 · `CachingSha2AuthCache` 4 · `ApplicationContext` 4 · `ExpiryLRUMap` 3 ·
`MemPlanCache` 3 · `ReactorThreadManager` 3 · `LazyTransformCollection` 2 · `PhysicsInstanceImpl` 2 ·
`MySQLDatasourcePool` 2 · `VertxExecuter` 2 · `MySQLCheckHandler` 2 · `SequenceFileGenerator` 2 ·
`calcite-core` 约 13（`Util` 5 / `ReflectUtil` / `ControlFlowException` / `SqlParserImpl` / `SqlParseException` /
`JethroDataSqlDialect` / `RexSlot` / `JaninoRelMetadataProvider` / `CalcitePrepare` / `CalciteMetaImpl` / `CalciteConnectionImpl`）·
其余单处文件若干。

**休眠特例**：`datasource/src/main/java/com/mysql/cj/protocol/x/SyncMessageReader.java` 5 处 —
系仓内 fork 的 Connector/J **X Protocol** 类，全仓除自身外**零引用**（Mycat 走 classic protocol）⇒ 不在活路径。

## 5. 第三方阻塞源（不可改，只能实测）

| 组件 | 版本 | VT 相关 | **round 3 实测结论** |
|---|---|---|---|
| Druid 连接池 | **1.2.28** | 池借用/归还在 `DruidDataSource` 内部锁上等待；须实测是否 pin（`getConnection` 饱和等待是 P0 路径的必经点） | ✅ **不 pin**。`pool=4, C=512` 深度饱和下 `borrowP95=6159ms`（真等了 6 秒），`pinnedFrames=0`。内部 `ReentrantLock`+`Condition.await`，VT 可正常 unmount |
| mysql-connector-j | **9.3.0** | 9.x 宣称支持 VT；须实测 classic protocol 流式读（`setFetchSize(Integer.MIN_VALUE)`）是否 pin | ✅ **不 pin**。零 Mycat 锁路径下 vt 三跑全部 `pinnedFrames=0` |
| vertx-core | **4.5.14**（Maven，非仓内 fork） | Future 完成链 | 未单独隔离实测（探针未走 vertx Future 链）；**属残留未测项** |

## 6. 静态半区结论（待运行时 A/B 数据合并成最终裁定）

1. **VT 不是 drop-in 替换**。把 `IOExecutor.DEFAULT` 换成 `newVirtualThreadPerTaskExecutor()` 而不动锁，
   主查询路径会因 `NewMycatConnectionImpl` 6 处方法级 synchronized **全程 pin carrier**，
   并发被压到核数量级 ⇒ 大概率**劣于**今天的 cached 平台池。
2. **前置条件明确且可数**：P0 共 **3 文件 / 22 处**（不是旧 plan 的 55 文件 / 146 处；也不是本轮初判的 4 文件——
   `TransactionSessionTemplate` 经活性复核为死码，已剔除，见 §2.4/§2.5）。
   其中 `NewMycatConnectionImpl` 6 处是收益关键（主查询路径），`DbPlanManagerPersistorImpl` 13 处属 SPM 持久化，
   `DefaultPrototypeGenerator` 3 处属配置门控的序列生成。
3. **P1/P2 共约 118 处不构成 VT 阻塞**，旧 plan 的「全仓零 synchronized」目标属过度工程，应否决。
4. `synchronized → ReentrantLock` 语义等价性在三个活体 P0 文件均成立——**已逐文件 grep 坐实：`.wait(` / `.notify(` / `.notifyAll(` 命中数 = 0**，
   无 monitor-condition 依赖，故机械换锁不破坏语义（不需要 `Condition` 迁移）。

## 7. 锁拓扑细节（换锁前必须遵守，已坐实）

| 文件 | 监视器拓扑 | 换锁要求 |
|---|---|---|
| `NewMycatConnectionImpl` | :109/:444/:496/:595 方法级 = `this`；:199/:298 内层块显式 `synchronized(NewMycatConnectionImpl.this)` ⇒ **同一监视器、可重入嵌套** | **单个** per-instance `ReentrantLock` 即可覆盖全 6 处（重入性天然保持）。拆成多把锁会破坏嵌套语义 |
| `DefaultPrototypeGenerator` | :69/:92 `synchronized(this)` 与 :108 方法级 ⇒ 同一监视器 | 单个 per-instance `ReentrantLock` |
| `DbPlanManagerPersistorImpl` | 13 处全为方法级 = `this` | 单个 per-instance `ReentrantLock`；须核是否有嵌套调用（`loadBaseline`→`saveBaseline` 之类）⇒ ReentrantLock 可重入，安全 |
| ~~`TransactionSessionTemplate`~~ | ❌ **死码，本行撤销**（原写「两把不同监视器、:200 须与 manager 同生命周期共享」） | 无需换锁。**活体 `TransactionSession` = `proxy/.../MycatXaTranscation`，其 synchronized 命中数 = 0** ⇒ 事务会话层无换锁工作量。详见 §2.4 |

> ⚠ **原「`TransactionSessionTemplate:200` 全局锁是 I-PR-1 争用放大点、spec 须一并裁定」的告警已撤销**——
> 该靶不存在（死码）。**教训记录**：本轮初判重犯了旧 plan 的同一个错误（只看锁形状、不验类活性）。
> 凡列 P0 必须附**活性证据**（实例化点 / 反射装载点 / 调用链），否则不得进入裁定依据。
> §2.5 表已为三个活体 P0 各附活性证据。

> **拓扑复核（2026-11-17 二次 grep 坐实）**：`NewMycatConnectionImpl` = :109/:444/:496/:595 方法级 +
> :199/:298 显式 `synchronized (NewMycatConnectionImpl.this)` ⇒ 同一监视器、可重入嵌套，**单锁覆盖 6 处**；
> `DbPlanManagerPersistorImpl` = 13；`DefaultPrototypeGenerator` = :69/:92 `synchronized(this)` + :108 方法级 ⇒ 单锁覆盖 3 处。
> 三文件 `.wait(`/`.notify` 命中均为 **0**。合计 **22 处**。

> 运行时 A/B 数据见 **`ci/vtspike/`**（`summary{,2,2b,2c,3}.tsv` / `logs{,2,2b,2c,3}/` / `spike{,2,2b,2c,3}.log`，
> 含探针 `VtSpike.java` / `DruidPinProbe.java` 与跑批脚本，共 53 跑）；
> 最终裁定见 `docs/superpowers/specs/2026-11-17-i-pr-3-vt-spike-report.md`（本清单同目录归档）。

## 8. VT 的**非性能**回归面（旧 plan 完全未提，本轮新坐实）

pinning 只是性能问题。下面这条是**功能静默失效**，比性能更硬：

`common/src/main/java/io/mycat/ThreadUtil.java:10-29 findAllThreads()` 的实现是
`Thread.currentThread().getThreadGroup()` 上溯到根组，再 **`topGroup.enumerate(slackList)`**。

**虚拟线程不属于任何 ThreadGroup**（JDK21 起 VT 的 `getThreadGroup()` 返回一个空的占位组，
`ThreadGroup.enumerate` 枚举不到 VT）。故一旦 IO 腿换成 VT：

| 消费点 | 现状 | VT 下后果 |
|---|---|---|
| `IOExecutor.java:29-30 findAllThreads()` | 返回全部平台线程 | **只剩 carrier 线程**，所有 IO 工作线程消失 |
| `IOExecutor.java:35 interrupt(pid)` / `:49 kill(pid)` | 按 `thread.getId()` 匹配后 interrupt/stop | 匹配不到 ⇒ 返回 false |
| `server/.../sqlhandler/dql/HintHandler.java:160` `killThread` hint | `setAffectedRows(kill(pid)?1:0)` + `sendOk()` | **affectedRows 恒 0，且仍回 OK** ⇒ 客户端以为成功，实际什么都没杀 |
| `HintHandler.java:167` `interruptThread` hint | 同上 | 同样静默失效 |
| `HintHandler.java:177` `showThreadInfo` hint | 输出 ID/NAME/STATE/STACKTRACE 结果集 | **结果集只剩 carrier**，运维看不到任何正在跑的查询 |

⇒ **VT 迁移必须同时改 `ThreadUtil.findAllThreads()` 的线程发现机制**（VT 需自行登记，
例如在 `executeBlocking` 提交时把 `Thread.currentThread()` 记入一个并发集合，完成时移除），
否则 `killThread`/`interruptThread`/`showThreadInfo` 三个 hint 静默降级为 no-op。
这是 I-PR-1 若选 VT 形态时的**强制附带工作量**，不是可选项。

**顺带观察（不属本轮范围，仅记录）**：`HintHandler.java:164` 在 `interruptThread` 分支里先执行
`Thread.currentThread().interrupt()` —— 打断的是**调用方线程**而非目标线程，随后 :167 才按 pid 打断目标。
这行自打断疑似遗留缺陷（会污染调用线程的 interrupt 标志位），与 VT 无关，建议另立小账。

## 9. 本轮实测覆盖缺口（诚实边界）—— **已由 round 3 补齐**

`VtSpike` 把 `poolSize` 条连接**一次性全借出**做成 lane，任务只从 `LinkedBlockingQueue<Lane>` 取用
⇒ **Druid 饱和借用等待（`ds.getConnection()` 在 maxActive 用尽时阻塞）从未被跑到**。
而这正是 Mycat 每条查询的必经点，且 `TransactionSessionTemplate:200` / `DefaultPrototypeGenerator:108`
是**在锁内借用**。故 §5 表中「Druid 1.2.28 是否 pin」在 round 1/2 **仍属未测**，
不能靠"栈里没看到第三方 monitor"就宣称第三方干净——那只证明"在已借出的连接上跑流式查询"不 pin，
不证明"饱和借用"不 pin。

**✅ 已补**：`DruidPinProbe.java` + `run-spike3.sh`（C≫pool、路径内无任何 Mycat 锁、单独计量 `borrowP50/P95`）
6 跑实测 ⇒ **Druid 与 Connector/J 均零 pinning**，含 `pool=4` 深度饱和（`borrowP95=6159ms`）。详见 `REPORT.md` §4。

**仍未测（残留边界，不得据此宣称完备）**：
- vertx-core 4.5.14 的 Future 完成链是否 pin（探针未走该链）
- 真实 Mycat 全代理路径（前端协议 / 路由 / calcite / 跨片归并）、多后端、TLS、prepared 协议、大结果集、长事务
- `DbPlanManagerPersistorImpl` 的 DDL 型 JDBC（`CREATE DATABASE`/`CREATE TABLE`）与 `DefaultPrototypeGenerator`
  的 `SELECT ... FOR UPDATE` 型序列语句——两者形状与探针的纯 SELECT 流式读不同