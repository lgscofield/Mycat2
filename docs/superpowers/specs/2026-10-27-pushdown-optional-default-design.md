# 下推可选参数被补 DEFAULT 致 1064 — 设计 Spec（I-DEBT-②·架构级）

日期：2026-10-27　状态：**根因+缝位已活体坐实·fix 策略 A 已拍板，待进 RED→GREEN**

> 订正说明：某轮曾以离线探针误判「下推无 DEFAULT、真凶是裸 `?`」并一度作废本 Spec。**该作废不成立**——
> 活体 `explain` 与直连真库差分复现（见 §1bis）铁证下推 SQL **确含 `, DEFAULT)`**，本 Spec 的 DEFAULT 叙事自始正确。

## 1. 现象（活体取证，非旧日志）

靶：`PushDownFunctionShardingTableTest#testTimeFunction`（现跑复现，`target/i59-timefunc.log`）。
语句 `SELECT DATE_FORMAT("2017-06-15", "%Y") from db1.travelrecord` 下推到物理分库，Mycat 发出：

```sql
SELECT DATE_FORMAT(?, '%Y', DEFAULT) AS `DATE_FORMAT(?,'%Y')` FROM db1_1.travelrecord_2 ...
```

后端 MySQL 报 `1064 ... near ') AS \`DATE_FORMAT(?,'%Y')\``。同法（Mycat 8066 MYSQL_8_0 + m80-a/b 真库）：
`testTimeFunction` 前 12 个 case（ADDDATE/ADDTIME/DATE/DATEDIFF/DATE_ADD/DATE_SUB…）**全过**，
唯 268 的 DATE_FORMAT 独红。

## 1bis. 本轮活体复核（三重铁证，2026-10-27·代理 8066 + m80-a@3306）

- **A｜代理 `explain`** 打出的实际下推 SQL 就是 `SELECT DATE_FORMAT(?, '%Y', DEFAULT) AS \`DATE_FORMAT(?,'%Y')\` FROM db1_0.travelrecord_0 ... union all ...`（多分片 fan-out 走 codegen 路），**DEFAULT 确凿在表达式实参位**。
- **B｜直连真库**（非预编译）`SELECT DATE_FORMAT('2017-06-15', '%Y', DEFAULT)` → **ERROR 1064 near ')'**，与代理活红签名一致。
- **C｜直连真库** 2 参 `SELECT DATE_FORMAT('2017-06-15', '%Y')` → 正常返回 `2017`。

⇒ 唯一死因 = `DEFAULT` 实参；`?` 是合法预编译占位符（能正常绑参）、非根因（别名里的 `?` 只是反引号装饰文本）。

## 2. 前提修正（推翻看板原措辞）

看板/记忆把本债框成「下推**隐式列别名裸 `?`** 不回填 → 1064」。**取证证伪**：
- `?` 下推走**预编译绑参**，带 `?` 的隐式别名（如 `ADDDATE(?,INTERVAL10DAY)`）能正常执行，非 1064 根因。
- 真凶 = **表达式被补了一个第三操作数 `DEFAULT`**：`DATE_FORMAT(?, '%Y', DEFAULT)`。MySQL DATE_FORMAT 仅 2 参，
  且 `DEFAULT` 关键字在函数实参位非法 → 1064。别名那条取的是**原始 2 参文本**（无 DEFAULT），与表达式渲染分叉。

## 3. 根因链（file:line）

1. `DateFormatFunction.java:54-75`——Java 侧两个 `dateFormat` 重载：2 参，及 3 参带
   `@Parameter(name="locale", optional=true)`（locale 仅供本地求值默认 Locale.US，**不应进下推 SQL**）。
2. `DateFormatFunction.java:46`——`ScalarFunctionImpl.create(DateFormatFunction.class,"dateFormat")` 取 **3 参签名**
   → `function.getParameters()` 末位 locale 标记 `isOptional()`。
3. `MycatSqlDefinedFunction.java:123-125`（`GetOperandTypes.invoke`）——据此生成 `SqlOperandMetadata`，
   把**末位声明为可选操作数**（`i -> ...isOptional()`）。
4. **DEFAULT 注入与 emit 的确切缝位（本轮已钉死）**：
   - 注入：`calcite-core/.../sql/SqlCallBinding.java:171-177`（`operands()`）——对 `isFixedParameters()` 且
     `isOptional(i)` 的尾部，`while (list.size() < range.getMax())` 把省略的可选参补成 `DefaultCallHolder.DEFAULT_CALL`
     （`:69-70` = `SqlStdOperatorTable.DEFAULT.createCall`）；该补全列表经 sql→rel 逃入 RexCall（deparse 端严格照搬操作数）。
   - emit：`hbt/.../calcite/MycatSqlDialect.java:66-68`（`unparseCall`）——对 `MycatSqlDefinedFunction` 直走
     `operator.unparse(...)` → `SqlUtil.unparseFunctionSyntax` 逐 operand 渲染，DEFAULT 操作数被原样 emit 成 `, DEFAULT`。
   - **策略 A 护栏落点 = `MycatSqlDialect.unparseCall` L66-68**：在委派 `operator.unparse` 前，对 Mycat 自定义
     函数剔除尾部连续 DEFAULT 操作数（只作用于下推 deparse，不碰本地 codegen 求值与类型推断）。
   （执行链：`MycatView.apply`→`sqlTemplateApply` 保留 `?` →`AsyncMycatDataContextImpl.java:276-278`
   `runQuery(sqlString.getSql(), extractParams(...))` 发后端；`?` 绑参本身正确。）

**反证**：`DateAddFunction` 等**无可选参**的函数下推不出 DEFAULT → 通过。

## 4. Blast radius（同类缺陷）

带 `optional` 末位参的 Mycat 自定义函数共 **8 个**：DateFormatFunction、SysDateFunction、WeekFunction(×2)、
UnixTimestampFunction、UtcTimeFunction、UtcTimestampFunction、YearWeekFunction、CurTimeFunction。
凡「带可选参 **且可下推**」者皆中招（Week/YearWeek 的 mode、DATE_FORMAT 的 locale 最现实）。
→ 修复宜做**通用护栏**，非逐函数。

## 5. 修复策略（三选一，**待拍板**）

| 方案 | 落点 | 优点 | 代价/风险 |
|---|---|---|---|
| **A 通用 deparse 护栏（推荐）** | 下推 rel→sql unparse 处：Mycat 自定义函数调用中，**被省略的可选尾操作数不得 emit DEFAULT**，直接截断只发实参 | 一次覆盖全 8 函数族；语义正确（后端不该收到它不知的可选参） | 需精确定位 unparse 缝（见 §6 首任务）；须确认只作用于「Mycat 省略的可选参」不误伤真实 DEFAULT 用法 |
| B 源头不补 | sql→rel 处不把可选参物化进 RexCall | 最干净 | Calcite 校验/物化路径改动面大、易牵类型推断 |
| C 逐函数窄修 | 给 DATE_FORMAT 等注册 2 参签名、去 optional 重载 | 最小爆炸半径、可控 | 8 函数各改一遍；同类新函数复发 |

推荐 **A**：与 I-DEBT-23（同文件 `checkOperandTypes` 已为 Mycat 函数做 operand 适配）同源同族，缝位自然。

## 6. 实施计划（TDD·RED 先行）

- **Task 0｜钉缝（已完成）**：活体 `explain`（§1bis A）+ 直连真库差分（B/C）已铁证 DEFAULT 在 rel→sql deparse emit；
  代码缝位钉到 `SqlCallBinding.operands():171-177`（注入）与 `MycatSqlDialect.unparseCall:66-68`（emit）。**A 的落点已锁定为后者。**
- **Task 1｜RED**：新增/复用活体差分锁——断言 `DATE_FORMAT` 下推 SQL **不含 `, DEFAULT`** 且经 Mycat 返回值 == 直连真库
  （testTimeFunction:268 现红即天然红锁，先落一个专项精确锁再谈全局）。目视 RED。
- **Task 2｜GREEN**：按 §5-A 在 deparse 缝截断被省略的可选尾操作数。复跑转绿。
- **Task 3｜同类回归护栏**：为 Week/YearWeek（mode 可选）补对拍格，证通用护栏覆盖全族。
- **验证门**：PushDownFunctionShardingTableTest 全绿 + SqlFunctionTest + CrudResultMatrix + hbt/server 套绿；
  活体差分对拍直连一致。**待 CI 全矩阵认证后升 Evidenced-Done**（同 I-DEBT-27 口径，防伪绿）。

## 7. 边界与挂账

- 本 Spec **不改运行期求值**（本地 `MycatProject` codegen 走 Java 方法、locale 默认，本就正确）。
- 别名与表达式的渲染分叉（别名取原始文本、表达式取参数化文本）属既有设计，本债只在「不 emit 多余 DEFAULT」收口；
  别名精确性另议（若需要）。
- pushdown 走预编译（`?` 绑参）为既定通路，非本债范围。
