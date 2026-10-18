# ORDER BY NULL 序对齐 MySQL（I-DEBT-55）设计规格

**日期:** 2026-10-17
**债务编号:** I-DEBT-55（对齐层级 A——招牌硬门本体：客户端眼中无缝替代 MySQL）
**北极星锚:** 客户端眼中 Mycat 与真 MySQL/MariaDB 行为一致；`ORDER BY` 含 NULL 行的输出序是 SQL 语义正确性的一部分，任何偏离都是可被客户端感知的行为差。

## 1. 症状与靶

裸 `ORDER BY col`（col 含 NULL）时，Mycat 按 Calcite 惯例把 NULL 排到最后；真 MySQL 把 NULL 视为最小值：

- 真 MySQL（实测，m1-mysql-a / m45-mysql-57）：`ASC → [NULL, 1, 2, 5]`；`DESC → [5, 2, 1, NULL]`
- Mycat（修复前）：`ASC → [1, 2, 5, NULL]`（偏离）

下推 SQL 同步偏离：`SqlImplementor.java:1122` 注释遗留的真实计划形状
`SELECT * FROM db1_0.travelrecord_0 WHERE (\`id\` = ?) ORDER BY (\`id\` IS NULL), \`id\``
——`(col IS NULL)` 前缀即 deparse 为"把 NULL 强挤到最后"而对 LOW 方言做的模拟补丁。

## 2. 根因链（全链已用当前代码逐级复核）

单因：裸 ORDER BY 的默认 null 方向取 Calcite 默认 `HIGH`（ASC→NULLS LAST），非 MySQL 的 `LOW`（ASC→NULLS FIRST）。

1. **出生点**：`SqlToRelConverter` 中 ORDER BY collation 的 UNSPECIFIED→显式方向解析，共 6 处、全部读同一旋钮
   `validator().config().defaultNullCollation()`：
   - L1638 `buildCollation`（窗口 `OVER (ORDER BY)` 与部分子查询形状）
   - L2738、L4038（主 select）、L5981、L5986、L6013 `sortToRex`
   - 旋钮默认 `HIGH`：`CalciteConnectionProperty.java:69`
   - 解析式：`defaultNullCollation().last(desc) ? LAST : FIRST`
2. **接入点唯一**：全仓 `SqlValidator.Config` 只造于 `MycatCalciteSupport.getValidatorConfig()`（L185，
   `SqlValidator.Config.DEFAULT.withSqlConformance(...)...`）；两处 validator 构造
   （`DrdsRunnerHelper.getSqlValidator` L223、`MycatSqlValidator`）都从它取。
3. **下游三消费腿，全部以 collation 为唯一真值，实测复核无旁路**：
   - 去程 deparse：`SqlImplementor.addOrderItem`（L1108-1114）仅在 `nullDirection != UNSPECIFIED` 时调
     `dialect.emulateNullDirection`；`MysqlSqlDialect.java:109` 已配 `NullCollation.LOW`
     （现存的 `(col IS NULL)` 模拟本身就是方言=LOW 的反证——HIGH 方言对 LAST 是默认序、根本不发模拟）。
     旋钮翻 FIRST 后 `isDefaultOrder(true,false)=true` → 返回 null → 发**裸** `ORDER BY col`，后端原生即正确。
   - 本地 codegen 排序：`PhysTypeImpl.generateCollationKey/generateComparator`（L297/347/425）按
     `nullDirection == FIRST ? compareNullsFirst : compareNullsLast` 分支。
   - 跨片流式归并：`CalciteCompiler.getPhysicalSortProperties`（L151-177）读 `fieldCollation.nullDirection`
     → `SortOptions.nullsFirst`；`PhysicalSortProperty/SortPlan/TopKPlan` 比较器全按 `nullsFirst` 分支，零硬编码。

## 3. 修复设计（方案 2：validator config 单旋钮）

**改动一处、零 fork diff、零新逻辑**——`MycatCalciteSupport.getValidatorConfig()` 的 config 链上补：

```java
// MYCAT CHANGE (I-DEBT-55): 裸 ORDER BY 的默认 null 方向须锚 MySQL 语义（NULL 视为最小值：
// ASC→NULLS FIRST，DESC→NULLS LAST）。Calcite 连接级 defaultNullCollation 默认 HIGH 是 SQL 标准
// 惯例而非 MySQL 惯例，SqlToRelConverter 六个 ORDER BY 出生点（含窗口 OVER(ORDER BY)）统一读此旋钮，
// 故在 Mycat 唯一 validator config 构造点拨到 LOW 即全源收口；deparse/codegen/跨片归并三腿皆以
// collation 为唯一真值，随动转正。
.withDefaultNullCollation(NullCollation.LOW)
```

上游同款用法旁证：`PlannerImpl.java:357`、`CalcitePrepareImpl.java:762` 均经
`CalciteConnectionProperty.DEFAULT_NULL_COLLATION` 消费同一 wither，机制为 Calcite 正置。

MySQL 与 MariaDB 在 NULL 排序上行为一致（皆 nulls-low），单旋钮无需 persona 拆分。

## 4. 目标行为对照表

| 输入形状 | 目标（= 真 MySQL 行为） |
|---|---|
| `ORDER BY col`（裸 ASC）/ 含 NULL | NULL 最前 |
| `ORDER BY col DESC` / 含 NULL | NULL 最后 |
| 多列 `ORDER BY a, b DESC` | 逐列独立按上述规则 |
| 跨片归并 + `LIMIT/OFFSET` | 与单片同序；推下序与归并比较器同用FIRST/LAST，合并流有序性成立 |
| 窗口 `OVER (ORDER BY col)` | NULL 最前（ASC） |
| 显式 `NULLS FIRST/LAST`（MySQL 原生语法不支持，druid 解析面接受） | 尊重显式指定，本设计不改变该分支 |
| 下推 SQL 形状 | 裸序形状不出现 `(col IS NULL)` 模拟前缀（LOW==native） |

## 5. 正交豁免（本轮明确不做）

1. **merge-join 键序**：`MycatMergeJoinRule:122`、`MycatExtraSortRule:73` 规则显式造 `NullDirection.LAST`，
   不经旋钮（旋钮只答 UNSPECIFIED）；`PhysTypeImpl.generateMergeJoinComparator:544` 的
   `assert nullDirection == LAST` 守卫因此不受扰动。join 键 NULL 匹配语义是另一条轴（已由
   Semi/Anti-Join 债处理），与本债正交。
   **⚠ 发现即修正（Task 4 全量护栏活锁 `SqlFunctionTest#testComplexQuery` 坐实，`4ff59e4f4`）**：
   本节前提“旋钮只答 UNSPECIFIED”成立，但原推论“因此 merge-join 面不受扰动”**错**——旋钮改的是
   其上游逻辑 Sort 的 trait（裸 ASC→nulls-first），与规则硬编的 ASC-LAST 必需 trait 冲突 →
   Volcano 无解 `CannotPlanException`。正解=trait 声明（`MycatMergeJoinRule`）、RBO 推入
   （`MycatExtraSortRule`）、运行期 comparator（`MycatSortMergeJoin`）**三处成套**翻 FIRST（与
   后端裸 ASC 实返序一致；NULL 键永不匹配，键序对连接正确性中性）。仍为真的豁免面收窄为：
   `generateMergeJoinComparator` 的 assert 未被触达（Mycat 走 `generateComparator`，无该 assert）。
2. **显式 NULLS FIRST/LAST 的语法面**：MySQL 客户端不会发此语法，Mycat 经 druid 接受它是既存
   超集行为，不属本债，不回归不删除。
3. 手工构造 collation 的算子/测试代码显式传 FIRST/LAST 者，语义自明，不随旋钮变。

## 6. 测试设计（TDD 脊柱）

**Task 0（RED 基线探针）**：跨片 `ORDER BY` 含 NULL 端到端用例先行，实跑钉死"修复前必红"，
同时作为矩阵最小代表；跑通即确认无第 7 运行时出生点。

**单元测试（无需活库）**：
- 断言 `getValidatorConfig().defaultNullCollation() == LOW`（旋钮回归锁）。
- 计划形状锁：裸 `ORDER BY id` 下推 SQL 恰为 `... ORDER BY \`id\``（无模拟前缀）；
  `DESC` 形状同理；显式 `NULLS LAST` 形状保留 `(col IS NULL)` 模拟（防过宽修复）。

**E2E 对拍矩阵 `OrderByNullCollationMatrixTest`（活库 Docker，方法学=对拍非硬编码）**：
对 Mycat 与直连控制库（同数据同查询）逐格比较**行序全等**：
- 轴：单片 / 跨片 × ASC / DESC × 单列 / 多列（含混合方向）× 无 NULL / 部分 NULL / 全 NULL
  × `LIMIT/OFFSET`（跨片分页含 `OFFSET 2 LIMIT 3` 类切窗）× 窗口 `OVER(ORDER BY)` 1 格
- 后端档位：m1-mysql-a（8.0）主力；MariaDB persona 容器在位则加验，不在位条件跳过并记录。
- 数据含可空列 NULL 分布在多片两侧（防单片掩盖归并错序）。

**全量护栏**：全 reactor `mvn -o clean install`（JDK21）零失败；快照断言复核——当前仓内
`ORDER BY (col IS NULL)` 字面量测试零命中（已复核），无批量重基线义务；若实跑暴露结果序
断言随修复转正，按本相更新之（非掩耳）。

## 7. 风险与观察项

- **trait 满足面变化**：collation 由 LAST 翻 FIRST 后，原"以 LAST 序满足 LAST 需求"的免排序路径
  变为"以 FIRST 满足 FIRST"，自洽；但个别视图/SPM 基线场景可能新增冗余 Sort（性能向非正确性向），
  矩阵跑 plan 形状时观察，异常再立观察账。
- **归并有序性前提**：跨片归并要求各片返回序与本地比较器同序——两侧均源自同一 collation，
  旋钮保证一致；矩阵中 `LIMIT/OFFSET` 切窗格专门盯此。
- 行为翻转即修复本相：客户端可见的 ORDER BY 结果序改变是目标而非副作用。

## 8. 验收判据

1. 矩阵全格 Mycat 行序 == 控制库行序（含 NULL 与分页切窗）。
2. 裸序形状下推 SQL 无 `(col IS NULL)` 前缀（形状断言绿）。
3. 显式 NULLS FIRST/LAST 分支行为不变（守卫测试绿）。
4. 全 reactor 构建 + 既有全套测试零新增失败。
5. DEBT-QUEUE：I-DEBT-55 收口，校准线回写。

## 9. 涉及模块

- `hbt`：`MycatCalciteSupport`（唯一产品码改动点）
- 测试：`hbt`（单元/形状锁）、`example`/`internalexample`（e2e 矩阵落位按既有 compat 测试惯例）
- `docs/superpowers/specs/DEBT-QUEUE.md`：收口回写
