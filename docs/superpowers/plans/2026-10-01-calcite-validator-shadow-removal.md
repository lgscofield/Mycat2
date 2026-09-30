# I-DEBT-3（Calcite `SqlValidatorImpl` 影子清除）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删掉 `hbt` 里 2018 年代的 `org.apache.calcite.sql.validate.SqlValidatorImpl` 拷贝，把唯一一处 Mycat 定制（`<=>` 强判 BOOLEAN）上提到算子自身，让 `calcite-core` fork 胜出，使 L1 端到端门真正拿到结果集。

**Architecture:** 定制上提到 `StrictEqualFunction` 的 `SqlReturnTypeInference`（官方扩展点，fork 零改动）→ 先用真实校验入口建"校验行为黄金基线"（副本仍在位时采集）→ 用 `副本 × 1.23.0 × 1.42.0` 三方 diff 把真定制与版本漂移分开 → 删副本 → fork 侧重采对账 → L1 端到端。

**Tech Stack:** JDK 21、Maven 多模块、Calcite fork `io.mycat:calcite-core:1.42.0-mycat`、JUnit 5、druid SQL AST → Calcite SqlNode（`MycatCalciteMySqlNodeVisitor`）。

**Spec:** `docs/superpowers/specs/2026-10-01-calcite-validator-shadow-removal-design.md`

## Global Constraints

- **每个 `mvn` 调用前必须设 JDK 21**：`$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"`。实测：不设则报 `无效的目标发行版: 21`（PATH 上的 java 不是 21）。
- **fork 保持零 `import io.mycat`**：`git grep -c "^import io.mycat" -- calcite-core/src/main/java` 必须仍为 `0`（G7，P1 的核心约束）。
- **本轮只删一类**：`hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java`。其余 11 个同 FQCN 副本、A 组改包名类、`example` 坏测试、`hbt` 既有 23 个 `ColumnMappingTest` error 全部不在本轮（spec §5）。
- **新测试必须 JUnit 5**（`org.junit.jupiter.api`）。实测：仓库无 `junit-vintage-engine`，JUnit 4 测试静默 `Tests run: 0`（`DruidOutputGoldenTest:29-30` 已记此坑）。
- **状态只允许 `Evidenced-Done`**：每个门的判定必须当场跑命令并贴原始输出摘要；禁止凭 commit message 或本计划文字标 Done。
- **账本**（SDD workspace）：`.superpowers/sdd/2026-10-01-calcite-validator-shadow-removal/progress.md`，每步实测（含 RED/GREEN 原文摘要）写进去。brief/report/review-package 也落同一目录。该目录被 `.superpowers/sdd/.gitignore`（内容 `*`）整体忽略，**不会进提交**，这是有意的。
- **提交时排除** `server/src/main/resources/version.txt`（每次构建改 `BuildTime` 弄脏）与 `.qoder/skills/*`。
- **bash 环境事实**：本机的 `bash` 是 WSL（`uname -s` → Linux，cwd 映射为 `/mnt/d/Workspaces/IdeaProjects/Mycat2`），且 WSL 自带 JDK 21（`/usr/bin/java`）。`ci/verify-l1.sh` 是 LF，能直接跑；但 `.qoder/skills/subagent-driven-development/scripts/*` 是 CRLF，需先清成 LF 副本再跑（本项目已放 `target/sdd/`）。
- **止损闸门**：三方 diff 判为"真定制且 fork 无对应能力"的条数 **> 10** → 停止并回报用户，回退 spec §4.6 的 P2；不允许"边删边猜"。

## 环境实测事实（写死，省得执行者重踩）

| 事实 | 证据 |
|---|---|
| `hbt/src/main/java/org/apache/calcite/**` 共 17 个 `.java`，其中 **12** 个在 `calcite-core` 有同路径孪生 | `foreach ($f in (Get-ChildItem -Recurse hbt/src/main/java/org/apache/calcite -Filter *.java)) { $rel=$f.FullName.Substring((Resolve-Path hbt/src/main/java).Path.Length+1) -replace '\\','/'; "$((Test-Path ("calcite-core/src/main/java/"+$rel)))  $rel" }` → 12 行 `True`，含 `org/apache/calcite/sql/validate/SqlValidatorImpl.java` |
| `hbt` 测试运行期由**副本**胜出 | `Test-Path hbt/target/classes/org/apache/calcite/sql/validate/SqlValidatorImpl.class` → `True`（surefire 类路径首位是 `hbt/target/classes`） |
| Calcite **1.23.0 sources jar 已可拉取**（spec §4.3 的回退分支不用了） | `mvn -B -q dependency:get "-Dartifact=org.apache.calcite:calcite-core:1.23.0:jar:sources"` → `EXIT=0`；`D:\Nexus\repository\org\apache\calcite\calcite-core\1.23.0\calcite-core-1.23.0-sources.jar` 存在 |
| 校验入口无需真 Mycat 进程 | `DrdsRunnerHelper.preParse(String,String)` 只走 druid + `MycatPreparedStatementUtil`（该类零 `MycatConfig`/`MetaClusterCurrent` 引用）；`MycatCalciteMySqlNodeVisitor` 同样零全局配置依赖 |
| `SqlOperatorBinding` 在 `org.apache.calcite.sql` 包，**构造器是 `protected SqlOperatorBinding(RelDataTypeFactory, SqlOperator)`**（探针可用的关键） | `calcite-core/src/main/java/org/apache/calcite/sql/SqlOperatorBinding.java:55-60`；无 `public static create(...)` 工厂 |
| `Table.getRowType` 形参是 `RelDataTypeFactory` | `calcite-core/src/main/java/org/apache/calcite/schema/Table.java:60` |
| `internalexample` 模块当前编译不过（`程序包 io.mycat.calcite 不存在`），故基线测试**不能**落在那里 | `mvn -B test -pl internalexample "-Dtest=UnionAllTest"` 实测失败；其 `pom.xml` 未声明 `hbt` |
| L1 探针命令与判据 | `bash ci/verify-l1.sh`：rc=0 且输出含 `^L1 OK rows=`；日志含 `NoSuchMethodError|NoClassDefFoundError` 即 FAIL（`ci/verify-l1.sh:103-113`） |
| `Verify L1 query path` 现在是软门 | `.github/workflows/pr-validation-main-test.yml:71-73`：`continue-on-error: true` |

## Review Focus

spec 有要求、但没有哪个任务的测试天然覆盖、且最可能坑到使用者的五点。每条后面钉到负责它的任务：

1. **删掉 validator 副本后，剩余 11 个副本可能与 fork 的 1.42 校验结果发生新的跨 jar 失配**，症状是运行期 `NoSuchMethodError`/`NoClassDefFoundError` 而不是编译错误 → Task 6（G4b 日志零命中 + `target/ci/l1-server.log` 原文入档）。
2. **`<=>` 结果列的可空性位**会改变下发给 MySQL 客户端的字段元数据（`NULL <=> NULL` 应得 `1`，不是 `NULL`）；只测"类型是 BOOLEAN"量不到 → Task 1（断言 `getFullTypeString()` 全等 `BOOLEAN NOT NULL`，不是只断 `SqlTypeName`）。
3. **`DrdsRunnerHelper` 匿名子类那 7 个 `@Override`**（`inferUnknownTypes`/`deriveType`/`getValidatedNodeType`/`validateLiteral`/`addToSelectList`/`validateWhereOrOn`/`handleUnresolvedFunction`）在 fork 上可能"仍在位但不再被调用"——`@Override` 只保证签名存在，不保证被走到 → Task 5（`?` 占位语料与 `GROUP BY` 语料在 fork 侧重采后必须逐条一致，不一致即裁定）。
4. **故意报错样本的错误文案从 1.23 年代变成 1.42 年代**，用户在前端看到的报错会变；"消息文本漂移"必须显式裁定而不是当成失败噪声 → Task 5（`!!ERR!!` 行的差异单列一段裁定）。
5. **DML（`INSERT`/`UPDATE`/`DELETE`）不走 `convertSelect`**，本轮的 CCE 与它们无关，基线对 DML 的回归敏感度低于对 SELECT 的 → Task 2（4 条 DML 语料入档）+ Task 6（G5 回归 `router,executor,hbt` 不新增失败）。

---

## Task 1: `<=>` 算子自带 BOOLEAN NOT NULL 推导（定制先有家）

**Files:**
- Create: `hbt/src/test/java/io/mycat/calcite/StrictEqualOperatorTypeTest.java`
- Modify（仅当探针为 RED）: `hbt/src/main/java/io/mycat/calcite/sqlfunction/cmpfunction/StrictEqualFunction.java:33-36`

**Interfaces:**
- Consumes: `MycatSqlDefinedFunction(String, SqlReturnTypeInference, SqlOperandTypeInference, SqlOperandTypeChecker, ScalarFunction, SqlFunctionCategory)`（`calcite-core/src/main/java/org/apache/calcite/mycat/MycatSqlDefinedFunction.java:37-51`）；`SqlOperatorBinding`（`org.apache.calcite.sql`，protected ctor `(RelDataTypeFactory, SqlOperator)`）
- Produces: `StrictEqualFunction.INSTANCE` 的返回类型推导在**不经过任何 validator 特判**的前提下给出 `BOOLEAN NOT NULL`。Task 5 删除副本后依赖此事实。

- [ ] **Step 1: 写 RED 探针**（JUnit 5，同文件内放一个 package-private 测试用 binding）

```java
// hbt/src/test/java/io/mycat/calcite/StrictEqualOperatorTypeTest.java
// 断言的是"删掉 hbt validator 副本后必须保住的现值"：副本 SqlValidatorImpl:5859 给的是
// createSqlType(BOOLEAN)，即 BOOLEAN NOT NULL。这里绕开 validator，只问算子自己的 inference。
RelDataType intType = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
SqlReturnTypeInference inference = StrictEqualFunction.INSTANCE.getReturnTypeInference();
Assertions.assertNotNull(inference, "<=> 必须自带返回类型推导");
RelDataType derived = inference.inferReturnType(
        new TwoOperandBinding(intType));
Assertions.assertEquals("BOOLEAN NOT NULL", derived.getFullTypeString(),
        "算子层推导必须与 hbt 副本特判同值，否则删副本即丢语义");
```

`TwoOperandBinding` 是同一测试文件里的 package-private 静态类，`extends SqlOperatorBinding`（它在 `org.apache.calcite.sql` 包，构造器 `protected SqlOperatorBinding(RelDataTypeFactory, SqlOperator)`，见"环境实测事实"表）：

```java
static class TwoOperandBinding extends SqlOperatorBinding {
    TwoOperandBinding(RelDataType t) {
        super(MycatCalciteSupport.TypeFactory, StrictEqualFunction.INSTANCE);
    }
    @Override public int getOperandCount() { return 2; }
    @Override public RelDataType getOperandType(int ordinal) {
        return MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
    }
    @Override public CalciteException newError(Resources.ExInst<SqlValidatorException> e) {
        return new CalciteException("probe-binding", null);   // 推导路径不会走到这里
    }
}
```

- [ ] **Step 2: 跑，看它失败**

Run: `mvn -B -o test -pl hbt "-Dtest=StrictEqualOperatorTypeTest"`（先设 `JAVA_HOME`）
Expected: FAIL。断言消息里会给出算子当前实际推导值——**把这个值原样记进账本**，它是"副本特判到底补了什么"的唯一实证答案（可能是 `BOOLEAN` 可空、`ANY`，也可能已经是 `BOOLEAN NOT NULL`）。

- [ ] **Step 3: 按 Step 2 的实际值裁定并动手**

- 若实际值 ≠ `BOOLEAN NOT NULL`：把 `StrictEqualFunction` 的构造器从 3 参重载换成 6 参重载，inference 用固定 `BOOLEAN NOT NULL`，其余参数照抄 `MycatSqlDefinedFunction:60-66` 的取值方式（`GetOperandTypes` 是它的 public 静态嵌套类，可直接 new）：

```java
public StrictEqualFunction() {
    super("<=>",
            opBinding -> MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.BOOLEAN),
            new GetOperandTypes(scalarFunction).invoke().getOperandTypeInference(),
            new GetOperandTypes(scalarFunction).invoke().getOperandMetadata(),
            scalarFunction,
            SqlFunctionCategory.SYSTEM);
}
```

  `createSqlType(BOOLEAN)` 默认不可空，与副本 `:5859` 的写法逐字同型，故不需要 `createTypeWithNullability`。
- 若实际值已 = `BOOLEAN NOT NULL`：不改产品代码，账本记"算子自带，副本特判是冗余"，Step 4 直接绿。两种分支都必须留可重跑命令。

- [ ] **Step 4: 再跑，确认通过**

Run: `mvn -B -o test -pl hbt "-Dtest=StrictEqualOperatorTypeTest"`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add hbt/src/test/java/io/mycat/calcite/StrictEqualOperatorTypeTest.java hbt/src/main/java/io/mycat/calcite/sqlfunction/cmpfunction/StrictEqualFunction.java
git commit -m "test(hbt): 钉住 <=> 算子层 BOOLEAN NOT NULL 推导（I-DEBT-3 Task 1）"
```

---

## Task 2: 校验行为黄金基线（趁副本仍胜出时采集）

**Files:**
- Create: `hbt/src/test/java/io/mycat/calcite/CalciteValidatorGoldenTest.java`
- Create: `hbt/src/test/resources/io/mycat/calcite/calcite-validator-corpus.txt`（48 条，见 Step 1 分布）
- Create: `hbt/src/test/resources/io/mycat/calcite/calcite-validator-baseline.txt`（Step 4 采集生成）

**Interfaces:**
- Consumes: `DrdsRunnerHelper.preParse(String sqlStatement, String defaultSchemaName)` → `DrdsSqlWithParams extends DrdsSql`；`DrdsRunnerHelper.newCalciteCatalogReader(SchemaPlus)` → `CalciteCatalogReader`；`DrdsRunnerHelper.getSqlValidator(DrdsSql, CalciteCatalogReader)` → `SqlValidator`；`MycatCalciteMySqlNodeVisitor#getSqlNode()`
- Produces: 基线文件 `N\t<render>` 格式（与 `druid-output-baseline.txt` 同构），锚点 `-Dgolden.validator=copy|fork`（默认 `copy`）。Task 5 用它做删副本前后对账。

- [ ] **Step 1: 写语料文件**（一行一条，顺序即索引，禁止空行——`readLines` 会跳空行导致索引错位，M2-1 已踩过）

fixture 表（测试内注册，副本与 fork 两次采集共用同一 fixture，可空性天然对齐）：

```
t1(id INT NOT NULL, name VARCHAR(64), age INT, amount DECIMAL(10,2), c_date DATE, c_ts TIMESTAMP, flag BOOLEAN)
t2(id INT NOT NULL, t1_id INT, note VARCHAR(64), amount DECIMAL(10,2))
employee(id INT NOT NULL, name VARCHAR(64), deptno INT, salary DECIMAL(10,2))
```

48 条按类分布，表名/列名一律小写：SELECT 基础 8（`*`、别名、限定列、常量表达式、`LIMIT`/`OFFSET`）· 聚合+`GROUP BY` 6（含 `GROUP_CONCAT(name SEPARATOR '|')`、`AVG`、`COUNT(DISTINCT)`）· `<=>` 5（含 `NULL <=> NULL`、`age <=> ?`）· 子查询/`IN`/`EXISTS` 6 · `JOIN` 5（INNER/LEFT/三表）· `UNION` 4（含 `UNION ALL` 列数不符的报错样本）· 参数占位 `?` 4（数值/字符串/日期各一，另一条用于 `IN (?)`）· DML 4（`INSERT`/`INSERT ... SELECT`/`UPDATE`/`DELETE`）· Mycat 函数与伪列 3（`MYCAT_VERSION()`、`MYCAT_LAST_INSERT_ID()`、`DATABASE()`）· 故意报错 3（未知列、未知表、聚合列不在 `GROUP BY`）。

- [ ] **Step 2: 写测试骨架**（采集/对账双模式 + 来源断言）

复用 `DruidOutputGoldenTest` 的四件套，逐条对应：`readLines`、`encode`/`decode`（`getFullTypeString()` 不含换行，但 `!!ERR!!` 的 message 可能含，必须转义）、`ERROR_PREFIX = "!!ERR!! "`、差异汇总（一次列出全部差异，不要首条即 fail）。渲染规则钉死：

```java
private static String render(String sql) {
    try {
        DrdsSqlWithParams drdsSql = DrdsRunnerHelper.preParse(sql, "mycat");
        SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, CATALOG_READER);
        SQLStatement stmt = drdsSql.getParameterizedStatement();
        MycatCalciteMySqlNodeVisitor v = new MycatCalciteMySqlNodeVisitor();
        stmt.accept(v);
        SqlNode validated = validator.validate(v.getSqlNode());
        return "cols=" + renderFields(validator.getValidatedNodeType(validated));
    } catch (Throwable t) {
        return ERROR_PREFIX + t.getClass().getName() + ": " + t.getMessage();
    }
}
// renderFields: 逐列 "name=<getFullTypeString()>"，逗号分隔，保持 Calcite 给的列序
```

`CATALOG_READER` 由 `DrdsRunnerHelper.newCalciteCatalogReader(rootSchemaPlus)` 得到；`rootSchemaPlus = CalciteSchema.createRootSchema(false).plus()`，三张表用测试内的 `FixedTable extends AbstractTable`（`@Override public RelDataType getRowType(RelDataTypeFactory tf)` 返 fixture 行类型，行类型用 `MycatCalciteSupport.TypeFactory.builder()` 造，参照 `ColumnMappingTest:50-56`）。

来源锚点（防"影子已删导致重采集误报失败"）：

```java
String from = System.getProperty("golden.validator", "copy");
String realFrom = SqlValidatorImpl.class.getProtectionDomain().getCodeSource().getLocation().toString();
System.out.println("validator from " + realFrom);
// copy → 断言 realFrom 含 "hbt"；fork → 断言含 "calcite-core"。不匹配就直接 fail，不允许静默采错。
```

- [ ] **Step 3: 证明这网能失败**（M2-1 用"人为篡改基线 → rc=1"证明断言非恒真，本轮照做）

先采集（Step 4），再手改 `calcite-validator-baseline.txt` 里任意一行的一个类型字面量，跑对账，Expected: FAIL 且打印该条 `golden diff #N`；改回后 Expected: PASS。原始输出摘要入账本。

- [ ] **Step 4: 采集 copy 侧基线**

Run: `mvn -B -o test -pl hbt "-Dtest=CalciteValidatorGoldenTest" "-Dgolden.update=true" "-Dgolden.validator=copy"`
Expected: `Tests run: 1, Failures: 0`；日志出现 `validator from ...hbt/target/classes/`；`calcite-validator-baseline.txt` 行数 == 语料行数（48）。

- [ ] **Step 5: 跑一遍纯对账，确认全绿**

Run: `mvn -B -o test -pl hbt "-Dtest=CalciteValidatorGoldenTest"`
Expected: PASS，输出干净。

- [ ] **Step 6: 记录 `!!ERR!!` 与 `<=>` 两组的现值**（Task 5 的裁定基线）

账本里逐条列出：所有 `!!ERR!!` 行的序号与消息、所有 `<=>` 语料的 `cols=` 值。这是"副本胜出时 Mycat 的真实校验行为"，不是噪声。

- [ ] **Step 7: 提交**

```bash
git add hbt/src/test/java/io/mycat/calcite/CalciteValidatorGoldenTest.java hbt/src/test/resources/io/mycat/calcite/
git commit -m "test(hbt): 建校验行为黄金基线（copy 侧采集，48 条分片语料，I-DEBT-3 Task 2）"
```

---

## Task 3: 三方血统 diff（副本 × 1.23.0 × 1.42.0）与"真定制"计数

**Files:**
- Create: 无产品代码；产物是 `target/shadowdiff/`（gitignore 内）与账本段落
- Modify: `.superpowers/sdd/2026-10-01-calcite-validator-shadow/progress.md`（账本，不入库）

**Interfaces:**
- Consumes: 副本 `hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java`；`D:\Nexus\repository\org\apache\calcite\calcite-core\1.23.0\calcite-core-1.23.0-sources.jar`；fork `calcite-core/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java`
- Produces: 三类计数（真定制 / 版本漂移 / 定制叠在旧版上）与逐条清单。**> 10 条真定制 → 触发 Global Constraints 的止损闸门，停下回报用户。**

- [ ] **Step 1: 解包 1.23.0 源码**

Run: `Expand-Archive "D:\Nexus\repository\org\apache\calcite\calcite-core\1.23.0\calcite-core-1.23.0-sources.jar" -DestinationPath target/shadowdiff/1.23 -Force`
Expected: `target/shadowdiff/1.23/org/apache/calcite/sql/validate/SqlValidatorImpl.java` 存在（`Test-Path` → True）。

- [ ] **Step 2: 生成两份 diff 并计数**

```
git diff --no-index -U0 target/shadowdiff/1.23/org/apache/calcite/sql/validate/SqlValidatorImpl.java hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java | Out-File -Encoding utf8 target/shadowdiff/copy-vs-1.23.diff
git diff --no-index -U0 calcite-core/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java | Out-File -Encoding utf8 target/shadowdiff/copy-vs-1.42.diff
```

（必须走管道 + `Out-File -Encoding utf8`：PowerShell 的 `>` 默认写 UTF-16LE，后续 `Select-String` 计数会失真。）

Expected: 两份 diff 都非空。记录 `Select-String -Pattern "^+" | Measure-Object` 的行数。

- [ ] **Step 3: 按 spec §4.3 分类规则逐条定性**（禁止在千行漂移里肉眼找补丁：只判 `copy-vs-1.23.diff` 的 `+` 块，1.23 与 1.42 都有的行属于版本漂移，不进候选）

对每个候选块给出三类之一并记 file:line：
- A 类：只在副本存在、1.23/1.42 都没有 → **真定制**，必须逐条裁定去向；
- B 类：与 1.23 一致而与 1.42 不同 → 版本漂移，删副本即随 fork 解决；
- C 类：1.23/1.42 都有但副本改过 → 定制叠在旧版上（重点，最易漏）。

Expected 锚点（已实测的下界，不得少于此）：A/C 类至少含 `<=>` 特判（`:5858-5859`）与 `callToOperandTypesMap` 回填（`:265-266`，已裁定"无需移植，fork `:281` 自带"）；`getMeasureScope`（`:1177-1179`）判为 B 类。

- [ ] **Step 4: 止损判定 + 账本**

账本写：A/C 总条数 N、逐条清单（file:line + 一句定性 + 计划去向）。
若 `N > 10`：**停止后续任务**，回报用户，按 spec §4.6 回退 P2。

- [ ] **Step 5: 提交（文档）**

```bash
git add docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -m "docs: I-DEBT-3 三方血统 diff 计数与真定制清单入看板"
```

---

## Task 4: 删副本 → 编译 + 影子计数下降 + fork 纯净性 + 孤儿钩子扫描

**Files:**
- Delete: `hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java`
- Create: `hbt/src/test/java/io/mycat/calcite/ValidatorOrphanScanTest.java`（孤儿钩子扫描器，JUnit 5）

**Interfaces:**
- Consumes: Task 2 的基线文件（此任务不动它）
- Produces: 同 FQCN 对数 `12 → 11`；G1 编译绿；G7 `import io.mycat` 计数 0；孤儿钩子扫描器可自证有效。Task 5 在此之上的编译产物里跑 fork 侧重采。

- [ ] **Step 1: 写孤儿钩子扫描器**

判据：枚举 validator 的子类（`git grep -l "extends SqlValidatorImpl" -- "*/src/main/java/*"` → `hbt/.../io/mycat/calcite/MycatSqlValidator.java`，外加 `DrdsRunnerHelper:216` 的匿名子类）里所有 `protected`/`public` 且**无** `@Override` 的方法，逐个用 javap 查 fork 是否有同签名可覆写目标（`javap -p -classpath <calcite-core jar> org.apache.calcite.sql.validate.SqlValidatorImpl`，用法参照 M2-1 的 druid 扫描）；无 `@Override` 的候选列成清单，测试断言清单为空并把候选逐个打印出来。

- [ ] **Step 2: 让扫描器自证有效**（M2-1 教训：恒真的扫描器等于没有扫描器）

在 `hbt/src/main/java/io/mycat/calcite/MycatSqlValidator.java` 临时加一个"fork 里不存在同签名目标"的方法（如 `protected void vliidateLiteralBogus(SqlLiteral l) {}`，故意错拼、无 `@Override`）。
Run: `mvn -B -o test -pl hbt "-Dtest=ValidatorOrphanScanTest"`
Expected: FAIL，报告 1 个候选孤儿（名字就是 `vliidateLiteralBogus`）。
然后撤销临时改动，同命令再跑一次。
Expected: PASS，0 候选。两次原始输出都入账本——这才叫证明过网有效。

**本步出口条件**（Step 7 用 `git add -A hbt/src`，残留会被一并提交）：撤销后必须跑
`git diff --stat hbt/src/main/java/io/mycat/calcite/MycatSqlValidator.java` 与
`git grep -n "vliidateLiteralBogus" -- hbt/src`，两者都必须空。不空就不准往下走。

- [ ] **Step 3: 删副本**

```bash
git rm hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java
```

- [ ] **Step 4: G1 真实编译（这是 spec §1.4 静态同形结论的收尾，不许跳过）**

Run: `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"; mvn -B install '-Dmaven.test.skip=true' -pl server -am`
Expected: `BUILD SUCCESS`，`server/target/server-1.22-release-jar-with-dependencies.jar` 时间戳为本次。
若编译断裂：断裂点即 fork API 缺口，**记进账本并回报用户**（spec §6 风险表第 3 行的处置路径），不要靠加回副本蒙过去。

- [ ] **Step 5: G6v + G7**

```
foreach ($f in (Get-ChildItem -Recurse hbt/src/main/java/org/apache/calcite -Filter *.java)) { ... }   # 见"环境实测事实"表首行
git grep -c "^import io.mycat" -- calcite-core/src/main/java
```
Expected: 孪生对数 `11`（`sql/validate/SqlValidatorImpl.java` 那行变 `False`）；后者 `0`。

- [ ] **Step 6: 跑孤儿扫描器（Step 1 的同一个类）**

Run: `mvn -B -o test -pl hbt "-Dtest=ValidatorOrphanScanTest"`
Expected: PASS（0 真孤儿）。任何候选都要逐条裁定并入账本，禁止"看起来没被用到"就放过。

- [ ] **Step 7: 提交**

```bash
git add -A hbt/src
git commit -m "refactor(hbt)!: 删除 SqlValidatorImpl 影子副本，让 calcite-core fork 胜出（I-DEBT-3 Task 4）"
```

---

## Task 5: fork 侧重采与逐条对账（G2v 判定）

**Files:**
- Create: `target/shadowdiff/fork-recollect.txt`（临时对账物，gitignore 内，不入库）
- Modify（仅当真需要移植）: `hbt/src/main/java/io/mycat/calcite/**` 里的算子或子类，**不许**新建副本

**Interfaces:**
- Consumes: Task 2 基线（copy 侧）、Task 4 编译产物
- Produces: 差异清单与逐条裁定；差异条数即 spec §4.6 的止损计数（与 Task 3 的计数合并判断）。

- [ ] **Step 1: 切 fork 侧重采**

Run: `mvn -B -o test -pl hbt "-Dtest=CalciteValidatorGoldenTest" "-Dgolden.update=true" "-Dgolden.validator=fork"`
Expected: 日志 `validator from ...calcite-core...`（断言若非 fork 即 fail）；基线重写为 48 行。

- [ ] **Step 2: 与 copy 侧基线 diff**

先 `git diff hbt/src/test/resources/io/mycat/calcite/calcite-validator-baseline.txt` 看全部差异；把差异逐条抄进 `target/shadowdiff/fork-recollect.txt`，每条标注：类型漂移 / 列名大小写 / 错误消息文案 / 新增或消失的 `!!ERR!!`。

- [ ] **Step 3: 逐条裁定**（Review Focus 第 3、4 条在这里落地）

每条差异写"接受 / 移植 / 待议"之一：
- **移植**：改 hbt 侧（算子 inference、`DrdsRunnerHelper` 匿名子类覆写）把副本行为补回来，禁止新建副本；
- **接受**：须在账本说明为什么 fork 的行为才是对的（例：1.42 修了 1.23 的 bug）；
- **待议**：数量 >10 时按止损闸门停下回报。

- [ ] **Step 4: 裁定后回落到对账模式**

把最终认可的基线（fork 侧）留在 `calcite-validator-baseline.txt` 并**把默认锚点从 `copy` 改成 `fork`**（`System.getProperty("golden.validator", "fork")`），这样 CI 重采集不会悄悄退回副本。
Run: `mvn -B -o test -pl hbt "-Dtest=CalciteValidatorGoldenTest"`
Expected: PASS（48 行逐条一致）。

- [ ] **Step 5: 提交**

```bash
git add hbt/src
git commit -m "test(hbt): fork 侧校验基线对账与定制移植裁定（I-DEBT-3 Task 5）"
```

---

## Task 6: L1 端到端（G4b，本轮存在理由）

**Files:**
- Modify（仅在暴露新缺口时）: `ci/verify-l1.sh`、`hbt/src/main/java/**`

**Interfaces:**
- Consumes: Task 4/5 产物 jar
- Produces: `bash ci/verify-l1.sh` rc=0 且输出 `L1 OK rows=1`；日志零 `NoSuchMethodError`/`NoClassDefFoundError`/`ClassCastException`。

- [ ] **Step 1: 跑门**

Run: `bash ci/verify-l1.sh`
Expected: rc=0，含 `L1 OK rows=`。
再跑 `grep -cE "NoSuchMethodError|NoClassDefFoundError|ClassCastException" target/ci/l1-server.log`
Expected: `0`。

G3（启动门）由本脚本内含，不单独再跑：`ci/verify-l1.sh:88-92` 必须等到 8066 就绪才继续，`:115` 断言日志含 `started up` 并打印`服务端启动日志已确认 started up` —— 该行出现即 G3 通过。

- [ ] **Step 2: 若仍是 `ClassCastException`，按 spec §4.5 走棘轮**

从栈帧定位**下一个**阻断的 Calcite 副本，记 file:line 与栈帧，回报用户后再决定是否在本 spec 内追加该类（每追加一类，G6v 期望值再减 1，看板 `I-DB-1` 子清单逐项标记）。禁止顺手清与 L1 无关的副本。

- [ ] **Step 3: 账本记原始日志尾部**（`tail -n 20 target/ci/l1-server.log`）并确认 `started up` 仍在。

- [ ] **Step 4: 提交（若本轮有代码/脚本改动）**

```bash
git add ci hbt
git commit -m "fix(hbt): 解锁 L1 结果集路径（I-DEBT-3 Task 6, G4b）"
```

---

## Task 7: 全量回归 + L1 转硬门 + 看板收口

**Files:**
- Modify: `.github/workflows/pr-validation-main-test.yml:71-73`
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`

**Interfaces:**
- Consumes: Task 1–6 全部产物
- Produces: G5 不新增失败；G8 硬门；`I-DEBT-3` → `Evidenced-Done`（附可重跑命令）；`I-DB-1` B 组勾掉已完成类并更新剩余对数。

- [ ] **Step 1: G5 回归**

Run: `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"; mvn -B test -pl router,executor,hbt -Dmaven.test.failure.ignore=true`
Expected: **退出码不是判据**（既有 23 个 error 会让 rc≠0），加 `-Dmaven.test.failure.ignore=true` 是为了让三个模块的汇总全部打印。判据是三项数字不劣于基准（router 15/0/0、hbt 26/0/23）。既有 23 个 error 是 `RelBuilder.transientScan` NPE，另案；**若本轮之后数字变了，必须逐条点名**（TDD 要求：跑过的红不能靠省略写成绿）。

- [ ] **Step 2: 摘掉软门**

把 `.github/workflows/pr-validation-main-test.yml` 里 `Verify L1 query path` 的 `continue-on-error: true` 整行删除（保留 `- name` 与 `run`）。
Run: `Select-String -Path .github/workflows/pr-validation-main-test.yml -Pattern "continue-on-error"`
Expected: 只剩 `L16: continue-on-error: ${{ matrix.experimental }}` 那一处。

- [ ] **Step 3: 逐门重跑并落原始输出**（G1/G2v/G4b/G5/G6v/G7；每门一条命令 + 一行结果，禁止引用本计划文字当证据）

- [ ] **Step 4: 看板 + 账本收口**

`I-DEBT-3` 状态 `Evidenced-Done`，附 Step 3 的命令清单；`I-DB-1` B 组 `SqlValidatorImpl` 勾掉、同 FQCN 对数改 `11`；变更日志加一行"删 validator 副本，L1 转硬门"。

- [ ] **Step 5: 提交**

```bash
git add .github/workflows/pr-validation-main-test.yml docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -m "ci: L1 查询路径转为硬门；docs: I-DEBT-3 收口（Evidenced-Done）"
```

- [ ] **Step 6: 回报用户"分支可收口"**（用户已裁定：再撑一轮、L1 全绿后合并。此处只报告证据，合并动作等用户点头，不自行 merge/push）
