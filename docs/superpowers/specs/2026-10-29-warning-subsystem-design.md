# I-DEBT-39·残 告警子系统（Warning Subsystem）专轮设计 Spec

- 日期：2026-10-29
- 债务：从 I-DEBT-39·残（除零对外语义）拆出的**独立子系统切片**——MySQL 告警（`SHOW WARNINGS` / `SHOW COUNT(*) WARNINGS` / `warning_count`）真读与按会话累积
- 对齐北极星：客户端眼中无缝替代 MySQL 各版本。`SHOW WARNINGS` / `warning_count` 是 DBeaver、mysql CLI、各语言驱动肉眼依赖的会话态协议面，当前 Mycat 完全缺失明细行 → 直接破面。
- 状态：**架构级专轮 · Spec 先行**（本文件为设计决策；实现按 tranche 逐刀 TDD，本里程碑不一次吞全部）。

---

## 1. 现状取证（当前代码 file:line，非记录推断）

| 事实 | 证据 |
|---|---|
| SHOW WARNINGS **明细行恒空**（服务端活路径） | `server/src/main/java/io/mycat/sqlhandler/dql/ShowWarningsSQLHandler.java:38` `mySQLResultSet.setRows(Collections.emptyList())` |
| SHOW WARNINGS **明细行恒空**（原型服务路径） | `hbt/src/main/java/io/mycat/prototypeserver/mysql/PrototypeHandlerImpl.java:358` `showWarnings(...) → return Collections.emptyList()`；`PrototypeService.java:191` 直接用其结果 |
| `warning_count`（OK/EOF 里的**数值计数**）管线**已存在** | `common/.../MycatDataContext.java:86 getWarningCount()`；`proxy/.../runtime/MycatDataContextImpl.java:62 private int warningCount`、`:228 setWarningCount`、`:290 get`；EOF/OK 出包用 `session.getWarningCount()`（`MycatVertxMySQLHandler.java:497/508/523`、`DefaultCommandHandler.java:131`、`VertxMycatServer.java:318`） |
| 有意**不清零** warning_count（客户端据此取详情） | `proxy/.../packet/MySQLPacketResolver.java:275` 注释掉 `setWarningCount(0)` |
| 告警列 schema 现成 | `hbt/.../PrototypeService.java:919 getShowWarningsColumns()`（Level/Code/Message） |
| deparse 能力现成 | `hbt/.../sql/visitor/MycatOutputVisitor.java:1686 visit(MySqlShowWarningsStatement)` 可回写 `SHOW WARNINGS` |
| 透传机制现成（Design A 先例=I-DEBT-58） | `hbt/.../PrototypeHandlerImpl.java:362 onJdbc(String)` |
| 会话可变态之家 | `proxy/.../runtime/MycatDataContextImpl.java`（enum 变量表 + 字段），`MycatDataContextEnum.WARNING_COUNT`、`LAST_MESSAGE` 已占位 |

**结论**：缺的不是计数，是**"按语句累积、可按需读取的告警消息明细缓冲"这一子系统**，以及把它接进 SHOW WARNINGS/COUNT(*) 与（后续）生产者。计数与告警明细必须**同源**（否则 `warning_count=2` 却 `SHOW WARNINGS` 空 = 自相矛盾、客户端行为分裂）。

---

## 2. 为何必须自建缓冲、不能纯透传后端（承重裁定）

Mycat 是**多后端连接池薄代理**，MySQL 告警是**单 mysqld 会话（per-connection）**态。客户端一条语句与紧随的 `SHOW WARNINGS` 之间，代理不保证复用同一后端连接：
- 纯透传 `SHOW WARNINGS` → 大概率落到**另一条后端连接**，返回 0 行 → **假绿/不稳定**。
- 分片/跨片语句的后端告警散落在多条连接，无单一后端可答。

∴ 正解 = **代理侧按会话（≈客户端连接）维护告警缓冲**，作为客户端可见的唯一权威告警态；生产者（后端捕获 / 本地求值）向该缓冲写入。这与 I-DEBT-58「SHOW CREATE DATABASE 读回后端真定义」不同——那里是元数据幂等读，这里是**有状态会话累积**，必须代理侧建模。

---

## 3. 目标与非目标

**目标（本专轮累计）**
- G1 地基：按会话的告警消息缓冲 `{Level, Code, Message}`，语句级生命周期，`warning_count` 与缓冲**同源**。
- G2 读面：`SHOW WARNINGS [LIMIT [o,] n]` 与 `SHOW COUNT(*) WARNINGS` 从缓冲真读，服务端活路径 + 原型路径两处一致。
- G3 系统变量：`@@warning_count` / `@@max_error_count`（截断上界）对齐。
- G4 生产者-后端捕获：单/跨片语句执行后，把后端该语句产生的告警收进缓冲。
- G5 生产者-本地：本地求值告警（除零 `Warning 1365` 等，接回 I-DEBT-39·残其余）。

**非目标（划界，防蔓延）**
- 本专轮**不改** `/ DIV % MOD` 全局算子值语义（属 I-DEBT-39·残其余 tranche，与告警地基正交；除零值面须先正确返 NULL，G5 才有意义）。
- 不实现 `SIGNAL/RESIGNAL`（存储诊断区完整 SQL 标准特性），仅覆盖 `SHOW WARNINGS` 族 + `warning_count`。
- 不引入告警持久化/跨会话共享。

---

## 4. Tranche 拆解（每刀独立可自证，逐刀 TDD）

> 排序原则：**先地基+读面（可用测试生产者驱动，不依赖耦合的算术改造）**，后接真实生产者。宁挂账不伪绿：地基阶段诚实标注"尚无生产告警源，SHOW WARNINGS 端到端真实返回留生产者 tranche"。

### Blade A — 地基：会话告警缓冲 + 计数同源（本刀）
- 新增 `WarningMessage { String level /*Note|Warning|Error*/; int code; String message; }`（放 `common`，跨模块可见）。
- `MycatDataContextImpl` 增 `List<WarningMessage> warnings` + `addWarning(...)` / `getWarnings()` / `clearWarnings()`；**`getWarningCount()` 改为返回缓冲语义一致值**（见 D2）。保留原 `warningCount` 字段作后端透传计数兜底（见 D5）。
- 语句级重置钩子：每条新语句开始时 `clearWarnings()`（见 D1）。
- TDD（离线单元锁，调 public API 证真实行为）：累积 N 条 → `getWarningCount()==N`；顺序=插入序（MySQL 告警非确定性序，见 D3）；`max_error_count` 截断（D4）；clear 归零。

### Blade B — 读面：SHOW WARNINGS / COUNT(*) / LIMIT 真读
- 改 `ShowWarningsSQLHandler.onExecute` 与 `PrototypeHandlerImpl.showWarnings`：从 `dataContext` 缓冲出 `List<Object[]>`（Level/Code/Message 三列），honored：
  - `SHOW COUNT(*) WARNINGS` → 单行 `(warning_count)`（若 druid AST 提供 count 标志；否则按文本判定，见 §6 待核）。
  - `SHOW WARNINGS LIMIT o,n` / `LIMIT n` → 切片。
  - `max_error_count` 默认 64 截断已在缓冲入队时生效（D4），读面按缓冲原样。
- TDD：Blade A 缓冲塞入 3 条 → SHOW WARNINGS 返 3 行、COUNT 返 1 行值 3、LIMIT 1,2 返后 2 行。

### Blade C — 系统变量：`@@warning_count` / `@@max_error_count`
- 接入 `MycatDataContextEnum.WARNING_COUNT` / `MysqlVariableService.getSessionVariable`，与缓冲同源。
- TDD：`select @@warning_count` == 缓冲条数；未知告警变量回退全局默认。

### Blade D — 生产者①：后端告警捕获（单片/跨片）
- 语句执行完成回调里，在**同一后端连接**上紧随取 `SHOW WARNINGS` + `SELECT @@warning_count`（同连接保序），灌入会话缓冲；跨片取产生告警的分片并集（去重/排序见 D3/D6）。
- 这是真实端到端价值落地刀（如后端 DML 截断告警）。
- TDD/差分：单数据源跑一条后端会告警的语句（如向 `VARCHAR(2)` 插超长→1265/1292 Note·Warning），直连真库 vs 经 Mycat 对拍 `SHOW WARNINGS` 行集。

### Blade E — 生产者②：本地求值告警（接回 I-DEBT-39·残其余）
- 依赖 `/` 十进制除零语义已返 NULL（I-DEBT-39·残 值语义 tranche，正交前置）后，本地对 `1/0` 追加 `Warning 1365 Division by 0` 入缓冲。
- 明确**不**在本专轮先做（值语义未通前追加告警而值仍错 = 半修）。

---

## 5. 语义决策（D1–D6，齐备可落地）

- **D1 生命周期（2026-10-29 活体探针修正·原设想"读后清"被证伪）**：真库语义 = **诊断区只持"最近一条语句"的条件·每条新语句替换 DA**（证据：`SELECT 1/0; SELECT 5; SHOW WARNINGS` → **空行集**）；**`SHOW WARNINGS` 读后不清空**（证据：`SELECT 1/0; SHOW WARNINGS; SELECT @@warning_count` → **1**）。落地 = **每条语句入口 `clearWarnings()`**（等价"替换"·挂在 `MycatdbCommand.execute` L466 `putProcessStateMap(emptyMap)` 同一重置点旁），语句执行期累积本语句条件，**读面只读不清**。
- **D2 计数同源（修正）**：`warning_count` = **本语句条件总数**，缓冲入队即计数、`getWarningCount()` 返回缓冲 size；**不乘加、不分两套**。真库证据：`SET max_error_count=1; SELECT 1/0,2/0,3/0` → `@@warning_count`=**3** ⇒ **计数不被 `max_error_count` 截断**。
- **D3 顺序**：MySQL `SHOW WARNINGS` 顺序不保证严格插入序，但**客户端实测多为产生序**（活体单条件场景即产生序）。本专轮采**产生插入序**（稳定、可测），跨片并集按 (Level 优先级 Error>Warning>Note, Code, 首次出现序) 稳定排序（D6）。诚实标注：与真库在极端并发下顺序或有出入，值集合一致优先。
- **D4 `max_error_count`（活体修正·严禁硬编码）**：**默认值版本相关** — 活体实测 **MySQL 8.0 = 1024 / MySQL 5.7 = 64 / MariaDB 10.11 = 64**。语义 = **截断只作用于列表**（`SHOW WARNINGS` 输出按会话 `max_error_count` 截尾），**不截计数**（见 D2）。故读面须取会话变量（并按 emulate 版本取默认），缓冲侧不截断。
- **D4b 读面截断落点**：因 D4 属"列表侧行为"，截断实现于 Blade B 读面（`ShowWarningsSQLHandler`），而非 Blade A 缓冲入队处 — 保持缓冲存全量、`warning_count` 报全量的真库一致性。
- **D5 后端透传计数兜底**：现有 `private int warningCount`（OK/EOF 出包已用）在后端捕获尚未落地（Blade D 前）时保留原语义；Blade D 后由缓冲计数统一覆盖，避免 `warning_count` 与明细两套来源打架。
- **D6 跨片去重**：同一语句在多分片可能产生重复告警；并集按 `(Code, Message)` 去重保留首次。

---

## 6. 实现前须核的小项（**2026-10-29 落刀时全部取证消解**）
- ~~druid `MySqlShowWarningsStatement` AST 是否暴露 `isCount()`/`getLimit()`~~ → **`javap` 实证存在**：`isCount()/setCount(boolean)/getLimit()/setLimit(SQLLimit)`，且该类 `extends MySqlStatementImpl implements MySqlShowStatement`，**并非 `SQLShowStatement`** ⇒ `MycatdbCommand` L511 的 `PUSH_SHOW && instanceof SQLShowStatement` 分支根本拦不到它；又因 `ServerConfig.java:85 pushShowOnPrototype=false`（默认·e2e/CI 无覆盖）+ `MycatdbCommand:135` 确已 `sqlHandlers.add(new ShowWarningsSQLHandler())` ⇒ **活路 = server handler**，收到的是 `VertxMySQLAuthHandler:100` 创建的客户端会话 context。
- ~~`SHOW WARNINGS` 三列的精确 wire 类型~~ → 沿用既有 `getShowWarningsColumns()`（Level=VAR_STRING(253)/len 21/charset 33、Code=INT32(3)/len 4/flags 0x00a1、Message=VAR_STRING(253)/len 1536），未新造列头。**新增待核项**：`COUNT(*)` 形态单列 `@@session.warning_count` 的精确列元数据尚未用 `--column-type-info` 活体复核（暂沿用 Code 列 INT32 口径），已登记为 Blade D 差分项。
- 后端告警捕获的**同连接取回**API 落点（Blade D 仍未做）。
- **`max_error_count` 取值现状（2026-10-29 二次取证·修正本条原论证）**：`MySQLVariablesEnum:496 max_error_count(Type.BOTH)` 有变量名。取值**分两条装配路径**（`hbt/.../MysqlVariableServiceImpl.java:34-96`）：①**配了后端数据源** → 会话变量表由真库 `SHOW SESSION VARIABLES` 拉取（L44-51），**含 `max_error_count`**，值即后端真实版本口径（8.0=1024 / 5.7=64 / MariaDB=64），读面直接生效，**无需 Blade C**；②**无后端/嵌入兜底** → L53-95 那份静态表，**不含**此键，`getSessionVariable` 返回 `null` ⇒ 读面走 **`NO_TRUNCATION(-1)` 保守退化**，**不臆造**版本相关默认。
  > 本条初稿写作"资源模板/会话变量表未见其默认值来源（全仓 grep 零命中）⇒ 取不到值"，**该论证只成立于路径②**，对路径①不成立 —— 已在落 Blade B 后复核并改正。Blade C 的正题相应**收窄**为：给兜底路径按 emulate 版本补默认值 + 支持 `SET max_error_count` 覆盖（不再是"让变量有值可读"）。
  锁：`ShowWarningsMaxErrorCountTest` 5 例（null/非数字/抛异常 → 不截断；字符串数字/Number → 直取），并做**变异核验**（把退化返回值改成 64 → `Tests run: 5, Failures: 3`，证明锁会咬人）。
- **同族缺陷登记（本专轮未动）**：`ShowErrorsSQLHandler` 同样 `setRows(Collections.emptyList())` 恒空；`SHOW ERRORS` 语义另含"只列 Error 级"与 `@@session.error_count`，需活体探针后再落，不臆造。已在入口判定 `isDiagnosticReadStatement` 中预留（防未来先清后读）。

---

## 6b. 落地进度（2026-10-29 本轮）

| Blade | 状态 | 证据 |
|---|---|---|
| **A 地基** | ✅ 已落（离线自证） | 新增 `common/.../beans/mycat/WarningMessage.java`；`MycatDataContext` 加 `addWarning/getWarnings/clearWarnings`（**接口新增抽象方法的实现者波及面实测为 0**：全仓仅 2 个 `implements MycatDataContext`，均已实现）；`proxy/.../MycatDataContextImpl` 与 `server/.../JdbcMycatDataContext` 各挂缓冲，`getWarningCount()` 改为**缓冲 size 派生**（取证：`MycatDataContextEnum.WARNING_COUNT` 全仓零调用者、`dataContext.setWarningCount` 无生产写入点 ⇒ legacy int 是恒 0 死状态，派生计数安全且严格更优）；语句入口重置钩子挂 `MycatdbCommand.execute` 的 `putProcessStateMap(emptyMap)` 同一收口（`executeQuery:208` 与 `DefaultCommandHandler:213` 均汇入此路）。锁：`SessionWarningBufferTest` 4/0/0 + `MycatdbCommandDiagnosticResetTest` 2/0/0 |
| **B 读面** | ✅ 已落（离线自证） | `ShowWarningsSQLHandler` 从缓冲真读，`buildWarningRows()` 纯函数承载 COUNT(*)/LIMIT/截断语义，`onExecute` 按 `isCount()` 分支列定义，`resolveMaxErrorCount()` 读会话变量并在取不到时保守退化。锁：`ShowWarningsRowBuilderTest` 9/0/0 + `ShowWarningsMaxErrorCountTest` 5/0/0（后者经**变异核验**确证会咬人） |
| C 系统变量 | ⬜ 待做（范围已收窄） | 见上"取值现状"：有后端时变量本就可读，C 只剩兜底默认值 + `SET` 覆盖 |
| D 后端告警捕获 | ⬜ 待做（需 infra） | 本轮 **无任何生产者**，故端到端尚不可见 → 诚实标 🟡 |
| E 本地除零 Warning 1365 | ⬜ 待做 | 依赖 I-DEBT-39·残值语义前置 |

**回归门（2026-10-29·`mvn -o -pl proxy,server -am test`，JAVA_HOME=Liberica JDK-21-Full）**：`BUILD SUCCESS`，14 模块合计 **377 tests / 0 failures / 0 errors / 0 skipped**（config 23、common 43、router 18、datasource 7、va 12、proxy 13、hbt 255、executor 6、server 100）。三枚新锁的 surefire 报告文件已逐个 `Test-Path` + 读正文实名核对（防"BUILD SUCCESS 但静默跑 0 测试"的伪绿）。全仓 `test-compile` 另已独立通过（19 模块）。

> **本轮诚实边界**：Blade A+B 建的是"管道 + 读面"，**生产者一条也没接**（Blade D/E 才有真实告警进缓冲）。所以此刻 `SHOW WARNINGS` 在活体链路上仍返回空行集——缺陷的**根因面**（缓冲/同源计数/读面/生命周期）已闭环并可离线证明，**可见价值**待 D 刀。不得因 20 锁全绿就冒 Evidenced-Done。

---

## 7. 护栏与回归（每刀必跑）
- 离线单元/契约锁（common/hbt/server）。
- 全量：`mvn -o` 相关模块测试；活库差分 e2e（Blade D 起，my57-3306/真库对照）。
- 既有绿锁不翻（尤其列头保真 `TypeRepresentationThreePathConsistencyTest`、`MySQLPacketUtil*`、`ConnectionEofDeprecationTest`）。
- 每刀 commit/push/ls-remote 闭环，DEBT-QUEUE 回写进度；未落地端到端价值的刀**诚实标 🟡 不冒 Evidenced-Done**。

---

## 8. 交付节奏建议
- **今晚（如授权落刀）= Blade A 地基 + Blade B 读面骨架**，以测试生产者驱动可离线 TDD 自证；端到端"真实后端告警可见"落 Blade D（需 infra），届时才升对应 Evidenced-Done。
- Blade A/B/C 与 codegen 算术**正交**，符合"独立专项"要求，可先闭环地基与读面而不夹带除零值语义风险。
