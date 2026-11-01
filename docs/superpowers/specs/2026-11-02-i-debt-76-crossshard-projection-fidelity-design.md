# I-DEBT-76 跨片计算投影本地求值保真——取证与修法叉路（Spec 先行·未定案）

状态：**🔴 取证完成·机制钉死·修法待裁**（2026-11-02·I-DEBT-75 解锁探针副产物立案）
分类：C（引擎/codegen·跨片下推决策 + 本地归并求值）
对齐北极星：M2 无缝 SELECT + §7 真库行为一致性（值保真 + 诊断保真）

## 1. 症状（活体铁证 `scratch/probe-i69-multitarget-localcast.txt`·新 jar 8066·persona=MARIADB_10_11·后端 MySQL 8.0）
- **单片** `select id, cast(note as signed) as n from shg where id=1` → 代理返 `0` + `Warning 1292 Truncated incorrect INTEGER value: 'abc'`（后端算，正确）。
- **跨片全扫描** 同投影 `… from shg order by id` → 代理返**原串 `abc/xyz/123`**、`SHOW WARNINGS` 空、`@@warning_count`=0（oracle 全扫描= `0/0/123` + 2×1292）。
- 旁证 `select id, 1/(id-1) as q from shg` 多片 → 本地 decimal `NULL/1.0000/0.5000`、无 1365。
⇒ 跨片下计算型投影的**值保真 + 诊断保真同时背离真库**；结果列类型亦退化为 varchar。

## 2. 根因链（离线取证钉死 `scratch/i76-probe.log`·mode=local 经 `drds.dispatch`+`PlanImpl.specificSql` dump rel 树与 fragment）
`select id, cast(note as signed) as n from shg`（跨片全扫描）：
- **rel 树**：`MycatView.MYCAT2.[](relNode=LocalProject#20, distribution=[db1.shg])`
- **下推 fragment**：`SELECT shg.id, shg.note AS n FROM db1_0.shg_0 AS shg union all SELECT shg.id, shg.note AS n FROM db1_1.shg_0 AS shg`
钉死三点：
1. **`ProjectViewRule.onMatch`（`hbt/.../localrel/LocalRules.java:242-249`）** 把 `Project` 抽成叠在 MycatView 之上的 `LocalProject`（`view(view, LocalProject.create(project, view))`）——非平凡投影**不进 fragment SQL**。
2. **fragment 出码只落基列 + 别名**（`shg.note AS n`），**CAST 表达式被整个丢弃**，改由延迟的 `LocalProject` 负责本地补算。
3. **本地补算在实际 JDBC 归并路径未生效**：`cast(string):int32`（`CastStringToIntFunctionFactory:82`）与 `cast(string):int64`（`CastStringToLongFunctionFactory:74`，signed→BIGINT 走此腿）均 `Integer/Long.parseLong('abc')` → 必抛 `NumberFormatException`。但活体**既未抛错也未转值**，直接透传原串——即跨片归并（非 vector 态）**未执行 `LocalProject` 的 transform**，投影被静默丢失。
4. **单片不受影响**：单目标走原始 SQL 文本直通下推（与 I-DEBT-75「单片原文下推、跨片 rel→sql 重渲染」同源），CAST 交后端 MySQL 求值 → 值/类型/诊断全对。

## 3. 修法叉路（二者皆非平凡·blast radius 大·待裁）
### 方案 A：令计算投影（CAST/算术）**下推进 fragment SQL**
- 让 `cast(note as signed)` 作为可下推表达式进各分片 fragment（`select id, cast(note as signed) from shg_0 ...`），后端逐片求值。
- 优点：与单片行为一致；值保真 + 诊断保真（后端产 1292）天然回归真库语义；与 I-DEBT-69「多目标 SELECT 告警拼接」活体格解锁**同向**（后端有告警可拼）。
- 代价：改 `ProjectViewRule`/下推决策，触及**所有分片投影**的下推边界（哪些表达式可安全下推：跨方言一致性、NULL/类型/字符集），回归面广；需防把不可下推函数也塞进 fragment。

### 方案 B：修**跨片归并对 `LocalProject` 的实际求值**
- 让非 vector 归并路径真正执行 `LocalProject`（把延迟投影跑起来），并把本地 cast/算术语义对齐 MySQL（非数值→0 而非抛、且产 1292 进 DA）。
- 优点：不改下推决策，面较窄。
- 代价：本地求值引擎（io.ordinate）的 vector/merge  toggle 复杂；本地要产 MySQL 级诊断（1292/1365）需把求值器接进会话 DA（与 Blade E 本地不产诊断同族，属另一大坨）；值保真要在本地重写 cast/算术全套 MySQL 语义，非单点。

## 4. 推荐（带权衡）
倾向 **A（下推）** 为主攻：（i）与既有「单片原文下推」心智一致，跨片尽量把可下推计算也交后端，避免本地重造 MySQL 语义；（ii）顺带解锁 I-DEBT-69 多目标 SELECT 告警拼接（后端产真告警）；（iii）不要求本地引擎产 1292/1365（把 B 的深坑绕开）。
**但 A 需先划清「可下推表达式」白名单**（跨方言安全的 CAST/算术 vs 不可下推的本地函数），否则回归不可控——此为架构级裁定，建议由用户拍板主攻方向后再进 RED→GREEN。
**残留取证待办**（进任一方案前）：活体跑一个真跨片分片表 + 打开/关闭 vector 态，确认「归并路径是否曾执行 LocalProject」的代码落点（读侧），以钉 A/B 的精确改动面。

## 5. 本轮已交付 / 未交付（诚实边界）
- ✅ 机制钉死（rel 树 + fragment 出码 + ProjectViewRule 落点 + 本地 cast 必抛 vs 活体透传矛盾 → 归并不补算）。
- ✅ 活体症状复现（单片对 / 跨片值+类型+诊断三背离）。
- ⬜ 未改任何生产码（宁挂账不伪绿：在 A/B 主攻方向裁定 + 归并路径执行落点钉死前，不冒进 RED 锁）。
- ⬜ I-DEBT-69「多目标 SELECT 告警拼接」活体格仍被本格挡（诚实维持 NO_DATA）。

## 6. 窄取证定案（2026-11-02·用户选「先窄取证再定」）——分歧缩到 deparse 层单点
再跑 `i76-probe.log` 的三格对照，拿到**决定性同构对比**：跨片 cast 与跨片 div 的**计划形态完全相同**（均 `MycatView.MYCAT2.[](relNode=LocalProject#…)`），但 fragment 出码分叉：
- **算术 `/` 正常进 fragment**：`select id, 1/(id-1)` → `SELECT shg.id, (? / (shg.id - ?)) AS q FROM db1_0.shg_0 union all …`（表达式保留）。
- **CAST 不进 fragment**：`select id, cast(note as signed)` → `SELECT shg.id, shg.note AS n …`（只剩基列+别名，CAST 丢）。
⇒ 问题**不是**全局下推决策差异（A  feared 的大重写），**而是 Mycat 自有 rel→sql 转换器 `io.mycat.calcite.RelToSqlConverter`（`hbt/.../calcite/RelToSqlConverter.java`，非 calcite-core 版）对投影内 `RexCall(CAST, col)` 的出码不对称**：
- `visit(Project)`/`visit(Calc)` 逐投影项走 `builder.context.toSql(program, ref)`（L318/L560）；
- 该 `toSql` 落到 `RexToSqlNodeConverterImpl.convertCall`（`calcite-core/.../rex/RexToSqlNodeConverterImpl.java:60-67`）：**无 convertlet 即 `return null`**；
- 算术有标准 convertlet（转成 infix `/`），**CAST 无对应 convertlet → 转换回退/降级为操作数列引用**，故 CAST 静默丢失（`MycatImplementor` 对 NULL 字面量有 `castNullType`，但对非 NULL 列的 CAST 未特化）。

### 6.1 窄定的修法候选（比 §3 的 A/B 都小得多）
- **Narrow-A（主攻·deparse 侧让 CAST 下推）**：在 Mycat 的 rel→sql 转换路径为投影内 `RexCall(CAST)` 补 convertlet/特化，像算术一样把 `cast(x AS type)` 渲进 fragment（MySQL 目标型用 SIGNED 等合法类型）。效果=跨片与单片统一、后端算 cast→值/类型/诊断全回真库、顺带解锁 I-DEBT-69。离线可锁：断言跨片 cast fragment **含** `CAST(`/`SIGNED`（当前不含即 RED）。
- **Narrow-B（次选·runtime 侧）**：若非 NULL 列的 CAST 被有意判为「本地腿」（LocalProject 应在归并执行），则修归并真正执行 LocalProject transform + 本地 cast 对齐 MySQL（非数值→0 且产 1292）。面比原 B 小，但仍涉求值器 + 诊断接线。
### 6.2 待进 RED→GREEN 前最后一钉
Narrow-A 的风险=把本应本地的 CAST 强推下推是否伤其他不可下推型（DECIMAL 精度/字符集）；Narrow-B 的风险=归并路径执行落点未定位。**下一步取证**：确认 `SqlImplementor.Context.toSql` 对 `convertCall==null` 的确切回退行为（回退成 field 引用 vs 抛错），据此在 Narrow-A/Narrow-B 定一。本轮止于取证（用户选「先窄取证」），零改生产。
