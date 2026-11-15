# 7C 下推隐式列别名裸 `?` 致后端 1064（C 层崩溃级）设计

- 日期：2026-11-13
- 路径：brainstorming / architectural
- 关联债：DEBT-QUEUE §1「7C 分诊·独立根」(b) 下推隐式列别名含裸 `?`（2026-10-26 立案，架构需专轮）
- 对齐：C（引擎地基）· M2 无缝 SELECT · §7 真库行为一致性
- 状态：取证已完成（current HEAD 坐实），待用户审 spec → 拍板 → writing-plans

## 1. 背景与问题

Mycat2 把查询下推到后端 MySQL 时，对**无显式 `AS` 的函数投影**会派生一个隐式列别名。该别名取自**参数化后的表达式文本**，其中字面量已被折成动态参占位符 `?`。例如：

```sql
SELECT DATE_FORMAT("2017-06-15", "%Y") FROM db1.travelrecord;   -- 无显式 AS
```

下推 SQL 渲染为：

```sql
SELECT DATE_FORMAT(?, '%Y') AS `DATE_FORMAT(?,'%Y')` FROM db1_0.shg_0 AS `shg`
  union all SELECT DATE_FORMAT(?, '%Y') AS `DATE_FORMAT(?,'%Y')` FROM db1_1.shg_0 AS `shg`
```

别名 `` `DATE_FORMAT(?,'%Y')` `` 内含裸 `?`。后端执行报 **ERROR 1064**，活体复现于 `example/.../PushDownFunctionShardingTableTest.testTimeFunction`（2026-10-26 立案铁证）。

**blast**：波及**所有「函数投影无显式别名」的下推查询**（DATE_FORMAT / YEAR / CONCAT / 任意标量函数投影）。显式 `AS` 不受影响。

## 2. 根因（current HEAD 2026-11-13 取证坐实，非旧记录）

### 2.1 离线 probe 实证（mode=local，证据 `scratch/7c-probe2.log`）

对当前 HEAD 跑离线 planner probe，dump 下推 SQL，四例坐实裸 `?` 别名**仍在**（未被 I-DEBT-76/77/78 的 CAST deparse 改动触及）：

| 用例 | 下推别名 | 裸 `?` |
|------|----------|--------|
| `DATE_FORMAT("2017-06-15","%Y")` 无 AS | `` AS `DATE_FORMAT(?,'%Y')` `` | 是 |
| `DATE_FORMAT(...) AS f` | `` AS `f` `` | 否（显式 AS 干净） |
| `YEAR("2017-06-15")` 无 AS | `` AS `YEAR(?)` `` | 是 |
| `CONCAT('a','b')` 无 AS | `` AS `CONCAT(?,?)` `` | 是 |

rel 计划实证字段名本身即参数化文本：`LogicalProject(DATE_FORMAT(?,'%Y')=[DATE_FORMAT(?0, _UTF-8'%Y')])`。

### 2.2 完整因果链（代码逐点钉死）

1. **参数化**：`DrdsRunnerHelper.preParse:121` `outputToParameterized` 把字面量折成 `?`，交 Calcite 校验。
2. **隐式字段名派生**：Calcite validator 对无 `AS` 的投影，从**参数化表达式文本**派生隐式列名 → `DATE_FORMAT(?,'%Y')`（含 `?`）。
3. **别名复用**：`hbt/.../io/mycat/calcite/RelToSqlConverter.java:1025-1028` `addSelect` 取 `rowType.getFieldNames()` 作下推别名，经 `SqlImplementor.as:151-159` 包成 `new SqlIdentifier(alias)` → unparse 渲成反引号标识符 `` `DATE_FORMAT(?,'%Y')` ``。
4. **参数绑定错配（1064 真机制）**：`hbt/.../executor/MycatPreparedStatementUtil.java:493-496` `executeQuery` 对渲染 SQL 调 `prepareStatement(sql)`，再 `extractParams(params, dynamicParameters)` 绑参。
   - 渲染 SQL 含 **3 个 `?` 字符**（1 个真 DATE_FORMAT 实参 + 2 个别名内）。
   - `SqlString.getDynamicParameters()` 只追踪 **真 `SqlDynamicParam`**（别名是 `SqlIdentifier` 文本，非动态参）→ 只含 1 个真参索引。
   - JDBC 驱动把 SQL 串里**所有 `?`**（含别名内）计为占位符 → 期望 3 参；Mycat 只绑 1 参 → 别名 `?` 占位符**未绑** → 后端 1064。

### 2.3 实证纠正旧论断（§0 铁律：不信看板旧记录）

旧看板称「`?` 在标识符位不参与参数回填→发后端残留裸 `?`→1064」。**直连真库实证**：

```sql
SELECT DATE_FORMAT('2017-06-15', '%Y') AS `DATE_FORMAT(?,'%Y')`;   -- 返 2017，无 1064
```

⇒ **反引号内含 `?` 的别名本身是合法 MySQL 标识符**，单独并不致 1064。真机制是 **§2.2 步骤 4 的 JDBC 占位符错配**（别名 `?` 被驱动计为未绑占位符），而非「标识符非法」。此纠正影响修法选型：修复目标是**让别名不含 `?`**（消除占位符错配），而非「转义标识符」。

### 2.4 既有杠杆

`DrdsRunnerHelper.preParse:106-109` 对无 `AS` 投影**已**从**原始（未参数化）AST** 派生别名进 `aliasList`（字面量形态 `DATE_FORMAT('2017-06-15','%Y')`），用作**结果列标签**（`CalciteRowMetaData:80-83`）。即：
- **结果列标签**（回客户端）= `aliasList` 字面量形态，**已正确**（与真 MySQL 列名一致）。
- **下推别名**（后端列标签）= validator 参数化字段名，**含 `?`，是 bug**。

二者来源不同。下推别名仅影响后端列标签与 JDBC 占位符计数；Mycat 回客户端的列标签由 `aliasList` 覆写，故下推别名形态**不直接影响结果列标签保真**。

## 3. 目标与非目标

**目标**
1. 消除「函数投影无显式别名」下推查询的后端 1064（崩溃级，M2 无缝 SELECT 硬伤）。
2. 下推别名不再含动态参 `?`，使 JDBC 占位符计数与真参一致。
3. 不回归结果列标签保真（`aliasList` 字面量形态维持）。
4. 不回归显式 `AS` 路径与既有下推保真（I-DEBT-76/77/78 CAST/SIGNED/binary 锁）。

**非目标**
- 不改真 MySQL 列名语义（结果列标签仍由 `aliasList` 决定，本轮不动）。
- 不处理 I-DEBT-45 列头家族窄差（独立格）。
- 不处理其它 C 层崩溃项（GREATEST/LEAST 本地 codegen 崩、除零 warning 等各自独立）。
- 不引入上游 Calcite 版本升级。

## 4. 架构与修法候选

### 4.1 落点 seam（跨切，故架构需专轮）

- **calcite-core fork**：validator 隐式字段名派生（从参数化表达式文本取列名）。
- **hbt `RelToSqlConverter.addSelect:1025`**：下推别名复用字段名。
- **hbt `SqlImplementor.as:151`**：别名包 `SqlIdentifier`。
- **hbt `MycatPreparedStatementUtil.executeQuery:493` + `SqlString.getDynamicParameters`**：JDBC 占位符计数与真参绑定（错配发生处）。

### 4.2 修法候选

**候选 A · 下推别名走字面量（非参数化）形态【推荐主攻】**
- 让无 `AS` 投影的下推别名取**原始表达式文本**（字面量形态，如 `DATE_FORMAT('2017-06-15','%Y')`），与真 MySQL 列名一致、不含 `?`。
- 两条实现路径（plan 期二选一，取更小改动者）：
  - **A1**：把 `preParse` 已有的 `aliasList`（字面量形态）线进下推别名派生，当字段名为隐式别名时以 `aliasList` 覆写参数化字段名。杠杆已在（§2.4），改动集中在 hbt deparse 侧。
  - **A2**：令 Calcite validator 从**参数化前**的表达式文本派生隐式字段名，使 `rowType.getFieldNames()` 天然为字面量形态。触 fork validator，blast 较广。
- 优点：对齐真 MySQL 列名；别名无 `?`→JDBC 占位符计数正确；结果标签保真不变。
- 代价：触共享 codegen；须保证字面量形态别名在**所有**隐式别名路径一致（含 UNION fan-out 各 fragment）。

**候选 B · 动态参隐式别名回退 `EXPR$n`【fallback】**
- 当隐式字段名含 `?` 时，下推别名回退 Calcite 平凡别名 `EXPR$n`（不含 `?`）。
- 优点：改动最窄（addSelect 局部检测 `?`→替换）；别名无 `?`。
- 代价：后端列标签 = `EXPR$n`（与真 MySQL 列名分叉，但 §2.4 已证结果标签由 `aliasList` 覆写，故客户端无感）；须确认所有结果路径确由 `aliasList` 覆写、无直透后端列标签的腿。

**候选 C · 参数绑定层感知别名 `?`【否决】**
- 在 JDBC 绑参处做「引号感知 `?` 计数」或渲染时剥离别名 `?`。
- 否决理由：治标不治本（别名仍含 `?`、仍与真 MySQL 列名分叉），且绑参层引号感知 blast 广、易引新错配。根因在别名派生，应在源头消除 `?`。

### 4.3 选型建议

**A 主攻**（对齐真 MySQL 列名 + 杠杆已在 + 结果保真天然不变），**B 为 A 受阻时的 fallback**。A1/A2 由 plan 期依「最小改动 + 离线可锁」裁定。C 否决。

## 5. 分阶段范围

**Phase A（本轮验收）**：在 `addSelect` 别名派生 seam 落选定修法，跑通「无 AS 函数投影下推 → 别名无 `?` → JDBC 占位符计数正确 → 后端无 1064」闭环。
- 交付：离线落码锁（仿 `CrossShardCastPushdownFidelityTest` 姿势，mode=local）+ 活体 `testTimeFunction` 转绿。

**Phase B（续做，同范式）**：覆盖其余隐式别名形态（多函数投影、UNION fan-out 各 fragment、嵌套子查询别名），确认 blast 全收。

## 6. 验证

1. **离线落码锁**（防复发，mode=local 无需真库）：断言 `SELECT DATE_FORMAT("2017-06-15","%Y") FROM shg`（无 AS）下推 fragment 别名**不含 `?`**；修复前必红（当前 `` `DATE_FORMAT(?,'%Y')` ``），修复后转绿。同跑 `YEAR`/`CONCAT` 形态 + 显式 `AS f` 对照（须仍干净）。
2. **不回退锁**：`CrossShardCastPushdownFidelityTest`（I-DEBT-76）/ `CrossShardBinaryLengthPushdownFidelityTest`（I-DEBT-77/78）同跑须仍绿。
3. **活体 e2e**：重打 fat jar + 重启 8066，`PushDownFunctionShardingTableTest.testTimeFunction` 须绿（DATE_FORMAT 行不再 1064，值与直连 oracle 逐格全等）；`testCharFunction`/`testMathFunction`/`testCastFunction` 不退化。
4. **护栏**：`mvn -o test -pl hbt,router,example`（相关簇）零回归；结果列标签保真（`aliasList`）抽查与真 MySQL 列名一致。

### 6.1 Evidenced（2026-11-13 A1 落地，executing-plans inline）

- **离线锁 RED→GREEN**：`ImplicitAliasBareParamFidelityTest` 6/6（DATE_FORMAT/YEAR/CONCAT/多投影/显式 AS/UNION fan-out 别名均无 `?`）；RED 取证 `scratch/7c-t1-red.log`（旧 `` AS `DATE_FORMAT(?,'%Y')` ``），GREEN `scratch/7c-t1-green4.log`。
- **不回退**：`CrossShardCastPushdownFidelityTest` 1/1 + `CrossShardBinaryLengthPushdownFidelityTest` 2/2（I-DEBT-76/77/78 CAST/SIGNED/binary 锁仍绿）。
- **活体 e2e**：重打 fat-jar（BUILD SUCCESS 4:20）+ 重启 8066，`PushDownFunctionShardingTableTest#testTimeFunction` 1/0（DATE_FORMAT 行不再 1064，值与直连 oracle 逐格全等，`scratch/7c-live.log`）；函数簇 testChar/Math/Cast/Time 4/4（`scratch/7c-guard-example.log`）。
- **护栏**：hbt 320/0/0 + router 18/0/0 BUILD SUCCESS（`scratch/7c-guard-hbt-router.log`），零回归。
- **seam 裁定（Ruling）**：覆写须在 **CBO 前** rename `logPlan`——MycatView 下推别名取自 CBO 期定稿 rowType，顶层 rename 晚于/高于 MycatView 叶子无法触及下推 fragment。验证须 `-pl internalexample -am` 重建 hbt（无 `-am` 跑 stale classes 出假 RED/GREEN）。

## 7. 风险与退路

- **R1 · 字面量形态别名 blast**：A 改共享 codegen，须确认所有隐式别名路径（含 UNION 各 fragment、子查询）一致取字面量形态，避免漏改某腿仍含 `?`。退路：B（EXPR$n）局部兜底。
- **R2 · 结果标签保真**：§2.4 已证结果标签由 `aliasList` 覆写、与下推别名解耦；但须活体抽查确认无直透后端列标签的腿（若有，B 的 EXPR$n 会泄到客户端→须 A）。
- **R3 · fork validator 改动（A2）**：若选 A2 触 calcite-core fork，须守 fork 零 `io.mycat` 闸门 + 上游可 rebase；A1 不触 fork，优先。
- **R4 · 旧版行为承重**：若取证揭示某隐式别名路径的参数化形态承重（如某下游靠 `?` 计数），退路为保留最小显式 diff 而非全改，并把真相写回本 spec 修订。

## 8. 取证证据清单

- 离线 probe dump：`scratch/7c-probe2.log`（4 例下推 SQL + rel 计划字段名）。
- 直连真库实证：反引号 `?` 别名返 2017 无 1064（§2.3）。
- 代码 seam：`RelToSqlConverter.java:1025` / `SqlImplementor.java:151` / `MycatPreparedStatementUtil.java:493` / `DrdsRunnerHelper.java:106-121` / `MycatView.java:442-471`。
- 立案铁证：DEBT-QUEUE §1「7C 分诊·独立根」(b)（2026-10-26，`testTimeFunction` 活体 1064）。