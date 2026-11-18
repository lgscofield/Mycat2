# I2b · force-close 物理 abort 升级 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把四腿共享 force-close 的 `abandonConnection` 从 Druid recycle（归还池、socket 不关）升级为物理 `abort(executor)`+`discardConnection`（硬杀 socket 解困冻结 worker + 归位池账），正常 `close()` recycle 不变。

**Architecture:** 在 `DefaultConnection` 新增自包含 `abort()`（CAS closeFlag → counter-- → `DruidPooledConnection.abort(Runnable::run)` 同步 forceClose → `DruidDataSource.discardConnection(physicalConn)` 归位 activeCount；非 Druid 回退 `JdbcUtils.close`）。`JdbcDatasourcePoolImpl` 匿名子类 `abandonConnection()` 改调 `defaultConnection.abort()`（替换 `close()`）。direct executor 保证 forceClose 先于 discard 的 isClosed 短路，避免冻结后端二次 close 阻塞。不改 `ConnectionManager` 接口、不改正常 `close()`、不动 vertx 路径。

**Tech Stack:** Java 21（LibericaJDK-21-Full）、Druid 1.2.28、mysql-connector-j、Vert.x、JUnit5 + Mockito 5.17.0（root dependencyManagement 管理，离线可解析）、Maven 离线 `-o`。

**Spec:** `docs/superpowers/specs/2026-11-13-i2b-force-close-physical-abort-design.md`（已拍板：候选 A / 全腿共享缝 / executing-plans inline）

## Global Constraints

- 改生产码必重打 fat jar 再跑活体（`server/target/server-1.22-release-jar-with-dependencies.jar`）。
- 下游模块测试验上游改动必带 `-am`（server 测试验 datasource 改动须 `-pl server -am`，否则跑 stale datasource classes 出假 RED/GREEN）。
- 长护栏（>10min，全量约 25-30min）用 Monitor persistent + 日志文件，不用 Bash run_in_background（10min 上限静默杀进程）。
- `JAVA_HOME="D:/Program Files/BellSoft/LibericaJDK-21-Full"`；mvn 离线 `-o`。
- 正常 `close()`（recycle）路径零改动；vertx 路径（`VertxMycatConnectionPool:299`）不动。
- abort 用 direct executor `Runnable::run`（同步 forceClose 先于 discard 的 isClosed 短路）；非 Druid 连接回退 `JdbcUtils.close`（不崩）。
- git commit / push 须用户当次显式拍板，一次批准不延续；本 plan 各 Task 的 commit 步骤落地后统一请示，不擅自 push。
- 活体真后端：m1-mysql-a:3306 / m1-mysql-b:3307（MySQL 8.0.46），docker pause 配方承 I-DEBT-84 §8.4。

## Review Focus

1. **worker/timer race**：query worker 被 forceClose 解困后自身 `close()` 与 abandon 线程 `abort()` 并发 ⇒ closeFlag CAS 须单方生效，无双重释放/无 IllegalStateException。（Task1 `abortIsIdempotentViaCloseFlag` + Task3 活体 race 负控）
2. **多轮冻结池耗尽**：abort 漏 discard ⇒ activeCount 泄漏 ⇒ 第 N+1 轮取不到连接。（Task1 `abortDiscardsConnectionForPoolAccounting` + Task3 活体多轮冻结）
3. **discard 二次 close 阻塞**：abort 未同步完成时 discard 见 isClosed=false ⇒ 在冻结后端 close 阻塞 ⇒ direct executor 同步排序须保证 isClosed 短路。（Task2 seam 锁 + Task3 活体坐实 timer/abandon 线程不被拖住）
4. **非 Druid 连接 abandon**：mongodb/假件连接非 DruidPooledConnection ⇒ 须回退不抛 ClassCastException。（Task1 `nonDruidConnectionFallsBackToClose`）
5. **正常 close 误杀**：健康连接正常释放若误走 abort ⇒ 物理杀 socket、池不复用 ⇒ close() 须仍 recycle。（Task2 `normalCloseDoesNotAbort` + 既有 Receiver*DeadlineTest 护栏）

---

### Task 1: DefaultConnection.abort() 物理弃用机制（datasource）

**Files:**
- Modify: `datasource/pom.xml`（加 mockito-core test 依赖，版本由 root 管理）
- Modify: `datasource/src/main/java/io/mycat/datasource/jdbc/datasource/DefaultConnection.java`（close():114 后新增 abort()；补 imports）
- Test: `datasource/src/test/java/io/mycat/datasource/jdbc/datasource/DefaultConnectionAbortTest.java`（create）

**Interfaces:**
- Consumes: `JdbcDataSource.counter`（package-private AtomicInteger，同包可访问）、`JdbcDataSource.getDataSource()`→`DataSource`（实为 DruidDataSource）、`DefaultConnection.connection`（final Connection，实为 DruidPooledConnection）、`closeFlag`（AtomicBoolean:44）。Druid API：`DruidPooledConnection.abort(java.util.concurrent.Executor)`、`DruidPooledConnection.getConnection()`→物理 Connection、`DruidDataSource.discardConnection(java.sql.Connection)`→boolean。
- Produces: `public void DefaultConnection.abort()`——CAS closeFlag 单方生效；counter--；Druid 连接走 `abort(Runnable::run)`+`discardConnection(getConnection())`；非 Druid 回退 `JdbcUtils.close(connection)`；异常吞掉记 LOGGER.error（对齐 close():111-113）。Task 2 消费此方法。

- [ ] **Step 1: 加 mockito test 依赖**

`datasource/pom.xml` `<dependencies>` 末尾加（版本不写死，root dependencyManagement `${mockito.version}=5.17.0` 提供，对齐 va/server）：
```xml
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-core</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 2: 写失败测试 `DefaultConnectionAbortTest`**

四个测试，Mockito mock `DruidPooledConnection pc` / `DruidDataSource ds` / 物理 `Connection raw`；`JdbcDataSource jdbcDs = new JdbcDataSource(cfg, ds)`（cfg.setName("ds0")，counter 先 getAndIncrement 到 1）；`when(pc.getConnection()).thenReturn(raw)`；`DefaultConnection dc = new DefaultConnection(pc, jdbcDs, true, Connection.TRANSACTION_REPEATABLE_READ, mock(ConnectionManager.class))`：

```java
@Test void abortInvokesPhysicalAbortNotRecycle() throws Exception {
    dc.abort();
    verify(pc).abort(any(Executor.class));
    verify(pc, never()).close();
    verify(pc, never()).recycle();
}
@Test void abortDiscardsConnectionForPoolAccounting() throws Exception {
    dc.abort();
    verify(ds).discardConnection(raw);
    assertEquals(0, jdbcDs.getUsedCount());   // counter 1→0
}
@Test void abortIsIdempotentViaCloseFlag() throws Exception {
    dc.abort(); dc.abort(); dc.close();
    verify(pc, times(1)).abort(any(Executor.class));
    verify(pc, never()).close();              // closeFlag 已置，close() no-op
}
@Test void nonDruidConnectionFallsBackToClose() throws Exception {
    Connection plain = mock(Connection.class);  // 非 DruidPooledConnection
    DefaultConnection dc2 = new DefaultConnection(plain, jdbcDs, true, Connection.TRANSACTION_REPEATABLE_READ, mock(ConnectionManager.class));
    assertDoesNotThrow(dc2::abort);
    verify(plain).close();                      // JdbcUtils.close 回退
    verify(plain, never()).abort(any());
}
```

- [ ] **Step 3: 跑测试验 RED**

Run: `mvn -o -pl datasource -am test -Dtest=DefaultConnectionAbortTest`
Expected: 编译失败 `cannot find symbol: method abort()`（DefaultConnection 尚无 abort）。

- [ ] **Step 4: 实现 `DefaultConnection.abort()`**

close():114 后新增。imports 补 `com.alibaba.druid.pool.DruidPooledConnection`、`com.alibaba.druid.pool.DruidDataSource`、`com.alibaba.druid.util.JdbcUtils`、`javax.sql.DataSource`。签名 `public void abort()`，body：
```java
try {
    if (closeFlag.compareAndSet(false, true)) {
        jdbcDataSource.counter.decrementAndGet();
        if (connection instanceof DruidPooledConnection) {
            DruidPooledConnection pc = (DruidPooledConnection) connection;
            pc.abort(Runnable::run);                       // 同步 forceClose，先于 discard
            DataSource ds = jdbcDataSource.getDataSource();
            if (ds instanceof DruidDataSource) {
                ((DruidDataSource) ds).discardConnection(pc.getConnection());  // isClosed 短路 + activeCount--
            }
        } else {
            JdbcUtils.close(connection);                   // 非 Druid 回退
        }
    }
} catch (Exception e) {
    LOGGER.error("", e);
}
```

- [ ] **Step 5: 跑测试验 GREEN**

Run: `mvn -o -pl datasource -am test -Dtest=DefaultConnectionAbortTest`
Expected: PASS 4/4。

- [ ] **Step 6: Commit（请示后）**

```bash
git add datasource/pom.xml datasource/src/main/java/io/mycat/datasource/jdbc/datasource/DefaultConnection.java datasource/src/test/java/io/mycat/datasource/jdbc/datasource/DefaultConnectionAbortTest.java
git commit -m "feat(I2b): DefaultConnection.abort 物理弃用(DruidPooledConnection.abort+discardConnection 归位池账,非Druid回退)"
```

---

### Task 2: JdbcDatasourcePoolImpl.abandonConnection 接 abort（server 共享缝）

**Files:**
- Modify: `server/src/main/java/io/mycat/commands/JdbcDatasourcePoolImpl.java:102`（`defaultConnection.close()` → `defaultConnection.abort()`，仅 abandonConnection 内；close():84 不动）
- Test: `server/src/test/java/io/mycat/commands/JdbcDatasourcePoolAbandonTest.java`（create）

**Interfaces:**
- Consumes: Task 1 `DefaultConnection.abort()`；`JdbcDatasourcePoolImpl(String targetName)` 构造；`getConnection()`→`Future<NewMycatConnection>`（经 `MetaClusterCurrent.wrapper(JdbcConnectionManager.class).getConnection(targetName)` 取 DefaultConnection，包 `RemoveAbandonedTimeoutConnectionImpl(ThreadMycatConnectionImplWrapper(stat, anon))`）；`ThreadMycatConnectionImplWrapper.abandonConnection:165` 经 `IOExecutor.executeBlocking` 异步跑 anon.abandonConnection；`RemoveAbandonedTimeoutConnectionImpl.abandonConnection:114` timeId==null（cfg 不开 removeAbandoned）时不需 Vertx。
- Produces: 四腿共享 force-close（ReceiverImpl.forceCloseInFlight:423/442 → wrapper → anon.abandonConnection）落物理 abort。Task 3 活体验证此缝。

- [ ] **Step 1: 写失败测试 `JdbcDatasourcePoolAbandonTest`**

setup 承 `ReceiverStatementDeadlineForceCloseTest`：保存/恢复 `MetaClusterCurrent.copyContext()`；mock `JdbcConnectionManager manager`，`when(manager.getConnection("ds0")).thenReturn(dc)`（dc = DefaultConnection(mock pc, new JdbcDataSource(cfg, mock ds), ...)，`when(pc.getConnection()).thenReturn(raw)`，cfg 不开 removeAbandoned）；`MetaClusterCurrent.register(JdbcConnectionManager.class, manager)` + `register(IOExecutor.class, IOExecutor.DEFAULT)`。

```java
@Test void abandonRoutesToPhysicalAbort() throws Exception {
    NewMycatConnection w = new JdbcDatasourcePoolImpl("ds0").getConnection().result();
    w.abandonConnection();   // 经 IOExecutor.executeBlocking 异步
    verify(pc, timeout(3000)).abort(any(Executor.class));
    verify(ds, timeout(3000)).discardConnection(raw);
    verify(pc, never()).close();
}
@Test void normalCloseDoesNotAbort() throws Exception {
    NewMycatConnection w = new JdbcDatasourcePoolImpl("ds0").getConnection().result();
    w.close().result();      // 正常释放仍走 defaultConnection.close()→manager.closeConnection
    verify(manager, timeout(3000)).closeConnection(dc);
    verify(pc, never()).abort(any(Executor.class));
}
```

- [ ] **Step 2: 跑测试验 RED**

Run: `mvn -o -pl server -am test -Dtest=JdbcDatasourcePoolAbandonTest`
Expected: `abandonRoutesToPhysicalAbort` FAIL（当前 abandon→close→recycle，`verify(pc).abort`  Wanted but not invoked）；`normalCloseDoesNotAbort` PASS（close 现状即 recycle）。`-am` 必带以重建 Task 1 datasource。

- [ ] **Step 3: 改 abandonConnection:102**

`JdbcDatasourcePoolImpl.java:102` 匿名子类 `abandonConnection()` 内 `defaultConnection.close();` → `defaultConnection.abort();`。close():84 的 `defaultConnection.close()` 保持不变。

- [ ] **Step 4: 跑测试验 GREEN**

Run: `mvn -o -pl server -am test -Dtest=JdbcDatasourcePoolAbandonTest`
Expected: PASS 2/2。

- [ ] **Step 5: 既有 force-close 护栏不回退**

Run: `mvn -o -pl server -am test -Dtest=ReceiverStatementDeadlineForceCloseTest,ReceiverCommitLegDeadlineTest,ReceiverTransactionLegDeadlineTest`
Expected: PASS（这些 mock 在 NewMycatConnection 层、缝之上，abandonConnection 仍被调，接线契约不变）。

- [ ] **Step 6: Commit（请示后）**

```bash
git add server/src/main/java/io/mycat/commands/JdbcDatasourcePoolImpl.java server/src/test/java/io/mycat/commands/JdbcDatasourcePoolAbandonTest.java
git commit -m "feat(I2b): JdbcDatasourcePoolImpl.abandonConnection 接物理 abort(四腿共享 force-close 缝,正常 close recycle 不变)"
```

---

### Task 3: 活体 worker-解困矩阵 + 重打 jar + 全量护栏 + 收口

**Files:**
- Create（用完即删）: 活体探针（承 I-DEBT-84 §8.4 docker pause 配方 / commit 腿活体探针 precedent）
- Modify: `docs/superpowers/specs/DEBT-QUEUE.md`（I2b 行收口：本地✅/CI⏳）
- Modify: `docs/superpowers/specs/2026-11-05-i-debt-84-statement-deadline-design.md` §8.7（I2b 遗留销账，引本 spec/plan）

**Interfaces:**
- Consumes: Task 1+2 生产码（重打 fat jar 后）；真后端 m1-mysql-a:3306/m1-mysql-b:3307；docker pause/unpause；`IOExecutor.findAllThreads()`（测真实 worker 线程，**非 `count()`**——count 只跟踪 executeBlocking 提交瞬时、不跟踪阻塞时长）；后端 `SHOW PROCESSLIST`（孤儿会话）。
- Produces: 活体证据（worker 解困/池账归位/无孤儿/多轮不耗尽/变异负控）+ 两板收口。

- [ ] **Step 1: 重打 fat jar**

Run: `mvn -o -pl server -am package -DskipTests`（Monitor persistent + 日志，若 >10min）
Expected: BUILD SUCCESS；`server/target/server-1.22-release-jar-with-dependencies.jar` 更新（记录 bytes）。

- [ ] **Step 2: 活体① 单 DS commit 冻结 ⇒ worker 真解困**

起代理（`.superpowers/start-server.ps1`，persona=MYSQL_5_7，port 8066，Arrow --add-opens）；客户端开事务写 ds1 后 commit；commit 期 `docker pause m1-mysql-a`。测：客户端 3013 有界（deadline+ε）；**冻结前后 jstack/`IOExecutor.findAllThreads()` 对比：阻塞在 socketRead0 的 query worker 线程在 force-close 后消失/回落**；后端 `SHOW PROCESSLIST` 无该会话孤儿。
Expected: 3013 有界 + worker 线程回落 + 无孤儿（三者齐备，非仅客户端有界）。

- [ ] **Step 3: 活体② 多轮冻结不耗尽池 + ③ 解冻恢复**

连续 N（≥3）轮 pause→force-close→unpause；每轮后 `DruidDataSource.getActiveCount()`（经代理监控/日志）归位、池可复用；第 N+1 轮正常取连接执行成功；解冻后代理恢复服务、后端会话清理。
Expected: activeCount 每轮归位、第 N+1 轮成功、无残留阻塞 worker。

- [ ] **Step 4: 活体④ race 负控 + ⑤ 变异负控**

race：高并发下 force-close 与 worker 解困并发 ⇒ 无双重释放/无 IllegalStateException（日志无 dup close 异常）。变异：临时把 abandonConnection 退回 close()（recycle）重打 jar ⇒ 活体① 的「worker 线程回落」**必红**（证明 abort 是解困真因）；验毕还原。
Expected: race 无异常；变异负控 worker 不回落（红）⇒ 还原后绿。

- [ ] **Step 5: 全量护栏（Monitor persistent + 日志）**

Run: `pwsh -NoProfile -File ci/run-example-guard.ps1 -Persona MYSQL_8_0 > target/ci/guard-i2b.log 2>&1; echo DONE`（Monitor persistent；前置 Docker Desktop + m1-mysql-a/b Up + max_connections 1000）
Expected: 日志 `RESULT=PASS`（va/proxy/server/example XA 簇全绿，Skipped 承前）。结果以日志 RESULT/GUARD_EXIT 行为准。

- [ ] **Step 6: 收口回写两板 + 请示 commit**

DEBT-QUEUE I2b 行：取证纠正（recycle≠abort）+ A 落地 + 活体 worker 解困绿 + 护栏绿（本地✅/CI⏳，push 后升 CI✅）。I-DEBT-84 §8.7 I2b 遗留：销账引本 spec/plan + 活体证据。汇报「未 commit/push，待拍板」。
Expected: 两板据实回写；用户拍板后 commit（push 另行显式批准）。

---

## Self-Review

**1. Spec coverage：** §2 根因→Task1/2 修复缝；§3.2 候选 A→Task1 abort()+Task2 接线；§4 R1 池账→Task1②+Task3②；R2 race→Task1③+Task3④；R3 孤儿→Task3①；R4 全腿 blast→Task2 共享缝+Step5 护栏；R5 executor→direct Runnable::run（Global Constraints）+Task3 坐实；R6 二次 close→direct 同步排序+Task3①；R7 非 Druid→Task1④；§5 活体矩阵→Task3①-⑤；§5.6 确定性单测→Task1/2。全覆盖。

**2. Step scan：** 每步单一动作；test 步给名+断言；code 步给签名+关键 body（abort 算法非签名可定，给 body）；verify 步给命令+Expected。无 TBD。

**3. Type consistency：** `DefaultConnection.abort()`（Task1 Produces）= Task2 Consumes = Task2 Step3 调用，一致。`discardConnection(pc.getConnection())` 参数物理 Connection，与 druid API 一致。`Runnable::run` 作 Executor（函数式接口 Executor.execute(Runnable)），一致。

**4. Review Focus：** 5 项各归 owning task 测试（见 Review Focus 括注）。

**5. Proportion：** plan ~180 行 vs spec ~150 行，相当；body 仅 abort 算法与测试断言，非程序转录。