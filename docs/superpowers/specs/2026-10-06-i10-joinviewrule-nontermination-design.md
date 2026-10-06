# I-DEBT-10 GSI+JOIN 服务端优化器不收敛 设计（Design Spec ·架构级根治版）

> **Goal:** 修复 Mycat 服务端 Calcite 优化器在「GSI + LEFT JOIN（两视图分布不可合并）」下的规划不收敛/失控（I-DEBT-10）。根因非任一单条规则，而是「把 MycatView 下推重写当作 Volcano 探索规则」这一执行模型缺陷。方案：将确定性的逻辑下推重写从 Volcano CBO 循环中**剥离为独立、有界的不动点前置阶段**，Volcano 只做约定转换与物理选型/代价。使触发查询在有限步内收敛并返回，且正常（可收敛）join 的最终计划与结果不被破坏。

**触发查询**
`SELECT a.id FROM db2.fp a LEFT JOIN db2.normal b ON a.user_id=b.user_id WHERE a.user_id=1 LIMIT 10`（`db2.fp` 带 GLOBAL INDEX）。

## 修订记录（凭当前实测，逐条推翻旧根因）
- **v1（初稿）**：归因 `JoinViewRule` 自触发（甲/乙守卫）。→ 实测主失控者是 `MycatExtraSortRule`，`JoinViewRule.onMatch` 此场景近似 no-op。**甲/乙单点守卫全败。**
- **v2**：主修改 `MycatExtraSortRule` 收敛守卫（onMatch 输入引用未变则不 transformTo + pushSort 内层 collation 幂等）。→ 实测仍挂（call# 1379 万级）。守卫 (a) `newLeft==left` 因 pushSort 恒造新对象而永不成立；守卫 (b) 读 `getTraitSet().getCollation()` 对叶子视图恒空。
- **v2.1**：`equals→satisfies` 精修。→ 仍挂（1376 万）。
- **v3（本会话·插桩实测·终）**：三处临时诊断（`AbstractRelNode`/`MycatView`/`MycatExtraSortRule`，**均已回退，不入代码**）坐实：
  1. **证伪「摘要嵌 id→永不合并」**：`VolcanoPlanner.registerImpl/reregister` 去重键是 `mapDigestToRel.get(rel.getRelDigest())`——`InnerRelDigest.equals→deepEquals`、`hashCode→deepHashCode`，**递归比较子 RelNode 对象（结构）**；`RelDigestWriter.done` 产出的 `LocalSort#<id>` 串仅 `getDigest()` 展示用，非去重键。
  2. **证伪「BKA/相关子查询每轮重展开 6482 次」**：`MycatTableLookupSemiJoinRule` 实仅 transformTo 6 次，6482 为 `LOGGER.error("MycatExtraSortRule",e)` 堆栈类名噪声。
  3. **量级实证**：rel 构造榜 LocalProject 6.64M/LocalJoin 6.62M/LocalFilter 3.33M/LocalTableScan 3.33M（≈2:2:1:1 翻倍），MycatView 仅约 2 万 → **爆炸是每视图 payload 尺寸指数增长，不是视图数量**。主导 payload 构造者：`MycatExtraSortRule.pushSort:161`、`JoinViewRule→pushDownERTable/tryMergeJoin→ofCondition/changeTo`、`SortViewRule`、`ProjectViewRule`。
  4. 落地 pushSort「坍缩而非叠加」根修（防 Sort 叠 Sort）+ satisfies → **仍挂**（call# 1268 万）。判读：坍缩确能防冗余再排序，但视图 payload 的 **base 自身**被 `JoinViewRule` 反复并表做大时，对新的大输入再排序是「合法的」，单规则拦不住。

**累计 4 次有据单规则守卫全败（乙 / 守卫a+b / satisfies / pushSort折叠），跨会话证据一致收敛到一个结论：这是规则族在 Volcano 内的复合放大，非单点缺陷。**

## 根因（一句话·架构级）
`DrdsSqlCompiler.optimizeWithCBO`（:440-498）把**逻辑下推重写规则**（`LocalRules.CBO_RULES` 含 `JoinViewRule/ProjectViewRule/SortViewRule/AggViewRule/CalcViewRule/UniversalFilterViewRule`、`MycatViewIndexViewRule`、以及 `MycatExtraSortRule.RULES`）与**物理约定转换/代价**一同作为 Volcano **探索规则**注册，交给 `findBestExp`。而 `MycatView`（`hbt/…/logical/MycatView.java`）在 Volcano 中是**不透明叶子**（未 override `getInputs`、未注册输入）：其下推子树 payload 不被 Volcano 的子集共享/结构去重看见，且 `changeTo/ofCondition` 每次经构造器 `input.accept(new ToLocalConverter())` **深拷贝整棵 payload 产出全新实例**。

于是：下推重写（把 Join/Sort/Project 并入视图、GSI 回表并表、为 merge-sort 预排序）在 Volcano 里被当作「可反复探索、可与自身产物再组合」的候选，每应用一次 payload 严格变大（并表把两侧子树并入一棵、排序在其上包一层），且 `MycatExtraSortRule.transformTo(join.copy(...))` 产出的新 Join 仍是 `Join.class` 且两侧仍是 `allowPushdown` 的 `MycatView` → `JoinViewRule`/`MycatExtraSortRule` 再触发。GSI 使每条表存在「主表 vs 索引表」两条分布路径，交叉出非闭合的探索空间 → **无不动点 → 失控**。下推重写本质是确定性 RBO（应只求一次不动点），把它塞进带 Alternatives 的 Volcano 搜索是设计错误。

## 设计：两阶段执行模型（改动全在 hbt，不动 calcite-core fork）

### 阶段一（新增）·确定性下推前置 pass
把「构建 MycatView payload」的 RBO 重写移出 Volcano，在**稳定语法树**上以有界、确定的方式求不动点：
- 用一个**受限 HEP 程序**（`HepPlanner` + `HepProgramBuilder`，含 `addMatchLimit`，`BOTTOM_UP`/`ARBITRARY` 序）依次跑：`ToLocalTableScanRule`、`ToViewTableScanRule`、`UniversalFilterViewRule`、`MycatViewIndexViewRule`(GSI 索引选择)、`ProjectViewRule`、`CalcViewRule`、`AggViewRule`、`SortViewRule`、`JoinViewRule`(并表/ER)、`MycatExtraSortRule`（merge-sort 预排）。
- HEP 把匹配节点**就地替换**为结果、不保留 Alternatives、matchLimit 兜底 → 每子树一遍成型、payload 不再被反复并大 → 收敛到单一「分片逻辑树」。
- 输出交给阶段二。

### 阶段二·Volcano 仅做物理选型/代价（现有 `optimizeWithCBO` 收敛裁剪）
- **移除**阶段一已确定性完成的下推重写规则注册（不再把这些规则加进 Volcano），保留 `MycatConvention.register`（物理转换器 `MycatRules.rules()`）+ 必要的物理选型/代价规则（如 hash/merge join 的物理 `ConverterRule`）+ transpose 类不改 payload 尺寸的规则（`FILTER_INTO_JOIN` 等，若其不作用于 MycatView 叶子内层则保留）。
- Volcano 因不再持有「会生长不透明叶子 payload」的规则，探索空间闭合，正常 join 的物理计划选择不变。

### 关键约束（决定成败，逐条实测）
- 阶段一产出的分片逻辑树，须与「修复前 Volcano 在正常（可收敛）join 上最终选中并下推成的形态」等价——否则正常 join 计划漂移。**以 join 全量回归 + 代表性 EXPLAIN 计划比对为唯一裁判**。
- 不改 `MycatView` 为「暴露输入」的共享节点（它是「一条 SQL 下推到 DN」的执行单元语义，必须保持叶子）；不深改 fork；不加全局迭代上限（丙，治标）。
- 若阶段一 HEP 内某规则仍不自收敛，回到 systematic-debugging 于该规则内做**幂等/坍缩**（本会话的 pushSort 折叠即属此类，可保留进阶段一），而非回退到在 Volcano 里加守卫。

## 边界与非目标
- 只重组 `optimizeWithCBO`/新增下推 pass 的规则编排 + 视需要保留 pushSort 折叠；规则**语义**不改（不改单次下推/排序/merge 选择逻辑）。
- 从属连接泄漏（`CreateGsiTest:219/258`、`TableAnalyseTest:24`、`UserTest:54`、`PushDownFunctionShardingTableTest:36/38`）不在本 spec 修，随基线全量验证另行收编。

## 验证策略（TDD，plan 阶段展开为任务）
1. **收敛门（RED→GREEN）**：以 `CreateGsiTest#testGsiJoinConverges`（守护线程 + 60s 有界等待，触发查询返回 `size==1`）为唯一收敛断言。修复前 RED（60s 未返回），阶段一/二落地后须 GREEN 且服务端 `call#`/`rel#` 有界。**测毕即 kill proxy** 防失控烧机。
2. **join 全量回归（唯一裁判，护栏）**：跑 `MegreJoinExecuteTest`、`BkaJoinExecuteTest`、`CreateGsiTest`（createGsi/createGsi2/createGsi3）及其它含 join 的 example 用例——相对修复前**无新增红、结果一致**；对代表性 join（含 sort-merge join、broadcast join、GSI 回表）做修复前后 EXPLAIN 计划比对，除「原本即失控的路径」外计划形态等价。漂移即回退该规则编排调整并记录。
3. **基线重生**：收敛门转绿后，一次性全量重跑重建可信产品红/绿基线。

## 成功判据（Evidenced-Done）
- 触发查询有限步产出计划并返回（运行日志 `call#` 有界，不再百万/千万级）；
- join 全量回归相对修复前无新增红、正常 join 结果与计划无实质漂移；
- 一切以当前实测（定向跑 + 全量回归 + EXPLAIN 比对）为凭，非仅凭本 spec 推断。

## 风险与缓解
- **阶段一与阶段二边界划错致正常 join 计划漂移** → 逐规则迁移、每迁一步跑 join 回归比对；规则语义不动，只动「在哪个 planner、是否保留 Alternatives」。
- **阶段一 HEP 自身不收敛**（某下推规则对其产物仍再命中）→ HEP matchLimit 兜底 + 规则内幂等（如 pushSort 折叠）；不回退到 Volcano 守卫。
- **误删 Volcano 仍需的物理规则致选型退化** → 回归结果一致性把关；仅移除「生长 payload 的逻辑重写」，保留物理 ConverterRule 与代价。
- **无法在本 spec 内一次性验证全量** → 以 ledger 记录每步实测，未证实的形态改动不得声称完成。
