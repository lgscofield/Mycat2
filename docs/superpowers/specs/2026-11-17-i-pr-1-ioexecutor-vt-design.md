# I-PR-1 IOExecutor 无界池 + 背压 + 名实相符 —— 设计（形态＝虚拟线程·一步到位）

**票号**：I-PR-1（M5 生产就绪，主线外【D】层）
**日期**：2026-11-17
**形态拍板**：用户选择 **「一步到位上 VT」**——加界 + 虚拟线程 + P0 22 处换锁 + 线程发现重写，全部进本轮。
**前置证据**：`docs/superpowers/specs/2026-11-17-i-pr-3-vt-spike-report.md`（53 跑实测）、
`docs/superpowers/specs/2026-11-17-i-pr-3-pinning-inventory.md`（静态清单 + 活体订正）
**状态**：spec 草成，**待拍板**。架构级变更，未拍板前不动生产码。

---

## 0. 本 spec 与前票裁定的关系（必须先读）

I-PR-3 收口时写入两板与报告 §8.1 的裁定是 **「先加界，VT 另立后续票」**（我的建议）。
**用户已明确推翻该建议，选择「一步到位上 VT」。** 本 spec 以用户拍板为准。

报告 §8.1 与两板的该句裁定属**已被取代的历史记录**，须回写标注（见 §12），
否则后来者读板会拿到与当前方向相反的结论。

I-PR-3 的实测数据**不支持按性能选形态**（吞吐在 interleaved 重复下不可分辨，线程治理等价）。
选 VT 的理由是**结构性的**，不是性能：

| 维度 | 平台线程 + TPE | VT + 准入闸 |
|---|---|---|
| 线程数与并发度 | 强耦合（cached 池 C2048 → 1595 线程） | **解耦**（实测 9 跑恒 27 线程） |
| core/queue 地雷 | **存在**（实测 core1/max1024/q65535 → peakInFlight=**1**） | **构造上消失**（无队列、无 core） |
| 加界手段 | 池参数（语义已被证明是装饰性的） | Semaphore（语义直白：同时在飞的语句数） |
| 主要风险 | 线程爆炸 / 静默串行化 | **pinning**（JDK21 实测不补偿 carrier） |

⇒ VT 把「地雷」换成「pinning」。**pinning 是可断言、可测尽的**（`<== monitors` 计数），
而 TPE 的静默串行化只能靠 peakInFlight 反推。这是本轮承担 22 处换锁成本的正当性来源。

**JDK21 实测硬约束**：`ForkJoinPool` **不为 pinning 补偿 carrier**（线程数恒 27，从未向 maxPoolSize=256 增长）。
⇒ **任何一处漏改的阻塞 `synchronized` 都会把并发度打回 carrier 数**。残留覆盖率不是「优化项」，是**正确性项**。

---

## 1. 现状事实（全部实读核对，含三处自我订正）

### 1.1 真靶：`common/src/main/java/io/mycat/IOExecutor.java`（143 行）

```java
public interface IOExecutor {
    <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler);   // L25
    long count();                                                             // L27
    static List<Thread> findAllThreads() { return Arrays.asList(ThreadUtil.findAllThreads()); }  // L29-32
    static boolean interrupt(long pid) { ... thread.interrupt() ... }         // L34-46
    static boolean kill(long pid)      { ... thread.interrupt() ... }         // L48-60
    IOExecutor DEFAULT = new IOExecutor() {                                   // L62
        final ExecutorService executorService = Executors.newCachedThreadPool();   // L63 ← 无界
        private final AtomicLong count = new AtomicLong();                    // L64
        // executeBlocking: count.getAndIncrement() → execute(...) → finally count.decrementAndGet()
        //                                                          ↑ L78-80 BUG：提交即减，count() 恒 ≈0
    };
    static IOExecutor fromVertx(Vertx vertx) { ... }                          // L89-141
}
```

四处名实相符缺陷：

1. **无界**：`newCachedThreadPool()`（L63 / L91），`maxPoolSize=Integer.MAX_VALUE`、`SynchronousQueue`。
   实测 C2048 → **1595 平台线程**。没有任何背压。
2. **`count()` 恒 ≈0**：`finally { count.decrementAndGet(); }` 在**提交后立刻**执行，
   不等任务完成。DEFAULT（L78-80）与 fromVertx（L129-131）**同 bug**。
   ⇒ `count()` 名为「在飞任务数」，实为「提交瞬时重叠数」，监控读数无意义。
3. **`kill(pid)` == `interrupt(pid)`**：L48-60 与 L34-46 实现逐字相同，都只 `thread.interrupt()`。
   名为 kill 实为 interrupt。（本轮**不改行为**，见 §11 挂账。）
4. **线程发现看不见 VT**：`ThreadUtil.findAllThreads()`（`common/.../ThreadUtil.java:10-29`）
   走 `ThreadGroup.enumerate()`（L24）。**VT 不属于任何 ThreadGroup** ⇒ enumerate 返回不了 VT。
   ⇒ 换 VT 后 `show @@thread` / `killThread` / `interruptThread` **静默失效**（返回 OK 但什么都没做）。

**消费者清单（grep 核实）**：
- `DEFAULT`：生产**唯一**接线点 `server/.../MycatCore.java:135`；
  测试 `ReceiverStatementDeadlineForceCloseTest:89`、`JdbcDatasourcePoolAbandonTest:67`、`SpmTest:46`；
  `DatasourcePoolTest:57` 已注释。
- `fromVertx`：**零调用者**。用户拍板 **保留并一并加界**（不删）。
- `DEFAULT` 是 `static final` ⇒ **类加载即建池，早于任何配置存在**。这是「配置永远到不了 IO 路径」的根因。

### 1.2 配置名实相符普查（grep 核实，逐项）

| 旋钮 | 活体消费者 | 真相 |
|---|---|---|
| `getWorkerPool()` | **1 处**：`MycatCore:114` | 只用于喂 vertx，见下 |
| `getMaxPoolSize()` | `MycatCore:125` | → `vertxOptions.setWorkerPoolSize()`，是 **vertx 自己的池**，不是 IOExecutor |
| `getTaskTimeout()` | `MycatCore:126` | → `setMaxWorkerExecuteTime()`，同上 |
| `getTimeUnit()` | `MycatCore:127` | 同上 |
| `getCorePoolSize()` | **0** | 仅出现在**整文件注释掉**的 `common/.../MycatWorkerProcessor.java:32` |
| `getKeepAliveTime()` | **0** | 同上 `:34` |
| `getMaxPendingLimit()` | **0** | 同上 `:36`，且该行原文写着 `//不支持` |
| `getTimeWorkerPool()` | **0** | 全仓零消费者 |

`MycatCore:114-135` 实况：

```java
ThreadPoolExecutorConfig workerPool = serverConfig.getServer().getWorkerPool();   // L114
VertxOptions vertxOptions = new VertxOptions();
vertxOptions.setWorkerPoolSize(workerPool.getMaxPoolSize());                       // L125 → vertx 自己的池
vertxOptions.setMaxWorkerExecuteTime(workerPool.getTaskTimeout());                 // L126
vertxOptions.setMaxWorkerExecuteTimeUnit(TimeUnit.valueOf(workerPool.getTimeUnit())); // L127
vertxOptions.setEventLoopPoolSize(serverConfig.getServer().getReactorNumber());    // L128
Vertx vertx = Vertx.vertx(vertxOptions);                                           // L134
context.put(IOExecutor.class, IOExecutor.DEFAULT);                                 // L135 ← 配置从未到达 IO 路径
```

⇒ **`workerPool` 整段配置对 IOExecutor 零影响**。`corePoolSize` / `keepAliveTime` / `maxPendingLimit` /
`timeWorkerPool` 是**纯装饰**。这是「名实相符」要修的核心。

**生产 `server/src/main/resources/server.json` 覆写值**：
`workerPool = {core:1, max:1024, keepAlive:1, maxPending:65535, taskTimeout:5, MINUTES}`，`reactorNumber=8`。
**这正是 spike 里打出 peakInFlight=1 的那组值**（core1/max1024/q65535 → 129.1 qps、inFlight **1**）。
⇒ 反地雷断言必须用**这组生产值**跑，不能用默认值跑。

**Lombok 陷阱**：`config/.../ThreadPoolExecutorConfig.java` 是 `@Data @ToString @Builder`，
字段初始化器 `corePoolSize=0`(L19) / `maxPoolSize=1024`(L20) / `keepAliveTime=60`(L21) /
`taskTimeout=600`(L22) / `timeUnit=SECONDS`(L23) / `maxPendingLimit=65535`(L24)，
**但没有 `@Builder.Default`** ⇒ `builder().build()` **忽略全部初始化器**，产出 0/null。
`new ThreadPoolExecutorConfig()` 才拿到字段默认值。**两套互不一致的默认值。**
（`ServerConfig:40-57` 的两个 builder 都显式设满 6 个字段，故补 `@Builder.Default` 对现有调用零影响。）

### 1.3 30 处活体 `executeBlocking` / 15 文件 —— 四车道分类

> **订正一**：此前口径「35 处 / 18 文件」**错**。排除定义处（`IOExecutor:25/66/101`）与
> 已注释处（`FileRepositoryImpl:56`、`MySQLMetadataStorageManager:237`）后，
> 活体为 **30 处 / 15 文件**。两板凡出现 35/18 处须一并订正。

按**拒绝策略**分车道（这是唯一真正重要的分类）：

**车道 U —— 永不拒绝（25 处）**

| 子类 | 站点 | 为什么必须永不拒绝 |
|---|---|---|
| 释放腿 3 | `ThreadMycatConnectionImplWrapper` `close():147`、`abandonConnection():167`、`abandonQuery():183` | **用户拍板的不变式**。拒绝归还 ⇒ 连接/许可永久泄漏 ⇒ 饱和不可恢复 |
| 心跳 1 | `JdbcConnectionManager:243` | 拒绝心跳 ⇒ 后端被误判死 ⇒ 雪崩 |
| 后台/观测 7 | `SqlResultSetService:96`、`MycatSQLLogMonitorImpl:167/186/203`、`SqlRecorderRuntime:43`、`MySQLLogConsumer:75`、`HttpCustomRuleFunction:65` | 与客户端语句无因果；拒绝只会丢观测或丢日志 |
| 序列取号 1 | `DefaultPrototypeGenerator:88` | **活体自死锁靶**，见 §7 |
| 内层执行腿 13 | wrapper `query:36`/`prepareQuery:51`/`insert:87`/`insert:102`/`update:117`/`update:132`；`JdbcMySqlConnection:153`/`:254`；`SqlResultCollectingPrepareJdbcQuery:47`；`RowSetJdbcPreparedJdbcQuery:146`/`:158`；`ReceiverImpl:144`；`CreateTableSQLHandler:90` | **嵌套准入死锁**，见 §3.3（决定性发现） |

**车道 A —— 可拒绝（5 处，全部是前端语句入口）**

| # | 站点 | 入口 |
|---|---|---|
| A1 | `server/.../mycatmysql/MycatVertxMySQLHandler:98` | COM_QUERY |
| A2 | `MycatVertxMySQLHandler:125` | COM_STMT_PREPARE |
| A3 | `MycatVertxMySQLHandler:374` | **COM_STMT_EXECUTE**（`handlePrepareStatementExecute`） |
| A4 | `server/.../commands/DefaultCommandHandler:66` | `handleQuery` |
| A5 | `DefaultCommandHandler:213` | **COM_STMT_EXECUTE**（`handlePrepareStatementExecute`） |

> **订正二**：此前把 A3/A5 标为「MycatdbCommand / 管理命令」并归入车道 Q 或 B，**错**。
> 实读 `MycatVertxMySQLHandler:359-375` 与 `DefaultCommandHandler:201-214`：
> 两处都是 `handlePrepareStatementExecute`，`MycatdbCommand.execute(dataContext, receiver, statement)`
> 是**通用语句执行器**，不是管理命令。⇒ **prepared 执行路径本来就在闸内**，不存在我一度担心的覆盖缺口。

> **订正三**：此前口径「车道 Q = 19 处可拒绝」**错且危险**。19 处里 14 处是内层腿，
> 给它们加闸会触发 §3.3 的嵌套死锁。正确可拒绝集是 **5 处前端入口**。

**前端只有这两个类**（`MycatVertxMySQLHandler` = vertx 前端，`DefaultCommandHandler` = 默认前端），
无第三前端。5 处入口覆盖 COM_QUERY / COM_STMT_PREPARE / COM_STMT_EXECUTE 三条客户端数据路径。

### 1.4 嵌套关系（实读确认，不是推演）

```
MycatVertxMySQLHandler:98  executeBlocking( handleQuery(sql, session) )      ← A1，外层
  └─ SQL Handler（如 CreateTableSQLHandler:90 executeBlocking）              ← 内层
  └─ AsyncMycatDataContext → ThreadMycatConnectionImplWrapper:36 executeBlocking  ← 内层
       └─ JdbcMySqlConnection:153 executeBlocking → innerExecute()（阻塞 JDBC）  ← 内层
  └─ response.sendResultSet(...) → ReceiverImpl:144 executeBlocking           ← 内层
```

`DefaultCommandHandler:66` 同构：`executeBlocking( MycatdbCommand.executeQuery(..., new ProxyReceiverImpl(...)) )`，
`ProxyReceiverImpl` → `ReceiverImpl:144` 仍在内层。

⇒ **一条客户端语句可嵌套 3～4 层 `executeBlocking`。**

### 1.5 阻塞腿的真实体量（本轮新增取证，spike 未覆盖）

- `server/.../exporter/MySQLLogConsumer:68-84`：后台腿里**借 Druid 连接 + INSERT `mycat`.`sql_log`**。
  与前端争**连接池**（池级，不是许可级）。
- `router/.../custom/HttpCustomRuleFunction:58-76`：`ScheduleUtil` 周期腿里**阻塞 HTTP** 拉分区。
  spike 从未覆盖 HTTP 这一第三方阻塞源。
- `server/.../exporter/SqlRecorderRuntime:36-52`：`synchronized (SqlRecorderRuntime.INSTANCE)`
  **全局单例监视器**。临界区是内存 sort+subList（CPU，非 IO）⇒ **不是 P0**，但属全局锁 pinning 观察项。
- `server/.../commands/ReceiverImpl:139-147`：`count++` 是**普通字段非原子**（既有缺陷，§11 挂账）。

---

## 2. 目标与非目标

### 目标

- **G1 有界**：客户端语句在飞数有硬上界，超限**拒绝并回干净 MySQL ERR**，会话存活、后续语句可成功。
- **G2 永不拒绝不变式**：释放/心跳/后台/序列/内层腿**结构上不可能被拒绝**（不是靠「记得迁移每一处」）。
- **G3 VT**：IO 执行体换虚拟线程，线程数与并发度解耦。
- **G4 pinning 清零**：P0 22 处换锁；活体满负载下 `-Djdk.tracePinnedThreads=full` **零 `<== monitors`**。
- **G5 名实相符**：`count()` 真等于在飞数；配置旋钮真接到 IO 路径；线程发现真看得见 VT；
  `kill`/`interrupt` 语义如实文档化。
- **G6 反地雷**：用**生产 server.json 那组值**断言 `peakInFlight > 1`。

### 非目标（本轮不做，§11 挂账）

不改 `kill` 的实际行为；不修 `HintHandler:164` 自 interrupt；不修 `ReceiverImpl.count++` 非原子；
不动 `SqlRecorderRuntime` 全局锁；不动 `MycatWorkerProcessor`（整文件注释）；
不删 `fromVertx`（用户拍板保留）；不引入 MariaDB 相关改动；不动 M1–M4 已闭环的验证网。

---

## 3. 架构设计

### 3.1 VT 执行体

`DEFAULT` 与 `fromVertx` 的 `Executors.newCachedThreadPool()` 换成
**具名 VT 工厂 + `newThreadPerTaskExecutor(factory)`**：

```java
ThreadFactory factory = Thread.ofVirtual().name("mycat-io-", 0).factory();
ExecutorService executorService = Executors.newThreadPerTaskExecutor(factory);
```

- **必须具名**：`show @@thread` 的 NAME 列要有可读前缀，否则运维无法区分 IO VT 与其他 VT。
- **不设 VT 调度器 parallelism**：沿用默认（= `availableProcessors`，本机 12）。
  spike 实测 maxPoolSize=256 从不被用到（JDK21 不补偿），调它无收益且引入不可解释的变量。
- **无界性是 VT 的本性**：`newThreadPerTaskExecutor` 无池上界 ⇒ **界必须由准入闸给，不能由池给**。
  这是 §3.2 的存在理由，不是可选优化。

### 3.2 准入闸：Semaphore，只装在 5 个前端入口

`IOExecutor` 新增**一个**方法，既有 `executeBlocking(h)` **签名与语义保持不变（永不拒绝）**：

```java
/** 客户端语句入口专用：准入受限，超限以 MycatException(ERR_IO_CONCURRENCY_LIMIT) 失败。 */
<T> Future<T> executeAdmitted(Handler<Promise<T>> blockingCodeHandler);
```

实现要点：

1. **立即提交 VT，在 VT 内部 acquire**。
   ⇒ **event loop 绝不阻塞**（在调用线程 acquire 会卡 reactor，这是必须避开的陷阱）。
   ⇒ 等待中的 VT 只是 park，成本极低。
2. `semaphore.tryAcquire(admitTimeoutMillis, MILLISECONDS)`；失败 ⇒
   `promise.tryFail(MycatErrorCode.createMycatException(ERR_IO_CONCURRENCY_LIMIT, ...))`。
3. **等待中的 VT 数自然有界** ≈ 到达率 × `admitTimeoutMillis`。
   不需要第二道「提交队列上限」——那会把 spike 里的 core/queue 地雷原样搬回来。
4. 许可释放见 §3.4。

**为什么用 `tryAcquire(timeout)` 而不是立即拒绝**：客户端短暂等待优于立刻报错；
且 VT park 使等待几乎免费。超时值必须**远小于**语句超时（生产 `taskTimeout=5 MINUTES`），
否则饱和时客户端会挂很久才拿到 ERR。默认值见 §8 决定点 D3。

### 3.3 决定性发现：内层腿加闸＝嵌套准入死锁

**若给 §1.3 车道 U 的 13 处内层腿也加闸，系统会在饱和时永久死锁：**

```
N 个许可全部被 N 条外层语句持有
  → 每条外层语句都在等一个内层许可
  → 内层许可只能由外层释放后产生
  → 外层永不释放
  ⇒ 死锁（不是变慢，是永久挂住）
```

这不是推演：§1.4 的嵌套链是**实读确认**的，`ReceiverImpl:144` 必然在 A1/A4 的 body 内被调用。

**因此本设计的核心裁决：闸只装在最外层客户端入口（5 处），内层一律走永不拒绝的 `executeBlocking`。**

三重收益：
- **语义正确**：许可数 == **同时在飞的客户端语句数**，这正是运维想界的量；
  而不是「内部任务提交数」（后者一条语句能贡献 3～4 个，界不住真实负载）。
- **不变式结构化**：25 处内层/释放/后台腿**连闸都不经过** ⇒
  「漏迁移某处」的失效模式是 **fail-open（多放一点并发）**，不是**破坏永不拒绝不变式**。
  这把 G2 从「靠纪律」变成「靠构造」。
- **改动面骤减**：需要改拒绝语义的只有 5 处，不是 19 处。

**协议安全性**（承接 I-DEBT-84 的教训：列头已刷后发裸 ERR 属协议非法）：
5 处入口都在**任何字节写向客户端之前**，receiver 尚未创建/尚未 flush
⇒ 拒绝时发 ERR 包**协议合法**。这是选前端入口做闸位的另一个必要条件。

### 3.4 许可生命周期：promise 完成才释放（不是 handler 返回就释放）

**陷阱**：车道 U 的 body 有两种形态——
- **阻塞型**：`JdbcMySqlConnection:153` → `innerExecute()` 同步阻塞到 JDBC 完成；
- **异步型**：wrapper `query:36` → `newMycatConnection.query(...).onComplete(promise)`，
  若底层是异步实现，`h.handle()` **立即返回**而 IO 仍在飞。

⇒ 若在 `finally { semaphore.release(); }` 里释放，异步型 body 会**提前释放**，
闸测到的是「提交重叠数」而非「在飞语句数」——**和 `count()` 现在的 bug 同一个病**。

**裁决**：许可与 `count()` **都挂在 promise 完成钩子上**：

```
提交时：count++（在飞 +1）
VT 内：acquire 成功 → h.handle(promise)
promise.onComplete：release 许可（若持有）+ count--
```

- 同步完成的 promise：vertx `onComplete` 对已完成 future **立即回调** ⇒ 无遗漏。
- **泄漏护栏**：若某 promise 永不完成，许可永久泄漏 ⇒ 闸单调收缩至 0 ⇒ 全库不可用。
  **依赖 I-DEBT-84 的语句 deadline**（已实现：超时 force-close 参与者解困 worker，
  以 3013 界住客户端）作为**唯一**的完成保证。
  ⇒ **本票与 I-DEBT-84 存在硬依赖**：deadline 必须在所有 5 个入口覆盖的路径上生效。
  验收须含「饱和 + 卡死查询」场景，证明许可被 deadline 回收（§9 A6）。
- **不另加 permit 最大持有超时**：那会与 deadline 形成两套互相打架的回收逻辑（YAGNI）。
  若 §9 A6 证明 deadline 有覆盖缺口，**补缺口而不是加第二套回收**。

### 3.5 线程发现重写：注册表 + 合并

`ThreadGroup.enumerate()` 看不见 VT，但 **`getId/getName/getState/getStackTrace` 对 VT 全部可用**
（spike 实测）⇒ **渲染层零改动，只改发现层**。

- `IOExecutor` 内维护 `Map<Long, Thread>`（`ConcurrentHashMap`）活体 VT 注册表：
  任务体开始处 put，**完成钩子处 remove**（与许可释放同一钩子，保证不泄漏）。
- `findAllThreads()` = `ThreadUtil.findAllThreads()`（平台线程，**原样保留**）
  **并上**注册表快照，**按 thread id 去重**。
- `interrupt(pid)` / `kill(pid)`：**先查注册表**，未命中再走平台线程列表。
- `ThreadUtil.findAllThreads()` **本身不改**（它服务于平台线程语义，改它会波及未知消费者）；
  合并逻辑放在 `IOExecutor.findAllThreads()`。
- `ThreadUtil.findThreadByName`（L31-41）**零生产消费者** ⇒ 不动。

**受益面**（`server/.../sqlhandler/dql/HintHandler.java`）：
- `showThreadInfo`（L170-194）：L177 取 `IOExecutor.findAllThreads()`，
  L172-175 建 ID/NAME/STATE/STACKTRACE 四列，L190 填行 ⇒ **只靠发现层修好即恢复**。
- `killThread`（L157-161）/ `interruptThread`（L163-168）：靠 `interrupt/kill` 查注册表恢复。
- 二者当前**无论有没有命中都 `sendOk()`**（L161/L168），只把 `affectedRows` 设 0/1 ⇒
  失效是**静默**的。验收必须断言 `affectedRows==1`，不能只看 OK 包。

### 3.6 `count()` 名实相符

DEFAULT 与 fromVertx **同一套修法**：提交时 `++`，**promise 完成钩子** `--`（§3.4）。
参照模型：`ThreadMycatConnectionImplWrapper` 的 `stat.plusThread()/decThread()`。
（注：该 wrapper 自身也在 `finally` 里 `decThread()`，有同样的提前释放问题；
**本轮不修它**——它是观测统计不是准入，修它会扩大范围。§11 挂账。）

### 3.7 `fromVertx` 保留并同闸（用户拍板）

- 同样换 VT、同样接闸、同样修 `count()`、同样注册线程。
- `metrics.createPoolMetrics("io", "mycat.io-thread", Integer.MAX_VALUE)`（L98）的
  `Integer.MAX_VALUE` 是**又一处名实相符谎言** ⇒ 换成**真实许可数**。
- `catch (RejectedExecutionException e) { metrics.rejected(queueMetric); throw e; }`（L123-128）
  ⇒ VT 池不会抛 REE；**准入拒绝**改走同一 metrics 计数后失败 promise。保留 rejected 指标语义。
- **零调用者 ⇒ 必须新增测试直接行使它**，否则本轮修的是一段没人跑过的代码（不可接受的伪绿）。

---

## 4. P0 22 处换锁（3 文件）

| 文件 | 处数 | 站点 | 锁拓扑 |
|---|---|---|---|
| `common/.../newquery/NewMycatConnectionImpl.java` | **6** | `:109`（`prepareQuery`，含 `executeQuery` + 完整流式 `while(rs.next())`）、`:199`、`:298`（显式 `synchronized(NewMycatConnectionImpl.this)`）、`:444`（`call`）、`:496`（`insert`）、`:595`（`update`） | **同一监视器 + 可重入嵌套** ⇒ **每实例一把 `ReentrantLock`**。**拆成多把会破坏语义** |
| `hbt/.../calcite/spm/DbPlanManagerPersistorImpl.java` | **13** | 全部方法级 `synchronized` | 同一 `this` ⇒ **每实例一把 `ReentrantLock`** |
| `plug/.../sequence/DefaultPrototypeGenerator.java` | **3** | `:69`、`:92`、`:108`（`getNumber()`，**监视器横跨阻塞 JDBC**） | 全部 `this` ⇒ **每实例一把 `ReentrantLock`** |

**其余 118 处活体 synchronized 不在本轮换锁范围**：P1 **40 处**（短临界区，无阻塞）+
P2 约 **78 处**（工具/缓存/配置/calcite fork；含休眠特例 `com/mysql/cj/protocol/x/SyncMessageReader` 5 处，
全仓零引用、Mycat 走 classic protocol ⇒ 不在活路径）。
它们不 park 就不产生 pinning 命中（机制见 §9 L1）；**命中即升级为 P0**，不预先换锁。

- **`.wait/.notify` 计数 = 0**（已核）⇒ 无需迁移条件变量，纯锁替换。
- **活体性已证**（不是静态推断）：
  `DbPlanManagerPersistorImpl` 经 `MycatRouterConfigOps:602`/`:684` 活体调用；
  `DefaultPrototypeGenerator` 经 `SequenceGenerator:49-59` **反射加载**
  （`Class.forName(clazz).getDeclaredConstructor(Map.class).newInstance(args)`）
  ⇒ **零静态消费者是反射加载的正常形态，不是死代码证据**。
- **`TransactionSessionTemplate` 是死代码**（abstract；唯一子类 `LocalTransactionSession.java:19` 已注释；
  `DruidDatasourceProvider:204-207 createSession` 已注释；全仓含测试零引用）
  ⇒ **不在 22 处内**。活体 `TransactionSession` = `proxy/.../runtime/MycatXaTranscation.java:17`，
  其 synchronized/wait/notify 计数 = **0**，`:80 getConnection` 无锁委派 ⇒ **无需改**。

### 4.1 外部监视器预检 —— **已执行，通过**

把内部 `synchronized(this)` 换成 `ReentrantLock` 后，**任何外部代码若对同一实例
`synchronized(obj)`，互斥即被破坏**（两把不同的锁）。故本轮**先做了全参考图预检**：

| 类 | 全部引用点（grep `**/src/**/*.java`，含测试） | 外部监视器？ |
|---|---|---|
| `NewMycatConnectionImpl` | `JdbcDatasourcePoolImpl:62`（**匿名子类**，覆写 `onSend`/`onRev`/`close`/`abandonConnection`）；测试 `JdbcWarningMappingTest:28/39/48`（只调静态 `toWarningMessages`）、`TestMySQLManagerImpl:73`、`QueryBackendWarningsTest:56` | **无**。匿名子类四个覆写**均非 synchronized、均无 `synchronized(this)` 块**（实读 `:62-113`）；`JdbcDatasourcePoolImpl` 全文唯一 synchronized 是 `:41 Collections.synchronizedList`（包装器，非监视器）；三个测试文件零 synchronized |
| `DbPlanManagerPersistorImpl` | `MycatRouterConfigOps:602`/`:684`（活体）、`SpmTest:37`（测试） | **无**。`MycatRouterConfigOps` 全文唯一 synchronized 是 `:761 synchronized (XA_REAPER_LOCK)`（**专用锁对象**，不是 persistor）；`SpmTest` 零 synchronized |
| `DefaultPrototypeGenerator` | 反射加载 `SequenceGenerator:50-57`；消费链 `VertxExecuter:251/255/261/646/655/661` → `TableHandler.nextSequence()` → `ShardingTable:182`/`NormalTable:108`/`GlobalTable:133`/`VisualTableHandler:115`/`SimpleCustomTableHandler:41`/`DualCustomTableHandler:40`/`CustomTableHandlerWrapper:90` | **无**。`VertxExecuter` 全文 synchronized 仅 `:148 synchronized (sum)`（**局部累加器**）与 `:1079 synchronizedList`；`hbt/.../calcite/table/` 全目录**零** synchronized；`router/.../sequence/` 全目录**零**；`plug/.../sequence/` 全目录**只有它自己那 3 处**（`:69`/`:92`/`:108`） |

⇒ **22 处这个数字成立**，换锁不会破坏任何外部互斥。
`NewMycatConnectionImpl:199`/`:298` 的 `synchronized (NewMycatConnectionImpl.this)` 位于**同文件内的匿名内部类**，
换成外层实例的 `ReentrantLock` 字段后仍可直接访问 ⇒ 单锁拓扑保持不变。

**实现期仍须复核**（预检是静态的，反射/动态代理无法穷尽）：
换锁后若 L1 出现 `<== monitors` 或活体出现数据竞争，**第一嫌疑就是漏网的外部监视器**。

### 4.2 换锁的内存可见性附带项

`DefaultPrototypeGenerator:50` 的 `private Value value = null;` **非 volatile**，
而 `:90` 在**锁外**读它（既有 race，在 `synchronized` 版本下就已存在）。
换 `ReentrantLock` **不会让它变坏**（happens-before 故事相同），但本轮既然动这个文件的锁，
**建议顺手加 `volatile`**（一个关键字）。列为决定点 D5。

---

## 5. 配置名实相符与真实接线

### 5.1 接线点

`MycatCore:135` 的 `context.put(IOExecutor.class, IOExecutor.DEFAULT)`
换成**由配置构造的实例**。`DEFAULT` 保留为**无配置时的兜底**（测试与类加载期仍需要它）。

- 构造时机：`MycatCore` 构造器内，`serverConfig` 已就绪（L105）之后、L151 `register` 之前。
- **必须注册进 L133 那份 `context`**（该 map 在 L151 被整表 register）。
  承接 I-VE-1 的教训：`register(Map)` 是**整表替换**，漏放会被抹掉 ⇒ 取用点 NPE。
- 所有 IOExecutor 消费者都是**每次调用惰性解析**（`MetaClusterCurrent.wrapper(IOExecutor.class)`）
  ⇒ 换实例无需改任何消费者。这是本轮改动面能这么小的结构原因。

### 5.2 旋钮语义重定义（名实相符的核心裁决）

VT 下**没有池、没有队列、没有 idle 线程** ⇒ 一半旋钮失去物理意义。如实处理，不装作它们还有效：

| 旋钮 | 新语义 | 理由 |
|---|---|---|
| `maxPoolSize` | **准入许可数**（同时飞行的客户端语句上界）；**同时继续喂 `vertxOptions.setWorkerPoolSize()`** | 唯一有「上界」语义的既存旋钮；生产值 1024 作为许可数是合理量级 |
| **新增** `admitTimeoutMillis` | 准入等待上限，超时拒绝 | `taskTimeout` 生产值 5 MINUTES，**绝不能**复用为准入等待 |
| `corePoolSize` | **VT 下无意义** ⇒ 启动 WARN 明示忽略 | 不删字段（避免既有 server.json 反序列化风险），但**必须如实告知** |
| `keepAliveTime` | 同上 | VT 无 idle 线程回收 |
| `maxPendingLimit` | **VT 下无意义** ⇒ 启动 WARN 明示忽略 | **严禁**把它实现成队列——那正是 spike 实测的地雷 |
| `taskTimeout` / `timeUnit` | **不变**，继续喂 vertx | 它们本来就只服务 vertx |
| `timeWorkerPool` | **零消费者** ⇒ 不动，文档标注未接线 | 本轮不扩大范围去给它找用途 |

**反地雷（G6）是构造性的**：VT 无队列 ⇒ 「小 core + 深队列 ⇒ 静默串行化」**不可能发生**。
但必须用**生产那组值**（core1/max1024/maxPending65535）跑活体断言 `peakInFlight > 1`，
证明地雷真的没了，而不是只在默认值下没了。

### 5.3 Lombok `@Builder.Default`

给 `ThreadPoolExecutorConfig` 全部 6 个字段补 `@Builder.Default`，统一两套默认值。
**对现有调用零影响**（`ServerConfig:40-57` 两个 builder 都显式设满 6 字段）。
**新增 `admitTimeoutMillis` 必须带 `@Builder.Default`**，否则 builder 产出 0 ⇒ 准入立即全拒。

### 5.4 新旋钮放哪

`admitTimeoutMillis` 加在 `ThreadPoolExecutorConfig` 上（与 `maxPoolSize` 同处，接线最短）。
**不新建 `IOExecutorConfig` 段**：那会引入一份新的配置文件形状与迁移面，收益不抵成本。

---

## 6. 拒绝语义与错误码

**新增** `common/.../beans/mycat/MycatErrorCode.java`：

```java
public static int ERR_IO_CONCURRENCY_LIMIT = 3007;
```

- **3007 是空槽**（现有 3006 → 3008，3007 未被占用；已核）。
- **不复用 `ERR_GET_CONNECTION_POOL_FULL = 3006`**：它语义是「连接池满」，
  而本闸是「语句准入满」，且它当前**零消费者**（已核）——复用会把两种资源耗尽混为一谈，
  运维无法据码区分「后端连接不够」与「Mycat 语句并发到顶」。
- **不用 MySQL 原生 1040/1041/1203**：1040 是连接数、1041 文案是内存、1203 是用户连接数，
  三者都会误导客户端驱动的重试/重连策略。Mycat 已有 3000 段自有码惯例
  （`ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT = 3013` 即 I-DEBT-84 在用），**沿用该惯例**。
- 经 `MycatErrorCode.createMycatException(3007, msg)` 抛出 ⇒
  自动带 `ERROR-CODE: [MYCAT-3007][ERR_IO_CONCURRENCY_LIMIT]` 前缀（该类 L143-165 的既有机制）。
- **文案须含可操作信息**：当前许可上界 + 建议重试。不泄露内部实现细节。
- **会话必须存活**：拒绝只失败当前语句的 promise，**不关通道**。
  验收须证明「拒绝后同一连接下一条语句成功」（§9 A3）。

---

## 7. 活体自死锁约束：`DefaultPrototypeGenerator`

**这是本轮新发现的活体靶**（此前我把自死锁机制降级为「待证推演」，现在找到了真实载体；
**仍未运行时复现**，故仍是「机制已定位、待活体证明」）。

```java
public Number get() {                       // :63
    long n = count.getAndUpdate(o -> o + 1);
    if (n < limit) return n;
    synchronized (this) { ...取走 this.value... }        // :69
    if (andUpdate != null) fetch();
    else Thread.sleep(inv / 2);             // :81  ← 15ms 自旋睡眠
    return get();                           // :83  ← 递归
}
public void fetch() {                       // :86
    MetaClusterCurrent.wrapper(IOExecutor.class)
        .executeBlocking(promise -> { ...getNumber()... });   // :88 ← 提交到同一个 IOExecutor
}
// 构造器 :56-58 另有一条 ScheduleUtil 周期腿，每 24ms 也调 fetch()
```

**机制**：`get()` 自旋睡眠 + 递归，**等待异步 `fetch()` 填 `value`**。
两条 fetch 路径（按需 + 24ms 周期）都提交到同一个 IOExecutor。
**若这些提交被调用方 `get()` 所占的同一道闸拦住，且闸已饱和 ⇒ fetch 永不执行 ⇒
`get()` 永远睡眠并持有许可 ⇒ 永久挂住。**

⇒ **硬约束**：`DefaultPrototypeGenerator:88` **必须走永不拒绝的 `executeBlocking`**（车道 U）。
本设计「闸只装 5 个前端入口」**天然满足**该约束——序列取号腿根本不经闸。

**但必须活体验证**，因为它是「加界」这类改动最典型的翻车点：
**饱和 + 序列密集 INSERT 工作负载 ⇒ 不得挂住**（§9 A5）。
无闸 VT 不会饿死；**有界准入闸会**。这条断言是本票与「随便加个 Semaphore」的分水岭。

---

## 8. 决定点（须用户拍板）

| # | 决定点 | 选项 | 我的建议 |
|---|---|---|---|
| **D1** | **闸位**：只装 5 个前端入口，内层 25 处全走永不拒绝 | (a) 只装前端 5 处　(b) 前端+内层都装 | **(a)**。(b) 已被 §3.3 证明会嵌套死锁，**不是权衡而是错误**。此项实为告知而非选择 |
| **D2** | **饱和时控制面 SQL 是否豁免准入**（`/*mycat:...*/` hint、`show @@...`、killThread） | (a) 豁免：在 A1/A4 对解码后 SQL 做大小写不敏感前缀判定，命中则走 `executeBlocking`　(b) 不豁免：饱和时运维只能走配置文件/重启 | **(a)**。饱和正是最需要 `show @@thread` / killThread 的时刻；不豁免会让饱和**不可用 SQL 恢复**。代价：前缀判定对前导空白/注释脆弱，须配测试。(b) 更简单但把运维逼到重启 |
| **D3** | **`admitTimeoutMillis` 默认值** | (a) 0（立即拒绝）　(b) 200ms　(c) 1000ms | **(b) 200ms**。远小于生产语句超时 5min；足够吸收瞬时尖峰；等待 VT 数 ≈ 到达率×0.2s，内存可控。(a) 对尖峰过于敏感，(c) 饱和时客户端体感挂 1s |
| **D4** | **许可数来源** | (a) 复用 `workerPool.maxPoolSize`（生产 1024）　(b) 新增独立旋钮 | **(a)**。少一个旋钮、少一份配置迁移；1024 作为「同时飞行语句数」量级合理，且 §5.2 已如实重定义其语义 |
| **D5** | **`DefaultPrototypeGenerator.value` 顺手加 `volatile`** | (a) 加（一个关键字，修既有 race）　(b) 不加，另立小债 | **(a)**。本轮已在改该文件的锁，可见性故事必须自洽；(b) 会留一个「换锁时看见了却没修」的已知 race |
| **D6** | **是否保留平台线程回退开关** | (a) VT-only，回滚＝revert 提交　(b) 加 `ioExecutorMode: VIRTUAL\|PLATFORM` 双路径 | **(a)**。(b) 引入两条都要测的生产路径与一份长期 dual-path 回归面，违背「一步到位」；spike 已证两者吞吐不可分辨，留开关的收益主要是心理上的 |

### 8.1 拍板结果（2026-11-17，用户）

**全部六点已拍板，生产码可开工。**

| # | 裁定 | 附加条件 |
|---|---|---|
| D1 | **(a) 闸只装 5 个前端入口** | 告知项，无歧义 |
| D2 | **(a) 控制面 SQL 豁免准入** | 仅作用 A1/A4 两个 COM_QUERY 入口；A2/A3/A5 拿不到原始 SQL 文本 ⇒ 一律不豁免，运维须用文本协议发 hint |
| D3 | **(b) `admitTimeoutMillis` 默认 200ms** | **必须可配置**（用户原话「最好是可以配置的，默认值可以用你推荐的值」） |
| D4 | **(a) 许可数复用 `workerPool.maxPoolSize`**，默认沿用生产 1024 | **必须可配置**（同上）。它本就是 `server.json` 既存字段 ⇒ 条件天然满足，不新增独立旋钮 |
| D5 | **(a) `DefaultPrototypeGenerator.value` 加 `volatile`** | 非配置项 |
| D6 | **(a) VT-only，不留平台线程开关** | 回滚＝revert 本轮提交 |

**「可配置」不是有字段就算数——必须证明非默认值真的生效。**
故强化验收 **A8** 并新增 **L10（配置生效性·活体）**：把 `server.json` 的 `workerPool.maxPoolSize` 改成 **3**、
`admitTimeoutMillis` 改成 **50**，重打 jar 起活体，断言
①同时在飞语句数上界 == 3（第 4 条并发语句拿 MYCAT-3007）；
②拒绝前的等待时长量级 ≈ 50ms 而非 200ms。
只测默认值等于没测配置通路——`MycatCore:135` 若仍写死 `IOExecutor.DEFAULT`，默认值测试照样全绿。
**这条断言是「真接线」与「假接线」的分水岭**，与 §5.1 的 I-VE-1 整表替换教训配套。

---

## 9. 验收标准

### 离线（TDD，每处先 RED）

- **A1 闸有界**：许可 N，提交 N+1 条并发语句 ⇒ 第 N+1 条以 **3007** 失败；前 N 条成功。
- **A2 永不拒绝不变式**：闸**完全饱和**时，`close()` / `abandonConnection()` / `abandonQuery()` /
  心跳 / 序列 fetch / 内层腿**全部成功**，无一被拒。
- **A3 会话存活**：拒绝后**同一连接**下一条语句成功（离线可用 fake session 断言通道未关）。
- **A4 `count()` 名实相符**：提交 K 个**未完成**任务 ⇒ `count()==K`；完成后 ⇒ `count()==0`。
  **必须用异步型 body 测**（阻塞型 body 测不出提前释放 bug）。
- **A5 线程发现**：`findAllThreads()` 能看见正在跑的 IO VT；
  对其 id 调 `interrupt(pid)` 返回 **true** 且该 VT 真被 interrupt；
  任务结束后注册表**不泄漏**（`findAllThreads()` 不再含它）。
- **A6 `fromVertx` 被真实行使**：零调用者 ⇒ 新增测试直接构造并跑 A1/A4/A5 全套。
- **A7 反地雷**：用**生产 server.json 那组值**（core1/max1024/maxPending65535）构造 ⇒
  并发提交下 `peakInFlight > 1`。
- **A8 配置真接线（D3/D4「必须可配置」的离线证明）**：改 `maxPoolSize` ⇒ 实际许可数随之变；
  改 `admitTimeoutMillis` ⇒ 拒绝时延随之变。**必须用非默认值断言**（如 permits=3 / timeout=50），
  只测默认值等于没测配置通路。活体对应 L10。
- **A9 Lombok**：`ThreadPoolExecutorConfig.builder().build()` 拿到**字段默认值**（非 0/null）。
- **A10 嵌套不死锁**：模拟 §1.4 的 3～4 层嵌套，在**闸饱和**下跑 ⇒ 不得挂住（§3.3 的回归锁）。

### 活体（**改生产必重打 jar 再验**）

fat jar：`server/target/server-1.22-release-jar-with-dependencies.jar`
后端：`m1-mysql-a`(:3306) / `m1-mysql-b`(:3307) mysql:8.0，`m6-mariadb-a`(:3308)
就绪：`docker exec m1-mysql-a mysqladmin ping -uroot -p123456 --silent`

- **L1 零 pinning（G4 的硬断言）**：满负载 proxy 跑 + `-Djdk.tracePinnedThreads=full`
  ⇒ 日志中 **`<== monitors` 出现 0 次**。
  **这是本轮最重要的单条断言**：JDK21 不补偿 carrier，一处漏改就把并发打回 12。

  **断言为何可达（机制须读准，否则会误判）**：JDK21 的 `tracePinnedThreads`
  **只在虚拟线程于 pinned 状态下 park 时**打印栈，**不是**每进一次 `synchronized` 就打印。
  ⇒ 静态清单里的 **P1 40 处**（短临界区：Future 赋值 / map 操作 / 内存态）与
  **P2 约 78 处**（工具 / 缓存 / 配置 / calcite fork）**只要临界区内不 park 就不产生命中**，
  故「全日志零命中」是**可达且有意义**的目标，不需要为它们换锁。
  （spike 佐证：vt/reentrant pinned **0**、vt/sync pinned **3**、round 3 DruidPinProbe pinnedFrames **0**。）

  **命中处置（不得放宽断言）**：若 `<== monitors` 命中在 **P0 那 3 个文件之外**的帧上，
  说明静态清单对该站点「非阻塞」的定性**错了** ⇒ **把它升级为 P0 并换锁**，
  而不是把断言改成「允许若干次」。命中即 finding，逐帧裁定并记入 ledger。
- **L2 线程数解耦**：同等负载下平台线程数**不随并发线性增长**（对照 spike：cached C2048→1595，VT→27）。
- **L3 饱和拒绝**：把许可压到小值 + 高并发 ⇒ 客户端拿到 **MYCAT-3007** 干净 ERR，
  **连接不断**，随后语句成功。
- **L4 饱和期释放腿**：饱和中主动断连 / abandon ⇒ 连接真被归还（Druid 活跃数回落），无泄漏。
- **L5 序列自死锁（§7）**：饱和 + **序列密集 INSERT** ⇒ **不得挂住**，取号持续前进。
- **L6 许可回收（§3.4 泄漏护栏）**：饱和 + 卡死查询 ⇒ I-DEBT-84 deadline 触发后**许可被回收**，
  闸不单调收缩。
- **L7 心跳隔离**：饱和中心跳持续 ⇒ 后端不被误判死。
- **L8 运维面恢复**：`show @@thread` **列出 IO VT**（NAME 带 `mycat-io-` 前缀）；
  `killThread` / `interruptThread` 对 VT id **`affectedRows==1`**（不能只看 OK 包）。
- **L9 反地雷活体**：用生产 server.json 原值跑 ⇒ 有效并发 > 1（不是 129 qps/inFlight1 那种静默串行化）。
- **L10 配置生效性活体（D3/D4「必须可配置」的端到端证明）**：改 `server.json`
  `workerPool.maxPoolSize=3` + `admitTimeoutMillis=50`，**重打 jar** 起活体 ⇒
  ①第 4 条并发语句拿 **MYCAT-3007**（许可上界真等于 3）；②拒绝前等待量级 ≈ **50ms** 而非 200ms。
  跑完**必须把 server.json 改回生产原值**再跑 L9，否则 L9 用的是被污染的配置。

### 残留未测项补证（spike 明确留下的缺口，本轮必须在活体矩阵里覆盖）

- **R1** vertx-core **4.5.14** 的 Future 链在 VT 上的行为（spike 用的是裸 JDK，未覆盖 vertx Future）。
- **R2** **DDL 型 JDBC**（`DbPlanManagerPersistorImpl` 的 13 处锁所在路径）。
- **R3** 序列 **`SELECT … FOR UPDATE`** 语句（`nextSql` 可配，spike 未覆盖行锁语义）。
- **R4** **HTTP custom-rule 腿**（`HttpCustomRuleFunction:65`，第三方阻塞 HTTP，spike 零覆盖）。
- **R5** `MySQLLogConsumer:75` 的**后台 JDBC INSERT** 与前端争连接池的行为。

### 既有护栏不得回退

M1/M2/M3/M4 矩阵、I-DEBT-84 deadline 全套、旧 Java Connector 线 STAGE6 full-CRUD 矩阵、
TLS 轴矩阵。**hbt 验证须 `-am`**。
**长护栏（>10min）用 Monitor persistent**，不用 Bash run_in_background（600000ms 上限会静默杀）。

### 双态标签纪律

每格标 **本地✅ / CI⏳ / CI✅**。**只有 CI 绿才是对外 Evidenced-Done。宁挂账不伪绿。**

---

## 10. 环境硬约束

- **JAVA_HOME 会漂到 JDK-25** ⇒ 每次构建/运行**必须强制**
  `D:/Program Files/BellSoft/LibericaJDK-21-Full`（openjdk 21.0.6）。
  VT 是本票核心，**用错 JDK 会让全部 pinning 结论失效**（JDK24+ 已改善 pinning，结论不可迁移）。
- 依赖版本（Maven 实测）：Druid **1.2.28**、mysql-connector-j **9.3.0**、vertx-core **4.5.14**。
- `vertx/`、`migrate/`、`seata/`、`sqlparser/`、`grid/`、`booster/`、`pattern/`、`testsuite/`
  **不在根 pom `<modules>`** ⇒ 别指望根构建覆盖它们。
- `.gitignore:5 = *.log`（全局）、`:71 = .superpowers/` ⇒ 证据日志入库须 `git add -f` 并写明归档例外。
- **`.qoder/*`（含 `.qoder/wt-baseline`）与 `scratch/` 不是我的，不得提交。**
- origin = `https://github.com/lgscofield/Mycat2.git`（合法 origin，**严禁替换成任何临时代理地址**）。
  **gh 未安装**。push 失败须自行重试（GitHub 时不时连不上）。
- **Explore / Plan / general-purpose 子代理已不可用** ⇒ 全部工作 inline；
  最终 review 只能是 **self-review（较弱，须如实披露）**。

---

## 11. 范围外（本轮发现，挂账不修）

| 项 | 位置 | 为什么不在本轮 |
|---|---|---|
| `interruptThread` **自 interrupt 调用方** | `HintHandler:164` `Thread.currentThread().interrupt()` | 疑似历史缺陷，与 IOExecutor 无因果；改它属另一票 |
| `kill(pid)` == `interrupt(pid)` 名实不符 | `IOExecutor:48-60` | Java 无法安全强停线程（`Thread.stop` 已移除）⇒ **改行为不可行**，本轮只如实文档化 |
| `SqlRecorderRuntime` 全局单例监视器 | `SqlRecorderRuntime:43` 内 `synchronized(INSTANCE)` | 临界区是内存 sort（CPU 非 IO）⇒ **不是 P0**；列 pinning 观察项，L1 若命中再议 |
| `ReceiverImpl.count++` 非原子 | `ReceiverImpl:141` | 观测统计，非准入；修它扩大范围 |
| wrapper `stat.decThread()` 在 finally（提前释放） | `ThreadMycatConnectionImplWrapper` 全 9 处 | 同 `count()` 病，但属观测；本轮只修 IOExecutor 自己的 `count()` |
| `MycatWorkerProcessor` 整文件注释 | `common/.../MycatWorkerProcessor.java` | 死文件，不动 |
| `timeWorkerPool` 零消费者 | `ServerConfig:49-57` | 本轮不给它找用途 |
| `MycatErrorCode` 既有**重码** | 3014（ERR_PARSE / ERR_SEQUENCE_NEXT_VALUE）、3026、3030 各重复 | 既有缺陷；新增 3007 已避開空槽，不顺手改既有码（会动客户端可见契约） |

---

## 12. 回写义务 —— **已执行（2026-11-17，先于实现）**

> 下列 1–5 项**已全部落盘**（记录用户拍板与事实订正，不属架构变更，无需等拍板）。
> plan Task 6 Step 12 的职责改为**复核 + 回填实测结果与双态标签**，不是重做。

1. **`2026-11-17-i-pr-3-vt-spike-report.md` §8.1**：标注裁定已被用户拍板取代
   （「先加界，VT 另立后续票」→ **「一步到位上 VT」**），保留原文作为历史记录，不删。
2. **`PROGRESS.md`**：I-PR-1 行 + M5 段 + 战略校准 + changelog，凡引旧裁定处一律标注取代。
3. **`DEBT-QUEUE.md`**：仪表盘 M5 段 + §5 M5 五个子条目 + 下一个开工位，同上。
4. **两板凡出现「35 处 / 18 文件」处订正为「30 处 / 15 文件」**（§1.3 订正一）。
5. **两板补记三处订正**：A3/A5 是 COM_STMT_EXECUTE 非管理命令（订正二）；
   可拒绝集是 5 处前端入口非 19 处（订正三）；`DefaultPrototypeGenerator` 是**活体自死锁靶**
   （此前降级为「待证推演」的机制现已定位到真实载体，仍未运行时复现）。

---

## 13. 风险与回滚

| 风险 | 量级 | 缓解 |
|---|---|---|
| **漏改一处阻塞 `synchronized`** | **高**：JDK21 不补偿 carrier ⇒ 并发打回 12，且**静默** | L1 零 `<== monitors` 硬断言 + 22 处逐处 RED→GREEN |
| **外部监视器与换锁冲突** | **已降为低**：§4.1 全参考图预检**通过** | 静态预检无法穷尽反射/动态代理 ⇒ L1 命中或活体竞争时第一嫌疑即此 |
| **许可泄漏 ⇒ 闸收缩至 0** | **高**：全库不可用 | §3.4 依赖 I-DEBT-84 deadline + L6 活体证明回收 |
| **序列自死锁** | **高**：永久挂住 | 闸只装前端（天然满足）+ L5 活体断言 |
| **嵌套准入死锁** | **高** | §3.3 裁决 + A10 离线回归锁 |
| **第三方 pinning 残留**（Druid 已实测 0；HTTP/vertx Future 未测） | 中 | R1/R4 补证 + L1 |
| **VT 上 vertx Future 语义差异** | 中 | R1 补证；既有护栏全跑 |
| **回滚** | — | **revert 本轮提交**（D6 选 VT-only，无 dual-path）。配置新增字段向后兼容（旧 server.json 缺 `admitTimeoutMillis` ⇒ 走 `@Builder.Default`） |

---

## 14. 诚实边界

- **吞吐收益不可宣称**：spike 的 interleaved 重复已证明吞吐差异在宿主负载方差下**不可分辨**，
  相关 qps 数字已**撤回**。本票的收益是**线程治理 + 有界 + 名实相符**，不是「更快」。
- **`DefaultPrototypeGenerator` 自死锁是「机制已定位到活体载体」，不是「已运行时复现」**。
  L5 是它的证明步骤，不是它的既有证据。
- **静态清单已两次自我订正**（`TransactionSessionTemplate` 死代码误判为 P0；
  「VT −24% 吞吐」与「core16 贴天花板」撤回）。本轮的 30/15、5 处闸位、22 处换锁
  均为**实读核对**结果，且 **§4.1 外部监视器预检已执行并通过**（全参考图逐点列出）。
  预检是静态的，**反射/动态代理无法穷尽** ⇒ 22 处成立的前提是 L1 零 pinning 与活体无竞争。
- **本轮又新增三处自我订正**（§1.3 订正一/二/三）：35/18→30/15；
  A3/A5 从「管理命令」订正为 **COM_STMT_EXECUTE**；可拒绝集从 19 处订正为 **5 处前端入口**。
  第三处是**危险性订正**：按旧口径实现会直接触发嵌套准入死锁。
- **最终 review 只能是 self-review**（子代理不可用），弱于 fresh reviewer，须如实披露后再由用户决定是否足够。