# I-DEBT-29 元数据 GUI / DBeaver 专轮 · 设计 Spec

> **状态**：✅ 已实现并本地收口（2026-10-30 · MYSQL_5_7 一档 · 本地 runtime-evidenced，CI 徽标待 push 另计）
> **收口实证**：hbt `VariantReprintFidelityTest` 13/13 + hbt 全套 268/0/0；`ClientTest` 拆离线/活体后 6/0/0（起 8066 persona=MYSQL_5_7 活体真跑，值断言过）；example 全量 539/0F/5E（残留 5E=XaBackendConnectionTest 环境族·正交）；核心面 server/proxy/router/internalexample 全绿。修法 A 落 `DrdsRunnerHelper.preParse` 入口。看板 DEBT-QUEUE §1 I-DEBT-29 + PROGRESS §7 第六列/里程碑 M3 已回写。余 I-DEBT-29-EXT（6 档）。
> **日期**：2026-10-30
> **债务**：`DEBT-QUEUE.md` §1 **I-DEBT-29**（A 层 · §7 验收面第六列「元数据 GUI」）
> **拆分票**：`I-DEBT-29-EXT`（其余 6 persona 档的元数据 GUI 扩面，本轮**不做**）
> **前置**：无（离线可复现，不需 Docker / 不需起 8066 即可锁死根因与主修）

---

## 1. 一句话

Mycat 把 druid 的 AST **reprint**（重新打印成 SQL 文本）后**再解析一次**；druid 1.2.28 在打印带作用域名（`@@global.x`）的系统变量时会**吞掉它前面的分隔逗号**，于是 Mycat 自己打印出来的文本自己读不回来，抛 `ParserException: token VARIANT`。DBeaver 建连时的元数据序列正好有这条语句 ⇒ GUI 里"连不上/属性页报错"。

## 2. 这为什么是招牌上的格子，不是又一条 C 层崩栈

- 北极星（主 spec §1.1）：客户端眼中**无缝替代** MySQL 5.7/8.0/8.4/9.x（MariaDB 为 M6 扩展位）。
- 验收尺 §7 六列：连接认证 / TLS / CRUD 事务 / connector 矩阵 / mysqldump 往返 / **元数据 GUI**。
- 前五列现有证据已 ☑×7 或 ◐×7（见 `PROGRESS.md` §7 逐列证据锚）；**第六列是全表唯一有"实测红"的一格**，且红在 MYSQL_5_7 档。
- GUI（DBeaver / MySQL Workbench / Navicat 类）是"客户端"最真实的形态之一。它一报错，"无缝替代"在用户眼里就不成立——所以这是 **A 层硬门本体**，不是嗓门大的引擎债。

## 3. 现象与证据（全部为当前 HEAD `b74b201ec` 实测，不采信任何既有记录口径）

### 3.1 离线 RED（已在库，`example/src/test/java/io/mycat/client/ClientTest.java:55-56`）

```java
DrdsSqlWithParams drdsSqlWithParams =
    DrdsRunnerHelper.preParse("SELECT @@global.character_set_server, @@global.collation_server", null);
Assert.assertEquals("SELECT @@global.character_set_server, @@global.collation_server",
        drdsSqlWithParams.getParameterizedSQL());
```

基线日志实测（`.tmp-example-baseline.log`，`mvn -o -B -pl example test`）：

```
[ERROR] io.mycat.client.ClientTest.testDBeaver -- Time elapsed: 0.007 s <<< FAILURE!
org.junit.ComparisonFailure:
  expected:<...character_set_server[,] @@global.collation_...>
  but was:<...character_set_server[] @@global.collation_...>
    at io.mycat.client.ClientTest.testDBeaver(ClientTest.java:56)
```

### 3.2 活体 e2e（一次性探针，8066 · `persona=MYSQL_5_7` · 跑完即删）

DBeaver 21.2.5 元数据序列 12 条 → **10 条 OK**，唯 2 条 charset/collation 全局变量查询抛
`com.alibaba.druid.sql.parser.ParserException: syntax error. pos 93, line 2, column 38, token VARIANT`。

### 3.3 基线上下文（同一轮全量跑，用于"零新增红"判据的分母）

`539 tests / 3F / 38E / 33skip`；41 项红逐名归属：XA 族 36（串跑环境族，本批未隔离复跑）+ `MariadbEd25519BackendTest` 4（3308 容器未起）+ **`ClientTest.testDBeaver` 1 = 唯一真产品红**。

## 4. 根因（钉到 druid 源码单行）

取证方式：从 `D:\Nexus\repository\com\alibaba\druid\1.2.28\druid-1.2.28-sources.jar` 解出源码 + 两条**纯 druid**一次性探针（不经 Mycat、不起 Docker）。

### 4.1 三段因果链

**① 病根：`hasPrefixComma` 初始化不对称**
`com/alibaba/druid/sql/ast/expr/SQLVariantRefExpr.java`

| 位置 | 事实 |
|---|---|
| L33 | `private boolean hasPrefixComma;` → 缺省 **false** |
| L37-45 / L47-56 | 单参 `(String)` 与 `(String, SQLObject)` 构造器 **置 `hasPrefixComma = true`** |
| L58-60 / L62-66 | `(String, boolean)` 与 `(String, boolean, boolean)` 构造器 **从不赋值** → 留在 false |
| L68-69 | 无参构造器同样不赋值 |

Parser 对 `@@global.x` / `@@x`（带作用域写法）走 **三参构造**（false），对裸 `@@x` 走 **一参构造**（true）。⇒ 凡"作用域名系统变量"生成的 variant 天生带病。

**② 消费点：把逗号换成空格的确切一行**
`com/alibaba/druid/sql/visitor/SQLASTOutputVisitor.java` `printSelectList` **L603-607**

```java
if (selectItem.getExpr() instanceof SQLVariantRefExpr && !((SQLVariantRefExpr) selectItem.getExpr()).isHasPrefixComma()) {
    print0(" ");          // ← 吞逗号在这里
} else {
    print0(", ");
}
```

**③ 我方自咬**
`hbt/src/main/java/io/mycat/calcite/DrdsRunnerHelper.java:111`

```java
return new DrdsSqlWithParams(params.isEmpty() ? sqlStatement.toString() : sb.toString(), ...)
```

无参 ⇒ 交 `sqlStatement.toString()`（即 ② 产出的坏文本）⇒ `DrdsSql.getParameterizedStatement()`（L82-83）再 `SQLUtils.parseSingleMysqlStatement(...)` ⇒ **与后端无关的确定性 ParserException**。

**旁注（解释"`@@session.x` 为什么一直没事"）**：`@@session.x` 被 druid 解析成 `SQLPropertyExpr`（不是 variant），根本不走 ② 这个分支 ⇒ 永久免疫。

### 4.2 确定性规则（取代此前"偏离形态未穷尽"的说法）

> `selectList` 中**下标 > 0** 的项为 `SQLVariantRefExpr` 且 `hasPrefixComma == false` ⇒ 其**前置分隔逗号被换成空格**。

`Probe.java`（一次性）12 例变体矩阵 12/12 吻合，且与 reparse 结果严格自洽（吞 ⇒ 必 `token VARIANT`；未吞 ⇒ reparse OK）：

| # | 输入 | reprint | reparse | 判据预测 |
|---|---|---|---|---|
| 1 | `SELECT @@GLOBAL.character_set_server` | `@@global...` | OK | 下标 0，无前置逗号 ✓ |
| 2 | `SELECT @@character_set_server,@@collation_server` | 逗号在 | OK | 两项均一参构造 true ✓ |
| 3 | `SELECT @@GLOBAL.x, @@collation_server` | 逗号在 | OK | [1] 是裸 `@@x`＝true ✓ |
| 4 | `SELECT @@character_set_server,@@GLOBAL.collation_server` | **吞** | THREW col 31 | [1] global＝false-prefix ✓ |
| 5 | `SELECT @@GLOBAL.x, 1` | 逗号在 | OK | [1] 是 `SQLIntegerExpr` ✓ |
| 6 | `SELECT 1, @@GLOBAL.collation_server` | **吞** | THREW col 10 | [1] global ✓ |
| 7 | `SELECT @@session.sql_mode, @@session.autocommit` | SAME | OK | 两项 `SQLPropertyExpr` ✓ |
| 8 | `SELECT @@global.max_allowed_packet, @@global.character_set_database` | **吞** | THREW col 36 | [1] global ✓ |
| 9 | `SELECT @@global.character_set_server, @@global.collation_server` | **吞** | THREW col 38 | 同 8（＝§3.1 离线红） ✓ |
| 10 | `SELECT @@global.a, @@session.b` | SAME | OK | [1] `SQLPropertyExpr` ✓ |
| 11 | `SELECT @@session.a, @@global.b` | **吞** | THREW col 20 | [1] global ✓ |
| 12 | `SELECT @@global.a, @@global.b, @@global.c` | **两处均吞** | THREW col 19 | [1][2] 均 global ✓ |

### 4.3 最小修假设已实测证真（不是推理）

`Probe2.java`（一次性）：**不动打印器、不 fork**，仅在解析后遍历 selectList、对 `SQLVariantRefExpr` 调 `setHasPrefixComma(true)` ⇒

- **12/12 全部 `reparse=OK`**
- 6 例逐字节 `SAME`，其余 6 例差异**仅为无害归一**（`@@GLOBAL`→`@@global`、逗号后补空格）

## 5. 修法（拍板方案 A · 已定案）

### 5.1 方案 A：reprint 前的一处属性归一

**位置**：`hbt/.../DrdsRunnerHelper.preParse`，在 `MycatPreparedStatementUtil.outputToParameterized(...)`（L104）**之前**、AST 已就绪处，对整棵 statement 做一次归一：所有 `SQLVariantRefExpr` ⇒ `setHasPrefixComma(true)`。

**为什么是"正确缺省恢复"而非"特例兜补丁"**：`hasPrefixComma` 的语义就是"该 select 项前面有没有逗号"，凡作为**独立 select item 存在**的项，前面**本来就有**逗号（下标 >0）或**不需要**逗号（下标 0）；置 true 在两种情形下都产出正确文本。druid 自己的一参构造器就是 true（L44/L55），三参漏赋是该字段引入时的**初始化不对称缺陷**，不是设计意图。

**作用域与遍历**：须覆盖**整棵 AST**（不限 selectList）——WHERE / SET / 函数实参位置的 variant 同样可能是三参构造所生；`printSelectList` 目前是唯一已知消费点，但把不变式恢复成"凡 variant ⇒ 有前置逗号语义"更稳、也无需枚举节点类型。

**blast radius 证据（实测）**：该字段在 druid 内**只被 `printSelectList` 一处读取**（源码 grep 坐实）；不读不写于参数计数、`index`、下推逻辑。⇒ 单点、幂等、无状态漂移。

### 5.2 已否决方案（留痕，禁止实现期重试）

| 方案 | 否决理由（均有证据） |
|---|---|
| **覆写 `MySqlOutputVisitor.visit(SQLVariantRefExpr)`** | 上一轮推荐，**作废**。该方法（L794-834，`return false`、无 `endVisit` 覆写）**根本不负责分隔逗号**——逗号在 ② 的 `printSelectList` 里印。覆写它治不到根，还要接管逐字符 `print(ch)` 与 `COLLATE` 属性拼接，接管面更大。 |
| **无参时保留原始 SQL 文本**（旧 Plan B） | 已被历史记录证伪：`toString()` 反映的是 visitor 改写后的 AST（如 `` FROM `myschema`.t ``），保留原文会丢 `defaultSchema` 限定 ⇒ 炸全部分片路由。**不得重试该裸形态。** |
| **fork druid** | 2026-10-29 撤销裁定 + 2026-10-30 方案 A 实测证真，无需。既有裁定"不 fork druid、回归官方依赖"继续有效。 |

## 6. 测试设计（TDD，RED-first）

> 铁律：先写失败测试、亲眼看着它红，再改产品码。每条锁测都要能答"什么产品码改动会让它失败"。

### T0（已在库 · 当前 RED）— 离线断言锁
`ClientTest.testDBeaver:55-56` 现状即本债的 RED 锁，失败原因正确（丢逗号、非编译/环境问题）。**先跑一次记录红名，再动码。**

### T1（RED 前置重构）— 拆开"卖灯缺口"
**问题**：`testDBeaver` 崩在 L56 离线断言、实测 `Time elapsed: 0.007 s` ⇒ 方法体 L78-90 的活体 DBeaver 序列**从未被执行**。所以"12/12 活体绿"这条判据**此前根本不可取证**——修完也是假背书。

**做法**：把该测试类里两条正交关注点拆成两个方法：
- `testDBeaverMetadataReprintFidelity`（离线，不需 infra）：断 `preParse(...).getParameterizedSQL()` 保真；
- `testDBeaverMetadataSequenceLive`（活体，需 8066 + infra）：跑 DBeaver 12 条序列，并**补上原本缺失的断言**——L83 那条 `SELECT @@GLOBAL.character_set_server,@@GLOBAL.collation_server` 必须**返回 1 行 2 列且两列非空**（现状 `executeQuery` 不校验返回值 = 哑断言）。

**T1 的 RED 验证**：拆分后先跑一次——离线方法仍红（同一丢逗号签名），活体方法也红（后端未变，但这次是**真跑到 8066 后**抛 ParserException）。两红各自签名不同，即为拆分有效。

### T2（GREEN 主锁）— 12 例变体矩阵进套件
把 §4.2 的 12 例做成**参数化**离线测试（`@ParameterizedTest` + `@MethodSource`），每条断两件事：
1. `preParse(sql, null).getParameterizedSQL()` **能被 druid 再解析**（这是真正的失效面）；
2. reprint 与预期归一文本相等（预期列内联写明，如 `@@GLOBAL`→`@@global`）。

放置模块：`hbt`（`DrdsRunnerHelper` 所在模块，纯离线、秒级）。

### T3（守卫）— 非 select 位置的 variant
新增 1-2 例：`SELECT 1 FROM t WHERE @@global.sql_mode IS NOT NULL` 形态，钉"遍历整棵 AST 而非只挑 selectList"的作用域决定。若该形态本就正常，则 T3 是一条**防回归**的守卫锁（须在报告中如实说明它当前不红，不冒充 RED）。

### 验证序（不可省）
1. `mvn -o -B -pl hbt test -Dtest=...`（T2/T3 RED→GREEN）
2. `mvn -o -B -pl server -am -DskipTests` 重建 fat-jar → 重启 8066（`persona=MYSQL_5_7`）
3. `mvn -o -B -pl example test -Dtest=ClientTest`（T0/T1/T2 活体绿）
4. **example 全量**（判据 3 的"零新增红"必须有全套件逐名报告）：与 §3.3 基线 539/3F/38E/33skip 对比，**红名单逐名报告，含非本次引入者**
5. `mvn -o -B clean install -DskipTests=false` 全 reactor 护栏（19 模块）

## 7. 判据与诚实边界

**本轮判据（2026-10-30 用户拍板：只收口 MYSQL_5_7 一档）**
1. DBeaver 元数据序列 12/12 在 `persona=MYSQL_5_7` 活体绿，且 L83 那条**带值断言**（1 行 2 列非空）；
2. `ClientTest.testDBeaver` 离线锁转绿（拆分后为 `testDBeaverMetadataReprintFidelity`）；
3. example 全量**零新增红**（相对 §3.3 基线）；
4. hbt/server 套 + 全 reactor 零回归；
5. §7 第六列 MYSQL_5_7 格由 🔴 转 ☑。

**诚实边界（不得越线）**
- 其余 6 档（8.0/8.4/9.x + MariaDB×3）的元数据 GUI **仍无任何证据**，§7 该列保持 ☐，由 **I-DEBT-29-EXT** 跟踪；**禁止**因 5.7 绿而对外称"元数据 GUI 全档支持"。
- XA 族 36 项与 mariadb:3308 的 4 项红**不在本票范围**，属环境/infra 族；本轮只保证"不新增红"，不承诺它们转绿。
- 若活体跑动暴露与 druid 吞逗号**正交**的元数据缺陷，据实另立债务行，不在本票内夹带扩面。

## 8. 实现期落地清单（交给 plan 阶段展开）

1. `hbt/.../DrdsRunnerHelper.preParse`：加一处 variant 属性归一（含遍历方式选型：优先 druid 现成 `SQLUtils.walk*`/`SQLASTVisitorAdapter`，避免手写递归）。
2. `hbt` 新增参数化离线锁（T2/T3）。
3. `example/.../ClientTest.java`：T1 拆分 + 补活体值断言。
4. 回写看板：`DEBT-QUEUE.md` §1 I-DEBT-29 行状态 + §6 关闭序、`PROGRESS.md` §7 第六列 MYSQL_5_7 格 + 里程碑勾选板 M3 的 ⚠ 注记。
5. 清理一次性探针：`.superpowers/tmp-druidsrc/**`（`Probe.java`/`Probe2.java`/解出的 druid 源）与 `.tmp-*.log` —— **探针属例外件，用完即删，不进产品树**。

## 9. 环境与配方（复现所需，避免下轮重新侦察）

- `JAVA_HOME` **必须** `D:\Program Files\BellSoft\LibericaJDK-21-Full`；复用 shell 会漂到 JDK25 ⇒ lombok 静默失效（症状：`@SneakyThrows`→"未报告的异常错误"、`@Slf4j`→"找不到符号 变量 log"）。每条 mvn 前显式设并 `mvn -version` 自检。
- 容器：`m1-mysql-a`(3306) / `m1-mysql-b`(3307)，`mysql:8.0 --max-allowed-packet=64M`（mysqld flag 必须放镜像名**之后**）；`SET GLOBAL max_connections=1000`。
- 起服务：`.superpowers/start-server.ps1`（Liberica21 + `--add-opens=java.base/java.nio=ALL-UNNAMED` + `-Dmycat.persona=MYSQL_5_7` + fat-jar）。
- fat-jar：`mvn -q package -pl server -am -DskipTests`（≈8 分钟）。
- druid 源码取证：`jar xf druid-1.2.28-sources.jar <path>`，用毕即删。
