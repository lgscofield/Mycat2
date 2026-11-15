# 7C 下推隐式列别名裸 `?` 修复（A1 · aliasList 覆写）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除「函数投影无显式 AS」下推查询的后端 1064——令下推隐式别名取字面量形态（不含动态参 `?`），使 JDBC 占位符计数与真参一致。

**Architecture:** 在 `DrdsSqlCompiler.compileQuery` 字段名定稿处，把含 `?` 的隐式别名字段名以 `DrdsSqlWithParams.getAliasList()`（preParse 已从原始 AST 派生的字面量形态）覆写，使 `RelToSqlConverter.addSelect` 天然取字面量形态别名。不触 calcite-core fork、不改 MycatView copy-site、不动结果列标签（aliasList 独立覆写）。

**Tech Stack:** Java 21 / Maven offline / Calcite fork / JUnit4 / internalexample mode=local planner probe。

**Spec:** `docs/superpowers/specs/2026-11-13-7c-implicit-alias-bare-param-design.md`

## Global Constraints

- fork（calcite-core）零 `io.mycat` 依赖；本轮 A1 不触 fork（纯 hbt 改动）。
- 改生产必重打 fat jar 再活体验（`server/target/server-1.22-release-jar-with-dependencies.jar`）。
- 长护栏（25-30min）用 Monitor persistent + 日志，不用 Bash 后台（10min 静默杀）。
- 离线 probe mode=local 需一个可达后端（m1-mysql-a:3306）供 MycatCore init 读变量；Docker daemon 须先起。
- commit/push 每次显式拍板才执行，一次批准不延续。
- 环境：`JAVA_HOME="D:/Program Files/BellSoft/LibericaJDK-21-Full"`；`mvn -o`。

## Review Focus

1. **UNION fan-out 各 fragment 别名一致性**：跨片 UNION 每个 fragment 都须取字面量形态别名，漏改某腿仍含 `?`→该 fragment 仍 1064。
2. **多函数投影 / 嵌套子查询别名索引对齐**：aliasList 按 SELECT 项序，rowType 字段名须同序对齐；嵌套/UNION 下索引错位会覆写错别名。
3. **显式 AS 不被误改**：用户显式 `AS f` 的字段名无 `?`，覆写须只命中含 `?` 的隐式别名，显式路径零变化。
4. **结果列标签保真不回退**：aliasList 仍独立覆写结果标签（CalciteRowMetaData），下推别名改动不得泄到客户端列名。
5. **I-DEBT-76/77/78 下推保真不回退**：CAST/SIGNED/binary-length 锁须仍绿（本轮只动别名派生，不动 CAST deparse）。

---

### Task 1: A1 核心修复 + 离线 RED→GREEN 锁

**Files:**
- Modify: `hbt/src/main/java/io/mycat/DrdsSqlCompiler.java:357-374`（compileQuery 字段名定稿处）
- Test: `internalexample/src/test/java/io/mycat/drdsrunner/ImplicitAliasBareParamFidelityTest.java`（新建）

**Interfaces:**
- Consumes: `DrdsSqlWithParams.getAliasList()`（`List<String>`，preParse:106-115 字面量形态）；`RelDataType.getFieldNames()`；`RelBuilder.rename(List<String>)`。
- Produces: 下推 fragment 隐式别名不含 `?`（字面量形态）；`addSelect`（RelToSqlConverter:1025）天然取字面量名。

- [ ] **Step 1: 写离线 RED 锁**

新建 `ImplicitAliasBareParamFidelityTest`，仿 `CrossShardCastPushdownFidelityTest` 姿势（mode=local，shg mod_hash 2 分片表，DB1=3306）。`pushdownSql(sql)` 汇出 `plan.specificSql` 文本。测试方法 `implicitAliasMustNotContainBareParam`：

```java
@Test
public void implicitAliasMustNotContainBareParam() {
    String pushdown = pushdownSql("select DATE_FORMAT(\"2017-06-15\", \"%Y\") from shg");
    // 别名位（AS `...`）不得含裸 ?；修复前必红（当前 AS `DATE_FORMAT(?,'%Y')`）
    Assert.assertFalse("隐式别名不得含动态参 ?（致 JDBC 占位符错配→后端1064）；实际: " + pushdown,
            pushdown.matches("(?s).*AS `[^`]*\\?[^`]*`.*"));
    Assert.assertTrue("隐式别名应取字面量形态；实际: " + pushdown,
            pushdown.contains("AS `DATE_FORMAT('2017-06-15','%Y')`")
            || pushdown.contains("AS `DATE_FORMAT(\"2017-06-15\",\"%Y\")`"));
}
```

- [ ] **Step 2: 跑锁验证 RED**

Run: `JAVA_HOME="D:/Program Files/BellSoft/LibericaJDK-21-Full" mvn -o -q -pl internalexample test -Dtest=ImplicitAliasBareParamFidelityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL——断言 `AS \`DATE_FORMAT(?,'%Y')\`` 含 `?`（取证已坐实当前形态）。若 Docker/后端未起，先 `docker start m1-mysql-a` 并等 `mysqladmin ping` alive。

- [ ] **Step 3: 实现 A1 字段名覆写**

在 `compileQuery`（DrdsSqlCompiler:357-374）字段名定稿处，对 `finalRowType` 的隐式别名字段名做覆写：当某字段名含 `?` 且 `drdsSql.getAliasList()` 有同序非空项时，以 aliasList 项替换该字段名，重建 rowType 后再 `.rename(...)`。仅命中含 `?` 的字段（显式 AS 无 `?`→零变化）。覆写须发生在 `optimizeWithCBO` 之后、`MycatProject.create` 之前，使最终 rowType 字段名为字面量形态。

实现要点（签名已定，body 由此 determine）：
- 取 `List<String> aliasList = (drdsSql instanceof DrdsSqlWithParams) ? ((DrdsSqlWithParams) drdsSql).getAliasList() : Collections.emptyList();`
- 构造 `List<String> renamed`：逐字段 `i`，若 `finalRowType.getFieldNames().get(i)` 含 `'?'` 且 `i < aliasList.size()` 且 `aliasList.get(i)` 非空 → 用 `aliasList.get(i)`，否则保留原名。
- 以 `renamed` 重建 `finalRowType`（`RelDataTypeFactory.builder` 逐字段 `columnList` 改名，仿 DrdsSqlCompiler:135-137 既有 rowType 重建姿势）或经 `RelBuilder.rename(renamed)`。
- 守卫：aliasList 为空 / 长度不匹配 → 不覆写（回退原行为，零回归）。

- [ ] **Step 4: 跑锁验证 GREEN**

Run: 同 Step 2 命令。
Expected: PASS——下推别名 `AS \`DATE_FORMAT('2017-06-15','%Y')\``（无 `?`）。

- [ ] **Step 5: commit**

```bash
git add hbt/src/main/java/io/mycat/DrdsSqlCompiler.java internalexample/src/test/java/io/mycat/drdsrunner/ImplicitAliasBareParamFidelityTest.java
git commit -m "fix(7C): 下推隐式别名取字面量形态消除裸?致后端1064(A1 aliasList覆写)"
```

---

### Task 2: blast 覆盖 + 不回退锁 + 活体 + 护栏

**Files:**
- Test: `internalexample/src/test/java/io/mycat/drdsrunner/ImplicitAliasBareParamFidelityTest.java`（扩）
- Modify（仅若 Task1 覆写漏某腿）: `hbt/src/main/java/io/mycat/DrdsSqlCompiler.java`

**Interfaces:**
- Consumes: Task1 的字段名覆写；`CrossShardCastPushdownFidelityTest` / `CrossShardBinaryLengthPushdownFidelityTest`（I-DEBT-76/77/78 锁）。
- Produces: 全隐式别名形态无 `?`；显式 AS 干净；既有下推保真不回退；活体 testTimeFunction 绿。

- [ ] **Step 1: 扩 blast 锁（RED→GREEN）**

同测试类新增方法，断言下推别名无 `?`：
- `yearImplicitAlias`：`select YEAR("2017-06-15") from shg` → 别名无 `?`。
- `concatImplicitAlias`：`select CONCAT('a','b') from shg` → 别名无 `?`。
- `multiProjectionImplicitAlias`：`select YEAR("2017-06-15"), CONCAT('a','b') from shg` → 两别名均无 `?`（索引对齐）。
- `explicitAliasUntouched`：`select DATE_FORMAT("2017-06-15","%Y") as f from shg` → 别名仍 `` AS `f` ``（显式路径零变化）。
- `unionFanoutAllFragments`：跨片 UNION 各 fragment 别名均无 `?`（Review Focus #1）。

Run: `mvn -o -q -pl internalexample test -Dtest=ImplicitAliasBareParamFidelityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS。若某形态仍含 `?`（覆写漏腿）→ 回 Task1 Step3 补覆写路径（systematic-debugging 定位），再跑。

- [ ] **Step 2: 不回退锁**

Run: `mvn -o -q -pl internalexample test -Dtest=CrossShardCastPushdownFidelityTest,CrossShardBinaryLengthPushdownFidelityTest,ImplicitAliasBareParamFidelityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（I-DEBT-76/77/78 CAST/SIGNED/binary 锁仍绿 + 本轮锁绿）。

- [ ] **Step 3: 重打 fat jar + 活体 testTimeFunction**

Run: `mvn -o -B -pl server -am install -DskipTests`（Monitor persistent + 日志，估>10min）。
起代理：`start-server.ps1`（persona=MYSQL_5_7，Arrow `--add-opens=java.base/java.nio=ALL-UNNAMED`）。
Run: `mvn -o -B -pl example test -Dtest=PushDownFunctionShardingTableTest#testTimeFunction`
Expected: PASS——DATE_FORMAT 行不再 1064，值与直连 oracle 逐格全等。日志留 `scratch/7c-live.log`。

- [ ] **Step 4: 护栏（相关簇）**

Run: `mvn -o -B -pl hbt,router test`（Monitor persistent + 日志）+ example 下推函数簇 `PushDownFunctionShardingTableTest`（testCharFunction/testMathFunction/testCastFunction/testTimeFunction）。
Expected: 零回归。结果列标签抽查与真 MySQL 列名一致（Review Focus #4）。

- [ ] **Step 5: 收口回写 + 请示 commit**

回写 DEBT-QUEUE §1「7C 分诊·独立根」(b) 行：取证纠正（§2.3 反引号?合法、真机制 JDBC 错配）+ A1 落地 + 活体绿。回写 spec §6 Evidenced。请示用户 commit/push（每次显式拍板）。

```bash
git add docs/superpowers/specs/DEBT-QUEUE.md docs/superpowers/specs/2026-11-13-7c-implicit-alias-bare-param-design.md
git commit -m "docs(7C): 隐式别名裸?专轮收口回写两板(A1落地+活体绿+取证纠正)"
```