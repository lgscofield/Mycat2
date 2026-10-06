# I-DEBT-10 JoinViewRule 自触发不收敛修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `JoinViewRule` 在「GSI + LEFT JOIN + 相关子查询（分布不可合并）」下的规则自触发不收敛，使触发查询在有限步内产出计划并返回，且不改变任何正常（可收敛）join 的最终计划。

**Architecture:** 两道互补守卫，全在 hbt：乙=在 `tryMergeJoin` 遇「两视图分布不可合并」时不再产出会回炉的 `MycatSortMergeJoin`（直接掐断自触发燃料）；甲=在 `JoinViewRule` 的匹配面加约定谓词，禁止规则吃自身产出的 Mycat 物理 join（加固，防其它回炉路径）。先实施乙验证收敛，再叠加甲并回归确认无差异。

**Tech Stack:** JDK 21（BellSoft Liberica 21-Full）、Calcite fork（VolcanoPlanner / RelRule）、JUnit 4（`org.junit.Test`）、Maven（offline Nexus）、Docker 后端 m1-mysql-a:3306 / b:3307（root/123456）。

**Spec:** `docs/superpowers/specs/2026-10-06-i10-joinviewrule-nontermination-design.md`（根因见留痕簿 §9/§9.1，诊断报告 `.superpowers/sdd/2026-10-06-i7b-diagnosis/i10_rootcause.md`）

## Global Constraints
- 改动**只在 hbt 模块**；不动 `calcite-core` fork；不采用 planner matchLimit（丙，治标）。
- 一切结论以**当前实测**为凭（运行时日志 / 定向跑），不凭本计划推断；仅 Evidenced-Done。
- 不 merge / 不 push / 不发布；不做不可逆操作。
- 甲的最终排除面以 **join 全量回归为唯一裁判**：若「排除所有 Mycat convention join」引发正常 join 回归，则收窄为「仅排除本规则产物类型 `MycatSortMergeJoin`/`MycatSortMergeSemiJoin`」。
- 环境：`$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"`；`$env:MAVEN_ARGS="-Dmaven.repo.local=D:\Nexus\repository -o"`；起 proxy 用 `java -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar server/target/server-1.22-release-jar-with-dependencies.jar`（蓝本见 `.superpowers/sdd/2026-10-06-i7b-diagnosis/i7b_red.sh`）。
- 从属 6 处连接泄漏不在本计划修（无可观测绿信号），基线重生阶段另行收编。

## Review Focus（spec 隐含、须由测试钉住的输入类）
- 非 GSI 的相关子查询（`EXISTS` / `IN` 子查询下推）不得因甲/乙回归 → Task 4 断言相关用例结果不变。
- 其它 join 类型（INNER / RIGHT / SEMI / ANTI / FULL）× 各种分布组合不得因甲回归 → Task 4 join 全量回归覆盖。
- 分布「部分可合并」边界（`PHY×BROADCAST`、`SHARDING×BROADCAST`）行为不变（乙守卫只在 `distribution.join` 为 empty 时生效）→ Task 2 断言这些组合仍能正常下推。

---

### Task 1: 建立收敛红测（定测试落点，watched-fail）

**Files:**
- Modify: `example/src/test/java/io/mycat/gsi/CreateGsiTest.java`（新增独立测试方法 `testGsiJoinConverges`，不动既有用例）
- Test: 同上

**Interfaces:**
- Consumes: `MycatTest.getMySQLConnection(DB_MYCAT)`、`MycatTest.execute()`/`executeQuery(...)`（既有测试基元，7A/7A-2 已改）
- Produces: 一个可重复运行的「触发查询在 60s 内返回」的收敛断言，供 Task 2/3 复用为红→绿门

- [ ] **Step 1: 探测脱机编译入口（决定红测形态）**

Grep `DrdsSqlCompiler` 是否有不依赖运行中 server 的 `SQL→RelNode plan` 编译入口（形如 `compile(String)`/`getPlan(...)`，且能在无 MetaCluster 网络时构造带 GSI 的 `ShardingTable`）。
Expected：若存在且可喂带 GSI 的真实 schema → 采用「hbt planner 级超时断言」；否则（预期大概率如此，因 GSI 元数据重）→ 采用「example 定向 + JUnit 超时」。**本计划后续步骤按后者（B）写；若 Step 1 得 A，把 B 的定向跑等价替换为 planner 级断言，其余不变。**

- [ ] **Step 2: 写失败测试（B 形态：超时守卫）**

在 `CreateGsiTest` 新增：
```java
@Test(timeout = 60_000L)   // 正常可收敛 join 应秒级返回；挂死则 JUnit 超时判红
public void testGsiJoinConverges() throws Exception {
    Connection c = getMySQLConnection(DB_MYCAT);
    // 复用该类既有的建表/建 GSI 前置（与 createGsi3 相同的 db2.fp + GLOBAL INDEX）
    ensureGsiFpSetup();
    List<Map<String, Object>> rows =
        executeQuery(c, "SELECT a.id FROM db2.fp a LEFT JOIN db2.normal b ON a.user_id=b.user_id WHERE a.user_id=1 LIMIT 10");
    Assert.assertNotNull(rows);   // 关键断言：能在 60s 内产出结果（不再 planner 不收敛挂死）
}
```
`ensureGsiFpSetup()`：从 `createGsi3` 抽出「建 `db2.fp`（带 GLOBAL INDEX）+ `db2.normal` + 必要行」的前置语句，复用而非复制（若 createGsi3 已内联这些，提取为私有方法供两者调用）。

- [ ] **Step 3: 起 proxy + 定向跑，确认 RED**

```
java -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar server/target/server-1.22-release-jar-with-dependencies.jar   # 后台
mvn -B -o test -pl example -Dtest=CreateGsiTest#testGsiJoinConverges
```
Expected: **FAIL / timeout**（`testGsiJoinConverges` 因 planner 不收敛、查询 60s 不返回被 JUnit 超时判红）。抓 `mycat.log` 尾部确认 `JoinViewRule` `call#` 无界增长（对齐诊断证据）。

- [ ] **Step 4: Commit（红测入仓）**

```
git add example/src/test/java/io/mycat/gsi/CreateGsiTest.java
git commit -m "test(example): I-DEBT-10 收敛红测——GSI+LEFT JOIN 查询超时守卫（修复前应 RED）"
```

---

### Task 2: 乙——tryMergeJoin 分布不可合并则不产 MergeJoin

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/rewriter/SQLRBORewriter.java:327-343`（`tryMergeJoin`）
- Test: 复用 Task 1 `testGsiJoinConverges`

**Interfaces:**
- Consumes: `MycatView.getDistribution()`、`Distribution.join(Distribution)→Optional<Distribution>`、`MycatView`（`left/right instanceof MycatView`）
- Produces: 分布不可合并的两视图 join 不再被强转 `MycatSortMergeJoin`，交回常规收敛

- [ ] **Step 1: 加守卫（GREEN 目标实现）**

在 `tryMergeJoin` 现有 `isleftView && isRightView` 且 `banPushdown` 检查之后、调用 `MycatMergeJoinRule...tryMycatSortMergeJoin` 之前，插入：
```java
Distribution ld = ((MycatView) left).getDistribution();
Distribution rd = ((MycatView) right).getDistribution();
if (!ld.join(rd).isPresent()) {
    // 分布维度不兼容：不可下推合并，也不应产出会再次触发 JoinViewRule 的 MergeJoin
    return Optional.empty();
}
```
（`Distribution` 与 `Optional` 已在该文件可见；缺则补 import。）

- [ ] **Step 2: 重建 hbt + server fat-jar**

```
mvn -B -o -pl hbt -am install
mvn -B -o -pl server -am package    # 产出 server-1.22-release-jar-with-dependencies.jar
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: 重起 proxy + 定向复跑，确认转绿**

```
# 重启 proxy（加载新 jar）后
mvn -B -o test -pl example -Dtest=CreateGsiTest#testGsiJoinConverges
```
Expected: **PASS**（60s 内返回）。抓 `mycat.log`：`JoinViewRule` `call#`/`rel#` 有界（对齐 §9.1 成功判据）。

- [ ] **Step 4: 边界断言（Review Focus 第 3 行）**

临时（或作为 Task 4 子集）验证 `PHY×BROADCAST`、`SHARDING×BROADCAST` 等**可合并**分布仍能下推：这些组合 `ld.join(rd)` 非 empty → 乙守卫不触发 → 计划不变。跑 `AssembleTest` 中 broadcast/sharding join 用例，Expected：结果与修复前一致（无差异）。

- [ ] **Step 5: Commit**

```
git add hbt/src/main/java/io/mycat/calcite/rewriter/SQLRBORewriter.java
git commit -m "fix(hbt): I-DEBT-10 乙——tryMergeJoin 遇分布不可合并不再产 MergeJoin，断 JoinViewRule 自触发"
```

---

### Task 3: 甲——JoinViewRule 匹配面排除 Mycat 物理 join（加固）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/LocalRules.java:288-295`（`JoinViewRuleConfig.withOperandFor`）
- Test: 复用 Task 1 `testGsiJoinConverges`

**Interfaces:**
- Consumes: `join.getConvention()`、`MycatConvention.INSTANCE`
- Produces: `JoinViewRule` 不再匹配自身产出的 Mycat 物理 join

- [ ] **Step 1: 加约定谓词**

将 `b0.operand(Join.class)` 改为带谓词形态：
```java
b0.operand(Join.class).predicate(j -> j.getConvention() != MycatConvention.INSTANCE)
```
（保持其后 `.inputs(...)` 两 MycatView 谓词不变。`MycatConvention` 已在文件 import。）

- [ ] **Step 2: 重建 + 定向复跑保持绿**

```
mvn -B -o -pl hbt -am install && mvn -B -o -pl server -am package
```
重起 proxy 后 `mvn -B -o test -pl example -Dtest=CreateGsiTest#testGsiJoinConverges`
Expected: **PASS**。

- [ ] **Step 3: join 回归门（甲的排除面裁定）**

跑 join 相关类（见 Task 4 清单）中最小代表集：`AssembleTest`、`BkaJoinExecuteTest`。
Expected: 相对 Task 2 后**无新增红、正常 join 计划无差异**。
若出现因甲排除过宽导致的下推缺失回归 → 按 Global Constraints 收窄谓词为 `.predicate(j -> !(j instanceof MycatSortMergeJoin) && !(j instanceof MycatSortMergeSemiJoin))`，重跑本步至无差异，并在 commit message / 留痕记录该裁定。

- [ ] **Step 4: Commit**

```
git add hbt/src/main/java/io/mycat/calcite/localrel/LocalRules.java
git commit -m "fix(hbt): I-DEBT-10 甲——JoinViewRule 匹配面排除 Mycat 物理 join，加固断自触发环"
```

---

### Task 4: join 全量回归 + 收敛确认（Review Focus 钉桩）

**Files:**
- Test: 复用既有 `example` join/gsi 用例；必要时 `CreateGsiTest` 内补结果断言
- 无新生产代码（除非回归暴露缺陷）

**Interfaces:**
- Consumes: Task 2/3 的改后 planner 行为
- Produces: 「无新增红 + 正常 join 计划/结果零差异」的回归证据（Evidenced-Done）

- [ ] **Step 1: 全量定向回归**

起 proxy 跑 join/gsi 相关类：`CreateGsiTest`、`BkaJoinExecuteTest`、`AssembleTest`、`PushDownFunctionShardingTableTest`，及含相关子查询（`EXISTS`/`IN`）的既有用例。命令：
```
mvn -B -o test -pl example -Dtest=CreateGsiTest,BkaJoinExecuteTest,AssembleTest
```
Expected: 相对修复前基线**无新增红**；`testGsiJoinConverges` 绿；相关子查询用例结果不变（Review Focus 第 1 行）。

- [ ] **Step 2: 计划零差异抽查**

对若干正常 join（可合并分布、非 GSI）比对修复前后最终计划（`EXPLAIN`/测试断言的计划串），确认甲/乙**只影响不收敛路径**、正常 join 计划无变化（Review Focus 第 2 行）。

- [ ] **Step 3: 留痕登记 + Commit**

在 `docs/superpowers/specs/2026-10-02-bounded-debt-records.md` 追加 §10（I-DEBT-10 完成记录：乙+甲改动、收敛实测、join 回归结果、甲排除面最终形态），更新看板 `2026-09-30-mysql-mariadb-compat-PROGRESS.md`（关键路径 I-DEBT-10→✅、基线重生为下一步）。
```
git add docs/superpowers/specs/2026-10-02-bounded-debt-records.md docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -m "docs(debt): I-DEBT-10 完成入簿——JoinViewRule 自触发修复（乙+甲）+ join 回归证据"
```

---

## 完成判据
- Task 1 红测修复前 RED（超时挂死）、修复后 GREEN（60s 返回）。
- 甲+乙落地后，join 全量回归相对修复前无新增红、正常 join 计划零差异。
- `mycat.log` 中该查询 `JoinViewRule` `call#`/`rel#` 有界（对齐 §9.1 成功判据）。
- 基线重生与 6 处连接泄漏收编为后续独立回合，不在本计划内宣告完成。
