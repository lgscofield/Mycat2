# Druid 影子类清理（不 fork）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 5 类（6 个文件）与官方 druid 1.2.28 同全限定名的影子类，把 4 个 Mycat 自增类搬出 druid 包，使 `MycatOutputVisitor` 直接继承官方 visitor，解锁 L1 连接层（客户端真能拿到一条 SQL 的结果集）。

**Architecture:** druid 回归为普通第三方依赖（不建 fork 模块）。先采集"SQL 序列化黄金基线"锁住删除前的行为，再搬 Mycat 类、删影子、改继承，最后用基线对账 + 端到端 L1 探针证明行为未倒退。

**Tech Stack:** Java 21、Maven 多模块（`io.mycat:parent:1.22-release`）、`com.alibaba:druid:1.2.28`、JUnit 4（hbt 测试沿用 `org.junit.Test`）、bash + Docker MySQL（L1 探针）。

**Spec:** `docs/superpowers/specs/2026-09-30-druid-shadow-removal-design.md`

## Global Constraints

- JDK 21；`JAVA_HOME=D:\Program Files\BellSoft\LibericaJDK-21-Full`（本机）。
- druid 版本单一真源：根 `pom.xml` 的 `<druid.version>1.2.28</druid.version>`，**本计划不改任何 druid 坐标或版本**。
- **不新增 Maven 模块**（已裁定不建 `druid-core`）。
- PowerShell 下 Maven 的 `-D` 属性必须整体加单引号：`'-Dmaven.test.skip=true'`（不加会被拆词，报 `Unknown lifecycle phase '.test.skip=true'`）。
- 构建产物门一律用 `install '-Dmaven.test.skip=true'`，**不用 `-DskipTests`**（后者仍编译全仓测试，会被无关历史坏测试连累）。
- hbt 模块测试用 **JUnit 4** 风格（对齐 `hbt/src/test/java/io/mycat/calcite/rewriter/ColumnMappingTest.java:19-20` 的 `org.junit.Assert`/`org.junit.Test`）。
- 看板状态只允许 `Evidenced-Done`，须附可重跑命令与实测输出；禁止凭 commit message 标 Done。
- 上游补丁痕迹沿用既有约定注释前缀 `// MYCAT CHANGE: <原因>`（现存 25 处先例）。

## Review Focus

spec 隐含、但没有哪个任务的测试天然覆盖、且最可能伤到使用者的输入类。每条后已把钉住它的测试放进归属任务：

1. **参数化语句里的 `?` 占位符**（`SELECT ... WHERE id = ?`）：使用者期望代理正常下发而不抛 `UnsupportedOperationException`。→ Task 1 语料第 15 条 + Task 4 对账。
2. **Mycat 用自造 `Timestamp` 替换官方 `now` 函数**（`MycatSQLEvalVisitorUtils.java:138`）：搬迁时漏掉注册会让 `now` 常量折叠静默变味。→ Task 2 Step 6 的注册断言 + Task 1 语料第 8 条。
3. **Connector/J 会话初始化 SQL**（`SELECT @@session.auto_increment_increment, ...`）：这正是当初崩 `NoSuchMethodError` 的输入，必须端到端复现修复。→ Task 5 的 L1 探针。
4. **删除两行 `Class.forName` 预热**（`MycatCore.java:75-76`，2021-07-26 与影子同期加入）：使用者期望首条查询不因缺少 eager init 而异常或显著变慢。→ Task 5 探针即首条查询的真实路径 + Task 6 G5 回归。
5. **官方 1.2.28 解析器与 2017 旧拷贝对 MySQL 方言扩展（Hint、`<=>`、`GROUP_CONCAT ... SEPARATOR`、反引号标识符）的输出差异**：期望仍原样可解析并语义等价输出。→ Task 1 语料第 3/4/5/6 条 + Task 4 逐条裁定。

---

### Task 1: SQL 序列化黄金基线（必须在任何删除动作之前）

**Files:**
- Create: `hbt/src/test/java/io/mycat/sql/DruidOutputGoldenTest.java`
- Create: `hbt/src/test/resources/io/mycat/sql/golden-corpus.txt`
- Create: `hbt/src/test/resources/io/mycat/sql/druid-output-baseline.txt`（由采集步骤生成）

**Interfaces:**
- Consumes: `com.alibaba.druid.sql.MycatSQLUtils.toString(SQLStatement): String`（Task 2 才改名，本任务用现状 FQCN）、`com.alibaba.druid.sql.SQLUtils.parseSingleMysqlStatement(String): SQLStatement`
- Produces: 测试类 `io.mycat.sql.DruidOutputGoldenTest`，含 `@Test public void goldenOutputMatchesBaseline()`；两个 classpath 资源 `io/mycat/sql/golden-corpus.txt`、`io/mycat/sql/druid-output-baseline.txt`；开关系统属性 `golden.update=true` 表示重写基线。基线格式：**每条一行**，内容为 `<语料序号>\t<输出字符串，其中换行替换为字面量 \n>`，UTF-8。

- [ ] **Step 0: 装齐 hbt 的上游模块（否则 `-pl hbt` 解析不到 `plug`）**

Run: `mvn -B install '-Dmaven.test.skip=true' -pl hbt -am`
Expected: BUILD SUCCESS。此后 `mvn -pl hbt` 从本地仓库 `D:\Nexus\repository\io\mycat\plug\1.22-release\plug-1.22-release.jar` 取 plug 产物。**并在此时记录 G5 对照基准**：跑 `mvn -B test -pl router,executor,hbt`，把汇总的 `Tests run` / `failures` / `errors` 三个数字抄进账本（Task 6 Step 1 要靠它判"无回退"）。

- [ ] **Step 1: 写语料文件 `golden-corpus.txt`**（每行一条 SQL，共 17 条，不含空行与注释行）

覆盖：`SELECT * FROM t1 WHERE id = 1`；`SELECT a, b FROM sharding_tab WHERE id IN (1, 2, 3)`；`SELECT * FROM t1 WHERE name <=> 'abc'`；`SELECT /*!50000 STRAIGHT_JOIN */ a FROM t1`；`SELECT GROUP_CONCAT(DISTINCT name ORDER BY name SEPARATOR ',') FROM t1 GROUP BY id`；``SELECT `order`, `group` FROM `my table` WHERE `id` = 1``；`SELECT id FROM t1 LIMIT 10, 20`；`SELECT 1 + 2, NOW() FROM DUAL`；`CREATE INDEX idx_a ON t1 (a, b)`；`CREATE TABLE t1 (id INT PRIMARY KEY, name VARCHAR(20))`；`ALTER TABLE t1 ADD COLUMN c1 INT`；`INSERT INTO t1 (id, name) VALUES (1, 'x')`；`UPDATE t1 SET name = 'y' WHERE id = 1`；`DELETE FROM t1 WHERE id IN (1, 2)`；`SELECT * FROM t1 WHERE id = ? AND name = ?`；`SET autocommit = 1, NAMES utf8mb4`；`SELECT * FROM t1 JOIN t2 ON t1.id = t2.id WHERE t2.x > 5`。

- [ ] **Step 2: 写失败测试**

`DruidOutputGoldenTest`：读 corpus 逐条 `MycatSQLUtils.toString(SQLUtils.parseSingleMysqlStatement(sql))`；读 baseline 比对；不等则 `Assert.fail("golden diff #" + i + ": " + sql + " expected=<...> actual=<...>")`。当 `Boolean.getBoolean("golden.update")` 为真时改为写回 baseline 并断言条目数 == 语料条数。

采集模式（`golden.update=true`）下额外断言影子确已胜出：取 `com.alibaba.druid.sql.visitor.SQLASTOutputVisitor.class.getProtectionDomain().getCodeSource().getLocation().toString()`，断言它包含 `plug`（`plug-1.22-release.jar` 或 `plug/target/classes` 皆可）**且不含** `druid-1.2.28.jar`，并把该字符串打印到 stdout。影子胜出的依据是 `hbt/pom.xml` 中 `plug`（:22）声明早于 `druid`（:45），classpath 前者优先。

- [ ] **Step 3: 运行验证它失败**

Run: `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest'`
Expected: FAIL —— `java.lang.IllegalStateException` 或断言失败，报 `druid-output-baseline.txt` 不存在/为空。

- [ ] **Step 4: 采集基线（此时影子仍在，行为 = 删除前）**

Run: `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest' '-Dgolden.update=true'`
Expected: PASS，且日志含 `visitor from ...plug...`（证明基线取的是**影子胜出**的那份实现，这是后续对账的锚）。若该路径指向 `druid-1.2.28.jar`，说明当前 classpath 下官方已胜出，**停止并回报**——基线锚点不成立，Task 4 的对账将失去意义。

- [ ] **Step 5: 复跑确认可重放**

Run: `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest'`
Expected: PASS（17 条全绿）

- [ ] **Step 6: 提交**

```bash
git add hbt/src/test/java/io/mycat/sql/DruidOutputGoldenTest.java hbt/src/test/resources/io/mycat/sql/
git commit -m "test(M2-1): druid SQL 序列化黄金基线，锁定影子删除前行为"
```

---

### Task 2: Mycat 自增类搬出 druid 包

**Files:**
- Move: `hbt/src/main/java/com/alibaba/druid/sql/MycatSQLUtils.java` → `hbt/src/main/java/io/mycat/sql/MycatSQLUtils.java`
- Move: `hbt/src/main/java/com/alibaba/druid/sql/dialect/mysql/visitor/MycatOutputVisitor.java` → `hbt/src/main/java/io/mycat/sql/visitor/MycatOutputVisitor.java`
- Move: `hbt/src/main/java/com/alibaba/druid/sql/visitor/MycatSQLEvalVisitorUtils.java` → `hbt/src/main/java/io/mycat/sql/visitor/MycatSQLEvalVisitorUtils.java`
- Move: `hbt/src/main/java/com/alibaba/druid/sql/visitor/functions/Timestamp.java` → `hbt/src/main/java/io/mycat/sql/visitor/functions/Timestamp.java`
- Delete: `plug/src/main/java/com/alibaba/druid/MycatSQLEvalVisitorUtils.java`、`plug/src/main/java/com/alibaba/druid/Timestamp.java`（B 组的跨模块重复副本）
- Modify: `hbt/src/main/java/io/mycat/DrdsSql.java:24`、`hbt/src/main/java/io/mycat/vertx/VertxExecuter.java:30`、`server/src/main/java/io/mycat/sqlhandler/dml/SetSQLHandler.java:19`、`server/src/main/java/io/mycat/config/MycatRouterConfigOps.java:21`、`server/src/main/java/io/mycat/sqlhandler/ddl/CreateIndexSQLHandler.java:17`

**Interfaces:**
- Consumes: 官方 druid 类型（`SQLASTOutputVisitor`、`functions.*`）与仍存在的 `plug` 影子（Task 3 才删）
- Produces（**方法签名一律不变，仅 FQCN 变**）：`io.mycat.sql.MycatSQLUtils.toString(SQLStatement): String`；`io.mycat.sql.visitor.MycatOutputVisitor(StringBuilder)` / `MycatOutputVisitor(StringBuilder, boolean)`；`io.mycat.sql.visitor.MycatSQLEvalVisitorUtils`（原有 `public static` 方法集不变）；`io.mycat.sql.visitor.functions.Timestamp`（`public final static Timestamp instance`，`implements Function`）

- [ ] **Step 1: 先给 `now` 注册加一条断言测试（RED 起点）**

在 `DruidOutputGoldenTest` 增 `@Test public void nowFunctionIsMycatTimestampNotOfficial()`：取语料第 8 条 `SELECT 1 + 2, NOW() FROM DUAL` 的参数化求值路径，断言 `MycatSQLEvalVisitorUtils` 的 `functions` 映射中 `"now"` 对应的实例 `getClass().getName()` 等于搬迁后的 `io.mycat.sql.visitor.functions.Timestamp`。因 `functions` 是 `private static Map`，用反射读取（`getDeclaredField("functions")` + `setAccessible(true)`）。

Run: `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest#nowFunctionIsMycatTimestampNotOfficial'`
Expected: FAIL —— 断言得到 `com.alibaba.druid.sql.visitor.functions.Timestamp`，与期望的 `io.mycat...` 不符（搬迁前本就该失败，证明该测试真的在量搬迁）。

- [ ] **Step 2: 比对 B 组跨模块重复副本，再决定删哪份**

Run（成对比较，**不要用 `git diff --no-index`**，它对 2000 行级文件会长时间阻塞终端）：

```powershell
$diff = Compare-Object (Get-Content "plug/src/main/java/com/alibaba/druid/MycatSQLEvalVisitorUtils.java") (Get-Content "hbt/src/main/java/com/alibaba/druid/sql/visitor/MycatSQLEvalVisitorUtils.java")
$diff.Count
```

同法比 `plug/src/main/java/com/alibaba/druid/Timestamp.java` vs `hbt/src/main/java/com/alibaba/druid/sql/visitor/functions/Timestamp.java`。

Expected: 各自输出差异行数。为 `0`（完全相同）→ 按 spec D2 删 `plug` 副本保留 `hbt` 副本（hbt 的包名与官方目录结构一致）。若不为 `0` → 用 `$diff | Format-Table -AutoSize` 逐条读差异，把保留哪份的决定以 `Task 2: Ruling: <finding> — <决定> — <代价>` 记入账本后再删。禁止凭"行数多的那份"猜。

- [ ] **Step 3: 执行搬迁（4 个 `git mv` + 改 4 处 `package` 行）**

`package com.alibaba.druid.sql;` → `package io.mycat.sql;`；`package com.alibaba.druid.sql.dialect.mysql.visitor;` → `package io.mycat.sql.visitor;`；`package com.alibaba.druid.sql.visitor;` → `package io.mycat.sql.visitor;`；`package com.alibaba.druid.sql.visitor.functions;` → `package io.mycat.sql.visitor.functions;`。

- [ ] **Step 4: 补搬迁引入的内部 import**

`io.mycat.sql.MycatSQLUtils` 增 `import io.mycat.sql.visitor.MycatOutputVisitor;`（替代原 `com.alibaba.druid...` 同包隐式引用，见原文件 :4）。
`io.mycat.sql.visitor.MycatOutputVisitor` 保留 `import com.alibaba.druid.sql.visitor.SQLASTOutputVisitor;`（Task 3 前仍指向影子，之后指向官方，import 行不变）。
`io.mycat.sql.visitor.MycatSQLEvalVisitorUtils` 保留 `import com.alibaba.druid.sql.visitor.functions.*;`（取官方函数），**并新增** `import io.mycat.sql.visitor.functions.Timestamp;` 以接住 :138 的 `functions.put("now", Timestamp.instance)`。

- [ ] **Step 5: 更新 5 处消费方 import 为新 FQCN**

`DrdsSql.java:24`、`VertxExecuter.java:30`、`SetSQLHandler.java:19`、`MycatRouterConfigOps.java:21`、`CreateIndexSQLHandler.java:17` 各改 import 行，其余代码不动。同样更新 `hbt/src/test/java/io/mycat/sql/DruidOutputGoldenTest.java` 里对 `com.alibaba.druid.sql.MycatSQLUtils` 的 import（Task 1 建的测试也引用了它）。`MycatInsertExecutor.java:15` 的那行 import 已被注释，不动。

- [ ] **Step 6: 运行测试与编译验证**

Run: `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest'` 然后 `mvn -B install '-Dmaven.test.skip=true' -pl server -am`
Expected: 两条均 SUCCESS；Step 1 的 `nowFunctionIsMycatTimestampNotOfficial` 由 FAIL 转 **PASS**；17 条黄金基线**必须仍全绿**（本任务只搬名不改行为，若出现 golden diff，说明搬迁意外改了行为，回到 Step 4 查 import）。

若 `mvn` 报 `... is not public in com.alibaba.druid.sql.visitor` / `has private access`：按 spec D2 例外条款，把该类放回 druid 包树下的 `com.alibaba.druid.sql.visitor.mycat`（类名不变），改回该类 import 并记录 `Task 2: Ruling: <类名> 需包级私有访问 — 落 ...visitor.mycat — <代价>`。

- [ ] **Step 7: 提交**

```bash
git add -A hbt/src/main/java/io/mycat/sql hbt/src/main/java/com/alibaba/druid server/src/main/java/io/mycat hbt/src/main/java/io/mycat plug/src/main/java/com/alibaba/druid
git commit -m "refactor(M2-1): Mycat 自增 druid 类搬出 com.alibaba.druid 包至 io.mycat.sql.*"
```

---

### Task 3: 删除 5 类真影子 + `MycatOutputVisitor` 改继承官方 + 移除抢加载 hack

**Files:**
- Delete: `plug/src/main/java/com/alibaba/druid/SQLASTOutputVisitor.java`、`plug/src/main/java/com/alibaba/druid/SQLExprParser.java`、`plug/src/main/java/com/alibaba/druid/SQLHexExpr.java`、`plug/src/main/java/com/alibaba/druid/ExportParameterVisitorUtils.java`、`plug/src/main/java/com/alibaba/druid/SQLIndexDefinition.java`、`hbt/src/main/java/com/alibaba/druid/sql/ast/SQLIndexDefinition.java`
- Modify: `hbt/src/main/java/io/mycat/sql/visitor/MycatOutputVisitor.java`（构造器 + 漂移的 override）
- Modify: `server/src/main/java/io/mycat/MycatCore.java:75-76`
- Modify: `common/pom.xml`（仅当 Step 4 证明 `common` 解析不到 druid 时补直接依赖声明）

**Interfaces:**
- Consumes: 官方 `com.alibaba.druid:1.2.28` 的 `SQLASTOutputVisitor`（已证实含 `protected final StringBuilder appender`、`protected DbType dbType`、`protected boolean parameterized`、`protected boolean shardingSupport`）、`ExportParameterVisitorUtils.exportParameter(List<Object>, SQLExpr)`、`SQLExprParser(String)`、`SQLHexExpr(String)`
- Produces: `io.mycat.sql.visitor.MycatOutputVisitor extends com.alibaba.druid.sql.visitor.SQLASTOutputVisitor implements MySqlASTVisitor`，公开构造器签名保持 `(StringBuilder)` 与 `(StringBuilder, boolean)` 不变；全仓 `com.alibaba.druid.*` 引用只剩官方类，`git ls-files "*com/alibaba/druid*"` 为空

- [ ] **Step 0: 先把带影子的 fat jar 副本存住（Task 5 的 RED 要拿它复现原始故障）**

```powershell
New-Item -ItemType Directory -Force target | Out-Null
Copy-Item "server/target/server-1.22-release-jar-with-dependencies.jar" "target/l1-shadow-baseline.jar"
```

Expected: `target/l1-shadow-baseline.jar` 存在（`target` 已 gitignore，不会误入版本库）。此 jar 是删除前构建的，内含影子，用于 Task 5 Step 3。

- [ ] **Step 1: 删除 6 个影子文件**

```bash
git rm plug/src/main/java/com/alibaba/druid/SQLASTOutputVisitor.java plug/src/main/java/com/alibaba/druid/SQLExprParser.java plug/src/main/java/com/alibaba/druid/SQLHexExpr.java plug/src/main/java/com/alibaba/druid/ExportParameterVisitorUtils.java plug/src/main/java/com/alibaba/druid/SQLIndexDefinition.java hbt/src/main/java/com/alibaba/druid/sql/ast/SQLIndexDefinition.java
```

- [ ] **Step 2: 运行编译，观察它失败（RED）**

Run: `New-Item -ItemType Directory -Force target | Out-Null; mvn -B install '-Dmaven.test.skip=true' -pl server -am 2>&1 | Tee-Object target/m2-1-compile.log; Select-String -Path target/m2-1-compile.log -Pattern "ERROR.*\.java"`
Expected: BUILD FAILURE，错误集中在 `hbt/src/main/java/io/mycat/sql/visitor/MycatOutputVisitor.java`（构造器 `super(appender)` 与若干 `@Override` 签名不匹配），**且** 出现 `cannot find symbol` 类错误则说明有业务代码引用了影子独有成员。**把完整错误清单抄进账本**（它是 Task 3/4 的工作量事实，也用于核对 spec 第 5 节止损阈值：>15 处需实质重写即停止回报）。

- [ ] **Step 3: 改构造器为官方签名**

`MycatOutputVisitor`（原 :49-51 与 :53-55 两构造器）中 `super(appender)` → 官方 `super(appender)`（形参已是 `StringBuilder`，官方存在 `(StringBuilder)`）；`super(appender, parameterized)` → 官方 `(StringBuilder, boolean)`。若官方另需方言参数，用 `super(appender, DbType.mysql, true)` 形态（官方含 `(StringBuilder, DbType, boolean)`）。

- [ ] **Step 4: 逐个修 Step 2 列出的 override 漂移**

只改编译器点名的失配项（返回类型/参数类型/已删除的 visit 方法），每处加 `// MYCAT CHANGE: Druid 1.2.28 <具体变更>`。同一步顺带确认 `common/src/main/java/io/mycat/PreparedStatement.java:104,108` 与 `hbt/src/main/java/io/mycat/MetadataManager.java:683` 是否编译通过——官方 `SQLHexExpr(String)` 已证实存在，预期无需改动；若 `common` 报 `package com.alibaba.druid.sql.ast.expr does not exist`，在 `common/pom.xml` 补一条无 version 的 `com.alibaba:druid` 直接依赖（版本由根 pom `dependencyManagement` 供给）。

- [ ] **Step 5: 移除 `MycatCore` 抢类加载的两行**

删 `server/src/main/java/io/mycat/MycatCore.java:75-76` 两条 `Class.forName("com.alibaba.druid.sql.visitor.SQLASTOutputVisitor"/"...parser.SQLExprParser", ...)`。该两行由 `4a3e5f6ad`（2021-07-26）与影子机制同期加入、且只点名这两个影子类；影子消失后其"抢覆盖优先权"的目的不复存在。同块其余条目（`:77-83`，含官方 `com.alibaba.druid.sql.SQLUtils`）为通用预热，**一律保留不动**。

- [ ] **Step 6: 运行编译，观察它通过（GREEN）**

Run: `mvn -B install '-Dmaven.test.skip=true' -pl server -am`
Expected: BUILD SUCCESS；并确认 `server/target/server-1.22-release-jar-with-dependencies.jar` 的时间戳为本次运行时刻（新鲜度实证，不凭日志推断）。

- [ ] **Step 7: 验证影子清零**

Run: `git ls-files "*com/alibaba/druid*"`
Expected: 无输出（0 行）。

- [ ] **Step 8: 提交**

```bash
git add -A plug hbt common server
git commit -m "refactor(M2-1)!: 删除 5 类 druid 影子类, MycatOutputVisitor 改继承官方, 移除 Class.forName 抢加载"
```

---

### Task 4: 黄金基线对账（逐条裁定行为差异）

**Files:**
- Modify: `hbt/src/test/resources/io/mycat/sql/druid-output-baseline.txt`（仅在有裁定时更新）
- Modify: `hbt/src/main/java/io/mycat/sql/visitor/MycatOutputVisitor.java`（仅当裁定"需要移植"时）
- Test: `hbt/src/test/java/io/mycat/sql/DruidOutputGoldenTest.java`

**Interfaces:**
- Consumes: Task 1 的基线格式与开关、Task 3 后的官方 visitor
- Produces: 全绿的 `DruidOutputGoldenTest`（17/17），以及账本中若干条 `Golden: <语料序号> — <保留官方/需要移植> — <理由>`

- [ ] **Step 1: 跑基线对账**

Run: `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest'`
Expected: PASS(17/17) 或输出若干 `golden diff #N`。两种都继续。

- [ ] **Step 2: 对每条 `golden diff #N` 做裁定**

判据：影子是 2017 旧代码，**默认倾向"保留官方"**（更正确且可随 druid 升级）。若差异属于 Mycat 的分片语义（例如分片表名/`?` 参数化输出、`<=>`、Hint 保真）而官方丢了，则判"需要移植"，转 Step 3。每条写一行 `Golden: <N> — <裁定> — <理由>` 入账本。

- [ ] **Step 3: "需要移植"的差异走 TDD**

先在 `DruidOutputGoldenTest` 同目录新增 `@Test` 单点复现该差异（断言期望的 Mycat 输出），Run 之 → Expected: FAIL；再在 `MycatOutputVisitor` 覆写对应 `visit(...)` 实现该行为（加 `// MYCAT CHANGE: <为何官方不满足>`）→ Run → Expected: PASS。

- [ ] **Step 4: 仅对判"保留官方"的差异更新基线，并全量复跑**

Run: `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest' '-Dgolden.update=true'` 然后 `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest'`
Expected: 两次均 SUCCESS；第二次为真断言（17/17 绿）。

- [ ] **Step 5: 提交**

```bash
git add hbt/src/test hbt/src/main/java/io/mycat/sql
git commit -m "test(M2-1): druid 影子删除后黄金基线对账与必要行为移植"
```

---

### Task 5: L1 端到端门（复现并关闭最初那个 `NoSuchMethodError`）

**Files:**
- Create: `ci/L1Probe.java`（单文件源码，`java` 直接运行，无需构建产物）
- Create: `ci/verify-l1.sh`
- Modify: `.github/workflows/pr-validation-main-test.yml`（把 verify-l1 接入既有矩阵步骤）

**Interfaces:**
- Consumes: `server/target/server-1.22-release-jar-with-dependencies.jar`（Task 3 Step 6 产物）、Docker 中已就绪的 MySQL（`m1-mysql-a` 3306 / `m1-mysql-b` 3307）、Mycat 前端 8066
- Produces: `bash ci/verify-l1.sh` → 退出码 0 且末行 `L1 OK rows=1`；`java ci/L1Probe.java <host> <port> <user> <pwd>` → 打印查询返回行数

- [ ] **Step 1: 写 `ci/L1Probe.java`**

`main(String[] args)`：用 JDBC 连 Mycat 前端（`jdbc:mysql://host:port/mycat`? 实际 schema 取 Mycat 配置里的默认 schema），执行 `SELECT 1`，`while(rs.next()) n++`，打印 `L1 OK rows=<n>`；任何 `SQLException` 打印 `L1 FAIL <message>` 后 `System.exit(1)`。用 MySQL Connector/J 单 jar 作 `-cp`（本机可用 `D:\Nexus\repository\com\mysql\jdbc\mysql-connector-j\9.4.0\mysql-connector-j-9.4.0.jar`）。

- [ ] **Step 2: 写 `ci/verify-l1.sh`**

步骤：启动 `java -jar server/target/server-1.22-release-jar-with-dependencies.jar` 后台并把日志写入 `target/ci/l1-server.log`；用 `/dev/tcp/127.0.0.1/8066` 轮询最多 60 秒（沿用 `ci/run-example.sh` 的轮询写法，**不要用裸 sleep 10s**）；跑 `java -cp <connector> ci/L1Probe.java 127.0.0.1 8066 <user> <pwd>`；`grep -q NoSuchMethodError target/ci/l1-server.log` 若命中则报错退出；最后杀进程并输出 `L1 OK rows=1`。

- [ ] **Step 3: 先在删除影子前的基线上验证探针能复现故障（RED）**

Run: `bash ci/verify-l1.sh`，执行前先设环境变量 `$env:L1_JAR = "target/l1-shadow-baseline.jar"`（`verify-l1.sh` 需支持 `L1_JAR` 覆盖 jar 路径，未设则默认用 `server/target/server-1.22-release-jar-with-dependencies.jar`；Step 2 写脚本时就把这个变量接上）
Expected: 失败 —— 服务端日志出现 `NoSuchMethodError: 'void com.alibaba.druid.sql.visitor.SQLASTOutputVisitor.<init>(java.lang.Appendable)'`，脚本退出码非 0。这一步证明探针量的就是那个真缺陷；复现不到则**停止并回报**（说明探针没打到会话初始化路径，Task 5 的绿灯不算数）。用的 jar 是 Task 3 Step 0 存下的副本，不依赖 `git stash`（已提交的内容 stash 不掉）。

- [ ] **Step 4: 用 Task 3/4 后的新产物验证绿灯**

Run: `mvn -B install '-Dmaven.test.skip=true' -pl server -am && bash ci/verify-l1.sh`
Expected: 末行 `L1 OK rows=1`；`target/ci/l1-server.log` 中 `Mycat Vertx server ... started up.` 存在且**无** `NoSuchMethodError`。

- [ ] **Step 5: 接入 CI 矩阵**

在 `.github/workflows/pr-validation-main-test.yml` 的 "Run example + compat smoke against ${{ matrix.db }}" 步骤（第 64 行）之后加 `- name: Verify L1 query path` / `run: bash ci/verify-l1.sh`，env 沿用矩阵的 DB 变量。

- [ ] **Step 6: 提交**

```bash
git add ci/L1Probe.java ci/verify-l1.sh .github/workflows/pr-validation-main-test.yml
git commit -m "ci(M2-1): 新增 L1 端到端探针，锁死 NoSuchMethodError 不回归"
```

---

### Task 6: 全量回归与看板收口

**Files:**
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`

- [ ] **Step 1: 跑回归门 G5**

Run: `New-Item -ItemType Directory -Force target | Out-Null; mvn -B test -pl router,executor,hbt 2>&1 | Tee-Object target/m2-1-regression.log; Select-String -Path target/m2-1-regression.log -Pattern "Tests run:|BUILD"`
Expected: `BUILD SUCCESS`；`Tests run` 汇总的 failures/errors 相比 **Task 1 Step 0 已记入账本的基准数字**不新增（两组数字都在账本里，凭数字判"无回退"，不凭观感）。

- [ ] **Step 2: 跑影子清零门 G6**

Run: `git ls-files "*com/alibaba/druid*"`
Expected: 无输出。

- [ ] **Step 3: 看板登记（只认实证）**

把 `I-DEBT-1` 状态从 `REVIEW` 改为 `Evidenced-Done`，附四样可重跑证据：G1 构建命令、G2 `DruidOutputGoldenTest` 的 17/17、G4 `bash ci/verify-l1.sh` 的 `L1 OK rows=1`、G6 的空输出；并在"当前快照"把 L1 阻塞项从 D1 移除、留下 D13(example) 为 M2 下一目标。若任一门未过，状态写 `WIP` 并附未过项，不得写 Done。

- [ ] **Step 4: 提交**

```bash
git add docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -m "docs(M2-1): 看板登记 druid 影子清理完成证据（G1/G2/G4/G6）"
```

---

## 交付后的收尾

全部任务绿灯后，按 executing-plans 走整支分支的最终评审（`review-package` 从 `feat/m1-verification-net` 起点到 HEAD），再进 `finishing-a-development-branch`。M2-2（`example/pom.xml` 缺 `replica`/`plug` 依赖、约 40 个坏测试）在本计划之外，另出计划。
