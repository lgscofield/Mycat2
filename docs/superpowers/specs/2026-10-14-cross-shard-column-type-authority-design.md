# 跨源列类型唯一权威（I-DEBT-41）设计

**日期**：2026-10-14
**债**：I-DEBT-41（跨片两缝同移 · carrier 作用域收口）
**裁定依据**：本设计的目标与验收全部锚定 [`ADJUDICATION.md`](./ADJUDICATION.md) 对拍基准——真 MySQL/MariaDB 各 persona 版实测 wire 为字节终审，官网文档定规则。
**关联**：I-DEBT-34（两缝同移，identity/同构已闭）、I-DEBT-42（A′ 列名守卫 `aac7f36ff`，部分缓解本面）、I-DEBT-35/I-DEBT-36·残（同族类型表征/跨片计算列）。

## 1. 问题与实证

Mycat 结果集有**两处各算各的列类型**，能不一致：

- **列头缝**（`PrepareExecutor.java:83-85`）：整个顶层结果集用**唯一 per-query carrier**（`AsyncMycatDataContextImpl.java:67-74`，`AtomicReference` + 首写者胜 `compareAndSet(null, meta)`）经 `RowMetaDataFidelity.effective(carrier, plan)` 建**一份列头**。
- **解码缝**（`VertxExecuter.java:914-918`）：**每个扫描用各自的** 后端轨 `effective(本扫描后端 meta, 本扫描计划)` 独立解码行值。

identity/同构 split 两缝一致（I-DEBT-34 已闭、活 e2e 绿）。但**同 arity 异构多源**（`union all` 分支类型不同、`MycatSQLTableLookup.java:272` 复用外层 carrier）时，首写者胜拿到的轨被当顶层列头，另一分支行值按自身轨解码 → **列头类型 ≠ 行值类型**。

**对拍实测（2026-10-14，MySQL 8.0.46，活靶红锁 `CrossShardUnionHeaderDifferentialTest`）**：

| 侧 | 列类型 | 行值 |
|---|---|---|
| 期望（直连真 MySQL 8.0.46；JDBC `ResultSetMetaData` + CLI `--column-type-info` 双背书） | **DECIMAL**（precision/displaySize=19） | `[1,7,45,123,170,255]`（bit 归成整数） |
| 实际（经 Mycat 跨源） | 列头定数值/Long 轨 | 服务端崩 `java.lang.ClassCastException: class [B cannot be cast to class java.lang.Long` |

关键 nuance（同轮实测）：同为 `bit ∪ bigint`，**列**归并成 DECIMAL，**字面量**（`b'..'∪123`）归并成 VAR_STRING，且跨版（5.7/MariaDB）collation/长度/小数位各异——**归并结果是随形状与 persona 漂移的移动靶**。

## 2. 架构不变式（正解）

> **每个输出位置只有一把"列类型权威"：发首行前一次性定死，同时喂列头缝与解码缝；其取值 = 当前 persona 后端就这条查询实际产出的列元数据。Mycat 不臆造后端未给出的类型，也不内建归并规则表。**

- **形状谓词**（计划期即知，避免等异步运行态）：顶层结果节点分两类——
  - **直通 / 同构单源**：权威 = 后端物理轨（= 现有忠实轨，行为不变）。
  - **异构多源 / 计算列**：需按下面"来源策略"定权威。
- 权威一旦确定，列头缝与解码缝**读同一份**，消除两缝分叉窗口。

## 3. 来源策略（已拍板：① 主 ③ 底，② 排除）

- **① 后端为准（主路）**：能由单个后端跑/描述整条合成查询时，向该 persona 后端取回**该查询的列元数据**（describe / prepared 列定义 / `LIMIT 0` 执行取 header），用作权威。版本天然正确，Mycat 不懂归并规则。行值据此渲染（如 bit 值归到 DECIMAL 轨）。
- **③ 退计划轨（底）**：当权威取不到（真正跨多 ds、无单后端能看全整句）时，列头缝与解码缝**一致回 Calcite 计划轨**（改前的一致态）。保证**不崩、不乱码**；但计划轨类型（Calcite 无 BIT，见 I-DEBT-34）可能 ≠ MySQL 精确归并 → 该类格按 persona 记入对拍表为**已知边界，不谎称兼容**。
- **② Mycat 内建归并规则表**：**否决**。§1 nuance 实证其为移动靶，复刻各版规则=维护地狱，正是"越修越多"根因。

## 4. 两片落地（各自独立可绿）

**切片 1 —— 立不变式、拆雷（不崩优先）**
- 引入 per-query"列类型权威"对象（取代异构场景下的单槽首写者载流语义），发首行前依形状谓词一次定；列头缝与解码缝均读它。
- 异构/计算 → 先走 ③ 计划轨两缝一致。
- DoD：活靶红锁 `CrossShardUnionHeaderDifferentialTest` **不再崩**（`rowError==null`）；同构/identity（`caseCrossShardBitColumnFidelity`）不回归。

**切片 2 —— ① 后端为准接致，追求精确归并**
- 加"单后端可跑/describe 合成查询取列元数据"通路，按 VersionProfile persona 选择；权威取到则列头/解码用后端归并类型。
- 逐 persona（5.7/8.0/8.4/9.0/MariaDB 10.6/10.11/11.4）把 `CrossShardUnionHeaderDifferentialTest` + 新增 lookup 异构活靶跑进对拍基准，扩至比列头五元组。
- 取不到权威的极端跨 ds 情形落 ③ 并逐 persona 记边界。
- DoD：8.0 活靶列头 = DECIMAL 与后端逐字段一致、行值等；各 persona 差异表成文；describe 不可行者显式标注为 ③ 边界。

## 5. 验收（依 ADJUDICATION）

- 活靶红锁转绿：期望（直连后端）vs 实际（经 Mycat）逐字段一致（列类型名/长度/标度 + 行值）。
- 跨 persona 差异表成文，附镜像版本 + 命令 + 输出；② 类"仅 8.0 一版"不作数。
- 崩溃面归零：任何异构多源不得 `[B→Long` 类服务端崩（切片1 即保）。
- 回归门：`caseCrossShardBitColumnFidelity`、`CrudResultMatrixTest` 逐 persona FAIL 不增、`HbtTest` 绿。
- 边界诚实：③ 退回计划轨的格，按 persona 记为已知分歧，**不计入兼容**。

## 6. 风险

- ① 的 describe 通路触及查询下推/物化契约与异步时序（权威须在发首行前就绪），为最大不确定点 → 先切片1（③ 保底不崩）锁定安全，再切片2 增量接 ①。
- 解码缝去独立化改读统一权威，波及 BOOLEAN/JSON 等其它跨片解码面（终审 Important#2 曾点名）→ 逐类型对拍护栏，禁止一处改动引入新沉默面。
