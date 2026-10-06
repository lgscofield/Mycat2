# I-DEBT-10 JoinViewRule 自触发不收敛修复 设计（Design Spec）

> **Goal:** 修复 Mycat 服务端 Calcite 优化器 `JoinViewRule` 在「GSI + LEFT JOIN + 相关子查询（分布不可合并）」下的规则自触发不收敛（I-DEBT-10），使 `CreateGsiTest` 等 join 查询在有限步内收敛并返回，且不改变任何正常（可收敛）join 的最终计划。

**背景（凭当前实测，不信任旧记录）**
- §9（RED 反转）：`CreateGsiTest` 长挂真因 = 服务端 planner 不收敛（`call#`=640万、`rel#`≈3427万、查询 3 分钟后仍在规划、永不返回），非客户端连接池泄漏。
- §9.1（源码级根因）与诊断报告 `.superpowers/sdd/2026-10-06-i7b-diagnosis/i10_rootcause.md`：不收敛链已逐 call# 与日志对合坐实。

**触发查询**
`SELECT a.id FROM db2.fp a LEFT JOIN db2.normal b ON a.user_id=b.user_id WHERE a.user_id=1 LIMIT 10`（`db2.fp` 带 GLOBAL INDEX）。

## 根因（一句话）
`JoinViewRule`（`hbt/…/localrel/LocalRules.java:259`）的匹配面 `Join.class × 2 MycatView(allowPushdown)` 覆盖了它自己产出的物理 join；当两视图分布不可合并时，`bottomJoin` 返回 empty、改写落到 `tryMergeJoin` 产出 `MycatSortMergeJoin`（`extends Join`，两输入仍是那两视图），新节点又满足匹配条件，叠加相关子查询 `MycatSQLTableLookup(correlationIds)` 每轮重展开 → VolcanoPlanner 无终止。

## 设计：甲 + 乙（改动全在 hbt，不动 calcite-core fork）

### 甲 — 匹配面守卫（切断「规则吃自身产物」）
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
- 只在 hbt 内改：甲 = LocalRules 一处 operand；乙 = SQLRBORewriter 一处守卫。
- 不动 calcite-core fork；不动 VolcanoPlanner 终止逻辑（排除丙 matchLimit，治标）。
- 从属的 6 处连接泄漏（CreateGsiTest:219/258、TableAnalyseTest:24、UserTest:54、PushDownFunctionShardingTableTest:36/38）**不在本 spec 修**（当前无可观测绿信号），随基线可全量验证时另行收编。

## 验证策略（TDD，plan 阶段展开为任务）
1. **收敛红测**：以触发查询为断言对象——修复前 planner 在超时时限/匹配上限内不产出最终计划（表现为查询挂死/测试超时），即失败；断言「有限步内产出可行计划并返回结果」→ 修复前 RED。优先找 planner 级测试基座（免起 proxy）；若无，退化为 `CreateGsiTest` 定向黑盒（该查询返回、不再长挂）。
2. **改动转绿**：实施甲或乙后，定向复跑 → 查询返回、服务端日志 `call#`/`rel#` 不再无界增长。
3. **join 全量回归**：跑 join 相关类（BkaJoinExecuteTest、AssembleTest、其它含 join 的 example 用例）确保正常 join 计划与结果**零变化**；甲的排除面若引发回归，按「诚实边界」收窄到产物类型形态。
4. **基线重生**：`CreateGsiTest` 转绿/不再长挂后，按 §9 触发一次性全量重跑，重建可信产品红/绿基线；届时收编 6 处连接泄漏。

## 成功判据（Evidenced-Done 定义）
- 触发查询在有限步内产出计划并返回（运行时日志 `call#` 有界、不再 640万级）；
- join 全量回归相对修复前**无新增红、正常 join 计划无差异**；
- 结论以当前实测（planner 输出/定向跑）为凭，非仅凭本 spec 推断。

## 风险与缓解
- **甲排除过宽挡住正常下推** → join 回归证实，必要时收窄为「排除规则产物类型」。
- **乙误伤依赖 MergeJoin 的正常查询** → 守卫严格限定在「两 MycatView 且 distribution.join empty」这一不可下推子集；不碰可合并分布与非 MycatView 输入。
- **仍有未预见的不收敛路径** → 若甲+乙后定向复现仍长挂，回到 systematic-debugging 扩根因，不强行加丙掩盖。
