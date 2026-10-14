# 类型表征统一收尾：跨片列头保真 + BINARY 表征 — 设计规格

**Goal:** 让跨片/本地计划结果集的列头元数据以"后端物理 MySQL 类型"为真相源，根治 `bit(n>1)` 等列在跨片归并时列头被降型（BIT→BOOLEAN）的保真缺陷，并把 BINARY/BLOB 的运行时表征统一到官方 `byte[]` 口径。

**Architecture:** MyCat 存在两把列头尺子——忠实轨 `MycatMySQLRowMetaData(columnDefPackets)`（单片/后端回包路径）与有损轨 `CalciteRowMetaData(relDataTypeFieldList)`（计划派生路径，因 fork 无 `SqlTypeName.BIT` 且 `Types.BIT→BOOLEAN` 而归一化掉 BIT）。本 spec 让跨片结果在归并点复用忠实轨作列头真相源（方案 B 主干），仅对 MyCat 本地计算、后端无对应字段包的列保留物理类型 side-channel（方案 C 兜底）。零 calcite-core 改动。

**Tech Stack:** Java 21、Calcite 1.42（fork，`calcite-core`）、Vert.x MySQL 协议解码、MycatRowMetaData 双实现、hbt 结果装配层、JUnit 兼容矩阵。

**Spec（本文件即规格）** 派生自既有类型表征统一设计 `2026-10-10-type-representation-unification-design.md`，收尾其挂账的最后一格（跨片列头保真 + BINARY 表征）。

## Global Constraints

- **严禁修改 fork 的 `calcite-core` 内部枚举**（不加 `SqlTypeName.BIT`，不动 `Types.BIT→BOOLEAN` 映射）。这是既有禁忌裁定。
- **BIT 对外契约（官方连接器口径）**：`BIT(1) = java.lang.Boolean`；`BIT(n>1) = byte[]`，wire 层按 `(n+7)/8` 字节大端原始串下发，字段类型 `MYSQL_TYPE_BIT`。Mycat 前端不代连接器猜类型。
- **BINARY/BLOB 契约**：`BINARY / VARBINARY / LONGVARBINARY / BLOB` 运行时行值统一 `byte[]`（非 `ByteString`）。
- **零回归**：当前已绿的 varbinary / date / 靶1(FLOAT) / 靶2(TIMESTAMP) 表征格不得回退，由结构守卫钉住。
- **诚实验收**：跨库 e2e 需两分片真库 infra——本轮 infra 已跑通（见 §4），主门 `caseCrossShardBitColumnFidelity` 须以真实 RED→GREEN 验收，in-process 逻辑锁作辅验，**不得以逻辑锁绿冒充跨片 e2e 已过**。
- **本轮范围**：以 `bit(n>1)` 跨片列头的两缝同移 wiring（§2 方案 B）为交付主体与验收门；§3 BINARY 表征格属内部表征统一（另一缝），仅在结构守卫不回归前提下附带推进，不得重踏半翻→CCE 的坑。
- 推进与结论一律以当前代码 `file:line` + 实跑测试输出独立复核，不信既有记录。

---

## §1 病根定性与不变式

**定性：** 跨片列头丢 BIT 不是算法 bug，而是**元数据选源错误**。

证据（当前代码逐环坐实）：

| 环节 | 事实 | 证据 |
|---|---|---|
| fork 类型系统 | 无 `SqlTypeName.BIT`；`SqlTypeName.java:300` 将 `Types.BIT → BOOLEAN` | fork `calcite-core` `SqlTypeName.java:300` |
| 有损轨派生 | `CalciteRowMetaData.getColumnType` = `getSqlTypeName().getJdbcOrdinal()`，BIT 列经 BOOLEAN 归一 → ordinal 非 `Types.BIT` | `hbt/.../calcite/resultset/CalciteRowMetaData.java:109-116` |
| 有损轨唯一构造 | 全仓 14 处均 `new CalciteRowMetaData(relDataTypeFieldList)`，列头只认 RelDataType，拿不到物理类型 | grep `new CalciteRowMetaData` |
| 忠实轨 | `MycatMySQLRowMetaData.getColumnType` = `DataType.parseDataType(后端 columnDefPacket…).getVendorTypeNumber()`，BIT 保真 | `common/.../beans/mycat/MycatMySQLRowMetaData.java:77-85` |
| 忠实轨构造点 | 单片/后端路径由解码器 `getColumnDefPackets()` 构造 | `server/.../mysqlclient/VertxMycatConnectionPool.java:82`；`common/.../newquery/NewMycatConnectionImpl.java:700` |
| 写出路径 | 列头/行值序列化均读 `rowMetaData.getColumnType(i)`——换 meta 即换列头 | `server/.../vertx/ResultSetMapping.java:46`；`hbt/.../vertx/BaseRowObservable.java:121,319`；`proxy/.../resultset/BinaryResultSetResponse.java:35` |

**INV-①（对外列头契约）：** 凡后端字段包可达的列，跨片/归并结果集写出的列头类型必须等于后端物理 MySQL 类型（含 `MYSQL_TYPE_BIT`），而非 Calcite RelDataType 派生 ordinal。

**INV-②（内部表征一致）：** `MycatRelDataTypeFactory.getJavaClass` 声明面与三条 codegen 路径（投影/聚合/排序）的运行时行值表征必须一致；BIT/BINARY 的运行时权威表征为 `byte[]`。

**INV-③（零回归）：** 单片路径、已绿表征格行为不变。

**INV-④（两缝同移，不可半翻）：** 跨片 `bit(n>1)` 的**行解码缝**（`VertxExecuter.runQuery` 的 `onColumnDef`→`BaseRowObservable.getObjects`）与**响应列头缝**（`PrepareExecutor.getMysqlPayloadObjectObservable` 的 `MySQLColumnDef`）必须以同一把忠实尺子（`RowMetaDataFidelity.effective`）**一并**切到后端物理类型——只翻其中一缝即为沉默数据损坏：行值 `byte[]` 撞数值列头经 `ResultSetMapping` default 分支 `Objects.toString(byte[]).getBytes(UTF_8)`=`[B@...` 落库，比原两缝皆数值时的响亮崩溃更坏。此为 2026-10-13 类型表征专轮 CodeReview Critical#1 的根因（当时仅接行解码缝，被回退），本 spec 的验收不变式即此条。

---

## §2 跨片列头保真路径（两缝同移的具体契约）

**现状断点（当前代码坐实）：**

- **缝一（列头装配）** `ObservablePlanImplementorImpl.executeQuery:242-243` 把 `plan.getMetaData()`（有损计划轨）传入 `PrepareExecutor.getMysqlPayloadObjectObservable(...)`，L77 `new MySQLColumnDef(rowMetaData)` 定死列头——在后端跑之前就用了有损尺子。
- **缝二（行解码）** `VertxExecuter.runQuery:898-907` 的 `onColumnDef` 里，后端忠实 `mycatRowMetaData`（入参）被 `Optional.ofNullable(rowMetaDataArg).orElse(...)` 丢弃（`rowMetaDataArg`=有损计划轨非 null），行解码也用有损轨 → bit `byte[]` 撞 `BaseRowObservable` 数值分支抛 `UnsupportedOperationException`（活 e2e 已复现）。
- **上抛缺口**：`getObservables:137-183` 只回传解码行 `Observable<T>`，后端忠实 meta 锁在 `runQuery` 的 collector 内部，**无跨缝通道**——这正是两缝断点。

**通道可行性（本轮已核）：** `executeQuery` 建的 `sqlMycatDataContext` 既是 `getMysqlPayloadObjectObservable` 的 `newMycatDataContext` 入参，又是 `bindable.bind(root)` 的 `root`（codegen 内 `root.getObservableList(node)`→`getObservables`(即 `this`)→`runQuery`）。**两侧同实例**，故该 per-query 上下文对象是上抛后端 meta 的天然通道，无需新增跨层载体。且列头已惰性化（I-DEBT-39 `headerSent` 门，`PrepareExecutor:91-93` 首行才发），后端 `onColumnDef` 于 `VertxMycatConnectionPool:131` 的 `onColumnEnd` 触发、**必早于首行** → 列头惰性重建时后端 meta 已就位，无竞态。

**机制（方案 B 主干 —— 后端 `columnDefPackets` 作列头真相源）：**

1. **侧信道载体**：`AsyncMycatDataContextImpl` 加 `final AtomicReference<MycatRowMetaData> backendRowMeta = new AtomicReference<>()`（@Getter 暴露）+ `publishBackendRowMeta(MycatRowMetaData)`（首写者胜 `compareAndSet(null, meta)`）。
2. **最小改动面（闭包绑定，不动 `Queryer`/`getObservables`）**：`VertxExecuter.runQuery` 新增一个带 sink 的重载 `runQuery(conn, sql, values, rowMetaDataArg, Consumer<MycatRowMetaData> backendMetaSink)`，原 4 参方法委托之（sink=`m->{}`）——因 `server/.../ReceiverImpl.java:208` 等 4 参直调点传 `rowMetaDataArg=null`（单结果集直出口），保留重载即零冲击。三处 lambda 调用点（`AsyncMycatDataContextImpl.getObservableList` 两实现 L205/L246 + `MycatSQLTableLookup.dispatchRightObservable` L272）改为 5 参并闭包捕获 `this::publishBackendRowMeta`（或 `sqlMycatDataContext::publishBackendRowMeta`）——`Queryer` 接口与 `getObservables` 不动。`runQueryOutputAsMysqlPayloadObject`（单片轨）不动。
3. **缝二行解码**：`runQuery.onColumnDef` 内改 `this.mycatRowMetaData = RowMetaDataFidelity.effective(<后端忠实入参>, rowMetaDataArg)`，并 `sink.accept(<后端忠实入参>)`——bit `byte[]` 经 `BaseRowObservable` 既有 `case BIT:` 透传。
4. **缝一列头**：`getMysqlPayloadObjectObservable` 把 `columnDef` 构造从 L77 急切改为 `headerSent` 首次置真时惰性 `new MySQLColumnDef(RowMetaDataFidelity.effective(ctx.getBackendRowMeta(), rowMetaData))`。

**零回归兜底：** `effective` 仅当后端是 `MycatMySQLRowMetaData` 且列数 == 计划 meta 列数才归一（`FaithfulTypeView`：类型取后端、列名/label 等委托计划）；后端 meta 缺失（非分片源）或列数不符（本地聚合列）时原样返回计划轨 → status quo，不崩。单片与跨片由此收敛到同一把尺子（`DataType.parseDataType`）。

**方案 C 兜底（本轮 defer，YAGNI）：** 纯 MyCat 本地计算/聚合列（后端无对应 `columnDefPacket`、列数与计划不符）应从 schema DDL 物理类型取 side-channel。本轮活靶为纯 identity 跨片 select，`effective` 列数守卫已让其自动落回计划轨（聚合 bit 列无 `byte[]` 行值，维持 status quo），故 C 显式留待有靶另轮。**边界：** 后端计算的表达式列其后端 `columnDefPacket` 仍忠实，走 B。

---

## §3 BINARY/BLOB 表征格（I-DEBT-35）

- 同一把尺子推广至 `BINARY / VARBINARY / LONGVARBINARY / BLOB`：跨片列头 = 后端 `Types.BINARY` 等；运行时行值统一 `byte[]`。
- `MycatRelDataTypeFactory.getJavaClass` 对 BINARY 家族的声明面对齐 `byte[]` 运行时口径（消除 `ByteString` vs `byte[]` 的三路径错配）。
- 结构守卫 `TypeRepresentationThreePathConsistencyTest` 补 BINARY 那一格：靶1(FLOAT)、靶2(TIMESTAMP) 已关，本格是表征统一收尾的最后一格。

**与"不夹带"裁定的关系：** 2026-10-12 曾裁"本次 spec 仅落 BIT 样板、#35 留另轮"。本轮用户原话点名"类型表征统一"，#34(BIT) 与 #35(BINARY) 共享"列头/表征真相源"同一个根，拆开留半洞，故按当前指令并入本 spec。此为对历史裁定的**显式覆盖**，记录于此。

---

## §4 测试靶与验证策略（RED→GREEN）

| 靶 | 内容 | 当前态 | 断言 |
|---|---|---|---|
| 主 RED | `caseCrossShardBitColumnFidelity`：跨片 `bit(n>1)` 列，双连接对拍（后端直连真库 vs Mycat 前端 `getObject` 逐字节） | **活红**（infra 已起，当前复现 `SQLException: UnsupportedOperationException: [B@...`） | `getColumnType==Types.BIT` 且行值字节逐一相等 |
| 副 RED | 跨片 binary/blob 表征对拍 | 红 | 列头 `Types.BINARY`，行值 `byte[]` |
| 守卫 | `TypeRepresentationThreePathConsistencyTest` BINARY 格 | 先红后绿 | 声明↔运行时三路径一致 |

**in-process 逻辑锁：** 构造跨片两列头 meta + 行值，喂进列头写出路径，断言字节与 `getColumnType`——无需真库即可证 §2/§3 逻辑正确性。

**e2e 跨库对拍（本轮为真门，NO_DATA 口径作废）：** 两分片真库 infra 已跑通（`m1-mysql-a:3306`/`m1-mysql-b:3307` mysql:8.0.46 + Mycat@8066 persona MYSQL_8_0，服务端 PID 存 `.mycat-e2e-server.pid`）。主门 = `UserCaseTest#caseCrossShardBitColumnFidelity` 当前活复现 `UnsupportedOperationException:[B@...`；落地须证其 **RED→GREEN**，并以跨片全矩阵回归（BOOLEAN/JSON/DATE 等列头不破）钉住 INV-④/零回归。跑法：`mvn -o -pl example -am test "-Dtest=UserCaseTest#caseCrossShardBitColumnFidelity" "-Dsurefire.failIfNoSpecifiedTests=false"`。**不得以逻辑锁绿冒充 e2e。**

---

## §5 影响面 / 回归

- **零 calcite-core 改动**（禁忌守住）。改动集中在 hbt 归并/元数据装配 + `ResultSetMapping`/列头取源路径。
- 已绿 varbinary / date / 靶1 / 靶2 表征格由结构守卫保证零回归。
- **反查：** 确认无模块依赖"跨片列头恒为 BOOLEAN"这一有损行为（改动前逐构造点核）。

## §6 非目标

- 不含 caseI36 / TIMESTAMPDIFF 跨片 deparse（属 Spec②）。
- 不加 `SqlTypeName.BIT`、不改 fork 枚举。
- 不改 BIT/BINARY 行值序列化逻辑（R5 已固化，仅补列头）。

## Review Focus（规格隐含但测试未必全覆盖的失败模式）

1. **两缝同移不可半翻（INV-④）**：行解码缝与列头缝必须同时接 `effective`；只接一缝 → 行值 `byte[]` 撞数值列头经 `ResultSetMapping` default `toString().getBytes`=`[B@...` 沉默腐化（上轮 Critical#1）。审查者须确认两缝在同一变更集内一并翻。
2. **跨片其它类型零回归**：`effective` 对列数相符的 identity 结果集会把**所有**列的 `getColumnType` 改取后端——BOOLEAN(bit1)/JSON/DATE/TIMESTAMP 等跨片列头不得因此翻车（全矩阵 e2e 钉）。
3. **本地聚合/非分片源兑底**：后端 meta 缺失或列数不符时须原样回计划轨（`effective` 已守），确认不出现 NPE 与误归一。
4. **`getColumnLength` 遗留格**：`FaithfulTypeView` 未覆写 `getColumnLength`（上轮 deferred Minor#4），BIT 列头 length 委派计划轨——活 e2e 若暴露 Connector/J 定长截断则本条提升为必修。
5. **多分片列头异构**：各分片 `columnDefPacket` 顺序/类型不一致时，取首片（首写者胜）是否安全；identity 结果集各片同构为本机制前提假设。
6. **e2e 诚实性**：主门已可活跑，不得以 in-process 逻辑锁绿冒充跨片 e2e 已过。

---

## 与既有资产的关系

- 续做 `docs/superpowers/specs/2026-10-10-type-representation-unification-design.md`（靶1/靶2 已关，本 spec 收尾跨片列头保真 + BINARY 表征格）。
- 复用 I-DEBT-33 已固化的 `MycatMySQLRowMetaData.getColumnType` 忠实模式与 `DataType.parseDataType` 签名修正。
- 承接 R5 `e35f44ba2` 的 `BaseRowObservable` BIT/BOOLEAN `byte[]` 透传。
