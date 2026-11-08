# I-DEBT-88 · `showDataSources` hint 明文回显后端数据源密码（叠加无授权模型与无认证监控面）

- **状态**：✅ 已收口（2026-11-08 立票即收口 · 修法A · TDD 4/0/0 · 活体 PASS；B/C 另票，消费点清单已入 DEBT-QUEUE 行）
- **证据源**：`audit/2026-11-08-capability-audit/`（P-D-03 日志 + P-D-04）
- **严重度**：高（安全）：任意已认证前端用户一跳拿到**全部后端数据源凭据**

## 0. 摘要

任意前端会话执行 `/*+ mycat:showDataSources{} */`，结果集第 3 列 `PASSWORD` **明文返回后端数据源密码**（实测输出：`prototypeDs  audit  Audit#2026  1000 …`）。由于前端无库表/操作授权模型（同轮 P-D-04 坐实），这条等价于"任何能连上 8066 的账号=拿到 mycat 所连后端的全部凭据"——横向移动直通车。9066 监控 HTTP 与 Prometheus 9090 亦无认证（P-D-02/03），三者构成同族暴露面。

## 1. 取证（file:line）

| # | 事实 | 证据 |
|---|---|---|
| 1 | `showDataSources` 分支显式构建 `PASSWORD` 列 | `server/src/main/java/io/mycat/sqlhandler/dql/HintHandler.java:297-304`（:303-304 addColumnInfo("PASSWORD", VARCHAR)） |
| 2 | 填值即明文：`String PASSWORD = config.getPassword()` 后加入结果行 | `HintHandler.java:330-339` 区域（:332） |
| 3 | 活体实证：hint 返回行含 `prototypeDs / audit / Audit#2026 / …` | `audit/2026-11-08-capability-audit/logs/P-D-01.log`（showDatasources 输出段） |
| 4 | 前置条件（放大器①）：前端无授权模型——新建用户 `probeguy` 直接可调 | `logs/P-D-04.log`（连接即全域可见）；`config/src/main/java/io/mycat/config/UserConfig.java:34-63`（SchemaPrivilege 仅留壳） |
| 5 | 放大器②：9066 监控 HTTP 无认证 HTTP 200；9090 Prometheus 无认证 | `logs/` P-D-02/P-D-03 取证（curl 直达） |
| 6 | hint 语法面：`/*+ mycat:showDataSources{} */`（HintHandler cmd 分派，大小写不敏感） | `HintHandler.java:297`（equalsIgnoreCase） |

## 2. 威胁链（一句话）

能连 8066（任何账号）→ 一条 hint → 拿到 mycat↔后端的全部 JDBC 凭据 → 直连后端横向移动；若后端再无网络隔离，等于代理层被整体旁路。

## 3. 修法候选（留专轮拍板，本票不改码）

- **A（最小闭环·推荐首刀）**：`showDataSources` 的 PASSWORD 列脱敏（固定 `******` 或可逆指纹前 4 位），USERNAME/URL 保留。单点改动、离线锁易写。
- **B（面收敛）**：管控类 hint（showDataSources/showUsers/createDataSource 族）收敛到专用管理用户/管理端口——依赖授权模型，与"库表授权空缺"（P-D-04 坐实）联动，属更大工程，建议独立立票。
- **C（同族面）**：9066 监控 HTTP + Prometheus 9090 加 token/绑定地址——独立小票，别与本票捆死。
- 注意：URL 可能含敏感参数（密码内嵌形态），脱敏策略需一并定义；运维脚本若依赖明文需在文档声明脱敏行为。

## 4. 非目标

- 本票不建授权模型（B 另票）、不动 9066/9090（C 另票）。
- 不改变 `createDataSource` 等 hint 的功能语义（其解析层问题另见审计 F9，独立观察项）。

## 5. 风险登记册

| 风险 | 缓解 |
|---|---|
| 脱敏破坏依赖明文密码的内部工具 | 文档声明 + 保留可配置开关（默认脱敏）；离线锁双向断言 |
| 只改一处还有兄弟出口（showUsers 等） | 实现轮先 grep 全部 `getPassword()` 消费点列出清单，逐点裁定，防"补一个漏三个" |
| 测试网无此断言 → 回归无感 | RED-first 离线锁 + example 护栏补一条负控格 |

## 6. 验收判据（Evidenced-Done）

1. `showDataSources` 输出不再含任何明文密码（格式其余列不变）；离线锁 RED-first。
2. `getPassword()` 全消费点清单入簿，每点标注"明文/脱敏/未消费"三态。
3. example 全量护栏无回归；负控格：脱敏后格式断言（列名/列数不变）。

## 7. 任务分解（原子）

- [ ] T1 消费点清单取证（grep getPassword 全仓）
- [ ] T2 拍板脱敏策略（固定掩码 vs 指纹）
- [ ] T3 生产码修改 + RED-first 离线锁
- [ ] T4 example 负控格 + 全量护栏回归
- [ ] T5 看板回写（含放大器①②的另票指针）
