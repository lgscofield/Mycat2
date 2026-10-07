# Calcite 同 FQCN 影子副本全量归位删除（I-DEBT-3 续 · 剩余 9 类）设计

**Goal:** 在严守"不编辑 calcite-core fork 源码"的前提下，把 hbt 内剩余 9 个 `org.apache.calcite.*` 同 FQCN 影子副本的 Mycat 钩子逐一归位到 fork 已有扩展点或 Mycat 自有类，删除影子让 fork 1.42 干净胜出，从而消解 fork 污染、并解封被这些影子崩溃挡住的 join/产品回归裁判。

**Architecture:** 每个影子都挂着 1–2 个 `io.mycat` 钩子（fork 必须保持零 io.mycat，故不能整删了事）。归位手法 = 把钩子搬到 Calcite 1.42 已提供的扩展点（元数据 provider 链 / 算子实现表 / RexExecutor 简化器）或 Mycat 自有类，删影子、让 fork 泛型实现接管其余、Mycat 侧专供 Mycat 类型。沿用本仓两次已验证套路：I-DEBT-3 把 `<=>` 从编辑影子改为上提算子 inference；I-DEBT-4 把 DataContext 定制抽到 MycatBindable 自有契约再删影子。

**Tech Stack:** JDK 21、Calcite 1.42 fork（calcite-core 模块）、Maven、JUnit 5、Docker m1-mysql-a/b 后端。

**Spec 前案（本设计的模板与依据，务必同读）：**
- `docs/superpowers/specs/2026-10-01-calcite-validator-shadow-removal-design.md`（SqlValidatorImpl 影子删除：三方血统 diff→移植→删→黄金基线回归）
- `docs/superpowers/specs/2026-10-01-exec-bindable-contract-design.md`（DataContext→MycatBindable 钩子归位删除，即"钩子外移"范式）
- `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md` §131 B 组（原 11 减 2 剩 9 的逐类三方合并清单）

---

## Global Constraints（每个任务的隐含要求，逐条来自既有铁律）

- **不编辑 calcite-core fork 源码**：`calcite-core/**` 编辑禁；仅允许重建编译。所有归位只能在 hbt/server 可动侧完成，用 fork 既有扩展点。
- **fork 保持零 `import io.mycat`**：验收门 `git grep -l "^import io.mycat" calcite-core/src/main/java` → 恒 0（G7）。
- **join 全量回归 = 唯一裁判**：任何删影子后 join/查询计划形状的最终背书，以 example 全量 + `io.mycat.sql` 套件为准。
- **单元套件不得回归**：`mvn -o -pl router,executor,hbt test` → router 15/0/0、hbt 36/0/0、executor 0 必须每类删后仍全绿。
- **不信任既有记录，凭当前实测**：每个"已归位/已删除/行为不变"结论必须由当前代码 file:line + 实跑测试输出独立复核。
- **TDD + 频繁提交**：每类一个红→绿循环，改一处跑一次；每类一个提交。
- **测毕立即 kill proxy + 停 mvn**：集成套件跑完立刻关 8066 进程，防失控烧机。
- **后端仅用 Docker m1-mysql-a/b**，本机 MySQL 服务禁动。

---

## 背景（当前实测证据，非记录）

① 全量回归清算（2026-10-07）实证 example = 479 tests/7 fail/93 err/30 skip，其中 **NoSuchMethodError ×8** 全源于 hbt 内 9 个 `org.apache.calcite.*` 影子副本的方法签名**落后于 fork 1.42 的调用方**（fork 的类调 `RexToLixTranslator.translateCast(5 参)`、`RelMdUtil.isRelDefinitelyEmpty`、`SqlBasicCall.<init>(…)`，旧影子缺签名 → 运行时炸）。9 类实测（`Glob hbt/**/org/apache/**/*.java`）且每个都 `import io.mycat`（1–2 处），故非"过时整拷贝直接删"，每类须先归位钩子。

**9 类耦合现场（实测行号）：**

| 家族 | 类（行数） | io.mycat 钩子现场 | 归位目标（fork 扩展点 / Mycat 自有） |
|---|---|---|---|
| **MycatView 元数据** | RelMdCollation(554) | `if(rel instanceof MycatView){ isMergeSort(); getRelNode(); getTraitSet().getCollation() }` | 注册 Mycat `RelMetadataProvider`，MycatView 专供 collation；provider 链首个命中即胜 |
| | RelMdUniqueKeys(337) | 同族 `instanceof MycatView` | 同上 provider |
| | RelJsonWriter(149) | 序列化 `MycatView` | Mycat 自有 RelJsonWriter 子类 / 注册类型适配器 |
| **SPM/ParamHolder** | RelMdRowCount(246) | `getRowCount(Sort)`：`LIMIT ?` 从 `ParamHolder.CURRENT_THREAD_LOCAL` 取实参估行 | Mycat provider 抢在 fork RelMdRowCount 前处理 Sort-with-dynamic-param |
| | RelMdMaxRowCount(237) | 同 ParamHolder | 同上 |
| | RelMdUtil(982) | `MycatCalciteSupport`、`ParamHolder` | 拆到 Mycat 自有 util / provider |
| **codegen** | RexLiteral(1386) | 值类方法内 `ParamHolder.CURRENT_THREAD_LOCAL.get().getParams().get(index)`（L1348）；`MycatRexExecutor` | **无天然扩展点**（值类，不能包）；先查 fork 是否已在别处经 RexExecutor 解参使该钩子变冗余 |
| | RexToLixTranslator(3027) | `MycatBindable.ROOT`(L178/L2408) 换 root 表达式；`StrictEqualFunction.INSTANCE`(L2638) `<=>` codegen | `<=>` 经 codegen 入口传入自定义算子表承接；`MycatBindable.ROOT` 若无注入口即撞闸门 |

**两类硬骨头预告**：`RexLiteral`（核心值类）与 `RexToLixTranslator` 的 `MycatBindable.ROOT`（codegen root 无注入口时）最可能撞可行性闸门——上一会话我是"给 RexToLixTranslator 补构造而非删"即此信号的体现。

---

## 核心机制：可行性闸门（每类第一步，先判后动）

对每个影子，**动手前先回答**："fork 1.42 是否有扩展点能承接该钩子，使删影子后行为等价？"

- **能** → 归位 → 删 → 过回归门 → 提交（Evidenced-Done）。
- **不能 / 不确定** → **停，不擅动 fork**，按**默认策略**处理：把该类登记为"文档化保留影子"（记录钩子现场、为何无 fork 钩子、保留代价），继续下一类。全 9 类跑完产出"真删不掉清单"交人类逐类裁定（是否破例给 fork 加最小扩展点）。
- 判"能"的凭据必须是**当前实测**（找到 fork 里对应的注册/入口 file:line，且删后测试绿），不接受"应该有"的推断。

**默认不松绑编辑 fork**：破例加 fork 钩子是难回退决定（一旦 fork 带 Mycat 味即永久锁死升级，正是本项目要消解的），故仅在人类对具体类显式批准后发生，且带独立评审。

---

## 排序与批次（P1 逐类 · 崩溃优先 · 按家族聚类）

1. **先啃两个实测确认的 NoSuchMethodError 崩溃影子**（`RexToLixTranslator` translateCast → `RelMdUtil` isRelDefinitelyEmpty）：删这俩最先解封 join/产品裁判（`SqlBasicCall.<init>` 报错为旧影子的连带崩，非独立影子，随本体删后自消）。一类过门再下一类。
2. **SPM/ParamHolder 族**（`RexLiteral`→`RelMdRowCount`→`RelMdMaxRowCount`）：RexLiteral 是核心值类、最硬，紧随两个崩溃类之后做（此时 provider/算子表机制已铺垫）。
3. **MycatView 元数据族**（`RelMdCollation`→`RelMdUniqueKeys`）：同 provider 机制，紧邻省一次设计。
4. **RelJson 族收尾**（`RelJsonWriter`→`RelJson`）：当前不崩、序列化面、风险最低最后做。

每类一个完整红→绿→回归→提交循环；一类不过门不进下一类（若撞闸门则按默认挂账、继续下一类）。

---

## 测试与回归门（每类三件套）

- **G-target 定向基线**：该类承载的行为建黄金/行为对账（参照 `CalciteValidatorGoldenTest` 48 语料范式；codegen 类用 `<=>`/参数化 LIMIT/单列标量投影等具体语料钉死）。红（影子在、签名旧崩）→ 绿（归位删后 fork 胜出且行为等价）。
- **G-unit 单元不回归**：`mvn -o -pl router,executor,hbt test` → router 15/0/0、hbt 36/0/0、executor 0，每类删后必仍全绿。
- **G-join 裁判**：`io.mycat.sql` join 子集 + `CreateGsiTest#testGsiJoinConverges` 门；全量 479 sweep 的 NoSuchMethodError 计数应随每类删除**单调下降**，无新增红签名。

**记账门**：每类收口回写 PROGRESS（I-DEBT-3 行状态 + §131 B 组勾选 + 孪生对计数递减 + 变更日志），账本 `2026-10-07-calcite-shadow-removal-all9/progress.md` 留每类 Ruling。

---

## 成功判据（方向 A 的达标定义）

- `Glob hbt/**/org/apache/calcite/**/*.java` 仅剩 6 个 **Mycat 真自增类**（MycatContext、MycatBuiltInMethodImpl、MycatHepJoinClustering、NewMycatDataContext、RxBuiltInMethod、RxBuiltInMethodImpl），其余同 FQCN 影子全删；
- 任何**未删**的类必须在 PROGRESS 明确挂账"因 fork 缺扩展点暂保留"及现场；
- G-unit 全绿、G-join 无新增红、NoSuchMethodError 崩溃族消解；
- fork 保持零 io.mycat（G7=0）。

## Review Focus（spec 未逐条点名、但最可能咬人的输入面）

1. **计划形状漂移**：删 RelMd 元数据影子后，join/GSI 的代价估算若因 provider 链命中顺序改变而漂移 → 以 G-join + 全量对账捕获，非仅单元绿。
2. **SPM 参数行为等价**：ParamHolder→provider 归位后，`LIMIT ?`/参数化字面量的行数估算与字面量求值须与旧影子逐值等价。
3. **`<=>` 与 codegen root 等价**：算子表承接 `<=>` 后生成码语义不变；`MycatBindable.ROOT` 归位不得改元素形状契约（Object[]）。
4. **MycatView 元数据供出等价**：provider 给 MycatView 的 collation/uniqueKeys 须与影子内 `instanceof MycatView` 分支逐条一致（尤其 merge-sort）。
5. **序列化往返**：RelJson/RelJsonWriter 删后 MycatView 的 JSON 序列化/反序列化往返不破（SPM baseline 持久化依赖）。

## 范围外（Out of Scope）

- 6 个 Mycat 真自增类的重构（本就不该删）。
- I-DEBT-8 CCE / I-DEBT-11 prepare-null NPE（另开工包）。
- 函数/类型注册缺口（产品功能建设）。
- 主动给 calcite-core 加扩展点（仅经人类逐类批准后）。
