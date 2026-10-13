# 类型表征统一收尾（跨片列头保真 + BINARY）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让跨片/归并结果集的列头与行值以"后端物理 MySQL 类型"为真相源，根治 `bit(n>1)` 列跨片列头降型（BIT→数值）导致 `BaseRowObservable` 数值分支抛 `UnsupportedOperationException` 崩会话的缺陷，并把 BINARY/BLOB 运行时表征统一到官方 `byte[]` 口径。

**Architecture:** MyCat 有两把列头尺子——忠实轨 `MycatMySQLRowMetaData(columnDefPackets)`（单片/后端回包，`getColumnType` 经 `DataType.parseDataType` 保真）与有损轨 `CalciteRowMetaData(relDataTypeFieldList)`（计划派生，fork 无 `SqlTypeName.BIT`、`Types.BIT→BOOLEAN`/bit(n) 归数值）。跨片在 `VertxExecuter.runQuery` 行解码缝用 `rowMetaDataArg`（有损）覆盖了后端忠实 meta，列头又在响应层从 `plan.getMetaData()`（有损）发。本计划引入"忠实 meta 归一"辅助件，在两缝上以忠实类型为准，零 calcite-core 改动。

**Tech Stack:** Java 21、Calcite 1.42（fork，`calcite-core`，禁改枚举）、Vert.x MySQL 协议解码、MycatRowMetaData 双实现、hbt/common/server 结果装配层、JUnit 兼容矩阵。

**Spec:** `docs/superpowers/specs/2026-10-13-type-representation-cross-shard-header-design.md`（本计划从该 spec 论证，执行者同读二者）

## Global Constraints

- **严禁修改 fork 的 `calcite-core` 内部枚举**：不加 `SqlTypeName.BIT`，不动 `SqlTypeName.java:300` 的 `Types.BIT→BOOLEAN` 映射。
- **BIT 对外契约**：`BIT(1)=java.lang.Boolean`；`BIT(n>1)=byte[]`，wire 按 `(n+7)/8` 字节大端原始串、字段类型 `MYSQL_TYPE_BIT`(FIELD_TYPE=16)。
- **BINARY/BLOB 契约**：`BINARY/VARBINARY/LONGVARBINARY/BLOB` 运行时行值统一 `byte[]`。
- **零回归**：单片路径、已绿 varbinary/date/靶1(FLOAT)/靶2(TIMESTAMP) 表征格不得回退。
- **诚实挂账**：跨片列头保真的权威测试是 e2e（需 2 分片真库 + 运行 Mycat），本计划落地期以 in-process 逻辑锁验证，完整 e2e 标 `NO_DATA` 待 Docker infra（用户已授权，在架构专轮之后起），不得谎报 e2e 已过。
- 结论一律以当前代码 `file:line` + 实跑测试输出独立复核，不信既有记录。
- 本地 commit，不 push；`git add` 精确，排除 `.qoder`/`.superpowers`/`server/src/main/resources/version.txt`。

## Review Focus（规格隐含但 e2e 未必覆盖，须逐条落到任务测试）

1. **忠实 meta 与计划 meta 列数/顺序不一致**：`effective(backend, plan)` 在列数不等时必须安全回退计划 meta，不得错配列（Task 1 守卫）。
2. **别名/投影差异**：行值取后端忠实类型，但列名/label 仍以计划逻辑视图为准（Task 1）。
3. **BINARY vs VARBINARY 区分**：统一到 `byte[]` 后列头仍分别保 `Types.BINARY`/`Types.VARBINARY`，不得并成单格（Task 4）。
4. **二进制协议 vs 文本协议列头一致**：`generateAllColumnDefPayload` 忠实分支写的 `columnDefPacket` 与 `BinaryResultSetResponse`/`ResultSetMapping` 行编码口径一致（Task 3）。
5. **e2e 缺口诚实性**：Task 2/3 的跨片 e2e 断言写成红且标 NO_DATA，逻辑锁绿不得冒充 e2e。

---

### Task 1: 忠实 meta 归一辅助件（纯逻辑，in-process）

**Files:**
- Create: `hbt/src/main/java/io/mycat/vertx/RowMetaDataFidelity.java`
- Test: `hbt/src/test/java/io/mycat/vertx/RowMetaDataFidelityTest.java`

**Interfaces:**
- Consumes: `MycatMySQLRowMetaData`（common，`getColumnDefPackets()`）；`MycatRowMetaData`（接口，`getColumnType(int)`/`getColumnLabel(int)`/`getColumnCount()`）
- Produces: `public static MycatRowMetaData RowMetaDataFidelity.effective(MycatRowMetaData backendMeta, MycatRowMetaData planMeta)` —— 当 `backendMeta instanceof MycatMySQLRowMetaData` 且与 `planMeta` 列数相等时，返回一个视图：`getColumnType(i)` 取 `backendMeta.getColumnType(i)`（忠实含 `Types.BIT`），`getColumnLabel/getColumnName/getColumnCount` 取 `planMeta`；其余委托 `planMeta`。列数不等或 `backendMeta` 非忠实轨时返回 `planMeta`（C 兜底：纯本地计算列）。

- [ ] **Step 1: 写失败测试**

构造一个后端忠实 meta：`ColumnDefPacketImpl` 置 field type = 16(MYSQL_TYPE_BIT)、charset=63、flags 使 `isSigned()` 正常，包进 `new MycatMySQLRowMetaData(List.of(bitPacket))`；构造计划有损 meta：`ResultSetBuilder.create().addColumnInfo("bitx","c_bit8", Types.BIGINT, 0, 0).build().getMetaData()`（模拟 bit(8) 归数值）。

```java
@Test
public void effectivePrefersFaithfulBackendColumnType() {
    MycatRowMetaData backend = faithfulBitMeta();          // getColumnType(0) == Types.BIT
    MycatRowMetaData plan = numericPlanMeta();             // getColumnType(0) == Types.BIGINT
    MycatRowMetaData eff = RowMetaDataFidelity.effective(backend, plan);
    assertEquals(Types.BIT, eff.getColumnType(0));         // 忠实类型为准
    assertEquals(plan.getColumnLabel(0), eff.getColumnLabel(0)); // 名字仍取计划视图
}
@Test
public void effectiveFallsBackToPlanWhenColumnCountDiffers() {
    MycatRowMetaData backend = faithfulBitMeta();          // 1 列
    MycatRowMetaData plan = twoColumnPlanMeta();           // 2 列
    assertSame(plan, RowMetaDataFidelity.effective(backend, plan));
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl hbt test -Dtest=RowMetaDataFidelityTest`（JAVA_HOME 指向 LibericaJDK-21）
Expected: 编译失败 / `RowMetaDataFidelity` 不存在。

- [ ] **Step 3: 实现 `effective`**

单列数判定 + 视图委托：新建匿名/内部 `MycatRowMetaData` 包装，`getColumnType(i)` 转 `backendMeta.getColumnType(i)`，其余转 `planMeta`。列数不等或 `backendMeta==null||!(backendMeta instanceof MycatMySQLRowMetaData)` → 返 `planMeta`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl hbt test -Dtest=RowMetaDataFidelityTest`
Expected: PASS（2/2）。若 `faithfulBitMeta().getColumnType(0)` 未得 `Types.BIT`，说明 `DataType.parseDataType(16,…)` 映射非 BIT——记录为发现并回报，不擅自改 calcite-core。

- [ ] **Step 5: 提交**

```bash
git add hbt/src/main/java/io/mycat/vertx/RowMetaDataFidelity.java hbt/src/test/java/io/mycat/vertx/RowMetaDataFidelityTest.java
git commit -m "feat(hbt): 忠实 meta 归一辅助件 RowMetaDataFidelity（跨片列头保真真相源）"
```

---

### Task 2: 行解码缝应用归一（runQuery split 路径）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/vertx/VertxExecuter.java:898-899`（`runQuery` 的 `onColumnDef`）
- Test: `hbt/src/test/java/io/mycat/vertx/CrossShardBitRowDecodeTest.java`（新建）

**Interfaces:**
- Consumes: `RowMetaDataFidelity.effective(...)`（Task 1）；`BaseRowObservable.getObjects(Object[] row, MycatRowMetaData metaData)`（静态，L316）
- Produces: 跨片行解码在缝上以忠实类型路由 `byte[]`（经 `BaseRowObservable` 既有 `case BIT:` 透传，L322-341）

- [ ] **Step 1: 写失败测试（in-process 逻辑锁）**

```java
@Test
public void crossShardBitRowDecodesUnderFaithfulMeta() {
    Object[] row = { new byte[]{(byte)0xB3} };
    MycatRowMetaData backend = faithfulBitMeta();   // Types.BIT
    MycatRowMetaData plan = numericPlanMeta();      // Types.BIGINT（复现跨片有损）
    // 有损 meta 下 byte[] 落数值分支 → 抛（钉当前 bug 面）
    assertThrows(UnsupportedOperationException.class, () -> BaseRowObservable.getObjects(row, plan));
    // 归一后忠实 meta → byte[] 透传不抛
    Object[] out = BaseRowObservable.getObjects(row, RowMetaDataFidelity.effective(backend, plan));
    assertArrayEquals(new byte[]{(byte)0xB3}, (byte[]) out[0]);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl hbt test -Dtest=CrossShardBitRowDecodeTest`
Expected: `effective` 已绿（Task1），但缝未接 → 本测试证逻辑；若第一步 assertThrows 未红（即有损 meta 未抛），复核 `numericPlanMeta` 类型，须为 BIGINT/TINYINT 数值分支才复现。

- [ ] **Step 3: 接线到 `runQuery`**

将 `VertxExecuter.java:899` 由
`this.mycatRowMetaData = Optional.ofNullable(rowMetaDataArg).orElse(mycatRowMetaData);`
改为
`this.mycatRowMetaData = RowMetaDataFidelity.effective(mycatRowMetaData, rowMetaDataArg);`
（`mycatRowMetaData`=后端忠实 onColumnDef 参，`rowMetaDataArg`=计划 meta）。保留 `runQueryOutputAsMysqlPayloadObject`（单片轨）不动。

- [ ] **Step 4: 跑测试确认通过 + 编译**

Run: `mvn -q -pl hbt -am test -Dtest=CrossShardBitRowDecodeTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add hbt/src/main/java/io/mycat/vertx/VertxExecuter.java hbt/src/test/java/io/mycat/vertx/CrossShardBitRowDecodeTest.java
git commit -m "fix(hbt): 跨片行解码缝采用忠实 meta（BIT byte[] 透传，治 UnsupportedOperationException 崩会话）"
```

---

### Task 3: 跨片列头保真（响应层发忠实 columnDef）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/plan/ObservablePlanImplementorImpl.java:242-243`（非向量支路 `getMysqlPayloadObjectObservable(..., plan.getMetaData())`）
- Modify: 跨片结果列头取源处（`PrepareExecutor`/`MySQLColumnDef` 装配，执行期定位）
- Test: `common/src/test/java/io/mycat/MySQLPacketUtilBitHeaderTest.java`（新建，in-process 列头锁）

**Interfaces:**
- Consumes: `RowMetaDataFidelity.effective`；`MySQLPacketUtil.generateAllColumnDefPayload(MycatRowMetaData)`（静态，L124，忠实分支直写 `columnDefPacket`）
- Produces: 跨片结果发出 `MySQLColumnDef` 携带忠实列头（`MYSQL_TYPE_BIT`）

- [ ] **Step 1: 写失败测试（列头字节锁，in-process）**

```java
@Test
public void faithfulMetaEmitsMysqlTypeBitColumnDef() {
    MycatRowMetaData faithful = faithfulBitMeta();       // MycatMySQLRowMetaData，字段包 type=16
    byte[] payload = firstColumnDefPayload(faithful);     // generateAllColumnDefPayload 取第 0 列
    assertEquals(16, fieldTypeIdOf(payload));             // MYSQL_TYPE_BIT
    MycatRowMetaData lossy = numericPlanMeta();           // CalciteRowMetaData，BIGINT
    assertNotEquals(16, fieldTypeIdOf(firstColumnDefPayload(lossy))); // 钉当前有损
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl common test -Dtest=MySQLPacketUtilBitHeaderTest`
Expected: `faithful` 分支已正确（走 L127 直写 columnDefPacket=16），`lossy` 分支断言成立——本步主要钉住"忠实轨出 BIT、有损轨不出"的 payload 语义；若 faithful 未出 16 则记录发现。

- [ ] **Step 3: 跨片列头取忠实 meta**

决策：跨片归并结果的响应列头经 `RowMetaDataFidelity.effective(首分片后端忠实 meta, plan.getMetaData())`。执行期定位从 `runQuery`/分片可观测物向响应头传递忠实 meta 的缝（候选：`ObservablePlanImplementorImpl:242-243` 传入 `getMysqlPayloadObjectObservable` 的 meta 改为归一视图；`PrepareExecutor` 头部 `new MySQLColumnDef(rowMetaData)` 用之）。将首分片 `MycatMySQLRowMetaData` 上抛至列头装配并替换有损 `plan.getMetaData()`。

- [ ] **Step 4: 编译 + 逻辑锁通过**

Run: `mvn -q -pl common,hbt -am test -Dtest=MySQLPacketUtilBitHeaderTest`
Expected: PASS（列头 payload 锁）。

- [ ] **Step 5: 提交**

```bash
git add -A hbt/src/main/java common/src/test/java/io/mycat/MySQLPacketUtilBitHeaderTest.java
git commit -m "fix(hbt): 跨片结果列头发忠实 columnDef（MYSQL_TYPE_BIT 保真）"
```

- [ ] **Step 6（NO_DATA，待 infra）: 补 e2e 列头断言**

在 `example/src/test/java/io/mycat/sql/UserCaseTest.java:1615 caseCrossShardBitColumnFidelity` 增：对 `mycatMetaData.getColumnType(i)` 断言 `== Types.BIT` 并与后端直连 `mysqlMetaData` 逐列对拍。本任务标 `NO_DATA`，需 2 分片真库 infra 跑通方转绿。

---

### Task 4: BINARY/BLOB 表征统一（I-DEBT-35）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java:43-87`（`getJavaClass` BINARY 家族）
- Modify: 结果端/字面量联动处（`ResultSetMapping`/`BinaryResultSetResponse` 的 BINARY→byte[]；`translateLiteral` 的 `new_(ByteString)` 路径，执行期定位）
- Test: `hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java`（翻转 knownGap）

**Interfaces:**
- Consumes: `MycatRelDataTypeFactory.getJavaClass(RelDataType)`；`SqlTypeName.BINARY/VARBINARY`
- Produces: BINARY 家族 `getJavaClass` → `byte[].class`（对齐 `MycatDataType.BINARY=byte[]` 运行时）

- [ ] **Step 1: 翻转 knownGap 测试为期望 byte[]**

将 `TypeRepresentationThreePathConsistencyTest.java:163-172 knownGapBinaryColumnMapsToByteStringNotByteArray` 改为：

```java
@Test
public void binaryColumnMapsToByteArray() {
    RelDataType binary = sqlType(SqlTypeName.BINARY, false);
    RelDataType varbinary = sqlType(SqlTypeName.VARBINARY, false);
    assertEquals(byte[].class, MycatCalciteSupport.TypeFactory.getJavaClass(binary));
    assertEquals(byte[].class, MycatCalciteSupport.TypeFactory.getJavaClass(varbinary)); // Review Focus #3：两支皆 byte[]，不并入
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl hbt test -Dtest=TypeRepresentationThreePathConsistencyTest`
Expected: FAIL（当前返 `ByteString`）。

- [ ] **Step 3: `getJavaClass` 加 BINARY 家族分支**

在 `MycatRelDataTypeFactory.getJavaClass` switch 加 `case BINARY: case VARBINARY: case LONGVARBINARY: case BLOB: return byte[].class;`。若字面量/结果端因表征改动回归，联动 `ResultSetMapping`/`BinaryResultSetResponse` 的 BINARY 分支接 `byte[]`（既有 L48-60 已含 byte[] 处理），并核对 `translateLiteral` 的 `new_(ByteString)` 字面量路径不回退。

- [ ] **Step 4: 跑测试确认通过 + 全类回归**

Run: `mvn -q -pl hbt test -Dtest=TypeRepresentationThreePathConsistencyTest`
Expected: PASS，且其余 knownGap（BIT codegen 面仍 Boolean、fork 无 BIT 枚举）不回退——**注意 BIT 的 codegen `getJavaClass(BOOLEAN)` 保持 Boolean 不动**（与行层正交，见该测试 L180-183 裁定）。

- [ ] **Step 5: 提交**

```bash
git add hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java
git commit -m "feat(hbt): BINARY/BLOB 表征统一 byte[]，翻转 knownGap（I-DEBT-35）"
```

---

### Task 5: 零回归闸 + 驾驶舱/看板对齐

**Files:**
- Test: 跨模块全量
- Modify: `docs/superpowers/specs/DEBT-QUEUE.md`、`docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`

**Interfaces:**
- Consumes: 前 4 任务产物

- [ ] **Step 1: 跑受影响模块全量测试**

Run（JAVA_HOME=LibericaJDK-21）：`mvn -q -pl common,hbt -am test`
Expected: 全绿；任何红须 root-cause 到本改动，非夹带。特别核 `MycatMySQLRowMetaDataColumnTypeTest`、`ObjectArrayDecoderDataTypeTest`、`TypeRepresentationThreePathConsistencyTest` 其余格零回退。

- [ ] **Step 2: 记录 e2e NO_DATA 与本地绿证据**

在 DEBT-QUEUE 将 I-DEBT-34 跨片、I-DEBT-35 状态更新为：本地 in-process 逻辑锁 GREEN（列头 payload + 行解码归一 + BINARY 表征），**权威跨片 e2e `caseCrossShardBitColumnFidelity` 标 NO_DATA 待 Docker 2 分片 infra**；不得写"跨片 e2e 已通"。

- [ ] **Step 3: 更新 PROGRESS 看板指针行 + 本轮条目**

顶部插 `🆕 2026-10-13` 条目：两 meta 面根因坐实、Task1-4 本地关、e2e NO_DATA 边界诚实标注、距 M 系列剩余。

- [ ] **Step 4: 提交**

```bash
git add docs/superpowers/specs/DEBT-QUEUE.md docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -m "docs(compat): 记录跨片列头保真+BINARY 本地关，标 e2e NO_DATA（驾驶舱对齐）"
```

---

## 执行注记

- Task 3 Step 3 是"定位列头取源缝"的小范围探索，非设计决策——决策（发归一忠实 meta）已定；实现者在候选文件内定位，若发现需把首分片 meta 上抛涉及较大改动，记 `Ruling` 并按最小改满足 INV-①。
- Task 2/3 的跨片 e2e（需 2 分片真库）本轮 NO_DATA；逻辑锁（Task1/2/3 in-process）即本 spec §4 的"逻辑锁先行"。
- 严守：BIT 的 codegen `getJavaClass(BOOLEAN)` 不动（Task4 Step4 显式保护），BIT 修复在行/列头层非 codegen 层。
- 测试 fixture：`faithfulBitMeta()`（用 `ColumnDefPacketImpl` 置 field type=16 包进 `MycatMySQLRowMetaData`）、`numericPlanMeta()`（`ResultSetBuilder` 造 BIGINT 列 meta）、`firstColumnDefPayload()/fieldTypeIdOf()`（调 `generateAllColumnDefPayload` 取第 0 列并解析 field type 字节）均为**各测试文件内私有静态 helper**；Task1/2 在 hbt 测试作用域、Task3 在 common 测试作用域，跨模块不共享，重复构造即可。`faithfulBitMeta()` 三处须一致语义（忠实轨、BIT 列）。
