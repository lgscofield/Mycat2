# Druid 影子类清理（不 fork）设计

> **Spec 状态**：待评审。本文件只定义设计与验收门，不含实施步骤（实施计划由 writing-plans 另行产出）。

**Goal:** 删除 Mycat 仓库内 5 类（6 个文件）与 druid 1.2.28 同全限定名的"影子类"，让 druid 回归为一个**可正常升级的普通第三方依赖**，并解锁 L1 连接层（客户端发 SQL 不再崩）。

**Architecture:** 双引擎分工不变（Druid 做 MySQL 方言前端，Calcite 做优化中端）。真影子一律删除、改由官方 1.2.28 提供；Mycat 自己新增的方言适配类保留并搬出 druid 包，其中 `MycatOutputVisitor` 改为**直接继承官方** `SQLASTOutputVisitor`。不新建 druid fork 模块。

**Tech Stack:** Java 21、Maven 多模块、`com.alibaba:druid:1.2.28`（根 pom `druid.version`）。

**父 Spec:** `2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md`（路线图 T-DEBT / D1 债务）
**实施看板:** `2026-09-30-mysql-mariadb-compat-PROGRESS.md`
**对齐的既有决策:** `2026-09-29-calcite-fork-strategy-and-cleanup-design.md` 第 114-116 行原则、第 132-146 行"删覆盖用原版"范式

---

## 1. 背景与实证

### 1.1 触发问题（运行期实锤）

Mycat 启动已修通（`ConverterRule` 一行，提交 `f2118b9df`），但客户端连上 8066 并发送会话初始化 SQL 时服务端抛：

```
NoSuchMethodError: 'void com.alibaba.druid.sql.visitor.SQLASTOutputVisitor.<init>(java.lang.Appendable)'
  at com.alibaba.druid.sql.dialect.mysql.visitor.MycatOutputVisitor.<init>(MycatOutputVisitor.java:50)
```

编译期绑定的是 `plug` 里的影子 `SQLASTOutputVisitor`（其构造器为 `(Appendable)`），运行期 fat jar 里加载的是官方 1.2.28（构造器已改为 `(StringBuilder)`）——**编译用一个、运行用另一个**。

### 1.2 影子的年代与性质（javap 取证）

| 事实 | 证据 |
|---|---|
| 官方 druid **1.0.18** 的构造器正是 `(Appendable)` | `javap` on `druid-1.0.18.jar` |
| 官方 druid **1.2.28** 构造器全部为 `(StringBuilder, ...)` | `javap` on `druid-1.2.28.jar` |
| 影子 `SQLASTOutputVisitor` 保留 `(Appendable)` | `plug/.../SQLASTOutputVisitor.java:108/112/117` |
| → 影子是 **1.0.18 年代（2017）的整份拷贝** | 文件头 `Copyright 1999-2017` |
| 影子里 25 处标记**全部**是兼容补丁，措辞一律 `MYCAT CHANGE: Druid 1.2.28 removed/renamed X` | `SQLASTOutputVisitor`(14 处)、`SQLExprParser`(10 处) |
| 影子**完全不引用 Mycat 代码** | 12 个文件中 `^import io.mycat` 命中 **0 处** |
| 当年定制的核心特性已被上游收编 | 官方 1.2.28 已有 `protected boolean shardingSupport` |

**判定**：影子的差异 = **版本陈旧 + 兼容补丁**，不含任何 Mycat 业务定制。留着它们等于长期手工维护一份 2017 年的 druid。

**改继承的可行性已正面实证**（非假设）：`javap` 得官方 1.2.28 `SQLASTOutputVisitor` 含 `protected final StringBuilder appender`、`protected DbType dbType`、`protected boolean parameterized`、`protected boolean shardingSupport`，均与 `MycatOutputVisitor.java:44-47` 实例初始化块所用字段对应；`ExportParameterVisitorUtils.exportParameter(List<Object>, SQLExpr)` 与 `SQLExprParser(String)`、`SQLHexExpr(String)` 均在官方存在。

### 1.3 全量清单（12 文件，按类加载危害分类）

**A 组·真影子（官方 1.2.28 存在同名类 → 删除）**

| 文件 | 行数 | 官方同文件行数 | 行差 |
|---|---|---|---|
| `plug/src/main/java/com/alibaba/druid/SQLASTOutputVisitor.java`（pkg `...sql.visitor`） | 11270 | 12410 | 3092 |
| `plug/src/main/java/com/alibaba/druid/SQLExprParser.java`（pkg `...sql.parser`） | 6659 | 6502 | 3159 |
| `plug/src/main/java/com/alibaba/druid/SQLHexExpr.java`（pkg `...sql.ast.expr`） | 97 | 101 | 84 |
| `plug/src/main/java/com/alibaba/druid/ExportParameterVisitorUtils.java`（pkg `...sql.visitor`） | 198 | 214 | **36** |
| `plug/.../com/alibaba/druid/SQLIndexDefinition.java` + `hbt/src/main/java/com/alibaba/druid/sql/ast/SQLIndexDefinition.java`（**跨模块重复两份**） | 503 | 497 | **24** |

注：`ExportParameterVisitorUtils`、`SQLIndexDefinition` 与官方仅差 24–36 行，几乎是官方原文——删除成本接近零。

**B 组·Mycat 新增（官方无此类 → 保留并搬出 druid 包）**

| 类 | 位置 | 行数 | 官方是否存在 |
|---|---|---|---|
| `MycatOutputVisitor` | `hbt/src/main/java/com/alibaba/druid/sql/dialect/mysql/visitor/` | 5575（**195 个 `@Override`**） | 否（官方 jar 内 `*Mycat*` 条目 0） |
| `MycatSQLUtils` | `hbt/src/main/java/com/alibaba/druid/sql/` | 13 | 否 |
| `MycatSQLEvalVisitorUtils` | hbt 2008 + plug 2005（**跨模块重复**） | — | 否（官方对应类名为 `SQLEvalVisitorUtils`） |
| `functions.Timestamp` | hbt 39 + plug 39（**跨模块重复**） | 39 | 否（官方 `...visitor.functions` 包内无 `Timestamp.class`，也无 `FunctionManager`） |

上表行数均以 `(Get-Content).Count` 重测修正（先前使用的 `Measure-Object -Line` 计数不可靠）。

B 组不与官方撞名，**没有类加载危险**，只是寄生在 druid 包名下（违背后既定的"业务模块不得出现与上游同包名自定义类"约束）。

### 1.4 为什么**不** fork druid（关键裁定）

| 维度 | fork druid（曾考虑） | 删影子（本设计） |
|---|---|---|
| 需永久持有的上游源码 | 1644 个 java 文件 + 224 个资源文件 | 0 |
| 与 calcite-core 先例比较 | calcite-core 已 vendor 1651 文件（规模同级，可行但沉重） | — |
| 是否有必要动上游内部 | **无**：影子含 0 处 Mycat 定制，真正的定制是 B 组子类 | 无，靠继承即可 |
| druid 未来可升级性 | 每次升级需重新 vendoring + 重打补丁 | **普通依赖，`<druid.version>` 一改即升** |
| 既有原则契合度 | 违背 `2026-09-29` 设计第 114 行"**能用原来的代码最好，逐步消除不必要的覆盖**" | 直接执行该原则，与第 132-146 行 `SqlValidatorImpl`（6635 行覆盖换 3 行定制 → 删）同一范式 |

**裁定：不建 `druid-core` 模块。** calcite 之所以需要 fork，是因为有 5 处"无 SPI 替代"的上游内部改动；druid 这边经取证**不存在此类改动**，套用同一判据即得相反结论。

---

## 2. 设计决策

### D1 删除 A 组 5 个真影子，改由官方 1.2.28 提供
共 6 个文件：`plug` 下 `SQLASTOutputVisitor`、`SQLExprParser`、`SQLHexExpr`、`ExportParameterVisitorUtils`、`SQLIndexDefinition`（5 个），加 `hbt/src/main/java/com/alibaba/druid/sql/ast/SQLIndexDefinition.java`（跨模块重复的副本）。删除后所有 `com.alibaba.druid.sql.visitor.SQLASTOutputVisitor` / `...parser.SQLExprParser` / `...ast.expr.SQLHexExpr` / `...visitor.ExportParameterVisitorUtils` / `...ast.SQLIndexDefinition` 的引用自动解析到官方类。

### D2 B 组搬出 druid 包，落到 `hbt` 模块的 Mycat 命名空间
默认目标包（`hbt` 已被 `server` 依赖，两个消费方都在 server/hbt 内）：

| 类 | 新全限定名 |
|---|---|
| `MycatOutputVisitor` | `io.mycat.sql.visitor.MycatOutputVisitor` |
| `MycatSQLUtils` | `io.mycat.sql.MycatSQLUtils` |
| `MycatSQLEvalVisitorUtils` | `io.mycat.sql.visitor.MycatSQLEvalVisitorUtils` |

**`functions.Timestamp` 必须保留——它是活代码，不是死码**：取证推翻了"只有自引用"的初判——`hbt/.../sql/visitor/MycatSQLEvalVisitorUtils.java:138` 有 `functions.put("now", Timestamp.instance)`，即 **Mycat 把官方 `now` 函数换成了自己的 `Timestamp` 实现**（对 `SQLVariantRefExpr` 参数直接取回原始 String，否则抛 `UnsupportedOperationException`）。官方 1.2.28 根本不存在 `Timestamp` 函数类（无 `Timestamp.class`、无 `FunctionManager`）。因此该类必须随 `MycatSQLEvalVisitorUtils` 一同迁移并**保持注册关系不断**。

**例外条款**：若某个类必须访问 druid 的**包级私有**成员（搬迁后编译不过），则不强行搬名，改为留在 druid 包树下、放入独立的 `com.alibaba.druid.<上游子包>.mycat` 子包——**类名保持 `Mycat*` 前缀故永不撞名**。此做法与 calcite 先例一致（`org.apache.calcite.mycat` 包）。裁定依据：优先干净命名，其次保证可编译，绝不用撞名换取访问权限。

**跨模块重复去重**（`MycatSQLEvalVisitorUtils` 有 hbt 2008 / plug 2005 两份）：两份先 `diff`。**完全相同** → 保留 `hbt` 副本（消费方所在模块），删除 `plug` 副本；**有差异** → 逐条差异裁定并记 `Ruling:`，保留被真实调用路径覆盖的那份。A 组的 `SQLIndexDefinition` 两份无需裁定，直接一起删。禁止凭行数猜孰新。

### D3 `MycatOutputVisitor` 改为直接继承官方类
- `extends com.alibaba.druid.sql.visitor.SQLASTOutputVisitor`（官方）。
- 两个构造器由 `super(appender)`（`(Appendable)`）改为官方 `(StringBuilder)` / `(StringBuilder, boolean)` 形态，即 `MycatOutputVisitor.java:49-51` 与 `(StringBuilder, boolean)` 重载。
- 195 个 `@Override` 中因 1.0.18→1.2.28 漂移而失配者，按编译器报错逐个适配。先验估计为**小量**：`2026-09-29` 设计第 107 行已把 MycatOutputVisitor 的 Druid API 漂移记为 ~5 个错误。若实际显著超出该估计，触发第 5 节止损条款。
- MySQL 方言专用属性经官方 `DbType.MYSQL` / `SQLDialect` 构造器入参传递，不再依赖影子内部字段。

### D4 调用点改写（精确位置）
| 位置 | 现状 | 改法 |
|---|---|---|
| `server/src/main/java/io/mycat/MycatCore.java:75-76` | `Class.forName("...SQLASTOutputVisitor"/"...SQLExprParser")` 强行抢类加载顺序 | **删除这两行**（影子消失后 hack 失去存在理由，属遗留脚手架） |
| `server/src/main/java/io/mycat/commands/MycatdbCommand.java:34,548` | 影子类型 + `SQLUtils.createOutputVisitor(out, type)` | 变量类型改为官方 `SQLASTOutputVisitor`；若需 Mycat 方言输出则显式 `new MycatOutputVisitor(out)` |
| `router/src/main/java/io/mycat/router/function/AutoFunctionFactory.java:21,1181` | `new SQLExprParser(methodExpr)` | 导入不变，实例化官方 1.2.28 `SQLExprParser` |
| `common/src/main/java/io/mycat/PreparedStatement.java:104,108` | `new SQLHexExpr(s)` | 使用官方 `SQLHexExpr`；确认 `common` 能解析到 druid（当前经传递依赖，需显式核对并在必要时补直接依赖声明） |
| `hbt/src/main/java/io/mycat/MetadataManager.java:20,683` | `instanceof SQLHexExpr` | 无需改名，随导入解析到官方类 |
| `hbt/src/main/java/io/mycat/DrdsSql.java:24` | import `MycatOutputVisitor` | 导入更新为 D2 新包名 |
| `hbt/src/main/java/io/mycat/vertx/VertxExecuter.java:30`、`server/src/main/java/io/mycat/sqlhandler/dml/SetSQLHandler.java:19` | import `MycatSQLEvalVisitorUtils`（仅此 2 处活调用；`MycatInsertExecutor.java:15` 那行 import 已被注释，不算消费方） | 导入更新为 D2 新包名 |
| `server/.../config/MycatRouterConfigOps.java:21`、`server/.../sqlhandler/ddl/CreateIndexSQLHandler.java:17` | import `MycatSQLUtils` | 导入更新为 D2 新包名 |
| `router/src/main/java/io/mycat/router/function/AutoFunctionFactory.java:1181` | `new SQLExprParser(methodExpr)` | **无需改代码**：已实测官方 1.2.28 存在 `SQLExprParser(String)`，删影子后自动解析到官方 |
| `common/src/main/java/io/mycat/PreparedStatement.java:104,108`、`hbt/src/main/java/io/mycat/MetadataManager.java:683` | `new SQLHexExpr(s)` / `instanceof SQLHexExpr` | **无需改代码**：已实测官方存在 `SQLHexExpr(String)`；仅确认 `common` 能解析到 druid（必要时补直接依赖声明） |
| `hbt/.../sql/MycatSQLUtils.java:4` | B 组内部互相 import `MycatOutputVisitor` | 随 D2 新包名一同更新 |
| `hbt/.../sql/visitor/MycatSQLEvalVisitorUtils.java:32` | 通配导入 `com.alibaba.druid.sql.visitor.functions.*` | 保留通配导入取官方函数，另补一行 `io.mycat.sql.visitor.functions.Timestamp` 显式导入以接住 :138 的 `now` 注册 |

### D5 依赖与构建不动
`com.alibaba:druid` 保持根 pom `dependencyManagement` 的 `1.2.28` 单一真源，10 个声明 druid 的模块 pom **不改坐标、不引 fork**。本设计不新增 Maven 模块。

### D6 回归网：删除前先取"SQL 序列化黄金基线"
影子删除会改变 `MycatOutputVisitor` 的父类实现，SQL 文本输出可能漂移，必须以基线对照而非肉眼判断：

1. **删除前**捕获基线：用受控 classpath（把 `plug/target/classes`、`hbt/target/classes` 置于 druid jar **之前**，使当前影子确定胜出）跑一段采集程序，对语料（覆盖分片 DDL/DML、`<=>`、Hint、`GROUP_CONCAT`、反引号、`LIMIT`、常量折叠、`CREATE INDEX`、`SET` 语句）逐条输出 `MycatSQLUtils.toString(sql)`，落成 `hbt/src/test/resources/druid-output-baseline.txt`。
2. **删除后**同一语料由 JUnit 断言逐条比对。
3. **差异不自动判失败**：影子是 2017 旧代码，官方输出更可能是正确的一方。每条差异单独裁定并记入实施账本（`Golden: <sql> — <保留官方/需要移植> — <理由>`），需要移植的定制回到 D3 处理。

该基线测试同时充当 D3 的 RED 起点（先失败，改通后转绿）。

---

## 3. 验收门（全部需当前实证，禁止凭记录标 Done）

| 门 | 判定命令 | 通过条件 |
|---|---|---|
| G1 编译 | `mvn -B install '-Dmaven.test.skip=true' -pl server -am` | BUILD SUCCESS；产物 jar 时间戳新鲜 |
| G2 黄金基线 | `mvn -B test -pl hbt -Dtest=*DruidOutputGolden*` | 语料逐条通过，或每条差异已有账本裁定 |
| G3 启动 | `java -jar server/target/server-1.22-release-jar-with-dependencies.jar` | 日志出现 `Mycat Vertx server ... started up.`，8066 可连 |
| **G4 L1 真跑通（本设计的存在理由）** | 客户端连 8066 完成认证并**收到一条 SQL 的结果集** | 不再出现 `NoSuchMethodError`；服务端日志无该栈 |
| G5 无回退 | `mvn -B test -pl router,executor,hbt` | 相对本次改动前不新增失败 |
| G6 影子清零 | `git ls-files "*com/alibaba/druid*"` | 只剩 D2 例外条款允许的 `com.alibaba.druid.*.mycat` 子包条目（理想为 0） |

G4 是 M1 验证网挖出的原始缺陷；G4 不过即视为本设计未交付。

---

## 4. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| `MycatOutputVisitor` 195 个 override 的漂移量远大于预估的 ~5 | 工作量放大、周期拉长 | 编译器一次性给出完整清单；超预估 3 倍以上触发止损（第 5 节） |
| 删除后出现**行为回归**（某方言输出变错） | SQL 下发语义错误 | D6 黄金基线逐条对照，不靠肉眼 |
| B 组类依赖 druid 包级私有成员，搬名后编译不过 | D2 受阻 | 走 D2 例外条款（`...druid.*.mycat` 子包），不牺牲正确性 |
| `SQLExprParser` 官方 1.2.28 解析行为与旧影子不同，影响分片路由 | 路由结果变化 | G2 语料含分片 DDL/DML；G5 跑 router 测试 |
| `common` 对 druid 仅有传递依赖，删影子后解析不到 | G1 编译失败 | D4 末行已列为核对项，必要时补直接依赖声明 |
| `functions.Timestamp` 迁移时漏掉 `now` 注册 | `now` 常量折叠行为变化 | D2 已固定其在 `MycatSQLEvalVisitorUtils` 新包下的显式导入；G2 语料含 `now` 参数化折叠用例 |
| 官方父类字段不够，`MycatOutputVisitor` 改继承后编译不过 | D3 受阻 | 已用 `javap` 预先证实 `appender`/`dbType`/`parameterized`/`shardingSupport` 均为 protected（见 1.2 节）；若仍有缺口，走 D2 例外条款而不是回退到 fork |
| 影子中确有未被 `MYCAT CHANGE` 标注的隐性定制（已发现一例：`Timestamp` 替换官方 `now`） | 静默功能丢失 | D6 基线兜底；G2 差异即线索，逐条裁定 |

---

## 5. 止损条款

若实施中出现以下任一情况，**停止并回报**，不擅自扩大改动面：
- D3 的实际 override 失配数超过预估 3 倍（>15 处需实质重写），或需要重写官方 visitor 的内部算法；
- 发现 A 组影子里存在**无法用继承/SPI 表达**的上游内部改动（此情形会推翻 D1"不必 fork"的前提，需回到本 spec 重评）。

推翻前提须显式说明新证据，不得静默转向 fork。

---

## 6. 范围之外

- **M2-2 example 模块依赖腐烂**（`example/pom.xml` 缺 `replica`/`plug`，约 40 个测试文件编译失败，连带阻塞 `ConnectionSmokeTest`）：独立项，另出计划。
- L2 元数据仿真、L3 在线迁移、D11 前端认证矩阵、D12 版本硬编码：本设计只解锁 L1。
- 其他 D1 类遮蔽（`hbt` 下的 `org.apache.calcite.*`）：已由 calcite 系列 spec 覆盖，不在此重复处理。

---

## 7. 追溯

- 债务项：D1 跨模块同 FQCN 类遮蔽；D13 CI 验证网坏死（本设计解其启动/运行一半，example 部分留 M2-2）。
- 发现途径：M1 验证网（分支 `feat/m1-verification-net`，提交 `76bc806b3`→`47f940847`）。
- 看板动作：本 spec 批准后在看板登记 `M2-1 = druid 影子清理（不 fork）`，状态依铁律只用 `Evidenced-Done`。
