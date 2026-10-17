# ORDER BY NULL 序对齐 MySQL（I-DEBT-55）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把裸 `ORDER BY` 的默认 null 方向从 Calcite 惯例 HIGH 拨到 MySQL 语义 LOW，使单片下推、本地 codegen、跨片归并三腿的 NULL 输出序与真 MySQL/MariaDB 逐格一致。

**Architecture:** 单旋钮方案——`MycatCalciteSupport.getValidatorConfig()` 是全仓唯一 `SqlValidator.Config` 出生点，`SqlToRelConverter` 六个 ORDER BY collation 出生点（含窗口 `buildCollation`）统一读其 `defaultNullCollation`；collation 为唯一真值，deparse/codegen/executor 归并三腿随动。零 fork diff。

**Tech Stack:** JDK 21、Calcite 1.42.0-mycat fork、JUnit4（internalexample drdsrunner 系）/JUnit5（hbt）/compat 对拍系（example）、Docker MySQL（m1-mysql-a: localhost:3306 = DB1，m1-mysql-b: localhost:3307 = DB2）、活服务端 :8066。

**Spec:** `docs/superpowers/specs/2026-10-17-orderby-null-collation-design.md`

## Global Constraints

- 每条 `mvn` 命令内联 `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'`（shell 默认 JAVA_HOME 可能指 JDK 8，env 不跨 Bash 调用持久化）。
- Maven 一律离线 `-o`；新增依赖禁止（本计划零新依赖）。
- 产品码改动仅允许 `hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java` 一处，带 `// MYCAT CHANGE (I-DEBT-55)` 中文注释块（格式见 spec §3）。
- e2e 跑完后 `git restore server/src/main/resources`（I-DEBT-56 卫生规矩）；禁止全量 `git clean -fd`，只允许 scoped 精确路径。
- commit 消息用 Write/Set-Content 生成 UTF-8 文件后 `git commit -F` 落地；全程不 push（推送时机用户自决）。
- Docker 容器 m1-mysql-a/m1-mysql-b 须在位且 3306/3307 可达；活服务端以 classpath 直启方式起（免打 fat-jar，见既有 e2e 记忆），重启后须 settle 等待初始化完成再连。

## Review Focus

（spec 隐含但任务测试不逐一覆盖、最可能咬人的输入类——每行由标注任务的测试钉住）

1. **跨片分页切窗**：`LIMIT/OFFSET` 边界恰好落在 NULL 段中间时，归并序与单 MySQL 全表序必须逐格相等（Task 3 cells 8–9）。
2. **窗口 `OVER (ORDER BY 可空列)`**：经 `buildCollation`(L1638) 而非 visitor 的出生点，旋钮应同样生效；若引擎不支持窗口则该格 PARK 并立账，不伪绿（Task 3 cell 12）。
3. **显式 `NULLS FIRST/LAST` 不被过宽改写**：MySQL 原生语法不含此子句，Mycat 经 druid 接受；显式指定必须尊重原意、`(col IS NULL)` 模拟保留（Task 1 守卫测试 + Task 3 cell 11）。
4. **merge-join 键路径不误伤**：规则显式造的 `NullDirection.LAST` 不经旋钮，`generateMergeJoinComparator` 的 assert 不得崩（Task 4 全量套件回归覆盖）。
5. **dumpPlan 文本随动**：collation 由 `NULLS LAST` 翻 `NULLS FIRST` 会改 plan 断言字面量，属修复本相、按实转正（Task 4）。

---

### Task 1: 形状锁 RED 探针（计划下推 SQL 无模拟前缀）

**Files:**
- Create: `internalexample/src/test/java/io.mycat.drdsrunner/OrderByNullCollationShapeTest.java`

**Interfaces:**
- Consumes: `DrdsTest.getDrds()`、`DrdsTest.parse(String sql)` → `Explain.specificSql()`（既有基建，BkaJoinTest 同款用法）
- Produces: 测试类 `OrderByNullCollationShapeTest`（Task 2 以转绿为验收；Task 4 全套件包含它）

- [ ] **Step 1: 写失败的形状测试**（JUnit4，`extends DrdsTest`，复用基类 `parse`/`getDrds`；`@BeforeClass` 参照 BkaJoinTest 重置 `drdsRunner/metadataManager` 与否按需——独立类直接继承即可）：

```java
package io.mycat.drdsrunner;

import org.junit.Assert;
import org.junit.Test;

/** I-DEBT-55 形状锁：裸 ORDER BY 的下推 SQL 不得带 (col IS NULL) 模拟前缀（LOW 方言下 FIRST==原生）。 */
public class OrderByNullCollationShapeTest extends DrdsTest {

    /** 裸 ASC：修复前必红（当前 collation=LAST → deparse 发 (`user_id` IS NULL), `user_id`）。 */
    @Test
    public void bareAscPushdownHasNoIsNullEmulation() throws Exception {
        String sqls = parse("select id, user_id from db1.sharding order by user_id")
                .specificSql().toString();
        Assert.assertTrue("裸 ASC 应下推裸序: " + sqls, sqls.contains("ORDER BY `user_id`"));
        Assert.assertFalse("裸 ASC 不得带模拟前缀: " + sqls,
                sqls.contains("(`user_id` IS NULL)"));
    }

    /** 裸 DESC：同理无模拟。 */
    @Test
    public void bareDescPushdownHasNoIsNullEmulation() throws Exception {
        String sqls = parse("select id from db1.sharding order by days desc")
                .specificSql().toString();
        Assert.assertTrue(sqls, sqls.contains("ORDER BY `days` DESC"));
        Assert.assertFalse(sqls, sqls.contains("(`days` IS NULL)"));
    }

    /** 守卫（防过宽）：显式 NULLS LAST 须尊重原意，模拟前缀必须保留——修复前后都应绿。 */
    @Test
    public void explicitNullsLastKeepsEmulation() throws Exception {
        String sqls = parse("select id from db1.sharding order by user_id nulls last")
                .specificSql().toString();
        Assert.assertTrue("显式 NULLS LAST 应保留模拟: " + sqls,
                sqls.contains("(`user_id` IS NULL)"));
    }
}
```

- [ ] **Step 2: 跑前两个测试，确认 RED**

Run: `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn -o -pl internalexample -am -Dtest=OrderByNullCollationShapeTest test -DfailIfNoTests=false`
Expected: `bareAscPushdownHasNoIsNullEmulation`、`bareDescPushdownHasNoIsNullEmulation` FAIL（断言消息里可见 `ORDER BY (\`user_id\` IS NULL), \`user_id\`` 形状——这就是运行时"无第七出生点"实锤）；`explicitNullsLastKeepsEmulation` PASS。
若 RED 形态不符（如裸序根本没带 ORDER BY），停下用 systematic-debugging 定因，不得继续。

- [ ] **Step 3: Commit（RED 入库，锁基线）**

```bash
git add "internalexample/src/test/java/io.mycat.drdsrunner/OrderByNullCollationShapeTest.java"
git commit -F .superpowers/i55-t1-msg.txt   # 消息文件用 Write 生成，UTF-8
```

---

### Task 2: 单旋钮转正 + 旋钮回归锁

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java:185`（`getValidatorConfig()` 的 return 链）
- Create: `hbt/src/test/java/io/mycat/calcite/OrderByNullCollationConfigTest.java`

**Interfaces:**
- Consumes: Task 1 的三个形状测试（转绿即旋钮生效实证）
- Produces: `SqlValidator.Config` 携带 `defaultNullCollation()==LOW`（Task 3/4 全部行为的地基真值）

- [ ] **Step 1: 写旋钮回归锁（JUnit5，hbt 测试风格）**：

```java
package io.mycat.calcite;

import org.apache.calcite.config.NullCollation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** I-DEBT-55 旋钮锁：全仓唯一 validator config 必须锚 MySQL 的 LOW（NULL 最小值）。 */
public class OrderByNullCollationConfigTest {
    @Test
    public void validatorConfigAnchorsMysqlNullCollation() {
        Assertions.assertEquals(NullCollation.LOW,
                MycatCalciteSupport.INSTANCE.getValidatorConfig().defaultNullCollation(),
                "裸 ORDER BY 默认 null 方向须为 MySQL 语义 LOW");
    }
}
```

- [ ] **Step 2: 跑它，确认 RED**

Run: `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn -o -pl hbt -am -Dtest=OrderByNullCollationConfigTest test`
Expected: FAIL——`expected:<LOW> but was:<HIGH>`

- [ ] **Step 3: 在 `MycatCalciteSupport.getValidatorConfig()` L185 的 return 链上补一项**（`withSqlConformance(...)` 之后任选稳定位置）：

```java
.withDefaultNullCollation(NullCollation.LOW)
```

加 `// MYCAT CHANGE (I-DEBT-55)` 注释块（内容按 spec §3 代码块），import `org.apache.calcite.config.NullCollation`（若未 import）。

- [ ] **Step 4: 跑 Task 1 + Task 2 测试，确认全绿**

Run: `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn -o -pl hbt -am -Dtest=OrderByNullCollationConfigTest test` 然后 `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn -o -pl internalexample -am -Dtest=OrderByNullCollationShapeTest test`
Expected: 双双 PASS（形状锁 RED→GREEN 即"六出生点收口"实证）。

- [ ] **Step 5: Commit**

```bash
git add hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java hbt/src/test/java/io/mycat/calcite/OrderByNullCollationConfigTest.java
git commit -F .superpowers/i55-t2-msg.txt
```

---

### Task 3: 活库对拍矩阵 `OrderByNullCollationMatrixTest`

**Files:**
- Create: `example/src/test/java/io/mycat/compat/OrderByNullCollationMatrixTest.java`

**Interfaces:**
- Consumes: Task 2 的 LOW 真值；`example/src/test/java/io/mycat/compat/` 既有 compat 测试的建库/hint/连接模式（先读 `DumpRestoreRoundTripMatrixTest.java` 的 setup 段照搬其 datasource/schema 构建与清理纪律）；Docker DB1(3306)/DB2(3307)；活服务端 :8066
- Produces: 逐格行序对拍证据（Task 4 护栏包含它；DEBT-QUEUE 收口判据 §8-1）

**裁定（记入账本，不再问）：** 对拍控制组用 DB1 上的物理影子表 `db1.orderby_ctrl`（直连 JDBC 建表灌行、Mycat 不可见）。行集与逻辑表全等 ⇒ 控制组在真 MySQL 上的 `ORDER BY` 输出序即序真值，绕开"两实例跨片 UNION 无处可跑"。单片轴用既有 `db1.normal`（normal 表纯下推，服务端 schema 里现成，BkaJoinTest 同源形状）。

- [ ] **Step 1: 前置体检（不写产品码）**：确认 3306/3307 容器可达、8066 服务端已以 classpath 直启方式起且 settle 完成、`select table_name from information_schema.tables where table_schema='db1'` 经 Mycat 可见 `sharding`/`normal`。若服务端是旧码起的，先重启（旋钮在类里，必须载新编译的 hbt）。
Expected: 三项全通过才继续；缺 normal 表则单片轴降级为 `WHERE id=<种子值>` 单行下推格并在此步记录降级原因。

- [ ] **Step 2: 写失败的对拍矩阵**（JUnit5， compat 系风格）。数据夹具：经 Mycat `DELETE FROM db1.sharding` 清底后插入 8 行（id 1..8；`user_id` NULL 在 id∈{2,5}；`days` NULL 在 id∈{3,6}；id=8 双 NULL；`normal` 表灌同构 4 行子集，id 与 sharding 对齐）；直连 DB1 建 `db1.orderby_ctrl(id BIGINT, user_id VARCHAR(100), days INT)` 灌入**同一 8 行**（列名/类型逐字对齐，行序乱序插入防"插入序==结果序"伪证）。矩阵单元（每格：Mycat 查询 vs 控制查询同 ORDER BY 文本在 orderby_ctrl 上执行，比较 `List<Object>` id 序列逐格相等）：

| # | Mycat SQL（轴） | 控制 SQL |
|---|---|---|
| 1 | `SELECT id FROM db1.normal ORDER BY addressname` （单片裸 ASC） | 同文直连 DB1 在物理表 `db1.normal` 上执行（normal 表本就是物理表，不另建影子） |
| 2 | `... normal ORDER BY addressname DESC` | 同文直连 |
| 3 | `SELECT id FROM db1.sharding ORDER BY user_id` （跨片 ASC） | `... orderby_ctrl ORDER BY user_id` |
| 4 | `... sharding ORDER BY user_id DESC` | `... orderby_ctrl ORDER BY user_id DESC` |
| 5 | `... sharding ORDER BY days` | `... orderby_ctrl ORDER BY days` |
| 6 | `... sharding ORDER BY days DESC` | `... orderby_ctrl ORDER BY days DESC` |
| 7 | `... sharding ORDER BY user_id ASC, id DESC` （多列混合向） | 同文 on ctrl |
| 8 | `... sharding ORDER BY user_id LIMIT 3 OFFSET 2` （切窗含 NULL 边界） | 同文 on ctrl |
| 9 | `... sharding ORDER BY days DESC LIMIT 4` | 同文 on ctrl |
| 10 | `... sharding WHERE user_id IS NULL ORDER BY days` （全 NULL 子集） | 同文 on ctrl |
| 11 | `... sharding ORDER BY user_id NULLS LAST` （显式守卫：预期 **不等**于裸 ASC——NULL 段垫底；控制组取同文在真 MySQL 会语法错，故断言"首行非 NULL 且末行 NULL"自证） | 无（Mycat 侧断言式） |
| 12 | `SELECT id, RANK() OVER (ORDER BY days) r FROM db1.sharding ORDER BY r, id`（窗口轴）——若引擎报不支持：`Assumptions.assumeTrue(false, "窗口不支持→PARK 立账")`，不伪绿 | `... orderby_ctrl` 同文 |

实现骨架：`private static final String[][] CELLS = {sql, controlSql}...` + `@ParameterizedTest @CsvSource("{index}")` 或直接循环；比较前两列先归一化（取 `row.get("id")` 序列）。cell 11 单独方法写断言。

- [ ] **Step 3: 全矩阵跑绿 + 可证伪双检**（先确认 Step 1 的服务端已载新 hbt，旧类必红）

Run: `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn -o -pl example -am -Dtest=OrderByNullCollationMatrixTest test`
Expected: 矩阵在载新 hbt 的服务端上应全 GREEN；随后把旋钮一行临时注释掉、重编译重启服务端重跑，确认 cell 3/5/7/8 红（NULL 段位置偏离）再恢复旋钮行并复绿——先红后绿才钉住"矩阵可证伪"（旋钮已 commit，git stash 摘不动已提交改动，故用临时注释法；注释期间不 commit 任何东西）。

- [ ] **Step 4: 卫生收口**：`git restore server/src/main/resources`（e2e 回写污染），scoped 清理本轮 e2e 临时物；跑完不留 8066 僵尸进程（`Get-NetTCPConnection -LocalPort 8066` 复核）。

- [ ] **Step 5: Commit**

```bash
git add example/src/test/java/io/mycat/compat/OrderByNullCollationMatrixTest.java
git commit -F .superpowers/i55-t3-msg.txt
```

---

### Task 4: 全量护栏 + plan 快照按本相转正

**Files:**
- Modify: 视 RED 捕获面而定（预计 `internalexample/src/test/java/io.mycat.drdsrunner/` 内含 dumpPlan collation 字面量的断言）

**Interfaces:**
- Consumes: Task 2 的 LOW 真值、Task 1/3 测试
- Produces: 全套件绿 + 全 reactor 构建绿（DEBT-QUEUE 收口判据 §8-4）

- [ ] **Step 1: 全量既有套件跑**

Run: `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn -o -pl internalexample -am test` 然后 `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn -o -T1C clean install -DskipTests`
Expected: 首轮 internalexample 可能有 dumpPlan/specificSql 字面量红（`NULLS LAST`→`NULLS FIRST` 文本随动、或 `(col IS NULL)` 前缀消失）。全 reactor 19 模块全 SUCCESS 零 SKIPPED。

- [ ] **Step 2: 对每个红做定性**（systematic-debugging）：凡"形状变红但新形状恰是 spec §4 目标行为"→按本相更新断言字面量（每处加 `// MYCAT CHANGE (I-DEBT-55 再基线): ...` 注释）；凡行为仍偏离 MySQL →是修复缺陷，回 Task 2 定因，不得改断言掩耳。逐处转正后重跑 Step 1 至全绿。
Expected: 无行为偏离类红；转正清单逐处可对照 spec §4 表。

- [ ] **Step 3: 冗余 Sort 观察**：对拍矩阵跨片格的 dumpPlan 里核对 `mergeSort=[true]` 类既有形状未新增多余 `MycatSort` 节点（spec §7 观察项）。异常则记入账本并汇报，不擅自扩面。

- [ ] **Step 4: Commit（若有转正）**

```bash
git add -A -- internalexample/src/test
git commit -F .superpowers/i55-t4-msg.txt
```

---

### Task 5: DEBT-QUEUE 收口回写

**Files:**
- Modify: `docs/superpowers/specs/DEBT-QUEUE.md`（I-DEBT-55 行 + L9 校准线 + 仪表盘）

**Interfaces:**
- Consumes: Task 1–4 全部 commit 哈希与测试证据
- Produces: 驾驶舱状态=本债关闭（判据 §8 五条逐条对号）

- [ ] **Step 1: I-DEBT-55 行改"已关闭"**，附 commit 区间与矩阵格数×证据；L9 校准线追加本轮条目（方案 2 单旋钮、Task 0 探针无第七出生点、转正清单数）；仪表盘对应里程碑格更新。
- [ ] **Step 2: 工作树体检**：`git status` 仅剩 `.qoder/**` 与已忽略项；`server/src/main/resources` 干净。
- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/DEBT-QUEUE.md
git commit -F .superpowers/i55-t5-msg.txt
```

---

## 收口后

回到既定顺序第 2 项：push 出 CI 徽标——**外溢操作，届时停下把命令交给用户拍板，不擅自推**。第 3 项 I-DEBT-56/休眠模块按用户此前口径一批带过。
