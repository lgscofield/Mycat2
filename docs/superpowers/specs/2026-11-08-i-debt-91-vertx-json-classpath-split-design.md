# I-DEBT-91 · vertx 4.5 升级残留：`io.vertx.core.json.Json` 编译期可见/运行期缺失 → 配置写路径全崩 NoClassDefFoundError（审计 F9 重定性）

- **状态**：🔴 spec-ready（2026-11-08 能力审计立票 · 活体实证 · 未改生产码）
- **严重度**：高——**配置热写路径（createDataSource/dropDataSource/resetConfig hint、MySQLMetadataStorageManager、9066 监控面部分路径）运行时全崩**；且审计 F9 原定性"解析层不可达"被本票推翻

## 0. 摘要

`io.vertx.core.json.Json` 在 vertx-core 4.5 中已被移除，但 3 个生产类仍 import：`server/.../config/MySQLMetadataStorageManager.java`、`server/.../config/MycatRouterConfigOps.java`、`server/.../monitor/MycatSQLLogMonitorImpl.java`。**编译能过 = 编译期 classpath 里有旧版 vertx 的传递依赖；fat jar 运行期只有 vertx-core 4.5.14（无该类）** → 运行时 `NoClassDefFoundError: io/vertx/core/json/Json`。与当年 Druid 影子类问题（I-DEBT-29 族教训）同族：编译期/运行期 classpath 分裂，这次发生在 vertx 上。

## 1. 取证

| # | 事实 | 证据 |
|---|---|---|
| 1 | createDataSource hint 以测试原生形态（纯 hint+分号、`Statement.execute()`）**可解析并到达 handler**——审计 F9"解析层不可达"（CLI + 尾随 SELECT 形态误判）被推翻 | `audit/.../probes/V-F9-recheck.java` 运行记录：无 Druid 语法错，改为 NCD |
| 2 | handler 执行崩：`java.lang.RuntimeException: java.lang.NoClassDefFoundError: io/vertx/core/json/Json` | 同上，2026-11-08 活体 |
| 3 | fat jar 无 `io/vertx/core/json/Json.class`（vertx-core=4.5.14） | `unzip -l` 取证：jackson 子包在、`Json.class` 不在 |
| 4 | 3 个生产类 import `io.vertx.core.json.Json` | `server/.../config/MySQLMetadataStorageManager.java`、`config/MycatRouterConfigOps.java`、`monitor/MycatSQLLogMonitorImpl.java` |
| 5 | 影响面：**hint 配置写路径**（createDataSource/dropDataSource→`ConfigUpdater.getOps().putDatasource/commit`）、**MySQLMetadataStorageManager**（配置存取）、**MycatSQLLogMonitorImpl**（9066 面部分路径） | import 清单 + hint 栈 |

## 2. 波及推论（诚实标注，待实现轮逐条实证）

- **I-DEBT-79 老结论"hint createDataSource 只落内存不落磁盘"** 的观测可能是本 NCD 的另一面（写路径中途崩 → 内存态已 put、磁盘 commit 未达）——重定性候选。
- **example 全量 555 例当前不可能绿**：`AssembleTest.initCluster` 链（createDataSource×4+createCluster）即走此路径（部分测试 `catch (Throwable ignored)` 吞掉后静默降级，部分会真红）。CI 未暴露 = CI 只跑 `-Dtest` 收窄子集（compat 四件套+M3/M4），全量套件不在门禁内——**验证网盲区②**。

## 3. 修法候选（留专轮拍板）

- **A（推荐）**：3 处 `io.vertx.core.json.Json` 改用 vertx 4.5 现行 API（`io.vertx.core.json.jackson.DatabindCodec`）或 mycat 自有 `io.mycat.util.JsonUtil`（jar 内已有）。
- **B（根治编译/运行分裂）**：依赖治理轮——排空传递依赖里的旧 vertx-core（enforcer 禁多版本共存），对齐 I-DEBT 影子清除方法论。
- A 止血 + B 收网，两刀分开走。

## 4. 验收判据

1. `V-F9-recheck` 探针：createDataSource 执行成功 + showDataSources 可见 + 磁盘 datasources/ 落盘 + 重启仍在。
2. `initCluster` 链活体绿；example 全量（或 AssembleTest 全类）无静默吞错回归。
3. 离线锁：NCD 不可离线直接造（运行期 classpath 问题），以探针 + `unzip -l` 断言（fat jar 不得含多版本 vertx-core）入 guard。

## 5. 任务分解

- [ ] T1 I-DEBT-79 老结论重取证（写路径崩点 file:line 逐段钉死）
- [ ] T2 拍板 A/B
- [ ] T3 3 处 import 修复 + RED（探针）→ GREEN
- [ ] T4 example 全量套件首跑（本机 3306+3307 双后端已备）
- [ ] T5 验证网补盲：全量套件进 CI 门禁的可行性评估
