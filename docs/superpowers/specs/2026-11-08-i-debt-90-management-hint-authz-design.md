# I-DEBT-90 · 管控 hint 面授权收敛：管控类 hint 收敛专用管理用户（I-DEBT-88 衍生票 B + F10 补面）

- **状态**：🟡 spec-ready（2026-11-08 能力审计立票 · 未改生产码）
- **上游票**：I-DEBT-88 §3-B；放大器①=前端无授权模型（审计 P-D-04 坐实任意用户全域可见）
- **严重度**：中高（安全/治理）

## 0. 摘要

前端无库表/操作授权模型（`UserConfig.SchemaPrivilege` 仅留壳），任何能连 8066 的账号可执行管控类 hint（showDataSources/createDataSource/resetConfig/migrate 族）。88 票已修凭据回显，但"普通业务账号能改集群配置/发起迁移"仍成立。本票把管控 hint 面收敛到专用管理用户，是通往"多租户可用"的前置。

## 1. 取证

| # | 事实 | 证据 |
|---|---|---|
| 1 | 库表级权限代码注释留壳 | `config/src/main/java/io/mycat/config/UserConfig.java:34-63` |
| 2 | 管控 hint 分派集中于 `HintHandler`（createDataSource/dropDataSource/createUser/resetConfig/migrate 族等） | `server/.../sqlhandler/dql/HintHandler.java`（cmd 分派区） |
| 3 | 任意新用户即可调（probeguy 无任何配置即连上并全域可见） | `audit/.../logs/P-D-04.log` |
| 4 | createDataSource hint 解析层不可达（F9）——收敛面先修可达性才有意义 | audit F9（P-D-01） |

## 2. 修法候选（留专轮拍板）

- **A（用户级管控开关）**：`users/*.user.json` 增 `"admin": true/false`（缺省 false）；false 时管控类 hint 返 1227（ER_SPECIFIC_ACCESS_DENIED_ERROR，对齐真库语义）。
- **B（hint 白名单）**：用户级 `allowedHints` 列表，细粒度但配置面重。
- **C（借力既有 disallowSqlTypes）**：把 hint 视作一种 sqlType 进黑名单机制（D-08 已证该机制生效）——最小实现面，但语义混杂。
- 倾向 A（与真库 SUPER 权限心智对齐）+ 对 F9（createDataSource 解析不可达）先修后管，否则"收口"无从验证。

## 3. 非目标

- 不实现完整库表授权模型（更大工程，独立立项）；不动 9066/Prometheus（89 票）。

## 4. 验收判据

1. 非 admin 用户执行管控 hint → 1227；admin 用户 → 正常（离线锁 + 活体矩阵）。
2. 业务 hint（如 `/*+ mycat:datanode... */` 路由类 hint）不受影响——需先产出 hint 分类清单（管控/路由/诊断三态），防误伤。
3. 全量护栏无回归。

## 5. 任务分解

- [ ] T1 hint 全清单三态分类（管控/路由/诊断）取证入簿
- [ ] T2 拍板 A/B/C
- [ ] T3 生产码 + RED-first 锁（含 F9 修复或剥离）
- [ ] T4 活体矩阵（admin/非 admin × 管控/路由 hint）+ 护栏
