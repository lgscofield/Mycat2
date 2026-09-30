# I-DEBT-3 设计：消除 hbt 内 Calcite `SqlValidatorImpl` 影子副本，让 fork 胜出

- 状态：待用户评审
- 看板项：`I-DEBT-3`（L1 拿到一条 SQL 的结果集），依赖 `I-DB-1` B 组的 `sql/validate/SqlValidatorImpl`
- 前置：M2-1（druid 影子清理，已 Evidenced-Done）交付的方法论直接复用
- 日期：2026-10-01
- 用户已裁三点：主方案 P1 / 中等规模分片 SQL 语料 / 待移植差异 >10 条即止损

---

## 0. 一句话目标

删掉 `hbt` 里那份 2018 年代的 `org.apache.calcite.sql.validate.SqlValidatorImpl` 拷贝，让
`calcite-core` fork（`io.mycat:calcite-core:1.42.0-mycat`）成为唯一实现，使 L1 端到端门
`bash ci/verify-l1.sh` 真正拿到 `SELECT 1` 的结果集（G4b 转绿），并且 **fork 保持零 `import io.mycat` 依赖**。

---

## 1. 取证（全部为当前实测，不采信历史记录）

### 1.1 L1 现在卡在哪

| 事实 | 证据（可重跑） |
|---|---|
| 服务端认证与 Connector/J 会话初始化已通过，druid 影子病已关闭 | `bash ci/verify-l1.sh` 后 `grep -c "NoSuchMethodError\|NoClassDefFoundError" target/ci/l1-server.log` → `0` |
| 脚本仍 rc=1，卡点是 `ClassCastException: SelectScope cannot be cast to MeasureScope` | 同上脚本输出；栈帧 `calcite-core/.../sql2rel/SqlToRelConverter.java:735` |
| `SqlToRelConverter:735` 无条件强转 `MeasureScope` | `Read calcite-core/src/main/java/org/apache/calcite/sql2rel/SqlToRelConverter.java` 第 734-735 行 |
| 运行时胜出的 validator 是 hbt 副本，其 `getMeasureScope` 返回 `SelectScope` | `hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java:1177`（返 `scopes.get(select)`，并注 `MYCAT CHANGE: Calcite 1.42 added MEASURE clause; not yet used in hbt`） |
| fork 版同一方法返 1.42 语义 | `calcite-core/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java:1554`（`getScope(select, Clause.MEASURE)`） |

### 1.2 副本清单与血统

| 事实 | 证据 |
|---|---|
| hbt 下 `org/apache/calcite/**` 共 **17** 个副本 | `Get-ChildItem -Recurse hbt/src/main/java/org/apache/calcite -Filter *.java` |
| 其中与 `calcite-core` **同 FQCN** 的 **12** 对（= B 组 11 真遮蔽 + A 组 `mycat/MycatBuiltInMethodImpl`） | 上一条命令逐项 `Test-Path calcite-core/src/main/java/<相对路径>` → True 共 12 |
| hbt 副本 `SqlValidatorImpl` 约 6.7k 行，fork 版约 8.8k 行（行数因末尾换行统计口径 ±1，**不作判据**） | `Get-Content <file>` 计数 / 编辑器 total 行数 |
| 副本只有 **2** 处 `MYCAT CHANGE` 标记，且两处分居两处（不在同一附近） | `Select-String -Pattern "MYCAT CHANGE" hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java` → `:265` 与 `:1178` 两行 |
| `:265` 不是定制，而是给旧拷贝**回填** 1.42 才有的 `callToOperandTypesMap` ⇒ 删副本后由 fork 自带，**无需移植** | 副本 `:266` 与 fork `:281` 同型同可见性（`public final IdentityHashMap<SqlCall, List<RelDataType>>`）；全仓消费方只有 fork 自己 `SqlOperator.java:579`（`git grep -n callToOperandTypesMap -- "*/src/main/java/*"`） |
| `:1178` 正是本轮 L1 卡点的自述：标注“1.42 added MEASURE clause; not yet used in hbt”，`getMeasureScope` 因而返 `SelectScope` | 副本 `:1177-1179` vs fork `:1554`（`getScope(select, Clause.MEASURE)`） |
| 副本里 **Mycat 真正的业务定制只有 1 处逻辑**：`<=>` 强判 BOOLEAN | 全文只 4 处 Mycat 符号命中：`:19`/`:20` import、`:5858` `if (operator == StrictEqualFunction.INSTANCE)`、`:5859` `return MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.BOOLEAN)`，位于 `DeriveTypeVisitor.visit(SqlCall)` |
| fork 版 `SqlValidatorImpl` **零** `MYCAT CHANGE` —— 定制不在 fork 里 | `Select-String -Pattern "MYCAT CHANGE" calcite-core/.../SqlValidatorImpl.java` → 0 行 |
| 副本血统线索指向 Calcite **1.23.0**（2018） | `hbt/src/main/java/io/mycat/calcite/MycatCalciteMySqlNodeVisitor.java:268` 与 `:2114` 两处注释写出 `calcite-core/1.23.0/calcite-core-1.23.0-sources.jar!/.../SqlValidatorImpl.java:1353`。**这是线索不是结论**，由第 4.3 节的三方 diff 定性 |

### 1.3 与 druid 那次的三处不同（决定方法论必须调整）

| 维度 | druid（M2-1） | Calcite（本轮） | 影响 |
|---|---|---|---|
| 唯一真源 | 官方 jar，**不许 fork** | **我们自己维护 fork** | 本轮是"归一到 fork"，不是"归一到第三方 jar" |
| 副本是否引用 Mycat 代码 | 12 文件 `^import io.mycat` = **0** | 12 对副本**每个都引用**（1~2 处） | 不能纯删，定制必须先有家 |
| 被影子咬合的范围 | 只有 druid 自身调用链 | **fork 内 42 个文件命中 `SqlValidatorImpl`（含其自身，即 41 个引用点文件）**：`SqlToRelConverter`、`SqlCaseOperator`、`SqlFilterOperator`、`CalciteSqlValidator`、`ContextSqlValidator`… | 病灶更深：同一模块内部跨 jar ABI 自相矛盾 |

`fork 内引用面`证据：`git grep -l "SqlValidatorImpl" -- calcite-core/src/main/java` → 42 行（其中一行是 `SqlValidatorImpl.java` 自身）。

### 1.4 编译可行性（P1 不打断调用方，逐项实测）

| 调用方 | 用法 | fork 是否满足 |
|---|---|---|
| `hbt/.../io/mycat/calcite/DrdsRunnerHelper.java:216` | 匿名子类 `new SqlValidatorImpl(opTab, catalogReader, typeFactory, config)`，并 `@Override inferUnknownTypes(...)`、内部调 `deriveType(scope, node)` | 构造器同形（fork `:318-322` vs 副本 `:279-283`，四参逐一同）；`inferUnknownTypes` fork `:2403` 存在；`deriveType(SqlValidatorScope, SqlNode)` 两边同为 public 同参（fork `:2284` / 副本 `:1788`） |
| `hbt/.../io/mycat/calcite/MycatSqlValidator.java:24` | `extends SqlValidatorImpl`，只转发构造器、无覆写 | 满足（同一 4 参构造器） |
| `hbt/.../io/mycat/calcite/MycatCalciteMySqlNodeVisitor.java:268/2114` | 仅注释引用 | 无关 |

> 计划第 1 步仍须以真实编译收尾该表：静态同形不能替代 `mvn`。

---

## 2. 裁定与理由（含否决项）

**采纳 P1：把 `<=>` 的类型推导上提到算子自身 → 删 hbt 副本 → fork 1.42 版胜出。**

理由：
1. fork 里已存在 Mycat 自建包 `org.apache.calcite.mycat.MycatSqlDefinedFunction`（`:33` `extends SqlFunction implements CallImplementor`）。
   它的 6 参构造器（`:37-51`）直接接受 `SqlReturnTypeInference`，并原样传给 `super(...)` 的 `:46`；
   `StrictEqualFunction` 当前走的是另一个 3 参构造器（`:53-57` → `:60-66`，inference 由 `GetOperandTypes` 推导）。
   所以只需在算子定义处带上“返回 BOOLEAN”的 inference（或覆盖 `inferReturnType`）。
   Calcite 的 `SqlOperator.deriveType → inferReturnType`（fork `SqlOperator.java:531/559`）正是官方扩展点，
   validator 无需认识任何 Mycat 算子。
2. 于是 1.2 节那**唯一一处真定制**回到它该在的地方（另一处标记是版本回填，删副本后由 fork 自带），**fork 保持零 `import io.mycat`**（实测现状为 0，属可继续同步上游的资产）。
3. 删副本同时消除 1.3 节"fork 内部 41 个引用点与旧拷贝咬合"的结构性矛盾。

**否决 P2**（只改 `getMeasureScope` 返回 `MeasureScope`）：1 行解锁 L1，但影子与跨 jar ABI 矛盾原样留着。M2-1 的账本教训是这类债会以另一种形式再收一次利息（`visitAggreateRest` 孤儿钩子正是这么爆的）。用户已排除。

**否决 P3**（在 fork 的 validator 里加同样的 2 行特判，或把 `MycatCalciteSupport`/`StrictEqualFunction` 下沉进 fork）：为 1 处逻辑把 fork 绑上 Mycat 生态（实测全仓 main 源码中 `MycatCalciteSupport` 被 37 个文件引用、`MycatView` 被 59 个引用，下沉会连锁搬家），永久抬高同步成本。收益/代价严重不对称。

---

## 3. 验收门（每门须当前实证，禁止凭记录标 Done）

| 门 | 判定命令 | 通过条件 |
|---|---|---|
| G1 编译 | `mvn -B install '-Dmaven.test.skip=true' -pl server -am` | BUILD SUCCESS；产物 jar 时间戳新鲜 |
| **G2v 校验黄金基线** | `mvn -B test -pl hbt '-Dtest=CalciteValidatorGoldenTest'` | 语料逐条一致，或每条差异已在本账本裁定 |
| G3 启动 | `java -jar server/target/server-1.22-release-jar-with-dependencies.jar` | 日志出现 `Mycat Vertx server ... started up.` |
| **G4b L1 拿到结果集（本轮存在理由）** | `bash ci/verify-l1.sh` | 脚本 rc=0，探针输出 `L1 OK rows=1`；日志无 `NoSuchMethodError`/`NoClassDefFoundError`/`ClassCastException` |
| G5 回归不新增 | `mvn -B test -pl router,executor,hbt` | 相对开工基准（router 15/0/0、hbt 26/0/23）不新增失败；既有 23 个 error 属另案（`RelBuilder.transientScan` NPE） |
| G6v 影子计数下降 | 同 FQCN 对数 `12 → 11` | 命令见 1.2 节；**只要求本类**，不要求 12 类清零 |
| **G7 fork 纯净性** | `git grep -c "^import io.mycat" -- calcite-core/src/main/java` | 仍为 0（P1 的核心约束，防"顺手污染"） |
| G8 门入 CI | `.github/workflows/pr-validation-main-test.yml` 的 `Verify L1 query path` | **去掉 `continue-on-error: true`**，L1 转为硬门 |

G4b 未过 → 本轮不算交付（与 M2-1 不同：druid 那轮的 G4b 属别的影子系统，本轮的 G4b 就是本设计自己的职责）。

---

## 4. 设计细节

### 4.1 定制上提：算子自带 BOOLEAN 推导

改动落点在 hbt，不在 fork：

- `hbt/src/main/java/io/mycat/calcite/sqlfunction/cmpfunction/StrictEqualFunction.java`
  —— 现走 `super("<=>", scalarFunction, SqlFunctionCategory.SYSTEM)`（即 `MycatSqlDefinedFunction:53-57` 那个重载），
  改而使用带 `SqlReturnTypeInference` 的 6 参构造器（`MycatSqlDefinedFunction:37-51`），
  传入固定返回 BOOLEAN 的 inference（用 `MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.BOOLEAN)`
  保持与现定制同一类型工厂，避免类型系统漂移）。
- 效果：hbt 副本 `SqlValidatorImpl.java:5858-5859` 那个特判成为**不需要存在**的代码，
  官方 `operator.deriveType(...)`（副本 `:5861` / fork `:7445`）自然走通。
- 探针测试先行（TDD RED）：不经 validator 特判，直接对 `<=>` 表达式断言推导类型为 BOOLEAN；
  并在 hbt 副本仍在位时先证该探针在"未改算子"状态下为 RED。

### 4.2 校验行为黄金基线（趁副本仍胜出时采集）

新建 `hbt/src/test/java/io/mycat/calcite/CalciteValidatorGoldenTest.java` +
`hbt/src/test/resources/io/mycat/calcite/calcite-validator-baseline.txt`。

每条语料记录三元组，逐行入档：

1. `推导列类型`：走 Mycat 真实校验入口（`DrdsRunnerHelper.getSqlValidator(...)`）拿到行类型后，逐列渲染 `name=<类型签名>`；
2. `校验错误消息`：`validate()` 抛出的异常**类名 + 完整 message** 入档（沿用 M2-1 的 `!!ERR!! <class>: <msg>` 口径，异常是行为的一部分，不许当噪声吞掉）；
3. 语料原文，顺序即索引，禁止隐式跳行。

语料规模 **40~60 条**，以分片 SQL 为主，必须覆盖：`SELECT` 基本/别名/`*`、`INSERT`/`UPDATE`/`DELETE`、
`GROUP BY` + 聚合、`<=>`（本轮定制）、子查询/`IN`、`JOIN`、`UNION`、参数占位 `?`（`inferUnknownTypes` 路径）、
分片函数/伪列、以及**故意报错**样本（未知列、未知表、非法别名、聚合列不在 GROUP BY）。

采集锚点必须声明来源（M2-1 直接教训复用，防"影子已删导致重采集误报失败"）：

```
-Dgolden.validator=copy|fork    默认 copy
```

- `copy`：断言运行时 validator 来自 `hbt`（`SqlValidatorImpl.class.getProtectionDomain().getCodeSource()` 含 `hbt`）；
- `fork`：断言来自 `calcite-core`；删副本后重采集/对账必须切到此值，并在测试输出里打印真实来源 URL 供复核。

### 4.3 三方血统 diff：把"真定制"与"版本漂移"分开

1081 行"只存在于 hbt 副本"的差异**不许肉眼判定**。做法：

1. 取候选上游源码 jar：`mvn -B dependency:get '-Dartifact=org.apache.calcite:calcite-core:1.23.0:jar:sources'`
   （本地仓库 `D:\Nexus\repository` 现有 calcite-core 版本：1.16.0 / 1.26.0 / 1.36.0 / 1.37.0 / 1.40.0 / 1.42.0，
   1.23.0 需拉取；拉不通则退回用 1.26.0 作最近邻并在账本注明）；
2. 解包后做三方比对：`副本 × 1.23 × 1.42`，分类规则——
   - 只在副本存在、且 1.23/1.42 都没有 → **真 Mycat 定制**（必须逐条裁定去向）；
   - 与 1.23 一致而与 1.42 不同 → 版本漂移（删副本即随 fork 解决）；
   - 1.23/1.42 都有但副本改过 → 定制叠加在旧版上（重点，最易漏）；
3. 分类结果与条数进账本，作为 4.5 节棘轮与第 6 节止损的输入。

### 4.4 删除与孤儿钩子扫描

- 删除对象：`hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java`（仅此一类）。
- 删后立即跑 G1；再扫 hbt 其余 11 个副本 + `io.mycat.calcite` 下 validator 子类中，
  是否存在"当年覆写 hbt 副本方法、删后不再覆写任何 fork 方法"的孤儿钩子：
  判据是方法签名能否在 fork（或上游 1.42）里找到同签名可覆写目标；无 `@Override` 注解的候选逐个列出。
- **扫描器必须先自证有效**（M2-1 教训）：先喂删除前版本跑出已知孤儿数，再喂现工作区要求为 0。

### 4.5 范围棘轮（明写，免成歧义）

- 若删完本类后 G4b 仍被**另一个** Calcite 副本阻断：允许在本 spec 内按同一方法论追加该必要类，
  每类独立走"基线 → 三方 diff → 删除 → 对账 → 孤儿扫描"一轮，逐类记录；
- 不允许顺手处理与 L1 无关的副本、A 组 5 个改包名类、或 1.2 节注释里的历史遗留。
- 每追加一类，G6v 的期望值随之下调 1（12 → 11 → 10…），并在看板 `I-DB-1` 子清单逐项标记。

### 4.6 错误处理与回退

- 待移植差异（4.3 节判为"真定制"且 fork 无对应能力者）**> 10 条** → 停止，回报用户，
  回退到 P2（只修 `getMeasureScope`）先把 L1 解锁，并把已发现的差异清单如实挂账到 `I-DB-1`；
  不允许"边删边猜"把差异留成静默功能丢失。
- 阈值取 10（M2-1 是 15）：本轮换的是 validator，语义面比 SQL 序列化器宽，收紧。
- 任何回退都要在看板上留下"P1 未达成"的具体证据，不得写成"已完成"。

---

## 5. 非目标

- 不做 12 类全量归一（用户已裁：本轮边界为 L1 路径上的必要类集）；
- 不动 `calcite-core` 的 fork 结构、版本、`1.42.0-mycat` 坐标；
- 不修 `hbt` 既有 23 个 `ColumnMappingTest` error（`RelBuilder.transientScan` NPE，另案）；
- 不动 `example` 模块 ~40 个坏测试（`I-DEBT-2`/D13，独立 spec）；
- 不改 MariaDB/认证/TLS 等 M2 其他轨道。

---

## 6. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 旧 1.23 年代副本换成 1.42 fork，校验语义面大范围漂移 | 分片 SQL 校验行为变化、静默功能丢失 | G2v 基线逐条对账（4.2）；4.3 三方 diff 先定性；6 节止损阈值 |
| 未被 `MYCAT CHANGE` 标注的隐性定制（本轮实测仅 2 处标记却有 1 处逻辑 + 1081 行漂移） | 漏移植 | 4.3 分类规则第 3 类专挑"定制叠在旧版上"；G2v 覆盖故意报错样本 |
| 删后匿名子类/子类覆写失配（`DrdsRunnerHelper`、`MycatSqlValidator`） | 编译断裂 | 1.4 已静态同形，计划第 1 步以真实 `mvn` 收尾；断裂则按其暴露的 fork API 缺口重新裁定 |
| 运行时类加载顺序导致"影子是谁"判断错 | 基线采集/对账失真 | `-Dgolden.validator` 锚点 + 打印 `getCodeSource()` 真实 URL（4.2） |
| 范围棘轮被当成"顺手下一次全清"的许可证 | 单轮不可控 | 4.5 明写只允许"阻 L1 的必要类"，其余归 `I-DB-1` 长尾 |

---

## 7. 决策记录

| 决策 | 内容 | 依据 |
|---|---|---|
| 不 fork 之外的第三条路 | 定制上提到算子，fork 零污染 | 1.2/1.3 实测 + `MycatSqlDefinedFunction` 已有 `SqlReturnTypeInference` 入口 |
| 本轮边界 | 只做 L1 路径上的必要类（首个 `SqlValidatorImpl`） | 用户裁定（2026-10-01） |
| 安全网 | 先建校验行为黄金基线再删 | 用户裁定；M2-1 方法论已验证 |
| 语料 | 40~60 条以分片 SQL 为主的中等语料 | 用户裁定 |
| 止损 | 待移植 >10 条即停并回退 P2 | 用户裁定 |
| 门拆分先例 | M2-1 的 G4 已拆 G4a/G4b（spec `2026-09-30-druid-shadow-removal-design.md` 第 3 节）；本轮 G4b 归本设计职责 | commit 312128318 |

---

## 8. 交付后动作

1. `Verify L1 query path` 摘掉 `continue-on-error: true`（G8），L1 成为硬门；
2. 看板：`I-DEBT-3` → `Evidenced-Done`（附 G1/G2v/G4b/G5/G6v/G7 可重跑命令）；
   `I-DB-1` B 组子清单勾掉已完成类并把剩余同 FQCN 对数更新；
3. 账本 `.superpowers/sdd/2026-10-01-calcite-validator-shadow/progress.md` 记每步实测（含 RED/GREEN 原始输出摘要）；
4. 若触发 4.5 棘轮追加类，每类单列一段，禁止合并叙述。
