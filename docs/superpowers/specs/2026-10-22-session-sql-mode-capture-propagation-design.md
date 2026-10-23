# 会话 sql_mode 捕获与传播 设计（I-DEBT-54 D2b）

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现本设计衍生的计划。

**Goal:** 让 Mycat 每会话捕获客户端 `SET sql_mode`、回读返真值，并让**本地 Calcite 分组校验的 `ONLY_FULL_GROUP_BY` 严格性跟随会话**——消除 mysqldump 默认口径 dump tablespaces 时因会话 `SET @@SQL_MODE=''` 被丢弃、MyCAT 本地 `AggChecker` 仍按严格模式对 `i_s.FILES`/`i_s.PARTITIONS` 的非严格 GROUP BY 抛 `CalciteContextException: ... is not being grouped` 的破面。

**Architecture:** 单阶段收口 + 一条另论支线。**正路径 = B 层门控**：会话持一份原始 sql_mode 串（捕获+回读，已在案），校验入口据该串派生 `onlyFullGroupBy` 布尔喂给 validator（第一刀机制在工作树、待接通生产入口+e2e）。**另论支线 = A 层传播**（把会话 sql_mode 下发到后端连接）：经实证**对 mysqldump D2b 症状不承重**（症状发生在本地校验、i_s 仿表从不下推后端），且其原定接线点为**死码**——摘出本债，待观测到"下推后端真报 1055"的实测证据后，另立债按活路径以 Vitess 式 per-query `SET_VAR` hint 实现。

**Tech Stack:** Java 21 / Calcite fork（calcite-core）/ Netty 式 NIO 代理（proxy、server）/ hbt（Druid SQL + Calcite 编译层）。

**关联台账:** docs/superpowers/specs/DEBT-QUEUE.md — I-DEBT-54（本设计对应 d2-arch）；D2b 在驾驶舱第 28/54/56 行的定性="非致命 cosmetic、不破坏 RC=0、tablespaces GROUP BY 报 Calcite 错"——**与本 v2 一致**（v1 曾误改归因为后端 1055，见 §0 修订说明）。

---

## 0. 修订说明（v1 → v2，2026-10-23）

本版**反转了 v1 的脊柱**，由两类当前证据驱动（非旧记录）：

1. **症状归因被铁证证伪**（`.superpowers/d2-repro.err`、`d2-userdb.err`，mysqldump 默认口径实测）：真实报错是
   `org.apache.calcite.runtime.CalciteContextException: At line 0, column 0: Expression 'EXTRA' is not being grouped`
   ——异常类是 **Mycat 自己的 Calcite**（非 MySQL `errno 1055/1487`），发生在**本地校验期**，针对 **`i_s.FILES`/`i_s.PARTITIONS`**（`MysqlMetadataManager` 供应行的本地仿表，**从不下推后端**）。v1 §1 事实#1"下推路径不跑校验器→后端 1055"据此作废。
2. **v1 §4.3 传播接线点经实证为死码**：`AbstractMySqlConnectionImpl`（全仓无 live `new`、无子类）→ `RowSetQuery`（只能由它造，永不可达）→ `MySQLTaskUtil.syncState`（唯一调用点 `MySQLTaskUtil:122` 在注释块内）；`MySQLClientSession` 是前端 NIO passthrough/事务独占会话，非 Calcite 分片下推连接。**活的 executor 下推路径** = `AsyncMycatDataContextImpl.getConnection` → `MetaClusterCurrent.wrapper(MySQLManager)=MycatMySQLManagerImpl`（`MycatRouterConfigOps:671`）→ `MycatNativeDatasourcePool.getConnection(:40)` → `VertxMycatConnectionPool` → `VertxConnection.query/update`（NetSocket 自带 codec）。

**结论**：D2b 收口 = B 层门控（本地校验跟随会话），非 A 层传播。已在案的 Task1（捕获）/Task2（回读）/Task3（`SqlModeTokens`）**不废弃**——它们既是回读腿，也正好是 B 门控派生 `onlyFullGroupBy` 的输入源。

---

## 1. 实证基础（全部当前证据复核，非旧记录）

1. **mysqldump D2b 症状 = Mycat 本地 Calcite 校验错**（`.superpowers/d2-repro.err`/`d2-userdb.err`）：默认口径 dump tablespaces 报 `CalciteContextException: Expression 'EXTRA' is not being grouped`；该查询作用于 i_s 本地仿表，`EXTRA` 是 I-DEBT-54/D2 补的列（`MysqlMetadataManager.java:973`）。**非致命**：`d2-userdb.sql` 显示 dump 实际 completed、INSERT 数据在，与驾驶舱"不破坏 RC=0"一致。

2. **Mycat 把客户端 `SET SESSION sql_mode=''` 整个丢弃**：`MySQLVariablesUtil.setVariable`（common/.../MySQLVariablesUtil.java:9-37）无 `sql_mode` 分支；同会话 `SET SESSION sql_mode=''` 后回查 `@@session.sql_mode` 仍返完整严格模板。

3. **回读路径读的是静态模板**：`MycatDataContextImpl.getVariable(false, name)`（proxy/.../MycatDataContextImpl.java:163-189）对 sql_mode 落到 `MysqlVariableServiceImpl.getSessionVariable`（hbt/.../MysqlVariableServiceImpl.java:122-123），返 `sessionVariables` 静态 map（同文件 L54-96 填充，含默认严格模板），**无每会话覆盖**。

4. **本地校验器严格性与后端语义错位**：Calcite `AggChecker` 对非严格 GROUP BY（裸列不在 GROUP BY）恒抛 `notGroupExpr`；MySQL 是**按会话 `sql_mode` 动态开关**该检查。代理"比后端严"违反生态原则，故 Mycat 须让 validator 依会话态决定是否放宽（见 §4）。

**证伪的旧假设（须记住，别再据此设计）：**
- `MySQLSynContext`/`getSyncSQL()`（proxy/.../handler/backend/MySQLSynContextImpl.java）是 **死码**——全仓仅自身定义 + `PrepareInfo` 一处被注释掉的引用。**不能**"往它加个字段"。
- 数据源 `initSqls` 是 **死配置**（全仓 `.java` 零消费，印证 I-DEBT-31）。**不能**用它注入 sql_mode。
- **v1 更正**：`MySQLTaskUtil.syncState()`（server/.../commands/MySQLTaskUtil.java:192-226）**亦为死码**——唯一调用点（:122）位于 `proxyBackendByDataSource` 整体注释块内。v1 称其"唯一活下发路径"**错误**。真正的活下发路径是 `VertxMycatConnectionPool`/`VertxConnection`（见 §0 修订说明第 2 点）。

---

## 2. 目标与非目标

**目标（=D2b 收口面，本设计承做）：**
- 每会话捕获 `SET [SESSION] sql_mode = <expr>`，存原始串。（已在案，Task1）
- `SELECT @@session.sql_mode` / `SHOW VARIABLES LIKE 'sql_mode'` 返该会话真值（未 SET 时返后端默认基线）。（已在案，Task2）
- **本地/跨片校验：分组校验严格性跟随会话**——会话 `sql_mode` 不含 `ONLY_FULL_GROUP_BY` 时，Mycat validator 放行非严格 GROUP BY，使 mysqldump 默认口径不再抛 `is not being grouped`。（**本债正路径**，§4，待接通+e2e）

**非目标（本设计不做，另立债）：**
- **A 层向后端传播会话 sql_mode**：经 §0 实证对 mysqldump D2b 不承重，且接线点为死码。**摘出本债**；若将来要（须先拿到"透明下推路径后端真报 1055"的实测证据），按活路径 `VertxConnection`/`VertxMycatConnectionPool` 做，且**优先 Vitess 式 per-query `SET_VAR(sql_mode='…')` hint 随语句带**（不碰复用连接态→无串味→无需 diff-replay），而非在连接层做 ProxySQL 式 diff-replay。
- **解析级 sql_mode**（`ANSI_QUOTES`、`NO_BACKSLASH_ESCAPES` 等改变词法/语法的模式）：需 Vitess 式 sqlmode 位集解析，改动面与回归风险显著更大，且非 mysqldump 症状路径。本次只处理 `ONLY_FULL_GROUP_BY`（校验级）。→ 挂新 I-DEBT。
- `SET GLOBAL sql_mode`：`SetSQLHandler`（server/.../sqlhandler/dml/SetSQLHandler.java:89-116）对 GLOBAL 走 default 分支即抛/忽略，维持现状。
- 不复通 `MySQLSynContext`/`syncState`/`initSqls` 等死码。

---

## 3. 值语义定案

- **单一数据源**：前端会话（`MycatDataContextImpl`）持一份原始 sql_mode 字符串 `sessionSqlMode`（可为 `null` 表"未显式设置，跟随后端默认模板"）。
- **两消费者共用这一份**（v2：删去"传播"消费者，因 A 层摘出）：
  1. 回读：`getVariable(false,"sql_mode")` 优先返 `sessionSqlMode`，为 null 时回落现有静态模板。（在案）
  2. 校验门控：从 `sessionSqlMode` 派生布尔 `containsOnlyFullGroupBy`（大小写不敏感、按逗号/空白 tokenize 后精确匹配 token，避免子串误判被别的模式名污染），喂 `SessionSqlMode.setOnlyFullGroupBy()`。（Task3 `SqlModeTokens` 在案，消费待接）
- **缺省严格**：未捕获到会话值时 `onlyFullGroupBy = true`，保持 Calcite 原生行为与所有既有 golden 基线，零回归。

---

## 4. 正路径设计：会话态 → 本地校验门控

### 4.1 捕获（write，已在案 Task1）
- `MySQLVariablesUtil.setVariable`（common）加 `sql_mode` 分支：识别 target 含 `sql_mode`（去 `@@`、`session.`、`global.` 前缀，session 域处理），把 normalize 后原始串写入 `dataContext` 会话 sql_mode 槽。
- `MycatDataContextImpl` 新增 `String sessionSqlMode` 字段 + `get/setSessionSqlMode`。
- 捕获时机即 `SetSQLHandler` L111 `dataContext.setVariable(name, value)` 已调用。

### 4.2 回读（read，已在案 Task2）
- `MycatDataContextImpl.getVariable(false, target)`（proxy:163-189）：命中 sql_mode 且 `sessionSqlMode != null` 时直接返之；否则维持现有静态模板行为。

### 4.3 校验门控（本债核心，第一刀机制在工作树，待接通生产入口 + e2e）
- **Calcite 侧（calcite-core 已改）**：`SqlValidator.Config.onlyFullGroupBy()`（`@Value.Default true`）+ `withOnlyFullGroupBy`；`AggChecker`：`if (!distinct && !validator.config().onlyFullGroupBy()) return null;` 放宽 GROUP BY 的 `notGroupExpr`；SELECT DISTINCT 分支仍抛（标准 SQL，不受 sql_mode 影响）。
- **hbt 侧（第一刀在工作树）**：`MycatCalciteSupport.getValidatorConfig()` `.withOnlyFullGroupBy(SessionSqlMode.isOnlyFullGroupBy())`；`SessionSqlMode`（ThreadLocal，缺省严格、查询末 reset 防线程复用串味）。
- **B 层增量（Task5 承做）**：代理查询入口在编译/校验前，按 §3 会话 `sessionSqlMode` 派生的 `containsOnlyFullGroupBy` 调 `SessionSqlMode.setOnlyFullGroupBy(...)`；查询后 `reset()`。**须坐实**：会话旋钮在**生产 validator 构建/生效点**真接通（非仅 `OnlyFullGroupBySqlModeTest` 的 validate-only harness），入口 `MycatdbCommand.executeQuery/execute` 编译前 set、finally reset。
- **机制选型注（Calcite 正道）**：v1 第一刀把布尔塞在 `AggChecker`/config；Calcite 官方意图扩展点是 `SqlDelegatingConformance` 覆写 conformance 方法、挂 `SqlValidator.Config.withConformance()`。二者皆可；实现阶段可择 `withOnlyFullGroupBy`（最小改动、已在工作树）或 delegating conformance（更贴 Calcite 惯例），由计划阶段定并保证零回归。
- **数据流**：
```
客户端 SET sql_mode='' → SetSQLHandler → MySQLVariablesUtil(新增分支) → MycatDataContext.sessionSqlMode=''
业务/元数据 非严格 GROUP BY 查询 → 入口: containsOnlyFullGroupBy(sessionSqlMode)=false
   → SessionSqlMode.setOnlyFullGroupBy(false) → validator.config.withOnlyFullGroupBy(false)
   → AggChecker 放行 → 查询正常规划/执行（i_s.tablespaces 不再抛 is not being grouped）
   → finally SessionSqlMode.reset()
回读 SELECT @@session.sql_mode → getVariable(false) 命中 sessionSqlMode → 返 ''
```

### 4.4 SPM 缓存策略：定案 S2（缓存会话无关，承自 v1，仍成立）
- 现状：`MemPlanCache.map : ConcurrentHashMap<Constraint, Baseline>`（hbt/.../spm/MemPlanCache.java:32），`Constraint={sql,paramTypes}`（Constraint.java:15-17），全局、会话无关。
- **核心判据**：`ONLY_FULL_GROUP_BY` 是"接受/拒绝"门，**不改变计划拓扑**（跨片切分/归并出的 MycatRel 与该模式无关，只决定这条查询准不准进）。
  - **S1（编进缓存键）= 过度工程**：为同一拓扑凭空复制两份 baseline，且改内存键+磁盘 JSON 格式，需处理旧 baseline 反序列化新字段默认值 = 地基爆炸半径。
  - **S2（定案）**：缓存保持会话无关；会话相关的"是否放行"放在**查询入口的校验门**（§4.3，命中缓存前即按会话 sql_mode 校验，被拒的查询根本不进缓存使用路径）。
- **命中后一致性注意点（计划阶段须验证）**：`getConstantMap(baseline)`（MemPlanCache.java:149-159）缓存命中时会 re-parse/re-validate 且 `catch(Throwable)` 吞异常成空 map。S2 下入口门控已先行拒绝"本会话模式不允许"的查询；但须有测试钉住"宽松会话接受的计划、在严格会话复用同一 SQL 时被正确拒绝"与反向，确保全局缓存不串味。

---

## 5. 业界对标（spec 定案的外部印证，非凭记忆）

Mycat2 是**语义/规划代理**（跑 Calcite 本地校验），非透传薄路由（MySQL Router/MaxScale/老 Mycat 不本地校验→无此病）→ **对标 Vitess，不对标 ProxySQL**。

- **Vitess issue #20984**（2026-09，官方 in-progress）病根与 D2b 同构："VTGate asks an arbitrary shard for @@sql_mode and runs sessions that never set it under whatever each backend happens to be configured with."。其三 PR 分工印证本 v2 的分层：
  - 新增 `go/mysql/sqlmode` 包逐条校验 `SET` 值语义 ↔ 本设计 §4.1 捕获 + Task3 `SqlModeTokens`。
  - sql_mode 归 gate 会话所有、`SET`/`@@` 就地求值不 round-trip shard ↔ 本设计"会话态存 Mycat、回读返真值"。
  - **follow-up #20892"gate-side evaluation 应遵从 effective sql_mode"** 是与传播**平级的一等修复项** → 印证 v2 把 D2b 定性从"A 传播"翻成"B 本地门控"与业界分工一致（本地校验/求值跟随会话本身即独立正题，非退而求其次）。
- **传播接法**（若将来另立债做）：
  - **ProxySQL**（3.0.8 session-track、multiplexing、issue #916）：`SET` 记进 tracker、会话漂到别的后端连接时重放 tracked state = **diff-replay**；#916 即"连接复用 sql_mode 串味"经典坑，**脆弱**。
  - **Vitess 选法**：per-query `SET_VAR(sql_mode='…')` hint 随语句下发，不碰复用连接态 → 无串味、无需 applied 挂哪条连接 → **"死码 vs 热连接层"纠结直接消解**。→ 本设计 §2 非目标据此定为"真做优先 SET_VAR"。

---

## 6. 错误处理与边界
- `SET GLOBAL sql_mode`：维持现状（非会话捕获），不影响。
- sql_mode 串规范化：仅去 `@@[session.]` 前缀与外层引号，**不改动模式 token 内容**。
- `containsOnlyFullGroupBy` 判定：token 级精确匹配（逗号/空白分割 + 大写 trim），避免子串误判（如 `STRICT_TRANS_TABLES` 不得判为含）。
- 线程/虚拟线程复用：`SessionSqlMode` 每查询末 reset；缺省严格，未 SET 会话零变化。

---

## 7. 测试策略（TDD，红-绿-重构）
- **离线单测（免活库，进 CI/回归）**：
  - `MySQLVariablesUtil` 捕获 sql_mode → 会话态==原始串；回读命中。（在案）
  - `containsOnlyFullGroupBy` tokenize：`ONLY_FULL_GROUP_BY`/含之/不含/大小写/`STRICT_TRANS_TABLES`（不得误判含）。（在案 Task3）
  - **门控放宽**：`OnlyFullGroupBySqlModeTest`（hbt，工作树）覆盖 `lenientSessionAcceptsNonStrictGroupBy`（`select a,sum(b) group by c` 在 `setOnlyFullGroupBy(false)` 下放行）、`defaultStrictRejectsNonStrictGroupBy`（缺省仍抛）、DISTINCT 分支不受放宽。
  - **S2 串味钉测**：同 SQL 在宽松会话被接受、在严格会话被拒绝（反之亦然），证明缓存未把跨会话接受性泄漏。
- **活库 e2e（Docker 后端，判据级，Task5/6）**：
  - **生产入口接通钉测**：经真实 8066 查询入口（非 validate-only harness）跑一条非严格 GROUP BY 查询，宽松会话放行、严格会话仍拒。
  - **mysqldump 症状消失**：默认口径（不加 `--no-tablespaces`）dump 一个含 i_s.tablespaces GROUP BY 的库，**stderr 不再出现 `is not being grouped`/Calcite 1055 类错**、RC=0 逐值保真；回灌成功。
  - 会话内 `SET sql_mode=''` 后 `SELECT @@session.sql_mode` 返 `''`。
- **全量回归**：跨模块 build（JDK21）+ 既有 golden/矩阵测试零新红。

---

## 8. 风险与回滚
- **主风险（已随 v1→v2 大幅收窄）**：不再插桩下推热路径（A 传播摘出），只在查询入口做会话布尔门控 + validator config。
- **兼容性**：缺省严格 + 仅显式会话值才放宽，未 SET sql_mode 的存量会话与所有离线基线零变化。
- **剩余风险**：生产 validator 生效点若与 validate-only harness 有出入，门控可能不生效 → 以 §7 生产入口 e2e 钉死。
- **回滚**：门控为增量布尔旋钮，撤 `withOnlyFullGroupBy` 即退回原生严格；捕获/回读为增量分支，关掉即退回静态模板。

---

## 9. 分期与验收
- **阶段 1（正路径，消 mysqldump D2b 症状根）**：捕获(在案)+回读(在案)+token 派生(在案) → **接通本地校验门控**（§4.3 Task5：入口 set/reset + 生产 validator 生效点坐实）+ S2 缓存不串味钉测。验收=**活库 mysqldump 默认口径 tablespaces 报错消失、RC=0** + 宽松/严格会话放行/拒绝对称 + 全量零新红。
- **阶段 2（另论支线，本债暂不承做）**：A 层向后端传播。**前置条件**=先拿到"透明下推路径后端真报 1055"的实测证据；实现按活路径 `VertxConnection`/`VertxMycatConnectionPool` + Vitess 式 `SET_VAR` hint。无证据则不启动（避免为未观测症状改热连接层）。
- **死码清理**：`RowSetQuery`/`AbstractMySqlConnectionImpl`/`syncState`/误挂 hook 的 `MySQLClientSession` 归位——转**独立 C 层工程卫生债**挂号，不混进 D2b 收口主线（A 优先于 C，不被清理活拽偏）。
- 每阶段完成回 DEBT-QUEUE 仪表盘更新（第 28/54/56 行 D2b 定性随阶段 1 收口从"cosmetic 挂账"改"已修"），合入前 CodeReview 门禁（本 harness 内联实现 + 单一 CodeReview 子代理）。
