# R5 基本 CRUD 全绿 · 三枚引擎深债根 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native inline) 逐任务实现本计划。Steps 用 checkbox (`- [ ]`) 跟踪。诊断型任务（A1/B1·B2/C1）的"机制"由实跑证据定，契约(对外行为)已全指定——非 TBD。

**Goal:** 关掉 compat `CrudResultMatrixTest` 跨全 7 档恒定的 3 枚红格（I-DEBT-39/34/36），点亮 M2"基本 CRUD"子门。

**Architecture:** 三根独立、体量递增（Root A→B→C）。Root A=语句级运行期错误回干净 ERR 且会话存活；Root B=BIT 列对外按 MySQL/MariaDB 官方 byte[] 口径交付并消除 codegen 声明/运行时错配崩会话；Root C=TIMESTAMPDIFF 三参原生透传破 Reinterpret deparse 关。每根出口判据=对应 compat 格转绿，最终 run-matrix ×7 全档背书。

**Tech Stack:** Java 21、Maven(离线)、Vert.x 运行时、Calcite fork(1.42)、JUnit4、Docker(后端逐版本)、PowerShell 7 矩阵驱动。

**Spec:** `docs/superpowers/specs/2026-10-12-r5-crud-debt-roots-design.md`（本计划论证源，执行时同读）。

## Global Constraints

- JDK **21**：`$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"`（shell 默认 JAVA_HOME 可能指 JDK 8，命令内显式设定）。
- Maven **离线**：所有 `mvn` 加 `-o`；构建/测试用 `-pl <module> -am`。
- **本地不 push**：每任务末尾 commit 仅本地；提交排除 `.qoder`/`.superpowers`/`version.txt`/`version.txt.template`/`.mycat-home-e2e`。用**精确 git add**（逐文件路径），禁 `git add -A`。
- 权威验证网：`pwsh -File .superpowers/sdd/2026-09-30-m1-verification-net/run-matrix.ps1` → 逐 7 档跑 `ConnectionSmokeTest,FrontAuthMatrixTest,BackendAuthMatrixTest,CrudResultMatrixTest`，产出 `.superpowers/sdd/2026-09-30-m1-verification-net/matrix-report.md`（PASS/FAIL/N-A/ERROR/NO_DATA）。
- 快环（单根开发用）：起单档后端(mysql:8.0，容器名 `m1-mysql-a`@3306 / `m1-mysql-b`@3307) + classpath 直启 Mycat@8066（免打 fat-jar，见 memory"Mycat2 本地 e2e 快速迭代"），再 `mvn -o -pl example test -Dtest=CrudResultMatrixTest#<method>`。
- **Evidenced-Done 铁律**：任何格标绿须当次实跑输出为证；commit message 自述不作数。
- 回归裁判：改前跑一次相关模块基线并记录，改后比对**零新增红**。

## Review Focus

规格隐含、但任务测试未逐一覆盖、最可能咬使用者的输入类（每行在归属任务补钉测）：
1. **运行期错误过窄/过宽**（Root A）：修"除零断连"若把**致命/协议级错误**也强行保活 = 掩盖真故障；或只修除零一类、其它语句级错误仍断连。→ Task A2 补：非仅 `select 1/0`，`select <不存在的列>`、跨片查询中途错误也须干净 ERR + 会话存活；而 `handleQuit`/socket 致命仍正常关。
2. **BIT(1) 边界**（Root B）：byte[] 重构误伤 BIT(1)（应仍 Boolean）。→ Task B3 补 BIT(1) 形状断言不破。
3. **BIT 多位字节序/NULL**（Root B）：BIT(>8) 大端多字节、NULL BIT 值读出。→ Task B3 补 `bitColumnFidelity` 的 BIT(16) 与 NULL 变体对拍。
4. **TIMESTAMPDIFF 其它单位/NULL**（Root C）：非仅 SECOND——MINUTE/HOUR/DAY/MONTH/YEAR 与 NULL 参数不得崩。→ Task C2 补单位变体 + 旧分支 helper 单位映射断言。
5. **单片 vs 跨片归并并存**（Root B/C）：修 codegen 表征后，点查(单片命中)与跨片聚合两条路都不得回归。→ Task B3/C2 保留 C7/C8 跨片格为回归裁判。

---

## Root A — I-DEBT-39 运行期错误干净 ERR 不断会话

### Task A1: 复现 + 定位真断连点（诊断，无生产码）

**Files:**
- Read: `server/src/main/java/io/mycat/mycatmysql/MycatVertxMySQLHandler.java:300-337`（query promise `.recover` → `writeErrorEndPacketBySyncInProcessError(errorCode)`）
- Read: `server/src/main/java/io/mycat/vertx/VertxSessionImpl.java:153-190`（`writeErrorEndPacketBySyncInProcessError` + `close` 写守卫）
- Read: `proxy/src/main/java/io/mycat/proxy/handler/backend/PrepareStmtTask.java:102-110`、`proxy/src/main/java/io/mycat/proxy/handler/MySQLPacketExchanger.java:46-54`（后端错误回调路径）
- Read: `proxy/src/main/java/io/mycat/proxy/session/MySQLProxyServerSession.java:88-98`（"写入不完整尝试四次之后关闭 session" 语义）
- 诊断产物（workspace，非提交）：`.superpowers/sdd/2026-10-12-r5-crud-debt-roots/a1-rootcause.md`

**Interfaces:**
- Consumes: 复现器 `CrudResultMatrixTest#divideByZeroIsCleanError`（已存在，当前 ERROR/红）。
- Produces: `a1-rootcause.md` 记录「哪一类错误经哪条路径关闭了前端会话」的分类结论（语句级 vs 协议级 vs 致命级），并锁定 Task A2 该改的**确切文件:行 + 分支条件**（后续任务据此实现）。

- [ ] **Step 1: 起单档 e2e（mysql:8.0 后端 + Mycat@8066），跑复现器捕获当前失败**

Run: `mvn -o -pl example test -Dtest=CrudResultMatrixTest#divideByZeroIsCleanError`
Expected: FAIL/ERROR——`select 1/0` 后同连接 `select 1` 抛 `No operations allowed after connection closed`（即会话被杀，非干净 ERR）。

- [ ] **Step 2: 定位 ERR 写后为何仍断连**

在候选路径（A1 Files 所列四处）加**临时**日志或用调试器观察 `select 1/0` 一次请求的完整流向：后端回 ER 1365 → 前端哪一处调用 `close(...)`（置写守卫后 socket 变 no-op）或 socket 被 `end()`。记录调用栈到 `a1-rootcause.md`。
Expected: 找到"写了 ERR 却又关闭会话"的确切代码位置，或"根本没走到 recover、被更上游致命路径截获"的证据。

- [ ] **Step 3: 分类并锁契约**

`a1-rootcause.md` 产出三分类：① 应干净 ERR+保活（语句级：除零、未知列、后端 SQL 错）；② 应正常关闭（QUIT、协议破坏、传输致命）。点名 Task A2 要改的 `file:line` 与判定条件（如"仅当 resultset 头未上线/错误为语句级时不 close"）。
Expected: 文档存在，含可执行的修复指令（下一任务照做，不再重新摸索）。

- [ ] **Step 4: 清理临时日志，仅归档诊断文档**

撤销 Step 2 的所有临时日志改动（`git checkout` 相关文件），`.superpowers` 下文档不提交。工作区回到干净状态。

### Task A2: 最小修复 + 干净 ERR 契约钉测

**Files:**
- Modify: Task A1 定位的 `file:line`（预期在 `MycatVertxMySQLHandler.java:310-336` 或后端错误回调路径）
- Test: `example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java`（`divideByZeroIsCleanError` 增补 Review Focus #1 变体）

**Interfaces:**
- Consumes: `a1-rootcause.md` 的修复指令；`MySQLPacketUtil.generateError(errorCode, message, capabilities)`（既有）。
- Produces: 语句级运行期错误 → 单枚 ERR、会话存活；行为契约由 C10 钉死。

- [ ] **Step 1: 扩充 C10 复现器覆盖多类语句级错误（Review Focus #1）**

在 `divideByZeroIsCleanError` 内、除零断言后追加：先 `createCell("c10", "`id` bigint PRIMARY KEY")` 建格表，再用同连接跑一条未知列查询（仍要求会话存活）：
```java
String t = createCell("c10", "`id` bigint NOT NULL, PRIMARY KEY(`id`)");
// 未知列：仍须干净 ERR + 会话存活
assertThrows(SQLException.class, () -> executeQuery(probe, "select `no_such_col_zzz` from " + t));
// 出错后仍可继续
List<Map<String,Object>> still = executeQuery(probe, "select 2");
assertEquals(1, still.size(), "C10+：多条语句级错误后会话仍存活");
```
Expected: 先跑 → FAIL（现状这些错误也会打挂会话，或至少除零那条挂）。

- [ ] **Step 2: 按 a1-rootcause.md 实现最小修复**

只收窄"语句级错误关会话"这一类分支：让语句级/后端 SQL 错误经 `writeErrorEndPacketBySyncInProcessError` 回 ERR 而**不**触发 `close`；致命/QUIT/协议破坏保持原关闭语义不动。
（此处实现体由 A1 证据决定，不预先臆造。）

- [ ] **Step 3: 跑扩充后的 C10**

Run: `mvn -o -pl example test -Dtest=CrudResultMatrixTest#divideByZeroIsCleanError`
Expected: PASS（除零与未知列均干净 ERR，`select 1`/`select 2` 后续可跑）。

- [ ] **Step 4: 回归——全套 CrudResultMatrixTest（单档）**

Run: `mvn -o -pl example test -Dtest=CrudResultMatrixTest`
Expected: 除仍红的 C6/C9（Root B/C 未动）外，其余格不因 A2 新增红；`FrontAuthMatrixTest`/`ConnectionSmokeTest` 零回归。

- [ ] **Step 5: Commit（本地）**

```bash
git add example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java <A2 定位到的 server/proxy 源文件>
git commit -m "fix(I-DEBT-39): 语句级运行期错误回干净ERR且保持会话存活(除零/未知列不再打挂连接)"
```

---

## Root B — I-DEBT-34 BIT 列官方 byte[] 口径 + 消表征错配

### Task B1: C6 改造为"后端直连 vs Mycat 前端"金标准对拍（RED）

**Files:**
- Modify: `example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java:165-174`（`bitColumnFidelity`）
- Test 辅助：本类已有 `mycat`(前端连接)、`adminBackend`/`getMySQLConnection`(后端直连)——如缺后端直连句柄则用 `DriverManager` 直连 3306 建一条。

**Interfaces:**
- Consumes: 后端直连 JDBC（绕过 Mycat，直连 `m1-mysql-a`@3306）。
- Produces: 新契约复现器 `bitColumnFidelity`——对同一行 `bit(8)`，Mycat 前端 `getObject("c_bit")` 与后端直连 `getObject` 类型+字节**逐字节一致**；现网下 RED。

- [ ] **Step 1: 重写 bitColumnFidelity 为对拍**

替换现断言（179/Number），改为：写入同一行（经 Mycat）后，分别从"Mycat 前端连接"与"后端直连"各读一次 BIT 值，以直连真库返回为**基准**逐字节对拍。直连句柄用本类既有 `getMySQLConnection(DB1)`（同 `adminBackend`，3306）；id=1 经 mod_hash 只落一个物理库，先用 `information_schema.tables where table_schema like 'dbcrud%' and table_name='crud_c6'` 定位物理库名再查（同 `purgePhysical` 手法）。
```java
@Test
public void bitColumnFidelity() throws Exception {
    String t = createCell("c6", "`id` bigint NOT NULL, `c_bit` bit(8), PRIMARY KEY(`id`)");
    execute(mycat, "insert into " + t + "(`id`,`c_bit`) values (1, b'10110011')");
    Object viaMycat = executeQuery(mycat, "select `c_bit` from " + t + " where `id`=1").get(0).get("c_bit");
    String phys = locatePhysicalSchema("crud_c6"); // information_schema 定位，helper 同文件私有
    try (Connection back = getMySQLConnection(DB1)) {
        Object golden = executeQuery(back, "select `c_bit` from `" + phys + "`.`crud_c6` where `id`=1").get(0).get("c_bit");
        assertArrayEquals(canonicalBytes(golden), canonicalBytes(viaMycat),
            "C6：Mycat 前端 BIT(8) 读出须与后端直连真库逐字节一致（I-DEBT-34 官方口径）");
    }
}
```
`canonicalBytes(Object)`：byte[] 原样；Number→大端最小字节；Boolean→1 字节；String→UTF-8。真库基准（Connector/J 对 BIT(8) 返 byte[] `{(byte)0xB3}`）。

- [ ] **Step 2: 跑改造后的 C6（单档 mysql:8.0）**

Run: `mvn -o -pl example test -Dtest=CrudResultMatrixTest#bitColumnFidelity`
Expected: FAIL/ERROR——现状 BIT(8) 经 Mycat 前端崩会话（EOFException/连接丢失）或返回类型≠byte[]，与直连金标准不符。

- [ ] **Step 3: Commit（测试网先于实现，本地）**

```bash
git add example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java
git commit -m "test(I-DEBT-34): C6 bitColumnFidelity 改为后端直连vs前端逐字节对拍(官方byte[]金标准,现RED)"
```

### Task B2: 表征错配诊断 + 定方向（诊断）

**Files:**
- Read: `hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java:180-195`（`knownGapBitColumnIsRepresentedAsBooleanNotLong` 现状钉测）
- Read: `hbt/**/vertx/BaseRowObservable.java`（case BIT → Long 运行时）
- Read: `server/**/mysqlclient/decoder/ObjectArrayDecoder.java:245`（`PacketUtil.decodeBit`）
- Read: fork `SqlTypeName.java:295-305`（`Types.BIT→BOOLEAN`）
- 诊断产物：`.superpowers/sdd/2026-10-12-r5-crud-debt-roots/b2-representation-decision.md`

**Interfaces:**
- Consumes: Task B1 的失败证据；C5(现绿) 作 varbinary 回归裁判。
- Produces: 二选一的表征归属裁定（① 行运行时扳成 byte[] 使声明/运行时一致；② 声明面 codegen 扳数值 Long + 编码层转 MySQL wire byte[]）+ 前端结果集如何发 `MYSQL_TYPE_BIT`+原始字节的落点 `file:line`。Task B3 照此实现。

- [ ] **Step 1: 跑 hbt 现状守卫基线，记录**

Run: `mvn -o -pl hbt test -Dtest=TypeRepresentationThreePathConsistencyTest`
Expected: 全绿（knownGap 钉测锁住"Boolean 表征"现状）——记为改前基线。

- [ ] **Step 2: 追前端结果集 BIT 字段的 wire 编码落点**

定位 Mycat 前端把行值写进结果包时，BIT 列的字段类型(`MYSQL_TYPE_BIT`)与值序列化代码；确认崩溃发生在 codegen 强转（声明 Boolean 取实 Long）而非 wire 层。写入 `b2-representation-decision.md`。

- [ ] **Step 3: 定方向并锁 Task B3 契约**

按"最小且与官方口径一致 + 不伤 C5/C7/C8"裁定方向；文档给出 B3 的精确改动清单（改哪个 getJavaClass/归并分支/编码器，knownGap 钉测如何更新到新表征）。**不得**把 BIT(1) 从 Boolean 扳走（Review Focus #2）。
Expected: 文档存在且含可执行清单。

### Task B3: 引擎修复 + 前端 byte[] wire + 更新钉测（GREEN）

**Files:**
- Modify: Task B2 清单指定处（预期含 `BaseRowObservable` BIT 归并 / codegen 表征 / 前端结果集编码器之一或组合）
- Modify: `hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java`（knownGap 更新到新契约）
- Test: `example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java`（C6 补 BIT(1)/BIT(16)/NULL 变体）

**Interfaces:**
- Consumes: `b2-representation-decision.md`；Task B1 对拍复现器。
- Produces: BIT(1)→Boolean、BIT(n>1)→byte[]（前端 `MYSQL_TYPE_BIT`+大端原始字节），跨片归并不崩。

- [ ] **Step 1: 补 Review Focus 变体测试**

`bitColumnFidelity` 增：BIT(1) 写 `b'1'` 期望前端 Boolean true；BIT(16) 写 `b'1000000000000001'` 期望 2 字节大端 `{(byte)0x80,0x01}`；NULL 行期望读出 null。均以直连金标准为准。
Expected: 跑 → 变体随主格一同 RED。

- [ ] **Step 2: 更新 hbt knownGap 钉测到新表征**

把 `knownGapBitColumnIsRepresentedAsBooleanNotLong` 依 B2 裁定改名/改断言，锁新一致表征（声明=运行时，不再跨层错配）。
Expected: 跑 `mvn -o -pl hbt test -Dtest=TypeRepresentationThreePathConsistencyTest` → 绿（钉测反映修复后契约）。

- [ ] **Step 3: 实现引擎修复**

按 `b2-representation-decision.md` 清单改生产码：消除 Boolean/Long 强转崩、前端 BIT 字段按官方口径序列化。实现体由 B2 证据定，不预造。

- [ ] **Step 4: 跑 C6 全变体 + hbt 守卫回归**

Run: `mvn -o -pl example test -Dtest=CrudResultMatrixTest#bitColumnFidelity` 然后 `mvn -o -pl hbt test`
Expected: C6 各变体 PASS；hbt 全量绿（基线 128/0/0 不新增红）；C5 varbinary 仍绿（表征改动首要雷区零回归）。

- [ ] **Step 5: Commit（本地）**

```bash
git add <B3 生产码文件> hbt/src/test/java/io/mycat/calcite/TypeRepresentationThreePathConsistencyTest.java example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java
git commit -m "fix(I-DEBT-34): BIT列对外官方byte[]口径(前端MYSQL_TYPE_BIT+大端原始字节)+消codegen声明/运行时错配,跨片归并不崩;C6对拍转绿"
```

---

## Root C — I-DEBT-36 TIMESTAMPDIFF 原生三参透传

### Task C1: 收割旧分支 f685ad60c 进 main（消首层 CCE）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatCalciteMySqlNodeVisitor.java:1911-1915`（unit `createCharString` → `SqlIntervalQualifier`）+ 新增 helper `timestampDiffUnitToQualifier`
- Test: `example/src/test/java/io/mycat/sql/UserCaseTest.java`（新增 `caseI36Timestampdiff`，从分支/工作区 `2026-10-10-i-debt-36-timestampdiff` 取，断言 86400L，作 RED 复现锁）

**Interfaces:**
- Consumes: 分支提交 `f685ad60c`（`git show f685ad60c`）。
- Produces: `MycatCalciteMySqlNodeVisitor.timestampDiffUnitToQualifier(String unit) -> SqlIntervalQualifier`（enum 名与 `TimeUnit` 对齐）；首层 `SqlCharStringLiteral→SqlIntervalQualifier` CCE 消失；阻塞后移至 deparse `Reinterpret`（`caseI36Timestampdiff` 仍 RED，但失败签名变）。

- [ ] **Step 1: 取旧分支改动比对，移植到 main 现码**

Run: `git show f685ad60c -- hbt/src/main/java/io/mycat/calcite/MycatCalciteMySqlNodeVisitor.java`
按当前 main 行号适配移植：TIMESTAMPDIFF case 用 `timestampDiffUnitToQualifier(arg0.toString().toUpperCase())` 替换 `SqlLiteral.createCharString(...)`；新增该 static helper（`TimeUnit.valueOf(unit)` → `new SqlIntervalQualifier(tu, tu, SqlParserPos.ZERO)`）。

- [ ] **Step 2: 移植 caseI36Timestampdiff 红锁进 UserCaseTest**

从分支/工作区取该测试方法落入 main `UserCaseTest`；断言 `select timestampdiff(SECOND,...)` = 86400L。
Expected: 编译通过。

- [ ] **Step 3: 跑 C9 + caseI36，确认首层 CCE 消、阻塞后移**

Run: `mvn -o -pl hbt test-compile` 然后（起 e2e）`mvn -o -pl example test -Dtest=CrudResultMatrixTest#timestampDiffFunctionCell`
Expected: 不再是 `SqlCharStringLiteral→SqlIntervalQualifier` CCE；改为 deparse 阶段 `Reinterpret`/SPECIAL 无法反解析的 `UnsupportedOperationException`（或同类）。→ 证明收割正确、C2 待写真活。

- [ ] **Step 4: Commit（本地）**

```bash
git add hbt/src/main/java/io/mycat/calcite/MycatCalciteMySqlNodeVisitor.java example/src/test/java/io/mycat/sql/UserCaseTest.java
git commit -m "refactor(I-DEBT-36): 收割旧分支f685ad60c—TIMESTAMPDIFF unit扳SqlIntervalQualifier消首层CCE+红锁caseI36;阻塞后移至Reinterpret deparse"
```

### Task C2: 原生三参透传，破 Reinterpret deparse 关（GREEN）

**Files:**
- Modify: TIMESTAMPDIFF 规划/deparse 落点（`hbt/**`，避免落 Calcite 标准 `TIMESTAMP_DIFF` convertlet 的 `Reinterpret` 算术重写；保留 Mycat 自有 `TimestampDiffFunction` 三参原生形态进入 SQL 生成/下推）
- Test: `example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java`（C9 补 Review Focus #4 单位变体）、`UserCaseTest#caseI36Timestampdiff`

**Interfaces:**
- Consumes: C1 的 `timestampDiffUnitToQualifier`；既有 `TimestampDiffFunction`。
- Produces: `TIMESTAMPDIFF(unit,t1,t2)` 原样 `TIMESTAMPDIFF(SECOND, t1, t2)` 下推后端求值；C9 + caseI36 转绿。

- [ ] **Step 1: 补单位变体 + NULL 测试（Review Focus #4）**

C9 增：`timestampdiff(MINUTE/HOUR/DAY/MONTH/YEAR,...)` 与 `timestampdiff(SECOND, NULL, b)` 各断言与后端直连一致（NULL→null，不崩）。
Expected: 跑 → 随主格 RED（当前 deparse 关未破）。

- [ ] **Step 2: 诊断重写落点**

定位规划器把 TIMESTAMPDIFF 重写成 `Reinterpret/INT/CAST` 算术的确切规则/convertlet 入口（systematic-debugging），确认让三参原生 SqlCall 直通 SQL 生成、绕开该重写的**最小侵入点**。**禁止**为单函数在 fork 给 Calcite 加侵入式扩展点（既往裁定）——优先在 Mycat 自有算子/unparse 层解决。产出结论进 workspace `c2-pushdown.md`。

- [ ] **Step 3: 实现原生透传**

按 c2-pushdown.md 实现：使 TIMESTAMPDIFF 不被标准算术重写覆盖，`toSqlString` 能反解析出原生 `TIMESTAMPDIFF(<UNIT>, t1, t2)` 交后端；跨片路径若需本地求值则整体下推或补实现（依诊断）。实现体由证据定。

- [ ] **Step 4: 跑 C9 全变体 + caseI36 + hbt 回归**

Run: `mvn -o -pl example test -Dtest=CrudResultMatrixTest#timestampDiffFunctionCell` 然后 `-Dtest=UserCaseTest#caseI36Timestampdiff` 然后 `mvn -o -pl hbt test`
Expected: C9 各单位+NULL PASS；caseI36 绿；hbt 全量零新增红；`TypeRepresentationThreePathConsistencyTest` 仍绿。

- [ ] **Step 5: Commit（本地）**

```bash
git add <C2 生产码文件> example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java
git commit -m "fix(I-DEBT-36): TIMESTAMPDIFF原生三参透传破Reinterpret deparse关,下推后端求值;C9+caseI36转绿"
```

---

## 收口 — 全矩阵背书 + 看板更新

### Task Z: run-matrix ×7 全档 + 文档回写

**Files:**
- Modify: `docs/superpowers/specs/DEBT-QUEUE.md`（I-DEBT-34/36/39 移 Done/Evidenced-Done；仪表盘更新；关闭序 `8→34/35→36→39` 进度）
- Modify: `docs/superpowers/specs/COMPAT-MILESTONE-PLAN.md`（R5 第一半 CRUD 红族转绿状态；connector 网标注另轮）
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`（本轮实证结论）

**Interfaces:**
- Consumes: Root A/B/C 三格转绿。
- Produces: 权威矩阵报告 + 看板一致。

- [ ] **Step 1: 全矩阵实跑**

Run: `pwsh -File .superpowers/sdd/2026-09-30-m1-verification-net/run-matrix.ps1`
Expected: `matrix-report.md` 中 `CrudResultMatrixTest` 10 格 × 7 档全 PASS（三枚原红格 C6/C9/C10 转绿，七枚原绿格零回归）；`FrontAuthMatrixTest`/`ConnectionSmokeTest`/`BackendAuthMatrixTest` 不因本轮回归。若某档 BACKEND_NOT_READY/NO_DATA 如实记，不假绿。

- [ ] **Step 2: 回写 DEBT-QUEUE 仪表盘与关闭序**

以当次矩阵实证把 I-DEBT-34/36/39 标 Done（附 `matrix-report.md` 逐格行）；I-DEBT-35 记"BIT 样板已立，应用留另轮"；connector 网保持另轮挂账。

- [ ] **Step 3: 回写 COMPAT-MILESTONE-PLAN R5 + PROGRESS**

R5 第一半（CRUD 全绿）标 Evidenced-Done；R5 完成（含 connector 网）仍开放。

- [ ] **Step 4: Commit（文档，本地）**

```bash
git add docs/superpowers/specs/DEBT-QUEUE.md docs/superpowers/specs/COMPAT-MILESTONE-PLAN.md docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -m "docs(r5): CRUD红族三根转绿—全矩阵×7档背书+DEBT-QUEUE/里程碑回写(Evidenced-Done)"
```

- [ ] **Step 5: 交最终整分支终审**

按 executing-plans：全部任务绿后跑 whole-branch review（CodeReview subagent，范围 = 本计划起始 commit..HEAD）。
