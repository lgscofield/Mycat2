# i_s 恒空仿表快速路径 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让真 mysqldump 默认无规避 flag 连 Mycat 能完整导出——修复 D1(JSON_EXTRACT 投影 codegen 崩溃)和 D2(FILES 缺列)——并保证逐值保真回灌。

**Architecture:** 在规划器 HEP 阶段添加折叠规则，把投影子树全叶为静态恒空仿表的情况直接折叠为空 LogicalValues，绕过不可 codegen 的表达式。仿表列集补齐按 D2 取证结果定。

**Tech Stack:** Java 21, Calcite 1.42 fork (calcite-core), Hutool/Druid 解析, RxJava Observable, JUnit 5, Docker MySQL 8.0

**Spec:** `docs/superpowers/specs/2026-10-17-is-empty-visual-table-fastpath-design.md`

## Global Constraints

- JDK 21 须显式 `JAVA_HOME=D:\Program Files\BellSoft\LibericaJDK-21-Full`
- PowerShell 无 grep/head，用 Select-String/Select-Object
- git commit 用 `-F <file>` 保 UTF-8
- 仅 stage 本轮改动文件（工作区有 ~156 无关改动）
- 不 push、不改 CI workflow

## Review Focus

1. JSON_EXTRACT 在**非恒空**表（如带数据的手工测试表）上仍会崩溃 → 验证折叠谓词的严格性（掺真实叶子不折叠）
2. rowType 对齐：折叠后空结果的列名/列序/类型须与真 MySQL 0 行结果一致 → 校验 mysqldump 客户端列头解析
3. D2 缺列假设：`EXTRA` 可能非 FILES 特有而是 COLUMNS 路径 → 先取证再定，避免补错表
4. HepPlanner matchLimit 命中：折叠规则若有循环匹配风险 → 规则 transformTo 后原节点已消失，理论上不会自环
5. 跨 convention 匹配：规则在 HEP 阶段匹配 Project(NONE) → MycatTableScan(MYCAT2) → 检查 HepPlanner 是否允许跨 convention operand

---

### Task 1: 静态恒空标记

**Files:**
- Modify: `hbt/src/main/java/io/mycat/prototypeserver/mysql/VisualTableHandler.java`
- Modify: `hbt/src/main/java/io/mycat/MysqlMetadataManager.java`

**Interfaces:**
- Consumes: 现有 `VisualTableHandler.createByMySQL(String sql, Supplier<Observable<Object[]>> rowsProvider)`
- Produces: `VisualTableHandler.isStaticallyEmpty() → boolean`（规则判定依赖）; `VisualTableHandler.createStaticallyEmptyByMySQL(String sql) → VisualTableHandler`（便捷工厂）

- [ ] **Step 1: 写失败测试——验证恒空标记**

```java
// hbt/src/test/java/io/mycat/EmptyVisualTableMarkerTest.java
package io.mycat;

import io.mycat.prototypeserver.mysql.VisualTableHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class EmptyVisualTableMarkerTest {
    @Test
    public void columnStatisticsIsStaticallyEmpty() {
        VisualTableHandler handler = MysqlMetadataManager.COLUMN_STATISTICS_TABLE_HANDLER;
        assertTrue(handler.isStaticallyEmpty(),
            "COLUMN_STATISTICS 用 () -> Observable.empty() 构造，应标记为恒空");
    }

    @Test
    public void enginesIsNotStaticallyEmpty() {
        VisualTableHandler handler = MysqlMetadataManager.ENGINES_TABLE_HANDLER;
        assertFalse(handler.isStaticallyEmpty(),
            "ENGINES 有实数据行，不应标记为恒空");
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `mvn test -pl hbt -Dtest=EmptyVisualTableMarkerTest -DfailIfNoTests=false`
Expected: 编译错误 `isStaticallyEmpty` 方法不存在

- [ ] **Step 3: 实现 `staticallyEmpty` 字段与 `isStaticallyEmpty()` 方法**

在 `VisualTableHandler` 添加 `private final boolean staticallyEmpty` 字段。
- 现有 `createByMySQL(sql, rowsProvider)` 保持不变，构造匿名类时 `staticallyEmpty = false`（向后兼容）。
- 新增 `createStaticallyEmptyByMySQL(String sql)` 工厂方法：内部调 `createByMySQL(sql, () -> Observable.empty())`，但设置 `staticallyEmpty = true`。
  实现方式：将现有 `createByMySQL` 改为接受一个额外的 `boolean staticallyEmpty` 参数（package-private），public 方法保持两参数签名委托传 `false`；新方法传 `true`。
- 添加 `public boolean isStaticallyEmpty() { return staticallyEmpty; }`

- [ ] **Step 4: 改写 MysqlMetadataManager 恒空 callsites**

将 `COLUMN_STATISTICS_TABLE_HANDLER`（L238）、`FILE_TABLE_HANDLER`（L240）、`INNODB_DATAFILES_TABLE_HANDLER`（L241）、`INNODB_FIELDS_TABLE_HANDLER`（L242）、`EVENTS_TABLE_HANDLER`（L236）等使用 `() -> Observable.empty()` 的行，改为 `VisualTableHandler.createStaticallyEmptyByMySQL(...)` 调用。`addVisualTableIfAbsent`（L715-727）内同样改。

- [ ] **Step 5: 运行测试验证通过**

Run: `mvn test -pl hbt -Dtest=EmptyVisualTableMarkerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add hbt/src/main/java/io/mycat/prototypeserver/mysql/VisualTableHandler.java hbt/src/main/java/io/mycat/MysqlMetadataManager.java hbt/src/test/java/io/mycat/EmptyVisualTableMarkerTest.java
git commit -F .superpowers/commit-msg-isfastpath-t1.txt
```

---

### Task 2: D1 折叠规则 + 注册 HEP

**Files:**
- Create: `hbt/src/main/java/io/mycat/calcite/rules/EmptyVisualTableSubtreeRule.java`
- Modify: `hbt/src/main/java/io/mycat/DrdsSqlCompiler.java:492-531`（`runLogicalPushdownFixedPoint`）

**Interfaces:**
- Consumes: `VisualTableHandler.isStaticallyEmpty()`（Task 1）; `MycatTableScan.relNode → TableScan → MycatLogicTable.table`
- Produces: `EmptyVisualTableSubtreeRule.INSTANCE`（注册在 HepProgramBuilder）

- [ ] **Step 1: 写失败测试——直接用规划器 API 触发**

```java
// hbt/src/test/java/io/mycat/calcite/EmptyVisualTableSubtreeRuleTest.java
package io.mycat.calcite;

import io.mycat.DrdsSql;
import io.mycat.OptimizationContext;
import io.mycat.config.MycatConfig;
import io.mycat.config.SchemaConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 离线规划器锁：JSON_EXTRACT 投影 over COLUMN_STATISTICS 不再抛 Unable to implement。
 * mode=local 无需活后端，只验证规划+折叠。
 */
public class EmptyVisualTableSubtreeRuleTest {
    static DrdsSqlCompiler compiler;

    @BeforeAll
    static void setup() {
        // 初始化最小 schema 含 information_schema.COLUMN_STATISTICS
        MycatConfig config = new MycatConfig();
        SchemaConfig sc = new SchemaConfig();
        sc.setName("information_schema");
        // ... (使用 MysqlMetadataManager 已有注册)
        compiler = DrdsSqlCompiler.createForLocalTest(sc);
    }

    @Test
    public void jsonExtractProjectionFoldsToEmpty() {
        String sql = "SELECT COLUMN_NAME, JSON_EXTRACT(HISTOGRAM, '$.\"number-of-buckets-specified\"') " +
                     "FROM information_schema.COLUMN_STATISTICS WHERE SCHEMA_NAME='test' AND TABLE_NAME='t1'";
        // 编译不抛异常
        assertDoesNotThrow(() -> {
            var plan = compiler.compileForTest(DrdsSql.of(sql), new OptimizationContext());
            assertNotNull(plan);
            assertEquals(2, plan.getRowType().getFieldCount());
            assertEquals("COLUMN_NAME", plan.getRowType().getFieldNames().get(0));
        });
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl hbt -Dtest=EmptyVisualTableSubtreeRuleTest`
Expected: 抛出 `IllegalStateException: Unable to implement MycatProject(...)` — RED

- [ ] **Step 3: 实现 `EmptyVisualTableSubtreeRule`**

在 `io.mycat.calcite.rules` 包新建规则类：

```java
public class EmptyVisualTableSubtreeRule extends RelRule<EmptyVisualTableSubtreeRule.Config> {
    public static final EmptyVisualTableSubtreeRule INSTANCE = ...;

    @Override
    public boolean matches(RelOptRuleCall call) {
        Project project = call.rel(0);
        return allLeavesAreStaticallyEmptyVisual(project.getInput());
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Project project = call.rel(0);
        RelNode emptyValues = LogicalValues.create(
            project.getCluster(), project.getRowType(), ImmutableList.of());
        call.transformTo(emptyValues);
    }

    // 递归判定：子树全部叶子是 MycatTableScan(visual, staticallyEmpty=true)
    private static boolean allLeavesAreStaticallyEmptyVisual(RelNode node) { ... }
}
```

`allLeavesAreStaticallyEmptyVisual` 用 `RelShuttleImpl` 遍历所有叶子：每个 `MycatTableScan` → 取 `relNode`(TableScan) → `unwrap(MycatLogicTable)` → `getTable()` instanceof `VisualTableHandler` → `.isStaticallyEmpty()`。遇到非 `MycatTableScan` 的叶子（如 `TableScan` with `Convention.NONE`、`LogicalValues`）返回 false。

- [ ] **Step 4: 注册到 `runLogicalPushdownFixedPoint`**

在 `DrdsSqlCompiler.java:492-531` 的 `builder` 定义中，在**视图下推组之前**添加一个前置组：

```java
// 恒空仿表折叠：全叶静态恒空时直接替换为空 Values，绕过投影 codegen
builder.addMatchLimit(64).addMatchOrder(HepMatchOrder.BOTTOM_UP);
builder.addGroupBegin()
        .addRuleInstance(EmptyVisualTableSubtreeRule.INSTANCE)
        .addGroupEnd();
```

- [ ] **Step 5: 编译验证通过**

Run: `mvn compile -pl hbt`
Expected: BUILD SUCCESS

- [ ] **Step 6: 运行测试验证 GREEN**

Run: `mvn test -pl hbt -Dtest=EmptyVisualTableSubtreeRuleTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add hbt/src/main/java/io/mycat/calcite/rules/EmptyVisualTableSubtreeRule.java hbt/src/main/java/io/mycat/DrdsSqlCompiler.java hbt/src/test/java/io/mycat/calcite/EmptyVisualTableSubtreeRuleTest.java
git commit -F .superpowers/commit-msg-isfastpath-t2.txt
```

---

### Task 3: D1 严格性守卫测试（掺真实叶子不折叠）

**Files:**
- Modify: `hbt/src/test/java/io/mycat/calcite/EmptyVisualTableSubtreeRuleTest.java`（添加用例）

**Interfaces:**
- Consumes: Task 2 产出的规则

- [ ] **Step 1: 添加混合子树测试——仍应崩溃（不折叠）**

在 `EmptyVisualTableSubtreeRuleTest` 中追加：

```java
@Test
public void mixedLeavesNotFolded() {
    // 子树含真实后端表 + 恒空仿表 → 不折叠，仍走原路径
    // 这里用 join 构造混合场景：COLUMN_STATISTICS × 分片表
    String sql = "SELECT cs.COLUMN_NAME FROM information_schema.COLUMN_STATISTICS cs " +
                 "JOIN testdb.t1 ON cs.TABLE_NAME = t1.id";
    // 此场景不应被折叠，但由于 t1 需要活库连接，验证折叠规则不误匹配即可：
    // 检查规划器是否产生了非 Values 的计划（含 Join），不需要运行到 codegen
    assertDoesNotThrow(() -> {
        var plan = compiler.compileForTest(DrdsSql.of(sql), new OptimizationContext());
        // 若被误折叠则 plan.getRowType().getFieldCount()==0 且无 join；守卫断言应见 join
        assertFalse(plan instanceof org.apache.calcite.rel.core.Values,
            "混合子树不应被折叠为 Values");
    });
}
```

注意：若分片表 `testdb.t1` 在 local mode 下不可用，此用例可改为单元测试直接调 `allLeavesAreStaticallyEmptyVisual`，验证它对含非空叶子的树返回 false。

- [ ] **Step 2: 运行验证**

Run: `mvn test -pl hbt -Dtest=EmptyVisualTableSubtreeRuleTest#mixedLeavesNotFolded`
Expected: PASS（守卫生效，混合场景不误折叠）

- [ ] **Step 3: Commit**

```bash
git add hbt/src/test/java/io/mycat/calcite/EmptyVisualTableSubtreeRuleTest.java
git commit -F .superpowers/commit-msg-isfastpath-t3.txt
```

---

### Task 4: D2 复现——捕获 tablespaces 确切查询

**Files:**
- Create: `.superpowers/m3-dumprt/d2-tablespaces-repro.txt`（取证记录）

**Interfaces:**
- Consumes: Docker mysql:8.0 真 mysqldump; Mycat 8066 运行态
- Produces: tablespaces 查询的确切 SQL 文本 + 缺失列清单

- [ ] **Step 1: 启动 Mycat 并确认可用**

Run: 使用已有 fat-jar 或 classpath 启动方式确保 Mycat 8066 在跑（容器 m1-mysql-a/b 可达）
Expected: `mysql -h host.docker.internal -P 8066 -u test1 -p123456 -e "SELECT 1"` 返回 1

- [ ] **Step 2: 用真 mysqldump 不加 --no-tablespaces 直连**

```powershell
docker run --rm mysql:8.0 mysqldump -h host.docker.internal -P 8066 -u test1 -p123456 `
  --get-server-public-key --ssl-mode=DISABLED --no-create-info --skip-data `
  testdb 2>&1 | Select-String -Pattern "EXTRA|tablespace|FILES" -Context 2
```

Expected: 发现 `Column 'EXTRA' not found` 错误上下文；若 mysqldump 日志/verbose 模式 (`-v -v -v`) 输出确切 SELECT 则记录之。

- [ ] **Step 3: 若无法直接捕获 SQL，从 mysqldump 源码确认**

检查 `mysqldump80.cc`（缓存 `.superpowers/m3-dumprt/mysqldump80.cc`）的 tablespaces 函数，定位引用 `EXTRA` 的确切行。若源码不引用 `EXTRA`，则错误可能来自 `COLUMNS.EXTRA` 路径或 MariaDB 版本差异，据实记录。

- [ ] **Step 4: 记录取证结果**

将结论写入 `.superpowers/m3-dumprt/d2-tablespaces-repro.txt`：确切 SELECT 文本、缺失列名、所属仿表。

- [ ] **Step 5: Commit（取证文档）**

```bash
git add .superpowers/m3-dumprt/d2-tablespaces-repro.txt
git commit -F .superpowers/commit-msg-isfastpath-t4.txt
```

---

### Task 5: D2 修复——补 FILES 缺失列 + 列集锁

**Files:**
- Modify: `hbt/src/main/java/io/mycat/MysqlMetadataManager.java`（`FILE_TABLE()` 方法）
- Modify: `hbt/src/test/java/io/mycat/DumpSurfaceRegressionTest.java`（`filesTableCoversTablespaceQuery`）

**Interfaces:**
- Consumes: Task 4 的取证结果（确切缺失列）
- Produces: 补全后的 `FILE_TABLE()` DDL + 扩展的列集断言

- [ ] **Step 1: 扩展 DumpSurfaceRegressionTest 列集断言**

根据 Task 4 结果，在 `filesTableCoversTablespaceQuery` 的 `assertContainsAll` 中追加缺失列（例 `"EXTRA"`，如取证确认）。

```java
@Test
public void filesTableCoversTablespaceQuery() {
    assertContainsAll("FILES", MysqlMetadataManager.FILE_TABLE_HANDLER,
            "TABLESPACE_NAME", "FILE_TYPE", "LOGFILE_GROUP_NAME", "FILE_NAME",
            "ENGINE", "TOTAL_EXTENTS", "INITIAL_SIZE", "MAXIMUM_SIZE", "AUTOEXTEND_SIZE",
            "EXTRA");  // ← Task 4 坐实
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn test -pl hbt -Dtest=DumpSurfaceRegressionTest#filesTableCoversTablespaceQuery`
Expected: FAIL — "FILES 缺 mysqldump 消费列: EXTRA"

- [ ] **Step 3: 在 `FILE_TABLE()` 补列**

在 `MysqlMetadataManager.java` 的 `FILE_TABLE()` 方法（L956-989）末尾、`return` 语句前添加：

```java
// D2: mysqldump --tablespaces 消费列
createEVENTSTableSQL.addColumn("EXTENT_SIZE", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("AUTOEXTEND_SIZE_FILES", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("CREATION_TIME", "datetime");
createEVENTSTableSQL.addColumn("LAST_UPDATE_TIME", "datetime");
createEVENTSTableSQL.addColumn("LAST_ACCESS_TIME", "datetime");
createEVENTSTableSQL.addColumn("RECOVER_TIME", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("TRANSACTION_COUNTER", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("VERSION", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("ROW_FORMAT", "varchar(64)");
createEVENTSTableSQL.addColumn("TABLE_ROWS", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("AVG_ROW_LENGTH", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("DATA_LENGTH", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("MAX_LENGTH", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("INDEX_LENGTH", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("DATA_FREE", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("CREATE_TIME", "datetime");
createEVENTSTableSQL.addColumn("UPDATE_TIME", "datetime");
createEVENTSTableSQL.addColumn("CHECK_TIME", "datetime");
createEVENTSTableSQL.addColumn("CHECKSUM", "bigint(21) unsigned");
createEVENTSTableSQL.addColumn("STATUS", "varchar(64)");
createEVENTSTableSQL.addColumn("EXTRA", "varchar(255)");
```

（注：具体列清单按 Task 4 取证结果调整；上面按 MySQL 官方 `i_s.FILES` 全列补全。若 D1 折叠规则已使 tablespaces 查询全叶恒空被绕过，D2 列补仍需要：它保证列头在折叠前/未折叠场景下的正确性。）

- [ ] **Step 4: 运行测试验证 GREEN**

Run: `mvn test -pl hbt -Dtest=DumpSurfaceRegressionTest`
Expected: PASS (all methods green)

- [ ] **Step 5: Commit**

```bash
git add hbt/src/main/java/io/mycat/MysqlMetadataManager.java hbt/src/test/java/io/mycat/DumpSurfaceRegressionTest.java
git commit -F .superpowers/commit-msg-isfastpath-t5.txt
```

---

### Task 6: 端到端金标准——真 mysqldump 默认口径完整导出

**Files:**
- Create: `.superpowers/m3-dumprt/d1d2-e2e-evidence.txt`

**Interfaces:**
- Consumes: Mycat 8066 运行态 + Docker mysql:8.0 原生

- [ ] **Step 1: 正向——真 mysqldump 默认无规避 flag 连 Mycat**

```powershell
docker run --rm mysql:8.0 mysqldump -h host.docker.internal -P 8066 -u test1 -p123456 `
  --get-server-public-key --ssl-mode=DISABLED `
  --databases testdb > d1d2_export.sql 2>d1d2_err.txt
$LASTEXITCODE
```

Expected: `RC=0`（不再出现 `Unable to implement MycatProject` 或 `Column 'EXTRA' not found`）。
若仍有错误，记录 stderr 内容。

- [ ] **Step 2: 反向——回灌原生 MySQL 验证逐值保真**

```powershell
docker exec -i m1-mysql-b mysql -uroot -p123456 < d1d2_export.sql
```

Expected: 无 ERROR，逐值保真。

- [ ] **Step 3: 取证结果写入 evidence 文件**

记录命令、RC、导出文件行数、关键对象（含 bit 列的表除外，那些属 D3 另立专轮）。

- [ ] **Step 4: 回写 DEBT-QUEUE 仪表盘**

更新 `docs/superpowers/specs/DEBT-QUEUE.md`：
- I-DEBT-54 缩窄为"仅剩 D3（binary 字面量表征链）"
- M3 完成度标注"D1/D2 本地✅（真 mysqldump 默认口径打通）/ CI⏳"

- [ ] **Step 5: Commit**

```bash
git add .superpowers/m3-dumprt/d1d2-e2e-evidence.txt docs/superpowers/specs/DEBT-QUEUE.md
git commit -F .superpowers/commit-msg-isfastpath-t6.txt
```
