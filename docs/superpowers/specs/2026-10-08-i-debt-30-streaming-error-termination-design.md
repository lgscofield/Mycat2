# I-DEBT-30（面①）：服务端流式查询错误必达终结（消除客户端挂死 + UndeliverableException 拖垮 8066）

> 状态：**设计已获用户批准（方案 A），待 writing-plans 拆解实施**。
> 来源：Phase3 逐族分诊挂账的 I-DEBT-30（看板 `testOptimizationProcedure:520` hint 守卫诊断行，2026-10-08）。经用户拍板，本轮 **只收面①=服务端流式错误处理**；**面②=hint JOIN 的真实执行（`ResultSetImpl.getString` 的 `columnDefinition is null` NPE 本身）另挂独立债**，不在本 spec。

## 0. 一句话结论

Mycat 把查询结果**流式**（RxJava `Observable`）推给客户端：列头先到、行后到。一旦"列头已上线、中途出错"，现有代码会往一条客户端正在"读结果集行"的连接上补发一个**裸 ERR 包**——这在 MySQL 协议里非法（该状态只认 行/EOF/OK），多数客户端把它当垃圾行继续等终结包 ⇒ **客户端永久挂死**。另一条更隐蔽的路：错误根本没路由到 `emitter.onError`（同步逃逸异常 / dispose 后再报错），被 RxJava 封成 `UndeliverableException`，而**全仓没配全局错误处理器** ⇒ promise 永不 settle、错误无处可去 ⇒ **同样挂死，且裸异常抛回 Vert.x worker 拖垮 8066**。正解 = **由构造保证每条流式失败都必达地、协议正确地终结**：列头未上线发合法 ERR（现状保留），列头已上线则关闭前端连接（对齐真 MySQL "Lost connection during query"），生产侧兜住逃逸异常并防 double-terminal，注册全局兜底不杀线程。

## 1. 现象（凭看板诊断行 + 当前实测代码签名）

| 症状 | 触发场景 | 观察 |
|---|---|---|
| 客户端永久挂死、连带 8066 被拖垮 | `testOptimizationProcedure` 打开 hint 后跑 `use_nl_join`/`use_hash_join` 多行 JOIN，服务端执行期抛错 | 看板 line 220 实测：mvn ~240s 无返回、手杀 surefire fork；服务端 err 日志 `columnDefinition is null` NPE + RxJava `UndeliverableException` |
| 半结果集后裸 ERR 包 | 列定义已推送、随后某行读取/转换抛异常 | 客户端在"读行"态收到 `0xFF` 包 = 协议非法 → 挂起等待合法终结 |

## 2. 根因（逐行坐实，file:line 为当前实测）

流式查询链路：`DefaultCommandHandler.handleQuery`（server，L57-74）→ `MycatdbCommand.executeQuery` → `ReceiverImpl.execute(QUERY)`（L186-213）→ `sendResultSet(Observable)`（L122-131）→ `ioExecutor.executeBlocking(promise1 -> observable.subscribe(new MysqlPayloadObjectObserver(promise1, ...)))`。生产侧 = `VertxExecuter.runQueryOutputAsMysqlPayloadObject`（hbt，L821-855）。

### 2.1 消费侧：列头先冲 + onError 不动网路（server `ReceiverImpl`）

`MysqlPayloadObjectObserver`（L291-357）：

- `onNext(MySQLColumnDef)`（L322-334）：一收到列定义**立即** `writeColumnCount` + 逐个 `writeBytes(列定义)` + `writeColumnEndPacket`——**从此刻起客户端进入"读结果集行"状态**。
- `onError(t)`（L341-345）：**只** `disposable.dispose()` + `promise.tryFail(t)`，**不写任何终结包、不关连接**。
- `onComplete`（L347-356）：写 `writeRowEndPacket`（EOF/OK）后 `promise.tryComplete()`。

### 2.2 顶层 recover 补发裸 ERR（两条链路都补，非协议安全）

- proxy `CommandResolver`（L397-407）：`endFuture.onComplete(event -> if (event.failed()) writeErrorEndPacketBySyncInProcessError())`。
- server `MycatVertxMySQLHandler`（L310-332）：`.recover(cause -> session.writeErrorEndPacketBySyncInProcessError(errorCode))`。
- `writeErrorEndPacketBySyncInProcessError`（proxy `MySQLProxyServerSession` L91-102）：若 `channel().isConnected()` 则 `switchMySQLServerWriteHandler()` + 写裸 `0xFF` ERR 包。

⇒ **子模式 (a)**：列头已上线（§2.1）后 `promise.tryFail` → 顶层 recover（§2.2）往"读行态"连接补发裸 ERR ⇒ 协议非法 → **挂死**。

### 2.3 生产侧：逃逸异常 + double-terminal → UndeliverableException

- `NewMycatConnectionImpl.prepareQuery`（common，L106-168）**有** try/catch（L157-160 → `collector.onError(e)`）：JDBC 读行异常已被路由。
- 但 `Observable.create` lambda（VertxExecuter L824-854）里 `connectionFuture.onSuccess(conn -> conn.prepareQuery(...))` 的回调**体外层无 try/catch**：`prepareQuery` catch 之外的同步逃逸（连接对象状态异常、collector 回调经 `emitter.onNext` 同步驱动下游 `observer.onNext` 抛回）→ 不走 `emitter.onError`。
- **无单次终态门**：下游 `observer.onNext` 写客户端失败会抛，RxJava 自行 onError（dispose）；此后 collector 再 `emitter.onError` = double-terminal → RxJava 对无处投递的终态封 `UndeliverableException`。
- **全仓未配 `RxJavaPlugins.setErrorHandler`**（实测 grep：命中的 `errorHandler` 全是 Calcite `HintStrategyTable`/`Litmus` 无关项）⇒ `UndeliverableException` 走默认 `RxJavaPlugins.onError` → 抛回当前线程 UncaughtExceptionHandler（Vert.x worker）→ promise 永不 settle + worker 不稳 ⇒ **子模式 (b)**：无线上终结、客户端挂死、8066 被拖垮。

### 2.4 可复用的关闭契约（面①落地依据）

前端会话强制关闭 API 已存在且被错误路径使用：`MySQLServerSession.close(boolean, String)`（proxy，接口 L242）——注意前端 close 接受 **String 消息**（非 Throwable），故 observer 里把 `t.getMessage()`（或含错误码的串）传入即可；后端 `MySQLClientSession.close(false, e)`（`ResultSetHandler` L360-361）是另一类型，勿混。`MySQLProxyServerSession`（L75）亦以 `close(false, setLastMessage(e))` 用法佐证。且 `writeErrorEndPacketBySyncInProcessError` 自带 `channel().isConnected()` 守卫（L93）⇒**先关连接再 settle，顶层 recover 的 ERR 补发自动变 no-op（幂等），无需改顶层逻辑**。

## 3. 设计（方案 A：observer 局部判定 + 生产侧加固 + 全局兜底）

### 节 1 — 结果集生命周期状态 + 终态契约
权威判据"结果集是否已开始（列头是否已上线）"落在唯一知情人 `MysqlPayloadObjectObserver`：新增 `boolean resultStarted`，在处理首个 `MySQLColumnDef`、写列数包那一刻置真。终态契约对齐真 MySQL：
- **列头未上线遇错** → 发**合法** ERR 包、会话存活（= 现状，保留）。
- **列头已上线遇错** → 不再发 ERR；**强制关闭前端连接** → 客户端得 "Lost connection to server during query"。
关连接后 `channel().isConnected()` 守卫使顶层 ERR 补发天然幂等 no-op。

### 节 2 — `MysqlPayloadObjectObserver.onError` 分流（server `ReceiverImpl`）
```
if (!resultStarted) { disposable.dispose(); promise.tryFail(t); }                       // 现路径：合法 ERR、会话存活
else { disposable.dispose(); session.close(false, msgOf(t)); promise.tryFail(t); }       // 关前端连接（close(boolean,String)，L242）；顶层 ERR 因断连变 no-op
```
`msgOf(t)` = 从 `t` 取一句面向会话关闭原因的 String（`t.getMessage()` 兜底 `t.toString()`）。`onComplete` 不变。切断子模式 (a)。

### 节 3 — 生产侧加固（hbt `VertxExecuter.runQueryOutputAsMysqlPayloadObject` / `runQuery`）
- `connectionFuture.onSuccess(conn -> conn.prepareQuery(...))` 回调体外层 try/catch → 任何同步逃逸 `emitter.onError(t)`。
- collector 加 `AtomicBoolean terminated` 单次门：`onError`/`onComplete` 只放行一次 emitter 终态；`connectionFuture.onFailure` 同过此门。杜绝 double-terminal → Undeliverable。

### 节 4 — 全局兜底（server 启动处注册 `RxJavaPlugins.setErrorHandler`）
解包 `UndeliverableException`；因果为客户端断连/取消（连接已没）→ 正常忽略；其余 → WARN 记日志含上下文；**绝不 rethrow**（不再抛回 Vert.x worker 杀线程 = 消除"拖垮 8066"）。作为任何残漏不可达错误的可见化 + 去毒化网。

### 节 5 — 挂死守卫取舍（裁定：不加时间超时）
**不加**流式空闲超时——合法长流式导出（大结果集）会被误杀，风险 > 收益。改由节 2+3 **"由构造保证终结"**：每条错误路径必达 `onError` 并 settle promise，挂死从"缺路由"变"不可能"。网络写超时属连接层，另议（不在本债面）。

### 节 6 — handler 无关性
observer / 生产侧修复对 proxy `CommandResolver` 与 server `MycatVertxMySQLHandler` **两条顶层链路同时生效**（二者共享 `ReceiverImpl`/`VertxExecuter`），无需二处分别改。

## 4. 测试面（TDD，每子模式一个先红测；不依赖面② JOIN 真 bug）

用可录制 `writeBytes`/`close` 调用的 **Fake 前端会话**驱动**真实 observer**（免活体 8066、秒级，沿用本系列脱机验证脚手架风格）：

1. **列头前错**：喂 `onError` 于任何 `onNext(ColumnDef)` 之前 → 断言 `promise` failed、**未** `close`（正控制，护现状的合法 ERR 路径）。
2. **列头后错（本债核心）**：先喂一条 `onNext(ColumnDef)`（置 `resultStarted`）成功后再 `onError` → 断言 **`session.close(false, ...)` 被调、前端连接关闭**（顶层 ERR 因断连变 no-op）。
3. **生产侧同步逃逸**：`runQueryOutputAsMysqlPayloadObject` 用同步抛错的 fake connection → 断言 `emitter.onError` **恰好一次**、无 `UndeliverableException`。
4. **全局 handler**：直接灌一个 `UndeliverableException` → 断言解包 + 记日志 + **不 rethrow**。

> 测试与面②正交：用**合成错误**触发，不复现 `columnDefinition is null` NPE，故本 spec 可独立验收，不受未修的面②阻断。

## 5. 范围边界与挂账

- **面② JOIN 执行 `columnDefinition is null` NPE 本身**：不在本轮（用户拍板），保留为独立债项；本 spec 收口**不承诺** `testOptimizationProcedure` 端到端转绿，只承诺**该类错误不再挂死客户端 / 不再 Undeliverable 拖垮 8066**（改为受控的连接终止）。
- 具体 close 方法签名、Fake 会话落点（新 test 工具类 vs 复用既有）、`RxJavaPlugins` 注册时机（Native server 启动链具体文件）——留 writing-plans 逐点钉 file:line。

## 6. 护栏与验收

- 改动跨 **hbt（生产侧 VertxExecuter）+ server（observer / 启动兜底）**，blast = 流式查询**错误路径**，成功路径零改。
- 提交前必过：定向新测（每子模式）+ `mvn -o test -pl hbt,router` 全量零回归；重编前停 8066、`mvn` 带 `JAVA_HOME`、跳测试用 `'-Dmaven.test.skip=true'`（单引号）。
- Evidenced-Done 才提交、不假修、保护 ~28 绿基线。

## 7. 参考锚点（当前实测）

- server `io/mycat/commands/ReceiverImpl.java`：`sendResultSet(Observable)` L122-131、`MysqlPayloadObjectObserver` L291-357（`onNext` L313-339、`onError` L341-345、`onComplete` L347-356）。
- server `io/mycat/commands/DefaultCommandHandler.java`：`handleQuery` L57-74。
- proxy `io/mycat/command/CommandResolver.java`：`COM_QUERY` L74-81、顶层失败 recover L395-408。
- server `io/mycat/mycatmysql/MycatVertxMySQLHandler.java`：recover 补发 L310-336。
- proxy `io/mycat/proxy/session/MySQLServerSession.java`：前端会话接口 `close(boolean, String)` **L242**（observer 关连接即用此）。
- proxy `io/mycat/proxy/session/MySQLProxyServerSession.java`：`writeErrorEndPacketBySyncInProcessError` L91-102（`isConnected` 守卫）、`close(false, ...)` L75。
- proxy `io/mycat/proxy/handler/backend/ResultSetHandler.java`：`close(...)` → `mysql.close(false, e)` L360-361。
- hbt `io/mycat/vertx/VertxExecuter.java`：`runQueryOutputAsMysqlPayloadObject` L821-855、`runQuery` L865-905。
- common `io/mycat/newquery/NewMycatConnectionImpl.java`：`prepareQuery(...MysqlCollector)` L106-168（内部 try/catch L157-160）。
