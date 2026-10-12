# R5 基本 CRUD 全绿 · 三枚引擎深债根（I-DEBT-39 / I-DEBT-34 / I-DEBT-36）设计

> **状态**：设计 spec（brainstorming 产物，待用户评审后进 writing-plans）。
> **里程碑**：`COMPAT-MILESTONE-PLAN.md` **R5**（基本 CRUD 全绿 + 全 connector）之**第一半**——CRUD 红族转绿；connector 冒烟网经用户裁定**拆出另轮**。
> **招牌目标**：客户端眼中无缝替代 MySQL——连上之后"把数据放进来、再原样取出来"不得变形、不得打挂会话。本 spec 关掉 compat CRUD 矩阵**跨全 7 档恒定的 3 枚红格**，点亮 M2"基本 CRUD"子门。

## 1. 背景与现状（亲验证据，2026-10-12 run-matrix ×7 档）

`CrudResultMatrixTest` 10 格 × 7 档（mysql 5.7/8.0/8.4/9.0 + mariadb 10.6/10.11/11.4）：**每档恰好恒红同 3 格、其余 7 格全绿**。红格与后端版本无关 ⇒ 纯 **Mycat 引擎侧缺陷**（DEBT-QUEUE 第 3 步 C 层债），修一处点亮全 7 档。逐格签名（surefire XML 取证）：

| 格 | 债 | 错误签名 | 性质 |
|---|---|---|---|
| `divideByZeroIsCleanError` | I-DEBT-39 | `select 1/0` 后会话被杀：同连接下一条 `select 1` 撞 `No operations allowed after connection closed` | 运行期错误断连，非干净 ERR |
| `bitColumnFidelity` | I-DEBT-34 | `select` BIT(8) 列 → `EOFException 连接丢失`（服务端崩） | 类型表征声明/运行时错配 → codegen 崩会话 |
| `timestampDiffFunctionCell` | I-DEBT-36 | `ClassCastException: SqlCharStringLiteral → SqlIntervalQualifier` | 函数接入/下推 deparse 缺口 |

**落点亲验**：

- **A（I-DEBT-39）**：vertx 查询错误路径 `server/.../mycatmysql/MycatVertxMySQLHandler.java:310-336` 已有 `.recover(...)` → `writeErrorEndPacketBySyncInProcessError(errorCode)`（写一枚 ERR）。但实测连接仍死 ⇒ **真凶断点未定位**（候选：运行期异常走了 `socket.exceptionHandler`（`:55-60`）之外的致命路径、Rx 流水线 Undeliverable、或写侧触发 `VertxSessionImpl.close`）。规划期错误干净回 ERR、运行期错误断连——两路径分叉点是本轮诊断核心，**不得凭猜直接改**。
- **B（I-DEBT-34）**：fork `SqlTypeName` 无 BIT，`Types.BIT→BOOLEAN`（`SqlTypeName.java` L300 区）→ codegen `getJavaClass`=boolean/Boolean；而运行时行值 = Long（`MycatDataType.BIT.getJavaClass()=Long.TYPE`；`hbt/.../vertx/BaseRowObservable.java:123-125,316-318` case BIT；`server/.../mysqlclient/decoder/ObjectArrayDecoder.java:245` `PacketUtil.decodeBit`）。声明 Boolean 强转实取 Long → 崩。现状由 `hbt/.../TypeRepresentationThreePathConsistencyTest.knownGapBitColumnIsRepresentedAsBooleanNotLong` 钉住（该钉测随本设计**更新到新契约**）。同族 **I-DEBT-35（BINARY/BLOB ByteString vs byte[]）经用户裁定不夹带**——本根立"声明=运行时"表征样板，仅落 BIT。
- **C（I-DEBT-36）**：main `hbt/.../calcite/MycatCalciteMySqlNodeVisitor.java:1911-1915` 仍把 unit 关键字建 `SqlLiteral.createCharString` → 校验期强转 CCE（首层）。**特性分支 `fix/i-debt-36-timestampdiff`（`f685ad60c`，未合）**已证：unit→`SqlIntervalQualifier` 后首层 CCE 消失、逻辑计划正确（`TIMESTAMPDIFF(SECOND,a,b)` → `CAST(/INT(Reinterpret(-($2,$1)),1000)):INTEGER` = 86400），**但阻塞后移至 deparse**：`Reinterpret` 是 Calcite 规划器内部算子，Mycat 的 `toSqlString` SPECIAL 分支抛 `UnsupportedOperationException`。正解=**原生三参透传**（不让 Calcite 重写成 interval 算术，把 `TIMESTAMPDIFF(unit,t1,t2)` 原样交后端执行/可反解析）。复现锁：`example UserCaseTest.caseI36Timestampdiff`（RED，断言 86400L）。

## 2. 目标 / 非目标

**目标（本 R5 第一半）**
1. **Root A**：语句级运行期错误（含 `select 1/0`）→ 回**一枚干净 MySQL ERR 包**且**会话保持存活**可继续下一条查询；诊断定位真断连点后最小修，与规划期错误路径行为对齐。
2. **Root B**：BIT 列**对外交付 MySQL 官方口径**（见 §3），且跨片归并不再崩会话。
3. **Root C**：`TIMESTAMPDIFF(unit,t1,t2)` 全链路走通（解析→校验→计划→deparse/下推→后端求值→回值保真 86400）。
4. 三格 compat 转绿 = 各根 DoD；全 7 档重跑矩阵背书。

**非目标（用户裁定划出）**
- §1.1 多语言 connector 冒烟网（Go/Python/.NET/C）——另轮。
- I-DEBT-35 BINARY/BLOB 表征联动根除——不夹带（样板归属本 R5-B，应用留另轮）。
- R4 前端 TLS、ed25519——各属其轮。
- push/合主干——本地闭环，用户拍板才外溢。

## 3. Root B 外部契约（官方口径查证，dev.mysql.com + MariaDB）

**判据来源**（"无缝替代"以真库客户端行为为金标准，不由我们替 MySQL 定类型）：

- MySQL Connector/J 官方映射表（§6.5 Table 6.20）：`BIT(1)` → type name `BIT`、类 `java.lang.Boolean`；**`BIT(>1)` → `byte[]`**。（两家连接器均**不**返回 Long。）
- MariaDB Connector/J：同契约 byte[]（bug 单 [CONJ-985] "getObject() 对 BIT 返回 BitSet 而非 byte[]" 被当作缺陷修复）。
- Wire 事实：MySQL 文本协议把 `BIT(n)` 值作 **(n+7)/8 字节大端原始字节串**发送，字段类型 `MYSQL_TYPE_BIT`。

**由此定案（推翻早期"扳数值 Long 保真 179"草案）**：
1. Mycat 前端对 BIT 列：字段元数据报 `MYSQL_TYPE_BIT`、值按 MySQL 字节串形态下发——连接器客户端行为（BIT(1)=Boolean、BIT(>1)=byte[]）**自然由连接器复现**，Mycat 不代猜。
2. 内部表征错配（声明 Boolean vs 运行时 Long）修复方向二选一（行面扳 byte[] 或声明面扳 Long+编码层转字节串），**由诊断期实证裁定**；唯一硬约束=对外契约 §3.1 与零回归。
3. **compat C6 断言本身失真**（期望 179 直转 Number）：改为**双连接金标准对拍**——同一 `select c_bit` 分别走"后端直连真库"与"Mycat 前端"，断言 getObject 类型与值逐字节一致。此即"无缝"的客观定义，且逐档后端（MySQL/MariaDB）各自成立。
4. **边界记档不扩 scope**：连接器默认 `tinyInt1isBit=true` 使 TINYINT(1) 上报 BIT/Boolean——属客户端 quirk，Mycat 只需把字段类型报对（`MYSQL_TYPE_TINY` + length），quirk 由连接器自行复现。

## 4. Root A 契约与诊断任务

- **金标准**：真 MySQL `select 1/0` → `ERROR 1365 (25000) Division by 0`，干净 ERR、会话存活。
- **保底契约（DoD 断言）**：Mycat 前端回干净 ERR 包（任意错误码）+ 同连接下一条查询可用。
- **争取契约（计划期对拍裁定）**：ERR 码对齐后端 1365/SQLSTATE `25000`——若对齐成本低则做，不硬撑（避免为错误码动执行器异常语义全局）。
- **诊断先行**（systematic-debugging）：定位"recover 已写 ERR 却仍断连"的真凶——候选路径 §1-A 所列；产出"哪类错误关会话"的分类（语句级 vs 协议级 vs 致命级），修复只收窄语句级一类，**不得**把致命错误也保活。

## 5. Root C 方案（两段式）

1. **收割旧分支**：cherry-pick/rebase `f685ad60c` 的 hbt 改动（unit→`SqlIntervalQualifier`，含 `timestampDiffUnitToQualifier` helper）+ `caseI36Timestampdiff` 红锁，适配 main 现码。
2. **原生三参透传**（真活）：让 TIMESTAMPDIFF 不落在 Calcite 标准 `TIMESTAMP_DIFF` convertlet 的 `Reinterpret` 算术重写上——保留 Mycat 自有 `TimestampDiffFunction` 的**三参原生形态**进入 SQL 生成/deparse，原样 `TIMESTAMPDIFF(SECOND, t1, t2)` 下推后端求值。实现细节（unparse 扩展点 vs 独立 SqlOperator + 专用 writer 分支）由 plan 期定；**禁止**为单函数在 fork 内给 Calcite 加侵入式扩展点（有既往裁定）。
3. 风险：`SECOND` 等 unit 在 Calcite 校验/类型系统的通过性（旧分支已证可过）；跨片归并路径下该算子是否本地求值（若是，需 Mycat 内实现或改路由整体下推）——诊断期实证。

## 6. 序与拆分

**Root A → B → C**（体量与风险递增；A 最自洽先落地立"会话保活"契约，B 立表征样板，C 特性级收顶）。每根一个**独立可评审、可二值判定**的交付（compat 格转绿为其出口判据），plan 期由 writing-plans 切成对应任务序列。

## 7. 回归护栏（全局）

- 当前绿的 7 个 CRUD 格 ×7 档**零回归**（尤其 varbinary/date 值保真格——B 的表征改动首要雷区）。
- 认证矩阵四件套其余格（含 R3 caching_sha2 新格）零回归。
- `hbt` 守卫基线（128/0/0 档）与 `UserCaseTest` 既有钉测基线（case14/case611 预标独立值）零新增红。
- 全量复核以实跑为准：每根绿后重跑 run-matrix ×7 出逐格表，Evidenced-Done 铁律不因 commit message 自述标绿。

## 8. 全局约束

- JDK 21（LibericaJDK-21-Full）；`mvn -o`；PowerShell 7；本地不 push；提交排除 `version.txt`/`.qoder`/`.superpowers`/`.mycat-home-e2e`。
- 每根：systematic-debugging 定位 → TDD（compat 格即 RED 复现器；能落纯单测的层——如 deparse/表征——优先单测）→ 绿后全矩阵背书。
- 结论不信记录：本 spec 的 `file:line` 均为 2026-10-12 亲验；实现期若现状漂移，以当时代码复核为准修订。
