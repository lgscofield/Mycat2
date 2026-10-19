# Mycat 注册标量函数·本地 codegen 接入同族专轮 设计

> 状态：**设计提案（spec-only）**。本轮只勘察+出设计，**不改产品码**。落地属 fork 码跨切改动，须评审后另行授权。
> 立档日期：2026-10-19。触发：A/C 层逐个 triage 后，用户批准起"标量函数本地 codegen 同族专轮"。

## 1. 背景与目标（对齐北极星）

北极星：客户端眼中无缝替代 MySQL 5.7/8.0/8.4/9.x。真 MySQL 对**无法下推/无表/纯本地**的查询会在代理侧就地算标量函数；Mycat 目前对**注册标量函数族在本地 enumerable codegen 阶段普遍落不了码**，凡交本地算即抛 `Unable to implement MycatProject`（`cannot translate call`）。

同族已知触点（均须当前证据，见 §2）：
- **I-DEBT-32（本 spec 主目标）**：`DAYOFWEEK` 本地求值无实现（DEBT-QUEUE L116）。
- **I-DEBT-50（已收口·先例）**：`IF()` 曾同族崩溃，用 AST 层改写为 searched CASE 窄修（L109）。
- **I-DEBT-54-D1（已收口·先例）**：`JSON_EXTRACT` over `i_s.COLUMN_STATISTICS` 同族崩溃，用"恒空仿表折叠"绕过 codegen（本目录 `2026-10-17-is-empty-visual-table-fastpath-design.md`）。

三枚共同根＝**"Mycat 注册的标量函数未接入 enumerable 本地 codegen"**。D1/50 都是**逐例规避**（改写/折叠），未通用解决该根；D1 明确"不实现可 codegen 的 JSON_EXTRACT 算子"（YAGNI），故根仍在。本专轮要**系统性收口该根**，替代散落窄修。

**关键收益**：一旦本地 codegen 打通，`DAYOFWEEK` 的**周日公式 bug（Sun→8）才第一次变得可观测**——此前因下推遮蔽，公式修复无 e2e 效果（上一轮据此撤回了"半修"）。故**公式纠偏应与本接入捆绑**落地，届时才非伪绿。

## 2. 本轮实证发现（2026-10-19 探针，全部当前证据）

复现配方（离线规划器 `mode=local`，harness 同 `internalexample/.../ProcedureMetaIfCodegenTest`；其 `planAndCodegen` 内 `DrdsExecutorCompiler.getCodeExecuterContext` L317 触发 `implementHybridRoot`＝真 codegen）：

| 探针 SQL | 结果 | 含义 |
|---|---|---|
| `select DAYOFWEEK(DATA_TYPE) from information_schema.parameters` | **THREW** `IllegalStateException: Unable to implement MycatProject(DAYOFWEEK(DATA_TYPE)=[DAYOFWEEK(CAST($6):TIMESTAMP(0))])`，SUP=`RuntimeException: cannot translate call DAYOFWEEK($t16)` | 本地强制投影 → **Mode A：cannot translate call** |
| `select DAYOFWEEK(traveldate) from db1.travelrecord where id=1` | `CODEGEN_OK plan=MycatView(...)`（**无 MycatProject**） | 分片表整投影**全下推**，codegen 未见本地函数 |
| `select DAYOFWEEK(cast('2020-01-05' as date)) from db1.travelrecord where id=1` | `CODEGEN_OK plan=MycatView(...)` | 同上，下推遮蔽 |
| `select group_concat(DAYOFWEEK(traveldate)) from db1.travelrecord` | `CODEGEN_OK plan=MycatView(...)` | 聚合+标量仍可下推 |
| `select WEEKDAY(traveldate) ...` | `CODEGEN_OK plan=MycatView(...)` | 兄弟函数同样下推遮蔽 |

**坐实两点**：
1. **下推全遮蔽本地公式**：可下推场景交后端算，返回正确值（Sun=1…Sat=7），Mycat 本地实现**永不被触达**——解释了"方法级单测显示公式错、但活库探针显示正确"的表面矛盾（非矛盾，是路径不同）。
2. **本地路径确为 Mode A `cannot translate call`**（非我最初静态臆测的 ClassCastException）。探针修正了推断，符合"不信既有记录/须实证"。

## 3. 根因链（已凭当前代码逐环锚定）

1. **解析/建算子**：`MycatCalciteMySqlNodeVisitor` 的函数名→算子映射 `operators` 表（L48-61）只灌 `SqlStdOperatorTable`（Calcite 标准算子）。MySQL 专有标量（DAYOFWEEK 等）不在其中，`func(hash)` 返 null。
2. **落 default**：大 `switch` 无 `case "DAYOFWEEK"` → **L2034-2045 default 分支**建 `new SqlUnresolvedFunction(DAYOFWEEK, USER_DEFINED_FUNCTION)`。这与 I-DEBT-50 `case "IF"`（L2015 注释自述"此前落 default 被建成 SqlUnresolvedFunction"）**完全同构**。
3. **校验/落 Rex**：算子表虽在 `MycatCalciteSupport` L408 注册了 `DayOfWeekFunction.INSTANCE`（供列名/校验），但**到达 codegen 的 Rex 算子并非可分派的 Mycat 实例形态**。
4. **codegen 查找**：`RexToLixTranslator.visitCall`（L1511-1514）调 `RexImpTable.INSTANCE.get(operator)`；该 `get()`（fork，L1485-1525）有三发现路：① `mycatCustomRegistry`（L1487，仅 4 枚 `registerMycatFunction` 的日期函数：DateAdd/DateSub/Extract/AddTime，见 `MycatCalciteSupport` L310-313）② `instanceof MycatSqlDefinedFunction`（L1492）③ `instanceof SqlUserDefinedFunction`（L1496）。返回 null → `RexToLixTranslator:1514` 抛 `cannot translate call` → `MycatEnumerableRelImplementor:57`/`StreamMycatEnumerableRelImplementor:34` 裹成 `Unable to implement`。

**残留待实现期首诊确认的子因（承重）**：为何 ②/③ 分派支没兜住——即本地路径解析出的 DAYOFWEEK 算子确切类型（`SqlUnresolvedFunction` 未回绑？回绑成非 Mycat/非 UserDefined 的普通 `SqlFunction`？）。§2 探针 + 一句 `call.getOperator().getClass()` 打印即可钉死；它决定修法落点是"回绑 Mycat 实例"还是"补注册 implementor"。**本 spec 不臆断，列为落地第 0 步。**

## 4. 与两枚已收口手法的对比（为何都不通用）

- **I-DEBT-50 IF→CASE**：把函数在 AST 层改写成"已可落码的等价算子"。**前提=存在语义等价的已实现算子**；DAYOFWEEK 这类无等价 CASE/标准算子可写，不适用。逐例、不可扩展。
- **I-DEBT-54-D1 恒空折叠**：子树全叶子为静态恒空仿表时折成空 `Values`，**绕过**投影 codegen。仅对"结果必为空"的 i_s 表忠实成立；对真会返数据或无表本地求值不适用，且不实现函数本身。
- 两者是**规避**，非**接入**。本专轮要的是**通用接入**（补 §3 缺的那一环），使散落窄修可被统一取代（存量窄修保留不回归）。

## 5. 候选修法与推荐

### 候选 A（推荐）：系统性把注册标量接入 enumerable codegen
统一让每个 Mycat 注册标量函数在本地路径解析出其 `MycatSqlDefinedFunction` 实例（其 `function` 为 `ScalarFunctionImpl`，**已 implements ImplementableFunction**——本轮实证，`calcite-core/.../schema/impl/ScalarFunctionImpl.java:45-46`），从而命中 `get()` ② 分派支，反射落到其 Java 方法。
- 落点（据 §3 首诊结果二选一或并用）：(a) `MycatCalciteMySqlNodeVisitor` 以算子表批量回绑（把 `operators`/default 从"仅 SqlStdOperatorTable"扩到含 Mycat 注册标量表），或 (b) 在 `MycatCalciteSupport` 静态块对注册标量**批量 `registerMycatFunction`**（把现有 4 枚手工泛化为遍历注册）。
- 优点：一处根治整族，DAYOFWEEK/JSON_EXTRACT/后续标量统一可本地算；与 fork 既有 ② 分派设计契合（本就为 Mycat 函数加的）。
- 代价：**blast 大**（触所有标量函数解析/注册）；须补 **java.time 形参适配**（`dayOfWeek(LocalDate)` 期望 LocalDate，codegen 喂列的 Java 型须对齐，与 I-DEBT-12/23e 相邻）；须全族差分对拍防伪绿。

### 候选 B：逐函数 AST 改写（续 I-DEBT-50 路子）
每个缺失标量各写 `case`。仅在"有等价已实现算子"时可行，DAYOFWEEK 无。不可扩展，制造更多散点。**不推荐**。

### 候选 C：维持现状（下推遮蔽 + 窄修 + 挂账）
普通客户端多走下推，本地崩面窄。**但**招牌判据下"无表 SELECT/纯本地投影/未来 connector 元数据深面"仍会触雷，且 I-DEBT-32 无法真关。**不满足北极星无缝目标**。

**推荐 A**，理由：三例已证根相同，A 是唯一能"关根"而非"绕例"的方案；分片/下推面已绿，增量风险集中在本地路径，可被离线规划器锁 + 差分对拍完全兜住。落地必须与 §7 三层测试同轮。

## 6. 设计（推荐方向·高层，落地期细化）

1. **第 0 步（诊断）**：扩 §2 探针打印 `call.getOperator().getClass()`，钉死 §3 子因 → 决定回绑 vs 补注册。
2. **通用接入**：按候选 A 落点，使注册标量在本地路径可得其 Mycat 实例/已注册 implementor；优先"遍历注册表批量 `registerMycatFunction`"以最小化对解析链的侵入。
3. **java.time 适配**：对期望 `LocalDate/LocalDateTime/Duration` 的标量，核对 codegen 实参 Java 型（列/字面量/CAST 三态），必要时在 implementor 前插转换，复用 I-DEBT-12/23e 既有适配口径。
4. **DAYOFWEEK 公式纠偏**：`DayOfWeekFunction.dayOfWeek` 由 `getValue()+1` 改 `getValue()%7+1`（Sun 7→1，Mon 1→2…Sat 6→7），**接入 codegen 后**才随附落此改（届时可观测、非伪绿）。兄弟 `WeekDayFunction`（`getValue()-1`）本就正确，仅回归锁覆盖。

## 7. 测试策略（三层，防伪绿）

1. **离线规划器锁**（internalexample `mode=local`，即 §2 harness）：`select DAYOFWEEK(DATA_TYPE) from information_schema.parameters` 由 RED（`Unable to implement`）转 GREEN（出码成功、列头正确）。同法为族内代表（DAYOFWEEK/WEEKDAY/一个 string/一个 json）各立锁。
2. **方法级差分对拍**（example `PushDown*`/`SqlFunctionTest` 扩，用 `checkValue` Mycat-8066 vs 原生 3306）：**关键**须构造**强制本地**场景（i_s 仿表投影/无表且不可下推形态）方能触达本地路径，否则又被下推遮蔽成假绿；显式覆盖 Sunday 边界日（既有测试用 2017-06-15 周四，不覆盖 Sun）。
3. **端到端金标准**：真二进制/无表本地求值经活服务端 8066（persona MYSQL_5_7/8_0），逐值对拍原生。本地活库记 runtime-evidenced（本地✅/CI⏳）。

## 8. 边界、blast 与风险

- **fork 码跨切**：§5-A 落点可能动解析/注册公共链，波及全标量函数；须小步 + 每步回归。落地阶段属高风险，**不在本 spec 执行**。
- **伪绿防护**：教训——只看"下推路径返对值"会误判函数已工作；测试**必须显式走本地不可下推形态**。
- **类型适配耦合**：与 I-DEBT-12/23e（java.time 内部表示）相邻，标量形参 Java 型须先对齐再谈公式。
- **不回归既有窄修**：I-DEBT-50/54-D1 的改写/折叠规则保留；本接入是"补根"，二者共存。

## 9. 关联债务（DEBT-QUEUE 交叉引用）

- **I-DEBT-32**（L116）：本 spec 主目标；接入后连同公式纠偏一并真关。
- **I-DEBT-50**（L109）/ **I-DEBT-54-D1**（L112）：同族已收口先例，作设计对照（规避 vs 接入）。
- **I-DEBT-12 / I-DEBT-23e**（L117）：java.time 型适配相邻，本 spec §6.3 复用其口径。
- **I-DEBT-39·残**（L115）：值语义（除零 NULL）债，与"能否本地落码"正交，本 spec 不触碰。
- **I-DEBT-54 D2b**（sql_mode ONLY_FULL_GROUP_BY 遵从）：Calcite 关系代数本质约束，跨切另论，不在本 spec。

## 10. 不做的事（YAGNI）

- 不改认证/TLS/connector 矩阵（属其他里程碑）。
- 不动 D2b（sql_mode 语义级 fork 手术，性价比低，维持挂账）。
- 不 push、不改 CI workflow（用户已定 push 暂不）。
- 不在本 spec 落任何产品码。

## 11. 授权边界（本 spec 之外须拍板）

- **起 plan / 动产品码落地**＝fork 码跨切改动，属高风险，须用户评审本 spec 后明确授权，方进入实现（建议走 `writing-plans`→TDD，逐函数小步）。
- 本轮交付＝此设计文档（本地、可逆），无产品码改动。

## 12. 环境备忘

- JDK21：`D:\Program Files\BellSoft\LibericaJDK-21-Full`（须显式 `JAVA_HOME`）。
- 离线探针：`mvn -o -pl internalexample -am test "-Dtest=<Probe>" "-Dsurefire.failIfNoSpecifiedTests=false"`（`-D` 参数须加引号，防 PowerShell 拆坏；`-am` 拉入 hbt 否则 `io.mycat.calcite` 包缺失）。
- 后端容器：`my57-3306`(3306)/`m1-mysql-b`(3307)，root/123456；Mycat 8066。
- PowerShell 无 grep/head，用 `Select-String`/`Select-Object`；中文 commit 用 `git commit -F <file>` + `[Console]::OutputEncoding=UTF8` 校验。
