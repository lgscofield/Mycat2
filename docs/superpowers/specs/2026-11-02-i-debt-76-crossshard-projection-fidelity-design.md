# I-DEBT-76 跨片计算投影本地求值保真——取证与修法叉路（Spec 先行·未定案）

状态：**✅ 全绿收口（值+类型保真 Narrow-A 已落地 `f0a7fb564`；诊断腿=跨片告警回传已随 I-DEBT-69 `c8156a566`（JDBC 池）+ NATIVE SELECT 腿 `a2e81f11b` 闭环）**（2026-11-02 取证→窄定→RED→GREEN→护栏 1138→活体对拍；2026-11-03 对当前 HEAD 叠 77/78/B 后重做取证复核转正·见 §8）
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
## 6.2 最后一钉（2026-11-02·决定性·**推翻** §6 的 convertCall 假设）
直接读 Mycat 自有 deparse 出口 `hbt/.../io/mycat/calcite/SqlImplementor.java` 的 `Context.toSql`（`visit(Project)`/`visit(Calc)` 逐投影项即走此方法，**不经** calcite-core 的 `RexToSqlNodeConverterImpl.convertCall`——§6 的 convertlet 假设作废）。CAST 有专门分支 L834-866：
```java
if (ignoreCast) { return nodeList.get(0); }          // 默认 false（ServerConfig:72），不命中
else if (call.getOperands().size() == 1) {
    RexNode rexNode = call.getOperands().get(0);
    if (族(源)==族(目标)) return nodeList.get(0);      // (a) 同族冗余 cast→丢，无害
    if (rexNode instanceof RexDynamicParam) return nodeList.get(0); // (b) 丢
    if (rexNode instanceof RexInputRef)     return nodeList.get(0); // (c) 丢 ← 命中：note 是列引用
    if (rexNode instanceof RexLocalRef)     return nodeList.get(0); // (d) 丢
}
nodeList.add(dialect.getCastSpec(call.getType());    // 只有走到这里才保留 CAST
```
**对照上游** `calcite-core/.../rel/rel2sql/SqlImplementor.java:907-935`：除 CURSOR/`ignoreCast`/UNKNOWN 外**一律** `nodeList.add(castNonNull(dialect.getCastSpec(...)))` 保留 CAST——上游**没有** (b)(c)(d) 三个早退。
**裁定**：(a) 已在同族时 return，故 (b)(c)(d) **只在族不同时触发** = 专门丢弃跨族承重 CAST（varchar→signed 等）。这是 hbt 影子副本相对上游的**纯分歧 bug**（非「有意本地腿」，LocalProject 在归并路径本就不执行）。删除 (b)(c)(d) 即回归上游、保留跨族 CAST 下推。
### 6.3 定案：Narrow-A（deparse 侧·削减 fork 分歧·可离线锁 + 活体验）
- 落点①：`SqlImplementor.java:850-864` 删去 (b)(c)(d) 三早退（保留 (a) 同族丢弃以控回归）。跨族列引用 CAST 落到 L865 `dialect.getCastSpec`。
- 落点②：`MycatSqlDialect.getCastSpec`（现仅 BOOLEAN→SIGNED）需为整数族（`cast(... as signed)` 目标 INTEGER/BIGINT）渲染 MySQL/MariaDB 合法型（`SIGNED`，对齐单片原文），否则 `super` 出的裸 `INTEGER/BIGINT` 可能被后端 CAST 拒。
- 离线 RED 锁：跨片 `cast(note as signed)` 的 fragment 断言**含** `CAST(`/`SIGNED`（当前只剩 `shg.note AS n`→RED）。活体对拍：跨片全扫描应回 `0` + `1292`（与 oracle/单片同），值+类型+诊断三保真。
- 风险/回归面：触及所有跨片投影 deparse；删分歧=向上游对齐，同族丢弃仍保留。全量护栏 + 活体兜底。
本钉为纯读侧取证，零改生产。下一步进 RED→GREEN。

## 7. RED→GREEN 收口（2026-11-02·已落地·值+类型保真达成）
**离线锁** `internalexample/.../drdsrunner/CrossShardCastPushdownFidelityTest.java`（mode=local，经 `drds.dispatch`+`PlanImpl.specificSql` 取下推 fragment）：
- **RED**（修复前）：`cast(note as signed)` 全扫描 fragment = ``SELECT `shg`.`id`, `shg`.`note` AS `n` FROM db1_0.shg_0 …``——CAST 被丢、只剩基列+别名（坐实 §6.2 L858 RexInputRef 早退）。
- **GREEN**（修复后）：两处生产改动后断言 `contains("CAST(")` + `contains("SIGNED")` 均过，`MVN_EXIT=0`。坐实下推渲染为合法的 `CAST(… AS SIGNED)`。

**生产改动（两处·均 MYCAT CHANGE 注释钉档）**：
1. `hbt/.../io/mycat/calcite/SqlImplementor.java` CAST 分支：删除 (b)RexDynamicParam/(c)RexInputRef/(d)RexLocalRef 三个跨族早退（保留同族冗余丢弃分支付），跨族列引用 CAST 落到 `dialect.getCastSpec`。**= 向 Calcite 上游对齐、削减 hbt 影子副本分歧。**
2. `hbt/.../io/mycat/calcite/MycatSqlDialect.getCastSpec`：整数族（`SqlTypeFamily.INTEGER`，含 BOOLEAN）统一渲染 MySQL/MariaDB CAST 合法的 `SIGNED`（原仅 BOOLEAN→SIGNED），避免 `super` 直出裸 `INTEGER/BIGINT` 被后端 CAST 拒（1064）。

**全量护栏**：`mvn -o -pl example -am test` = **1138 / 0F / 0E / 37skip**，与 I-DEBT-75 基线逐格全等，**零回归**（`scratch/i76-guardrail.log`）。（新离线锁在 internalexample，不在 example -am reactor 内，由 `run-i76-red.ps1` 单跑绿。）

**活体对拍**（重打 fat jar MVN_EXIT=0 + 重启 8066 persona=MARIADB_10_11、后端 m1-mysql-a@3306 MySQL 8.0；`scratch/live-i76-cast.sql` vs `oracle-i76-cast.sql`）：
- **值保真 + 结果类型保真已回归真库**：跨片全扫描 `cast(note as signed)` 从修复前的原串 `abc/xyz/123` → 现为 **`1→0 / 2→0 / 3→123`**，与 oracle 直连逐格全等；单片 id=1 仍 `0`+1292；普通投影 abc/xyz/123、算术下推 11/12/13 回归护栏均干净。
- **诊断保真（跨片告警回传）仍未达成→属 I-DEBT-69 独立格**：跨片 `@@warning_count`=0（单片有 1292），即后端逐片已**产出** 1292（本修复解锁其前提），但代理跨片归并未将多后端连接告警汇入会话 DA。与 §3/§4 所述一致：本修复采 Narrow-A 不要求本地引擎造诊断；跨片告警拼接 = I-DEBT-69（现已不再被 76 阻断，前提就绪）。

**北极星对齐**：M2 无缝 SELECT + §7 真库行为一致性中的**值保真 + 类型保真**子项已拿回；诊断保真（跨片告警）显式留给 I-DEBT-69，不伪绿。

## 8. 2026-11-03 取证复核转正收口（B 专轮紧接执行·零新生产码·行首 🔴→✅）

承用户「A、B、C 按顺序逐个完善」，C=本格。B（I-DEBT-69 NATIVE SELECT 腿）收口后紧接对 I-DEBT-76 做**不臆信旧记录**的当前 HEAD 复核。铁证四钉：

1. **静态代码**（读当前 `hbt/.../io/mycat/calcite/SqlImplementor.java` + `MycatSqlDialect.java`）：CAST 分支现仅存同族丢弃（L852-854），§6.2 所述 (b)RexDynamicParam/(c)RexInputRef/(d)RexLocalRef 三跨族早退确已删除并带 `MYCAT CHANGE (I-DEBT-76)` 注释钉档；I-DEBT-78（`90dfa9bfc`）已移除 I-DEBT-77 的 BINARY→CHAR 方向剥离分支，跨族 CAST 一律落到 L870 `dialect.getCastSpec(call.getType())`；`MycatSqlDialect.getCastSpec` 整数族（`SqlTypeFamily.INTEGER` 含 BOOLEAN）渲 `SIGNED` 在位。⇒ **76 的修存活于后续 77/78/B 三刀之后**。
2. **离线锁重跑**：`mvn -o -pl internalexample -am test -Dtest=CrossShardCastPushdownFidelityTest` → **Tests run: 1, Failures: 0, Errors: 0, BUILD SUCCESS, MVN_EXIT=0**（断言跨片 `cast(note as signed)` 下推 fragment 含 `CAST(` + `SIGNED`·日志 `scratch/i76-offline-lock.log`）。
3. **活体诊断腿闭环**（本债 (b) 面「多目标 SELECT 告警拼接」之阻塞已解除）：跨片告警回传随 `c8156a566`（I-DEBT-69 Blade D 余项·Calcite 路径 warningSink 接线·JDBC 池）落地；NATIVE datasource SELECT 腿 EOF warnings 随 `a2e81f11b`（I-DEBT-69 NATIVE SELECT 腿三刀）落地。native 8066（`-Dserver=native`·载 `a2e81f11b` 修复 jar）复跑 `CrossShardWarningRelayTest` → **2/0/0 GREEN**（日志 `scratch/i69-live-native.log`），跨片全扫描 `cast(note as signed)` 的后端 1292 现汇入会话 DA、与直连 oracle 计数/成员逐格一致。

**定案**：对跨片计算投影，值保真 + 类型保真（§7 Narrow-A）+ 诊断保真（§7 留待 I-DEBT-69，现已落）三者已**全链回归真库**，本债主体与唯一下游依赖皆收口 → DEBT-QUEUE 行首 🔴→✅、本 spec 状态行升 ✅。**无剩余实现项**（诚实划界：Blade E 本地求值除零 1365 属 I-DEBT-39·残 / I-DEBT-69 独立格，非本债；本债采 Narrow-A 本就不要求本地引擎造诊断）。
