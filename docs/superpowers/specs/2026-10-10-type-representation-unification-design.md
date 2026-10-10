# 类型表征统一（三路径 java 类一致不变式）设计

> **状态**：设计已获用户口头批准（2026-10-10），进入 spec review 前置阶段。
> **范围**：让 Mycat 侧 `MycatRelDataTypeFactory.getJavaClass` 的 java 类决策在所有 codegen 路径（投影 / 聚合 / 排序）被一致消费，根治 class-cast / NoSuchMethod 类回归。
> **关联**：延续 [I-DEBT-12 java.time 方向 A](./2026-10-07-i-debt-12-temporal-java-time-representation-design.md) 的策略——`getJavaClass` 是 Mycat 运行时 java 类型的**唯一真相源**；本轮把这一真相源从"仅投影生效"扩到"全 codegen 路径生效"。

**Goal:** 用三路径 java 类型一致不变式，一次性收敛 case6052（FLOAT）与 case7（ANY_VALUE datetime）两靶，并建立结构守卫防同类债复发。

**Architecture:** 靶 1 在 `MycatRelDataTypeFactory.getJavaClass` 补 `FLOAT → Float.class` override（与 java.time 同构，一行）；靶 2 让 aggregate 的 accumulator 状态类型与参数读取路径共用同一套 java 类推导（MycatRelDataTypeFactory），并对非 primitive Comparable 走 `compareTo` 语义。改动集中在 `hbt/MycatRelDataTypeFactory.java` + `calcite-core` 的 `MinMaxImplementor` 或 `StrictAggImplementor`（依实现期 Q1 复现结果二选一）。

**Tech Stack:** JDK 21 (LibericaJDK-21-Full)、Calcite 1.42 fork（calcite-core 模块）、Janino codegen、JUnit 5。

**Spec:** 本文件即设计 spec；实现计划由 writing-plans 后续生成。

---

## 全局约束（Global Constraints）

- **`getJavaClass` 唯一真相源**：Mycat 声明的每个 `RelDataType` 对应的运行时 java 类，就是 `MycatRelDataTypeFactory.getJavaClass` 返回的类；任何 codegen 路径产出的表达式类型必须与之兼容。
- **三路径一致**：projection（裸列）、aggregate accumulator/comparator（ANY_VALUE/MIN/MAX）、sort comparator（ORDER BY）三条 codegen 路径对同一列必须给出一致的 java 类。
- **零回归**：每次改动跑 `mvn test -pl hbt,executor,server` + e2e 全矩阵对齐既有绿基线；每次 mvn 后 `git checkout -- server/src/main/resources/version.txt` 还原。
- **JDK 21 显式内联**：每次构建命令前 `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"`。
- **不擅自扩范围**：发现新漏格按"入对账表 + 加 override"处理，不夹带无关重构。
- **不碰正交缺口**：本轮不处理前端 TLS、后端 ed25519 多驱动（I-AU-6），那两枚是协议/驱动层缺口，与类型表征正交。

---

## 问题定性（两层根，别再混）

### 靶 1：case6052 · MySQL FLOAT 列 ClassCastException（静态完全坐实）

**Evidence**（当前 HEAD=c5482059e）：

- 表 schema 侧：MySQL `float` 列 → `MycatDataType.FLOAT`、`klass=Float.class`（[MycatRelDataType.java L110-120](../../../common/src/main/java/io/mycat/beans/mycat/MycatRelDataType.java#L110-L120)），映射到 Calcite `SqlTypeName.FLOAT`。
- 运行时行值：JDBC `ResultSet.getFloat` → `java.lang.Float`（[MycatDataType.java L54-95](../../../common/src/main/java/io/mycat/beans/mycat/MycatDataType.java#L54-L95) `case FLOAT: return Float.class`）。
- Codegen 侧：`JavaTypeFactoryImpl.getJavaClass(SqlTypeName.FLOAT)` 返回 **`Double.class`**（[JavaTypeFactoryImpl.java L98-161](../../../calcite-core/src/main/java/org/apache/calcite/jdbc/JavaTypeFactoryImpl.java#L98-L161) 带 `// sic` 注释：SQL 标准 FLOAT≡DOUBLE）。
- **MycatRelDataTypeFactory.getJavaClass override 只接管了 DATE/TIME/TIMESTAMP/OTHER**（[MycatRelDataTypeFactory.java L52-100](../../../hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java#L52-L100)），FLOAT 落在 `super.getJavaClass(type)` → Double。
- 结果：codegen 发 `(Double) Object[槽位]`，运行时槽位是 `Float` → `ClassCastException`。

**性质**：纯粹是 Mycat 真相源少了一格 override。与 java.time 完全同构、修法完全同构。

### 靶 2：case7 · ANY_VALUE(datetime) NoSuchMethodError `greater(LocalDateTime, long)`

**Evidence**（当前 HEAD）：

- `ANY_VALUE` 归 `defineAgg(ANY_VALUE, MinMaxImplementor.class)`（[RexImpTable.java L1322](../../../calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java#L1322)）。
- `StrictAggImplementor.getNotNullState`：`info.returnType()` → `typeFactory.getJavaClass(TIMESTAMP)` → **`LocalDateTime`**（走 Mycat 真相源，正确）。
- `MinMaxImplementor.implementNotNullAdd`：生成 `SqlFunctions.greater(acc, unbox(arg))`；`acc=LocalDateTime`。
- 报错实跑证据显示 **`arg=long`** → `NoSuchMethodError: SqlFunctions.greater(LocalDateTime, long)`。
- 静态追 `PhysTypeImpl` L76 输入行字段 java 类推导出 `arg=LocalDateTime`——与实跑报错矛盾（详见「实现期必答问题」Q1）。

**性质**：Mycat 的 java.time 真相源与 aggregate codegen 的另一条独立类型推导路径打架。

**统一诊断**：两靶共享同一个根——**"Mycat `getJavaClass` 只在裸列投影被完整消费，进 aggregate/sort codegen 就有第二条类型推导路径与它冲突"**。

---

## 不变式（本轮建立的架构性约束）

**INV-TRU-1（三路径 java 类一致不变式）**：
> 对 Mycat 支持的任意列 `RelDataType`，`MycatRelDataTypeFactory.getJavaClass(type)` 返回的 java 类，必须同时是下列三条 codegen 路径产出的表达式类型：
> ① projection 的 `PhysType.fieldReference` 目标类
> ② aggregate accumulator 状态类 + aggregate argument 读取类
> ③ sort comparator 的 `fieldReference` 目标类

违反即红。这是"系统性全覆盖"的可执行定义。

**INV-TRU-2（`getJavaClass` 是真相源，不是启发式）**：
> 任何 codegen 路径想要 java 类，只能问 `getJavaClass`；不允许自行从 `SqlTypeName`/`Primitive`/`toInternal` 反推一个与 Mycat 决策不一致的类型。若必须走内部表示（如 Arrow 列式），在表示边界做显式 convert。

---

## 解法方向

### 靶 1 FLOAT（一行 override）

在 `hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java` 的 `getJavaClass` switch 补：

```java
// MYCAT CHANGE (类型表征统一): MySQL FLOAT 列运行时是 java.lang.Float，
// 而 Calcite 默认 getJavaClass(SqlTypeName.FLOAT) 返回 Double.class
// （JavaTypeFactoryImpl L131 `// sic`：SQL 标准 FLOAT≡DOUBLE）。
// 与 java.time 同策略——扳齐 getJavaClass 到 Mycat 运行时真实类型。
case FLOAT:
  return type.isNullable() ? Float.class : float.class;
```

不动 `SqlTypeName` 映射（保持 MySQL `FLOAT` → `SqlTypeName.FLOAT` 既有决策），改动面最小。

### 靶 2 aggregate java.time（依 Q1 复现结果二选一）

**候选路径 A（若根在 `SqlFunctions.greater` 只识 primitive）**：给 `calcite-core` 的 `MinMaxImplementor` 加分支——当 accumulator 类型是非 primitive 的 `Comparable`（LocalDateTime/LocalDate/Duration/BigDecimal 等），生成 `compareTo` 语义而非调 `SqlFunctions.greater`。改动集中在 `implementNotNullReset` 与 `implementNotNullAdd` 两处。

**候选路径 B（若根在 `toInternal` 或 typeFactory 归属）**：让 aggregate 全链（`aggStateTypes`、`accPhysType`、`inputPhysType`、`rowTranslator`）共用同一个 MycatRelDataTypeFactory，且 `EnumUtils.fromInternal` / `toInternal` 对 java.time 类型返回 null（不改，让 codegen 保持 LocalDateTime）。

**决策时点**：实现阶段的第一步是活体复现 case7（起服务端跑用例 → 打开 Calcite verbose → dump 生成的 Janino 源码），用证据关闭 Q1，再选 (A) 或 (B)。**spec 不预设哪条对——预设就是冒进。**

### 全列对账表（INV-TRU-1 的应用面）

在实现阶段，按下表逐一核对每一列类型在三条 codegen 路径上的 java 类是否与 `getJavaClass` 一致；发现不一致即加 override 或修 codegen，并把结论固化进 spec 附录：

| Mycat 列类型 | SqlTypeName | `getJavaClass`（真相源） | 投影 | 聚合 accumulator | 聚合 arg | 排序 | 状态 |
|---|---|---|---|---|---|---|---|
| BIT/TINYINT/SHORT/INT | BOOLEAN/TINYINT/SMALLINT/INTEGER | boolean/Byte/Short/Integer | 一致 | 待对账 | 待对账 | 待对账 | 待对账 |
| UNSIGNED_* / LONG | 同名 | Long/BigInteger/Long | 一致 | 待对账 | 待对账 | 待对账 | 待对账 |
| DECIMAL | DECIMAL | BigDecimal | 一致 | 待对账 | 待对账 | 待对账 | 待对账 |
| **FLOAT** | FLOAT | **Float（本轮加 override）** | **不一致→本轮修** | 待对账 | 待对账 | 待对账 | **本轮修** |
| DOUBLE | DOUBLE | Double | 一致 | 待对账 | 待对账 | 待对账 | 待对账 |
| DATE | DATE | LocalDate | 一致（I-DEBT-12） | 待对账 | 待对账 | 待对账 | 待对账 |
| DATETIME/TIMESTAMP | TIMESTAMP | LocalDateTime | 一致 | **待对账→case7 即此靶** | 待对账 | 待对账 | **本轮修** |
| TIME | TIME | Duration | 一致 | 待对账 | 待对账 | 待对账 | 待对账 |
| YEAR | SMALLINT | Short/Integer | 待对账 | 待对账 | 待对账 | 待对账 | 待对账 |
| CHAR/VARCHAR | CHAR/VARCHAR | String | 一致 | 一致 | 一致 | 一致 | 一致 |
| BINARY/BLOB | BINARY | byte[] | 一致（case14 挂 I-DEBT-27，值域另论） | 一致 | 一致 | 一致 | 本轮不扩 |

对账完成 → 表格填"一致"或标"本轮修/挂账"。任何"不一致"若不影响 case6052/case7 但属于同类问题，加 override；若影响范围超出三路径（如二进制协议），挂账到 I-DEBT-* 不夹带。

---

## 实现期必答问题（Q1-Q5，硬前置，不拍脑袋）

| 编号 | 疑点 | 关闭方法 | 关闭时点 |
|---|---|---|---|
| **Q1** | case7 `arg=long` 究竟来自哪：aggregate 输入的 typeFactory 非 MycatRelDataTypeFactory，还是 `RexToLixTranslator.forAggregation` 走了一次 `toInternal`？静态追 `PhysTypeImpl` L76 结论（arg=LocalDateTime）与实跑报错（arg=long）矛盾。 | 起服务端 → 跑 case7 → 打开 Calcite `Linq4jEXPANDSelect`/verbose → dump 生成的 Janino 源码看谁做的类型决策 | 实现阶段第 1 步（先诊断，后改码） |
| **Q2** | 靶 2 落点是候选 A（`MinMaxImplementor` 加分支）还是候选 B（全链共用 MycatRelDataTypeFactory + `EnumUtils.toInternal` 返回 null）？ | 依 Q1 复现证据决定 | Q1 关闭同轮 |
| **Q3** | FLOAT 外，是否还有别的 SqlTypeName 存在"Calcite 默认 java 类 ≠ Mycat 运行时 java 类"漏格？（可能候选：`YEAR`、`UNSIGNED_*`、`DECIMAL` 精度边界、`REAL` vs `FLOAT`） | 上表「全列对账」逐一刻，或参数化遍历全 `SqlTypeName` × 三路径 | 实现阶段与 Q1 并行 |
| **Q4** | Q3 若发现多个漏格（不止 FLOAT），本轮范围是否扩至全部？ | 逐个入对账表 + 各自加 override；**不扩到**与三路径不变式无关的重构（如协议层、二进制值域） | Q3 出结论同轮 |
| **Q5** | 前端 TLS、后端 ed25519 是否本轮处理？ | **否**，正交缺口，各自债务行（I-AU-3 TLS / I-AU-6 ed25519）挂账不动 | 设计阶段已关（2026-10-10） |

---

## 组件与文件

- **修改**：
  - `hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java`（靶 1 override）
  - `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java`（靶 2 候选 A：`MinMaxImplementor` 加 Comparable 分支）——或——
  - `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/EnumUtils.java`（靶 2 候选 B：`toInternal`/`fromInternal` 让 java.time 直通）
- **新增测试**（hbt）：
  - `hbt/src/test/java/io/mycat/calcite/MycatRelDataTypeFactoryTest.java` 增补 `getJavaClassFLOATReturnsFloat`
  - `hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java`（**新，结构守卫**）：参数化遍历全列类型 × 三路径，断言 java 类一致
- **e2e**：case6052、case7 转绿

---

## 数据流（修复前 vs 修复后）

**修复前**：
```
MySQL float 列 → MycatDataType.FLOAT (Class=Float)
                     ↓ 运行时行值 Object[]=Float
                     ↓
SqlTypeName.FLOAT → getJavaClass (JavaTypeFactoryImpl 默认) → Double
                     ↓ codegen 强转 (Double)
                     ↓
                     ClassCastException

ANY_VALUE(LocalDateTime 列)
   acc state ← getJavaClass(TIMESTAMP)=LocalDateTime
   arg       ← 某路径推导=long
                     ↓
   SqlFunctions.greater(LocalDateTime, long) → NoSuchMethodError
```

**修复后**：
```
SqlTypeName.FLOAT → MycatRelDataTypeFactory.getJavaClass → Float
                     ↓ codegen 强转 (Float)
                     ↓
                     ✓

ANY_VALUE(LocalDateTime 列)
   acc state ← getJavaClass(TIMESTAMP)=LocalDateTime
   arg       ← 同路径 getJavaClass=LocalDateTime
                     ↓
   生成的比较走 Comparable.compareTo（或 SqlFunctions 泛型版）
                     ↓
                     ✓
```

---

## 错误处理

- **不新增异常类型**：靶 1/2 修复的都是编译期/class-load 期错配，运行期不引入新抛点。
- **诊断增强（可选，实现阶段视需要）**：若 Q1 复现困难，`MycatRelDataTypeFactory.getJavaClass` 临时打 debug 日志（受 `CalciteSystemProperty.DEBUG` 门），记录 SqlTypeName × 返回 java 类，帮助定位。定位完删掉，不入产品码。

---

## 测试策略（TDD，红→绿）

1. **RED-1**：hbt 单元 `MycatRelDataTypeFactoryTest.getJavaClassFLOATReturnsFloat`——`getJavaClass(relDataTypeOfFLOAT)` 应返回 `Float.class`。跑，看它按当前实现失败（返回 `Double.class`）。
2. **GREEN-1**：加 `case FLOAT → Float.class` override。重跑 RED-1 变绿。
3. **RED-2**：活体 e2e `case6052`（float_test.value）——现红 `ClassCastException: Float → Double`， GREEN-1 落盘后重跑，转绿。
4. **RED-3**：hbt 结构守卫 `TypeRepresentationThreePathConsistencyTest` 参数化遍历，对 `SqlTypeName.TIMESTAMP`（case7 类）在三路径上的 java 类做一致性断言。跑，看它按当前 aggregate codegen 结果失败。
5. **Q1/Q2 关闭**：实现阶段活体复现 case7，dump Janino 源码，锁定 arg=long 来源，选 (A) 或 (B)。
6. **GREEN-2**：按 Q1 结论落最小改动。
7. **RED-4**：hbt `TypeRepresentationThreePathConsistencyTest` 对 TIMESTAMP（case7 类）现在通过。
8. **GREEN-3**：e2e `case7`（`ANY_VALUE(datetime_col)`）转绿。
9. **全列对账 Q3/Q4**：对账表填完，发现的漏格各自补 override 或修 codegen（保持 TDD：先红后绿）。
10. **全量零回归**：`mvn test -pl hbt,executor,server` 对齐既有绿基线；e2e 全矩阵不新增红；每次 mvn 后 `git checkout -- server/src/main/resources/version.txt`。

**Every test 都被 watch fail before GREEN.** 无测试的改动 = 未验证的改动 = 不算完成。

---

## 影响面 / 风险 / 回滚

- **改动集中**：靶 1 hbt 一行 override，零风险；靶 2 依 Q1 结果改 `calcite-core/RexImpTable.MinMaxImplementor` 或 `EnumUtils.toInternal`，改动集中在 `// MYCAT CHANGE` 标注区，回滚即 revert。
- **blast radius**：不影响其他 aggregate（`COUNT/SUM/AVG` 走 primitive 表示不变）、不影响 `Sort` 比较器（case1 已全绿，本轮只补结构守卫锁死既有一致性）、不影响协议层。
- **零回归护栏**：三路径一致性参数化测试即结构性护栏；同类新增列类型（如 JSON、GEOMETRY）漏格即 CI 红。
- **PARK 清单不扩**：靶 2 若发现必须改 `toInternal` 让 java.time 全链直通，需评估是否触碰 I-DEBT-12 §4 影响的"storage 端"（Arrow / 列式）——那是 PARK 范畴，不属本轮，遇到即挂账 I-DEBT-* 不动。

---

## 与既有决策/债务的关系

- **承 I-DEBT-12 方向 A**（`getJavaClass` 是 java.time 真相源）——本设计把它从"仅时间类型"扩到"全列类型 + 全 codegen 路径"，是既有决策的自然延伸，不冲突。
- **不动 I-DEBT-1 / 3 / 4 / 18**（影子类清理），本设计与影子归一正交。
- **不碰 I-AU-3（前端 TLS）、I-AU-6（后端 ed25519 多驱动）**——协议/驱动层，与类型表征正交，各自债务行独立推进。
- **本轮不合并 I-DEBT-27（case14 二进制协议）**：那条是 BLOB 值协议层，属另一类问题。
- **影子存量清单（Q 记录）**：本会话磁盘实测 `hbt/.../org/apache/calcite/` 共 10 文件，其中 5 个真影子孪生（`RelJson`、`RelJsonWriter`、`RelMdMaxRowCount`、`RelMdRowCount`、`mycat/MycatBuiltInMethodImpl`），5 个 Mycat 自有包名借道（`MycatContext`、`MycatHepJoinClustering`、`NewMycatDataContext`、`RxBuiltInMethod/Impl`）。归 I-DB-1 记录维护，本设计不重开影子清理。

---

## Review Focus（e2e/单测不覆盖但可能咬人的失败模式）

1. **`ANY_VALUE(varchar_col)` 走 MinMax 时的字符串比较**：与 datetime 同 ref 路径但 arg/acc 都是 String（非 primitive），修复 (A) 加 Comparable 分支要确保 String 走 `compareTo`（区分 collation）不回归到 `greater(long,long)` 硬编；(B) 修 `toInternal` 不影响 String 分支。当前 case 已全绿的 varchar ANY_VALUE 若在修复中变红即此靶。
2. **`MIN/MAX` 与 `ANY_VALUE` 共享 MinMaxImplementor**（`defineAgg(MIN/MAX/ANY_VALUE, MinMaxImplementor.class)`）：修复必须同时保三者的 primitive 与 Comparable 双支路，不能只测 ANY_VALUE。
3. **nullable 与 non-nullable 的 java 类分支**：`Float.class` vs `float.class`、`LocalDateTime` vs（若有）primitive-time——加 override 时要处理 `type.isNullable()` 分支，别只测 nullable 路径。
4. **Arrow / 列式 storage 与 Object 行的表示分离**（I-DEBT-12 §4 遗留）：三路径不变式只在 Object 行 codegen 上强制；若某路径走 Arrow primitive（如 `Float4Vector`）在表示边界做了 convert，对账表要标注"表示边界 convert 后一致"，不能误判为漏格。
5. **e2e 未覆盖但可能触发同类 bug 的 SQL 形状**：`ORDER BY float_col`、`GROUP BY datetime_col`、`SELECT MAX(TIMESTAMPDIFF(...))`——修复后需追加或至少手工跑一次，确认三路径不变式在这些邻域形状也成立。

---

## 完成契约（Definition of Done）

本轮完成 = 全部满足：

- [ ] 靶 1 case6052 转绿（e2e 实跑证据）
- [ ] 靶 2 case7 转绿（e2e 实跑证据）
- [ ] Q1 有明确关闭证据（Janino 源码或 typeFactory 归属结论），Q2 依 Q1 结果选路并落地
- [ ] 全列对账表填完且无遗留"不一致"（或已挂账 I-DEBT-*）
- [ ] hbt 结构守卫 `TypeRepresentationThreePathConsistencyTest` 全绿
- [ ] `mvn test -pl hbt,executor,server` 对齐既有绿基线（零新增红）
- [ ] e2e 全矩阵不新增红
- [ ] 每次 mvn 后 `version.txt` 已还原
- [ ] spec 附录「全列对账表」更新到最新实际结果
- [ ] 看板 I-DEBT-12 / I-DEBT-13（时间类型表征）行加"三路径不变式已锁"标记

---

## 决策与批准记录

- **2026-10-10**：用户选定方案 A（"扩展 `getJavaClass` override + 补全 calcite-core 的 aggregate/comparator 层 java.time 感知"），并显式要求"凡有疑问、不清楚、遗留的尽量记录"（Q1-Q5 落表）。
- **2026-10-10**：brainstorming 设计节呈现并获用户口头"ok 继续"→ 授权写本 spec。
- **待用户 review 本 spec → writing-plans 生成实现计划 → 用户选执行模式（inline / subagent / native）→ 按 executing-plans 走 TDD 落码**。

---

## 附录 A：全列对账表（实现阶段填）

见「解法方向 · 全列对账表」小节，实现阶段 Q3 关闭后回填实际一致状态。

## 附录 B：Q1-Q5 关闭记录（实现阶段填）

| 编号 | 关闭日期 | 关闭证据 | 决策 |
|---|---|---|---|
| Q1 | 2026-10-10 | 服务端 `target/e2e-server2.out` Suppressed 栈：`greater[class java.time.LocalDateTime, long]` @ `RexImpTable$MinMaxImplementor.implementNotNullAdd:1863`；acc=LocalDateTime（Mycat 真相源正确），arg=long（走了 `toInternal` 或 typeFactory 归属错）；详见 [Q1 关闭证据](./2026-10-10-q1-evidence.md) | **候选 B**（arg 侧根在 `EnumUtils.toInternal`/typeFactory 归属，非 `SqlFunctions.greater` 缺重载） |
| Q2 | 2026-10-10 | 由 Q1 自动锁定 | **候选 B**：`EnumUtils.toInternal`/aggregate 全链 typeFactory 归属校正（Task 3B 起手第一步先收窄 B1/B2） |
| Q3 | 待填 | 待填 | 待填（漏格清单） |
| Q4 | 待填 | 待填 | 待填（范围裁定） |
| Q5 | 2026-10-10 | 设计阶段裁定 | 否，正交缺口，各自债务行挂账 |
