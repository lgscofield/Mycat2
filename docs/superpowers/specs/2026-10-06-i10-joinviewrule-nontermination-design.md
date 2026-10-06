# I-DEBT-10 JoinViewRule 自触发不收敛修复 设计（Design Spec）

> **Goal:** 修复 Mycat 服务端 Calcite 优化器在「GSI + LEFT JOIN + 相关子查询（分布不可合并）」下的规则自触发不收敛（I-DEBT-10）。实测坐实主失控者为 `MycatExtraSortRule`（靠 `join.copy` 自我供料、`pushSort` 幂等失效）；`JoinViewRule`（原甲/乙靶点）为次级因素。使 `CreateGsiTest` 等 join 查询在有限步内收敛并返回，且不改变任何正常（可收敛）join 的最终计划。

**背景（凭当前实测，不信任旧记录）**
- §9（RED 反转）：`CreateGsiTest` 长挂真因 = 服务端 planner 不收敛（`call#`=640万、`rel#`≈3427万、查询 3 分钟后仍在规划、永不返回），非客户端连接池泄漏。
- §9.1（源码级根因）与诊断报告 `.superpowers/sdd/2026-10-06-i7b-diagnosis/i10_rootcause.md`：不收敛链已逐 call# 与日志对合坐实。

**触发查询**
`SELECT a.id FROM db2.fp a LEFT JOIN db2.normal b ON a.user_id=b.user_id WHERE a.user_id=1 LIMIT 10`（`db2.fp` 带 GLOBAL INDEX）。

> **修订记录（2026-10-06 实测纠偏，凭当前实测不信任旧记录）**：干净重启 proxy（含乙 jar、PID 640）后前台真跑触发查询，实时抓服务端 planner 日志坐实——`call#` 冲到 **13,816,313 且持续飙升**，无限 fire 的规则是 **`MycatExtraSortRule`**（产物 `MycatHashJoin`），**不是本文初稿所指的 `JoinViewRule`**。据此：MycatExtraSortRule 为**主失控者**；JoinViewRule（甲/乙）降为**次级/贡献性**因素（该规则本身在此场景 `onMatch` 实为 no-op）。下文 §根因与 §设计 已按此纠偏重写。原始错误假设保留于此修订记录以存证。

## 根因（一句话·已纠偏）
**主因**：`MycatExtraSortRule`（`hbt/…/calcite/rules/MycatExtraSortRule.java`）的匹配面由 `{Join, Aggregate} × {MycatView(allowPushdown) | RelNode(anyInputs)}` 组合生成（`RULES` 静态块 :102-134），其中 `RelNode(anyInputs)` 变体**无视 pushdown 禁令恒匹配**；`onMatch`（:52-84）无条件 `call.transformTo(join.copy(..., pushSort(left), pushSort(right)))` 产出一个「为 merge-sort 预排好序」的**新 Join**，该新 Join 仍是 `Join.class`、又满足自身 operand → **规则靠 `join.copy` 自我供料**。`pushSort`（:86-100）的幂等早退守卫只比对节点自身 `traitSet` 的 collation，对 MycatView 则把排序经 `changeTo` 推进视图内层 relnode；叠加相关子查询 `MycatSQLTableLookup(correlationIds)` 每轮重展开出**新鲜 digest 的 MycatView**，被反复再排序 → VolcanoPlanner 无终止。

**次因（原甲/乙所治，非本次主爆炸点）**：`JoinViewRule`（`LocalRules.java:259`）的 `Join.class × 2 MycatView(allowPushdown)` 匹配面覆盖自身物理产物；分布不可合并时 `bottomJoin` 返回 empty、落 `tryMergeJoin` 产 `MycatSortMergeJoin` 回炉。但实测该查询在加入乙后 `JoinViewRule.onMatch` 已 no-op，主导 `call#` 增长的是 MycatExtraSortRule。

## 设计：主修（MycatExtraSortRule）+ 次级加固 甲/乙（改动全在 hbt，不动 calcite-core fork）

### 主修 — MycatExtraSortRule 收敛守卫（切断「规则靠 join.copy 自我供料」）
- **改点**：`MycatExtraSortRule.onMatch`（MycatExtraSortRule.java:52-84）与 `pushSort`（:86-100）。
- **切法（本 spec 裁定）**：
  1. `onMatch` 计算 `newLeft = pushSort(left, col0)`、`newRight = pushSort(right, col1)` 后，若 `newLeft == left && newRight == right`（**引用同一、无需再排序**），则 `return` 而**不调用 `transformTo`**——使一次已排好的 join 再命中本规则时成为 no-op，规则无法用 `join.copy` 造等价新节点自喂。
  2. 强化 `pushSort` 幂等：MycatView 分支在 `changeTo` 包 sort 前，先判其**有效 collation 是否已满足目标**（外层 traitSet collation 或内层 relnode 已是同 collation 的 `Sort`）；若已满足则返回**原视图引用不变**，杜绝 `Sort(Sort(...))` 逐层加深的新 digest。
- **语义**：只让「输入确实缺目标排序」时产一次新计划；重复应用必须收敛到 no-op。不改单次的排序/merge-join 选择行为。
- **诚实边界**：`RelNode(anyInputs)` 变体恒匹配是自我供料的放大器；若主修后定向复现仍长挂（相关子查询重展开持续造新 base view），回到 systematic-debugging 扩根因，不强行加丙（matchLimit）掩盖。正常查询单次生效不变，由 join 全量回归证实零差异。**回归是唯一裁判**。

### 甲 — 匹配面守卫（切断「JoinViewRule 吃自身产物」，次级加固）
- **改点**：`JoinViewRuleConfig.withOperandFor()`（LocalRules.java:288-295）。
- **切法（本 spec 裁定）**：对 `b0.operand(Join.class)` 增加约定谓词——仅匹配 **非 Mycat convention** 的 join（即 `join.getConvention() != MycatConvention.INSTANCE`，等价于逻辑 join：`LogicalJoin`/`LocalJoin`）。
- **语义**：`JoinViewRule` 是「把逻辑 join 下推/合并成 MycatView」的规则，只对进入 Mycat convention **之前**的 join 生效；一旦节点已是 Mycat 物理 join（`MycatHashJoin`/`MycatSortMergeJoin`/`MycatSortMergeSemiJoin`），不再由本规则回炉。
- **诚实边界**：爆炸输入实为 `MycatHashJoin`（物理约定）。此守卫的排除面「是否会误挡以物理形态进入下推的正常查询」，由 plan 的 join 全量回归证实；若发现正常下推依赖匹配 Mycat convention join，则收窄为「仅排除本规则产物类型（`MycatSortMergeJoin`/`MycatSortMergeSemiJoin`）」这一更保守形态。**回归是唯一裁判**。

### 乙 — 下推不可行即不再 merge 回炉
- **改点**：`SQLRBORewriter.tryMergeJoin()`（SQLRBORewriter.java:327-343）。
- **切法**：当 left、right 均为 `MycatView` 且 `left.getDistribution().join(right.getDistribution())` 为 empty（分布维度不兼容、join 不可下推合并）时，`tryMergeJoin` 返回 `Optional.empty()`——不再产出分布不兼容、且会再次触发 `JoinViewRule` 的 `MycatSortMergeJoin`。
- **语义**：「分布合不上的两视图」其 join 不该在 RBO 下推阶段被强转 merge；交回常规 `MycatHashJoin`（不满足甲的匹配、无重展开）收敛。
- **不改 CBO converter**：`MycatMergeJoinRule.convert()`（MycatMergeJoinRule.java:75）走的是物理约定转换，独立路径，本 spec 不改，避免误伤最终计划选择。

## 边界与非目标
- 只在 hbt 内改：主修 = MycatExtraSortRule 的 onMatch+pushSort 收敛守卫；次级加固 甲 = LocalRules 一处 operand；乙 = SQLRBORewriter 一处守卫。
- 不动 calcite-core fork；不动 VolcanoPlanner 终止逻辑（排除丙 matchLimit，治标）。
- 从属的 6 处连接泄漏（CreateGsiTest:219/258、TableAnalyseTest:24、UserTest:54、PushDownFunctionShardingTableTest:36/38）**不在本 spec 修**（当前无可观测绿信号），随基线可全量验证时另行收编。

## 验证策略（TDD，plan 阶段展开为任务）
1. **收敛红测**：以触发查询为断言对象——修复前 planner 在超时时限/匹配上限内不产出最终计划（表现为查询挂死/测试超时），即失败；断言「有限步内产出可行计划并返回结果」→ 修复前 RED。优先找 planner 级测试基座（免起 proxy）；若无，退化为 `CreateGsiTest` 定向黑盒（该查询返回、不再长挂）。
2. **改动转绿**：实施**主修（MycatExtraSortRule 收敛守卫）**后（必要时叠甲/乙），定向复跑 → 查询返回、服务端日志 `call#`/`rel#` 不再无界增长（对齐实测：修复前该查询 call# 达 1381万级）。
3. **join 全量回归**：跑 join 相关类（BkaJoinExecuteTest、AssembleTest、其它含 join 的 example 用例）确保正常 join 计划与结果**零变化**；甲的排除面若引发回归，按「诚实边界」收窄到产物类型形态。
4. **基线重生**：`CreateGsiTest` 转绿/不再长挂后，按 §9 触发一次性全量重跑，重建可信产品红/绿基线；届时收编 6 处连接泄漏。

## 成功判据（Evidenced-Done 定义）
- 触发查询在有限步内产出计划并返回（运行时日志 `call#` 有界、不再 1381万级）；
- join 全量回归相对修复前**无新增红、正常 join 计划无差异**；
- 结论以当前实测（planner 输出/定向跑）为凭，非仅凭本 spec 推断。

## 风险与缓解
- **主修改幂等误伤正常 merge-sort join**（MycatExtraSortRule 广泛注册于 CBO）→ 守卫只在「输入引用未变/有效 collation 已满足」时跳过 transformTo，单次有效排序行为不变；由 join 全量回归（BkaJoinExecuteTest/AssembleTest 等含 sort-merge join 用例）作为唯一裁判。
- **甲排除过宽挡住正常下推** → join 回归证实，必要时收窄为「排除规则产物类型」。
- **乙误伤依赖 MergeJoin 的正常查询** → 守卫严格限定在「两 MycatView 且 distribution.join empty」这一不可下推子集；不碰可合并分布与非 MycatView 输入。
- **仍有未预见的不收敛路径** → 若甲+乙后定向复现仍长挂，回到 systematic-debugging 扩根因，不强行加丙掩盖。
