# I-DEBT-75 设计：跨片下推整数除法 DIV 的方言渲染修复

- 状态：✅ **Evidenced（GREEN 离线锁 + 活体端到端对拍全等）**（2026-11-02 收口）
- 日期：2026-11-02
- 分类：C 层·引擎/codegen 方言渲染债（喂 M2 无缝 SELECT）
- 关联：挡 I-DEBT-69「多目标 SELECT 告警拼接」活体格（本债修通后解锁该活体余格）

## 1. 断链取证（当前代码 file:line + 活体铁证）

### 1.1 算子映射
druid 的 `DIV` 经 `hbt/.../MycatCalciteMySqlNodeVisitor.java:1179-1181`
`case DIV: operator = SqlStdOperatorTable.DIVIDE_INTEGER;` 映射到 Calcite 内部整数除算子。

### 1.2 算子形态
`calcite-core/.../sql/fun/SqlStdOperatorTable.java:353-361`
`DIVIDE_INTEGER = new SqlBinaryOperator("/INT", SqlKind.DIVIDE, 60, ...)`——
**算子名字面量是 `"/INT"`**。`SqlBinaryOperator` 默认 unparse 走
`SqlUtil.unparseBinarySyntax`（`calcite-core/.../sql/SqlUtil.java:436-455`），
其中 `writer.sep(operator.getName())`（L451）按算子名逐字发出，即渲染成中缀 `a /INT b`。

### 1.3 下推出口用 MycatSqlDialect
`hbt/.../MycatCalciteSupport.java:944/958` `return MycatSqlDialect.DEFAULT;`——
跨片 rel→sql 重渲染（`plan.specificSql` 的 deparse）经
`hbt/.../MycatSqlDialect.unparseCall`（L67）。该方言未对 DIVIDE_INTEGER 作任何特化，
`super.unparseCall`（L134）落回 `SqlBinaryOperator` 默认 → 原样 emit `/INT`。

### 1.4 活体铁证（scratch/proxy-t75-out.txt·I-DEBT-74 刀副产物）
- T1 同构 UNION（无 DIV）：绿；
- **T2 `select id, 1 div (id-1) from shg where id=1`（单片）：绿**，q=NULL
  （单值路由单片、原 SQL 文本原样下推，后端 MySQL 原生 `1 DIV 0`=NULL）；
- **T3 跨片 UNION ALL 两分支各含 DIV：红 ERROR 1064**
  `near 'INT (\`shg\`.\`id\` - 1)) AS \`q\``——跨片 fan-out 强制 rel→sql 重渲染，`/INT` 出码，
  MySQL 把 `/` 后的 `INT` 当保留字/函数解析 → 语法错；
- T4 跨片 UNION（`id+0`，无 DIV）：绿。
  矩阵钉界：只有「跨片 + DIV 投影」组合命中重渲染路径 → 红。

## 2. 契约裁定

**正解 = 下推出口把 DIVIDE_INTEGER 渲染成 MySQL 原生中缀 `DIV`**。依据：
1. MySQL `expr DIV expr` 是整数除法原生算子，**向零截断**（`-3 DIV 2 = -1`），除零返 NULL——
   与用户原写 `1 div (id-1)` 的语义**全等**（北极星=客户端眼中无缝，round-trip 回同一原生算子最保真）。
2. **否决 `FLOOR(a/b)`**：`FLOOR(-3/2) = FLOOR(-1.5) = -2 ≠ MySQL -3 DIV 2 = -1`，负数语义不等价，
   且 `a/b` 在 MySQL 是十进制除（结果 decimal），套 FLOOR 与整除口径分叉——属把错误语义钉成"正确"，违背宁挂账不伪绿。
3. **否决 `CAST(a/b AS SIGNED)`**：MySQL `a/b` 先做 decimal 除，CAST 只是截断显示，负数同样向零但与 FLOOR 同族分歧，且多写 CAST 噪声。
4. 检测谓词按**算子名 `"/INT"`**（同时覆盖 `DIVIDE_INTEGER` 与同名的 `CHECKED_DIVIDE_INTEGER`），
   不用 `==SqlStdOperatorTable.DIVIDE_INTEGER` 单例判（防规划期算子实例被复制/降型漏判）。

**落点=单一 chokepoint**：`MycatSqlDialect.unparseCall`，在既有 `MycatSqlDefinedFunction` 分支后、
`super.unparseCall` 前新增 DIVIDE 分支，镜像 `SqlUtil.unparseBinarySyntax` 但 sep 字面量写 `"DIV"`、
前后强制空格（`writer.setNeedWhitespace(true)`，MySQL 词运算符必须带空格）。零碰本地 codegen 求值
（`io.ordinate`/janino `RexConverter case DIVIDE`）与类型推断（那些仍用完整 `/INT` 算子）。

## 3. RED 锁清单（离线·mode=local 不连库·仿 PushDownOptionalDefaultGuardrailTest）
`internalexample/.../drdsrunner/PushDownDivUnparseFidelityTest`：
① `crossShardUnionDivMustNotEmitSlashInt`——跨片 UNION ALL 两分支各含 `1 div (...)`，
断言 `plan.specificSql` 汇出的下推 SQL **不含 `/INT`**（修复前必红，含活体已证的 `/INT`）；
② `singleShardDivProjectionMustRenderNativeDiv`——单表 DIV 投影走 rel→sql 重渲染，
断言下推 SQL 不含 `/INT` **且含中缀 ` DIV `**（钉正解形态，非仅去病）。

## 4. 活体对拍（GREEN 后）
8066（persona 任一·后端 MySQL 8.0.46）跑 T3 原语句：
`select id, 1 div (id-1) q from shg where id=1 union all select id, 1 div (id-2) q from shg where id=2`
→ 不再 1064，逐格与直连真库同语句（各分支各自路由分片后 UNION）全等；补负数格
（如 `5 div (id-10)`）验向零截断与真库一致。

## 5. 边界与解锁
- 本刀=纯下推方言渲染面，不改除法语义/类型表征（I-DEBT-39/60 正交）；
- 修通后 I-DEBT-69「多目标 SELECT 告警拼接」活体格不再被 T3 1064 挡，可续跑对拍；
- 全量护栏须覆盖 internalexample（本锁所在）+ example（既有 DIV 相关金标准不回归）。

## 6. GREEN + 活体对拍落地（2026-11-02）

### 6.1 离线 GREEN（scratch/run-i75-red.ps1 → MVN_EXIT=0）
`PushDownDivUnparseFidelityTest` 两测转绿：下推 SQL 不再含 `/INT`、`singleShard` 断言含中缀 ` DIV `。
RED 实串曾取证为 `SELECT \`shg\`.\`id\`, (? /INT (\`shg\`.\`id\` - ?)) AS \`q\` FROM db1_1.shg_0 …`（`/INT` 原样出码），GREEN 后同路径出 ` DIV `。

### 6.2 活体端到端对拍（新 jar 重启 8066·persona=MARIADB_10_11·后端 MySQL 8.0）
重打 fat jar（`build-i75-jar.ps1`→MVN_EXIT=0·jar 内含 `MycatSqlDialect` 的 `/INT` 守卫 + `DIV` 分隔符常量，javap 反编译坐实），`start-proxy.ps1 -Kill` 重启，跑 `live-t75-green.sql`（经 `mycat:createDataSource/createCluster` hint 动态建 `dbt75g` mod_hash **2 分片**表）5 组：
- **G1 跨片 UNION 两分支各含 DIV（非零）**——旧 jar 在此 ERROR 1064 near `INT (…)`，**新 jar 返 `2→5, 7→1`**；
- G2 跨片 UNION 除零分支——`1→NULL, 2→5`；
- **G3 负数向零截断** `-7 div 2, 7 div -2, -7 div -2` → `-3, -3, 3`（**确证非 FLOOR**，FLOOR 会得 -4）；
- G4 全片扫描 `7 div id` → `-2→-3,1→7,2→3,3→2,7→1`；
- G5 常量 `100 div (id+1)` → `2→33,3→25,7→12`。
**逐格与直连真库 oracle（`oracle-t75g.sql` 单库同语句）字节全等**（`Compare-Object` 28 行 0 差异），**零 1064**。契约钉死：跨片 DIV 下推渲染 MySQL 原生中缀 `DIV`，向零截断 + 除零 NULL 语义与真库全等。

### 6.3 I-DEBT-69「多目标 SELECT 告警拼接」解锁探针——精确再定性（新发现）
1064 消除后重跑多目标 SELECT 告警对拍（`live-i69-multi-target.sql` + `probe-i69-multitarget-localcast.txt`），结论**推翻** §5 原假设「1064 消除即可续跑该活体格」：
- **单片** `select id, cast(note as signed) from shg where id=1`（命中单片）→ 代理返 `0` + `SHOW WARNINGS` 出 `Warning 1292 Truncated incorrect INTEGER value: 'abc'`（下推到后端、Blade D 单目标 Query 路，正常）；
- **多片全扫描** 同投影 `… from shg order by id` → 代理返**原串 `abc/xyz/123`**（未 CAST 成 0/0/123）、`SHOW WARNINGS` **空**、`@@warning_count`=0（oracle=2）；
- **多片** `select id, 1/(id-1) from shg`（除零）→ `NULL/1.0000/0.5000`（本地 decimal 求值）、**无 1365**（Blade E 本地不产诊断已知）。
**定性**：跨片 fan-out 下 SELECT 的**计算型投影由 Mycat 本地引擎求值**（非逐 target 下推），故 (a) 本地无 1292/1365 产源 → 多目标后端告警恒不存在 → 「多目标 SELECT 告警拼接」活体格**并非仅被 1064 挡，实被「跨片投影本地求值」挡**，仍 ⬜ 未活体验证（诚实挂账，不冒 Evidenced）；(b) 顺带拓出**独立的值保真偏差**：跨片 `cast(非数值 varchar as signed)` 本地返原串 vs 真库返 0，登记候选 **I-DEBT-76**（跨片计算投影本地求值保真面，属 codegen/本地执行器，正交于本 DIV 渲染刀）。

### 6.4 全量护栏终值（`mvn -o -pl example -am test`·scratch/i75-guardrail.log）
**BUILD SUCCESS·1138/0F/0E/37skip**：逐模块 config29 / common49 / router18 / datasource7 / va12 / proxy31 / hbt**299** / executor6 / server140 / example547·37skip，求和 1138。与上轮 I-DEBT-69 三刀基线 **1138 完全一致（hbt/`MycatSqlDialect` 改动零回归）**。注：新增 `internalexample PushDownDivUnparseFidelityTest` 2 测**不在 `example -am` reactor**（该 reactor 不含 internalexample），由 `run-i75-red.ps1`（`-pl internalexample -am test`）单独跑绿覆盖，故不计入 1138 总数。
