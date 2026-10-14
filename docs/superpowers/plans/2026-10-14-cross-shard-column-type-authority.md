# 跨源列类型唯一权威（I-DEBT-41）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每个输出列的"列类型权威"在发首行前一次性定死、同时喂列头缝与解码缝，消除异构多源（union/join/lookup 分支类型不同）下两缝分叉导致的列头≠行值与服务端崩溃。

**Architecture:** 引入 per-query 列类型权威决策（形状谓词区分"直通/同构单源"与"异构多源/计算列"）。异构多源先退 Calcite 计划轨（两缝一致、不崩），再增"单后端 describe 合成查询取归并列元数据"通路逼近真 MySQL persona 精确类型。Mycat 不内建归并规则表。

**Tech Stack:** Java 21、Maven 多模块（离线 `-o`）、Calcite fork（`calcite-core`）、MySQL/MariaDB wire（HBT）、JDBC 对拍（`example` 分片 e2e）。

**Spec:** [`docs/superpowers/specs/2026-10-14-cross-shard-column-type-authority-design.md`](../specs/2026-10-14-cross-shard-column-type-authority-design.md) · 裁定依据：[`docs/superpowers/specs/ADJUDICATION.md`](../specs/ADJUDICATION.md)

## Global Constraints

- 只支持后端 MySQL / MariaDB；一切"正确"以 ADJUDICATION 对拍基准为准（真机 wire 字节终审，官网文档定规则）。
- persona 经 `-Dmycat.persona=MYSQL_5_7|MYSQL_8_0|MYSQL_8_4|MYSQL_9_X`（`run-matrix.ps1:67-73`），默认 MYSQL_8_0。
- 崩溃面必须归零：任何异构多源不得 `[B→Long` / `UnsupportedOperationException` 类服务端崩，且不得静默错位（列头类型必与行值解码同源）。
- 每步 TDD：先写失败测试、看它失败，再写最小实现。禁止实现先于测试。
- 构建/测试离线：`mvn -o -pl <module> -am test -Dtest=<Class>`（勿触网）。
- 本地提交、**不 push**（贯穿约束）。

## Review Focus

规格隐含但单测未必覆盖、最可能咬用户的输入类——每行会在归属任务补一条锁其行为的测试：

1. **异构 union 且分片跨 ds**（本活靶：`t_bit ∪ t_bigint` 两分支落两物理库）：期望退计划轨时**不崩且两缝一致**，而非仅同 ds 才安全。→ Task 4。
2. **lookup 同 arity 异类型**（`MycatSQLTableLookup:272` 复用外层 carrier）：期望与 union 走同一权威决策、不各自为政。→ Task 3。
3. **BOOLEAN/JSON 等其它跨片解码面**（解码缝去独立化会波及，终审 Important#2 点名）：期望这些既有忠实解码面不回归。→ Task 3。
4. **空结果集**（首行前正常终结）：期望权威决策不在无行时误发/漏发列头。→ Task 2。
5. **单后端 describe 取不到归并元数据**（极端跨 ds、无单库见全句）：期望响亮落 ③ 计划轨并逐 persona 记边界，而非谎称兼容或崩。→ Task 6。

## 文件结构（创建/修改）

- 修改 `hbt/src/main/java/io/mycat/AsyncMycatDataContextImpl.java`：加 per-query 列类型权威决策（形状标志）。
- 修改 `hbt/src/main/java/io/mycat/calcite/PrepareExecutor.java:83-85`：列头缝按权威决策取轨（异构→计划轨）。
- 修改 `hbt/src/main/java/io/mycat/vertx/VertxExecuter.java:914-924`：解码缝按同一权威决策取轨。
- 修改 `hbt/src/main/java/io/mycat/calcite/physical/MycatSQLTableLookup.java`：lookup 复用外层 carrier 处接入权威决策。
- 修改 `common/src/main/java/io/mycat/beans/mycat/RowMetaDataFidelity.java` 与 `BaseRowObservable.getObjects`：bit `byte[]` 按计划轨数值/DECIMAL 合并解码不崩。
- 标记异构：计划构建期识别顶层为 Union/Join/Aggregate 且分支列类型不同（形状谓词落点在执行时按现有 physical-node 结构确定）。
- 测试：改 `example/src/test/java/io/mycat/compat/CrossShardUnionHeaderDifferentialTest.java`（活靶红锁，转绿门）；新增 `hbt/src/test/java/io/mycat/vertx/CrossShardHeterogeneousDecodeTest.java`（仿 `CrossShardBitRowDecodeTest`）；扩展 `common` 侧 `RowMetaDataFidelityTest`（若无则新建）。

---

## 切片 1 —— 立不变式、拆雷（不崩优先）

### Task 1: per-query 列类型权威标志

**Files:**
- Modify: `hbt/src/main/java/io/mycat/AsyncMycatDataContextImpl.java`（现有 `backendRowMeta` carrier 在 L67-74）
- Test: `hbt/src/test/java/io/mycat/ColumnTypeAuthorityTest.java`（新建）

**Interfaces:**
- Produces: `AsyncMycatDataContextImpl#markMergedHeterogeneousOutput()`（void）、`#isMergedHeterogeneousOutput()`（boolean）。语义：顶层输出为异构多源合并，单分支 carrier 不是合法权威 → 两缝退计划轨。默认 false（直通/同构，行为不变）。

- [ ] **Step 1: 写失败测试** — `ColumnTypeAuthorityTest.defaultIsNotHeterogeneous` 断言新 context `isMergedHeterogeneousOutput()==false`；`markThenRead` 调 `markMergedHeterogeneousOutput()` 后 `isMergedHeterogeneousOutput()==true`。
- [ ] **Step 2: 跑到失败** — `mvn -o -pl hbt -am test -Dtest=ColumnTypeAuthorityTest`；Expected: 编译失败（方法未定义）。
- [ ] **Step 3: 实现** — 加 `private volatile boolean mergedHeterogeneousOutput = false;` + 两方法（getter 返回该字段；setter 置 true）。
- [ ] **Step 4: 跑到通过** — 同命令；Expected: PASS 2/2。
- [ ] **Step 5: 提交** — `git add` 两文件 + `git commit -m "feat(hbt): per-query 列类型权威异构标志(I-DEBT-41 切片1)"`

### Task 2: 列头缝按权威取轨

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/PrepareExecutor.java:83-85`
- Test: `example/.../CrossShardUnionHeaderDifferentialTest.java`（作集成断言）；`ColumnTypeAuthorityTest` 补列头单元锁

**Interfaces:**
- Consumes: Task 1 `isMergedHeterogeneousOutput()`。
- Produces: 列头 supplier 在 `isMergedHeterogeneousOutput()==true` 时用 `RowMetaDataFidelity.effective(null, rowMetaData)`（=计划轨）。

- [ ] **Step 1: 写失败集成测试**（已有活靶红锁即此测试）——先跑确认它因崩而 `rowError!=null` 失败（当前态）。Expected: FAIL（`ClassCastException [B→Long`）。
- [ ] **Step 2: 改列头 supplier**：L83-85 lambda 改为 `effective(ctx.isMergedHeterogeneousOutput() ? null : ctx.getBackendRowMeta().get(), rowMetaData)`。
- [ ] **Step 3: 空结果集护栏**（Review Focus#4）：加单元断言——异构标志置位且零行时列头仍按统一计划轨构造、不抛。
- [ ] **Step 4: 跑到列头不再取单分支 carrier** — `mvn -o -pl example -am test -Dtest=CrossShardUnionHeaderDifferentialTest`；Expected: 列头类型不再=BIT/单分支（行解码缝尚未改，可能仍崩——记录，交 Task 3）。
- [ ] **Step 5: 提交** — commit `"feat(hbt): 列头缝按列类型权威取轨(I-DEBT-41 切片1)"`

### Task 3: 解码缝按同一权威取轨

**Files:**
- Modify: `hbt/src/main/java/io/mycat/vertx/VertxExecuter.java:914-924`（解码缝 `effective(mycatRowMetaData, rowMetaDataArg)`）、`MycatSQLTableLookup.java:272`
- Test: `hbt/src/test/java/io/mycat/vertx/CrossShardHeterogeneousDecodeTest.java`（新建，仿 `CrossShardBitRowDecodeTest`）

**Interfaces:**
- Consumes: Task 1 标志（经同一 context 传递到解码回调）。
- Produces: 解码缝在异构标志置位时对每个扫描分支返回计划轨（与列头同源），不再用各分支后端 meta。

- [ ] **Step 1: 写失败单测** — `CrossShardHeterogeneousDecodeTest`：构造 bit-branch 后端 meta + bigint-branch 后端 meta + 合并计划轨 meta，断言"异构标志下两分支 `getObjects` 用同一计划轨、结果类型一致、不抛"。Expected: FAIL。
- [ ] **Step 2: 改解码缝** — VertxExecuter 解码回调读取同一 `isMergedHeterogeneousOutput()`，为真则对该分支用计划轨 `mycatRowMetaData`（不经分支后端 effective）；MycatSQLTableLookup:272 复用外层 carrier 处同接入。
- [ ] **Step 3: 回归护栏**（Review Focus#1/#2/#3）— 跑 `CrossShardBitRowDecodeTest`（BOOLEAN/JSON/同构忠实解码不回归）+ lookup 异构断言。
- [ ] **Step 4: 跑到通过** — `mvn -o -pl hbt -am test -Dtest=CrossShardHeterogeneousDecodeTest+CrossShardBitRowDecodeTest`；Expected: PASS。
- [ ] **Step 5: 提交** — commit `"feat(hbt): 解码缝按列类型权威取轨(I-DEBT-41 切片1)"`

### Task 4: bit byte[] 合并解码不崩（拆雷收口）

**Files:**
- Modify: `common/src/main/java/io/mycat/beans/mycat/RowMetaDataFidelity.java`（合并位对 bit `byte[]` 的处理）与 `BaseRowObservable.getObjects`（数值/DECIMAL 分支对 `byte[]` 输入）
- Test: `example/.../CrossShardUnionHeaderDifferentialTest.java`（转绿门）

**Interfaces:**
- Consumes: Task 2/3（两缝均计划轨）。
- Produces: 计划轨数值/DECIMAL 列解码 bit `byte[]` 时按位→整数无符号转换（对齐 MySQL bit→数值），不再 `[B→Long` / 抛。

- [ ] **Step 1: 写失败单测** — `CrossShardHeterogeneousDecodeTest.bitByteArrayUnderNumericPlanCoercesToUnsignedLong`：`getObjects({new byte[]{(byte)0xFF}}, numericPlanMeta)` 期望返回 `255L`（非抛）。Expected: FAIL（当前抛）。
- [ ] **Step 2: 实现转换** — 在数值/DECIMAL 分支：`byte[]` 输入 → 无符号整数（`new BigInteger(1, bytes).longValue()` 语义），对齐 §1 实测 bit→数值渲染。
- [ ] **Step 3: 跑到活靶红锁转绿（③ 路径）** — `mvn -o -pl example -am test -Dtest=CrossShardUnionHeaderDifferentialTest`；Expected: `rowError==null`、两缝一致、行值 `[1,7,45,123,170,255]`（列类型名此步为计划轨值，非 DECIMAL——留给切片2 逼近）。活靶断言中"列类型名==后端"若仍不等，改断言为"不崩+两缝一致+行值正确"，列类型精确匹配移入 Task 5。
- [ ] **Step 4: 全链路回归** — `mvn -o -pl example -am test -Dtest=CrudResultMatrixTest`（逐 persona FAIL 不增）+ `caseCrossShardBitColumnFidelity` 绿。
- [ ] **Step 5: 提交** — commit `"feat(common): bit byte[] 合并解码不崩(I-DEBT-41 切片1 收口)"`

> **切片 1 出口**：崩溃归零、两缝同源一致（③ 计划轨）。异构列类型 = 计划轨值，可能 ≠ MySQL 精确归并 → 按 spec §3 记为"待切片2 逼近"，不谎称兼容。

---

## 切片 2 —— ① 后端为准逼近精确归并

### Task 5: 单后端 describe 取合成查询归并列元数据

**Files:**
- Create: `hbt/src/main/java/io/mycat/calcite/BackendMergedMetaProbe.java`（向可跑全句的单后端 `LIMIT 0`/prepared describe 取列五元组）
- Modify: `AsyncMycatDataContextImpl`（异构权威可被后端归并 meta 覆盖）、`PrepareExecutor`/`VertxExecuter`（权威优先用探测到的后端归并 meta）
- Test: `example/.../CrossShardUnionHeaderDifferentialTest.java`（列类型断言收紧到 DECIMAL）

**Interfaces:**
- Consumes: persona（`VersionProfile`）、合成查询文本。
- Produces: `BackendMergedMetaProbe#probeMergedMeta(sql, persona) -> Optional<MycatRowMetaData>`；取到则两缝用它作权威（列头/解码一致=后端归并 DECIMAL），取不到 → 保持切片1 ③ 计划轨。

- [ ] **Step 1: 写失败测试** — 红锁列类型断言改回 `assertEquals("DECIMAL", actual.columnTypeName)` 且行值等；Expected: FAIL（仍是计划轨值）。
- [ ] **Step 2: 实现探测** — 对单 ds 可表征的合成查询向该 persona 后端取列元数据；缓存 per (sql-shape, persona)。
- [ ] **Step 3: 权威接入** — 取到即覆盖异构标志走后端归并轨（两缝同读）。
- [ ] **Step 4: 跑到通过（8.0）** — `mvn -o -pl example -am test -Dtest=CrossShardUnionHeaderDifferentialTest`；Expected: PASS，列头 DECIMAL==直连后端、行值 `[1,7,45,123,170,255]`。
- [ ] **Step 5: 提交** — commit `"feat(hbt): 单后端 describe 取归并列元数据作权威(I-DEBT-41 切片2)"`

### Task 6: 逐 persona 对拍 + 边界挂账

**Files:**
- Modify: `CrossShardUnionHeaderDifferentialTest.java`（扩列头五元组比对 + 新增 lookup 异构活靶）
- 记录: `docs/superpowers/specs/DEBT-QUEUE.md`（各 persona 差异表）

- [ ] **Step 1: 扩对拍维度** — 断言列头五元组（Type/Collation/Length/Decimals/Flags）逐字段 vs 直连后端。
- [ ] **Step 2: 加 lookup 异构活靶**（Review Focus#2）。
- [ ] **Step 3: 逐 persona 跑** — MYSQL_5_7/8_0/8_4/9_X（+ MariaDB 探针镜像按需起）对拍，收集差异表。
- [ ] **Step 4: describe 不可行者落 ③** — 极端跨 ds（Review Focus#5）显式标注为计划轨边界，写入 DEBT-QUEUE，不计兼容。
- [ ] **Step 5: 提交** — commit `"test(compat): I-DEBT-41 跨 persona 列类型对拍差异表 + 边界挂账"`

## Test Plan

- 单元：`ColumnTypeAuthorityTest`、`CrossShardHeterogeneousDecodeTest`（仿 `CrossShardBitRowDecodeTest`）、`RowMetaDataFidelityTest`。
- 集成活靶：`CrossShardUnionHeaderDifferentialTest`（切片1 不崩转绿 → 切片2 DECIMAL 精确转绿）。
- 回归门：`CrudResultMatrixTest` 逐 persona FAIL 不增、`caseCrossShardBitColumnFidelity`、`HbtTest` 绿。
- 终审：`finishing-a-development-branch` 前跑全量 `mvn -o test`。
