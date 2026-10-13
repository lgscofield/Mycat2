# 跨片列头两缝同移 wiring 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (Native 内联) 逐任务实现本计划。步骤用 checkbox (`- [ ]`) 跟踪。

**Goal:** 把跨片 `bit(n>1)` 的**行解码缝**与**响应列头缝**一并（两缝同移）切到后端物理类型真相源，用活 e2e `caseCrossShardBitColumnFidelity` 证明 RED→GREEN，根治上轮 CodeReview Critical#1 揪出的"半翻→沉默数据损坏"。

**Architecture:** 后端忠实 `MycatMySQLRowMetaData`（`VertxMycatConnectionPool:131` 于 `onColumnEnd` 发出，早于首行）经 per-query 上下文 `AsyncMycatDataContextImpl` 的 `AtomicReference` 侧信道捕获；列头装配点（`getMysqlPayloadObjectObservable`，同一 ctx 实例）在惰性 `headerSent` 首次置真时用 `RowMetaDataFidelity.effective(ctx.backendRowMeta, planMeta)` 重建 `MySQLColumnDef`；行解码缝（`runQuery.onColumnDef`）改 `effective(后端忠实入参, rowMetaDataArg)`。两缝收敛到同一把尺子。零 calcite-core 改动。

**Tech Stack:** Java 21（LibericaJDK-21-Full）、Calcite 1.42 fork、Vert.x MySQL 协议解码、hbt 结果装配层、Docker mysql:8.0 双后端、JUnit example 活 e2e。

**Spec:** `docs/superpowers/specs/2026-10-13-type-representation-cross-shard-header-design.md`（执行者须同读，尤其 §1 INV-④、§2 机制、§4 活 e2e、Review Focus）。

## Global Constraints

- **严禁修改 fork 的 `calcite-core`**（不加 `SqlTypeName.BIT`、不动 `Types.BIT→BOOLEAN`）。
- **两缝同移（INV-④）**：行解码缝与列头缝必须都切忠实轨；**任务顺序锁定 header→decode**——"列头忠实+行解码有损"中间态响亮崩溃（安全），"行解码忠实+列头有损"中间态沉默损坏（禁止）。任何让行解码缝先于列头缝翻绿的中间提交都不可推送/合入。
- **零回归**：单片路径（`runQueryOutputAsMysqlPayloadObject`）、已绿 varbinary/date/靶1/靶2 表征格行为不变；`effective` 对列数不符/后端 meta 缺失原样回计划轨。
- **构建须用 JDK 21**：shell 默认 `JAVA_HOME` 可能指 JDK 8，显式 `$env:JAVA_HOME` 指 LibericaJDK-21-Full。
- **Maven 离线**：一律 `-o`；`-D` 参数在 PowerShell 须整体引号包裹。
- **测试后端强制 Docker 容器**（`m1-mysql-a:3306`/`m1-mysql-b:3307` mysql:8.0，禁用本地 MySQL 服务）。
- **本地不 push**：仅本地 commit；`.qoder`/`.superpowers`/`server/src/main/resources/version.txt`/`.mycat-*` 运行时产物一律不入暂存。
- **诚实验收**：主门以活 e2e RED→GREEN 为准，in-process 逻辑锁作辅；不得以逻辑锁绿冒充 e2e 已过。
- 一切结论以当前代码 `file:line` + 实跑输出独立复核，不信既有记录。

## 环境与快速回路（贯穿各任务）

infra 已跑通并保留：容器 `m1-mysql-a:3306`（mysql:8.0.46，`max_connections=1000`）、被测 Mycat@8066（persona `MYSQL_8_0`，PID 存 `.mycat-e2e-server.pid`，`.mycat-home-e2e` 为 MYCAT_HOME）。

- **改服务端代码后快速验证（免重打 fat-jar）**：`mvn -o -pl hbt install -DskipTests` 重编 hbt → 用缓存 classpath 直启 `io.mycat.MycatCore`（主类），`-cp "<各模块>/target/classes;<server runtime 依赖 classpath 缓存文件>"`，起前先停旧 PID。**服务端行为改动必须重启 MycatCore 进程才生效**（example 测试只是 8066 客户端，不重启服务端则跑的是旧码）。
- **e2e 跑法**：`mvn -o -pl example -am test "-Dtest=UserCaseTest#caseCrossShardBitColumnFidelity" "-Dsurefire.failIfNoSpecifiedTests=false"`（`-am` 带上游模块，过滤器让其它用例静默跳过）。
- **提交/收尾前完整组装一次**：`mvn -o -q -pl server -am package -DskipTests` 出 fat jar 做最终校验（assembly 是最慢环，仅终验用）。
- classpath 缓存文件生成：`mvn -o -pl server dependency:build-classpath "-Dmdep.outputFile=<绝对路径.txt>" "-Dmdep.includeScope=runtime"`（`-Dmdep.outputFile` 须绝对路径，相对路径静默不生成）。

---

## Review Focus（规格隐含但任务测试未必全覆盖，末轮独立 CodeReview 逐条核）

1. **两缝同移不可半翻（INV-④）**：确认行解码缝与列头缝在同一分支的最终态都接 `effective`；审查者须验证不存在"解码忠实/列头有损"的可达中间推送态。
2. **跨片其它类型零回归**：`effective` 对列数相符的 identity 结果集把所有列 `getColumnType` 改取后端——BOOLEAN(bit1)/JSON/DATE/TIMESTAMP 跨片列头不得翻车（Task 3 全矩阵钉）。
3. **后端 meta 缺失/列数不符兜底**：非分片源（`ReceiverImpl:208` 4 参 `null`）或本地聚合列（列数≠计划）时 `effective` 原样回计划轨，须无 NPE、无误归一。
4. **空结果集列头补发路径**：`headerSent` 未置真走 `onComplete` 补发（`PrepareExecutor:104-106`）时，列头也须经 `effective(ctx.backendRowMeta, plan)`，与首行路径口径一致。
5. **`getColumnLength` 遗留格**：`FaithfulTypeView` 未覆写 `getColumnLength`（上轮 deferred Minor#4），活 e2e 若暴露 Connector/J BIT 定长截断则本条提升为必修（在 Task 3 观察）。

---

### Task 1: 列头缝保真（侧信道捕获 + 惰性重建），行解码缝不动（响亮安全中间态）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/AsyncMycatDataContextImpl.java`（类体加字段 + 方法，紧邻 L59 `shareObservable` 之后）
- Modify: `hbt/src/main/java/io/mycat/vertx/VertxExecuter.java`（`runQuery` L882-945：加带 sink 重载 + `onColumnDef` 发布；4 参委托）
- Modify: `hbt/src/main/java/io/mycat/AsyncMycatDataContextImpl.java:205,246` 与 `hbt/src/main/java/io/mycat/calcite/physical/MycatSQLTableLookup.java:272`（三处 lambda 改 5 参，闭包捕获 publish）
- Modify: `hbt/src/main/java/io/mycat/calcite/PrepareExecutor.java:67-119`（列头惰性从 carrier 重建）
- Test: `hbt/src/test/java/io/mycat/calcite/CrossShardHeaderFidelityTest.java`（新建，in-process）

**Interfaces:**
- Consumes: `RowMetaDataFidelity.effective(backendMeta, planMeta)`（既有，`common`）；`MycatMySQLRowMetaData`。
- Produces: `AsyncMycatDataContextImpl.getBackendRowMeta() : AtomicReference<MycatRowMetaData>`（@Getter）；`AsyncMycatDataContextImpl.publishBackendRowMeta(MycatRowMetaData) : void`（首写者胜）；`VertxExecuter.runQuery(Future, String, List, MycatRowMetaData, Consumer<MycatRowMetaData>) : Observable<Object[]>`（5 参重载）。

- [ ] **Step 1: 写失败测试（in-process，无真库）**

在 `CrossShardHeaderFidelityTest` 直接测 `effective` 驱动的列头视图 + carrier 首写者语义（不依赖连接）：

```java
// (a) 后端忠实轨 + 计划有损轨 → 列头有效类型取后端 Types.BIT
@Test
public void headerUsesBackendBitWhenCarrierHoldsFaithful() {
    MycatRowMetaData backend = faithfulBitMeta();   // MycatMySQLRowMetaData, getColumnType(0)=Types.BIT
    MycatRowMetaData plan = numericPlanMeta();      // getColumnType(0)=Types.BIGINT
    MycatRowMetaData eff = RowMetaDataFidelity.effective(backend, plan);
    Assert.assertEquals(Types.BIT, eff.getColumnType(0));
    Assert.assertEquals(plan.getColumnName(0), eff.getColumnName(0)); // 列名仍以计划为准
}

// (b) carrier 首写者胜：第二次 publish 不覆盖
@Test
public void publishBackendRowMetaIsFirstWriterWins() {
    AsyncMycatDataContextImpl ctx = /* 最小可构造子类或测试替身 */;
    ctx.publishBackendRowMeta(metaA);
    ctx.publishBackendRowMeta(metaB);
    Assert.assertSame(metaA, ctx.getBackendRowMeta().get());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl hbt test "-Dtest=CrossShardHeaderFidelityTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 编译失败（`publishBackendRowMeta`/`getBackendRowMeta` 不存在）→ 即红。若 (a) 单独跑因 `effective` 已存在而绿，则红点须落在 (b) 新方法缺失。

- [ ] **Step 3: 加侧信道载体**

`AsyncMycatDataContextImpl` 类体加：

```java
private final java.util.concurrent.atomic.AtomicReference<MycatRowMetaData> backendRowMeta =
        new java.util.concurrent.atomic.AtomicReference<>();

/** 首写者胜发布后端忠实轨 meta（identity 结果集各片同构，取任一即可作列头真相源）。 */
public void publishBackendRowMeta(MycatRowMetaData meta) {
    if (meta != null) {
        backendRowMeta.compareAndSet(null, meta);
    }
}
// 字段名 backendRowMeta + 类上 @Getter → 自动生成 getBackendRowMeta()
```

- [ ] **Step 4: runQuery 加 sink 重载并发布（解码缝暂不动）**

`VertxExecuter`：把现有 `runQuery(Future,String,List,MycatRowMetaData)` 主体迁到 5 参重载
`runQuery(Future<NewMycatConnection> connectionFuture, String sql, List<Object> values, MycatRowMetaData rowMetaDataArg, java.util.function.Consumer<MycatRowMetaData> backendMetaSink)`，
在 `onColumnDef` 里**先发布**再决定解码轨（本任务解码轨保持历史 `orElse` 不动）：

```java
@Override
public void onColumnDef(MycatRowMetaData backendMeta) {
    backendMetaSink.accept(backendMeta); // 上抛后端忠实轨到 per-query carrier（列头缝据此重建）
    // 行解码缝本任务不动（仍历史有损轨优先）：header 忠实 + decode 有损 → byte[] 于此响亮崩溃，非沉默损坏。
    this.mycatRowMetaData = Optional.ofNullable(rowMetaDataArg).orElse(backendMeta);
}
```

4 参 `runQuery` 委托 5 参、sink 传 `m -> {}`（保 `server/.../ReceiverImpl.java:208` 等既有 4 参调用零冲击）。

- [ ] **Step 5: 三处 lambda 改 5 参，闭包捕获 publish**

`AsyncMycatDataContextImpl.getObservableList`（L205、L246）lambda 体改：
`(...) -> VertxExecuter.runQuery(sessionConnection, sql, extractParams, calciteRowMetaData, this::publishBackendRowMeta)`
（L246 在 `SqlMycatDataContextImpl`、L205 在 `HbtMycatDataContextImpl`，`this` 均为 context 实例）。
`MycatSQLTableLookup.dispatchRightObservable`（L272）改：
`(...) -> VertxExecuter.runQuery(sessionConnection, sql, extractParams, calciteRowMetaData, sqlMycatDataContext::publishBackendRowMeta)`。
`Queryer` 接口与 `getObservables` 签名**不动**。

- [ ] **Step 6: 列头惰性从 carrier 重建**

`PrepareExecutor.getMysqlPayloadObjectObservable`：移除 L77 急切 `final MySQLColumnDef columnDef = new MySQLColumnDef(rowMetaData);`，改在两处发送点惰性构造（首行 L91-93、空集补发 L104-106）统一走一个局部 lambda：

```java
java.util.function.Supplier<MySQLColumnDef> columnDefSupplier = () ->
        MySQLColumnDef.of(RowMetaDataFidelity.effective(
                newMycatDataContext.getBackendRowMeta().get(), rowMetaData));
// 发送点：emitter.onNext(columnDefSupplier.get()); 后 headerSent[0]=true
```

carrier 为 null（非分片源）时 `effective(null, plan)` 原样返回 `plan` → 行为与现状一致（零回归）。

- [ ] **Step 7: 跑测试确认通过 + 全 hbt 编译/单测绿**

Run: `mvn -o -pl hbt -am test "-Dtest=CrossShardHeaderFidelityTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: PASS。随后跑全 hbt 单测确认无回归（行为对既有单测不变）。

- [ ] **Step 8: 快速回路与 e2e 确认仍响亮红（证未引入沉默损坏）**

重编 hbt + 重启 MycatCore（见"环境与快速回路"），跑 `caseCrossShardBitColumnFidelity`。
Expected: **仍 RED**，且崩点仍是行解码处 `UnsupportedOperationException:[B@`（响亮），**不是** `[B@...` 被当数据写库。此步证明 Task 1 只动列头缝未致沉默损坏。

- [ ] **Step 9: 提交**

```bash
git add hbt/src/main/java/io/mycat/AsyncMycatDataContextImpl.java hbt/src/main/java/io/mycat/vertx/VertxExecuter.java hbt/src/main/java/io/mycat/calcite/PrepareExecutor.java hbt/src/main/java/io/mycat/calcite/physical/MycatSQLTableLookup.java hbt/src/test/java/io/mycat/calcite/CrossShardHeaderFidelityTest.java
git commit -m "feat(hbt): 跨片列头缝经 per-query 侧信道取后端忠实轨(惰性重建,行解码暂不动)"
```

---

### Task 2: 行解码缝切忠实轨（两缝至此合一，INV-④ 达成）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/vertx/VertxExecuter.java`（`runQuery.onColumnDef` 的解码轨行）
- Modify: `hbt/src/test/java/io/mycat/vertx/CrossShardBitRowDecodeTest.java`（上轮 dormant 契约锁转正：去 `assertThrows(有损抛)`，改 `effective 不抛`）

**Interfaces:**
- Consumes: `RowMetaDataFidelity.effective(backend, plan)`；`BaseRowObservable.getObjects(Object[], MycatRowMetaData)`（既有 `case BIT:` byte[] 透传）。
- Produces: 跨片 split 行解码以忠实类型路由 `byte[]`（与 Task 1 列头同尺）。

- [ ] **Step 1: 写契约锁（in-process 逻辑锁，钉 effective 解码语义）**

复用/转正上轮 dormant 的 `CrossShardBitRowDecodeTest`（它直接喂 meta 给 `BaseRowObservable`，锁的是 `effective` 解码语义原语，不声称覆盖生产装配）：

```java
@Test
public void crossShardBitRowDecodesUnderFaithfulMeta() {
    Object[] row = { new byte[]{(byte)0xB3} };
    MycatRowMetaData backend = faithfulBitMeta();   // Types.BIT
    MycatRowMetaData plan = numericPlanMeta();      // Types.BIGINT
    assertThrows(UnsupportedOperationException.class, () -> BaseRowObservable.getObjects(row, plan)); // 有损 bug 面
    Object[] out = BaseRowObservable.getObjects(row, RowMetaDataFidelity.effective(backend, plan));    // 忠实透传
    Assert.assertArrayEquals(new byte[]{(byte)0xB3}, (byte[]) out[0]);
}
```

> **诚实标注：** 本逻辑锁在 `effective` 原语层面即绿（上轮已证），**不是** Step 3 生产翻缝的 RED 见证。生产解码缝的唯一权威 RED→GREEN 见证是 Step 4 的活 e2e（Task 1 Step 8 已坐实其为响亮红）。

- [ ] **Step 2: 跑逻辑锁确认**

Run: `mvn -o -pl hbt test "-Dtest=CrossShardBitRowDecodeTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: PASS（原语层面）。红点不在此——在生产翻缝，见 Step 4 活 e2e。

- [ ] **Step 3: 切解码缝为 effective**

`VertxExecuter.runQuery.onColumnDef` 的解码轨行由
`this.mycatRowMetaData = Optional.ofNullable(rowMetaDataArg).orElse(backendMeta);`
改为
`this.mycatRowMetaData = RowMetaDataFidelity.effective(backendMeta, rowMetaDataArg);`
（`effective` 对 `rowMetaDataArg=null`（单结果集直出）返回 backend；对列数不符返回 plan → 零回归兜底）。加 `import io.mycat.beans.mycat.RowMetaDataFidelity;`。删/改原"两缝须同移本轮不夹带"的历史注释为"两缝已同移"。

- [ ] **Step 4: 活 e2e 权威 RED→GREEN（本任务完成契约）**

重编 hbt（`mvn -o -pl hbt install -DskipTests`）→ 停旧 MycatCore、以 classpath 重启加载新码（PID 回写 `.mycat-e2e-server.pid`）→ 跑：
`mvn -o -pl example -am test "-Dtest=UserCaseTest#caseCrossShardBitColumnFidelity" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: **GREEN**——对照 Task 1 Step 8 的响亮红（`UnsupportedOperationException:[B@`），两缝同移后跨片归并返 2 行、`c_bit8` 逐字节 `{0xB3}`、列头 `Types.BIT`。读 surefire 报告确认 `Failures:0, Errors:0`（非零筛空）。

- [ ] **Step 5: 全 hbt 单测绿**

Run: `mvn -o -pl hbt test "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 全绿（含 `CrossShardHeaderFidelityTest`+`CrossShardBitRowDecodeTest`）。

- [ ] **Step 6: 提交**

```bash
git add hbt/src/main/java/io/mycat/vertx/VertxExecuter.java hbt/src/test/java/io/mycat/vertx/CrossShardBitRowDecodeTest.java
git commit -m "fix(hbt): 跨片行解码缝切忠实轨 effective(两缝同移达成,活e2e caseCrossShardBitColumnFidelity 转绿)"
```

---

### Task 3: 跨片全矩阵回归 + 组装终验

**Files:**
- Test: `example/src/test/java/io/mycat/sql/UserCaseTest.java`（全量作跨片其它类型回归门；若暴露 `getColumnLength` 截断，则回 Task 1 补 `FaithfulTypeView.getColumnLength` 覆写，标 Ruling）
- 无新生产码（除 Review Focus#5 触发的条件性补覆写）

**Interfaces:**
- Consumes: Task 1+2 全部改动（单例 e2e 主门已在 Task 2 Step 4 转绿）。
- Produces: 跨片其它类型零回归的活证据 + 全模块绿 + 完整组装一致。

- [ ] **Step 1: 跨片其它类型零回归（全矩阵）**

确保服务端已加载 Task 2 后新码（若未重启先重启）。Run: `mvn -o -pl example -am test "-Dtest=UserCaseTest" "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: `UserCaseTest` 全量绿（BOOLEAN/JSON/DATE/TIMESTAMP 等跨片列头不因所有列 `getColumnType` 改取后端而翻车）。逐项读 surefire 报告，任何非零失败/错误按名上报。

- [ ] **Step 2: 全模块回归**

Run: `mvn -o -pl common,hbt,server -am test "-Dsurefire.failIfNoSpecifiedTests=false"`
Expected: 全绿（读各模块 surefire 报告确认 0 fail/0 err，勿被 `-q` 抑制的堆栈误导——错误路径测试的期望日志异常非失败）。

- [ ] **Step 3: 观察 Review Focus#5（getColumnLength）**

若 Step 1 暴露 Connector/J 侧 BIT 定长截断/取数异常，则回 Task 1 在 `FaithfulTypeView` 补 `getColumnLength` 覆写取后端轨，以 in-process 测试 RED→GREEN，记 Ruling。若无异常，ledger 记 "e2e 未见 getColumnLength 截断，维持 deferred Minor#4"。

- [ ] **Step 4: 完整组装终验**

Run: `mvn -o -q -pl server -am package -DskipTests`
Expected: BUILD SUCCESS 出 fat jar（确认 classpath 快速回路与完整组装结果一致，无 classpath 漂移）。提交/发布前一次即可。

- [ ] **Step 5: 提交（仅当 Step 3 触发了补覆写）**

```bash
git add <补覆写涉及文件>
git commit -m "fix(hbt): FaithfulTypeView 补 getColumnLength 取后端轨(活e2e暴露定长截断时)"
```

（若 Step 3 未触发补覆写，本任务无码改动，不产生空提交；完成契约由 Step 1/2/4 实跑证据满足。）
