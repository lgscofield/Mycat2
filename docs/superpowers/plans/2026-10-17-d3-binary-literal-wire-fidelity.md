# D3 Binary 字面量回灌线级保真 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 真 mysqldump 导出的 `_binary '原始字节'` 字面量经 Mycat 回灌不丢字节、不报 1406（I-DEBT-54 三破面之 D3 收口）。

**Architecture:** 三层各自闭环：① 前端 SQL 文本解码改为 MySQL 式可逆解码（非法 UTF-8 字节恒等映射 U+00xx，合法序列行为不变）；② 写路径参数化对带 charset introducer 的 `MySqlCharExpr` 不参数化、输出纯 ASCII `_binary x'HEX'` 模板；③ 原生协议后端 SQL 字节编码显式化（消平台默认编码隐患）。JDBC 后端只见 ASCII，绕开一切连接器编码差异。

**Tech Stack:** JDK21、druid 1.2.28（MySqlCharExpr.getCharset）、vertx 前端 handler、JUnit5 断言。

**Spec:** docs/superpowers/specs/2026-10-17-bit-binary-literal-wire-fidelity-design.md

## Global Constraints

- 构建用 JDK 21（`D:\Program Files\BellSoft\LibericaJDK-21-Full`，显式 JAVA_HOME）；PowerShell 下 `-Dxxx` 参数整体加双引号。
- 零回归红线：合法 UTF-8（含中文）SQL 解码行为与现状逐字符一致；无 introducer 字面量照常参数化；`b'…'`（I-DEBT-44 面）不触碰。
- 后端已实证接受 `_binary x'AA'`（m1-mysql-b 原生 RC=0、hex=AA 保真）。
- 每 Task 收尾 commit；不 push（用户已 push 本轮既有提交，本计划新提交默认不 push，除非用户另行指令）。
- 测试后端 = docker m1-mysql-a(3306)/m1-mysql-b(3307)；Mycat 起停用 fat-jar + 8066 轮询。

## Review Focus

（spec 隐含但任务测试未全覆盖、最可能伤使用者的输入类）
1. **非 ASCII 字节出现在 SELECT 文本透传路径**：解码修复后 U+00xx 码位会流入查询模板文本；JDBC 后端 utf8 再编码使其变 c2xx——写路径（本专轮）已用 hex 化绕开，读/查询比较语义的同类面本专轮不扩，reviewer 检查是否另有写/替换路径（UPDATE/REPLACE）走同一 visitor。
2. **codec decode/encode 对称性在混合序列（合法中文 + 非法字节相邻）下逐字节可逆**——向量测试须覆盖。
3. **COM_STMT_PREPARE 与 COM_QUERY 两接入点一致**（漏一处=半修复假绿）。
4. **`MySQLSqlTextCodec.encode` 对码位 >0xFF 字符产出标准 UTF-8**（中文经 decode 是真实字符，encode 回原字节）。
5. **原生协议后端 `getBytes()` 平台默认编码隐患**（Windows GBK 下曾可能沉默损坏）——Task 4 显式化后须核对其调用面全覆盖。

---

### Task 1: `MySQLSqlTextCodec`（common,新工具类）

**Files:**
- Create: `common/src/main/java/io/mycat/util/MySQLSqlTextCodec.java`
- Test: `common/src/test/java/io/mycat/util/MySQLSqlTextCodecTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `public static String decode(byte[] bytes)` — MySQL 式可逆解码：合法 UTF-8 序列按标准解码；非法字节恒等映射为同码位字符(U+0000–U+00FF)。
  - `public static byte[] encode(String s)` — decode 的精确逆：码位 ≤0xFF 的"字节载体"字符输出单字节；其余标准 UTF-8 编码。（注：对合法解码出的真实 U+00xx 字符（如真 'ª'，原字节 c2aa），encode 产出单字节 aa——与真 MySQL 的 latin1 兜底歧义窗一致，spec §2 已裁定接受。）

- [ ] **Step 1: 写失败测试** `MySQLSqlTextCodecTest`，向量至少含：
  - ASCII 恒等：`decode(b"select 1")=="select 1"`。
  - 合法中文：`decode("中文".getBytes(UTF_8))=="中文"`，且 `encode("中文")==UTF_8 字节`。
  - 非法单字节：`decode(new byte[]{(byte)0xAA})=="ª"`（U+00AA）；`decode(new byte[]{(byte)0xFF})` 为 U+00FF。
  - 混合：`decode(拼接(合法 "中" UTF-8 字节, 0xAA, 0xFF))` == "中ªÿ"；`encode` 后逐字节还原（"中" 回到 e4b8ad，0xAA/0xFF 单字节原值）。
  - 截断序列：`new byte[]{(byte)0xE4,(byte)0xB8}`（半个"中"）→ 两个 U+00xx 载体字符 → encode 逐字节还原。
  - round-trip 性质：对随机字节数组（含全部 0x00–0xFF）`encode(decode(b))` 与 b 逐字节相等（0x00 NUL 也须保：decode 出 \u0000，encode 回 0x00）。
- [ ] **Step 2: 跑测试确认失败**（类不存在，编译失败即 RED）。Run: `mvn -B test -pl common "-Dtest=MySQLSqlTextCodecTest"` Expected: BUILD FAILURE（找不到符号）。
- [ ] **Step 3: 实现** `decode`：手写 UTF-8 序列识别（2/3/4 字节前导 + 延续字节校验，非法即 emit `(char)(b & 0xFF)` 前进 1 字节；合法序列按标准解码），`encode`：逐字符，`c<=0xFF` 输出单字节，否则把该字符所属码位的标准 UTF-8 字节写出。禁 replace 语义泄漏。
- [ ] **Step 4: 跑测试确认通过** Expected: `Tests run: ≥6, Failures: 0, Errors: 0`。
- [ ] **Step 5: Commit** `git add common/src/main/java/io/mycat/util/MySQLSqlTextCodec.java common/src/test/java/io/mycat/util/MySQLSqlTextCodecTest.java && git commit -m "feat(common): MySQLSqlTextCodec——SQL 文本可逆解码(非法字节 latin 兜底)"`

### Task 2: 前端接入 codec（server）

**Files:**
- Modify: `server/src/main/java/io/mycat/mycatmysql/MycatVertxMySQLHandler.java`（COM_QUERY L92；并核查 COM_STMT_PREPARE 的 SQL 文本解码点同改）

**Interfaces:**
- Consumes: `MySQLSqlTextCodec.decode(byte[])`（Task 1）
- Produces: 会话内 `String sql` 对非法 UTF-8 字节以 U+00xx 载体保真（下游 Task 3 依赖此语义）。

- [ ] **Step 1: COM_QUERY** `String sql = new String(readView.readEOFStringBytes(), StandardCharsets.UTF_8);` → `String sql = MySQLSqlTextCodec.decode(readView.readEOFStringBytes());`
- [ ] **Step 2: 排查同文件其余 SQL 文本解码点**（COM_STMT_PREPARE 等 `readEOFString/readString` 取 SQL 的），凡"整条 SQL 文本"均换 codec.decode；schema 名/表名等非 SQL 体不动。列出改动点清单于 commit body。
- [ ] **Step 3: 编译** Run: `mvn -B install -pl server -am "-Dmaven.test.skip=true"` Expected: BUILD SUCCESS。
- [ ] **Step 4: Commit** `git commit -am "fix(server): I-DEBT-54/D3 前端 SQL 文本改用可逆解码,非法 UTF-8 字节不再坍缩 U+FFFD"`

### Task 3: 写路径 binary introducer 不参数化 + hex 化（hbt）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/executor/MycatPreparedStatementUtil.java`（匿名 visitor，参照 L174 SQLIntervalExpr 同构写法）
- Test: `hbt/src/test/java/io/mycat/calcite/BinaryIntroducerParameterizeTest.java`

**Interfaces:**
- Consumes: `MySqlCharExpr.getCharset()`（druid 1.2.28）、`MySQLSqlTextCodec.encode(String)`。
- Produces: `preParse("insert … values(2,_binary '<0xAA>','aa')")` 的 parameterizedSQL 含 `_binary x'AA'`，该字面量不进 params；普通字面量仍参数化。

- [ ] **Step 1: 写失败测试**（用 `DrdsRunnerHelper.preParse`）：
  - `insert into t values (2,_binary '<0xAA 载体字符 ª>','aa')` → `getParameterizedSQL()` 匹配 `values \(2, _binary x'AA', \?\)`（或等价含 `_binary x'AA'`）；`getParams()` 恰为 `[2, "aa"]` 语义（ª 不在 params）。
  - 中文 `_binary '中'` → `_binary x'E4B8AD'`（encode 走标准 utf8）。
  - 无 introducer 的 `'aa'` 仍被参数化为 `?`（回归红线）。
  - `b'10101010'`（I-DEBT-44）输出行为不变（断言其仍在模板文本原样）。
- [ ] **Step 2: 跑测试确认失败** Run: `mvn -B test -pl hbt "-Dtest=BinaryIntroducerParameterizeTest"` Expected: FAIL（当前 `_binary` 字面量被折叠为 `?`+String 参数）。
- [ ] **Step 3: 实现**：匿名 visitor 增补 `@Override public boolean visit(MySqlCharExpr x)`：当 `x.getCharset()!=null`：`StringBuilder appender`（druid `this.appender`）直接 `append(x.getCharset()).append(" x'").append(uppercaseHex(MySQLSqlTextCodec.encode(x.getText()))).append("'")`，`return false`（不参数化、不走默认输出）。charset 形态归一：以 `_` 开头小写直拼，否则输出 `charset + " x'..'"`。NUL/引号无需转义（hex 全 ASCII）。
- [ ] **Step 4: 跑测试确认通过** Expected: 4 用例全绿。
- [ ] **Step 5: hbt 全量回归** Run: `mvn -B test -pl hbt` Expected: 197+4=201 全绿（原 I-DEBT-44 相关测试不回归）。
- [ ] **Step 6: Commit** `git commit -am "fix(hbt): I-DEBT-54/D3 写路径 charset-introducer 字面量不参数化并输出纯 ASCII hex 形态"`

### Task 4: 原生协议后端显式编码 + 金标准 e2e + 全量回归 + 台账

**Files:**
- Modify: `server/src/main/java/io/mycat/vertxmycat/RowSetQuery.java:123`（`curSql.getBytes()` → `MySQLSqlTextCodec.encode(curSql)`；同文件/同调用面其余 `getBytes()` 无参 SQL 文本发送处一并核查）
- Create: `.superpowers/m3-dumprt/d3-e2e-evidence.txt`
- Modify: `docs/superpowers/specs/DEBT-QUEUE.md`（I-DEBT-54 行 + M3 尺子行）

- [ ] **Step 1: 改 RowSetQuery 编码**（显式 codec,消平台默认 GBK 隐患）。Run: `mvn -B install -pl server -am "-Dmaven.test.skip=true"` Expected: SUCCESS。
- [ ] **Step 2: 金标准 e2e**：起 fat-jar（persona MYSQL_8_0,8066）→ 复用现存 `dbdump3.tbit`（bit(8) 判别值 0x00/0xAA/0xFF/0x01/NULL）→ 真 mysqldump 默认口径导出（容器内文件方式防 PowerShell 字节伪影,同 `d3_export_raw.sql` 方法）→ **drop 后经 Mycat 回灌同文件** Expected: RC=0、无 1406 → Mycat 读回 `select id,bin(flag),note` Expected: 逐值 = ground truth（0 / 10101010 / 11111111 / 1 / NULL）→ 回灌原生 m1-mysql-b Expected: 逐值一致。general_log 抽查下发 SQL：含 `_binary x'AA'` 纯 ASCII。
- [ ] **Step 3: 写 evidence** `d3-e2e-evidence.txt`（环境、命令、RC、逐值、general_log 片段、与 D1/D2 金标准合并判词）。
- [ ] **Step 4: 全量回归** Run: `mvn -B test -pl hbt` + compat 矩阵（`mvn -B test -pl example "-Dtest=io.mycat.compat.*Test"`，服务在跑）Expected: 全绿（skip 数不高于基线 3）。
- [ ] **Step 5: DEBT-QUEUE 回写**：I-DEBT-54 行尾"D3"改已修 + D3b 挂账（SELECT 透传路径同类面 + 用户直发 hex 字面量 druid 解析不支持）；M3 尺子行升 🟢（真二进制金标准全闭合）；仪表盘 M3 完成度更新。
- [ ] **Step 6: Commit** evidence + DEBT-QUEUE：`git commit -m "docs(I-DEBT-54): D3 金标准收口——真 mysqldump bit(8) 回灌 Mycat RC=0 逐值保真"`；RowSetQuery 改动并入 Step 6 前单独 commit `fix(server): RowSetQuery SQL 文本显式 UTF-8 系编码,消平台默认编码隐患`。
- [ ] **Step 7: 停 Mycat 测试床、drop dbdump3 复原环境。**
