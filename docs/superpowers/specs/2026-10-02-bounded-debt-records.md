# Bounded 债项留痕记录（合订）

> **用途**：主兼容性流程（`docs/superpowers/specs/2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md`）
> 允许"小而有界的债项"（单方法/单文件级修复、取证拆债裁定）不单独走 spec→plan，
> 但**不写独立 spec/plan ≠ 不留痕**。本文件是这类决策的合订依据簿：
> 每条记录 = 触发 → 定性 → 裁定与理由 → 关键证据 → 提交 → 遗留。
> 后续回顾、审计、复盘一律以此为准；新增 bounded 债项按同样格式追加小节。
>
> **权威性说明**（依用户"不信任既有记录"原则）：本文件是**裁定与理由的留痕**，
> 不是状态的证据源。任何"Evidenced-Done"判定仍须当场重跑证据列出的检查
> （命令与产物路径都写明），文件本身不改变任何状态。
>
> 执行授权来源：用户 2026-10-01 预批 + 2026-10-02 拍板（拆债与起手 7A）。
> 本文件本身由用户 2026-10-02 指示创建（"把所有 bounded 这种都补充一下，后续回顾有依据"）。

---

## 1. 记录规则

- 什么算 bounded（不建独立 spec/plan，进本簿即可）：
  1. 单方法/单文件级修复，改动 ≤ ~20 行，不动断言语义；
  2. 取证后的拆债/顺位裁定（用户拍板或预批口径内自主裁定并入账本 Ruling）；
  3. 诊断先行轮里"结果决定下一步"的取证本身。
- 超出即升级：多文件、触产品代码、动测试断言收放 → 必须独立 spec（或至少在计划里成任务）。
- 每条记录必须能在磁盘上指出可重跑的证据（日志路径/命令/提交号），否则不许立条目。

---

## 2. I-DEBT-5：ColumnMappingTest 23 error（planner 测试基建修复）

- **触发**：I-DEBT-3/4 期间 hbt 基线一直是 `29~34/0/23`，23 error 全在 `ColumnMappingTest`，挂账"另案"后转正为独立债项（用户 2026-10-01 确认后续闭环）。
- **定性（取证 spike 实证）**：纯测试脚手架缺陷，非产品逻辑漂移。`ColumnMappingTest.createRelBuilder()` 传 `relOptSchema=null`；Calcite 1.42 fork 的 `RelBuilder.transientScan(String,RelDataType)` 新增 `requireNonNull(relOptSchema)`（`RelBuilder.java:3165`）→ 23/23 NPE，栈全经 `createTable_user:54`。
- **裁定与理由**：bounded——修复只给脚手架一个空 root schema（`CalciteSchema.createRootSchema` 适配 `RelOptSchema`，同仓 `MycatView:188`、`CalciteValidatorGoldenTest:79` 已是先例），单测试文件、产品代码零动。不建独立 spec/plan。
  **事后注记（2026-10-02 对齐审计）**：spec 第 2.7 节把 T-DEBT 定为"并行、最低优先、不阻塞关键路径"，本项不在关键路径上却占用一个收口轮次——是九轮债项循环里唯一被认定的顺位绕路；代价核实很小（10 行脚手架），在此如实留痕，不追认也不重演。
- **证据（可重跑）**：`mvn -B -o test -pl hbt` → BUILD SUCCESS、`Tests run:36 Failures:0 Errors:0`（ColumnMappingTest 单独 23/0/0）；RED=修复前 23×NPE。
- **提交**：`fbe44e271`。
- **遗留**：无。

---

## 3. I-DEBT-7 拆债裁定：42 红 → 7A/7B/7C 三条正交根因

- **触发**：I-DEBT-6 收口后，example e2e 首轮全量清单 60 类 = 18 绿 / 42 红。42 红不能当一个债修。
- **定性（当前磁盘 surefire 报告独立复核，分类依据 `target/i7_classify.ps1` + `I7Probe`）**：
  - **Root A（39 类）**：第一行 setup `execute(mycatConnection, RESET_CONFIG)` 即崩，跑不到任何断言——e2e harness 缺陷（详见第 4 节）；
  - **Root B（2 类挂点）**：`SqlFunctionTest.testCharFunction/testTimeFunction` 各挂 ~110s+，`GetConnectionTimeoutException: active 8, maxActive 8`——`MycatTest` 的 `DruidDataSource.setMaxActive(8)` 下连接未归还耗尽（真挂起，CI 会撞 job timeout）；
  - **Root C（2 类）**：harness 解阻后首露的服务端缺陷（`MegreJoinExecuteTest:116`/`BkaJoinExecuteTest` 抛服务端 `NullPointerException: selectList`）+ 少量真断言失败。
- **裁定与理由**：三根因正交（一个在客户端发送前、一个在连接池、一个在产品执行器），合并修会互相掩盖信号 → 拆 7A/7B/7C，起手 7A（A 覆盖 39/42，修完才有资格谈 B/C 的真实清单）。**用户 2026-10-02 拍板确认**拆法与起手顺序。
  同日用户另问"example 是否够全、要不要先健全再修"——评估后裁定**先修后补**：39 类跑不到断言的地基上补砖无意义，结构性空白立 I-DEBT-9 挂账不插队。
- **证据**：分类产物 `target/i7a_*`（已持久化 `.superpowers/sdd/2026-10-02-i7a-harness/`）；根因 A 修复前红栈样例见 `2026-10-01-smoke-cce-rootcause/i6f-passfail.txt`（42 红名单本体）。
- **提交**：看板拆行 `a737a18a8` → amend 后并入 `49250bc24`。
- **遗留**：7B、7C 均 TODO；7C 是否再拆，待全量基线清单出来后按子域裁定。

---

## 4. I-DEBT-7A：Root A harness 修复（MycatTest.execute → Statement.execute）

- **触发**：第 3 节拆债后起手 7A。
- **定性（磁盘栈 + connector-j 8.0.33 源码独立复核，非产品 bug）**：
  `MycatTest.execute()→JdbcUtils.execute→PreparedStatement.executeUpdate()`；Connector/J 在**发送前**的纯客户端静态分类守卫（`ClientPreparedStatement:1145 isNonResultSetProducingQuery`）把 `/*+ mycat:... */` 注释/hint 串（无被识别起始关键字）误判为"产出结果集"，直接抛 `Can not issue executeUpdate() ... statements that produce result sets`——根本没走网络，Mycat 服务端 `HintHandler` sendOk 正确。旁证：同一栈对真 MySQL 跑 `drop table ...`（有 DDL 起始关键字）能过，故非 harness 通用误用，是 hint 串 × 强制 executeUpdate 的组合缺陷。
- **裁定与理由**：bounded——单方法 ~10 行，`Statement.execute()` 对 OK/结果集/hint 任意形态不抛（返回 boolean 忽略；方法 void，调用方从不依赖 updateCount，既有用例行为不变）；产品代码零动。不建独立 spec/plan。
  **修复范围边界（整支终审 Important·已独立 Grep 核实）**：本修复只覆盖走 `MycatTest.execute()` helper 的调用；另有 9 处直调 `JdbcUtils.execute(conn, "/*+ mycat:...")` 绕过 helper（`AssembleTest:32`、`CreateGsiTest:26`、`SavepointTest:33/276/299`、`MycatMonitorTest:58/165`、`ProxyBackendConnectionTest:21`、`XaBackendConnectionTest:21`，涉及 ~7 类），同一守卫仍会炸。改这 9 处 = 多文件越界，不扩本 bounded 提交，收编进 7C（7C 看板行已含此注）。
- **证据（Docker m1-mysql-a/b 后端，有界子集 4 类实跑，脚本 `target/i7_subset.sh`）**：
  子集内 `Can not issue executeUpdate` 残留=0；控制组 `ConnectionSmokeTest` 1/0/0 绿；`PushDownFunctionNormalTableTest` 由 Root-A 红转绿 3/0/0；`MegreJoin`/`BkaJoin` setup 不再崩→错误转为服务端 NPE（转 7C 域）。
  日志持久化：`.superpowers/sdd/2026-10-02-i7a-harness/`（i7a-client.log / i7a-mycat.log / i7a-passfail.txt）。
  **口径注意**：终审把初版"全库残留=0"降级为"子集内=0"（子集不含 9 处直调类），看板/提交已同步修正——引用本记录时勿沿用旧口径。
- **提交**：`49250bc24`（含 CodeReview 终审修正的 amend；终审结论：可放行、无 Critical、代码零返工）。
- **遗留**：9 处直调残留 → 7C 收编（见第 5 节）。

---

## 5. I-DEBT-7C：全量重新基线（sweep 预授权口径，结果待回填）

- **触发**：7A 收口后顺位项。
- **裁定（2026-10-02 用户批准"修+7C"口径，先跑 sweep 后写计划的顺序）**：
  7C = 取证动作本身（全量清单产出前无法写具体修复计划——给未知内容写步骤是假动作），故**不预先建独立 spec/plan**，按本簿格式留执行记录；清单出来后按根因拆出的**每个子债各自走 spec→plan→execute**（复制 I-DEBT-6"诊断先行"先例）。
- **执行口径**：
  1. 先收编 9 处直调残留（机械批处理改走 `MycatTest.execute()` helper，编译门 + 子集实测 executeUpdate 彻底归零）；
  2. 全量 sweep 复用 `target/i6_fullrun.sh` 模式（harvest 不怕超时），排除清单在 `**/io/mycat/xa/**` 基础上追加 Root B 挂点类（`SqlFunctionTest` 等），防单点挂起吞掉整轮；
  3. 产出逐类 pass/fail 真清单 → 分类（绿 / harness 残红 / 产品红按子域聚类）→ 回填本节 + 更新看板。
- **证据**：（待回填——sweep 日志、pass/fail 清单、直调收编的子集实测。）
- **提交**：（待回填。）
- **遗留**：（待回填——拆出的子债清单。）

---

## 变更记录

| 日期 | 动作 |
|---|---|
| 2026-10-02 | 建簿：收录 I-DEBT-5、I-DEBT-7 拆债裁定、I-DEBT-7A 三条已完成记录 + 7C 预授权口径（用户指示"bounded 都补充留痕，防遗忘"） |
