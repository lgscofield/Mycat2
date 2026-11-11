# I-DEBT-85 · 裸 HAVING（无 GROUP BY）两层崩 —— MySQL 语义对齐 Spec（待拍板）

> **状态**：🟢 已拍板（2026-11-10 用户定：候选 A + A1 validator + 1054 对齐纳入），**实现+活体验证已绿（2026-11-11）**，全量护栏背书后收口。
> **实现注记**：A1 落点经 RED 实测校正——`validateSelect` 早期改写太晚（`AggregatingSelectScope` 在 `registerQuery` 注册期已按 `isAggregate` 缓存），实际改写点前移到 `registerQuery` 的 `scopes.put` 之后、WHERE 子查询注册之前（`SqlValidatorImpl.java:3204` 一带），语义不变。
> **验证记录（2026-11-11）**：TDD RED 6 红（notGroupExpr/无 1054 消息）→ GREEN `BareHavingAsWhereTest` 9/9；calcite-core+hbt 全套 640/0F/0E；fat jar 重打（15:08）活体复跑 `I85SpecProbe`：FULL-default/lax 均 OK rows=166（修复前默认会话 1055 类崩）、HAVING_ONLY-default/lax 均报 `Unknown column 'TABLE_TYPE' in 'having clause'`（1054 对齐，两模式同）、getTables(typeList) 默认会话 OK rows=55；`MetadataGuiMatrixTest` 7/7 活体绿（MYSQL_8_0，含新增 S2 格 `getTables all=163 typeList[TABLE,VIEW]=55` 与客户端过滤基准逐键相等）。**附带对账**：`getTables(catalog="mysql",typeList)` 代理与直连后端同返 0 行——general_log 实捕 Connector/J 9.7 把 mysql/sys/performance_schema 的 BASE TABLE 映射为 `SYSTEM TABLE`，类型过滤后合法为空，非本次改动引入（代理=后端逐格一致）。
> **分层**：A（兼容招牌面：GUI 列库列表 / DatabaseMetaData.getTables(typeList)）。
> **一句话**：Connector/J `getTables(typeList=[TABLE,VIEW])` 生成**无 GROUP BY 的裸 HAVING**；真 MySQL/MariaDB 把"无 GROUP BY 且无聚合的 HAVING"**按 WHERE 逐行处理**（多行返回），Mycat 默认会话在校验层崩（1055 类）、宽松会话在实现层崩（IndexOutOfBounds）。正解=**把该形 HAVING 归一为 WHERE**（MySQL 优化器自身行为），一处改写同时消两层。

---

## §1 破口与触发面（2026-11-06 立票，2026-11-10 复核+加深取证）

- **触发**：Connector/J 9.7.0 `DatabaseMetaData.getTables(catalog, schema, "%", new String[]{"TABLE","VIEW"})` 生成：
  `SELECT TABLE_SCHEMA AS TABLE_CAT, …, CASE WHEN TABLE_TYPE='BASE TABLE' THEN … END AS TABLE_TYPE, … FROM INFORMATION_SCHEMA.TABLES WHERE … HAVING TABLE_TYPE IN ('TABLE','VIEW',null,null,null) ORDER BY TABLE_TYPE, TABLE_SCHEMA, TABLE_NAME`（proxy 日志实捕，`target/ci-guard/i85-proxy.log:2210`）。
- **影响面**：DBeaver/Navicat 等 GUI 列库列表带类型过滤即崩；分片表同样崩（非分片特有，产品级）。`getTables` 不带 typeList 不崩。
- **两层崩（2026-11-10 活体复跑坐实，`I85SpecProbe`）**：
  | 形 | 默认会话 | `SET sql_mode=''` |
  |---|---|---|
  | Connector/J 完整形（HAVING 引 select 别名 TABLE_TYPE） | ❌ `CalciteContextException: Expression 'TABLE_SCHEMA' is not being grouped` | ✅ 放行（rows 正常） |
  | 简化形（HAVING 引**不在 select list** 的底层列 TABLE_TYPE） | ❌ `Expression 'TABLE_NAME' is not being grouped` | ❌ `Unable to implement MycatFilter(OR(=($3,?1),=($3,?2)))` |

## §2 真 MySQL/MariaDB 语义锚（2026-11-10 一手实测，非二手结论）

对 docker 真后端直发（mysql 8.0.46 @3306 / mariadb 10.11.19 @3308）：

1. **完整形（HAVING 引 select 别名/列）**：默认 `ONLY_FULL_GROUP_BY` 开启下**放行且逐行返回多行**（mysql 库 5+ 行实测）——MySQL 把"无 GROUP BY + HAVING 无聚合"**当 WHERE 逐行过滤**，不当单组聚合。MariaDB 10.11 同。
2. **HAVING 引不在 select list 的底层列**：**ERROR 1054 `Unknown column 'TABLE_TYPE' in 'having clause'`**—— lax（`sql_mode=''`）也拒。MariaDB 同（文案 `in 'HAVING'`）。⇒ MySQL 的 HAVING 引用域 = select list（别名/列名）+ 聚合，**不含**任意 FROM 列。
3. 推论：Mycat 两种会话下的报错**都不对齐**——默认会话报 1055 类（MySQL 根本不当聚合查询）、lax 会话直接崩（MySQL 报 1054）。

## §3 Mycat 侧根因（file:line 实证）

- **校验层**：HAVING 使 `isAggregate(select)`=true → 查询进 `AggregatingSelectScope`；ORDER BY/HAVING 的裸列被 `AggChecker` 拒（`calcite-core/.../AggChecker.java:161` throw notGroupExpr）。D2b 已有 lenient 门（`:157` 会话无 ONLY_FULL_GROUP_BY 放行）与 D2b-B select-list MAX 重写（`SqlValidatorImpl.java:5493-5534`），但 **HAVING/ORDER BY 明确不重写**（`:5507-5509` 注释自认"既定局限"）——本票正落该缝。
- **实现层**：lax 放行后，计划为 `MycatFilter(OR(=($3,?1),=($3,?2)))` 罩在 `MycatHashAggregate(group=[{}], TABLE_NAME=[MAX($0)])` 上——聚合输出仅 1 列，HAVING 条件却引用**原表索引 $3**（TABLE_TYPE 未被投影穿过聚合）。`MycatFilter.toMycatCalc`（`hbt/.../physical/MycatFilter.java:96`）`RexProgramBuilder.addCondition` 注册输入时 `IndexOutOfBoundsException: Index 3 out of bounds for length 1`（proxy 日志 Suppressed 全栈实捕），被 `StreamMycatEnumerableRelImplementor.implementHybridRoot:98` 裹成 "Unable to implement"。**不是 OR/参数翻译不支持，是 lenient 放行的 HAVING 裸列没穿过聚合投影**。
- **结论**：补实现层（把 HAVING 裸列 MAX 化/投影穿过聚合）= 沿"单组任意值"语义走，**与 MySQL 实测的逐行 WHERE 语义相反**（会返 0/1 行而非多行），方向错误。正解是 §4 的归一改写。

## §4 修法候选（拍板项）

### 候选 A（推荐）：无 GROUP BY + 无聚合 HAVING → 归一为 WHERE
- **语义**：完全对齐 MySQL 优化器自身行为（§2.1 实测多行）。HAVING 中 select 别名/列名先展开为 select 表达式，再 AND 进 WHERE，清空 HAVING ⇒ 查询不再是聚合 ⇒ 校验层/实现层两崩同时消失。
- **引用不可解析时**（HAVING 引不在 select list 的 FROM 列）：按 MySQL 报 **1054 `Unknown column 'X' in 'having clause'`**（§2.2），不放行也不崩。
- **不动**：HAVING 含聚合（`HAVING COUNT(*)>0`）或有 GROUP BY 的形，保持现路径。
- **插入点二选一（plan 定）**：
  - **A1 validator 早期钩子**：`SqlValidatorImpl.validateSelect`（`:4373`）在 `validateWhereClause(:4440)` 之前做改写——此时 select list 未展开，需自建别名映射（AS 别名/裸列名→表达式）；子查询内的标识符不得误展开（只扫 HAVING 顶层表达式树、跳过子查询节点）。风险：改写后 WHERE 走正常 `validateWhereClause` 校验，无 scope 缓存问题（改写发生在 scope 创建前）。
  - **A2 druid AST 层钩子**：`MycatCalciteMySqlNodeVisitor` 建 SqlSelect 前在 druid AST 上改写（先例：I-DEBT-29 `DrdsRunnerHelper.preParse` AST 突变）。优点：Calcite 全程见到的是普通 WHERE，零 scope 风险；缺点：影响下推文本（语义等价——MySQL 自己也这么优化；参数序 WHERE 在前 HAVING 在后，AND 追加保序），且 visitor 是共享面。
- **倾向 A1**：改写只影响本地校验/规划入口，下推文本保持客户端原样（对后端零扰动）；别名展开可复用 `extendedExpand` 的 Clause 语义或自写顶层 shuttle。

### 候选 B（否决）：实现层补支持
把 HAVING 裸列 MAX 化穿过聚合（D2b-B 扩展到 HAVING）。**否决理由**：语义=单组任意值（0/1 行），与 MySQL 实测逐行多行相反；且只修 lax 层，默认会话 1055 仍在。

### 候选 C（否决）：仅放宽校验
默认会话也放行裸 HAVING。只修第一层，lax 层 IndexOutOfBounds 仍在，且语义同 B 错误。

## §5 测试矩阵（TDD，先 RED）

1. **离线锁（hbt/internalexample）**：① 完整形默认会话 → 改写后正常出计划/执行（RED=现 1055）；② 简化形 lax → 1054 风格报错而非 IndexOutOfBounds（RED=现崩）；③ `HAVING COUNT(*)>0` 无 GROUP BY → 保持聚合路径不回归；④ 有 GROUP BY + 非聚合 HAVING → 不改写；⑤ HAVING 引子查询内列 → 不误展开；⑥ 别名展开正确性（CASE 别名进 WHERE 后逐行过滤结果与真 MySQL 对拍）。
2. **活体锁（example）**：`MetadataGuiMatrixTest` 补 `getTables(typeList=[TABLE,VIEW])` 格（7 persona 参数化），断言行数>0 且与不带 typeList 的结果集一致（TABLE 子集）；对拍真后端直连同查询。
3. **变异负控**：去掉改写 ⇒ ①②必红。
4. **版本锚**：MySQL 5.7 本地无容器，§2 语义锚在 5.7 档由 CI 矩阵补验（若 5.7 行为异，spec 修订）。
5. **硬门**：改生产码必重打 fat jar 再验；全量护栏（example 555 基线 + server/hbt/va 模块测试）；账本+DEBT-QUEUE+PROGRESS 对齐；commit 不 push（待拍板）。

## §6 拍板结果（2026-11-10 用户定）

1. 修法方向=**候选 A**（HAVING→WHERE 归一 + 1054 对齐）；插入点=**A1 validator**（实现时前移到 registerQuery 注册期，见头部注记）。
2. 1054 报错对齐**纳入本票**。
3. 节奏=先 I-DEBT-85（commit 腿 §8 组合案已认可，随后轮次）。