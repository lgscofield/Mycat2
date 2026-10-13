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
- **诚实挂账**：跨库 e2e 对拍需两分片真库 infra，本 spec 落地期以 in-process 逻辑锁验证，完整跨库对拍标 `NO_DATA` 待 infra 补跑，不得谎报已 e2e。
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

---

## §2 跨片列头保真路径（BIT）

**方案 B 主干 —— 复用后端 `columnDefPackets` 作列头真相源：**

- 跨片/归并/本地计划结果集在装配列元数据时，保留各分片后端回包的 `columnDefPackets`（至少首片，identity 结果集各片同构），以其构造 `MycatMySQLRowMetaData` 作列头真相源，`getColumnType` 天然返回 `Types.BIT`。
- 单片与跨片由此收敛到同一把尺子（`DataType.parseDataType`），列头口径统一。

**行值：** `bit(n>1)` 后端返回 `byte[]`，`BaseRowObservable` 的 BIT/BOOLEAN 分支已在 R5 加入 `byte[]` 透传（`e35f44ba2`）。本 spec 只补"列头"这一半，**不动行值逻辑**。

**方案 C 兜底 —— 本地计算列 side-channel：**

- 当某列由 MyCat 本地聚合/计算产生、后端无对应 `columnDefPacket` 时，从 schema DDL 声明的物理类型取真相源，作 side-channel 喂给列头，避免落到有损轨。
- 兜底仅在归并点确取不到后端字段包时启用，不改变 identity/可归并结果集走 B 的主干。

**边界：** 表达式列若由后端计算（MySQL 服务端出结果类型），其后端 `columnDefPacket` 仍忠实，走 B；仅纯本地计算列走 C。

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
| 主 RED | `caseCrossShardBitColumnFidelity`：跨片 `bit(n>1)` 列，双连接对拍（后端直连真库 vs Mycat 前端 `getObject` 逐字节） | 红 | `getColumnType==Types.BIT` 且行值字节逐一相等 |
| 副 RED | 跨片 binary/blob 表征对拍 | 红 | 列头 `Types.BINARY`，行值 `byte[]` |
| 守卫 | `TypeRepresentationThreePathConsistencyTest` BINARY 格 | 先红后绿 | 声明↔运行时三路径一致 |

**in-process 逻辑锁：** 构造跨片两列头 meta + 行值，喂进列头写出路径，断言字节与 `getColumnType`——无需真库即可证 §2/§3 逻辑正确性。

**e2e 跨库对拍（标 NO_DATA 挂账）：** 需两分片真库。本 spec 落地期以逻辑锁先行验证；完整跨库对拍标 `NO_DATA`，待用户已授权的 Docker infra 在架构专轮之后起来补跑。**不得谎报 e2e 已过。**

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

1. **纯本地聚合列走 C 兜底**：`SELECT bit_col FROM t GROUP BY …` 跨片、后端无该列字段包时，side-channel 是否真拿到 `bit` 物理类型而非再落 BOOLEAN。
2. **多分片列头异构**：各分片 `columnDefPacket` 顺序/类型不一致时，取首片是否安全。
3. **BINARY 与 VARBINARY 区分**：`getJavaClass` 统一到 `byte[]` 后，列头仍须分别保 `Types.BINARY`/`Types.VARBINARY`，不得并为单一格。
4. **二进制协议 vs 文本协议**：`BinaryResultSetResponse` 与 `ResultSetMapping` 两条写出路径列头口径须一致。
5. **e2e 缺口诚实性**：跨库对拍 NO_DATA 不得被逻辑锁绿冒充为已 e2e。

---

## 与既有资产的关系

- 续做 `docs/superpowers/specs/2026-10-10-type-representation-unification-design.md`（靶1/靶2 已关，本 spec 收尾跨片列头保真 + BINARY 表征格）。
- 复用 I-DEBT-33 已固化的 `MycatMySQLRowMetaData.getColumnType` 忠实模式与 `DataType.parseDataType` 签名修正。
- 承接 R5 `e35f44ba2` 的 `BaseRowObservable` BIT/BOOLEAN `byte[]` 透传。
