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

### 2b. 第 0 步诊断结论（**推翻本 spec 初稿假设**，落地前已执行）

追加离线诊断（打印 codegen 期 RexCall 算子类型 + 直查 `RexImpTable.INSTANCE.get(...)`）实证：

- `select DAYOFWEEK(DATA_TYPE) from information_schema.parameters` 到 codegen 的算子是 **`org.apache.calcite.sql.fun.SqlDatePartFunction`（name=DAYOFWEEK），`isMycatSD=false`**——是 Calcite **标准**算子，不是 Mycat 的 `DayOfWeekFunction`，也不是 Unresolved。
- `RexImpTable.INSTANCE.get(Mycat DayOfWeekFunction.INSTANCE)` = **非空**（返回 `RexImpTable$2@...`）→ **Mycat 注册的标量实例本就可通过 fork 的 `instanceof MycatSqlDefinedFunction` 分派落码**。
- `RexImpTable.INSTANCE.get(Std SqlStdOperatorTable.DAYOFWEEK)` = **null** → 标准 `SqlDatePartFunction` 在本 fork 的 RexImpTable **从未登记实现者** → `cannot translate call`。
- 且 `MycatCalciteMySqlNodeVisitor` **确有 `case "DAYOFWEEK"`→`DayOfWeekFunction.INSTANCE`（L1828）**——常规分片 SQL 走 drds parser 用 Mycat 实例（随即被下推，本地不触达）；而 `information_schema` 走 **Calcite parser**，解析到标准 `SqlStdOperatorTable.DAYOFWEEK`（L2253 `SqlDatePartFunction`），触发崩溃。

**⇒ 真根因不是"注册标量函数未接入 codegen"（它们其实能接入），而是"i_s/Calcite-parser 路径把 MySQL 函数名解析成本 fork 无 codegen 的标准同名算子，遮蔽了 Mycat 自己的可落码实现"。** 修法随之改变（见 §5 订正）。

## 3. 根因链（第 0 步诊断后订正版）

崩溃有**两条不同解析路径**，只有 i_s 路径触雷：

1. **常规分片 SQL → drds parser（`MycatCalciteMySqlNodeVisitor`）**：其 `switch(methodName)` **已有 `case "DAYOFWEEK"`（L1828）→ `DayOfWeekFunction.INSTANCE`**（Mycat 自己的 `MycatDateFunction`/`MycatSqlDefinedFunction`）。而 `get(Mycat 实例)` 非空→本可落码；但分片场景整投影被**下推**交后端，Mycat 本地实现从不执行（故周日公式 bug 至今不可观测）。
2. **`information_schema` 查询 → Calcite parser**：不走 drds visitor，`DAYOFWEEK` 解析到**标准 `SqlStdOperatorTable.DAYOFWEEK`（L2253，`SqlDatePartFunction`）**。
3. **codegen 查找**：`RexToLixTranslator.visitCall`（L1511-1514）先经 `MycatCalciteRuntime.get().remapOperator(operator)`（中立 seam，默认恒等）再 `RexImpTable.INSTANCE.get(...)`。标准 `SqlDatePartFunction.DAYOFWEEK`：① 不在 `mycatCustomRegistry`（仅 4 枚手工 `registerMycatFunction`），② 非 `MycatSqlDefinedFunction`，③ 非 `SqlUserDefinedFunction`，④ fork `map` 亦未登记→`get()`=**null**（本轮实证）→ `cannot translate call` → `MycatEnumerableRelImplementor:57` 裹成 `Unable to implement`。

**净结论**：Mycat 注册标量其实**能**通过 `instanceof MycatSqlDefinedFunction` 分派落码（已证 `get(Mycat 实例)` 非空）；真正的缺口是 **i_s/Calcite-parser 选中的标准同名算子在本 fork 无 codegen 实现者**，把 Mycat 可用实现遮蔽了。

## 4. 与两枚已收口手法的对比（为何都不通用）

- **I-DEBT-50 IF→CASE**：把函数在 AST 层改写成"已可落码的等价算子"。**前提=存在语义等价的已实现算子**；DAYOFWEEK 这类无等价 CASE/标准算子可写，不适用。逐例、不可扩展。
- **I-DEBT-54-D1 恒空折叠**：子树全叶子为静态恒空仿表时折成空 `Values`，**绕过**投影 codegen。仅对"结果必为空"的 i_s 表忠实成立；对真会返数据或无表本地求值不适用，且不实现函数本身。
- 两者是**规避**，非**接入**。本专轮要的是**通用接入**（补 §3 缺的那一环），使散落窄修可被统一取代（存量窄修保留不回归）。

## 5. 候选修法与推荐

### 候选 A（推荐·诊断后重定位）：经 `remapOperator` seam 把标准同名算子重映射到 Mycat 实例
真根是 i_s/Calcite-parser 选中的标准 `SqlDatePartFunction`（及其他无 codegen 同名算子）遮蔽 Mycat 实现。`RexToLixTranslator.visitCall` **已有 `remapOperator` 中立 seam**（L1512，`<=>`→`StrictEqualFunction` 先例），hbt 侧实现为 `MycatCalciteRuntimeHooks.remapOperator`。
- **做法**：在 `MycatCalciteRuntimeHooks.remapOperator` 里，对"标准算子但本 fork 无 codegen、且 Mycat 有可用实例"的函数名（首先 `DAYOFWEEK`）映射到对应 Mycat `MycatSqlDefinedFunction` 实例（`get(Mycat 实例)` 已证非空→自然落码）。只改查表用 operator，不改算子本身。
- **优点**：**blast 最小**（复用现有 seam，仅按名单重定向特定算子，其余恒等不变）；直接走到 Mycat 自己的语义实现。
- **代价**：需处理 **java.time 形参适配**（remap 后以原 call 传入，operand 是 `CAST($6):TIMESTAMP`，而 `dayOfWeek(LocalDate)` 期 LocalDate，需验证 ScalarFunctionImpl 生成的码能接住 java.sql.Timestamp/需否插转，与 I-DEBT-12/23e 相邻）；接入后周日公式 bug 才首次可观测→同轮兼修公式。

### 候选 A'（备选）：向 `mycatCustomRegistry` 批量 `registerMycatFunction` 标准算子
以 `registerMycatFunction(SqlStdOperatorTable.DAYOFWEEK, <implementor>)` 给标准算子直接挂实现者。缺点：需为每个标准算子手写 implementor 且要保证语义与 MySQL 一致（Calcite DOW 与 MySQL Sun=1 可能不同），不如 A 直接复用 Mycat 实例。

### 候选 B：逐函数 AST 改写（续 I-DEBT-50 路子）
每个缺失标量各写 `case`。仅在"有等价已实现算子"时可行，DAYOFWEEK 无。不可扩展，制造更多散点。**不推荐**。

### 候选 C：维持现状（下推遮蔽 + 窄修 + 挂账）
普通客户端多走下推，本地崩面窄。**但**招牌判据下"无表 SELECT/纯本地投影/未来 connector 元数据深面"仍会触雷，且 I-DEBT-32 无法真关。**不满足北极星无缝目标**。

**推荐 A（seam 重映射）**，理由：第 0 步已证真根是"i_s/Calcite-parser 选中无 codegen 的标准同名算子遮蔽 Mycat 实现"，而非 Mycat 实例不可落码；`remapOperator` seam 是**现成、低 blast、有先例**（`<=>`）的正确落点。分片/下推面已绿，增量风险集中在本地路径，可被离线规划器锁 + 差分对拍完全兜住。落地必须与 §7 三层测试同轮。

## 6. 设计（推荐方向·高层）

1. **第 0 步（诊断）——✅ 已执行（2026-10-19）**：结论见 §2b/§3——真根=i_s/Calcite-parser 选中标准 `SqlDatePartFunction.DAYOFWEEK`（`get()`=null）遮蔽 Mycat 实例（`get(Mycat 实例)`≠null）；故落点=**重映射标准同名算子→Mycat 实例**（非"回绑解析链"也非"批量注册"）。
2. **seam 重映射**：在 `MycatCalciteRuntimeHooks.remapOperator(op)` 新增分支：当 `op` 属于"本 fork 无 codegen 且 Mycat 有可用实例"的标准同名算子集（首批 `SqlStdOperatorTable.DAYOFWEEK`→`DayOfWeekFunction.INSTANCE`）时返回 Mycat 实例，其余保持恒等。映射表可逐步扩充覆盖整族（YEAR/WEEK/MONTH… 逐个先验证下推/本地行为再纳入）。
3. **java.time 适配**：remap 后以原 call（operand `CAST(...):TIMESTAMP`）传入，需验证 `ScalarFunctionImpl` 生成的码能接住实参 Java 型（java.sql.Timestamp vs LocalDate）；不适配则在 implementor 前插转换，复用 I-DEBT-12/23e 口径。
4. **DAYOFWEEK 公式纠偏**：`DayOfWeekFunction.dayOfWeek` 由 `getValue()+1` 改 `getValue()%7+1`（Sun 7→1，Mon 1→2…Sat 6→7），**接入本地 codegen 后**才随附落此改（届时可观测、非伪绿）。兄弟 `WeekDayFunction`（`getValue()-1`）本就正确，仅回归锁覆盖。

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
