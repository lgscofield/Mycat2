# I-DEBT-92 · 分片×分片自连接下推语义错误：表达式 join 条件被误判 co-located → 每分片本地自连接 → 错误结果 0 行（审计 F6）

- **状态**：🔴 spec-ready（2026-11-08 能力审计立票 · EXPLAIN 铁证 · 未改生产码）
- **严重度**：P1（**错误结果**类，PROD-GATE G2 红线）

## 0. 摘要

`SELECT COUNT(*) FROM 分片表 a JOIN 分片表 b ON a.id = b.id - 1` 经 8066 返回 **0**（真库语义 29）。EXPLAIN 钉死：planner 把自连接**整体下推为每分片本地 JOIN**（`j1.id = t.$f2`，`$f2 = j10.id - ?`），再对局部 COUNT 求和。mod_hash(id) 使 id 与 id-1 必落不同分片 → 本地自连接恒 0 → 合计 0。**join 条件右侧是表达式而非纯分片键等值，被误判为可下推的 co-located 连接。**

## 1. 取证

| # | 事实 | 证据 |
|---|---|---|
| 1 | 活体：0 行（应 29）；分片×全局 JOIN 正常（30/30） | `audit/.../logs/P-B-04.log` |
| 2 | EXPLAIN：`MycatHashAggregate ← MycatView`；下推 SQL = 每分片本地 `INNER JOIN (SELECT ... (id-?) AS $f2 ...) t ON j1.id = t.$f2` UNION ALL | 2026-11-08 EXPLAIN 实跑（8066, dbaudit_shard2.j1） |
| 3 | 下推判定面 | `hbt/calcite/logical/MycatView.java:157-230`（IndexCondition/predicateAnalyzer）、join 下推规则（`LocalRules`/JoinViewRule 族） |

## 2. 修法候选（留专轮拍板）

- **A（窄修·推荐）**：下推判定的 join 条件收紧——仅当**两侧均为纯分片键列引用的等值**（`a.sk = b.sk`）才判 co-located 下推；含表达式/函数/常量的一律回退内存 JOIN（MycatHashJoin 等）。
- **B**：下推前对本地 JOIN 做分片函数等价性验证（mod_hash(id)=mod_hash(id-1) 可判定不同分片）——精确但复杂。
- 风险：A 须回归 B-04（分片×全局 30/30 绿）与 colocated 亲和优化（`ColocatedPlanner`）不误伤。

## 3. 验收判据

1. 自连接 `a.id = b.id - 1` 返回 29（探针入库 `audit/.../probes/P-B-04.java`）；`a.id = b.id`（纯等值）仍走下推且结果正确。
2. B-04 三格（shard×global/shard×shard/join+groupby）回归绿；EXPLAIN 显示内存 JOIN 形态。
3. example 护栏无回归。

## 4. 任务分解

- [ ] T1 下推判定代码路径取证（MycatView predicateAnalyzer + join 规则，file:line）
- [ ] T2 拍板 A/B → T3 修复 + EXPLAIN 断言锁 → T4 B-04 回归 → T5 看板回写
