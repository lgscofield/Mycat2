# I-DEBT-4 执行层 Bindable 契约归位 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Mycat 执行引擎的"双态 bind"契约归位到 Mycat 自有接口 `MycatBindable`（编译期多态，零反射/零 fork 改动/零新增影子），删掉 hbt `DataContext` 影子副本，打通 L1 端到端拿到真实 rows。

**Architecture:** 生成器 `StreamMycatEnumerableRelImplementor` 改产 `bind`（现产 `bindObservable`），janino 编译目标接口从 fork 的 `ArrayBindable` 换成 `MycatBindable`；类型化 root 参数常量 `ROOT` 从"寄生在 DataContext 副本里"迁到 `MycatBindable.ROOT`，hbt 全部 23 处引用点机械替换后，`DataContext` 副本失去存在理由被删（同 FQCN 孪生 11→10）。

**Tech Stack:** Java 21、Calcite 1.42 fork（`io.mycat:calcite-core`）、janino 3.1.12（hbt 直接依赖，hbt/pom.xml:67-72）、RxJava3、Maven。

**Spec:** `docs/superpowers/specs/2026-10-01-exec-bindable-contract-design.md`（03d1fec1c）

## Global Constraints

- fork（calcite-core）零改动：`git grep -l "^import io.mycat" calcite-core/src/main/java` 保持 0（G7）。
- 零反射进入生产行为路径（反射只准出现在测试工具）。
- 生成代码文本除 `bindObservable`→`bind` 改名外逐字节不变（spec §7）。
- 棘轮止损：L1 途中撞需"删重型孪生副本"（RexToLixTranslator/RexLiteral/RelMd×5/RelJson×2/MycatBuiltInMethodImpl 级三方合并）即停手回报；本债新增向 fork 移植的真定制 >10 条即停（spec §6）。
- 测试后端一律 Docker 容器（m1-mysql-a:3306 / m1-mysql-b:3307），禁动本地 MySQL84 服务。
- 环境命令基线：`$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"`；mvn 一律 `-B -o`；PowerShell 下 `-D` 参数加引号。
- hbt 既有 23 个 ColumnMappingTest error 为另案，判据用"三项数字不劣化"而非退出码。

## Review Focus

- **非数组单列计划 + Observable 态**的 `asObjectArray` 升箱：老 wrapper 只处理 Enumerable（返回 Observable 时旧代码会 ClassCastException），新 wrapper 双态都要正确——合同测试加钉子。
- **fork 侧 RexToLixTranslator 若在运行期胜出**（ shaded classpath 顺序），其产码用 `DataContext.ROOT`（名 "root"、类型 DataContext）：我方方法声明参数是 NewMycatDataContext "root"，标准 `root.get(...)` 调用应仍编译——Task 4 L1 端到端兜底验证。
- **janino 允许实现类有接口之外的额外 public 方法**（`isObservable` 保留生成）：若 janino 版本行为与此不符，Task 1 GREEN 步会暴露。
- **PlanImpl explain 输出**（`PlanImpl:128` 拆代码串行）形态变化会影响依赖 explain 文本的用法：Task 3 收尾人审一次 explain。
- **PrepareExecutor 调用点实参类型**（AsyncMycatDataContextImpl 经 NewMycatDataContextImpl 实现 NewMycatDataContext）：编译即证。

---

### Task 1: MycatBindable 接口 + 生成器改产 bind（契约最小闭环）

**Files:**
- Create: `hbt/src/main/java/io/mycat/calcite/MycatBindable.java`
- Modify: `hbt/src/main/java/io/mycat/calcite/StreamMycatEnumerableRelImplementor.java:51,64-65,84`
- Test: `hbt/src/test/java/io/mycat/calcite/MycatBindableContractTest.java`

**Interfaces:**
- Consumes: `EnumerableRel.Result(BlockStatement, PhysType, JavaRowFormat)`（fork EnumerableRel.java:118，public）；`PhysTypeImpl.of(JavaTypeFactory, RelDataType, JavaRowFormat)`（fork :81）；`MycatRel.isSupportStream()` / `implementStream(StreamMycatEnumerableRelImplementor, Prefer)`；`BuiltInMethod.AS_ENUMERABLE`；`Expressions.call(ParameterExpression, Method, Expression...)`。
- Produces: `io.mycat.calcite.MycatBindable`——`ParameterExpression ROOT`（`Expressions.parameter(Modifier.FINAL, NewMycatDataContext.class, "root")`）、`Object bind(NewMycatDataContext root)`、`Class getElementType()`；生成代码含 `public Object bind(final org.apache.calcite.runtime.NewMycatDataContext root)`。Task 2/3/4 依赖这些确切签名。

> **Fixture 口径（相对 spec §4.5 的计划级裁定）**：fixture 用 JDK Proxy 假 MycatRel + 手写 block（含 `root.getSessionVariable("probe")` 调用，形态同 l1-server.log:484-542）驱动 `implementHybridRoot`，不走完整 planner——因为 planner 脚手架就是 ColumnMappingTest 23 error 的已知泥潭，单测不踩它；真实语句 `SELECT @@session...` 的完整链路由 Task 4 L1 端到端门兜底。

- [ ] **Step 1: 写失败的合同测试（RED）**

新建 `MycatBindableContractTest`，fixture 构造（全 API 已在 Global Constraints/Interfaces 钉死）：

```java
// 造一个可控 Result：block 先调用 root.getSessionVariable("probe")，再 return Linq4j 空数组 enumerable
BlockBuilder builder = new BlockBuilder();
builder.add(Expressions.statement(Expressions.call(
    /* ROOT：先断言期临时用局部同名参数，见下 */ root,
    NewMycatDataContext.class.getMethod("getSessionVariable", String.class),
    Expressions.constant("probe"))));
builder.add(Expressions.return_(null, Expressions.call(
    BuiltInMethod.AS_ENUMERABLE.method, Expressions.newArrayInit(Object[].class))));
PhysType physType = PhysTypeImpl.of((JavaTypeFactory) MycatCalciteSupport.TypeFactory,
    rowType, JavaRowFormat.ARRAY);
EnumerableRel.Result result = new EnumerableRel.Result(builder.toBlock(), physType, JavaRowFormat.ARRAY);
// fakeRel：JDK Proxy，"isSupportStream"→true、"implementStream"→result，其余方法抛 UnsupportedOperationException
StreamMycatEnumerableRelImplementor implementor =
    new StreamMycatEnumerableRelImplementor(new HashMap<>());
ClassDeclaration cd = implementor.implementHybridRoot(fakeRel, EnumerableRel.Prefer.ARRAY);
String code = Expressions.toString(cd.memberDeclarations, "\n", false); // 与 DrdsExecutorCompiler:318 同款扁平化
```

断言①（文本契约）：`code` 含 `public Object bind(final org.apache.calcite.runtime.NewMycatDataContext root)`。
改动前跑：应失败于断言①（现为 `bindObservable`）——**必须看到这个失败**。
注意：RED 阶段 fixture 里的 `root` 参数先用测试本地
`ParameterExpression root = Expressions.parameter(Modifier.FINAL, NewMycatDataContext.class, "root");`
（GREEN 后此常量即 `MycatBindable.ROOT` 的同形物，Step 3 完成后测试改用 `MycatBindable.ROOT`）。

- [ ] **Step 2: 跑测试验证 RED**

Run: `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"; mvn -B -o test -pl hbt "-Dtest=MycatBindableContractTest"`
Expected: FAIL，ComparisonFailure 指向 `bindObservable`（不含 `bind(`）。

- [ ] **Step 3: 新建 `MycatBindable.java`（正文含 spec §4.1 的两态 javadoc）**

```java
package io.mycat.calcite;

import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.runtime.NewMycatDataContext;

import java.lang.reflect.Modifier;

/** Mycat 执行引擎自有的 bind 契约（I-DEBT-4）。双态返回：流式计划返
 *  io.reactivex.rxjava3.core.Observable&lt;Object[]&gt;，传统计划返
 *  org.apache.calcite.linq4j.Enumerable&lt;Object[]&gt;；调用方以 instanceof 分叉。
 *  方法名沿用 bind——被删的 hbt DataContext 副本当年靠改写 ROOT 类型寄生表达此契约。 */
public interface MycatBindable {
  ParameterExpression ROOT = Expressions.parameter(Modifier.FINAL,
      NewMycatDataContext.class, "root");

  Object bind(NewMycatDataContext root);

  Class getElementType();
}
```

改 `StreamMycatEnumerableRelImplementor`：:51 与 :65 的 `DataContext.ROOT` → `MycatBindable.ROOT`；:64 `"bindObservable"` → `"bind"`；:84 `Collections.singletonList(Bindable.class)` → `Collections.singletonList(MycatBindable.class)`；删随之无用的 `Bindable`/`DataContext` import（:7,:13——若文件内仍他处使用则保留，以编译器为准）。

- [ ] **Step 4: 补 GREEN 断言并跑绿**

测试追加（同文件新用例）：fixture 的 root 参数改用 `MycatBindable.ROOT`；janino 现编（三行配置复刻 `ExecutorProviderImpl:118-122`：`setExtendedClass(Utilities.class)`、`setImplementedInterfaces(new Class[]{MycatBindable.class})`、`setParentClassLoader(EnumerableInterpretable.class.getClassLoader())`）得到 `MycatBindable b`；断言② `b.getElementType() == Object[].class`；③ 用 `Proxy` 造 `NewMycatDataContext`（`getSessionVariable` 记录名字并返 null），`Object out = b.bind(ctx)` 不抛异常、`out instanceof Enumerable`、记录含 `"probe"`；④ 双态钉子：第二个 fixture 的 return 改
`Expressions.call(Observable.class.getMethod("just", Object.class), Expressions.newArrayInit(Object[].class))` 形态，断言 `b2.bind(ctx) instanceof io.reactivex.rxjava3.core.Observable`。

Run: `mvn -B -o test -pl hbt "-Dtest=MycatBindableContractTest"`
Expected: PASS（用例全绿；若 ④ 的 just 表达式产文本不合 janino 语法，用 `Expressions.call` 静态方法等价写法调整 fixture 内部——不许动生产断言）。

- [ ] **Step 5: hbt 模块回归不劣化**

Run: `mvn -B -o test -pl hbt`
Expected: Tests run 30, Failures 0, Errors 23（29+本债 1；23 仍全为 ColumnMappingTest）。

- [ ] **Step 6: Commit**

```bash
git add hbt/src/main/java/io/mycat/calcite/MycatBindable.java hbt/src/main/java/io/mycat/calcite/StreamMycatEnumerableRelImplementor.java hbt/src/test/java/io/mycat/calcite/MycatBindableContractTest.java
git commit -m "feat(hbt): MycatBindable 自有契约，生成器改产 bind（I-DEBT-4 Task 1）"
```

---

### Task 2: ROOT 全量替换 + 消费方类型归位（hbt 21 处 + 7 文件）

**Files:**
- Modify: hbt 内其余 21 处 ROOT 引用：`MycatEnumerableRelImplementor.java:73,87`、`logical/MycatView.java:846`、`physical/MycatCalc.java:213,343`、`physical/MycatMemSort.java:152`、`physical/MycatTopN.java:136`、`sqlfunction/infofunction/` 下 10 个函数类各 1 处（MycatConnectionId:42、MycatCurrentUser:42、MycatDatabase:43、MycatGlobalValue:45、MycatLastInsertId:42、MycatRowCount:42、MycatSessionValue:49（现为 `NewMycatDataContext.ROOT`）、MycatUser:42、MycatUserValue:45）+ `lockfunction/` 3 处（MycatGetLock:43、MycatIsFreeLock:43、MycatReleaseLock:44）、`org/apache/calcite/adapter/enumerable/RexToLixTranslator.java:178,2408`
- Modify: `hbt/.../CodeExecuterContext.java:28`、`hbt/.../ExecutorProvider.java:8`（import 换/删）
- Modify: `hbt/.../PrepareExecutor.java:18,29,50,55,66-67,119-120`（ArrayBindable→MycatBindable）
- Modify: `hbt/.../plan/ObservablePlanImplementorImpl.java:40,241`
- Modify: `server/.../ExecutorProviderImpl.java:26,104-107,110,121,131,134-153`
- Modify: `server/.../resultset/SqlResultSetService`(:170) `server/.../sqlhandler/dql/HintHandler.java:64,153`

**Interfaces:**
- Consumes: Task 1 的 `MycatBindable`（`ROOT`/`bind`/`getElementType`）。
- Produces: 全库 `git grep -n "DataContext\.ROOT" hbt/src/main` = 0 行（副本删除前置条件，Task 3 用）；`ExecutorProviderImpl:121` janino implements `MycatBindable.class`；`PrepareExecutor.getMysqlPayloadObjectObservable(MycatBindable, AsyncMycatDataContextImpl, MycatRowMetaData)` 签名不变（仅首参类型收窄）。

- [ ] **Step 1: hbt 侧 21 处 ROOT 替换（含 RexToLixTranslator 副本内 2 处）**

每处 `DataContext.ROOT` / `NewMycatDataContext.ROOT` → `MycatBindable.ROOT`（静态导入或限定名随文件习惯），需要时补 import、删无用 `DataContext` import。RexToLixTranslator.java:178/:2408 两处 `final ParameterExpression root = DataContext.ROOT;` → `MycatBindable.ROOT`（该副本仍保留——它是重型孪生，非本债删除面）。

- [ ] **Step 2: `asObjectArray` 双态升箱迁入 `MycatBindable`（静态方法，可测）**

`MycatBindable` 追加静态方法 `static MycatBindable asObjectArray(MycatBindable bindable)`：入口条件 `bindable.getElementType().isArray()` 原样；anon 实现 `getElementType()→Object[].class`；`bind` 分两态——`Object out=bindable.bind(root); out instanceof Enumerable → ((Enumerable)out).select(e->new Object[]{e})；out instanceof Observable → ((Observable)out).map(e->new Object[]{e})；其余原样返回`（spec §4.3；Review Focus 第 1 条）。
`ExecutorProviderImpl:105` 改调 `MycatBindable.asObjectArray(...)`，删本类私有旧法（:134-153）。

Run: `mvn -B -o install '-Dmaven.test.skip=true' -pl server -am`
Expected: BUILD SUCCESS（hbt+server 编译链即类型收窄的证明）。

- [ ] **Step 3: 消费方类型替换（逻辑不动）**

hbt：CodeExecuterContext/ExecutorProvider（import 换或删）；PrepareExecutor 字段/构造/`getMysqlPayloadObjectObservable` 首参/`asObservableObjectArray` 局部（:29,50,55,66-67,119-120）→ `MycatBindable`；ObservablePlanImplementorImpl:241 `MycatBindable arrayBindable = prepare.getArrayBindable();`。
server：ExecutorProviderImpl import/:104 返回类型/:110/:131 强转/:121 `setImplementedInterfaces(new Class[]{MycatBindable.class})`；SqlResultSetService:170、HintHandler:64(import)/153 局部类型。

- [ ] **Step 4: 合同测试加 asObjectArray 双态钉子并全跑绿**

`MycatBindableContractTest` 追加用例⑤⑥：`asObjectArray(非数组 Enumerable 装)` bind 后逐行 `Object[]` 长度 1；Observable 态同理 `.blockingIterable` 验证。
Run: `mvn -B -o test -pl hbt "-Dtest=MycatBindableContractTest"` → PASS；
`mvn -B -o test -pl hbt` → 30/0/23 不劣化；`git grep -c "\bArrayBindable\b" -- hbt/src server/src` → 0 行（server 旧私有 asObjectArray 的 ArrayBindable 也清）。

- [ ] **Step 5: Commit**

```bash
git add hbt/src server/src
git commit -m "refactor: 类型化 ROOT 归位 MycatBindable，ArrayBindable 消费方全量替换（I-DEBT-4 Task 2）"
```

---

### Task 3: 删 DataContext 影子副本（G6v 11→10）

**Files:**
- Delete: `hbt/src/main/java/org/apache/calcite/DataContext.java`
- Modify: `hbt/src/main/java/org/apache/calcite/util/RxBuiltInMethod.java:39`（删注释掉的 OBSERVABLE_BIND 邪路痕迹行）
- Test: 复用既有（本 Task 无新测——删除的合法性由编译器与 G6v 数字门证明）

**Interfaces:**
- Consumes: Task 2 前置条件 `git grep "DataContext\.ROOT" hbt/src/main` = 0；副本与 fork 的全部差异已实证仅为 ROOT 类型与 import/javadoc（本计划勘察记录：fork 侧独有行全是上游新增 TimeFrameSet/@Nullable 等，副本无 fork 缺失的成员）。
- Produces: 同 FQCN 孪生对 =10；`hbt/src/main/java/org/apache/calcite/` 下文件数 16→15。

- [ ] **Step 1: 前置验证（防凭记录删除）**

Run: `git grep -n "DataContext\.ROOT" -- hbt/src/main` → Expected 空输出；
`git grep -n "import org.apache.calcite.DataContext;" -- hbt/src/main` 逐点确认引用者只用标准成员（get/getRootSchema/Variable），不再用副本 ROOT。

- [ ] **Step 2: 删副本 + 清 RxBuiltInMethod:39**

- [ ] **Step 3: 全链构建 + 三道数字门复跑**

Run: `mvn -B -o install '-Dmaven.test.skip=true' -pl server -am` → BUILD SUCCESS；
`mvn -B -o test -pl hbt` → 30/0/23；
孪生对枚举（I-DEBT-3 Task 7 同款 pwsh 循环）→ **10**；
`git grep -l "^import io.mycat" -- calcite-core/src/main/java` → 0。

- [ ] **Step 4: explain 输出人审（Review Focus 第 4 条）**

从 hbt 测试里挑一处 explain/代码串打印（如 `PlanImpl:128` 路径的既有测试输出），确认代码串形态未因 ROOT 迁移改变（除方法名）。把结论一行记账本。

- [ ] **Step 5: Commit**

```bash
git add -A hbt/src
git commit -m "refactor!: 删 hbt DataContext 影子副本，ROOT 寄生终结（I-DEBT-4 Task 3，孪生对 11→10）"
```

---

### Task 4: L1 端到端（G4b 终门，容器后端，棘轮循环）

**Files:**
- 无预案改动。本 Task 是"跑门—定性—（可能）回报"循环；每轮撞点只在账本记录 file:line+定性，符合 spec §6 归位范围的微小改动（如漏替换的第 24 处引用）才可随轮提交。

**Interfaces:**
- Consumes: Task 1-3 全部产物；重建 shaded jar。
- Produces: `L1 OK rows=` 证据（Task 5 摘软门的唯一前置），或停损回报。

- [ ] **Step 1: 环境与 jar**

`docker info` 就绪检查（未起则 `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"` 轮询）；`docker start m1-mysql-a m1-mysql-b`；
`docker exec m1-mysql-a mysql -uroot -p123456 -e "select 1"` 探活。
Run: `mvn -B -o install '-Dmaven.test.skip=true' -pl server -am`（重建 shaded jar）。

- [ ] **Step 2: 跑 L1 门**

Run（WSL bash）: `L1_CONNECTOR=/mnt/d/Nexus/repository/com/mysql/mysql-connector-j/9.3.0/mysql-connector-j-9.3.0.jar bash ci/verify-l1.sh`
Expected: rc=0 且输出 `L1 OK rows=`。
**若 rc≠0**：抓 `target/ci/l1-server.log` 崩点——`ExecutorProviderImpl` 吞异常处（:82-86）附近必有真实异常（沿 `grep -n "CompileException\|NoSuchMethod\|ClassCast\|NullPointer" target/ci/l1-server.log` 找）；按 spec §6 定性：漏替换/可归位小改 → 记 `Task 4: Ruling` 后修一列再来；重型孪生（RexToLixTranslator/RexLiteral/RelMd 级）或真定制>10 → **停，回报用户**。禁止即兴扩大删除面。

- [ ] **Step 3: 记录**

无论结果，账本记：命令、rc、`rows=` 数字或崩点 file:line+定性（旧病/回归）。

---

### Task 5: 回归收口 + 看板 + 软门复核

**Files:**
- Modify: `.github/workflows/pr-validation-main-test.yml`（仅当 Task 4 rc=0：摘 `Verify L1` 步的 `continue-on-error: true`）
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`（看板收口或如实挂账两分支）

**Interfaces:**
- Consumes: Task 4 的 rc 与证据；全部前序数字门。
- Produces: I-DEBT-4 终态（Evidenced-Done 或 WIP+崩点清单）、I-DEBT-3 顺延项（软门）处置。

- [ ] **Step 1: 三门数字**

`mvn -B -o test -pl router,executor,hbt "-Dmaven.test.failure.ignore=true"` → router 15/0/0、hbt 30/0/23、executor 0；
`mvn -B -o test -pl hbt "-Dtest=CalciteValidatorGoldenTest"` → 1/0/0；
G6v=10、G7=0（命令同 Task 3）。

- [ ] **Step 2: 条件动作**

Task 4 rc=0 → 摘软门：`.github/workflows/pr-validation-main-test.yml` 中 `Verify L1` 步删 `continue-on-error: true` 一行（定位 `git grep -n "continue-on-error" .github/workflows/pr-validation-main-test.yml`，只动 L1 步，别动 `L16` 的 matrix.experimental），并 `bash -n`/YAML 静态复核该 workflow。
Task 4 未绿 → 软门保留，看板 I-DEBT-4 如实 WIP + 崩点 file:line。

- [ ] **Step 3: 看板收口**

I-DEBT-4 行（Evidenced-Done 时附命令清单；快照阻碍行改写；关键路径 L1→✅ 或挂账；变更日志加行；I-DEBT-3 行的"转硬门推迟"注记闭环）。

- [ ] **Step 4: Commit（两分支都提交看板，rc=0 时含 workflow）**

```bash
git add docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md .github/workflows/pr-validation-main-test.yml
git commit -m "docs: I-DEBT-4 收口；L1 软门处置（Task 5）"
```

- [ ] **Step 5: 整支终审**

范围 `5359a9a54..HEAD`（I-DEBT-4 全部提交）。按 executing-plans：有 subagent 工具则派 fresh reviewer（most capable model + code-reviewer.md），无则 self-review 并在账本标 `Final review: self-review (no subagent tool)`。发现按 Critical/Important/Minor 分级处置，Ruling 全入账本。
