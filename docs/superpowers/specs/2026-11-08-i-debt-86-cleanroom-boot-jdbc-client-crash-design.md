# I-DEBT-86 · 净室启动即坏：缺 clusters/ 配置时任何 JDBC 客户端无法连接（session 变量表缺 max_allowed_packet → toInt("null") 崩）

- **状态**：✅ 已收口（2026-11-08 立票即收口 · A+B 两刀 · TDD 4/0/0 · 活体净室验 PASS；拍板=A+B，C 有意不采纳，D 留后续）
- **证据源**：`audit/2026-11-08-capability-audit/`（独立审计轮，全部探针可重跑）
- **严重度**：高（冷启动/新部署即触发；CI 与护栏因配置齐全而成为测试网盲区）

## 0. 摘要

mycat-home 只有 `datasources/` + `users/`（缺 `clusters/`）时，**任何 Connector/J 客户端连 8066 即死**：`java.sql.SQLException: java.lang.NumberFormatException: For input string: "null"`，会话被服务端关闭。mysql CLI 可连（不发 22 变量引导查询），故该缺陷对"先用 CLI 验、再用应用连"的部署路径极具迷惑性。补上 `clusters/prototype.cluster.json` 后立即恢复。

## 1. 取证（file:line + 日志）

| # | 事实 | 证据 |
|---|---|---|
| 1 | MYCAT_HOME 解析与运行态回写 | `server/src/main/java/io/mycat/MycatCore.java:210-229`（findMycatHome，I-DEBT-56 隔离子目录） |
| 2 | 变量表初始化：prototype 拉取路径被 `getConfigAsMap().containsKey(prototype)` 门控 | `hbt/src/main/java/io/mycat/MysqlVariableServiceImpl.java:24-111`（门控在 :36） |
| 3 | `prototype = PrototypeService.PROTOTYPE` = **集群名** "prototype" 而非数据源名 | `hbt/src/main/java/io/mycat/MetadataManager.java:56` |
| 4 | 无 clusters/ 时 ReplicaSelectorManager 分支(:31-34)拿到的仍是集群名 → containsKey=false → **走内置默认表** | 同上；boot 日志 `E0-05-mycat-boot.log` L115-117：`JdbcConnectionManager.resolveDataSource can not resolve datasourceName:prototype → NullPointerException: unknown target:prototype`（启动期被吞） |
| 5 | 内置默认表：global 有 `max_allowed_packet`(:63)，**session 清单没有** | `MysqlVariableServiceImpl.java:76-95` |
| 6 | Connector/J 9.3 引导查询含 `@@session.max_allowed_packet`（22 变量） | boot 日志 `executeQuery` 原文（16:12:32 行） |
| 7 | `fixValue` 对 max_allowed_packet 无条件 `toInt(o)`，o=null | `MysqlVariableServiceImpl.java:133-144`（:140-141） |
| 8 | `toInt(Object)`→`Objects.toString(null)`="null"→`Integer.parseInt("null")` 抛 | `common/src/main/java/io/mycat/MySQLVariablesUtil.java:103-104,127` |
| 9 | 结果集执行失败→会话关闭 | boot 日志 L382-405 `result-set execution failed (streaming)`（`PrepareExecutor:127`）全栈含 `MysqlVariableServiceImpl.fixValue:141` |
| 10 | 复现闭环：无 clusters 三探针全 RED；补 clusters 后全 PASS | `logs/P-L1-01.log`（前后两轮）+ `summary-final.csv` |

## 2. 根因链（一句话）

集群名/数据源名混用使 containsKey 门控失效 → 回落内置默认表 → session 表缺 `max_allowed_packet` → `fixValue` 对 null 无设防 → `toInt("null")` 在 Connector/J 引导查询求值中途抛 → 会话死。

## 3. 修法候选（留专轮拍板，本票不改码）

- **A（止血·单点）**：`fixValue` 对 null 返回该变量缺省值（16777216 或按 persona），打 WARN。
- **B（补面）**：session 默认表补齐 Connector/J 引导查询的 22 变量（值按 persona 分档）。
- **C（设防）**：`MySQLVariablesUtil.toInt` 对 null/"null" 返回缺省并 WARN（防其它变量重蹈）。
- **D（根治）**：containsKey 门控先做"集群名→主数据源名"解析（消 MetadataManager.java:56 混用面）。
- A+B+C 可组合、互不冲突；D 涉装配顺序，建议独立评估。**无论选哪个，必须补"净室启动"护栏**（空 mycat-home + Connector/J 连通探针），否则测试网对此类回归永远绿灯。

## 4. 非目标

- 不动 I-DEBT-56 的 mycat-home 隔离/回写语义。
- 不解决"缺 clusters 属用户配置错误"的产品引导问题（可另立 UX 票）。

## 5. 风险登记册

| 风险 | 缓解 |
|---|---|
| C 层设防可能掩盖真实配置缺失 | WARN 必须带变量名+回落值；护栏里净室档断言"能连"而非"无 WARN" |
| B 的 22 变量值随 persona 漂移（5.7/8.x 值不同） | 复用 `PersonaCollations`/VersionProfile 分档取值，禁止硬编码单值 |
| 净室护栏与现有 guard 抢 8066 | 走 guard 现有 kill-and-restart 闸门，新增 `-Cleanroom` 开关档 |

## 6. 验收判据（Evidenced-Done）

1. 净室启动（mycat-home 仅 datasources+users）→ Connector/J 连 8066：认证 + `SELECT 1` + 22 变量引导查询全绿，会话存活。
2. 离线锁 RED-first：构造 session 表缺 max_allowed_packet 的单测（或 codegen 锁），修复前 RED 修复后 GREEN。
3. `ci/run-example-guard.ps1` 增净室档且本轮通过；全量护栏（example 555 / hbt 276 基线）无回归。
4. mysql CLI 面回归（原本能连的路径不得变红）。

## 7. 任务分解（原子）

- [ ] T1 复现固化：把审计轮净室 boot 场景写成 `MycatCleanroomBootTest`（或 ci 探针）RED-first
- [ ] T2 拍板修法（A/B/C 组合 vs D）
- [ ] T3 生产码修改 + WARN 语义
- [ ] T4 净室护栏进 guard/CI
- [ ] T5 全量护栏回归 + 看板回写
