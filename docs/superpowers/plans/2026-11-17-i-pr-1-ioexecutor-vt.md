# I-PR-1 IOExecutor VT + 准入闸 + 名实相符 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 IOExecutor 从无界 cached 平台线程池换成「具名虚拟线程 + 只装在 5 个前端语句入口的 Semaphore 准入闸」，并修好 count()、线程发现、配置接线与初始 P0 22 处阻塞锁；活体新发现的阻塞锁按 Step 3 升级条款继续补换。

**Architecture:** 闸只装最外层客户端入口（COM_QUERY / COM_STMT_PREPARE / COM_STMT_EXECUTE × 两前端），内层 25 处腿全部走**永不拒绝**的既有 `executeBlocking` —— 这既让「释放腿永不拒绝」成为构造性保证，又避开嵌套准入死锁。许可与 count() 都挂在 **promise 完成钩子**上（不是 handler 返回），泄漏由 I-DEBT-84 语句 deadline 兜底。VT 不属任何 ThreadGroup ⇒ 用活体注册表补线程发现，渲染层零改动。

**Tech Stack:** JDK21 虚拟线程（`Thread.ofVirtual()` / `newThreadPerTaskExecutor`）、`java.util.concurrent.Semaphore`、`ReentrantLock`、vertx-core 4.5.14 `Future/Promise`、Lombok、Maven 多模块。

**Spec:** `docs/superpowers/specs/2026-11-17-i-pr-1-ioexecutor-vt-design.md`

---

## Global Constraints

- **拍板状态：D1–D6 全部已拍板（2026-11-17，用户），可开工。**
  D1 闸只装 5 前端入口 · D2 **控制面 SQL 豁免**（仅 A1/A4）· D3 `admitTimeoutMillis` 默认 **200ms** ·
  D4 许可数复用 **`workerPool.maxPoolSize`**（生产 1024）· D5 `value` 加 **`volatile`** · D6 **VT-only 不留开关**。
  **用户附加条件（原话「最好是可以配置的，默认值可以用你推荐的值」）：D3/D4 必须可配置。**
  两者都落在 `server.json` 的 `workerPool` 段 ⇒ 不新增独立旋钮。
  **「有字段」不等于「可配置」**：必须用**非默认值**断言配置真生效（spec A8 强化 + 新增 L10）。
  只测默认值时，`MycatCore:135` 若仍写死 `IOExecutor.DEFAULT` 也照样全绿 ⇒ 测不出假接线。

- **JAVA_HOME 会漂到 JDK-25。每条构建/运行命令前必须强制**：
  `export JAVA_HOME="D:/Program Files/BellSoft/LibericaJDK-21-Full"` 且 `export PATH="$JAVA_HOME/bin:$PATH"`。
  用错 JDK 会让全部 pinning 结论失效（JDK24+ 已改善 pinning，结论不可迁移）。
- **任务顺序是硬约束，不得调换**：Task 2（换锁）**必须先于** Task 3（VT）。
  反过来会造出「VT + 未换的阻塞 synchronized」中间态 = 并发静默坍缩到 carrier 数。
- **JDK21 实测：ForkJoinPool 不为 pinning 补偿 carrier**（线程数恒 27，从未向 maxPoolSize=256 增长）。
  ⇒ 漏改一处阻塞锁就把并发打回 12。残留覆盖率是**正确性项**，不是优化项。
- **`executeBlocking(h)` 的签名与语义保持不变：永不拒绝。** 新增 `executeAdmitted(h)` 承载闸。
- **可拒绝集恰好 5 处**（spec §1.3 车道 A）：`MycatVertxMySQLHandler:98`/`:125`/`:374`、
  `DefaultCommandHandler:66`/`:213`。**不得给任何内层腿加闸**（spec §3.3 嵌套死锁）。
- **P0 换锁 22 处 / 3 文件，每实例一把 `ReentrantLock`**：
  `NewMycatConnectionImpl` 6（同一监视器 + 可重入嵌套，**拆多把会破坏语义**）、
  `DbPlanManagerPersistorImpl` 13、`DefaultPrototypeGenerator` 3。
  `.wait/.notify` 计数 = 0 ⇒ 纯锁替换，无条件变量迁移。
- **外部监视器预检已通过**（spec §4.1 全参考图逐点列出）⇒ 22 处成立。
- **`fromVertx` 保留并一并加界**（用户拍板）。零调用者 ⇒ **必须新增测试直接行使它**。
- **`kill(pid)` 行为不变**（仍等于 interrupt）；本轮只如实文档化。
- **错误码 `ERR_IO_CONCURRENCY_LIMIT = 3007`**（空槽，已核）。
  不复用 3006（连接池满，语义不同且零消费者），不用 MySQL 原生 1040/1041/1203（会误导驱动重试策略）。
- **`maxPendingLimit` 严禁实现成队列** —— 那正是 spike 实测的地雷
  （core1/max1024/q65535 → peakInFlight=**1**、129.1 qps）。VT 无队列，地雷构造性消失。
- **反地雷断言必须用生产 `server/src/main/resources/server.json` 那组值**：
  `workerPool = {core:1, max:1024, keepAlive:1, maxPending:65535, taskTimeout:5, MINUTES}`、`reactorNumber=8`。
  用默认值跑证明不了地雷没了。
- **改生产必重打 jar 再验**：`server/target/server-1.22-release-jar-with-dependencies.jar`。
- **hbt 验证须 `-am`**。`vertx/ migrate/ seata/ sqlparser/ grid/ booster/ pattern/ testsuite/`
  **不在根 pom `<modules>`**，别指望根构建覆盖。
- **长护栏（>10min）用 Monitor persistent**，不用 Bash run_in_background（600000ms 上限会静默杀）。
- **双态标签**：每格标 本地✅ / CI⏳ / CI✅。**只有 CI 绿才是对外 Evidenced-Done。宁挂账不伪绿。**
- **`.qoder/*`（含 `.qoder/wt-baseline`）与 `scratch/` 不是我的，不得提交。**
  `.gitignore:5 = *.log`、`:71 = .superpowers/` ⇒ 证据日志入库须 `git add -f` 并写明归档例外。
- origin = `https://github.com/lgscofield/Mycat2.git`。**严禁替换成任何临时代理地址。**
  **gh 未安装**。push 失败自行重试。
- **子代理工具可用** ⇒ 实现仍按已选 executing-plans inline 执行；最终 review 必须使用真 fresh reviewer，不得退回 self-review。
- **吞吐收益不可宣称**：spike interleaved 重复已证吞吐差异不可分辨，相关 qps 数字已撤回。
  本票收益是**线程治理 + 有界 + 名实相符**，不是「更快」。
- **Task 3 之后、Task 6 的 L1 通过之前，不得对外宣称本票完成。**

## Review Focus

以下输入类/失效模式是 spec 蕴含、但常规测试最容易漏掉的，按咬人概率排序。
每条都已在对应 Task 里配了钉住它的测试：

1. **异步型 body**（`h.handle()` 立即返回而 IO 仍在飞，如 wrapper `query:36` 的 `.onComplete(promise)` 形态）
   ⇒ 若在 `finally` 释放许可/减 count，闸测到的是提交重叠数而非在飞语句数。
   合理预期：许可与 count 必须到 **promise 完成**才释放。**Task 3 Step 1 / Task 4 Step 1 用异步 body 钉住。**
2. **饱和 + 序列密集 INSERT** ⇒ 若取号的异步 `fetch()` 被同一道闸拦住，调用方会永久等待（不是变慢）。
   合理预期：取号持续前进。**Task 6 L5 以活体 `SequenceMySQLGenerator` 钉住；`DefaultPrototypeGenerator` 只是死码机制样本。**
3. **饱和 + 卡死查询** ⇒ promise 永不完成 ⇒ 许可永久泄漏 ⇒ 闸单调收缩至 0 ⇒ 全库不可用。
   合理预期：I-DEBT-84 deadline 触发后许可被回收。**Task 6 L6 钉住。**
4. **嵌套 3～4 层 `executeBlocking` 在闸饱和下** ⇒ 外层持满许可等内层许可 ⇒ 死锁。
   合理预期：不得挂住。**Task 4 Step 7（A10）钉住。**
5. **饱和时运维发 `show @@thread` / `killThread` / `interruptThread`** ⇒ 控制面被闸住 ⇒ 饱和不可用 SQL 恢复；
   且这三个 hint **无论命中与否都 `sendOk()`**，失效是静默的。
   合理预期：控制面豁免准入，且 `affectedRows==1`（不能只看 OK 包）。**Task 5 Step 5 + Task 6 L8 钉住。**

---

### Task 1: 配置基座 + 拒绝错误码

**Files:**
- Modify: `config/src/main/java/io/mycat/config/ThreadPoolExecutorConfig.java`（6 字段补 `@Builder.Default`；新增 `admitTimeoutMillis`）
- Modify: `common/src/main/java/io/mycat/beans/mycat/MycatErrorCode.java`（新增 3007）
- Test: `config/src/test/java/io/mycat/config/ThreadPoolExecutorConfigDefaultsTest.java`
- Test: `common/src/test/java/io/mycat/beans/mycat/MycatErrorCodeIoLimitTest.java`

**Interfaces:**
- Consumes: 无（首任务）
- Produces:
  - `ThreadPoolExecutorConfig.getAdmitTimeoutMillis()` → `long`，默认 **200**（决定点 D3）
  - `ThreadPoolExecutorConfig.builder().build()` 产出字段默认值：
    `corePoolSize=0, maxPoolSize=1024, keepAliveTime=60, taskTimeout=600, timeUnit="SECONDS", maxPendingLimit=65535, admitTimeoutMillis=200`
  - `MycatErrorCode.ERR_IO_CONCURRENCY_LIMIT` → `int = 3007`
  - `MycatErrorCode.createMycatException(3007, msg)` → `MycatException`，message 以
    `ERROR-CODE: [MYCAT-3007][ERR_IO_CONCURRENCY_LIMIT] ` 开头（该类 L143-165 既有机制）

- [ ] **Step 1: 写失败测试 —— builder 默认值**

`ThreadPoolExecutorConfigDefaultsTest`：
- `builderDefaultsMatchFieldInitializers()`：`ThreadPoolExecutorConfig.builder().build()` 的
  7 个 getter 逐一等于上面 Produces 列的值。**当前必失败**：无 `@Builder.Default` ⇒ builder 产出 0/null。
- `noArgsConstructorStillMatchesBuilder()`：`new ThreadPoolExecutorConfig()` 与 `builder().build()`
  逐字段相等（钉住「两套默认值统一」）。
- `admitTimeoutDefaultIs200()`：两种构造路径都得 200。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl config -am test -Dtest=ThreadPoolExecutorConfigDefaultsTest`
Expected: FAIL —— `builderDefaultsMatchFieldInitializers` 断言 `maxPoolSize` 期望 1024 实得 0；
`admitTimeoutDefaultIs200` 编译失败（getter 不存在）。

- [ ] **Step 3: 写失败测试 —— 错误码 3007**

`MycatErrorCodeIoLimitTest`：
- `ioLimitCodeIs3007()`：`ERR_IO_CONCURRENCY_LIMIT == 3007`。
- `ioLimitCodeIsRegisteredInMap()`：`errorCode(3007)` 返回串含 `[MYCAT-3007][ERR_IO_CONCURRENCY_LIMIT]`
  （证明反射静态块已收录，不是只加了个常量）。
- `ioLimitExceptionCarriesPrefix()`：`createMycatException(3007, "busy").getMessage()` 以上述前缀开头且含 `busy`。
- `code3007DoesNotCollide()`：3007 不等于该类任何**其它**字段值（避开既有重码 3014/3026/3030 的坑）。

- [ ] **Step 4: 跑测试确认失败**

Run: `mvn -q -pl common -am test -Dtest=MycatErrorCodeIoLimitTest`
Expected: FAIL —— 编译错误 `cannot find symbol: ERR_IO_CONCURRENCY_LIMIT`。

- [ ] **Step 5: 实现**

`ThreadPoolExecutorConfig`：给 6 个既有字段各加 `@Builder.Default`（保留原初始化器字面值不变）；
新增 `@Builder.Default private long admitTimeoutMillis = 200;`；
全参构造器（L29）**追加** `long admitTimeoutMillis` 末位参数并赋值。
`MycatErrorCode`：在 connection 段后新增 `public static int ERR_IO_CONCURRENCY_LIMIT = 3007;`
并补一行注释 `// MYCAT-3007:ERR_IO_CONCURRENCY_LIMIT`（与该文件既有注释风格一致）。

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -q -pl config -am test -Dtest=ThreadPoolExecutorConfigDefaultsTest` 与
`mvn -q -pl common -am test -Dtest=MycatErrorCodeIoLimitTest`
Expected: 两者 PASS。

- [ ] **Step 7: 确认对既有调用零影响**

Run: `mvn -q -pl config,common -am test`
Expected: PASS。`ServerConfig:40-57` 两个 builder 都显式设满 6 字段 ⇒ 补 `@Builder.Default` 不改其值。
若有既有测试断言 builder 产出 0，**停下来 ledger 裁定**（那是在钉住 bug）。

- [ ] **Step 8: Commit**

```bash
git add config/src/main/java/io/mycat/config/ThreadPoolExecutorConfig.java \
        config/src/test/java/io/mycat/config/ThreadPoolExecutorConfigDefaultsTest.java \
        common/src/main/java/io/mycat/beans/mycat/MycatErrorCode.java \
        common/src/test/java/io/mycat/beans/mycat/MycatErrorCodeIoLimitTest.java
git commit -m "feat(I-PR-1): 配置基座名实相符——@Builder.Default 统一两套默认值 + admitTimeoutMillis + MYCAT-3007 准入拒绝码"
```

---

### Task 2: P0 22 处阻塞锁换 ReentrantLock（**必须先于 VT**）

**Files:**
- Modify: `common/src/main/java/io/mycat/newquery/NewMycatConnectionImpl.java`（6 处：`:109 :199 :298 :444 :496 :595`）
- Modify: `hbt/src/main/java/io/mycat/calcite/spm/DbPlanManagerPersistorImpl.java`（13 处方法级）
- Modify: `plug/src/main/java/io/mycat/plug/sequence/DefaultPrototypeGenerator.java`（3 处：`:69 :92 :108`；`:50` 加 `volatile`）
- Test: `common/src/test/java/io/mycat/newquery/NewMycatConnectionImplLockTest.java`
- Test: `plug/src/test/java/io/mycat/plug/sequence/DefaultPrototypeGeneratorLockTest.java`
- Test: `hbt/src/test/java/io/mycat/calcite/spm/DbPlanManagerPersistorImplLockTest.java`

**Interfaces:**
- Consumes: 无（与 Task 1 独立）
- Produces: 三个类各持**一个** `private final ReentrantLock lock = new ReentrantLock();` 实例字段。
  **不新增公开 API**，不改任何方法签名 ⇒ 对消费者零接口变化。
  Task 3 依赖「这三个类在 VT 上不再 park-while-pinned」这一性质。

- [ ] **Step 1: 写失败测试 —— 互斥与可重入仍成立**

`NewMycatConnectionImplLockTest`（用真实 JDBC 连接连 `m1-mysql-a`，或 H2/内存桩不可行时用真库）：
- `concurrentQueriesOnSameConnectionAreSerialized()`：同一实例上两线程并发 `prepareQuery`/`insert`，
  断言**无交叉**（用后端 `SELECT ... FOR UPDATE` 或计数桩观测临界区互斥）。
- `reentrantNestingDoesNotDeadlock()`：钉住 `:199`/`:298` 的**同一监视器可重入嵌套**——
  在持锁路径内再进入另一持锁方法，**不得死锁**（设 5s 超时，超时即 FAIL）。
  **这条是「拆成多把锁」的回归锁**：拆锁后此测试仍可能过，故配 Step 2 的结构断言。
- `lockIsSinglePerInstance()`：反射断言该类**恰好一个** `ReentrantLock` 类型的实例字段
  （钉住单锁拓扑，防止实现者拆成多把）。

`DefaultPrototypeGeneratorLockTest`：
- `getNumberIsMutuallyExclusive()`：两线程并发 `getNumber()`，断言串行。
- `valueFieldIsVolatile()`：反射断言 `value` 字段带 `volatile` 修饰符（决定点 D5）。
- `lockIsSinglePerInstance()`：同上，恰好一个 `ReentrantLock` 实例字段。

`DbPlanManagerPersistorImplLockTest`：
- `persistorMethodsAreMutuallyExclusive()`：两线程并发调不同持锁方法，断言串行。
- `lockIsSinglePerInstance()`：恰好一个 `ReentrantLock` 实例字段。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl common -am test -Dtest=NewMycatConnectionImplLockTest`
Expected: FAIL —— `lockIsSinglePerInstance` 断言 `ReentrantLock` 字段数期望 1 实得 **0**（当前是 synchronized）。
互斥类测试此时**应当已通过**（synchronized 本来就互斥）——**这是预期**：它们是不回退锁，不是 RED 靶。
**RED 必须由结构断言提供**，ledger 记明这一点，不得把「已通过」当成测试写错。

Run: `mvn -q -pl plug -am test -Dtest=DefaultPrototypeGeneratorLockTest`
Expected: FAIL —— `lockIsSinglePerInstance` 得 0；`valueFieldIsVolatile` 得 false。

Run: `mvn -q -pl hbt -am test -Dtest=DbPlanManagerPersistorImplLockTest`
Expected: FAIL —— `lockIsSinglePerInstance` 得 0。**hbt 必须带 `-am`。**

- [ ] **Step 3: 实现换锁**

三个类各加 `private final ReentrantLock lock = new ReentrantLock();`。
每处 `synchronized (X) { body }` → `lock.lock(); try { body } finally { lock.unlock(); }`；
每个 `synchronized` 方法 → 去修饰符 + 同形包裹。
**`NewMycatConnectionImpl:199`/`:298` 的 `synchronized (NewMycatConnectionImpl.this)` 位于同文件匿名内部类**
⇒ 直接引用外层实例的 `lock` 字段（内部类可访问），**保持单锁**。
`DefaultPrototypeGenerator:50` 的 `value` 加 `volatile`。
**不得顺手改任何其它逻辑**（`:90` 的锁外读保持原样，volatile 已修其可见性）。

- [ ] **Step 4: 跑测试确认通过**

Run: 三条 Step 2 的命令
Expected: 全 PASS，含互斥不回退锁与可重入不死锁。

- [ ] **Step 5: 跑三模块既有全套（不回退）**

Run: `mvn -q -pl common,plug,hbt -am test`
Expected: PASS。任何既有红测**按名字写进汇报**，不得静默略过。

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/io/mycat/newquery/NewMycatConnectionImpl.java \
        common/src/test/java/io/mycat/newquery/NewMycatConnectionImplLockTest.java \
        hbt/src/main/java/io/mycat/calcite/spm/DbPlanManagerPersistorImpl.java \
        hbt/src/test/java/io/mycat/calcite/spm/DbPlanManagerPersistorImplLockTest.java \
        plug/src/main/java/io/mycat/plug/sequence/DefaultPrototypeGenerator.java \
        plug/src/test/java/io/mycat/plug/sequence/DefaultPrototypeGeneratorLockTest.java
git commit -m "refactor(I-PR-1): P0 22 处阻塞 synchronized 换每实例单把 ReentrantLock（VT 前置）+ 序列 value 补 volatile"
```

---

### Task 3: IOExecutor 换 VT + count() 名实相符 + 线程注册表

**Files:**
- Modify: `common/src/main/java/io/mycat/IOExecutor.java`（DEFAULT L62-87、fromVertx L89-141、findAllThreads L29-32、interrupt L34-46、kill L48-60）
- Test: `common/src/test/java/io/mycat/IOExecutorVtTest.java`
- Test: `common/src/test/java/io/mycat/IOExecutorFromVertxTest.java`

**Interfaces:**
- Consumes: Task 2 的「三个 P0 类不再 park-while-pinned」性质（**顺序硬约束**）
- Produces:
  - `IOExecutor.DEFAULT` 与 `fromVertx(vertx)` 均在**具名 VT** 上跑，线程名前缀 `mycat-io-`
  - `count()` → **真实在飞任务数**（提交 +1，**promise 完成** −1）
  - `findAllThreads()` → 平台线程（`ThreadUtil.findAllThreads()` 原样）**并上**活体 VT 注册表，按 id 去重
  - `interrupt(long pid)` / `kill(long pid)` → 先查注册表再查平台列表；**行为仍只是 interrupt**
  - Task 4 将复用同一套「提交 / 完成钩子 / 注册表」骨架

- [ ] **Step 1: 写失败测试 —— count() 必须用异步型 body 测**

`IOExecutorVtTest`：
- `countTracksInFlightForAsyncBody()`：**关键测试**。提交 K=8 个 body，形如
  `promise -> { /* 立即返回，不 complete promise */ }`，把 promise 存进测试持有的列表。
  断言 `count() == 8`（**当前必失败**：既有实现在提交后立刻 `decrementAndGet` ⇒ count≈0）。
  随后逐个 `promise.tryComplete()`，断言 `count()` 递减到 **0**。
- `countTracksInFlightForBlockingBody()`：body 内 `CountDownLatch` 卡住 ⇒ `count()==K`；放行 ⇒ 0。
- `tasksRunOnNamedVirtualThreads()`：body 内断言 `Thread.currentThread().isVirtual()` 为 true
  且 `getName().startsWith("mycat-io-")`。
- `findAllThreadsSeesLiveVirtualThread()`：body 卡住期间，`findAllThreads()` 含该 VT
  （按 body 内记录的 `Thread.currentThread().getId()` 匹配）；完成后**不再含**（注册表不泄漏）。
- `findAllThreadsStillSeesPlatformThreads()`：起一个具名平台线程，断言它仍在结果里
  （钉住「合并而非替换」，`ThreadUtil` 原样保留）。
- `interruptFindsVirtualThreadById()`：body 卡住期间 `IOExecutor.interrupt(vtId)` 返回 **true**
  且 body 内观测到 `InterruptedException` / interrupt 标志。
- `killBehavesAsInterrupt()`：钉住**行为不变**（`kill(vtId)` 同样只 interrupt），
  并在测试注释里写明这是如实记录而非认可命名（spec §11 挂账）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl common -am test -Dtest=IOExecutorVtTest`
Expected: FAIL —— `countTracksInFlightForAsyncBody` 期望 8 实得 0；
`tasksRunOnNamedVirtualThreads` 期望 isVirtual=true 实得 false。

- [ ] **Step 3: 写失败测试 —— fromVertx 必须被真实行使**

`IOExecutorFromVertxTest`（`fromVertx` **零生产调用者** ⇒ 不测就是修了一段没人跑过的代码）：
用 `Vertx.vertx()` 构造实例，跑与 Step 1 **同形的全套**：
`countTracksInFlightForAsyncBody` / `tasksRunOnNamedVirtualThreads` /
`findAllThreadsSeesLiveVirtualThread` / `interruptFindsVirtualThreadById`。
另加 `poolMetricsBoundIsNotMaxValue()`：断言其 pool metrics 上界**不是** `Integer.MAX_VALUE`
（当前 L98 传的就是 MAX_VALUE，属名实相符谎言）。

- [ ] **Step 4: 跑测试确认失败**

Run: `mvn -q -pl common -am test -Dtest=IOExecutorFromVertxTest`
Expected: FAIL —— 同上两类断言。

- [ ] **Step 5: 实现**

`IOExecutor`：
- 抽一个**共享骨架**（DEFAULT 与 fromVertx 共用，避免两份漂移）：
  具名 VT 工厂 `Thread.ofVirtual().name("mycat-io-", 0).factory()` +
  `Executors.newThreadPerTaskExecutor(factory)`；
  `ConcurrentHashMap<Long, Thread>` 活体注册表；`AtomicLong count`。
- **提交时** `count.incrementAndGet()`；**promise 完成钩子**里 `count.decrementAndGet()` + 注册表 remove。
  **不得在 `finally` 里减**（那是既有 bug 的病根）。
- 任务体开始处注册 `Thread.currentThread()`。
- `findAllThreads()`：`ThreadUtil.findAllThreads()` + 注册表快照，**按 id 去重**后返回。
- `interrupt`/`kill`：先查注册表，未命中再走平台列表；**实现仍只 `thread.interrupt()`**。
- `fromVertx`：`metrics.createPoolMetrics("io","mycat.io-thread", <真实上界>)`；
  既有 `catch (RejectedExecutionException)` 的 `metrics.rejected(queueMetric)` 语义保留
  （VT 池不抛 REE，该分支留给 Task 4 的准入拒绝复用）。
- **`ThreadUtil.java` 不改一字。**

- [ ] **Step 6: 跑测试确认通过**

Run: Step 2 与 Step 4 两条命令
Expected: 全 PASS。

- [ ] **Step 7: 跑 common 既有全套 + 三个 DEFAULT 测试消费者**

Run: `mvn -q -pl common -am test` 与
`mvn -q -pl server -am test -Dtest=ReceiverStatementDeadlineForceCloseTest,JdbcDatasourcePoolAbandonTest` 与
`mvn -q -pl example -am test -Dtest=SpmTest`
Expected: PASS。这三处是 `IOExecutor.DEFAULT` 的既有测试消费者，换 VT 后必须仍绿。

- [ ] **Step 8: Commit**

```bash
git add common/src/main/java/io/mycat/IOExecutor.java \
        common/src/test/java/io/mycat/IOExecutorVtTest.java \
        common/src/test/java/io/mycat/IOExecutorFromVertxTest.java
git commit -m "feat(I-PR-1): IOExecutor 换具名虚拟线程 + count() 挂 promise 完成钩子 + VT 注册表补线程发现（fromVertx 同修）"
```

---

### Task 4: 准入闸 executeAdmitted + 许可生命周期

**Files:**
- Modify: `common/src/main/java/io/mycat/IOExecutor.java`（新增 `executeAdmitted`；DEFAULT 与 fromVertx 各实现）
- Create: `common/src/main/java/io/mycat/IOExecutorConfig.java`（许可数 + 准入超时的不可变载体）
- Test: `common/src/test/java/io/mycat/IOExecutorAdmissionTest.java`

**Interfaces:**
- Consumes: Task 1 的 `getAdmitTimeoutMillis()` / `ERR_IO_CONCURRENCY_LIMIT`；Task 3 的提交/完成钩子/注册表骨架
- Produces:
  - `IOExecutor.executeAdmitted(Handler<Promise<T>> h)` → `Future<T>`；
    准入失败时以 `MycatErrorCode.createMycatException(3007, msg)` 失败，**不抛异常给调用方**
  - `IOExecutorConfig`：`long permits`（来自 `maxPoolSize`）、`long admitTimeoutMillis`；
    静态工厂 `IOExecutorConfig.from(ThreadPoolExecutorConfig)`
  - `IOExecutor.create(IOExecutorConfig)` → 由配置构造的实例（Task 5 在 `MycatCore:135` 用）
  - **`executeBlocking(h)` 不变：永不拒绝、不经闸**
  - `IOExecutor.peakInFlight()` → `long`，历史最大在飞数（**反地雷断言 A7 需要它**）

- [ ] **Step 1: 写失败测试 —— 闸有界 + 异步 body 不提前释放**

`IOExecutorAdmissionTest`（用 `IOExecutor.create(new IOExecutorConfig(permits=4, admitTimeoutMillis=200))`）：
- `rejectsBeyondPermitsWith3007()`：提交 5 个**卡住不完成**的 body ⇒ 前 4 个在飞，
  第 5 个的 Future **失败**且 `cause` 是 `MycatException`、`errorCode==3007`。
- `permitHeldUntilPromiseCompletesForAsyncBody()`：**Review Focus #1**。
  body **立即返回但不 complete promise**（异步型）⇒ 占满 4 个许可后第 5 个**仍被拒**
  （证明许可不是在 handler 返回时释放的）。随后 complete 一个 promise ⇒ 第 5 个**转为成功**。
- `admitWaitIsBoundedByTimeout()`：permits=1、admitTimeoutMillis=200，占满后提交第二个 ⇒
  在 **≥200ms 且 <2s** 内失败（证明是带超时的等待，不是立即拒也不是无限等）。
- `eventLoopIsNotBlockedByAdmission()`：在**单线程 executor**（模拟 event loop）上调用
  `executeAdmitted`，断言**调用本身立即返回**（不阻塞调用线程），拒绝发生在返回的 Future 上。
- `executeBlockingNeverRejectsWhenGateSaturated()`：**Review Focus #4 / 不变式 A2**。
  用 `executeAdmitted` 占满全部许可，再提交 20 个 `executeBlocking` ⇒ **全部成功**，无一被拒。
- `countAndPeakInFlightReflectAdmittedTasks()`：`count()` 与 `peakInFlight()` 随准入/完成正确变化。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl common -am test -Dtest=IOExecutorAdmissionTest`
Expected: FAIL —— 编译错误 `cannot find symbol: executeAdmitted` / `IOExecutorConfig` / `create`。

- [ ] **Step 3: 实现闸**

- `IOExecutorConfig`：不可变，`permits` + `admitTimeoutMillis`；`from(ThreadPoolExecutorConfig)`
  取 `getMaxPoolSize()` 与 `getAdmitTimeoutMillis()`（决定点 D4）。
- `executeAdmitted`：**立即提交 VT**（调用线程绝不 acquire），VT 内
  `semaphore.tryAcquire(admitTimeoutMillis, MILLISECONDS)`；
  失败 ⇒ `promise.tryFail(createMycatException(3007, <含许可上界与重试建议的文案>))`；
  成功 ⇒ `h.handle(promise)`，**许可在 promise 完成钩子释放**（与 count/注册表同一钩子）。
- **不加第二套「许可最大持有超时」**：泄漏由 I-DEBT-84 deadline 兜底（spec §3.4），
  两套回收逻辑会互相打架。
- `executeBlocking` **完全不经闸**，保持 Task 3 的形状。
- `peakInFlight()`：完成钩子里用 `AtomicLong.accumulateAndGet(Math::max)` 维护。
- `fromVertx` 同样接闸，拒绝走既有 `metrics.rejected(queueMetric)`。

- [ ] **Step 4: 跑测试确认通过**

Run: Step 2 命令
Expected: 全 PASS。

- [ ] **Step 5: 写失败测试 —— 嵌套准入不死锁（A10）**

`IOExecutorAdmissionTest` 追加：
- `nestedExecuteBlockingUnderSaturationDoesNotDeadlock()`：permits=2，
  用 `executeAdmitted` 提交 2 个外层 body，每个 body 内再调 **3 层** `executeBlocking`
  （模拟 spec §1.4 的 `前端 → SQLHandler → wrapper → JdbcMySqlConnection` 链），
  最内层用 latch 卡住后放行。设 **10s 超时**，断言全部完成、**无挂住**。
- `nestedExecuteAdmittedWouldDeadlock_documentsWhyInnerLegsAreUngated()`：
  **反向对照**——同样嵌套但内层也用 `executeAdmitted` ⇒ 断言**超时挂住**，
  以此把「内层不得加闸」钉成**可执行的证据**而非注释。
  （此测试预期是「观察到死锁」，用 timeout 断言，不得让它真的挂死测试进程。）

- [ ] **Step 6: 跑测试确认失败**

Run: `mvn -q -pl common -am test -Dtest=IOExecutorAdmissionTest`
Expected: 第一条 PASS（Task 4 实现已保证）；第二条若 FAIL 说明内层意外经闸 ⇒ 修实现。
**两条都必须存在且都跑过**，ledger 记明第二条是反向对照。

- [ ] **Step 7: 跑 common 全套**

Run: `mvn -q -pl common -am test`
Expected: PASS。

- [ ] **Step 8: Commit**

```bash
git add common/src/main/java/io/mycat/IOExecutor.java \
        common/src/main/java/io/mycat/IOExecutorConfig.java \
        common/src/test/java/io/mycat/IOExecutorAdmissionTest.java
git commit -m "feat(I-PR-1): executeAdmitted 准入闸（Semaphore·VT 内 acquire·promise 完成才释放）+ 嵌套死锁正反回归锁"
```

---

### Task 5: 5 处前端入口接闸 + 控制面豁免 + MycatCore 配置真接线

**Files:**
- Modify: `server/src/main/java/io/mycat/mycatmysql/MycatVertxMySQLHandler.java`（`:98 :125 :374`）
- Modify: `server/src/main/java/io/mycat/commands/DefaultCommandHandler.java`（`:66 :213`）
- Modify: `server/src/main/java/io/mycat/MycatCore.java`（`:135`）
- Create: `server/src/main/java/io/mycat/mycatmysql/ControlPlaneSql.java`（豁免判定，若 D2 选豁免）
- Test: `server/src/test/java/io/mycat/mycatmysql/ControlPlaneSqlTest.java`
- Test: `server/src/test/java/io/mycat/FrontendAdmissionWiringTest.java`

**Interfaces:**
- Consumes: Task 4 的 `executeAdmitted` / `IOExecutor.create(IOExecutorConfig)` / `IOExecutorConfig.from(...)`
- Produces:
  - `ControlPlaneSql.isControlPlane(String sql)` → `boolean`（大小写不敏感前缀判定，容忍前导空白与 `/*...*/` 注释）
  - `MycatCore` 在 L133 那份 `context` 里放入**由配置构造**的 IOExecutor（L151 整表 register 不会抹掉）
  - 5 处入口：控制面走 `executeBlocking`，其余走 `executeAdmitted`

- [ ] **Step 1: 写失败测试 —— 控制面豁免判定**

`ControlPlaneSqlTest`（**Review Focus #5**，决定点 D2）：
- `recognizesMycatHint()`：`/*mycat:cmd=showThreadInfo*/`、`/*mycat:cmd=killThread,id=123*/` ⇒ true。
- `recognizesShowAtAt()`：`show @@thread`、`SHOW @@datasource` ⇒ true。
- `toleratesLeadingWhitespaceAndComments()`：`"  \n/*x*/ /*mycat:cmd=showThreadInfo*/"` ⇒ true
  （**这条钉住前缀判定的脆弱点**，朴素 `startsWith` 会漏）。
- `doesNotExemptOrdinaryDml()`：`select 1`、`insert into t values(1)`、`/*mycat*/ select 1`
  （**注意**：`/*mycat*/` 不带冒号命令 ⇒ 不是 hint，不得豁免）⇒ false。
- `nullAndEmptyAreNotControlPlane()`：null / `""` / 全空白 ⇒ false（不得 NPE）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl server -am test -Dtest=ControlPlaneSqlTest`
Expected: FAIL —— 编译错误 `cannot find symbol: ControlPlaneSql`。

- [ ] **Step 3: 实现豁免判定 + 5 处接闸**

`ControlPlaneSql.isControlPlane`：跳过前导空白与 `/*...*/` 注释后，
大小写不敏感匹配 `/*mycat:` 前缀或 `show @@` 前缀。**只做前缀判定，不引解析器**（成本与范围）。
5 处入口改为：`isControlPlane(sql)` ⇒ `executeBlocking`，否则 ⇒ `executeAdmitted`。
**A2/A3/A5（prepare / execute）拿不到原始 SQL 文本**（只有 statementId / bytes）⇒
**这三处一律走 `executeAdmitted`，不做豁免**；豁免只作用于 A1/A4 两个 COM_QUERY 入口。
ledger 记明此裁决：prepared 语句的控制面豁免不可得，运维须用文本协议发 hint。

- [ ] **Step 4: 跑测试确认通过**

Run: Step 2 命令
Expected: PASS。

- [ ] **Step 5: 写失败测试 —— 接线与拒绝语义**

`FrontendAdmissionWiringTest`：
- `mycatCoreRegistersConfiguredIOExecutor()`：以 `workerPool.maxPoolSize=7` 构造配置 ⇒
  `MetaClusterCurrent.wrapper(IOExecutor.class)` 的许可上界为 **7**（不是 DEFAULT 的无界）。
  **钉住 L135 真接线**，且钉住「注册进 L133 那份 context、不被 L151 整表 register 抹掉」（I-VE-1 教训）。
- `admissionRejectionKeepsSessionAlive()`：**A3**。fake session 下触发 3007 ⇒
  断言**通道未关**，且随后一条语句成功。
- `rejectionHappensBeforeAnyByteWritten()`：断言拒绝路径上 receiver **尚未 flush**
  （协议合法性，承接 I-DEBT-84「列头已刷后裸 ERR 非法」的教训）。
- `admitTimeoutIsConfigurableNotHardcoded()`：**A8 强化 / 用户「必须可配置」条件**。
  以 `admitTimeoutMillis=50` 构造（**非默认 200**）⇒ 饱和下拒绝前的等待量级 **≥50ms 且 <200ms**。
  再以 `admitTimeoutMillis=400` 构造 ⇒ 等待 **≥400ms**。
  **两个非默认值都要测**：只测一个仍可能被「硬编码 200 + 巧合」蒙混。
- `innerLegsStillUseExecuteBlocking()`：反射/源码结构断言——
  `ThreadMycatConnectionImplWrapper`、`ReceiverImpl`、`JdbcMySqlConnection`、`CreateTableSQLHandler`、
  `DefaultPrototypeGenerator`、`JdbcConnectionManager` 及 7 处后台腿
  **零 `executeAdmitted` 调用**（钉住车道 U 的 25 处不被误迁移）。

- [ ] **Step 6: 跑测试确认失败**

Run: `mvn -q -pl server -am test -Dtest=FrontendAdmissionWiringTest`
Expected: FAIL —— `mycatCoreRegistersConfiguredIOExecutor` 期望 7 实得无界（L135 仍是 DEFAULT）。

- [ ] **Step 7: 实现 MycatCore 接线**

`MycatCore:135`：`context.put(IOExecutor.class, IOExecutor.create(IOExecutorConfig.from(workerPool)))`。
位置在 `serverConfig` 就绪（L105）之后、L151 `register(context)` 之前，**放进 L133 那份 map**。
`IOExecutor.DEFAULT` 保留为无配置兜底（测试与类加载期仍需要）。
启动时对 `corePoolSize` / `keepAliveTime` / `maxPendingLimit` / `timeWorkerPool`
**各打一条 WARN 明示 VT 下忽略**（spec §5.2 名实相符），文案须说明「无队列、无 core、无 idle 回收」。

- [ ] **Step 8: 跑测试确认通过 + 反地雷（A7）**

Run: Step 6 命令
Expected: PASS。
追加 `productionServerJsonValuesDoNotSerialize()`：用**生产 server.json 原值**
（core1/max1024/maxPending65535/taskTimeout5MINUTES）构造 ⇒ 并发提交下 `peakInFlight() > 1`。
**这是 spike 里打出 inFlight=1 的那组值**，必须证明地雷没了。

- [ ] **Step 9: 跑 server 既有全套**

Run: `mvn -q -pl server -am test`
Expected: PASS。既有红测按名字写进汇报。

- [ ] **Step 10: Commit**

```bash
git add server/src/main/java/io/mycat/mycatmysql/MycatVertxMySQLHandler.java \
        server/src/main/java/io/mycat/mycatmysql/ControlPlaneSql.java \
        server/src/main/java/io/mycat/commands/DefaultCommandHandler.java \
        server/src/main/java/io/mycat/MycatCore.java \
        server/src/test/java/io/mycat/mycatmysql/ControlPlaneSqlTest.java \
        server/src/test/java/io/mycat/FrontendAdmissionWiringTest.java
git commit -m "feat(I-PR-1): 5 处前端语句入口接准入闸 + 控制面 SQL 豁免 + MycatCore 配置真接线 + 忽略旋钮如实 WARN"
```

---

### Task 6: 活体矩阵 + 残留补证 + 全量护栏 + 收口回写

**Files:**
- Create: `ci/iopr1/run-live-matrix.sh`（活体探针驱动，`WS="ci/iopr1"`）
- Create: `ci/iopr1/LiveMatrixProbe.java`（饱和 / 序列 / 卡死 / 运维面 场景）
- Modify: `docs/superpowers/specs/2026-11-17-i-pr-3-vt-spike-report.md`（§8.1 裁定取代标注）
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`
- Modify: `docs/superpowers/specs/DEBT-QUEUE.md`
- Modify: `docs/superpowers/specs/2026-11-17-i-pr-1-ioexecutor-vt-design.md`（回填实测结果与双态标签）

**Interfaces:**
- Consumes: Task 1–5 全部产出
- Produces: **L1–L10** 与 R1–R5 的实测证据（入库、可复跑）；两板与本 spec 的收口回写

- [x] **Step 1: 重打 fat jar（改生产必重打）**

Run: `mvn -q -pl server -am package -DskipTests`
Expected: 产出 `server/target/server-1.22-release-jar-with-dependencies.jar`，时间戳新于 Task 5 提交。

- [x] **Step 2: 确认后端就绪**

Run: `docker exec m1-mysql-a mysqladmin ping -uroot -p123456 --silent`（对 `m1-mysql-b`、`m6-mariadb-a` 同）
Expected: 三个都 `mysqld is alive`。未起则先起，**不得跳过活体直接标绿**。

- [x] **Step 3: L1 零 pinning（本轮最重要单条断言）**

Run: 以 `-Djdk.tracePinnedThreads=full` 启动 jar，跑满负载 proxy（并发 ≥ 许可数），
日志落 `ci/iopr1/logs/l1-pinning.log`，然后 `grep -c '<== monitors' ci/iopr1/logs/l1-pinning.log`
Expected: **0**。
**命中处置**：若非 0，逐帧定位。**命中在 P0 三文件之外 ⇒ 静态清单对该站点定性错了 ⇒
升级为 P0 并换锁（回 Task 2 补做），不得放宽断言。** 每帧裁定记入 ledger。
机制须读准：JDK21 只在 **pinned 状态下 park** 时打印，纯 CPU 短临界区不触发
⇒ P1 40 处 / P2 约 78 处不预先换锁（spec §9 L1）。

- [x] **Step 4: L2/L3/L4/L7 —— 线程治理与饱和语义**

Run: `ci/iopr1/run-live-matrix.sh`（各场景独立日志）
Expected:
- **L2**：同等负载下平台线程数**不随并发线性增长**（对照 spike：cached C2048→1595，VT→27）。
- **L3**：把许可压到小值 + 高并发 ⇒ 客户端拿到 **MYCAT-3007** 干净 ERR，**连接不断**，随后语句成功。
- **L4**：饱和中主动断连 / abandon ⇒ Druid 活跃连接数**回落**，无泄漏。
- **L7**：饱和中心跳持续 ⇒ 后端不被误判死。

- [x] **Step 5: L5 序列自死锁（Review Focus #2）**

Run: 饱和 + **序列密集 INSERT** 工作负载（配 `SequenceMySQLGenerator` 的 prototype 序列），
设 **60s 硬超时**
Expected: **不得挂住**，取号持续前进，INSERT 持续成功。
这是「加界」类改动最典型的翻车点：无闸 VT 不会饿死，**有界准入闸会**。
若挂住 ⇒ 说明序列取号腿被误接闸或锁拓扑破坏 ⇒ 回 Task 5 修 `innerLegsStillUseExecuteBlocking` / Task 6 锁回归。`DefaultPrototypeGenerator` 无生产调用方，不得拿它冒充活体覆盖。

- [x] **Step 6: L6 许可回收（Review Focus #3）**

Run: 饱和 + 卡死查询（后端 `SELECT SLEEP(...)` 或锁等待），等 I-DEBT-84 deadline 触发
Expected: deadline 以 3013 界住客户端后，**许可被回收**，闸不单调收缩；
后续语句可正常准入。**若许可未回收 ⇒ 本票不得标完成**（spec §3.4 硬依赖）。

- [x] **Step 7: L8 运维面恢复（Review Focus #5）**

Run: 饱和中发 `/*mycat:cmd=showThreadInfo*/`、`killThread`、`interruptThread`（目标为 IO VT 的 id）
Expected:
- `showThreadInfo` 结果集**列出 IO VT**，NAME 带 `mycat-io-` 前缀，STATE/STACKTRACE 非空。
- `killThread` / `interruptThread` 的 **`affectedRows == 1`**。
  **不能只看 OK 包**——`HintHandler:161`/`:168` 无论命中与否都 `sendOk()`，失效是静默的。
- 控制面 SQL **在饱和中仍被受理**（豁免生效）。

- [x] **Step 8: L9 反地雷活体**

Run: 用**生产 server.json 原值**启动，跑并发负载
Expected: 有效并发 **> 1**（不是 spike 里 129.1 qps / inFlight=1 那种静默串行化）。

- [x] **Step 8b: L10 配置生效性活体（用户「必须可配置」条件的端到端证明）**

Run: 把 `server/src/main/resources/server.json` 的 `workerPool.maxPoolSize` 改 **3**、
`admitTimeoutMillis` 改 **50** ⇒ **重打 fat jar** ⇒ 起活体 ⇒ 4 条并发长查询
Expected:
- 第 4 条拿 **MYCAT-3007**（许可上界真等于 3，不是 1024）；前 3 条成功。
- 拒绝前等待量级 ≈ **50ms**（不是默认 200ms）。
- **跑完必须把 server.json 改回生产原值并重打 jar**，再跑 Step 8（L9）。
  否则 L9 用的是被污染的配置，反地雷断言失去意义。
**只测默认值证明不了配置通路**：`MycatCore:135` 若仍写死 `IOExecutor.DEFAULT`，默认值测试照样全绿。

- [x] **Step 9: R1–R5 残留补证（spike 明确留下的缺口）**

Run: 活体矩阵覆盖以下五项，各自独立日志：
- **R1** vertx-core **4.5.14** Future 链在 VT 上的行为（spike 用裸 JDK，未覆盖 vertx Future）。
- **R2** **DDL 型 JDBC**（`DbPlanManagerPersistorImpl` 13 处锁所在路径，经 `MycatRouterConfigOps:602`/`:684`）。
- **R3** 序列 **`SELECT … FOR UPDATE`**（`nextSql` 可配，spike 未覆盖行锁语义）。
- **R4** **HTTP custom-rule 腿**（`HttpCustomRuleFunction:65`，第三方阻塞 HTTP，spike 零覆盖）。
- **R5** `MySQLLogConsumer:75` 后台 JDBC INSERT 与前端争连接池的行为。
Expected: 五项均无挂住、无 pinning 命中、无数据竞争。**任一项失败 ⇒ 挂账并如实标注，不得伪绿。**

- [x] **Step 10: 全量既有护栏（不回退）**

Run: M1/M2/M3/M4 矩阵、I-DEBT-84 deadline 全套、旧 Java Connector 线 STAGE6 full-CRUD 矩阵、TLS 轴矩阵。
**长护栏用 Monitor persistent**（>10min；Bash run_in_background 的 600000ms 上限会静默杀）。
Expected: 全绿。任何红测**按名字**写进汇报与 ledger。
实测：Monitor `bbe5qorn9` 12/12 rc=0，`GUARD_RC=0`；失败/中断轮次单独保留，未拼成绿证。

- [x] **Step 11: 证据入库**

Run: `git add -f ci/iopr1/logs/*.log`（`.gitignore:5 = *.log` 会静默丢弃）+ 脚本 + TSV
Expected: `git status` 确认日志**真的进了暂存区**（此前 I-PR-3 就在这里翻过车）。
实测：最终 215 个授权文件已暂存；6 组归档清单共 134 条均对工作树与 Git index blob 双重校验通过，禁止路径/密钥命中/超 100 MiB 文件均为 0。
在 spec 里写明**归档例外说明**（原始 trace 是 L1 的决定性证据，必须可审计）。

- [x] **Step 12: 收口回写复核 + 实测回填（spec §12 五义务已于 spec 期落盘，此步是复核不是重做）**

**下列 1–5 与「另」项已在 spec 期执行完毕**（报告 §8.1 取代标注、两板裁定取代、30/15 订正、
三处订正补记、inventory §2 标题 4→3 文件）。此步职责：**逐条复核仍然准确**，
并把 **L1–L9 / R1–R5 的实测数字与双态标签**回填进 spec 与两板。原文如下（作为复核清单）：

1. `2026-11-17-i-pr-3-vt-spike-report.md` **§8.1**：标注裁定已被用户拍板取代
   （「先加界，VT 另立后续票」→ **「一步到位上 VT」**），**保留原文作为历史记录，不删**。
2. `PROGRESS.md`：I-PR-1 行 + M5 段 + 战略校准 + changelog，凡引旧裁定处标注取代；M5 余票数据实更新。
3. `DEBT-QUEUE.md`：仪表盘 M5 段 + §5 M5 五个子条目 + 下一个开工位，同上。
4. **两板凡「35 处 / 18 文件」订正为「30 处 / 15 文件」**。
5. **两板补记三处订正**：A3/A5 是 **COM_STMT_EXECUTE** 非管理命令；
   可拒绝集是 **5 处前端入口**非 19 处（**危险性订正**：按旧口径实现会直接嵌套死锁）；
   `DefaultPrototypeGenerator` 是**死代码机制样本，不得冒充活体证据**；活体序列靶已据源码订正为
   `SequenceMySQLGenerator`（实现 `SequenceHandler`，由 `AddSequenceTest` 配置并经真实后端取号）。
另：`2026-11-17-i-pr-3-pinning-inventory.md` **§2 标题仍写「共 4 文件 / 22 处」**（§2.5 已订正为 3）
⇒ 顺手把标题订正为 3 文件，消除自相矛盾。

- [x] **Step 13: 双态标签与诚实边界回填**

本 spec 与两板每格标 **本地✅ / CI⏳ / CI✅**。
**只有 CI 绿才是对外 Evidenced-Done。** CI 未跑或未绿 ⇒ 标 **CI⏳**，**宁挂账不伪绿**。
回填实测数字；**吞吐收益不可宣称**（spike 已撤回相关 qps 数字）。

- [x] **Step 14: Final review（fresh reviewer）**

用 Agent 工具按 `requesting-code-review/code-reviewer.md` 审全分支（`git merge-base` 到 HEAD），
以 review-package、spec、plan 与 ledger 的 Ruling 为输入；不得用作者自审替代。
Critical/Important ⇒ **一轮** fix pass，每处 RED→GREEN + 全套绿；Minor ⇒ ledger 挂账。
实测：fresh review 与 control-plane fresh review 均已完成；Important 修复后 targeted 37/37、driver 12/12、完整护栏 12/12；R5 资源角色 Minor 保留挂账。

- [x] **Step 15: Commit + push**

```bash
git add ci/iopr1 docs/superpowers/specs
git commit -m "test(I-PR-1): 活体矩阵 L1-L10 + 残留 R1-R5 补证 + 全量护栏 + 两板收口回写"
git push origin main
```
push 失败**自行重试**（GitHub 时不时连不上）。**严禁替换 origin 为任何代理地址。**
Expected: `git rev-parse HEAD` == `git rev-parse origin/main`，ahead=0。

实测（2026-11-25）：实现提交 `4237c17cc2784f01021b46044c6dcfac0d4c5dc4` 已推送，
本地/远端 `main` 一致；该提交触发 [run `45568270425`](https://github.com/lgscofield/Mycat2/actions/runs/45568270425)，
总结论 `success`，9 个实际 job 全 success，`full-suite`/`soak-g6` 按触发条件 skipped。
诚实边界：当前 workflow 未运行新增 server 定向测试；这些测试的可重跑执行证据仍以本地完整护栏归档为准。

---

## Self-Review

**1. Spec 覆盖**：G1→Task 4/5（A1/L3）；G2→Task 4 Step 1 `executeBlockingNeverRejectsWhenGateSaturated` + Task 5 Step 5 `innerLegsStillUseExecuteBlocking`；
G3→Task 3；G4→Task 2 + Task 6 L1；G5→Task 1（配置）/Task 3（count+发现）/Task 5（接线）；G6→Task 5 Step 8 + Task 6 L9。
spec §7 序列约束→Task 6 L5；§3.4 泄漏护栏→Task 6 L6；§6 错误码→Task 1；§4.1 预检→已执行（spec 内）；
§12 回写五义务→Task 6 Step 12。**无缺口。**

**2. 步骤扫描**：每步一个可判定动作。Task 2 Step 2 显式说明「互斥类测试此时应当已通过、RED 由结构断言提供」——
这是**诚实的 RED 设计**，不是把已通过当失败。Task 4 Step 6 的反向对照测试同理显式标注。

**3. 类型一致性**：`executeAdmitted(Handler<Promise<T>>)` → `Future<T>` 在 Task 4 Produces 与 Task 5 Consumes 一致；
`IOExecutorConfig.from(ThreadPoolExecutorConfig)` / `IOExecutor.create(IOExecutorConfig)` / `peakInFlight()` 三名在 Task 4 定义、Task 5/6 使用一致；
`ControlPlaneSql.isControlPlane(String)` 在 Task 5 内定义并使用；`ERR_IO_CONCURRENCY_LIMIT` Task 1 定义、Task 4 使用。

**4. Review Focus**：五条各配测试——#1→Task 3 Step 1 + Task 4 Step 1；#2→Task 6 L5；#3→Task 6 L6；
#4→Task 4 Step 5；#5→Task 5 Step 1 + Task 6 L8。**非空且逐条落地。**

**5. 比例**：plan 以签名/测试名/断言/命令为主，未抄实现体；长度与 spec 相称。

**已知留白（实现者需裁定并 ledger）**：
- Task 2 互斥测试的观测手段（真库 `SELECT … FOR UPDATE` vs 计数桩）取决于能否连上 `m1-mysql-a`；
  连不上则用真库以外的**可观测互斥**手段，**不得用 mock 断言 mock**。
- Task 5 `rejectionHappensBeforeAnyByteWritten` 的 fake session 构造方式，参照既有
  `ReceiverStatementDeadlineForceCloseTest` 的接线（同一模块已有先例）。
- ~~决定点 D2–D6 未拍板前不得开工~~ ⇒ **已于 2026-11-17 全部拍板**（见 Global Constraints 首条）。
  D3/D4 取建议默认值（200ms / 复用 `maxPoolSize`），**且必须可配置** ⇒
  Task 5 Step 5 的接线测试与 Task 6 新增 **L10** 都用**非默认值**（permits=3 / timeout=50）断言，
  不得只测默认值。