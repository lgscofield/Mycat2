# I-DEBT-6 冒烟 CCE（SHOW DATABASES 单列标量行）设计文档

> 状态：**设计（自审批态）**。执行授权来源：用户 2026-10-01 预批（一次性预批 + 四条硬停 + 顺位可由助手按新证据微调并入账本 Ruling）。I-DEBT-6 由 I-DEBT-2 收口时的冒烟实测新证据插队至 I-DEBT-5 之前（看板"下一步"已记）。

**Goal:** 让 `ConnectionSmokeTest` 的 `SHOW DATABASES` 不再抛 `ClassCastException: String → Object[]`，M1 达标表"连接认证"列拿到首个真绿冒烟证据，并顺带产出 example e2e 首轮 pass/fail 清单。

**Architecture:** 单列投影 over 可视化表的信息_schema 查询（`SHOW DATABASES` 被 `ShowDatabasesHanlder` 改写为 `select SCHEMA_NAME as \`Database\` from information_schema.SCHEMATA`）在 codegen 产出的 bindable 里把每行 `Object[]` 投影成裸标量 `String`；下游结果集写行按 `Object[]` 消费。本债项修复该"标量行升箱契约"在真实执行路径上的生效性，不改 SQL 改写规则、不动可视化表数据本身。

**Tech Stack:** JDK 21、Calcite fork（`calcite-core`）+ hbt 引擎、janino codegen、RxJava3 `Observable`、Vert.x `Future`、maven-shade 单 jar。

## Spec 权威与关联
- 上游：`docs/superpowers/specs/2026-10-01-example-module-deps-design.md` §2.3 G-E2 执行后注记（本债项承接"跑绿"验收门）。
- 依赖：I-DEBT-4（`MycatBindable` 契约 + `asObjectArray` 上提，提交 392072a0c..cb795bf5b）——本 bug 正落在该契约的消费边界。
- 下游：M1 验证网达标、example 全量 pass/fail 清单。

## 1. 根因取证（当轮实测，非转抄旧账）

| # | 事实 | 证据 |
|---|------|------|
| F1 | 冒烟测可执行且**确定性红**：`Tests run: 1, Errors: 1`（非 0 执行假绿） | `i6-surefire.txt`（`ConnectionSmokeTest.connectAndBasicRoundTrip`）|
| F2 | 失败点 = `SHOW DATABASES`（第 32 行）；`SELECT 1`（第 29 行）通过 | 栈 `MycatTest.java:92 → ConnectionSmokeTest.java:32` |
| F3 | 异常 = `java.sql.SQLException: java.lang.ClassCastException: class java.lang.String cannot be cast to class [Ljava.lang.Object;`（两类型均在 `java.base` bootstrap loader → 强转发生在**服务端**，非 JDBC 客户端） | `i6-surefire.txt` |
| F4 | `SHOW DATABASES` 被改写为单列投影：`LogicalProject(\`Database\`=[$1]) → LogicalTableScan(information_schema.schemata)` | `i6-server.log:2143-2144` |
| F5 | codegen 生成的 bindable `getElementType()` 返回 `java.lang.String.class`；bind 体 = `RxBuiltInMethodImpl.select(getTableObservable("information_schema","schemata"), row -> row[1].toString())`——每行产出**裸 String** | `i6-server.log:2206-2255`（`DrdsExecutorCompiler.getCodeExecuterContext` + `ExecutorProviderImpl.getBindable` 两处 dump）|
| F6 | `SCHEMATA_TABLE_HANDLER` 产的是规整 5 列 `Object[]` 行（`{CATALOG,SCHEMA_NAME,CHARSET,COLLATION,SQL_PATH}`）——数据侧无缺陷，断层纯在投影→消费契约 | `hbt/MysqlMetadataManager.java:269-291` |
| F7 | 顶层执行入口**已**应用升箱：`ExecutorProviderImpl.getArrayBindable()` = `MycatBindable.asObjectArray(getBindable(...))`；流式（`isVector()=false`，proxy 默认）与非流式两条消费路径都取 `prepare.getArrayBindable()` | `ExecutorProviderImpl.java:78,100-104`；`ObservablePlanImplementorImpl.java:241`；`MycatDataContextImpl.isVector:489`（默认 false）|
| F8 | `asObjectArray` 守卫 `if (getElementType().isArray()) return bindable;`，对 `String.class`（非数组）**应**走 `.map(e -> new Object[]{e})` 升箱；且该行为有合同测试钉死（`asObjectArrayShouldBoxScalarObservable/Enumerable`）| `MycatBindable.java:42-65`；`MycatBindableContractTest.java:139-190` |
| F9 | **矛盾（本债项核心待证）**：F7/F8 表明升箱在真实路径上应生效、单测可复现生效，但 F3 实测仍 CCE → 一定有一条使 `String` 触达 `(Object[])` 强转的实际路径未被静态定位 | 静态读 `PrepareExecutor.getMysqlPayloadObjectObservable:82`、`RxBuiltInMethodImpl.select:25` 均不能同时解释 F3 与 F7 |
| F10 | **可诊断性缺陷**：服务端把执行异常经 `emitter.onError(throwable)` 打包成 SQL 错误响应发回客户端，**服务端日志零 ERROR/零栈**——`i6-server.log`（2260 行）内 grep `ClassCastException`/`at io.` 命中 0，异常不可归位 | `PrepareExecutor.java:83-93`；grep 结果 |

**取证结论**：症状、复现、断层族（单列标量投影 × 升箱契约）全部钉死；**确切强转点未定位**（F9），且异常被吞（F10）使静态归位失败。因此本债项**不以"猜测强转点改一行"开工**——第一步必须让运行时栈可见，凭栈钉死 F9 后再谈修复。这既守 systematic-debugging（不凭假设改码），也把 F10 这个独立缺陷一并治掉。

## 2. 范围与设计

### 2.1 Task 面（实施计划拆细，本节只定方向）
- **诊断面（先行）**：在流式/非流式结果集消费的 `onError`/`catch` 分支补服务端 ERROR 级栈日志（受 logger 级别门控，非无条件 print），使下一次冒烟能在 `i6-server.log` 拿到 `ClassCastException` 完整栈 → 钉死 F9 的确切强转调用点。此改动本身即消除 F10，属正向产品改进（可诊断性），非临时脚手架。
- **修复面（凭栈落地后确定）**：按栈定位，最小改动使单列标量投影产出的行在到达结果集写行前确为 `Object[]`。候选（须由栈证实后才择一，不预先选定）：
  - (a) 真实执行路径绕过了 `getArrayBindable`（如某 plan 缓存的 `PrepareExecutor` 以裸 bindable 构造）→ 在缓存/构造处统一走升箱；
  - (b) `RxBuiltInMethodImpl.select` 对单列投影直接返回标量流而 `getElementType()` 谎报，使 `asObjectArray` 的 `isArray()` 守卫误判跳过 → 修守卫或修 codegen 的元素类型自述；
  - (c) janino 生成的 bridge `Object apply(Object)` 里 `(Object[]) row` 对已投影 String 二次强转 → 修生成模板的单列投影形态。
- **验收回投**：修复后 `ConnectionSmokeTest` 跑绿，随即跑 `bash ci/run-example.sh` 全量（xa 除外）产出 example e2e 首轮逐类 pass/fail 清单，回投 M1 达标表"连接认证"列与看板 I-CI-3。

### 2.2 与 I-DEBT-7 的边界
挂点两类（`LocalXaSavepointTest`、`SqlFunctionTest`）= I-DEBT-7，与本 CCE 无证据同根，**不在本债项处置**；若修复后全量清单显示二者转好，仅登记观察、不扩面。

## 3. 非目标
- 不改 `ShowDatabasesHanlder` 的 SQL 改写规则（`select SCHEMA_NAME ...`）——改写正确，问题在行契约。
- 不改 `SCHEMATA_TABLE_HANDLER` 等可视化表的数据产出（F6 证明其合规）。
- 不动 server shade 配置、不迁包名、不碰孪生副本（各自债项）。
- 不在本债项消解 I-DEBT-5（planner `ColumnMappingTest`）与 I-DEBT-7（挂点）。

## 4. Review Focus（spec 隐含、任务测试未必覆盖，交付评审逐条查）
1. **异常不再被吞**：任一 e2e 查询在服务端抛运行时异常时，`i6-server.log` 必须出现带栈的 ERROR 行（本债项 F10 的回归断言）；日志门控在 logger 关闭时不产生噪声、更不裸 print 到 stdout。
2. **升箱契约对"谎报元素类型"的鲁棒性**：`asObjectArray` 依赖 `getElementType()` 自述；若某 codegen 形态自述与真实流元素类型不符（F9 疑点之一），须有测试钉死"以运行时真实元素类型为准升箱"或"自述不符即失败可见"，而非静默漏箱。
3. **单列 vs 多列、标量 vs 数组投影矩阵**：至少覆盖 ①单列标量（`SHOW DATABASES`）②多列（`SELECT *`）③常量单列（`SELECT 1`）三形态各自的行元素运行时类型，防止修 A 形态引入 B 形态回归。
4. **plan 缓存一致性**：若根因是缓存的 `PrepareExecutor` 绕过升箱（候选 a），修复须覆盖缓存命中与首次构建两条来路，不能只修未命中分支造成"首跑绿、二跑红"式假象。
5. **CI 可解析**：新增日志/守卫改动不得引入 example 之外模块的编译断裂；`mvn -B -o test-compile -pl example` 与 `install -pl server -am` 保持绿。

## 5. 风险与止损
- **确切强转点仍未知（最大风险）**：栈拿到前任何"改一行"都是赌。止损 = Task 1 必须先拿到服务端栈；拿不到（如异步吞链无栈）则升级为在消费边界加一次性 `try/catch(Throwable) 记录 + 重抛` 受控探针再复现，仍属诊断面。
- **修复触 codegen 模板**（候选 c）面较大：若定位到 janino 模板，先以最小守卫/后处理升箱替代，模板级重构另立债项。
- 单文件改动 >20 行或触及断言语义 → 停手挂账待裁定（预批硬停三：棘轮止损）。
- 撞不可逆 / merge / push / 路径全蒙 → 预批四条硬停照旧。
- 验收门只认当轮真实输出（Evidenced-Done）；ConnectionSmokeTest 未跑绿不得记 Evidenced-Done。
