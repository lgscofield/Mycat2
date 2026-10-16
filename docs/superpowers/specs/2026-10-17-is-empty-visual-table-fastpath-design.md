# i_s 恒空仿表快速路径（I-DEBT-54 D1/D2）设计

## 背景与目标

真 mysqldump/mariadb-dump 二进制往返回灌是 M3 的招牌判据。上一轮取证（`.superpowers/m3-dumprt/mysqldump-roundtrip-evidence.txt`）坐实三个破面：

- **D1**：默认 mysqldump 发 `SELECT COLUMN_NAME, JSON_EXTRACT(HISTOGRAM, '$."number-of-buckets-specified"') FROM information_schema.COLUMN_STATISTICS ...`，在投影 codegen 阶段抛 `Unable to implement MycatProject`（JSON_EXTRACT 无可 codegen 的实现者 → `cannot translate call` → 被 implementor 裹成 `IllegalStateException`）。崩溃与返回行数无关（0 行也崩）。属 I-DEBT-50 同族。
- **D2**：`--tablespaces` 路径报 `Column 'EXTRA' not found`。触发查询尚未钉死；现 `information_schema.FILES` 仿表已补 TABLESPACE 相关列但无 `EXTRA`。
- **D3**：反向原生 mysqldump 对 `bit(8)` 导出 `_binary '\252'`(0xAA) 单字节，回灌进 Mycat 报 ERROR 1406 Data too long（binary 字面量表征链，I-DEBT-35/34 同族）。

**本轮范围：只 D1 + D2。D3 维持另立表征链专轮，本 spec 不触碰。** 目标：让真 mysqldump **默认无规避 flag** 连 Mycat 能完整导出（D1/D2 转绿），并逐值保真回灌；I-DEBT-54 因此缩窄为仅剩 D3。

## 核心决策：D1 走"恒空仿表快速路径"（D1-B，用户选定）

备选 D1-A 是在 AST 层把 JSON_EXTRACT 改写为可 codegen 形式（仿 I-DEBT-50 的 `MycatCalciteMySqlNodeVisitor.visit(SQLMethodInvokeExpr)` `case "IF"` L2015 模板）。用户裁定走 **D1-B**：在规划器层，当投影子树的**全部叶子都是静态恒空仿表**时，直接折叠为携带正确 rowType 的空结果节点，绕过对投影表达式的 codegen。

语义依据：真 MySQL 在无直方图统计信息时，`information_schema.COLUMN_STATISTICS` 本就返回 0 行，mysqldump 拿到空结果即跳过 —— 折叠为空是**忠实语义**，不是伪绿。严格谓词（掺任何真实后端表就不折叠）把回归面锁死。

## 现状代码锚点（已凭当前代码复核）

- `hbt/.../MysqlMetadataManager.java:238` `COLUMN_STATISTICS_TABLE_HANDLER = VisualTableHandler.createByMySQL(COLUMN_STATISTICS(), () -> Observable.empty())` —— scanAll 恒 empty。
- `MysqlMetadataManager.java:240` `FILE_TABLE_HANDLER = ...FILE_TABLE(), () -> Observable.empty()`；`FILE_TABLE()` L956-989 已含 `TABLESPACE_NAME`(L963)、`TOTAL_EXTENTS/INITIAL_SIZE/MAXIMUM_SIZE/AUTOEXTEND_SIZE`(L971-974, I-ME-2)，**无 `EXTRA`**。
- `MysqlMetadataManager.java:887-896` `COLUMN_STATISTICS()` 四列：`SCHEMA_NAME/TABLE_NAME/COLUMN_NAME/HISTOGRAM`（均 varchar(64)）。
- `MysqlMetadataManager.java:715-727` `addVisualTableIfAbsent(...)` 用 `VisualTableHandler.createByMySQL(value, () -> Observable.empty())` 建恒空仿表 —— 静态恒空标记的挂载候选点。
- `hbt/.../prototypeserver/mysql/VisualTableHandler.java`：`createByMySQL(sql, rowsProvider)` 从 DDL 建 LogicTable；`scanAll()` 返回 `rowsProvider.get()`（COLUMN_STATISTICS 恒 empty）。
- 崩溃抛出点：`hbt/.../calcite/MycatEnumerableRelImplementor.java:57` 与 `StreamMycatEnumerableRelImplementor.java:34` —— `rootRel.implement()` catch RuntimeException 后裹成 `IllegalStateException("Unable to implement " + ...)`。D1-B 折叠规则须使投影不再走到会崩的 codegen。
- 列集回归锁：`hbt/.../DumpSurfaceRegressionTest.java:67` `columnStatisticsCoversHistogramQuery`、`:74` `filesTableCoversTablespaceQuery`（`assertContainsAll` 锁）。

## 设计（四部分）

### 1. 静态恒空标记

在 `MysqlMetadataManager`（或 `VisualTableHandler`）为 scanAll 恒 empty 的仿表引入可判定谓词 `isStaticallyEmptyVisualTable(schema, table)`。数据源单一化：凡以 `() -> Observable.empty()` 构造的 handler 登记入集合，避免散落的布尔硬编码。规划器据此识别可折叠叶子。

### 2. D1 折叠规则（planner rule，核心）

新增规划规则：当 `MycatProject` 关系子树的**全部叶子都是静态恒空仿表**时，将该子树折叠为携带**正确 rowType**（列名、类型、可空性与真 MySQL 一致）的空结果节点，使投影表达式（含 JSON_EXTRACT）不再进入 RexToLix codegen，从而绕开崩溃抛出点。

- **严格谓词**：子树掺入任何非静态恒空叶子（真实后端表、含实数据的仿表如 ENGINES）即**不折叠**，保持既有行为。
- **正确 rowType**：空结果节点必须暴露与真库一致的列头，保证 mysqldump 读元数据结构时列名/列序正确，即使行为空。

### 3. D2（先复现再定）

D2 的触发查询尚未钉死。第一步是**取证**：用真 mysqldump `--no-defaults`（不加 `--no-tablespaces`）连 Mycat 抓取 tablespaces 路径发出的确切 SELECT，定位引用了哪些当前仿表缺失的列（疑似 `EXTRA`）。

据此：
- 若确系缺列，按 MySQL 官方 i_s 列定义在 `FILE_TABLE()`（或对应仿表）**补齐缺失列**，保证列头真值，并扩 `DumpSurfaceRegressionTest` 列集锁。
- 若该查询同样"全部叶子恒空"，则第 2 部分的 D1 折叠规则顺带绕过其 codegen，D2 随 D1 一并转绿。
- 两条路径不互斥：先复现，再据实选择；可能既补列头又受益于折叠。

### 4. 测试（三层）

1. **离线规划器锁**（internalexample `mode=local`）：断言对 JSON_EXTRACT 投影**不再抛 `Unable to implement MycatProject`**，且被折叠为空结果、列头正确。RED → GREEN。
2. **仿表列集锁**：扩 `DumpSurfaceRegressionTest`，锁 `COLUMN_STATISTICS`/`FILES`（含 D2 新补列）列集。
3. **端到端金标准**：docker `mysql:8.0` 真 mysqldump **默认无规避 flag** 连 Mycat（`host.docker.internal:8066`，明文 caching_sha2 需 `--get-server-public-key --ssl-mode=DISABLED`）→ RC=0 完整导出 → 回灌原生 MySQL 逐值保真。

## 边界与回写

- **D3 本轮不做**：完成 D1/D2 后，I-DEBT-54 在 DEBT-QUEUE 缩窄为"仅剩 D3（binary 字面量表征链，另立专轮）"。
- **回写仪表盘**：D1/D2 端到端转绿后，把 M3 完成度从"~85%（模拟口径）"诚实上调，标注真 mysqldump 默认口径已打通（剩余项：D3 + CI 徽标）。
- 遵循 Evidenced-Done 硬门：判 Done 须有可重跑证据；本地活库实跑记为 runtime-evidenced（本地✅ / CI⏳）。

## 不做的事（YAGNI）

- 不实现可 codegen 的 JSON_EXTRACT 算子（D1-A 路线，已被 D1-B 取代）。
- 不动 D3 binary 表征链。
- 不改认证/TLS/connector 矩阵（属其他里程碑）。
- 不 push、不改 CI workflow（用户已定 push 暂不）。

## 环境备忘

- JDK21：`D:\Program Files\BellSoft\LibericaJDK-21-Full`（须显式 JAVA_HOME）。
- fat-jar：`server/target/server-1.22-release-jar-with-dependencies.jar`。
- docker：m1-mysql-a/b（3306/3307，root/123456）、Mycat 8066。
- PowerShell 无 grep/head，用 Select-String/Select-Object；git commit 用 `-F <file>` 保 UTF-8。
