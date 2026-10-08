# Calcite 影子版本归一（I-DEBT-3 / I-DB-1 面③）设计

- 日期：2026-10-08
- 路径：brainstorming / architectural
- 关联债：I-DEBT-3（hbt PARK 影子残留 NoSuchMethodError 族）、I-DB-1（影子归一）、I-DEBT-30 面①②（同源 codegen 接缝已各修一次）、方向1（`RelMdUtil.isRelDefinitelyNotEmpty`）
- 状态：设计已对话认可，待用户审 spec → writing-plans

## 1. 背景与问题

Mycat2 通过 vendored fork `calcite-core`（目标 Calcite 1.42）承载定制 Calcite。另有 **7 个与 fork 同全限定名（FQCN）的类拷贝寄生在 `hbt/src/main/java/org/apache/calcite/` 下**，靠 fat-jar 打包顺序在**运行期胜出**（遮蔽 fork 同名类）。项目闸门：**fork（calcite-core）零 `io.mycat` 依赖**，以保持对上游可 rebase。

反复出现的病症：运行期 `NoSuchMethodError`（如 `RexToLixTranslator.translateCondition(7参)`、`RelMdUtil.isRelDefinitelyNotEmpty`、`translateCast`、`RelJson.create`）。此前逐例手工补影子重载（已 5 次）——治标不治本。

## 2. 根因（凭当前代码复核，非既有记录）

实测坐实：**这不是"同版本双胞胎缺方法"，而是"两套 Calcite 版本混载"**：

- `RexToLixTranslator` 影子 3061 行 vs fork 2006 行，但**真·Mycat 定制仅 7 处调用点**（`MycatBindable.ROOT`、`StrictEqualFunction`、fork 中立包 `MycatBuiltInMethod`）；上千行 churn 主体是**版本落后 + 通配 import 风格**。
- 版本指纹：fork 有 `RexLambda`/`RexLambdaRef`/`RexPatternFieldRef` 等新 Calcite API 的 import 与 `translateCondition` 的 `nullable` 重载；影子**均无** ⇒ 影子基线是**更旧 Calcite**，fork 是 1.42。
- fork 内部 `new RexLiteral(...)`（23 处）、`new RexToLixTranslator(...)`（6 处）**直接构造具体类** ⇒ 这些类**非插拔**，不改 fork 就只能整类遮蔽（这正是当初"只能 PARK"的真因）。

⇒ **fat jar 同时存在旧版影子类 + 新版 fork 调用方**，接缝处签名/行为错配即 `NoSuchMethodError`。手工补重载只焊一条缝，缝会不断冒。病根 = **版本二义性本身**。

### 7 个同 FQCN 影子（本轮对象）
`adapter/enumerable/RexToLixTranslator`、`rex/RexLiteral`、`rel/externalize/RelJson`、`rel/externalize/RelJsonWriter`、`rel/metadata/RelMdRowCount`、`rel/metadata/RelMdMaxRowCount`、`rel/metadata/RelMdUtil`。

> 另有 `MycatContext`/`MycatHepJoinClustering`/`NewMycatDataContext`/`RxBuiltInMethod`/`RxBuiltInMethodImpl` 是 Mycat 自研（fork 无同名），**非影子，不在范围**。

### 既有中立落点（机制无需从零建）
`calcite-core` 已含 `org.apache.calcite.mycat` 包（`MycatBuiltInMethod`/`MycatBuiltInMethodImpl`/`MycatSqlDefinedFunction`），三者 `import io.mycat=0`——闸门正是靠这个"Calcite 包名下的 Mycat 中立支持包"守住的。归一回填即以此包为落点。

## 3. 目标与非目标

**目标**
1. 统一为单一 Calcite 版本（fork 1.42）：删除旧版影子拷贝，让 fork 原生类胜出。
2. 把可数的真·Mycat 定制以 **fork 中立 seam** 回填进 fork，**保持 fork 零 io.mycat**。
3. 从结构上消灭"影子缺 fork 方法"这一整类缺陷（单版本后逻辑上不可能再现）。
4. 顺带吞掉方向1（`RelMdUtil.isRelDefinitelyNotEmpty`：fork 本就有，影子一删即不缺失）。

**非目标**
- 不改 fork 的公共 API 语义以适配 Mycat 业务（只加最小中立 seam）。
- 不处理非插拔之外的其它债务族（I-DEBT-25/26/27/28/29 等各自独立）。
- 不引入上游新 Calcite 版本升级（仍是既有 fork 1.42）。

## 4. 架构与机制

### 4.1 单版本原则
影子删除后，fork 1.42 类是唯一实现，运行期不再有"旧版胜出"歧义。Mycat 定制改由 seam 注入，而非整类替换。

### 4.2 三类中立 seam（按触点归纳，均以 plain Calcite 类型表达）

**S1 · Root seam**（承载 `MycatBindable.ROOT`）
- 现状：fork `RexToLixTranslator` 用 `DataContext.ROOT`（类型 `DataContext`）；Mycat 需 `MycatBindable.ROOT = Expressions.parameter(NewMycatDataContext.class, "root")`（类型是 Mycat 的 `NewMycatDataContext`）。
- 设计：fork `RexToLixTranslator` 内新增 `static Supplier<ParameterExpression> ROOT_SUPPLIER`，默认 `() -> DataContext.ROOT`；所有原 `DataContext.ROOT` 引用点改走 `ROOT_SUPPLIER.get()`。hbt 启动处 `ROOT_SUPPLIER.set(() -> MycatBindable.ROOT)`。
- 闸门：seam 类型是 `ParameterExpression`（plain Calcite），fork 不 import io.mycat。

**S2 · 动态参上下文 seam**（承载 `ParamHolder.CURRENT_THREAD_LOCAL`）
- 现状：`RexLiteral.java:1361`、`RelMdUtil`、`RelMdRowCount`、`RelMdMaxRowCount` 调 `ParamHolder.CURRENT_THREAD_LOCAL.get()` 及少量访问方法（取当前动态参值）。
- 设计：在 `org.apache.calcite.mycat` 定义中立接口 `MycatDynamicParamContext`（含 `static MycatDynamicParamContext current()` 与各影子实际用到的访问方法；具体方法集在计划期枚举这 4 处调用点的用法后定），默认实现返回"无参"语义；hbt 提供以 `ParamHolder` 为底的实现并在启动处注册。
- 闸门：接口与默认实现均在 calcite-core 内，零 io.mycat；ParamHolder 只活在 hbt 实现里。

**S3 · 算子替换 seam**（承载 `StrictEqualFunction.INSTANCE`）
- 现状：`RexToLixTranslator.java:2672` 在特定条件下把 operator 替换为 `StrictEqualFunction.INSTANCE`。
- 设计：fork 中立接口 `MycatOperatorResolver`（入参原 operator/上下文，出参可替换 operator；默认原样返回），hbt 注册严格相等逻辑。或直接把 `StrictEqualFunction` 的中立部分迁入 `org.apache.calcite.mycat`。计划期二选一（取更小改动者）。

### 4.3 注册点
单一 hbt 启动安装例程（复用面① `MycatCore()` 构造器顶部 `install()` 模式），集中 `set` S1/S2/S3 默认→Mycat 实现。依赖方向合法：hbt 依赖 calcite-core，可 import 并写其静态 seam。

## 5. 分阶段范围

**Phase A（参考类，本轮验收）**：`RexLiteral`（S2，1 触点）+ `RexToLixTranslator`（S1+S3，实测其 io.mycat 触点仅 `MycatBindable.ROOT` 与 `StrictEqualFunction`，不含 S2）。
- 目标：在最承重、最爱崩处跑通"删影子 → fork 1.42 胜出 → 中立 seam 回填 → 全绿"完整闭环，证伪 §7 风险。
- 交付即确立可复制的范式（seam 命名/注册/守卫测试模板）。

**Phase B（续做，同范式铺开）**：`RelMdUtil`（含方向1 `isRelDefinitelyNotEmpty`）、`RelJson`、`RelJsonWriter`、`RelMdRowCount`、`RelMdMaxRowCount`。
- 每类触点可数（各 1–2 io.mycat import），机械套用 Phase A 范式。

> Phase A 若揭示"旧版行为承重、fork 1.42 不等价"（见 §7），则该类退路为"保留最小显式 fork diff 而非删影子"，并把真相写回本 spec 修订；不阻塞其余类。

## 6. 验证

1. **结构守卫测试**（防复发）：断言运行期载入的 `RexToLixTranslator`/`RexLiteral`/… **即 fork 类**（单一真源），并保留 fork-vs-被调 ABI 一致断言（面② 的 `ShadowRexToLixTranslatorJoinConditionTest` 归一后改造为"fork 类即胜出类"的正向锁，而非影子缺方法锁）。
2. **e2e**：`testOptimizationProcedure`（面② JOIN，须仍 1/0/0）、`testComplexQuery`（`:403` `isRelDefinitelyNotEmpty` 崩点须消）、`case1`/`testCastFunction` 等既有 codegen 相关用例不退化。
3. **护栏**：`mvn -o test -pl hbt,router` 全量零回归；重打 fat jar + 8066 活体复跑。
4. **闸门校验**：`grep "^import io.mycat" calcite-core/.../mycat/*` 保持 0；`grep -R "io.mycat" calcite-core/src/main/java/org/apache/calcite` 命中数不增（seam 全用 plain Calcite 类型）。

## 7. 关键风险与处置（经验证伪，不梭哈）

- **行为等价风险（主）**：影子是旧版 codegen，fork 1.42 新版对 lambda/pattern/codegen 结构可能不同；当年冻结旧影子**或许正因新版某行为 Mycat 不认**。
  - 处置：Phase A 先只动 1–2 类、以全绿 e2e+护栏为门经验证伪；绿则范式成立，红则就地定位"旧行为承重"点，该类回退到"最小显式 fork diff"并记录，其余类不受阻。**绝不一把删全部 7 类。**
- **注册遗漏风险**：seam 默认=上游行为，若 hbt 未在 codegen 触发前 `set`，将静默丢定制、测试可能仍绿。
  - 处置：seam 默认实现遇 Mycat 依赖路径时显式 fail-fast（或断言日志），并以结构守卫测锁"注册已生效"。
- **rebase 成本**：fork 新增中立 seam 属可控、局部、无 io.mycat 的加法，rebase 摩擦远小于维护整类旧版影子。

## 8. 影响文件（预期）

- 删：7 个 `hbt/src/main/java/org/apache/calcite/**` 影子拷贝（Phase A 先 2 个）。
- 改：对应 `calcite-core/.../RexToLixTranslator.java`、`RexLiteral.java` 等——加中立 seam（默认=上游行为）。
- 增：`calcite-core/.../org/apache/calcite/mycat/` 下 seam 接口与默认实现；`hbt` 下 seam 的 Mycat 实现 + 启动注册例程。
- 测：结构守卫测试改造 + e2e 复跑。

## 9. 交付边界

- 本 spec 定义 WHAT/WHY 与 seam 机制；逐类逐行移植步骤、ParamHolder 访问方法集、算子 seam 二选一，留 writing-plans 分解。
- Phase A 为一次实现计划的范围；Phase B 另立续做（可复用本 spec）。
- 合回 main / push 由用户拍板。
