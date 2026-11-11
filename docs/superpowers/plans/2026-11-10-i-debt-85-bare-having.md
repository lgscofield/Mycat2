# I-DEBT-85 裸 HAVING→WHERE 归一 · 实施 Plan（2026-11-10，spec 已拍板：候选 A / A1）

Spec：`docs/superpowers/specs/2026-11-10-i-debt-85-bare-having-design.md`（用户拍板：候选 A + A1 validator 插入点 + 1054 对齐纳入）。

## Task 1：RED 锁（hbt 离线，validate+convert 脚手架）
新 `hbt/src/test/java/io/mycat/calcite/BareHavingAsWhereTest.java`（仿 `OnlyFullGroupBySqlModeTest` 脚手架，fixture 表 tgui(TABLE_SCHEMA,TABLE_NAME,TABLE_TYPE,TABLE_COMMENT)）：
- 别名形（Connector/J 完整形：CASE 别名 + HAVING IN + ORDER BY 底层列）严格默认会话 validate+convert 放行且计划无 Aggregate（RED=notGroupExpr）
- select 裸列名 HAVING 放行（RED）
- 不可解析 HAVING 列 → 报 `Unknown column 'X' in 'having clause'`（RED=现报 notGroupExpr）
- lax 会话同形 → 同 1054 而非放行/崩（RED=现 lax 放行）
- 守卫格（须保持绿）：HAVING 含聚合不改写；GROUP BY+HAVING 不改写；严格会话聚合查询裸列仍拒
- `SELECT *` + HAVING 底层列放行（RED）；WHERE+HAVING 并存合并放行（RED）

## Task 2：GREEN 实现（calcite-core fork）
- `SqlValidatorImpl.validateSelect`：`validateFrom` 之后、`validateWhereClause(:4440)` 之前插 `rewriteBareHavingAsWhere(select)`。
- 改写条件：having!=null ∧ groupBy 空 ∧ having 无聚合 ∧ selectList 无聚合 ∧ 无 OVER ∧ having 无子查询（保守跳过）。
- 别名映射：AS 别名/裸列名→表达式（nameMatcher 大小写口径同 :4416）；star ⇒ 全部 FROM 列合法。
- shuttle 展开 HAVING 顶层标识符：命中映射→替换为 select 表达式；未命中且非 star→抛新资源消息 `unknownColumnInHavingClause`（`CalciteResource` 加 @BaseMessage("Unknown column ''{0}'' in ''having clause''")）。
- 合并 `WHERE = AND(oldWhere, expandedHaving)`、`setHaving(null)` ⇒ 后续正常 WHERE 校验，查询非聚合。
- 跑 hbt 全套 + 既有 OnlyFullGroupBySqlModeTest 不回归。

## Task 3：活体验证 + 护栏
- 重打 fat jar → `I85SpecProbe` 复跑：FULL-default/HAVING_ONLY-default 转绿（HAVING_ONLY 报 1054）、getTables(typeList) 默认会话 OK。
- example `MetadataGuiMatrixTest` 补 getTables(typeList) 格（本地 MYSQL_8_0 实跑；CI 纳入仍待 P3 拍板）。
- 全量护栏（Monitor persistent）：example 555 基线 + server/hbt/va 模块；账本+DEBT-QUEUE+PROGRESS 回写；commit 待用户拍板。