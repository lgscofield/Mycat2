# I-DEBT-30（面①）服务端流式查询错误必达终结 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Mycat 每一条流式查询失败都必达地、协议正确地终结——列头未上线发合法 ERR 包（保留现状），列头已上线则关闭前端连接（对齐真 MySQL），生产侧兜住逃逸异常并防 double-terminal，注册全局 RxJava 错误兜底不再拖垮 8066。

**Architecture:** 三处正交改动：① server `ReceiverImpl.MysqlPayloadObjectObserver` 依 `resultStarted` 分流终态（关连接 vs tryFail→合法 ERR）；② hbt `VertxExecuter` 两个 `Observable.create` 生产侧包 try/catch + `AtomicBoolean` 单次终态门；③ 新增 `MycatRxJavaErrorHandler`（server）+ 在 `NativeMycatServer.start()`（proxy）注册 `RxJavaPlugins.setErrorHandler`。成功路径零改；顶层 recover（`CommandResolver`/`MycatVertxMySQLHandler`）不改——关连接后其 `channel().isConnected()` 守卫自动让 ERR 补发变 no-op。

**Tech Stack:** Java 21、Vert.x `io.vertx.core.Promise/Future`、RxJava3 `io.reactivex.rxjava3`、JUnit 5、Mockito 5.16.1（version 由根 pom `dependencyManagement` 管理，需在 server/hbt 各自声明为 test 依赖）。

**Spec:** `docs/superpowers/specs/2026-10-08-i-debt-30-streaming-error-termination-design.md`

## Global Constraints

- Evidenced-Done 才提交、不假修、保护 ~28 绿基线。
- 提交前必过定向新测 + `mvn -o test -pl hbt,router` 全量零回归。
- 重编前停 8066；`mvn` 带 `JAVA_HOME=D:\Program Files\BellSoft\LibericaJDK-21-Full`；跳测试用 `'-Dmaven.test.skip=true'`（单引号）；PowerShell 无 `cd /d`（用 `Set-Location`）、无 `grep`/`tail`、每个含 `.` 的 `-D` 参数整体加引号。
- 面②（JOIN 执行 `columnDefinition is null` NPE 本身）不在本计划；本计划测试用合成错误触发，不依赖活体 8066、不复现该 NPE。
- 全程仅在 feature 分支 `feat/i-debt-30-streaming-error` 提交；合回 main / push 归用户拍板。

## Review Focus

（spec 隐含、但成功路径测试不覆盖、最可能咬用户的失败面——逐条钉到 owning task 的测试）

1. **列头前错不得关连接**：连接获取/预编译在发出列头前失败 → 客户端应拿到具体错误消息、会话存活（护现状，防误伤既有能工作的报错路径）。→ Task 1 `onErrorBeforeColumnDefDoesNotCloseConnection`。
2. **列头后错必须关连接且不发裸 ERR**：已刷列头后中途崩 → 必须关闭前端连接，绝不往"读行态"连接补 `0xFF`（本债核心）。→ Task 1 `onErrorAfterColumnDefClosesConnection`。
3. **生产侧 onError 恰好一次**：同步逃逸异常 + 下游写失败并存时，emitter 终态只放行一次（防 double-terminal → Undeliverable）。→ Task 2 `producerRoutesEscapeToOnErrorExactlyOnce`。
4. **全局 handler 绝不 rethrow**：喂 `UndeliverableException` → 解包记日志、不抛回线程（否则杀 Vert.x worker = 拖垮 8066）。→ Task 3 `handlerNeverRethrowsUndeliverable`。
5. **成功终结路径不变**：`onComplete` 仍写 EOF/OK 且 `promise.tryComplete`——`resultStarted` 新分支不得改变正常完成语义。→ Task 1 `onCompleteStillTerminatesNormally`。

## File Structure

- Modify `server/src/main/java/io/mycat/commands/ReceiverImpl.java`（observer 终态策略）。
- Modify `hbt/src/main/java/io/mycat/vertx/VertxExecuter.java`（生产侧加固，两个 `Observable.create`）。
- Create `server/src/main/java/io/mycat/rx/MycatRxJavaErrorHandler.java`（全局兜底：`createHandler()` + `install()`）。
- Modify `proxy/src/main/java/io/mycat/NativeMycatServer.java`（`start()` 内注册兜底）。
- Modify `server/pom.xml`、`hbt/pom.xml`（加 `mockito-core` test 依赖）。
- Create tests：`server/.../commands/MysqlPayloadObjectObserverErrorTest.java`、`server/.../rx/MycatRxJavaErrorHandlerTest.java`、`hbt/.../vertx/StreamingProducerErrorTest.java`。

---

### Task 1: observer 终态分流（列头前发 ERR / 列头后关连接）

**Files:**
- Modify: `server/src/main/java/io/mycat/commands/ReceiverImpl.java`（`MysqlPayloadObjectObserver`，L291-357）
- Modify: `server/pom.xml`（加 `mockito-core` test 依赖，version 由根 pom 管理，不写死）
- Test: `server/src/test/java/io/mycat/commands/MysqlPayloadObjectObserverErrorTest.java`

**Interfaces:**
- Consumes：`MySQLServerSession.close(boolean, String)`（proxy 接口 L242）；`MySQLColumnDef.of(MycatRowMetaData)`（ReceiverImpl L382 已在用）；`ResultSetBuilder.create().addColumnInfo(...).build().getMetaData()` 造 1 列元数据。
- Produces：`MysqlPayloadObjectObserver` 新增 `private boolean resultStarted`，`onError` 依其分流；构造签名不变 `(Promise<Void>, boolean moreResultSet, boolean binary, MySQLServerSession)`。

- [ ] **Step 1: 写失败测试**（Mockito mock `MySQLServerSession` + `Disposable`，真实 observer）

```java
package io.mycat.commands;
// JUnit5 + Mockito；promise = io.vertx.core.Promise.promise()
class MysqlPayloadObjectObserverErrorTest {
  MycatRowMetaData oneColMeta() {
    return ResultSetBuilder.create().addColumnInfo("", "", "c", java.sql.JDBCType.INTEGER).build().getMetaData();
  }
  @Test void onErrorBeforeColumnDefDoesNotCloseConnection() {
    MySQLServerSession session = mock(MySQLServerSession.class);
    Promise<Void> promise = Promise.promise();
    var ob = new ReceiverImpl.MysqlPayloadObjectObserver(promise, false, false, session);
    ob.onSubscribe(mock(io.reactivex.rxjava3.disposables.Disposable.class));
    ob.onError(new RuntimeException("boom"));               // 未发列头
    verify(session, never()).close(anyBoolean(), any());      // 不关连接
    assertTrue(promise.future().failed());                    // 交由顶层 recover 发合法 ERR
  }
  @Test void onErrorAfterColumnDefClosesConnection() {
    MySQLServerSession session = mock(MySQLServerSession.class);
    Promise<Void> promise = Promise.promise();
    var ob = new ReceiverImpl.MysqlPayloadObjectObserver(promise, false, false, session);
    ob.onSubscribe(mock(io.reactivex.rxjava3.disposables.Disposable.class));
    ob.onNext(MySQLColumnDef.of(oneColMeta()));               // 置 resultStarted=true
    ob.onError(new RuntimeException("mid-stream"));
    verify(session, times(1)).close(eq(false), any());        // 已发列头 → 关前端连接
  }
  @Test void onCompleteStillTerminatesNormally() {
    // mock session + dataContext/transactionSession 链（closeStatementState 返回 succeededFuture）
    // ob.onNext(ColumnDef); ob.onComplete();
    // verify(session).writeRowEndPacket(anyBoolean(), anyBoolean()); 且 promise.future().succeeded()（或 eventual）
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"; mvn -o test -pl server '-Dtest=MysqlPayloadObjectObserverErrorTest'`
Expected: 编译失败（`MysqlPayloadObjectObserver` 构造/无 `resultStarted` 行为不符）或 `onErrorAfterColumnDefClosesConnection` FAIL（现 `onError` 不调 `close`）。若 server 无 mockito 依赖先报 `package org.mockito does not exist`→ 属预期 RED。

- [ ] **Step 3: 加 mockito test 依赖到 `server/pom.xml`**

在 `<dependencies>` 内加（不写 version，根 pom 已管）：
```xml
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-core</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 4: 实现 observer 终态分流**

在 `MysqlPayloadObjectObserver`（L291）：
- 新增字段 `private boolean resultStarted = false;`
- `onNext`（L322 的 `instanceof MySQLColumnDef` 分支）：在 `session.writeColumnCount(...)` **之前**置 `this.resultStarted = true;`（列数包即客户端进入读行态的提交点）。
- `onError`（L341-345）改为：
```java
@Override
public void onError(@NonNull Throwable throwable) {
    disposable.dispose();
    if (resultStarted) {
        // 列头已上线：不能再发裸 ERR（协议非法），强制关闭前端连接
        // → 客户端得 "Lost connection during query"；顶层 recover 因 channel 已断变 no-op
        String msg = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
        session.close(false, msg);
    }
    promise.tryFail(throwable);
}
```
`onComplete` 不改。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o test -pl server '-Dtest=MysqlPayloadObjectObserverErrorTest'`
Expected: PASS（3 测）。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/io/mycat/commands/ReceiverImpl.java server/pom.xml server/src/test/java/io/mycat/commands/MysqlPayloadObjectObserverErrorTest.java
git commit -m "fix(i-debt-30): observer 列头后错误关前端连接、列头前保留合法ERR 终态分流"
```

---

### Task 2: 生产侧加固（VertxExecuter 逃逸异常必达 onError + 单次终态门）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/vertx/VertxExecuter.java`（`runQueryOutputAsMysqlPayloadObject` L821-855、`runQuery` L865-905）
- Modify: `hbt/pom.xml`（加 `mockito-core` test 依赖，同 Task 1 Step 3）
- Test: `hbt/src/test/java/io/mycat/vertx/StreamingProducerErrorTest.java`

**Interfaces:**
- Consumes：`Future<NewMycatConnection>`（Vert.x）、`NewMycatConnection.prepareQuery(String, List<Object>, MysqlCollector)`、`Observable.create`。
- Produces：两处 `Observable.create` 内 collector 用 `AtomicBoolean terminated` 单门；`onSuccess` 回调体外层 try/catch → `emitter.onError`。方法签名不变。

- [ ] **Step 1: 写失败测试**（fake/ mock `NewMycatConnection`，`prepareQuery` 同步抛错）

```java
package io.mycat.vertx;
// mock NewMycatConnection：prepareQuery(any,any,any) 同步 thenThrow(new RuntimeException("escape"))
// connectionFuture = Future.succeededFuture(mockConn)
// Observable<MysqlPayloadObject> obs = VertxExecuter.runQueryOutputAsMysqlPayloadObject(connectionFuture, "sql", List.of());
// java.util.concurrent.atomic.AtomicInteger errCount = new AtomicInteger();
// obs.subscribe(x -> {}, e -> errCount.incrementAndGet(), () -> {});
// assertEquals(1, errCount.get());   // 逃逸异常须被路由成恰好一次 onError
class StreamingProducerErrorTest {
  @Test void producerRoutesEscapeToOnErrorExactlyOnce() {
    NewMycatConnection conn = mock(NewMycatConnection.class);
    org.mockito.Mockito.doThrow(new RuntimeException("escape"))
        .when(conn).prepareQuery(any(), any(), any());
    Future<NewMycatConnection> f = Future.succeededFuture(conn);
    AtomicInteger err = new AtomicInteger();
    VertxExecuter.runQueryOutputAsMysqlPayloadObject(f, "sql", java.util.Collections.emptyList())
        .subscribe(o -> {}, t -> err.incrementAndGet(), () -> {});
    org.junit.jupiter.api.Assertions.assertEquals(1, err.get());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl hbt '-Dtest=StreamingProducerErrorTest'`
Expected: FAIL — 现 `onSuccess(conn -> conn.prepareQuery(...))` 体外层无 try/catch，同步抛错逃逸成 `UndeliverableException`（下游收不到 onError，`err.get()==0` 或抛回订阅者）。

- [ ] **Step 3: 加 mockito test 依赖到 `hbt/pom.xml`**（同 Task 1 Step 3 片段）

- [ ] **Step 4: 实现生产侧加固**

`runQueryOutputAsMysqlPayloadObject`（L821-855）与 `runQuery`（L865-905）各自：
- collector 内加 `final AtomicBoolean terminated = new AtomicBoolean(false);`（放在 `Observable.create` lambda 作用域，供 onFailure 共用）。
- `onColumnDef`/`onRow`：保持 `emitter.onNext(...)`（`onRow` 已有 try/catch 者保留）。
- `onComplete`：`if (terminated.compareAndSet(false, true)) emitter.onComplete();`
- `onError`：`if (terminated.compareAndSet(false, true)) emitter.onError(e);`
- `connectionFuture.onSuccess` 回调体包 try/catch：
```java
connectionFuture.onSuccess(connection -> {
    try {
        connection.prepareQuery(sql, values, collector);
    } catch (Throwable t) {
        if (terminated.compareAndSet(false, true)) emitter.onError(t);
    }
});
connectionFuture.onFailure(i -> { if (terminated.compareAndSet(false, true)) emitter.onError(i); });
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o test -pl hbt '-Dtest=StreamingProducerErrorTest'`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add hbt/src/main/java/io/mycat/vertx/VertxExecuter.java hbt/pom.xml hbt/src/test/java/io/mycat/vertx/StreamingProducerErrorTest.java
git commit -m "fix(i-debt-30): 流式生产侧兜逃逸异常必达 onError 且单次终态防 Undeliverable"
```

---

### Task 3: 全局 RxJava 错误兜底（不杀 worker）+ 启动注册

**Files:**
- Create: `server/src/main/java/io/mycat/rx/MycatRxJavaErrorHandler.java`
- Modify: `proxy/src/main/java/io/mycat/NativeMycatServer.java`（`start()` L68-73）
- Test: `server/src/test/java/io/mycat/rx/MycatRxJavaErrorHandlerTest.java`

**Interfaces:**
- Consumes：`io.reactivex.rxjava3.plugins.RxJavaPlugins.setErrorHandler(Consumer<Throwable>)`、`io.reactivex.rxjava3.exceptions.UndeliverableException`。
- Produces：`MycatRxJavaErrorHandler.createHandler()` 返 `io.reactivex.rxjava3.functions.Consumer<Throwable>`；`MycatRxJavaErrorHandler.install()` 调 `RxJavaPlugins.setErrorHandler(createHandler())`。

- [ ] **Step 1: 写失败测试**（直接驱动 handler 函数）

```java
package io.mycat.rx;
class MycatRxJavaErrorHandlerTest {
  @Test void handlerNeverRethrowsUndeliverable() {
    var h = MycatRxJavaErrorHandler.createHandler();
    // 不可 rethrow：任何 accept 均不得抛出
    h.accept(new io.reactivex.rxjava3.exceptions.UndeliverableException(new RuntimeException("x")));
    h.accept(new RuntimeException("plain"));   // 也应吞并记录，不抛回线程
  }
}
```
（类尚不存在 → 编译失败 = RED。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl server '-Dtest=MycatRxJavaErrorHandlerTest'`
Expected: FAIL（`MycatRxJavaErrorHandler` 未定义）。

- [ ] **Step 3: 实现 `MycatRxJavaErrorHandler`**

```java
package io.mycat.rx;
import io.reactivex.rxjava3.core.RxJava; // 或 io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.functions.Consumer;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import java.io.IOException; import java.net.SocketException;
import java.util.concurrent.TimeoutException;
public final class MycatRxJavaErrorHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(MycatRxJavaErrorHandler.class);
  private MycatRxJavaErrorHandler() {}
  public static Consumer<Throwable> createHandler() {
    return throwable -> {
      if (throwable instanceof UndeliverableException ue) {
        Throwable cause = ue.getCause();
        // 客户端断连/取消类：正常噪声，忽略（不刷错、更不抛）
        if (cause instanceof IOException || cause instanceof SocketException
            || cause instanceof InterruptedException || cause instanceof TimeoutException) {
          if (LOGGER.isDebugEnabled()) LOGGER.debug("undeliverable (client gone/cancel) ignored", cause);
          return;
        }
        LOGGER.warn("undeliverable rx error", cause);
        return;
      }
      if (throwable instanceof io.reactivex.rxjava3.exceptions.CompositeException ce) {
        for (Throwable t : ce.getExceptions()) LOGGER.warn("composite rx error", t);
        return;
      }
      LOGGER.warn("rx error (not deliverable)", throwable);  // 绝不 rethrow
    };
  }
  public static void install() {
    io.reactivex.rxjava3.plugins.RxJavaPlugins.setErrorHandler(createHandler());
  }
}
```

- [ ] **Step 4: 在 `NativeMycatServer.start()` 注册**

`start()`（L68-73）内、`startProxy(...)` 之前加：`io.mycat.rx.MycatRxJavaErrorHandler.install();`

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o test -pl server '-Dtest=MycatRxJavaErrorHandlerTest'`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/io/mycat/rx/MycatRxJavaErrorHandler.java proxy/src/main/java/io/mycat/NativeMycatServer.java server/src/test/java/io/mycat/rx/MycatRxJavaErrorHandlerTest.java
git commit -m "fix(i-debt-30): 注册全局 RxJava 错误兜底，Undeliverable 记日志不 rethrow 免杀 worker"
```

---

### Task 4: 集成编译 + 全量护栏回归

**Files:**
- 无源码改动（验证任务）。

- [ ] **Step 1: 重编 hbt + server（shade fat jar）**

Run（先停 8066）：
```
$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
mvn -B -o -pl hbt -am install '-Dmaven.test.skip=true'
mvn -B -o -pl server -am package '-Dmaven.test.skip=true'
mvn -B -o -pl proxy -am install '-Dmaven.test.skip=true'
```
Expected: BUILD SUCCESS（三处）。

- [ ] **Step 2: 全量回归护栏**

Run: `mvn -o test -pl hbt,router`
Expected: hbt、router 全绿、零回归（对齐当前基线 hbt 64 / router 15；server 新测另计）。

- [ ] **Step 3: 定向新测复跑**

Run: `mvn -o test -pl server '-Dtest=MysqlPayloadObjectObserverErrorTest+MycatRxJavaErrorHandlerTest'` 与 `mvn -o test -pl hbt '-Dtest=StreamingProducerErrorTest'`
Expected: 全绿。

- [ ] **Step 4: 回写看板 + Commit**

在 `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md` 变更日志追加一行（I-DEBT-30 面① 收口、面② 仍挂），`git add` 后提交。

---

## 完成判据

- Task 1–3 每子模式一条先红后绿测（RED 已实证、GREEN 已跑）。
- Task 4：hbt/router 全量零回归、定向新测全绿、三模块重编 BUILD SUCCESS。
- 不承诺 `testOptimizationProcedure` 端到端转绿（面②另挂）；承诺该类流式错误**不再挂死客户端 / 不再 Undeliverable 拖垮 8066**。
