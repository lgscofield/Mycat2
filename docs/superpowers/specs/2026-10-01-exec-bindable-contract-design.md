# I-DEBT-4：执行层 Bindable 契约归位设计（L1 最后一公里）

> 状态：设计已获用户批准（2026-10-01，方向 A + "以 L1 通路为界"）。本文是 spec（实施计划由其派生）。
> 前置：I-DEBT-3 已收口（validator 影子删除，旧 CCE 消失）；本债承接 G4b。

## 1. 背景与实证（全部 file:line 当场复核，不信任历史记录）

Mycat 的执行引擎把 RelNode 计划 codegen 成 Java 源码串，运行期用 janino 编译成
"Bindable"实例。删掉 validator 影子后 L1 通路第一次走到这一段，实测断层：

- **崩点**：`bash ci/verify-l1.sh` rc=1，`target/ci/l1-server.log:547`
  janino `CompileException: Non-abstract class "Baz" must implement
  Bindable.bind(DataContext)`。
- **生产 codegen 唯一入口**：`hbt/.../DrdsExecutorCompiler.java:282,317-323` →
  `StreamMycatEnumerableRelImplementor.implementHybridRoot`（:60-85 生成
  `bindObservable`/`isObservable`/`getElementType` 三方法，**没有 `bind`**）→
  :318 把 memberDeclarations 扁平成代码串 →
  `server/.../ExecutorProviderImpl.java:121` janino
  `setImplementedInterfaces(ArrayBindable.class)`。
- **fork 的契约**：`calcite-core/.../runtime/ArrayBindable.java:27`
  `Enumerable<Object[]> bind(DataContext)`；Mycat 真实想要的是**双态**：
  `PrepareExecutor.java:74-82、:120-126` 拿到 `bind()` 返回值先
  `instanceof Observable` 分叉（流式计划返 Rx Observable，传统计划返 Enumerable）。
- **生成代码体依赖类型化 root**：`l1-server.log:484-542` 实测生成体调用
  `root.getSessionVariable(...)` —— 这是 `NewMycatDataContext`
  （hbt `org/apache/calcite/runtime/NewMycatDataContext.java:20` 起，
  extends DataContext 的 Mycat 私有接口）上的方法，官方 `DataContext` 没有。
- **Mycat 私有类型寄生在 calcite 公开接口上的手段**：hbt 的
  `org/apache/calcite/DataContext.java` 影子副本（11 对同 FQCN 孪生之一）唯一
  关键改动是把 `DataContext.ROOT` 参数类型从官方 `DataContext.class` 改成
  `NewMycatDataContext.class`；hbt 内 **23 处** `DataContext.ROOT` 引用靠它生成
  类型化 root 参数（含 MycatView:846、MycatCalc:213,343、MycatMemSort:152、
  MycatTopN:136、infofunction 群等）。
- **未遂的邪路证据**：hbt `RxBuiltInMethod.java:39` 注释掉的
  `// OBSERVABLE_BIND(Bindable.class, "bindObservable", NewMycatDataContext.class)`
  —— 当年想把私有方法塞进 calcite 类型系统，未走完。
- 旁支：`MycatEnumerableRelImplementor.implementRoot:82-88` 是会生成正确
  `bind` 的样例，但生产无人调用（唯一实例化点是其子类 Stream 版）。
- `isObservable` 生成于 StreamMycatEnumerableRelImplementor:67-73，
  **全库零调用方**（调用面用 instanceof 分叉）。

## 2. 目标与非目标

**目标**
- G-a：L1 端到端绿——容器后端下 `ci/verify-l1.sh` rc=0 且 `L1 OK rows=`。
- G-b：bind 契约归位到 Mycat 自有接口，编译期多态，**零反射、零 fork 改动、
  零新增影子**。
- G-c：删 hbt `org.apache.calcite.DataContext` 影子副本（同 FQCN 孪生 11→10）。

**非目标**
- 不重写执行引擎（ordinate 引擎、Arrow 通路 `ArrowBindable` 原样保留）。
- 不在本债删除其余 10 对孪生副本（RexToLixTranslator、RexLiteral、RelMd×5、
  RelJson×2、MycatBuiltInMethodImpl）——除非 L1 棘轮撞上了它们，按 §6 处置。
- 不改 fork（calcite-core 保持零 `import io.mycat`，G7 门不变）。
- 不迁 `NewMycatDataContext` 的包名（A 组寄生改包名是后续独立小任务；本债
  只把它从"被 calcite.DataContext 影子引用"解绑为"被 MycatBindable.ROOT 引用"，
  FQCN 暂不动，避免一次改动两族）。

## 3. 方案裁定

| 方案 | 内容 | 裁定 |
|---|---|---|
| **A. Mycat 自有 bind 契约** | 新接口 + 生成器产 `bind` + 类型化 ROOT 归 Mycat + 删 DataContext 副本 | **采纳**（用户批准 2026-10-01） |
| B. 硬凑 fork ArrayBindable | 生成 `bind(DataContext)` 返 Enumerable，Observable 转掉 | 否决：流式执行断成同步全量物化，行为退化 |
| C. fork 的 Bindable 加 Mycat 方法 | 改 calcite-core | 否决：重开 fork 污染，违反 G7 纪律（正是 `RxBuiltInMethod:39` 未遂邪路） |

A 的层级定位：最优解梯子第①层（标准扩展点/自有接口的编译期多态）。
Mycat 双态执行语义本来就不是 calcite `Bindable` 能表达的——与其寄生改它，
不如承认"Myocat 的执行契约是 Mycat 的接口"。

## 4. 设计

### 4.1 新接口（唯一新增生产类型）

`hbt/src/main/java/io/mycat/calcite/MycatBindable.java`：

```java
public interface MycatBindable {
  /** 生成的 bind 的根上下文参数：类型化到 Mycat 私有上下文（寄生于
   *  org.apache.calcite.runtime.NewMycatDataContext，包名迁移是后续 A 组任务）。 */
  ParameterExpression ROOT = Expressions.parameter(Modifier.FINAL,
      NewMycatDataContext.class, "root");

  /** 双态返回：流式计划返 io.reactivex Observable<Object[]>，传统计划返
   *  Enumerable<Object[]>。调用方以 instanceof 分叉（PrepareExecutor 既有逻辑）。 */
  Object bind(NewMycatDataContext root);

  Class getElementType();
}
```

- `isObservable` **不进接口**（全库零调用方；生成器照旧多产一个方法无害，
  janino 允许额外方法）。
- 方法名就叫 `bind`——这是契约本身；`bindObservable` 从生成器消失。

### 4.2 生成器改动（hbt）

`StreamMycatEnumerableRelImplementor.implementHybridRoot`：
- :64 `"bindObservable"` → `"bind"`；:65 参数 `DataContext.ROOT` →
  `MycatBindable.ROOT`；:84 classDecl 的接口 `Bindable.class` →
  `MycatBindable.class`。
- 其余 21 处 `DataContext.ROOT` 引用点（MycatView/MycatCalc/MycatMemSort/
  MycatTopN/MycatEnumerableRelImplementor/infofunction 群等）逐点替换为
  `MycatBindable.ROOT`（机械替换，语义等价：两者同为
  `ParameterExpression(Modifier.FINAL, NewMycatDataContext.class, "root")`，
  生成代码文本逐字节不变——这是 4.4 合同测试的断言依据之一）。

### 4.3 消费方改动（类型替换，逻辑不动）

`ArrayBindable` → `MycatBindable` 共 7 文件：
CodeExecuterContext、ExecutorProvider、PrepareExecutor、
ObservablePlanImplementorImpl:241-243（hbt）；ExecutorProviderImpl、
SqlResultSetService:170、HintHandler:153-154（server）。

- `ExecutorProviderImpl:121` janino `setImplementedInterfaces(MycatBindable.class)`。
- `ExecutorProviderImpl.asObjectArray`（:135-153）anon 类实现 `MycatBindable`：
  非数组元素升箱时 `bind` 返回值分两态——Enumerable 走 `.select(e->new Object[]{e})`
  照旧；Observable 走 `.map(e -> new Object[]{e})`（Rx 逐元素升箱为单元素行）。
  入口条件仍 `getElementType().isArray()`。
- 调用点传参：`PrepareExecutor:75/:120` 传入的 `AsyncMycatDataContextImpl`
  已是 `NewMycatDataContext` 实现者（经 `NewMycatDataContextImpl`），类型收窄
  不破坏任何调用。

### 4.4 删影子（G-c）

- 删 `hbt/src/main/java/org/apache/calcite/DataContext.java` 副本
  （孪生对 11→10，G6v 数字门）。
- 删前先证：hbt 内不再有依赖副本 ROOT 类型的引用（4.2 替换完成后由编译器证明——
  若还有引用 fork 语义不兼容处，编译即爆，不留隐患）。
- 附带清理（不进验收门）：`RxBuiltInMethod.java:39` 注释行删除——邪路痕迹。

### 4.5 执行行为黄金基线（方法论沿用 I-DEBT-3：先自校后用）

新测试 `hbt/src/test/java/io/mycat/calcite/MycatBindableContractTest.java`：
1. **RED（先行）**：用 I-DEBT-3 的 validator 基线同款 fixture 构造
  `SELECT @@session.auto_increment_increment, ...`（l1 崩点原句）计划 →
  `DrdsExecutorCompiler.getCodeExecuterContext` 取代码串 → 断言含
  `public Object bind(final org.apache.calcite.runtime.NewMycatDataContext root)`。
  改动前必红（现为 bindObservable）——这是"看着失败"的钉子。
2. **GREEN**：janino 现编译该代码串 implements `MycatBindable`，用 JDK Proxy
  造 `NewMycatDataContext`（`getSessionVariable` 返 null），调 `bind` →
  断言返回 Enumerable、枚举一行、行值全 null——复刻 l1-server.log:484-542
  的生成语义。
3. **双态钉子**：对同一代码串断言 `bind` 声明返回 `Object`（不是 Enumerable），
  防 B 方案回潮。
4. 流式态（isObservable 分支）由 L1 端到端的真实查询覆盖（DML over MycatView
  的 Observable 路径），不在单测强造——YAGNI，端到端门兜底。

## 5. 验收门（逐条命令，账本实录原始输出）

| 门 | 命令 | 判据 |
|---|---|---|
| S-RED→GREEN | `mvn -B -o test -pl hbt -Dtest=MycatBindableContractTest` | 先红（bindObservable 现状）后绿 |
| G1 构建 | `mvn -B -o install '-Dmaven.test.skip=true' -pl server -am` | BUILD SUCCESS |
| **G4b-L1** | 容器后端（m1-mysql-a:3306）`bash ci/verify-l1.sh` | **rc=0 且 `L1 OK rows=`**（I-DEBT-3 起挂账的本债终门，首次转硬门前仍走 CI 软门一轮） |
| G2v | `mvn -B -o test -pl hbt -Dtest=CalciteValidatorGoldenTest` | 1/0/0 不劣化 |
| G5 | `mvn -B -o test -pl router,executor,hbt "-Dmaven.test.failure.ignore=true"` | router 15/0/0；hbt 30/0/23（29+本债 1 新测，23 error 仍全为既有 ColumnMappingTest）；executor 0 |
| G6v | 同 FQCN 孪生对枚举命令（I-DEBT-3 同款） | 11 → **10** |
| G7 | `git grep -l "^import io.mycat" calcite-core/src/main/java` | 0（fork 纯净性不变） |

## 6. 棘轮与止损（L1 为界的含义说死）

G4b 首跑大概率不会一次绿：生成体内还引用其余影子族（RexToLixTranslator 孪生、
RexLiteral 孪生、RelMd×5 孪生、NewMycatDataContext 寄生、ordinate 引擎触点）。
每撞一个新断点，按 I-DEBT-3 spec §4.5 同款棘轮：**记录 file:line + 栈帧 +
定性（旧病/回归）** → 属于"Mycat 私有概念寄生 calcite 命名空间"的，随本债归位；
属于"需删孪生副本"的（RexToLixTranslator/RexLiteral 级别是重型三方合并），
**停手回报用户**，不即兴扩大本债面。
止损阈值（沿用 §4.6 口径）：本债新增"向 fork 移植的真定制" **> 10 条即停**。

## 7. 风险与回滚

- **生成代码文本必须逐字节不变**（除方法名 bindObservable→bind）：4.5 的
  RED 断言 + `PlanImpl:128` explain 输出人审一轮，防 ROOT 替换意外改变表达式
  树打印形态。
- 双态 instanceof 分叉脆弱性（返回 Object 无静态保证）：接口 javadoc 钉死
  语义 + 合同测试断言两态归属；不做密封类改造（YAGNI，改动面大）。
- 回滚：本债全部改动在独立提交链上（不夹带），revert 即回 I-DEBT-3 收口态。

## 8. 追踪

- 看板项：I-DEBT-4（docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md）。
- Dep：I-DEBT-3（✅ Evidenced-Done，df9856c14）。
- 完成后动作：`ci/verify-l1.sh` 在 workflow 的 `continue-on-error` 摘除
  （I-DEBT-3 Task 7 裁定的顺延项，归本债收口清单）。
