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

## 5. I-DEBT-7C：全量重新基线（sweep 取证，结果已回填）

- **触发**：7A 收口后顺位项。
- **裁定（2026-10-02 用户批准"修+7C"口径，先跑 sweep 后写计划的顺序）**：
  7C = 取证动作本身（全量清单产出前无法写具体修复计划——给未知内容写步骤是假动作），故**不预先建独立 spec/plan**，按本簿格式留执行记录；清单出来后按根因拆出的**每个子债各自走 spec→plan→execute**（复制 I-DEBT-6"诊断先行"先例）。
- **执行口径**：
  1. 先收编绕过 helper 的 hint 直调（见第 6 节）；
  2. 全量 sweep 复用 `target/i7c_sweep.sh`（timeout 1800 harvest 不怕超时），排除清单 `target/i7c-excludes.txt` = `**/io/mycat/xa/**` + `SqlFunctionTest` + `TableAnalyseTest`（Root B 已知挂点，防单点吞整轮）；
  3. 逐类 pass/fail → 按根因归类 → 回填本节 + 更新看板。
- **取证结果（当前 HEAD 实测，凭日志不凭记忆）**：
  - 出报告 58 类 = **21 绿 / 37 红**；服务端 `ClassCastException=0`（I-DEBT-6 战果保持）、`selectList` NPE 计数=27。
  - **根因归类快照**（`target/i7c_roots.txt`，sweep 报告被后续单类验证覆写前所抓，权威）：
    - `POOL_EXHAUST`（Root B/池耗尽 `GetConnectionTimeoutException`）：XaBackendConnectionTest、PushDownFunctionShardingTableTest；另有 CreateGsiTest 445.8s、XaBackend 326.5s、PushDownSharding 303.7s 超长挂点（部分无池标记，疑似同源）。
    - `SELECTLIST_NPE`（Root C/服务端产品红）：AlterTableTest、PstmtAssembleTest、UserTest、ProxyBackendConnectionTest、XaBackendConnectionTest、CreateGsiTest、BkaJoinExecuteTest、PushDownFunctionShardingTableTest（与 Root B 有交叉）。
    - `EXECUTEUPDATE_RESIDUAL`：收编前仅 MycatServerStdStorageManagerTest 1 类（第 6 节已修）。
- **⚠ 关键发现一：Root A 是"方向不完整"的（推翻第 4 节"Root A 收口"的乐观边界）**——
  Connector/J 发送前的纯客户端静态查询类型分类对 mycat hint 串**两个方向都拒**：`executeUpdate`（7A 已修）与 `executeQuery`（helper `MycatTest.executeQuery:99-102` 仍走 `JdbcUtils→PreparedStatement.executeQuery`，**未修**）。第 6 节把某类的 `execute(hint)` 收编后，执行推进到 body 里的 `executeQuery(纯hint)` 立刻露出**镜像守卫** `Statement.executeQuery() cannot issue statements that do not produce result sets`（客户端拒、不走网络，非产品 bug）。
  源码实证 hint→executeQuery 调用点广泛存在（`MycatServerStdStorageManagerTest:38/49/52/60`、`UserTest:40/45`、`SwapBufferTest:19/35`、`MycatMonitorTest:159/166`、`VectorTest:43`、helper `MycatTest:129` 等；带真实 `select`/`explain` 后缀的 hint 不受影响）。
  **含义（诚实口径）**：**当前"37 红"不是可信的产品红清单**——其中有多少其实是这第二层 harness 缺陷伪装，无法从本轮确定（per-class 报告已被单类验证覆写，见"发现二"）。**须先补 `executeQuery` helper 再重跑基线**才有资格谈产品红归类。
- **⚠ 关键发现二：本轮排序失误（如实留痕）**——收编第 10 处后为验证而跑的单类脚本内含 `rm -rf example/target/surefire-reports`，**覆写了全量 sweep 的逐类报告**，导致"发现一"的按类镜像守卫计数未能及时抓取。已抢救的权威归类 = `target/i7c_roots.txt`（擦除前所抓）+ 持久化 `i7c_client.log`/`i7c_mycat.log`。教训：归类未固化前不得复用会清报告的验证脚本；后续基线应把逐类报告一并拷入持久化目录。
- **裁定（顺位）**：
  1. **Root B 经实证为系统性**（非拆债时认定的 SqlFunction/TableAnalyse 两类）→ **干净基线须先修 7B**（连接生命周期/归还或提 `maxActive`），否则挂点类永远跑不完、绿/红信号被污染；
  2. **`executeQuery` 方向 = Root A 未收的第二层**，修它须动**共享测试基元**（`MycatTest.executeQuery`）+ 全量回归 → **越 bounded 阈值**（第 1 节规则：触多调用方/动断言可见形态）→ 升级为独立回合（登记为 I-DEBT-7A-2，复制"诊断先行"先例走 spec→plan）；
  3. 二者皆先于"可信产品红基线"，故 **7C 本轮定位为"取证 + 拆出后续债"，不宣称 Done**；产品红最终归类被 7A-2 + 7B 阻塞。
- **证据（可重跑，持久化 `.superpowers/sdd/2026-10-02-i7c-baseline/`）**：`i7c_passfail.txt`（58 类）、`i7c_roots.txt`（根因归类快照）、`i7c_client.log`/`i7c_mycat.log`（全量日志）、`i7c_v_client.log` + `i7c_v_MycatServerStdStorageManagerTest.txt`（第 10 处收编后 executeUpdate 守卫残留=0 的单类实测）。
- **提交**：见第 6 节收编提交；本节为文档回填提交。
- **遗留（拆出的后续债）**：I-DEBT-7A-2（`executeQuery` 方向 harness，越 bounded → 独立回合）、I-DEBT-7B（池耗尽系统性，先于干净基线）、I-DEBT-7C 产品红归类（被前两者阻塞）。

---

## 6. I-DEBT-7C-1：收编绕过 helper 的 hint 直调（`execute()` 方向，bounded 机械批处理）

- **触发**：7A 终审发现 `MycatTest.execute()` helper 修好了走它的调用，但另有若干类**直调** `JdbcUtils.execute(conn, "/*+ mycat:...")` 绕过 helper，同一 Connector/J `executeUpdate` 静态守卫仍炸 → 属 Root A 同根。
- **定性**：纯测试脚手架，与 7A 完全同根，每处 = 一个会炸的 hint 串改走 `execute()` helper（`Statement.execute()`）。非产品码、不动断言。
- **裁定与理由**：bounded——同形机械批处理，合并为一次收编；守"每处改动对应一个会炸的串"，**非 hint 的 `set transaction_policy`/`initSQL`/FLUSH/kill/DROP/CREATE/use 等直调明确不动**（不炸，改了反增风险）。
- **落地（9 处 / 6 文件，提交 `d27f1bd40`）**：`AssembleTest:32`、`CreateGsiTest:26`、`SavepointTest:33/276/299`、`ProxyBackendConnectionTest:21`、`XaBackendConnectionTest:21`、`MycatMonitorTest:58/165`。编译门 `mvn -B -o test-compile -pl example` BUILD SUCCESS（87 源文件）。
- **第 10 处（本次追加，未提交）**：全量 sweep 实测暴露 `MycatServerStdStorageManagerTest` 仍有 `executeUpdate` 残留（`JdbcUtils.execute(hint)` × 5：line 33/34/51/58/59，含 `RESET_CONFIG`/`syncConfigFromFileToDb`/`syncConfigFromDbToFile`/`loadConfigFromFile`）→ 同类收编为 `execute()`。
  **实测（Docker m1-mysql-a/b，单类实跑，`target/i7c_v_stdio.sh`）**：该类 `Can not issue executeUpdate` 残留=**0**（守卫确除）；但类整体仍红——**过首行后 body 里 `executeQuery(纯hint)` 撞见"发现一"的镜像守卫**（`i7c_v_...Test.txt`），故第 10 处收编正确、但不等于该类转绿。
- **遗留**：
  1. **`UserCaseTest.java` 本轮不改（工具级 bug 绕行裁定）**：编辑该文件时 SearchReplace 保存会把无关行（955/1119/1644 的 `createTableSQL` 里 `\\n`）误序列化成真实换行、破坏字符串字面量——即便只改一行也复现。判定为工具整文件序列化 bug，非本任务范围；连续 `git checkout` 还原两次，本轮不碰该类（其首行 `execute(RESET_CONFIG)` 属 Root-A 红、body 里的 6 处 hint 直调待工具修复或专用编辑器处理）。**教训留痕**：含大段 `\\n` 字面量的测试文件对 SearchReplace 不安全。
  2. 9 处直调残留中的 `executeQuery` 方向部分 → 见第 5 节"发现一"，升级为 I-DEBT-7A-2。
  3. **观察项（7C-1 scoped 终审 Declined-to-judge）**：`SpmHintTest:94-145`、`SqlFunctionTest:571-573` 直调 `JdbcUtils.execute(conn, "BASELINE <cmd> <id>")`——`BASELINE` 既非 `/*+mycat` hint、亦无被识别的 SQL 起始关键字，是同一 Connector/J 发送前静态分类守卫的**潜在近亲**。本轮 sweep 该类未落进 `EXECUTEUPDATE_RESIDUAL` 集（SqlFunctionTest 又因 Root B 被排除），故不改属正确处置；但若将来重基线暴露其红，属 Root A 同族，一并交 I-DEBT-7A-2 定夺，勿临时扩面。
- **终审（2026-10-06，fresh CodeReview scoped `07ab1e939..HEAD`）**：**全绿，无 Critical/Important/Minor**；独立复算持久化证据与文档数字自洽（58=21/37、CCE=0、selectList=27、根因归类逐类吻合、I-DEBT-7A-2 调用点行号全部实证命中、第 10 处正确但未谎称转绿）。结论：可放行，无返工。

---

## 7. 历史 bounded 裁定索引（防遗忘：本簿只收无独立 spec/plan 的 bounded 决策；有 plan 的有界子裁定在此登记指针）

> 用户 2026-10-02 指示"把所有 bounded 都补留痕、防回顾时遗忘"。已建独立 spec/plan/账本的债项，其**决策**不重复搬进本簿（权威在其 plan 账本），此处只登记**去处 + 落盘状态**，避免"以为忘了/找不到"。**诚实注记**：多个账本目录已空（progress.md 不在），git 提交是唯一存续记录，故下表同时标注证据实存位置。

| 债项 | bounded 裁定（一句话） | 权威记录去处 | 磁盘实存状态（本轮核实） |
|---|---|---|---|
| I-DEBT-1 | 不建 `druid-core` fork，删 5 类真影子 + 搬出自研类回归官方 1.2.28 | `.superpowers/sdd/2026-09-30-druid-shadow-removal/progress.md` | ✅ 账本在 |
| I-DEBT-3 | 三项用户裁定：仅做 L1 路径必要类 / 先建校验黄金基线 / 待移植 >10 条即止损 | `2026-10-01-calcite-validator-shadow-removal/progress.md` + spec | ✅ 账本在 |
| I-DEBT-4 | 方向 A（MycatBindable 自有契约，L1 为界）；**测试后端一律 Docker m1-mysql-a/b、禁本机 MySQL** | 账本 `2026-10-01-exec-bindable-contract/progress.md` | ⚠ **目录已空**，证据=提交 392072a0c..cb795bf5b + 终审报告(亦缺) + 看板第 4/26 行 |
| I-DEBT-2 | 根因=pom 两层缺失（shade reduced-pom 剥传递依赖 + 硬码 jupiter 5.7.0 假绿），补 6 test 依赖 + 删硬码 | 账本 `2026-10-01-example-module-deps/progress.md` | ⚠ **目录已空**，证据=提交 98892ca06..168e1999b + `target/ge2_*.sh` + `smoke-cce-rootcause/i6f-*` |
| I-DEBT-6 | 诊断先行（探针钉死 select 返回值强转）；副产品 PrepareExecutor 补 log.error 修"异常被吞" | `2026-10-01-smoke-cce-rootcause/progress.md` | ✅ 账本在 |
| I-DEBT-5 | ColumnMappingTest 补空 RelOptSchema（10 行脚手架）| 本簿第 2 节 | ✅ 在簿（无独立 plan） |
| I-DEBT-7 | 42 红拆 7A/7B/7C 三正交根因；先修后补（结构空白立 9 不插队）| 本簿第 3 节 | ✅ 在簿（裁定） |
| 本机环境口径 | JDK21=`LibericaJDK-21-Full`；后端=Docker 3306/3307 root/123456；本机 MySQL 服务禁动 | 用户 2026-10-01 指令（亦入记忆）| — 约定，非债项 |

**登记的新增后续债（由 7C 取证拆出，见第 5 节）**：
- **I-DEBT-7A-2**（建议新开）：修 `MycatTest.executeQuery` helper 的 Connector/J `executeQuery` 镜像守卫（同 7A 法：`Statement.execute()+getResultSet()`），**触共享基元 → 越 bounded → 独立回合 + 全量回归**。
- **I-DEBT-7B**（已在看板）：池耗尽系统性（`maxActive=8` 连接未归还），**先于**可信基线；含 `UserCaseTest` 工具级绕行待处理。

---

## 变更记录

| 日期 | 动作 |
|---|---|
| 2026-10-02 | 建簿：收录 I-DEBT-5、I-DEBT-7 拆债裁定、I-DEBT-7A 三条已完成记录 + 7C 预授权口径（用户指示"bounded 都补充留痕，防遗忘"） |
| 2026-10-06 | 回填 §5（7C 全量 sweep 取证：58 类=21/37、CCE=0、根因归类快照、**发现一 Root A 方向不完整(executeQuery 未修)**、**发现二 排序失误覆写逐类报告**、顺位裁定）；新增 §6（收编 9+1 处 + UserCaseTest 工具 bug 绕行裁定）；新增 §7（历史 bounded 裁定索引，含 3 个账本目录已空的诚实落盘状态）；登记新增后续债 I-DEBT-7A-2 |

