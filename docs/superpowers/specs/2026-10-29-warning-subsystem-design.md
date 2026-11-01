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
| C 系统变量 | ✅ 已落（2026-10-31·离线 17 锁 + 8066 活体对拍） | 见下“Blade C 落地”。原范围：兜底默认值 + `SET` 覆盖；本次同时把 `@@warning_count` 的“读上一条”语义与 `@@session.*` 前缀归一补齐 |
| D 后端告警捕获 | ✅ 已落（2026-10-31·JDBC 池 DML + Query 路径·活体双向对拍） | 见下“Blade D 落地”“Blade D 余项·Query 路径”。原范围：把后端语句产生的诊断条件物化进前端会话缓冲。本次覆盖 `UPDATE/INSERT`（含 `INSERT IGNORE`）与单目标 `SELECT`（均限 JDBC 数据源池）；NATIVE 协议池 / 分片 DML（`simpleUpdate`）/ 多目标 `SELECT` 仍挂 ⬜ |
| E 本地除零 Warning 1365 | ⬜ 待做 | 依赖 I-DEBT-39·残值语义前置 |

**回归门（2026-10-29·`mvn -o -pl proxy,server -am test`，JAVA_HOME=Liberica JDK-21-Full）**：`BUILD SUCCESS`，14 模块合计 **377 tests / 0 failures / 0 errors / 0 skipped**（config 23、common 43、router 18、datasource 7、va 12、proxy 13、hbt 255、executor 6、server 100）。三枚新锁的 surefire 报告文件已逐个 `Test-Path` + 读正文实名核对（防"BUILD SUCCESS 但静默跑 0 测试"的伪绿）。全仓 `test-compile` 另已独立通过（19 模块）。

> **本轮诚实边界**：Blade A+B 建的是“管道 + 读面”，**生产者一条也没接**（Blade D/E 才有真实告警进缓冲）。所以此刻 `SHOW WARNINGS` 在活体链路上仍返回空行集——缺陷的**根因面**（缓冲/同源计数/读面/生命周期）已闭环并可离线证明，**可见价值**待 D 刀。不得因 20 锁全绿就冒 Evidenced-Done。

### Blade C 落地（2026-10-31）

**真库活体钉死（先取证后写码）**：
- `@@max_error_count` 默认值七档：MySQL 5.7.44=**64**、8.0.46/8.4.11/9.0.1=**1024**、MariaDB 10.6.28/10.11.19/11.4.13=**64**。
- `SET` 边界（8.4.11 与 11.4.13 一致）：`0`→0、`65535`→65535、`65536`→**65535（clamp）**、`-1`→**0（clamp）**、`'abc'`→**ERROR 1232**。
- 诊断区语义：`SELECT 1/0; SELECT @@warning_count` → **1**（`@@warning_count` 读的是**上一条**语句结束时的条数，本语句产生的条件不参与）；`SELECT 1/0; SELECT @@warning_count; SHOW WARNINGS` → 列表**空**；`SHOW WARNINGS` 读后不清。

**改动**：
1. `MycatDataContext` 加三枚 default：`getPreviousWarningCount()`（默认 0）/ `getSessionMaxErrorCount()`（默认 null）/ `setSessionMaxErrorCount(Integer)`（no-op），不破既有 2 个实现。
2. `MycatDataContextImpl.clearWarnings()` 改为**先归档后清空**（`previousWarningCount = warnings.size()`），`getVariable(false, …)` 归一前缀后直返 `warning_count`→归档值、`max_error_count`→会话覆盖值（未覆盖则按去前缀名查变量服务）。`JdbcMycatDataContext` 同构。
3. `MySQLVariablesUtil.setVariable` 加 `max_error_count` 分支：`DEFAULT` 清覆盖、数值 clamp `[0,65535]`、非数字抛 `MycatException(1232, "Incorrect argument type to variable 'max_error_count'")`。`SetSQLHandler` 新增 `catch (MycatException)` 带码出包（此前 1105 + Java 异常文本，客户端按 errorCode 分支会判错）。
4. `MysqlVariableServiceImpl`：兜底表补 `max_error_count`（`defaultMaxErrorCount()` 按 persona：MariaDB→64、MySQL major≥8→1024、否则 64）；`getSessionVariable` 补前缀归一（生产读腿传 `@@session.max_error_count`，NameMap 原先查不中 → null）。

**证据**：
- 离线：新增 `MycatDataContextWarningVariableTest` 8/0/0（proxy）、`MysqlVariableServiceImplMaxErrorCountTest` 9/0/0（hbt）；先跑红（proxy 5 个 CCE + 1 个异常类型不符，hbt 9 个 null）再落码；改完后定向回归（含 `ShowWarnings*` 既有锁）proxy 15/0/0、server 16/0/0。
- 全量：`mvn -o -pl example -am test` → `BUILD SUCCESS`，**1078 tests / 0 failures / 0 errors / 37 skipped**（14:03 min；config 29、common 43、router 18、datasource 7、va 12、proxy 21、hbt 286、executor 6、server 109、example 547/37skip；protocol/replica/plug 无测试源码）。与上批 1061 基线相比恰 +17（本轮新锁 8+9）。
- 活体（8066，`-Dmycat.persona=MARIADB_10_11`，后端 mysql 8.0）：`SELECT @@version, @@max_error_count, @@warning_count` → `10.11.8-MariaDB / 1024 / 0`；`SET max_error_count=1`→1；`65536`→65535；`-1`→0；`SET SESSION …=DEFAULT`→1024；`'abc'`→**ERROR 1232**；`SET max_error_count=1; SHOW WARNINGS` → 空行集。

> **仍诚实挂 🟡 的理由**：Blade D 已覆盖 JDBC 池的 DML 与单目标 Query 路径；NATIVE 协议池、分片 DML（`VertxExecuter.simpleUpdate`）、多目标 SELECT 的告警拼接、Note 级条件与 `SHOW ERRORS` 同族仍未接。

### Blade D 落地（2026-10-31）

**真库对照（真库直连 3306 = MySQL 8.0.46 vs Mycat 8066·persona=MARIADB_10_11·后端同一 3306·JDBC 池）**：

| 序列 | 真库 | Mycat |
|---|---|---|
| `CREATE TABLE t(a VARCHAR(3)); INSERT IGNORE INTO t VALUES('abcdef'),('ghijkl'); SHOW WARNINGS` | 2 行：1265 `Data truncated for column 'a' at row 1` / `... row 2` | **逐字一致** |
| 同上后 `SELECT @@warning_count` | 2 | 2 |
| `SET max_error_count=1;` 同上 | 列表 1 行（只留 row 1）、计数 2 | **逐字一致**（截列表不截计数） |
| `SHOW WARNINGS` 连读两次 | 两次都返回同一行集（读后不清） | 一致 |
| 无告警的 `INSERT` 后 `SHOW WARNINGS` | 空 | 空 |
| `SELECT 1/0 FROM t; SHOW WARNINGS` | 1 行 1365 | **逐字一致**（Blade D 余项落地后，见下节） |

**改动**：
1. `SqlResult` 增 `warnings` 列表 + `getWarningCount()` 派生计数；`of(affectRows, lastInsertId)` 保持兼容。
2. `NewMycatConnectionImpl.toWarningMessages(SQLWarning)`：JDBC 告警链 → `WarningMessage`，保序、身份集合防自环；在 `Statement` 关闭前采集（`insert` 3 分支 + `update` 2 分支）。
3. `ReceiverImpl` 的 `UPDATE/INSERT` 分支直接取 `NewMycatConnection.update/insert` 的 `SqlResult`（不再经 `VertxExecuter.runUpdate` 丢掉告警信息），`collectBackendWarnings()` 按后端结果顺序拼接后写入 `dataContext`，且**早于 `sendOk`**——保证 OK/EOF 的 warnings 字段与本语句缓冲同源。

**证据**：
- 离线：`SqlResultWarningsTest` 3/0/0、`JdbcWarningMappingTest` 3/0/0（common）、`ReceiverBackendWarningTest` 3/0/0（server）；均先跑红（11 处符号缺失、3 处方法未定义）再落码。
- 定向：`mvn -o -pl common,proxy,hbt,server -am test` → `BUILD SUCCESS`，**540 / 0 / 0 / 0**（common 49、server 112）。
- 全量：`mvn -o -pl example -am test` → `BUILD SUCCESS`，**1087 / 0F / 0E / 37 skip**；较上批 1078 恰 +9。
- 活体：见上表，8066 与真库逐字一致。

**仍未覆盖（诚实边界）**：
- **多目标 Query**：`ReceiverImpl` 的 QUERY 分支在多数据源时已对第 2..n 个目标接线，但默认配置无分片表，**未做活体验证**（NO_DATA）。
- **NATIVE 协议池**（`type=NATIVE`）：OK 包 warnings 字段未解析（`PacketUtil.decodeOkPacketPayload` 读完 statusFlags 即返回）。
- **分片 DML**：`VertxExecuter.simpleUpdate` 路径不经 `ReceiverImpl`。
- **Note 级条件**：JDBC `SQLWarning` 面不可见，统一标 `Warning`，不臆造级别。
- `SHOW ERRORS` / `@@session.error_count` 同族未接。

### Blade D 余项 · Query 路径落地（2026-10-31）

**问题**：Blade D 只接了 DML。`SELECT` 走 `runQueryOutputAsMysqlPayloadObject` 流式出包，后端诊断链从未取走——`SELECT 1/0 FROM t` 在 8066 返回 NULL，但 `SHOW WARNINGS` 为空、`@@warning_count`=0（真库 1 行 1365）。

**改动**：
1. `MysqlCollector` 增 `default void onWarnings(List<WarningMessage>)`（默认空实现，不破既有采集器）。
2. `NewMycatConnectionImpl.prepareQuery` 在 `Statement` 关闭前采集（无参 + 预编译两分支），**早于 `onComplete`**——保证前端 EOF/OK 的 warning 计数与本语句缓冲同源。
3. `VertxExecuter`：`runQueryOutputAsMysqlPayloadObject` 增 4 参重载、`runQuery` 增 7 参重载，均带 `Consumer<List<WarningMessage>>` sink；旧签名委托空 sink，调用方零改动。
4. `ReceiverImpl` 的 `QUERY/QUERY_MASTER` 分支把 sink 接到 `dataContext.addWarning`（首目标与后序目标都接）。

**真库对照（真库直连 3306 = MySQL 8.0.46 vs Mycat 8066·persona=MARIADB_10_11·后端同一 3306·JDBC 池·表 q1(a int)）**：

| 序列 | 真库 | Mycat |
|---|---|---|
| `SELECT 1/0 FROM q1 LIMIT 1; SHOW WARNINGS` | 1 行 1365 `Division by 0` | **逐字一致**（列标签 `1 / 0` vs `1/0` 是既有 reprint 面，与告警无关） |
| 同上后 `SELECT @@warning_count` | 1 | 1 |
| `SELECT a/0 FROM q1 LIMIT 1; SHOW WARNINGS` | 1 行 1365 | **逐字一致** |
| `--show-warnings`（读 EOF/OK 的 warning 计数字段） | `Warning (Code 1365): Division by 0` | **逐字一致** ⇒ 终结包计数也被点亮 |
| `SELECT a/0 …; SELECT 1; SHOW WARNINGS` | 空、计数 0（诊断区被新语句替换） | 一致 |
| `INSERT IGNORE` 截断两行（DML 回归） | 2 行 1265、计数 2 | 一致 |

**口径订正（2026-10-31 实测）**：3306 实为 MySQL 8.0.46（直连 `select version()` 取证），MariaDB 10.11.19 在 3308；上节原写「MariaDB 10.11 直连 3306」标签有误。两库对下列两例（1265 截断、1365 除零）输出逐字相同（3308 抽检同），对照结论不变。

**证据**：
- 离线：`QueryBackendWarningsTest`（hbt）3/0/0——JDBC 动态代理造“后端语句带告警链”的最小场景，覆盖无参/预编译两分支与无告警退化，并断言 sink 在 `onComplete` 之前收到；RED = 4 参重载缺失（编译失败）。
- 定向：`mvn -o -pl common,proxy,hbt,server -am test` → **543 / 0 / 0 / 0**（hbt 286→289）。
- 全量：`mvn -o -pl example -am test` → **1090 / 0F / 0E / 37 skip**（较上批 1087 恰 +3）。

**仍未覆盖**：NATIVE 协议池（`OkCommand` 不解析 warnings 字段）、分片 DML（`VertxExecuter.simpleUpdate`）、多目标 SELECT 的 live 验证（已接线，无分片表 = NO_DATA）、Note 级条件、`SHOW ERRORS` / `@@session.error_count`。

### Blade D 余项 · 分片 DML 路径落地（2026-10-31）

**问题**：Blade D 的 DML 只覆盖单目标（经 `ReceiverImpl`）。分片 INSERT/UPDATE 走 `VertxExecuter.simpleUpdate`，其内部 `runInsert/runUpdate` 把 `SqlResult` 直接 `.map` 成 `long[]`——后端诊断条件在链上被丢弃 ⇒ 跨片语句的告警恒空。

**活体取证（改前）**：经 8066（persona=MARIADB_10_11，后端 MySQL 8.0.46@3306）对分片表 `db1.wlk` 执行 `INSERT IGNORE INTO wlk VALUES(1,'abcdef'),(2,'ghijkl')` → `Query OK, 2 rows affected`，但 `SHOW WARNINGS` = **Empty set**、`@@warning_count` = **0**；回读值 `abc`/`ghi`（确已截断），物理分布 `wlk_0=0, wlk_1=1, wlk_2=1, wlk_3=0`（两行落两片）。真值应为 2 条 1265。

**infra 前置（零手工配置）**：分片表直接经 8066 用 DDL 子句建立——Mycat 解析 `DBPARTITION BY mod_hash(id) DBPARTITIONS 2 / TBPARTITION BY mod_hash(id) TBPARTITIONS 2` 自动生成分片配置（落 `server/target/.mycat-home/schemas/db1.schema.json`，`mappingFormat=c${targetIndex}/db1_${dbIndex}/wlk_${index}`）并创建 4 张物理表。**单数据源即可按库分片，无需第二 datasource、无需手改 JSON。**

**改动**（`hbt/src/main/java/io/mycat/vertx/VertxExecuter.java`，仅 `simpleUpdate(…, Iterable<EachSQL>)` 一处）：
1. 先把 `eachSQLs` 物化为 `List`，按 EachSQL 序号开 `AtomicReferenceArray<List<WarningMessage>>` 槽位；
2. 每片执行由 `runUpdate/runInsert`（丢告警）改为直接 `connectionFuture.flatMap(c -> c.insert/update(sql, params))` 取回 `SqlResult`，在 `map` 里 `warningSlots.set(index, result.getWarnings())`；影响行数累加、`onlyFirstSum`、连接归还链与 `CompositeFuture.join` **原样保留**；
3. `join` 之后新增 `flushShardWarnings`，**按序号**把各片告警写入 `context.addWarning`；位置仍在返回 future 完成之前 ⇒ 调用侧 `simpleUpdate(...).eventually(closeStatementState).flatMap(r -> response.sendOk(...))` 的 OK 包 warning 计数取得到。

**两条契约（各有独立断言，且都做过失效检验）**：
- *顺序 = SQL 顺序而非完成顺序*：分片并行下发、完成次序不确定。夹具用 vertx `Promise` 让分片 1 先于分片 0 落地，断言缓冲仍是 row1→row2。**变异检验**：临时把 flush 改成"每片完成即追加"，该锁立刻变红（实测得 `row2, row1`）⇒ 断言非空转。
- *时序 = 早于 `sendOk`*：断点挂在 `simpleUpdate` 返回 future 的 `onSuccess` 上（先挂观测点、再放行后端），若 flush 落在完成之后则取不到。

**证据**：
- 离线：`ShardUpdateWarningsTest`（hbt）5/0/0——RED = 4 例断言"期望告警但实得 `[]`"（第 5 例为无告警负向对照，天然绿）；JDK 动态代理造 `MycatDataContext` / `XaSqlConnection`+`TransactionSession` / `NewMycatConnection`，不连库、不 mock 被测码。覆盖多 target、乱序完成、同 target 多语句、UPDATE 分支、无告警退化。注意 `getTransactionSession()` 返回类型是 `TransactionSession` 而 `simpleUpdate` 向下转型 `XaSqlConnection` ⇒ 夹具代理必须同时实现两接口。
- 活体（改后）：同表同语句 → `SHOW WARNINGS` 2 行 1265、`@@warning_count` = **2**。绕过代理分别在 `db1_0.wlk_1`、`db1_1.wlk_2` 直跑同一条单行 `INSERT IGNORE`，两片真值逐字均为 `Warning | 1265 | Data truncated for column 'a' at row 1` ⇒ 代理侧两条 = 两片各一，计数与明细同源。
- 定向：`mvn -o -pl common,proxy,hbt,server -am test` → **548 / 0F / 0E / 0 skip**（hbt 289→294，恰 +5）。
- 全量：`mvn -o -pl example -am test` → BUILD SUCCESS（14:11 min）：前 9 模块 29+49+18+7+12+21+294+6+112 = 548 / 0F / 0E / 0 skip（hbt 289→294 恰 +5），example 547 / 0F / 0E / 37 skip，合计 1095 / 0F / 0E / 37 skip（上批基线 1090，恰 +5）

**全量首跑的 3 枚红 = 启动工件缺陷（非本刀回归）**：首跑全量为 `547 / 0F / 3E / 37 skip`，红在 `SwapBufferTest#test2`、`VectorTest#testBase`、`VectorTest#testVectorAvgDecimalResiduals`，客户端异常均为 `java.sql.SQLException: Can not read response from server. Expected to read 4 bytes, read 0 bytes before connection was unexpectedly lost.`，且 `VectorTest` 耗时 2003 s。代理端日志给出首因：紧随 `SELECT arrow` 抛 `java.lang.RuntimeException: Failed to initialize MemoryUtil. You must start Java with --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED`（`MemoryUtil.<clinit>:140` → `BaseFixedWidthVector.initValidityBuffer`），即代理端 Arrow 分配崩致连接被关，客户端只看到连接丢失。仓库内早有明文要求该参数：`ci/run-example.sh:31-33` 注释「Arrow 18（「vector()」算子用其堆外内存）在 JDK17+ 必需，否则 VectorTest 挂死」、`ci/verify-mysqldump-binary-roundtrip.sh:102`、`ci/verify-old-connector.sh:68`、`executor/pom.xml:87-94`（surefire fork 常驻该开帧），以及 `.superpowers/start-server.ps1` 的标准 argList；I-DEBT-59 spec L196 亦记过同款「基线代理启动未带 `--add-opens` …（启动工件，非代码）」。**根因：本轮为活体复验手工重启 8066 时漏传该参数。** 坐实实验：带 `--add-opens=java.base/java.nio=ALL-UNNAMED` + `-Duser.timezone=Asia/Shanghai` 重启代理后，同两类定向复跑 → `SwapBufferTest` 1/0/0（3.404 s）+ `VectorTest` 2/0/0（4.221 s）= **3 / 0F / 0E / BUILD SUCCESS**，挂死耗时 2003 s 塌回 4.2 s——挂起与连接丢失正是该崩溃的指纹。仓库根 `start.bat` 内容停留在 JDK8 时代、无这些参数，不可作为启动参考。

**仍未覆盖 / 新发现**：分片 `UPDATE IGNORE` 的告警**无法活体验证**，因为另有独立缺陷（本刀活体副产物）→ **I-DEBT-74**：`VertxUpdateExecuter.handleTargets` 用 `statement.clone()` 造每片语句，而 druid 1.2.28 的 `MySqlUpdateStatement.clone()` 降级为 `SQLUpdateStatement` 并**丢 `ignore` 标志**（离线取证：parse→`UPDATE IGNORE …`，clone→`UPDATE …`），于是代理把真库的 warning 1265 升级成 ERROR 1406；同语句打 normal 表（单目标路径）仍为告警，坐实差异只在"经分片改写"这一跳。属 SQL 改写保真面，不并入本刀。其余未覆盖：NATIVE 协议池、多目标 SELECT 的 live 验证、Note 级条件、`SHOW ERRORS` / `@@session.error_count`。


### Blade D 余项 · SHOW ERRORS / @@error_count 同族读面落地（2026-10-31）

**问题**：§6b 各刀收口时把「`SHOW ERRORS` / `@@session.error_count` 同族未接」作为诚实边界挂账（见上节 L104 同族登记 + L228 未覆盖清单）。`ShowErrorsSQLHandler` 原为假空 stub（`setRows(Collections.emptyList())`），`@@error_count` 读腿缺失——与已接线的 `SHOW WARNINGS` / `@@warning_count` 不对称。本刀补齐该同族读面，可自包含、无需另起专轮。

**活体取证（直连真库·MySQL 8.0.46@3306 与 MariaDB 10.11.19@3308 双档一致）**：
- `SELECT 1/0`（纯 Warning 1365）后 → `SHOW WARNINGS` 出 Warning 行；`SHOW ERRORS` = **空**；`@@warning_count`=1、`@@error_count`=**0**。
- 产 Error 的语句（无 db → 1046 No database selected）后 → `SHOW ERRORS` 出 `Error | 1046`；**`SHOW WARNINGS` 亦含该 Error 行**（真库 SHOW WARNINGS **不过滤** Error，坐实本批「不动 SHOW WARNINGS」的既有不过滤行为正确）；`@@error_count`=1。
- `SHOW COUNT(*) WARNINGS` 列头 = `@@session.warning_count`；`SHOW COUNT(*) ERRORS` 列头 = `@@session.error_count`，值 = Error 级条数（纯告警 → 0）。

**契约裁定**：
1. `SHOW ERRORS` = 会话缓冲按 `Level=="Error"` **过滤**后构造行集（与 SHOW WARNINGS「不过滤」相反）；`COUNT(*)` 形态单列单行、活体列头 `@@session.error_count`、值 = Error 级总数。
2. 先按 Level 过滤，**再**受 `max_error_count` 截断与 `LIMIT o,n` 切片（切片发生在 Error 子集上）；`COUNT(*)` 不受截断（计数与列表解耦，沿用 Blade B/C 口径）。
3. `@@error_count` = 上一条语句结束时的 Error 级条件数，读腿走新增的 `previousErrorCount`；`clearWarnings()` 归档时按 Level **分别计**（`previousWarningCount`=全量 size、`previousErrorCount`=Error 级计数），两条互不污染。
4. 读面只读不清（`MycatdbCommand.isDiagnosticReadStatement` 早已含 `MySqlShowErrorsStatement`，入口不清空，SHOW ERRORS 后仍可读到上一条缓冲）。

**改动（4 生产文件）**：
- `common/.../MycatDataContext.java`：接口加 `default int getPreviousErrorCount(){return 0;}`（与既有 `getPreviousWarningCount` 对称）。
- `proxy/.../runtime/MycatDataContextImpl.java`：加 `previousErrorCount` 字段（类级 `@Getter` 自动生访问器）；`clearWarnings()` 追加 `previousErrorCount = countErrorLevel(warnings)`；新增 private `countErrorLevel`；`getVariable` 加 `error_count` 分支返回 `getPreviousErrorCount()`（**关键：必须在会话域内取值，不得溢出到变量服务的静态模板串，否则 `SELECT @@error_count` 落到模板串/NULL**）。
- `server/.../connection/JdbcMycatDataContext.java`：无 Lombok ⇒ 手动加 `previousErrorCount` 字段 + `getPreviousErrorCount()` override + `getVariable` 的 `error_count` 分支 + `clearWarnings()` 分级归档。
- `server/.../sqlhandler/dql/ShowErrorsSQLHandler.java`：从假空 stub 全量重写——`buildErrorRows` 纯函数（Error 过滤 + COUNT/LIMIT/截断），`onExecute` 按 `isCount()` 分支列定义（COUNT 用活体列头 `@@session.error_count`，普通形态沿用 `getShowErrorsColumns()`），复用同包 `ShowWarningsSQLHandler.resolveMaxErrorCount`（`NO_TRUNCATION=-1` 保守退化）。

**RED（先看红）**：
- 新建 `ShowErrorsRowBuilderTest`（server）10 测（过滤/纯告警空/COUNT/计数不截断/列表截断/LIMIT 取小/偏移切片/越界/负数哨兵），先加最小 stub `buildErrorRows` 返 `emptyList` 令其可编译并观察红。
- `MycatDataContextWarningVariableTest`（proxy）追加 4 枚 error_count 测 → 实测 4 枚 `ClassCastException: String cannot be cast to Number`（`error_count` 落到变量服务模板串 `SESSION_TEMPLATE` 而非会话整数），权威坐实缺读腿。
- 新建 `JdbcMycatDataContextErrorCountTest`（server）4 测，同 proxy 语义但直接 `new JdbcMycatDataContext()`。

**GREEN 验证**：定向 proxy `MycatDataContextWarningVariableTest` **12/0F/0E**、server `JdbcMycatDataContextErrorCountTest` **4/0F/0E** + `ShowErrorsRowBuilderTest` **10/0F/0E**（RED 4 CCE → 全过，闭环）。全量 `mvn -o -pl example -am test` → **BUILD SUCCESS（17:12 min）· 1113 tests / 0F / 0E / 37 skip**（逐模块：config 29、common 49、router 18、datasource 7、va 12、proxy 25、hbt 294、executor 6、server 126、example 547/37skip；较上批基线 1095 恰 **+18** = server +14（builder 10 + JDBC 4）+ proxy +4（error_count），零回归）。

**活体对拍（本批范围·8066 persona=MARIADB_10_11·后端 8.0）**：代理端 `SHOW ERRORS` → 空、`SELECT @@warning_count, @@error_count` → `0 | 0`、`SHOW COUNT(*) ERRORS` → 列头 `@@session.error_count` 值 0，与真库逐项一致。

**诚实边界**：生产诊断缓冲当前**只有 Warning 级来源**（JDBC `toWarningMessages` 统一标 Warning，见 Blade D），故活体 `SHOW ERRORS` 实际恒空、`@@error_count` 恒 0——这与真库「无 error 场景」逐项一致；Error 级过滤逻辑的正确性由 `ShowErrorsRowBuilderTest`（造 `Level=="Error"` 夹具）离线锁住，非依赖活体产 Error。已知独立偏差：`SELECT 1/0`（无 from）经代理 `@@warning_count`=0 而真库=1，系 Mycat 本地表达式求值不产 1365（**Blade E·本地除零生产者**，正交，非本批）。I-DEBT-69 整体仍 🟡：NATIVE 协议池 / 多目标 SELECT 活体 / Blade E / 分片 UPDATE 活体（受 I-DEBT-74 挡）仍未接，本刀仅关闭「`SHOW ERRORS` / `@@error_count` 同族读面」这一项，不冒 Evidenced-Done。


### Blade D 余项 · 分片 UPDATE 告警活体受 I-DEBT-74 挡 → 本刀收口（2026-11-01）

**缘起**：Blade D 分片 DML 告警接线时，`INSERT IGNORE` 跨片已闭环，但分片 `UPDATE IGNORE` 经代理报 **ERROR 1406** 而非真库 warning 1265，差异只在「经分片改写」这一跳——立钉 I-DEBT-74。本刀即闭此债，顺带解掉 I-DEBT-69 「分片 UPDATE 告警活体」挂账。

**根因（druid 1.2.28 sources 三要素坐实）**：`VertxUpdateExecuter` 每片用 `statement.clone()` 造语句；`MySqlUpdateStatement` **未覆写 clone**，继承的 `SQLUpdateStatement.clone()` 降型为通用语句且不复制 mysql 私有 `ignore`/`lowPriority`；`MySqlOutputVisitor.visit(MySqlUpdateStatement)` 只按 MySql 型字段的 `IGNORE`——降型形态走不到该重载，永不输出 ⇒ 分片物理 SQL 丢 IGNORE，真库 1265 被代理升级成 1406。对照 `MySqlDeleteStatement.clone()` 自行复制 ignore ⇒ **DELETE 无此症**，修复面收窄到 UPDATE。

**修法（TDD）**：新锁 `hbt ShardUpdateIgnoreFidelityTest` 5 锁（根因定性锁钉裸 clone 确实丢 IGNORE+降型、`handleNormal`/`handleTargets` seam 行为锁、`LOW_PRIORITY` 同根锁、`plainUpdateStaysPlain` 负面锁、多片逐片保真锁）；RED 根因测断言实得 `[UPDATE wlk SET note = ? WHERE id = ?]`。GREEN：`VertxUpdateExecuter` 新增 `cloneForShard`+`restoreMysqlUpdateFlags`（MySql 目标就地 set；被降型时属性标记+reparse 升回 `MySqlUpdateStatement` 再回写 `ignore`/`lowPriority`；标志全空零扰动），`handleTargets/handleNormal/handleEmptyWhereSharding` 全改走该 seam。GREEN 5/0/0、hbt 294→299。

**活体（8066 persona=MARIADB_10_11·后端 MySQL 8.0.46 直连 3306 同数据·VARCHAR(8) 写超长判据）**：跨片 `UPDATE IGNORE … IN(1,2)`→2×warning 1265（修前 ERROR 1406）、单片/normal 各 1×1265、无 IGNORE 仍 ERROR 1406 不误伤、M3 三行跨两片 `IN(1,2,3)`→3×1265（告警逐行回传）；物理 SQL 直证 `sql:UPDATE IGNORE dbig74_0.shg_0`。⇒ **I-DEBT-69「分片 UPDATE 告警活体」挂账就此闭环**。

**全量护栏**：`mvn -o -pl example -am test` → BUILD SUCCESS·**1118 / 0F / 0E / 37 skip**（基线 1113 恰 +5）。注：本轮 example e2e（`BackendAuthMatrixTest` 等）需 8066 代理在线。

**新立 I-DEBT-75（需专轮）**：多目标 SELECT 告警拼接活体补证时咬出跨片 fan-out 下推把 `1 DIV (id-1)` 渲染成 `CAST(INT(..))` 非法 MySQL 语法→ERROR 1064（矩阵钉界：同构 UNION 绿 / 单片 div 绿 / 跨片 div 红 / 跨片 `id+0` 绿，真库 `CAST(x AS INT)` 直跑同签名 1064）。属 codegen/方言渲染深债，不并入本刀；多目标 SELECT 告警拼接活体仍 NO_DATA（被 75 挡）。

**诚实边界**：根因在 druid 上游，本修法是 Mycat 侧补偿层（非 fork），上游修复时定性锁咬红提醒拆补偿；分片告警的 `at row N` 为各后端局部行序（代理 `1,1,2` vs 真库 `1,2,3`），属分片告警行序语义、随 I-DEBT-69 继续挂。I-DEBT-69 整体仍 🟡（NATIVE 协议池 / 多目标 SELECT 活体·被 75 挡 / Blade E / 分片告警行序），不冒 Evidenced-Done。


### Blade F · 生产者③：Error 级诊断生产者（语句失败收敛，2026-11-01 承重轮开轮）

**缘起**：§6b 各刀反复声明「生产诊断缓冲当前只有 Warning 级来源」——活体 `SHOW ERRORS` 恒空、`@@error_count` 恒 0，读腿（Error 过滤/分级归档/COUNT/LIMIT）全有离线锁但**喂不饱**。本刀接通 Error 级生产者，是 I-DEBT-69 承重轮的腿拆分首刀。

**静态断链取证（当前代码 file:line）**：
- 全仓 `new WarningMessage(...)` 生产点唯一：`common/.../NewMycatConnectionImpl.java:575-578`（JDBC SQLWarning 链，硬标 `"Warning"`，本质正确不动）。
- VERTX 活路径语句错误出口 `server/.../mycatmysql/MycatVertxMySQLHandler.java:314-334`（recover）+ 335-338（catch Throwable）：只 `setLastMessage` + `writeErrorEndPacketBySyncInProcessError(code)`，**从不写会话诊断缓冲**。
- NIO 活路径同型：`DefaultCommandHandler` / `ReceiverImpl.sendError:82-87` 只 setLastMessage。
- 结论：凡回客户端 ERR_Packet 的语句级 Error 都不进 DA → `SHOW WARNINGS/ERRORS`、`@@error_count` 对失败语句全部失真（vs 真值 C1：真库必须出 `Error|1062`）。

**单点收敛取证（ERR 包出客户端仅三处，`generateError(` 全仓调用面钉死）**：
1. `server/.../vertx/VertxSessionImpl.java:153-160`（无参重载，code 取 `mycatDataContext.getLastErrorCode()`）；
2. `server/.../vertx/VertxSessionImpl.java:162-169`（带码重载）——二者覆盖 VERTX 链全部语句失败；
3. `proxy/.../session/MySQLProxyServerSession.java:92-102` default `writeErrorEndPacketBySyncInProcessError(int)`（无参 default L80-82 委托它）——覆盖 NIO 链。
   旁路排除：`VertxMySQLAuthHandler:325` 直写 ER_ACCESS_DENIED 发生在会话 DA 建立前（真 MySQL 登录失败亦无会话诊断区可谈）→ 不入契约域；`ErrorPacketImpl` 各 new 点均为**后端收包解析**或 `generateError` 内部构造，非前端独立出口。

**活体真值表（C1-C9 + D0-D7，MySQL 8.0.46@3306 / MariaDB 10.11.19@3308 双档，scratch/probe*.txt 留证）**：
- 双档一致：① 语句级失败必进 DA（Error 级，wc=1/ec=1，`SHOW WARNINGS` 不过滤含 Error）；② 失败语句**替换**整个诊断区——同语句此前截断告警与先前语句告警均被弃（C2/C7：只剩 Error 行）；③ `INSERT IGNORE` 把 Error 降级为 Warning 级（C4/C6：wc≥1/ec=0，走告警路径非本刀域）；④ 再次失败替换为新 Error（D6）。
- **双档分叉（本批新发现）**：失败后「干净语句」对 DA 的处理——MySQL 8.0 **每条语句无条件替换**（D1-D3 一条纯 SELECT 即清 0/0）；MariaDB 10.11 Error 诊断区**跨只读语句驻留**（D1-D3 恒 1/1），直到下一条数据修改/产出新诊断的语句才替换（D4/D7 清 0/0）；而成功语句告警（C5）两档均一条即清。

**契约裁定（D7'**）**：以 **MySQL 8.0 档为基准**实现——既有语句入口 `MycatdbCommand:482-488 clearWarnings()`（每条非诊断读语句替换）本就是 MySQL 语义，零冲突；本刀只补「Error 进 DA」的生产半边，读腿（previousErrorCount 归档 / `error_count` 分支 / `ShowErrorsSQLHandler`）**全复用零改**。MariaDB 驻留差异记为**已取证分档偏差**挂账（代理同时满足两档需后端方言感知，非本刀）。落点=三处 chokepoint 收敛、单一数据面入口：
- `common/.../MycatDataContext.java` 新增 default `recordStatementError(int errorCode, String message)`：构造 `new WarningMessage("Error", errorCode, message==null?"":message)` 并 `addWarning`（null 兜底因 WarningMessage 对 message requireNonNull）。
- VERTX：`VertxSessionImpl` 两重载在 generateError 前调 `mycatDataContext.recordStatementError(...)`——**置于写守卫之外**（close 后 socket no-op 但 DA 仍写，真库诊断与投递无关）；无参重载 code=`getLastErrorCode()`。
- NIO：`MySQLProxyServerSession` default(int) 调 `getDataContext().recordStatementError(errorCode, getLastMessage())`，同样**移出 `isConnected` 守卫**。`MycatVertxMySQLHandler:333` 带 0 码兜底路径与 ERR 包同源照录（不另映射）。
- 去重不做（单语句单出口，YAGNI）；auth 旁路不接。

**RED 锁清单**：① `recordStatementError` 数据面行为锁（Error 级入缓冲/null 兜底/wc·ec 归档联动）；② server `VertxSessionErrorDiagnosticTest`（仿 `VertxSessionCloseGuardTest` 构造：两重载 → DA 含 `Error|code|msg`；close 守卫后仍写 DA）；③ proxy NIO default 锁（`MySQLProxyServerSession` 最小 fake/mock，isConnected false/true 均写 DA）。

**状态**：✅ **已落地（2026-11-01）**。RED 9 锁（先红因正确：数据面 3 + server VertxSession 3 + proxy NIO default 3）→ GREEN 三处 chokepoint 接线 9/9 全绿 → 活体对拍（8066·**VERTX 链实证**：unknown schema Error 进 DA、wc/ec=1/1、`SHOW ERRORS` 过滤生效、下条 clean 语句替换 0/0、ERR 包行为不变，scratch/probe-8066-out.txt）→ 全量护栏 **1127/0F/0E**（基线 1118 恰 +9=本刀三测试类；example 段 547/37skip 与基线逐格一致，persona=MARIADB_10_11 对齐后）。诚实边界：① 后端原生码（1062/1406）经代理透传进 DA 的活体格未打穿（.mycat-home 无 db1 路由配置；离线锁已锁「带码重载→DA 同码」同一 chokepoint，挂活体余格）；② 错误码映射债（真库 1146 被代理回 1105/code 0）另立挂账；③ MariaDB Error 驻留分档偏差按已取证挂账不变。全量首跑挂起已根因定性：**代理 stdout 经 PowerShell Tee 管道、消费端回收后管道满致 JVM 级停摆（纯环境陷阱，与本刀代码无关，jhsdb SA 栈全线程空闲坐实）**，改直接文件重定向后一次通过。

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
