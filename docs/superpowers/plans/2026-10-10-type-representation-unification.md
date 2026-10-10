# 类型表征统一（三路径 java 类一致不变式）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落 INV-TRU-1 三路径 java 类一致不变式：靶 1 case6052（FLOAT）+ 靶 2 case7（ANY_VALUE datetime）转绿 + 全列对账 + hbt 结构守卫 + 全量零回归。

**Architecture:** 靶 1 一行 `MycatRelDataTypeFactory.getJavaClass` 补 `FLOAT → Float.class` override；靶 2 依 Task 1 活体复现证据二选一（候选 A：`MinMaxImplementor` 加 Comparable 分支；候选 B：aggregate 全链 typeFactory 归属校正 / `EnumUtils.toInternal` 让 java.time 直通）；不变式由 hbt 参数化结构守卫 `TypeRepresentationThreePathConsistencyTest` 锁死。

**Tech Stack:** JDK 21 (LibericaJDK-21-Full)、Calcite 1.42 fork（`calcite-core`）、Janino codegen、JUnit 5、Docker MySQL 8.0/5.7/MariaDB 探针、Mycat e2e `UserCaseTest`。

**Spec:** [docs/superpowers/specs/2026-10-10-type-representation-unification-design.md](../specs/2026-10-10-type-representation-unification-design.md)

## Global Constraints

- **JDK 21 显式内联**：每次 mvn/docker exec 前 `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"`（shell 默认可能回落 JDK 8）。
- **version.txt 还原铁律**：每次 mvn 构建产物后立刻 `git checkout -- server/src/main/resources/version.txt`，禁止污染工作树。
- **MYCAT CHANGE 标注**：calcite-core 任何改动必须在改动块上打 `// MYCAT CHANGE (类型表征统一): <一句话原因>` 头注释；无标注不合并。
- **红绿不掺假**：每个 GREEN 前必须 watched fail；未 watched fail 的测试 = 不算测试（承 test-driven-development skill）。
- **不夹带无关修复**：本轮不修 I-AU-3 前端 TLS、I-AU-6 后端 ed25519、I-DEBT-27 case14 blob 值、case611 backtick 文本保真。
- **保护既有绿基线**：`mvn -o test -pl hbt` 现有 91/0/0、`common` 15/0/0、`server` 8/0/0 全绿；本 plan 不新增红、不减少既有绿。
- **重编前停 8066**：e2e 阶段凡需重编 fat jar，先 `Stop-Process -Id <8066 PID>`，编完再起。
- **可自主裁定 vs 停手**：承记忆 123c9135——可逆/本地/实证自证的岔路自主闭环；不可逆 / push / 外部资源变动须停手。

## Review Focus（承 spec §Review Focus，plan 逐面对应有任务）

1. **`ANY_VALUE(varchar_col)` 走 MinMax** 与 datetime 同 implementor——Task 3A/3B 修复必须同时保 varchar（String 已全绿）、datetime（LocalDateTime 现红）、float（若 agg 也踩到 case6052）三类 accumulator 不回归。
2. **`MIN/MAX` 与 `ANY_VALUE` 共享 `MinMaxImplementor`**（`defineAgg(MIN/MAX/ANY_VALUE, MinMaxImplementor.class)`）——Task 3 的测试必须三种聚合都跑，不能只测 ANY_VALUE。
3. **nullable vs non-nullable java 类分支**（`Float.class` vs `float.class`）——Task 2 override + Task 4 结构守卫都要覆盖两分支。
4. **Arrow / 列式 storage 与 Object 行表示分离**（I-DEBT-12 §4 遗留）——Task 4 对账表若某列走 Arrow 表示边界 convert 后一致，标注"边界 convert 后一致"，不误判漏格。
5. **邻域 SQL 形状**：`ORDER BY float_col`、`GROUP BY datetime_col`、`MAX(TIMESTAMPDIFF(...))`——Task 4 结构守卫覆盖三路径，Task 5 e2e 手工补验邻域形状。

---

## Task 1: Q1 关闭——活体复现 case7 + dump Janino 源码

**Files:**
- Create（throwaway，Task 1 提交前删净）: `target/ReproCase7.java`
- Create: `docs/superpowers/specs/2026-10-10-q1-evidence.md`（Q1 关闭证据文件）
- Modify: spec `docs/superpowers/specs/2026-10-10-type-representation-unification-design.md`（附录 B Q1 行由"待填"改为实际结论）

**Interfaces:**
- Consumes: 当前 HEAD `e9e679f2a` 的服务端 fat jar（含 Bug B+D 修复）+ Docker MySQL 容器（`m1-mysql-a:3306` / `mycat57probe:3310` / `mycatmdbprobe:3311`）
- Produces: `docs/superpowers/specs/2026-10-10-q1-evidence.md` 里明确结论：**候选 A 或 B**（Task 3 依此选路）

- [ ] **Step 1: 起 8066 服务端（若已跑则复用；未跑则启）**

Run（PowerShell）：
```powershell
$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
# 若 8066 未跑：java -jar server/target/mycat-server-*.jar &  # 具体启动参数照 docs/11-mycat-quick-start.md
# 若已跑：netstat -ano | Select-String ":8066"  # 记下 PID
```
Expected：8066 端口在听，PID 已记。

- [ ] **Step 2: 打开 Calcite debug 属性单跑 case7**

Run：
```powershell
$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
mvn -o test -pl example '-Dtest=UserCaseTest#case7' '-DargLine=-Dcalcite.debug=true -Dlinq4j.debug=true' 2>&1 | Tee-Object target/case7-repro.log
```
Expected：surefire 报 1 test，Failures 1，Errors 0；日志含 `NoSuchMethodError: greater(...)`；服务端日志 `target/e2e-server*.out` 有 `MycatHashAggregate(ANY_VALUE)` codegen 抛异常。

- [ ] **Step 3: 从服务端日志提取生成的 Janino 源码**

Run：
```powershell
Select-String -Path target/e2e-server*.out -Pattern "greater|lesser|accumulator|ANY_VALUE" -Context 2,4 | Select-Object -First 40
```
Expected：拿到生成的 `Baz$1$1` 或类似类里 `SqlFunctions.greater(<acc 表达式>, <arg 表达式>)` 调用行的**实际类型**——`acc` 侧（LocalDateTime / long）与 `arg` 侧（LocalDateTime / long / java.sql.Timestamp）。

- [ ] **Step 4: 判定选路 A 或 B**

判定规则（写死，不留决策空间）：
- **若 arg 表达式实际是 `long`（primitive），且生成码里能看到 `((Number) input[i]).longValue()` 或 `(long) input[i]` 之类的 toInternal 转换**→ 走 **候选 B**（arg 侧类型走了 `EnumUtils.toInternal` 或 typeFactory 非 Mycat 版本）。
- **若 arg 表达式是 `LocalDateTime`、但 `greater/lesser` 只有 primitive 重载被解析到**→ 走 **候选 A**（`MinMaxImplementor` 硬编 primitive 比较）。
- **若两者都非典型（如 arg=java.sql.Timestamp）**→ **停下报告用户**（spec 未预见的第三路径）。

- [ ] **Step 5: 写 Q1 关闭证据文件**

Create `docs/superpowers/specs/2026-10-10-q1-evidence.md`，结构：

```markdown
# Q1 关闭证据（case7 复现·2026-10-10）

## 观察
- 生成 Janino 源码关键行：<粘 Step 3 的实际片段>
- acc 类型：<LocalDateTime | long | 其他>
- arg 类型：<LocalDateTime | long | java.sql.Timestamp | 其他>

## 判定
候选 <A | B>，因为 <一句话>。

## 关闭
Q1 关闭日期：2026-10-10；Q2 依此结论自动锁定候选 <A | B>。
```

Expected：文件 < 40 行、结论明写。

- [ ] **Step 6: 更新 spec 附录 B**

SearchReplace spec 附录 B 表格 `Q1` 与 `Q2` 行「待填」→实际值。

- [ ] **Step 7: 清理 throwaway + 提交**

Run：
```powershell
Remove-Item target/ReproCase7.java -ErrorAction SilentlyContinue
git checkout -- server/src/main/resources/version.txt 2>$null
git add docs/superpowers/specs/
git commit -m "docs(q1-evidence): 关闭 case7 arg=long 来源问题,锁定候选 A|B"
```
Expected：`git status --short` 干净；HEAD 前进一个提交。

---

## Task 2: 靶 1 · FLOAT override

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java`（L52-100 `getJavaClass` switch）
- Modify / Create: `hbt/src/test/java/io/mycat/calcite/MycatRelDataTypeFactoryTest.java`

**Interfaces:**
- Consumes: `MycatRelDataTypeFactory`（现有类）
- Produces: `Type getJavaClass(RelDataType type)` 对 `SqlTypeName.FLOAT` 返回 `Float.class`（nullable） / `float.class`（non-nullable）——Task 3/4 依此断言

- [ ] **Step 1: 写 RED 测试**

在 `MycatRelDataTypeFactoryTest.java` 追加：
```java
@Test
void getJavaClassNullableFLOATReturnsFloatClass() {
  RelDataType t = factory.createUnmatchedType(float.class, true); // 或既有等价 API，创建 SqlTypeName.FLOAT + nullable
  assertEquals(Float.class, factory.getJavaClass(t));
}

@Test
void getJavaClassNotNullFLOATReturnsPrimitiveFloat() {
  RelDataType t = factory.createUnmatchedType(float.class, false);
  assertEquals(float.class, factory.getJavaClass(t));
}
```
（若 `createUnmatchedType` 签名不同，用现有测试工厂等价方式建 `SqlTypeName.FLOAT` 的 RelDataType——本 plan 不预设 API 细节，实现者按现有 test 模式选最近即可。）

- [ ] **Step 2: 跑 RED**

Run：
```powershell
$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
mvn -o test -pl hbt '-Dtest=MycatRelDataTypeFactoryTest#getJavaClassNullableFLOATReturnsFloatClass+getJavaClassNotNullFLOATReturnsPrimitiveFloat'
```
Expected：两个新例 **FAIL**，`expected:<Float|float> but was:<Double|double>`。

- [ ] **Step 3: 在 `MycatRelDataTypeFactory.getJavaClass` switch 加 `case FLOAT`**

在现有 DATE/TIMESTAMP/TIME 分支同段加：
```java
// MYCAT CHANGE (类型表征统一·靶 1): MySQL FLOAT 列运行时是 java.lang.Float,
// Calcite 默认 getJavaClass(SqlTypeName.FLOAT) 返回 Double.class
// (JavaTypeFactoryImpl L131 `// sic`: SQL 标准 FLOAT≡DOUBLE)。
// 与 java.time 同策略——扳齐 getJavaClass 到 Mycat 运行时真实类型。
case FLOAT:
  return type.isNullable() ? Float.class : float.class;
```

- [ ] **Step 4: 跑 GREEN**

Run：同 Step 2。
Expected：两个新例 **PASS**；`mvn -o test -pl hbt` 全量对齐既有 91/0/0 零回归。

- [ ] **Step 5: e2e 靶心 · case6052 转绿**

Run（前提：Task 2 的 fat jar 尚未编；先 `mvn -o install -pl hbt -Dmaven.test.skip=true`→停旧 8066→重打 server fat jar→起新）：
```powershell
$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
mvn -o install -pl hbt -Dmaven.test.skip=true
mvn -o package -pl server -am -Dmaven.test.skip=true
# 停旧 8066 → 起新 fat jar
mvn -o test -pl example '-Dtest=UserCaseTest#case6052'
```
Expected：surefire `Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 6: 提交**

Run：
```powershell
git checkout -- server/src/main/resources/version.txt
git add hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java hbt/src/test/java/io/mycat/calcite/MycatRelDataTypeFactoryTest.java
git commit -m "hbt: getJavaClass(FLOAT) 返回 Float.class (类型表征统一·靶 1 case6052)"
```

---

## Task 3A: 靶 2 · 候选 A（若 Task 1 定案 A：`MinMaxImplementor` 加 Comparable 分支）

**Files:**
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java`（`MinMaxImplementor.implementNotNullReset/Add` L1840-1866）

**Interfaces:**
- Consumes: Task 2 后的 `MycatRelDataTypeFactory.getJavaClass`
- Produces: `AggImplementor` 在 accumulator 类型为非 primitive `Comparable` 时生成 `compareTo` 语义而非 `SqlFunctions.greater(long,long)`

- [ ] **Step 1: 写 RED 结构守卫（先只 TIMESTAMP）**

Create `hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java` 初版：
```java
@Test
void aggregateAccumulatorAndArgumentAgreeOnJavaClassForTIMESTAMP() {
  RelDataType ts = factory.createUnmatchedType(LocalDateTime.class, true);
  Type acc = factory.getJavaClass(ts); // = LocalDateTime（真相源，已由 I-DEBT-12 override）
  // 触发 MycatHashAggregate.createAccumulatorAdders 生成的 arg 表达式类型
  Type arg = <实现者按 hbt 现有 codegen 测试模式取 arg 侧 java 类>;
  assertEquals(acc, arg, "aggregate acc 与 arg java 类必须一致");
}
```
（arg 取值方式：Task 1 已 dump 出生成 Janino 源码，本测试从 codegen 层重放同样的推导即可；若测试层难以直接触达，改为跑 e2e case7 单例作 RED 亦可，实现者择一。）

- [ ] **Step 2: 跑 RED**

Run：
```powershell
$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
mvn -o test -pl hbt '-Dtest=TypeRepresentationThreePathConsistencyTest#aggregateAccumulatorAndArgumentAgreeOnJavaClassForTIMESTAMP'
```
Expected：**FAIL**，`expected:<LocalDateTime> but was:<long>` 或 e2e case7 `NoSuchMethodError`（视 Step 1 择法）。

- [ ] **Step 3: 修 `MinMaxImplementor`**

在 `RexImpTable.java` 内 `MinMaxImplementor` 类（L1840-1866）改：
```java
// MYCAT CHANGE (类型表征统一·靶 2 候选 A): accumulator 类型为非 primitive Comparable
// (java.time.LocalDateTime/LocalDate/Duration, BigDecimal, String 等) 时,
// 走 compareTo 语义生成；保留 primitive 分支的 SqlFunctions.greater/lesser 路径不变。
static class MinMaxImplementor extends StrictAggImplementor {
  // implementNotNullReset: 若 acc.getType() 非 primitive, inf = null (原 p==null 分支已如此);
  // implementNotNullAdd: 若 acc.getType() 非 primitive && Comparable.class.isAssignableFrom(acc.getType()):
  //   next = Expressions.call(acc, "compareTo", arg) > 0 ? acc : arg   (or reversed for MIN)
  // 否则走原 Expressions.call(BuiltInMethod.GREATER...)
}
```
（实现细节：`compareTo` 表达式用现有 `Expressions.call` + `ConditionalExpression`；具体签名照 `StrictAggImplementor` 上下文风格写。）

- [ ] **Step 4: 跑 GREEN（三聚合 × 三类型矩阵）**

追加参数化测试：`{MIN, MAX, ANY_VALUE} × {TIMESTAMP(LocalDateTime), VARCHAR(String), DOUBLE(double primitive)}`，共 9 例。
Run：`mvn -o test -pl hbt '-Dtest=TypeRepresentationThreePathConsistencyTest'`。
Expected：9/0/0；`mvn -o test -pl hbt` 全量零回归。

- [ ] **Step 5: e2e 靶心 · case7 转绿**

Run：先重编 fat jar → 停旧 8066 → 起新 → `mvn -o test -pl example '-Dtest=UserCaseTest#case7'`。
Expected：1/0/0。

- [ ] **Step 6: 提交**

```powershell
git checkout -- server/src/main/resources/version.txt
git add calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java
git commit -m "calcite-core: MinMaxImplementor java.time Comparable 分支 (类型表征统一·靶 2 case7)"
```

---

## Task 3B: 靶 2 · 候选 B（若 Task 1 定案 B：aggregate 全链 typeFactory 归属 / `EnumUtils.toInternal` 校正）

**Files:**
- Modify 或 `hbt/src/main/java/io/mycat/calcite/physical/MycatHashAggregate.java`（若根在 implement 传参 typeFactory 归属）
- Modify 或 `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/EnumUtils.java`（`toInternal`/`fromInternal` 让 java.time 直通；若根在此）

**Interfaces:**
- Consumes: Task 2 后的 `MycatRelDataTypeFactory`
- Produces: aggregate codegen 全链 java 类推导都走 Mycat 真相源；`EnumUtils.fromInternal(LocalDateTime)=LocalDateTime`（不改）、`EnumUtils.toInternal(TIMESTAMP)=null`（让上层走 `getJavaClass`）

- [ ] **Step 1: 写 RED 测试**

同 Task 3A Step 1。

- [ ] **Step 2: 跑 RED**

同 Task 3A Step 2。

- [ ] **Step 3: 修 `EnumUtils` 或 `MycatHashAggregate`**

依 Task 1 关闭证据锁定：
- **若根在 `EnumUtils.toInternal` 让 TIMESTAMP→long**：在 `calcite-core/.../EnumUtils.java` `toInternal(RelDataType, boolean)` 加 `// MYCAT CHANGE` 注释，改：
```java
case TIMESTAMP:
case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
  // 若 typeFactory 是 MycatRelDataTypeFactory 且其 getJavaClass 返 java.time,
  // 则不返回 long 而返回 null (让上层走 getJavaClass 真相源)
  if (type.getDigest() != null && <typeFactory instanceof MycatRelDataTypeFactory 判定>) return null;
  return type.isNullable() ? Long.class : long.class;
```
（`EnumUtils` 静态方法拿不到 typeFactory，需把 `toInternal` 签名改为带 `JavaTypeFactory` 参数——这属"MYCAT CHANGE 侵入 fork"边界，实施前须 ledger 记 Ruling。）
- **若根在 aggregate implement 传参 typeFactory 不是 Mycat 版本**：修 `MycatHashAggregate.implement` L95-96 处，把 `typeFactory` 显式换成 `implementor.getTypeFactory()` 的 Mycat 版本（应已是；若不是就校正）。

- [ ] **Step 4: 跑 GREEN + 三聚合矩阵 + 零回归**

同 Task 3A Step 4。

- [ ] **Step 5: e2e 靶心 case7 转绿**

同 Task 3A Step 5。

- [ ] **Step 6: 提交**

```powershell
git checkout -- server/src/main/resources/version.txt
git add <改动文件>
git commit -m "calcite-core|hbt: aggregate typeFactory 归属/toInternal 校正 (类型表征统一·靶 2 case7)"
```

---

## Task 4: 全列对账表填完 + hbt 结构守卫扩展

**Files:**
- Modify: `hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java`（把参数化范围从 TIMESTAMP 一列扩到 spec 全列对账表 12 类）
- Modify: spec 附录 A 全列对账表（"待对账"→实际值）
- Modify（若有漏格）：`hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java`（补 override）

**Interfaces:**
- Consumes: Task 2 + Task 3{A|B}
- Produces: 参数化测试通过；spec 附录 A 表格填齐；发现的漏格各自配 override（或挂账 I-DEBT-*）

- [ ] **Step 1: 扩展参数化测试到全 12 类**

矩阵：`MycatDataType.{BOOLEAN,BIT,TINYINT,SHORT,INT,LONG,UNSIGNED_*,DECIMAL,FLOAT,DOUBLE,DATE,DATETIME,TIME,YEAR,CHAR,VARCHAR,BINARY}` × `{projection, aggregate accumulator, aggregate argument, sort}`。
断言：四格 java 类都 = `factory.getJavaClass(type)`。

- [ ] **Step 2: 跑一遍**

Run：`mvn -o test -pl hbt '-Dtest=TypeRepresentationThreePathConsistencyTest'`。
Expected：若除 TIMESTAMP/FLOAT 外无漏格 → 全绿；若有漏格 → 记录失败的 (类型 × 路径) 组合。

- [ ] **Step 3: 漏格逐个处理**

每个漏格按 Task 2 模式：加 override → RED → GREEN。全部通过后再进 Step 4。

- [ ] **Step 4: 更新 spec 附录 A**

把对账表 "待对账" 逐格改成 "一致" / "本轮修" / "挂账 I-DEBT-*"。

- [ ] **Step 5: 提交**

```powershell
git checkout -- server/src/main/resources/version.txt
git add hbt/src/test/java hbt/src/main/java docs/superpowers/specs
git commit -m "hbt: 三路径 java 类一致结构守卫覆盖全列类型 (类型表征统一·Task 4)"
```

---

## Task 5: 全量零回归 + 看板 + spec 附录 B 关闭

**Files:**
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`（变更日志 + I-DEBT-12/13 标记）
- Modify: spec 附录 B（Q3/Q4 关闭记录）

- [ ] **Step 1: 三层测试全跑**

Run（PowerShell）：
```powershell
$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
mvn -o install -pl common,hbt,server -am '-Dmaven.test.skip=true'
mvn -o test -pl common,hbt,executor,server 2>&1 | Tee-Object target/zero-regression.log
git checkout -- server/src/main/resources/version.txt
```
Expected：`common` ≥ 15/0/0（含新增）、`hbt` ≥ 91/0/0（含 Task 2/3/4 新增）、`server` ≥ 8/0/0、`executor` 对齐基准。

- [ ] **Step 2: e2e 全矩阵**

先停旧 8066 → 起新 fat jar → 跑全量 UserCaseTest：
```powershell
mvn -o test -pl example '-Dtest=UserCaseTest' 2>&1 | Tee-Object target/e2e-full.log
```
Expected：现有基线 `45/2F/2E/1S` → **`45/0F/0E/1S` 或更好**（case6052+case7 转绿；残留 case14/case611 独立债务不属本轮）。若 case14/case611 也变红 = 回归 = 立即停下用 systematic-debugging。

- [ ] **Step 3: 更新看板**

SearchReplace 变更日志追加一行"类型表征统一交付 Evidenced-Done"；I-DEBT-12/13 相关行加"三路径不变式已锁·2026-10-10"标记。

- [ ] **Step 4: 填 spec 附录 B Q3/Q4**

Q3 → 对账表实际结果；Q4 → 若有漏格记录范围裁定。

- [ ] **Step 5: 提交**

```powershell
git checkout -- server/src/main/resources/version.txt
git add docs/superpowers/specs/
git commit -m "docs(board+spec): 类型表征统一交付收口 Evidenced-Done (case6052+case7 转绿)"
```

---

## Execution Handoff

**Plan complete**。请审阅此 plan 是否捕捉到你想要的实现路径，并选择执行方式：

- **Subagent-driven**（每任务一个新 subagent 实施 + 一个新 reviewer 审，最后 whole-branch review）——最彻底，成本最高；每任务付一份 fresh context。
- **Native / inline**（在本会话用 executing-plans 自己逐任务 TDD 实施，最后派一个 fresh reviewer 审整支）——最省钱省事；ledger 追进度。**我推荐此项**，因为本 plan 只有 5 个任务、任务间接口耦合度中等（Task 1 输出决定 Task 3 选路）、inline 一份 ledger 全程可追；最后那道 whole-branch review 是最高价值闸门，inline 也能保住。

选哪个？plan 是否捕捉到你想要的？
