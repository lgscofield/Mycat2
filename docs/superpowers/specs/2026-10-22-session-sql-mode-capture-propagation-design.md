# 会话 sql_mode 捕获与传播 设计（I-DEBT-54 D2b）

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现本设计衍生的计划。

**Goal:** 让 Mycat 每会话捕获客户端 `SET sql_mode`，回读返真值、下推原样传播到后端连接、本地/跨片校验按会话严格性放宽——消除 mysqldump 因 `SET @@SQL_MODE=''` 被丢弃而撞后端 `ONLY_FULL_GROUP_BY` 报 1055 的破面。

**Architecture:** 分层两阶段。**A 层**（症状根路径）：前端会话持一份原始 sql_mode 串，做"捕获→回读→传播"三件事，传播复用 Mycat 已有的 `MySQLTaskUtil.syncState()` "换后端连接时 diff-replay"活路径。**B 层**（跨片窄面）：分组校验严格性跟随会话（第一刀机制已落地、缺省严格零回归），SPM 全局计划缓存保持会话无关（S2）。

**Tech Stack:** Java 21 / Calcite fork（calcite-core）/ Netty 式 NIO 代理（proxy、server）/ hbt（Druid SQL + Calcite 编译层）。

**关联台账:** docs/superpowers/specs/DEBT-QUEUE.md — I-DEBT-54（本设计对应 d2-arch）。

---

## 1. 实证基础（全部当前证据复核，非旧记录）

活库廉价探针（d2-probe，2026-10-22）+ 本仓代码实测，四条承重事实：

1. **纯单片可整体下推路径，Mycat 不跑自身 Calcite 校验器**，透明把 SQL 交后端判：后端 global sql_mode 严格 → 转发回 `ERROR 1055 ... 'probe.t.a'`（措辞=MySQL 原生、列限定=后端真实库名，非 Mycat 产生）；后端清空 sql_mode 并重启 Mycat → 同查询直接放行。**=> mysqldump 症状的真破面是"会话 sql_mode 未捕获 + 未传播到后端连接"，不是"Mycat 校验器过严"。**

2. **Mycat 把客户端 `SET SESSION sql_mode=''` 整个丢弃**：`MySQLVariablesUtil.setVariable`（common/.../MySQLVariablesUtil.java:9-37）无 `sql_mode` 分支；探针里同会话 `SET SESSION sql_mode=''` 后回查 `@@session.sql_mode` 仍返完整严格模板。

3. **回读路径读的是静态模板**：`MycatDataContextImpl.getVariable(false, name)`（proxy/.../MycatDataContextImpl.java:163-189）对 sql_mode 落到 `MysqlVariableServiceImpl.getSessionVariable`（hbt/.../MysqlVariableServiceImpl.java:122-123），返 `sessionVariables` 静态 map（同文件 L54-96 填充，含默认严格模板），**无每会话覆盖**。

4. **后端连接是复用/交换的（multiplexing），回收时不重置 SQL 会话态**：`MySQLDatasourcePool.addIdleSession`（proxy/.../MySQLDatasourcePool.java:77-101）归还连接只 `resetPacket`/清游标/置 idle，不碰 sql_mode/charset；`createSession`（同文件 L162-167）从 `idleSessions` 直接取出复用，可能是别的前端会话用过的。**=> 仅在会话建立时设一次 sql_mode 会串味，必须"每次换后端连接 diff-replay"。**

**证伪的旧假设（须记住，别再据此设计）：**
- `MySQLSynContext`/`getSyncSQL()`（proxy/.../handler/backend/MySQLSynContextImpl.java）是 **死码**——全仓仅自身定义 + `PrepareInfo` 一处被注释掉的引用，从未接线。**不能**"往它加个字段"。
- 数据源 `initSqls` 是 **死配置**（全仓 `.java` 零消费，印证 I-DEBT-31）。**不能**用它注入 sql_mode。
- 真正活着的下发路径只有 `MySQLTaskUtil.syncState()`（server/.../commands/MySQLTaskUtil.java:192-226），当前仅同步 isolation + autocommit。

---

## 2. 目标与非目标

**目标：**
- 每会话捕获 `SET [SESSION] sql_mode = <expr>`，存原始串。
- `SELECT @@session.sql_mode` / `SHOW VARIABLES LIKE 'sql_mode'` 返该会话真值（未 SET 时返后端默认基线）。
- 下推/单片路径：执行任何业务 SQL 前，保证所用后端连接的 sql_mode == 本会话期望值（不等则先补发 `SET sql_mode='...'`）。
- 本地/跨片路径：分组校验严格性跟随会话（第一刀已就绪）。

**非目标（本设计明确不做，另立债）：**
- **解析级 sql_mode**（`ANSI_QUOTES`、`NO_BACKSLASH_ESCAPES` 等改变词法/语法的模式）：需 Vitess 式 sqlmode 位集解析，改动面与回归风险显著更大，且非 mysqldump 症状路径。本次只处理 `ONLY_FULL_GROUP_BY`（校验级）。→ 挂新 I-DEBT。
- `SET GLOBAL sql_mode`：`SetSQLHandler`（server/.../sqlhandler/dml/SetSQLHandler.java:89-116）对 GLOBAL 走 default 分支即抛/忽略，维持现状；mysqldump 用 `SET @@SQL_MODE`（session 语义），不受影响。
- 不复通 `MySQLSynContext` 死码，不改 datasource `initSqls` 语义。

---

## 3. 值语义定案

- **单一数据源**：前端会话（`MycatDataContextImpl`）持一份原始 sql_mode 字符串 `sessionSqlMode`（可为 `null` 表"未显式设置，跟随后端默认"）。
- **三消费者共用这一份**：
  1. 回读：`getVariable(false,"sql_mode")` 优先返 `sessionSqlMode`，为 null 时回落现有静态模板。
  2. 传播：把 `sessionSqlMode` 原样（含所有模式，不只是 ONLY_FULL_GROUP_BY）回放到后端。原样透传 = 最贴 MySQL 语义。
  3. 校验门控：仅从 `sessionSqlMode` 派生一个布尔 `containsOnlyFullGroupBy`（大小写不敏感、按逗号/空白 tokenize 后精确匹配 token，避免 `...ONLY_FULL_GROUP_BY...` 子串误判被别的模式名污染），喂 `SessionSqlMode.setOnlyFullGroupBy()`。
- **缺省严格**：未捕获到会话值时 `onlyFullGroupBy = true`，保持 Calcite 原生行为与所有既有 golden 基线，零回归。

---

## 4. A 层设计：捕获 / 回读 / 传播

### 4.1 捕获（write）
- 在 `MySQLVariablesUtil.setVariable`（common）加 `sql_mode` 分支：识别 target 含 `sql_mode`（去 `@@`、`session.`、`global.` 前缀，session 域处理，global 域维持现状），把 normalize 后的原始串写入 `dataContext` 的会话 sql_mode 槽。
- `MycatDataContextImpl` 新增 `String sessionSqlMode` 字段 + `get/setSessionSqlMode`（或经现有 `MycatDataContextEnum.SQL_MODE` 走通用 setVariable/getVariable 枚举腿——计划阶段二选一，本设计只锁"存原始串、会话级、单一权威源"）。
- 捕获时机即 `SetSQLHandler` L111 `dataContext.setVariable(name, value)` 已调用，只是此前对 sql_mode 无落点。

### 4.2 回读（read）
- `MycatDataContextImpl.getVariable(false, target)`（proxy:163-189）：在落到 `variableService.getSessionVariable` 之前，若 target 命中 sql_mode 且 `sessionSqlMode != null`，直接返之；否则维持现有静态模板行为（未 SET 的会话不受影响）。

### 4.3 传播（diff-replay on acquire，方案 A1）
- **每条后端连接记期望态**：`MySQLClientSession`（proxy/.../session/MySQLClientSession.java）新增 `String appliedSqlMode`（该连接当前实际生效的 sql_mode 文本，`null` 表"未知/建连时后端默认"）。
- **diff 点**：在下推业务 SQL 前（复用 `MySQLTaskUtil` 内与 `syncState` 同源的"查询前确保后端态"时机），比较 `前端 sessionSqlMode` 与 `后端 appliedSqlMode`：
  - 相等（含都为默认基线）→ 直接发业务 SQL，零额外往返。
  - 不等 → 先补发 `SET sql_mode='<前端串>'`，成功后置 `appliedSqlMode` 并记 `onSyncSQL` 监控，再发业务 SQL。
  - 连接首建（`appliedSqlMode==null`）且会话有显式值 → 必发。
- **原样引用**：回放的串须按 MySQL 字符串字面量转义单引号（`''` 双写），避免注入与语义漂移。
- **多语句路径**：`allowMultiQueries` 下补发的 `SET` 不得混入客户端多语句包，作为独立前置 COM_QUERY 先行完成。
- **归还连接（A2 已裁定不做）**：`addIdleSession` 不额外重置——因为 acquire 前 diff 已保证任何业务 SQL 执行时后端态==本会话期望态，归还重置是冗余 SET。

### 4.4 数据流
```
客户端 SET sql_mode=''  →  SetSQLHandler → MySQLVariablesUtil(新增分支) → MycatDataContext.sessionSqlMode=''
客户端 SELECT @@session.sql_mode  →  getVariable(false) 命中 sessionSqlMode → 返 ''（不再返严格模板）
客户端 group-by 查询  →  下推前 diff(sessionSqlMode vs backend.appliedSqlMode)
                          不等 → SET sql_mode='' → 业务SQL ；相等 → 直接业务SQL
                          → 后端(默认严格)因连接已置宽松 → 放行，不再 1055
```

---

## 5. B 层设计：跨片校验门控 + SPM 缓存策略

### 5.1 校验门控（第一刀，已在工作树、待随本设计一并 commit）
- `SqlValidator.Config.onlyFullGroupBy()`（calcite-core，`@Value.Default true`）+ `withOnlyFullGroupBy`。
- `AggChecker`（calcite-core:141+）：`if (!distinct && !validator.config().onlyFullGroupBy()) return null;` 放宽 GROUP BY 的 notGroupExpr；SELECT DISTINCT 分支仍抛（标准 SQL，不受 sql_mode 影响）。
- `MycatCalciteSupport.getValidatorConfig()`（hbt:192+）：`.withOnlyFullGroupBy(SessionSqlMode.isOnlyFullGroupBy())`。
- `SessionSqlMode`（hbt，ThreadLocal，缺省严格、查询末 reset 防线程复用串味）。
- **B 层增量**：代理查询入口在编译/校验前，按 §4 会话 `sessionSqlMode` 派生的 `containsOnlyFullGroupBy` 调 `SessionSqlMode.setOnlyFullGroupBy(...)`；查询后 `reset()`。即 A 层的捕获顺带驱动 B 门控，二者同一份会话态，无二源。

### 5.2 SPM 缓存策略：定案 S2（缓存会话无关）
- 现状：`MemPlanCache.map : ConcurrentHashMap<Constraint, Baseline>`（hbt/.../spm/MemPlanCache.java:32），`Constraint={sql,paramTypes}`（Constraint.java:15-17，`@EqualsAndHashCode`），且 `JsonUtil.toJson(constraint)` 落盘 `mycat.spm_baseline.constraint` 列（DbPlanManagerPersistorImpl.java:157-159）。全局、会话无关。
- **核心判据**：`ONLY_FULL_GROUP_BY` 是"接受/拒绝"门，**不改变计划拓扑**（跨片切分/归并出的 MycatRel 与该模式无关，只决定这条查询准不准进）。因此：
  - **S1（编进缓存键）= 过度工程**：为同一拓扑凭空复制两份 baseline，且改内存键 + 磁盘 JSON 格式，需处理旧 baseline 反序列化的新字段默认值 = 地基爆炸半径。
  - **S2（定案）**：缓存保持会话无关；会话相关的"是否放行"放在**查询入口的校验门**（§5.1，命中缓存前即按会话 sql_mode 校验，被拒的查询根本不进缓存使用路径）。
- **命中后一致性注意点（计划阶段须验证）**：`getConstantMap(baseline)`（MemPlanCache.java:149-159）在缓存命中时会 re-parse/re-validate，且 `catch(Throwable)` 吞异常成空 map。S2 下入口门控已先行拒绝"本会话模式不允许"的查询，故不会带着错误拓扑走到这里；但须有测试钉住"宽松会话接受的计划、在严格会话复用同一 SQL 时被正确拒绝"与反向，确保不因全局缓存而串味。

---

## 6. 错误处理与边界
- `SET GLOBAL sql_mode`：维持现状（非会话捕获），不回放下发。
- sql_mode 串规范化：仅去 `@@[session.]` 前缀与外层引号，**不改动模式 token 内容**，保证透传保真。
- `containsOnlyFullGroupBy` 判定：token 级精确匹配（逗号/空白分割 + 大写 trim），避免子串误判。
- 后端 `SET sql_mode` 补发失败（如后端不认某 token）：按现有 syncState 失败路径处理（记录、不静默），避免向下游泄漏半同步态。
- 线程/虚拟线程复用：`SessionSqlMode` 每查询末 reset；后端 `appliedSqlMode` 随连接生命周期，不跨连接假定。

---

## 7. 测试策略（TDD，红-绿-重构）
- **离线单测（免活库，进 CI/回归）**：
  - `MySQLVariablesUtil` 捕获 sql_mode → 会话态==原始串；回读命中。
  - 传播 diff 单测：给定 sessionSqlMode 与 appliedSqlMode 的相等/不等/首建组合，断言是否补发 `SET sql_mode='...'` 及转义正确（可 mock ResultSetHandler 捕获下发 SQL 序列）。
  - `containsOnlyFullGroupBy` tokenize：`ONLY_FULL_GROUP_BY`/含之/不含/大小写/`STRICT_TRANS_TABLES`（不得误判含）。
  - 第一刀已有 `OnlyFullGroupBySqlModeTest`（hbt）覆盖校验门放宽/缺省严格。
  - **S2 串味钉测**：同 SQL 在宽松会话被接受、在严格会话被拒绝（反之亦然），证明缓存未把跨会话接受性泄漏。
- **活库 e2e（Docker 后端，判据级）**：mysqldump 对一个含非严格 GROUP BY 依赖的库，**stderr 不再出现 1055**、回灌成功；会话内 `SET sql_mode=''` 后 `SELECT @@session.sql_mode` 返 `''`；连接复用下前后两个不同 sql_mode 会话在同池交错查询互不串味。
- **全量回归**：跨模块 build + 既有 golden/矩阵测试零新红。

---

## 8. 风险与回滚
- **主风险**：传播 diff 点插在下推热路径，若时机选取不当会漏发或多发 SET。缓解：与既有 `syncState`（isolation/autocommit）同源同时机，行为可对齐、可测。
- **兼容性**：缺省严格 + 仅显式会话值才放宽，未 SET sql_mode 的存量会话与所有离线基线零变化。
- **回滚**：A 层三处（捕获/回读/传播）均为增量分支，关掉传播 diff 即退回"仅透传后端"旧行为；B 层第一刀缺省即严格。

---

## 9. 分期与验收
- **阶段 A（优先，消 mysqldump 症状根）**：捕获 + 回读 + 传播(A1)。验收=活库 mysqldump stderr 消失 + 回读返真值 + 复用不串味 + 全量零新红。
- **阶段 B（跨片窄面）**：会话捕获驱动第一刀门控 + S2 缓存策略钉测。验收=跨片非严格 GROUP BY 查询在宽松会话放行、严格会话拒绝，缓存不投毒。
- 每阶段完成回 DEBT-QUEUE 仪表盘更新，合入前 CodeReview 门禁（本 harness 内联实现 + 单一 CodeReview 子代理）。
