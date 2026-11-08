# I-DEBT-87 · `SELECT YEAR(...)` 杀连接：后端 YEAR 线缆类型解码成 Date → 序列化分派无分支 → UnsupportedOperationException → 会话强断

- **状态**：✅ 已收口（2026-11-08 立票即收口 · T1 钉死=JDBC 腿 yearIsDateType · 修法A · TDD 4/0/0 · 活体四法对拍 PASS；B'/C 不采纳理由见 DEBT-QUEUE 行）
- **证据源**：`audit/2026-11-08-capability-audit/`（P-B-05 + 手工复现 + boot2 日志全栈）
- **严重度**：P1（任何客户端一条 SQL 即断连；I-DEBT-32-FAMILY 声称已收口家族的漏网成员）

## 0. 摘要

`SELECT YEAR('2026-11-08')` 经 8066 执行 → `ERROR 2013 (HY000): Lost connection to MySQL server during query`，**前端连接被服务端强杀**。同族 11 个日期/标量函数（MONTH/QUARTER/WEEK/WEEKDAY/DAYOFYEAR/DAYNAME/MONTHNAME/LAST_DAY/TO_DAYS/DATEDIFF/IFNULL/DIV…）全部正常，缺陷面极窄但致死。真库直连同 SQL 返回 2026（int）。

## 1. 取证（file:line + 日志）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 复现：mysql CLI 8066 `SELECT YEAR('2026-11-08')` → ERROR 2013；Java 侧 `connection was unexpectedly lost` | 审计手工复现 ×3；`logs/P-B-05.log`（DIFF 行） |
| 2 | **YEAR 函数本体正确**：`YearFunction.year(LocalDate/String)` 返回 `Integer` | `hbt/src/main/java/io/mycat/calcite/sqlfunction/datefunction/YearFunction.java:44-56` |
| 3 | 服务端异常：`java.lang.UnsupportedOperationException: 2026-01-01`（值对象是 **java.sql.Date** 形态） | `logs/E0-05-mycat-boot2.log` L31350 全栈 |
| 4 | 抛出点：`BaseRowObservable.getObjects` 类型分派 else `throw new UnsupportedOperationException(o.toString())` | `hbt/src/main/java/io/mycat/vertx/BaseRowObservable.java:362`——**正上方即 I-DEBT-41 byte[] 修复的 MYCAT CHANGE 注释（同点同族先例）** |
| 5 | 调用栈：`VertxExecuter$4.onRow(VertxExecuter.java:911)` ← `NewMycatConnectionImpl.prepareQuery(NewMycatConnectionImpl.java:156←113)` → "result-set execution failed (streaming)" | boot2 日志全栈 |
| 6 | 后端行为：Connector/J 对 YEAR 类型列 `getObject` → `java.sql.Date 2026-01-01`（yearIsDateType 语义） | 审计直连 3306 对拍（backend=2026-01-01）；HEX 对拍确认存储值为 int 语义 |
| 7 | 对比：MONTH() 等返回 INT 无此问题；MySQL 仅 YEAR 有独立线缆类型 13 | 审计 13 函数逐一 fresh-session 摸底记录 |

## 2. 根因链（一句话）

裸投影 `SELECT YEAR('...')` 走后端执行路径，后端 YEAR 线缆类型（13）的值被解码成 `java.sql.Date`（Connector/J yearIsDateType 语义），mycat 序列化分派 `BaseRowObservable:362` 对该列类型没有 Date 分支 → else throw → 结果集失败 → 会话强断。

**诚实标注的疑点**（留实现轮钉死）：
- "哪一段把 YEAR(13) 解码成 Date"（原生腿 Vert.x fork 解码 vs JDBC 腿 Driver 行为 vs mycat RowMetaData 列型声明）未逐行钉死——本票给的是**观测面 100% 坐实的因果链**，解码点三选一待实现轮第一任务取证。
- MONTH 族为何幸存的机制推断（返回 INT 型无线缆特型）未经源码级验证。

## 3. 修法候选（留专轮拍板，本票不改码）

- **A（贴先例·最小面）**：`BaseRowObservable:362` 分派链为该场景增 Date→列型分支（对齐 I-DEBT-41 在同点的 byte[] 修法）。
- **B（根治·动 fork）**：原生腿对 YEAR(13) 列解码为 int（vertx-mysql-client fork 侧）；JDBC 腿对应参数/解码覆盖。
- **C（类型权威缝）**：跨片列头权威（`RowMetaDataFidelity`）把 YEAR 列型声明规约为 INT——与 I-DEBT-45 ColumnDef 透传轨有交叠，需防回归其保真语义。
- 倾向：A 止血 + B 根治分两刀；C 需先厘清与 I-DEBT-45 的边界再动。

## 4. 非目标

- 不重开 I-DEBT-32-FAMILY 整族（其余 19/20 函数本轮逐值对拍全等，不动）。
- 不处理同轮发现的日志伪影 `SELECT $$`（F13，独立低优先观察项）。

## 5. 风险登记册

| 风险 | 缓解 |
|---|---|
| A 的 Date 分支可能吞掉"真 DATE 列"的既有语义 | 分支只对**列型声明为非 DATE 而值为 Date** 的错配场景生效；离线锁断言真 DATE 列行为不变 |
| B 动 vertx fork 影响面大 | 复刻 fork 既有解码测试；`FrontAuthMatrixTest`/`CrudResultMatrixTest` 全量回归 |
| persona 差异（5.7 vs 8.x YEAR 语义） | 修后至少抽 2 个 persona 档活体对拍 |
| CI 网盲区（CI 为何没红） | 现有 I32Family 探针未覆盖"裸投影 YEAR"形态——补 RED-first 用例时一并纳入 |

## 6. 验收判据（Evidenced-Done）

1. `SELECT YEAR('2026-11-08')`（裸投影，8066）返回 2026 且会话存活；与 3306 直连对拍全等。
2. 离线锁 RED-first（裸投影 YEAR 会话存活断言），修复前 RED 修复后 GREEN。
3. `I32FamilyDateDiff` 类活体差分仪扩 YEAR 格；example 全量护栏无回归。
4. 抽验：`SELECT YEAR(y_col) FROM 表`（真 YEAR 列）与 `SELECT YEAR(字符串)` 两形态均绿。

## 7. 任务分解（原子）

- [ ] T1 实现轮取证：钉死"YEAR(13)→Date"解码点（原生腿/JDBC 腿/RowMetaData 三选一，file:line）
- [ ] T2 拍板修法（A/B/C）
- [ ] T3 生产码修改 + 离线锁 RED-first
- [ ] T4 活体对拍（2 persona × 直连对拍）
- [ ] T5 全量护栏回归 + 看板回写
