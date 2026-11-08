# I-DEBT-89 · 监控面无认证收敛：9066 监控 HTTP 与 Prometheus 端点加认证/token（I-DEBT-88 衍生票 C）

- **状态**：🟡 spec-ready（2026-11-08 能力审计立票 · 活体实证 · 未改生产码）
- **上游票**：I-DEBT-88（`showDataSources` 凭据回显已修）§3-C 明确切出的同族暴露面
- **严重度**：高（安全）

## 0. 摘要

9066 监控 HTTP（`/ShowInstanceMonitor`、`/ShowDbMonitor`、`/QuerySqlLog` 等）与 Prometheus `prometheusPort` 指标端点均**无认证无 TLS**，任意可达网络位置可拉取实例/数据库/SQL 日志面。与已修的 I-DEBT-88（hint 面凭据回显）、放大器①（前端无授权模型，I-AU 族）构成同族暴露面，本票只收监控面。

## 1. 取证

| # | 事实 | 证据 |
|---|---|---|
| 1 | 9066 四端点无认证 HTTP 200 返回真实数据（cpu/mem/lqps/databaseInstanceMap/SQL 日志） | `audit/2026-11-08-capability-audit/logs/`（P-D-02 取证段，2026-11-08 实测 curl 直达） |
| 2 | Prometheus `/metrics` 无认证 159 行指标 | 同上 P-D-03 |
| 3 | 端点装配：9066 Vert.x HTTP（`MycatSQLLogMonitorImpl`）、Prometheus simpleclient（`server/.../exporter/PrometheusExporter.java`，`prometheusPort` 属性开关） | 上轮架构审计 file:line |

## 2. 修法候选（留专轮拍板）

- **A（token 头）**：两端口要求 `Authorization: Bearer <token>`，token 由 server.json properties 配置（`monitorToken`），缺省未配置=仅绑 127.0.0.1 并 WARN。
- **B（绑定面收敛）**：缺省绑定 127.0.0.1（现 0.0.0.0/任意），显式配置才对外。
- **C（Prometheus scrape auth）**：simpleclient HTTPServer 支持基本认证/自填 servlet 门。
- A+B 组合为最小闭环；C 视运维诉求。

## 3. 非目标

- 不动 hint 面（88 已修）；不建通用授权模型（I-AU 族另票）。

## 4. 验收判据

1. 无 token 访问 9066/Prometheus → 401；带 token → 200（离线锁 + 活体）。
2. 未配置 token 时缺省仅回环绑定 + WARN（防"升级后监控突然全断"类事故）。
3. example/guard 全量无回归；负控格（无 token 必 401）。

## 5. 任务分解

- [ ] T1 端点清单取证（9066 全端点 + prometheus collector 面）
- [ ] T2 拍板 A/B/C 组合与缺省值
- [ ] T3 生产码 + RED-first 锁
- [ ] T4 活体验收 + 护栏负控
