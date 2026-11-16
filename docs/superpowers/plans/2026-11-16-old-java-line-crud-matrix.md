# 旧 Java Connector 线 full-CRUD 矩阵 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (inline 执行，用户已指定). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在旧 Java Connector 线（5.1.49 + 8.0.33）探针中复刻 `CrudResultMatrixTest` 的 CRUD 结果保真矩阵，兑现 M2"全 connector 跑基本 CRUD"最后一格。

**Architecture:** 纯测试仪器专轮——在 `ci/OldConnectorProbe.java`（JDK 单文件源码模式）增量新增 `STAGE6 crudmatrix`，每格 `report("cm.<cell>", ok, detail)` 纳入既有 verdict；复用 `ci/verify-old-connector.sh` 的 coord×{明文,TLS} 单 jar 隔离接线与 CI `old-connector` job。不改产品码、不进 reactor。

**Tech Stack:** JDK 21（Liberica Full）单源文件 `java -cp <connector.jar> OldConnectorProbe.java`；Connector/J 5.1.49（`com.mysql.jdbc.Driver`）与 8.0.33（`com.mysql.cj.jdbc.Driver`）；Mycat fat jar；docker 后端 m1-mysql-a（mysql:8.0, persona MYSQL_8_0）。

**Spec:** `docs/superpowers/specs/2026-11-16-old-java-line-crud-matrix-design.md`（决定点已拍板：全 11 格复刻 / 老线硬编码 ground truth / 单 persona MYSQL_8_0 / prepared 折进 C5 / 继承跨片 BIT 边界）。模板：`example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java`。

## Global Constraints

- **不改产品码**：本票只加测试格。若某格对当前 jar 红 → 真破面，据实 FAIL 纳入 verdict（宁挂账不伪绿），另立产品票，本票不放宽掩盖。
- **判别力非空**：每格须先证"能红"（临时写错期望常量→该 `STAGE cm.<cell>` 转 FAIL→改回）再留绿；照搬 C9 跨月跨年选数教训，选使断言真具鉴别力的数据。
- **硬编码 GT 取自现代网 stdout，不得臆造**：C11/C12 的期望值先跑一次现代网 `CrudResultMatrixTest`，读其 `assertAvgEqualsOracle`/`assertAggFidelity` 无条件打印的 `=> mycat=X oracle=Y` 实际值，原样固化为老线常量（persona MYSQL_8_0）。
- **隔离**：旧 jar 绝不与主 connector 同 classpath（类遮蔽）；只经 `java -cp <单 jar>` 源码模式跑。
- **persona**：一律 `MYSQL_8_0`（后端 m1-mysql-a mysql:8.0 已起）。
- **环境**：`JAVA_HOME=D:/Program Files/BellSoft/LibericaJDK-21-Full`；本地 mvn `-o` 离线；jar = `server/target/server-1.22-release-jar-with-dependencies.jar`（若不存在先 `mvn -o -B install -Dmaven.test.skip=true -pl server -am`）。
- **CI 向后兼容**：`verify-old-connector.sh` 明文/TLS 两轮 + 端口预检 + NoSuchMethod/NoClassDefFound 影子断裂守卫不得回退。

## Review Focus

（spec 隐含、但格子断言未必穷尽、最易伤"用旧 connector 的人"的输入类；每行由对应 Task 的格子钉死）

1. **5.1.x vs 8.x 结果表征差异**：同一 SQL，旧/新驱动对 BIT/DECIMAL/DATETIME 的 `getObject` 返回类型可能不同（byte[] vs Number、LocalDateTime vs Timestamp）。合理预期：值保真、不因驱动代际失真。→ Task 2（BIT）、Task 1（DECIMAL/VARBINARY）、Task 3（datetime 经聚合归并）钉。
2. **跨分片归并值在旧客户端一致**：Mycat 跨 2 分库归并后 AVG/STDDEV/count 必须与后端单算一致。→ Task 3 钉。
3. **除零/未知列错误语义透传到旧线**：旧客户端不应因语句级错误被 2013 打挂会话。→ Task 4 钉。
4. **prepared-write 字节保真（5.1 服务端 prep）**：`setBytes`/`?` 写入的 0xFE/0xFF 等高位字节读回逐字节相等。→ Task 1 C5 钉。
5. **BIT 跨片列头边界不误纳**：本票只钉单片 BIT，跨片归并 BIT 仍属"类型表征统一"专轮，不得为凑绿强推。→ Task 2 显式划界。

---

## 格子覆盖映射（对齐现代网 C1–C12，兑现"全 11 格复刻"）

| 现代网格 | 老线归属 | 由谁提供 |
|----------|----------|----------|
| C1 int+varchar | STAGE4 基本 CRUD | 已有（不重复，DRY） |
| C3 decimal 标度 | STAGE5 `checkDec` | 已有（DECIMAL(20,10) SELECT 保真） |
| C4 DATE/DATETIME | STAGE5 `checkObj` | 已有（date/timestamp 值对象 equals） |
| **C2 UPDATE/DELETE** | **STAGE6 cm.c2** | Task 1（STAGE4/5 仅 INSERT+SELECT，UD 全缺） |
| **C5 VARBINARY** | **STAGE6 cm.c5** | Task 1 |
| **C6 BIT 单片** | **STAGE6 cm.c6** | Task 2 |
| **C7 跨片聚合** | **STAGE6 cm.c7** | Task 3 |
| **C8 跨片 LIMIT/OFFSET** | **STAGE6 cm.c8** | Task 3 |
| **C9 TIMESTAMPDIFF** | **STAGE6 cm.c9** | Task 4 |
| **C10 除零/会话存活** | **STAGE6 cm.c10** | Task 4 |
| **C11 跨片 AVG** | **STAGE6 cm.c11** | Task 3 |
| **C12 跨片 STDDEV/VAR** | **STAGE6 cm.c12** | Task 3 |

STAGE6 新增 9 格，合已有 STAGE4/5 的 C1/C3/C4，即现代网 12 格全覆盖（"全 11 格复刻"=除基线 C1 外全 11 格达标，其中 3 格已由 STAGE4/5 提供、6 格本票补齐至含 C2 共 9 新格）。执行时勿把 C3/C4 再建一次（DRY）。

---

### Task 1: STAGE6 脚手架 + C2 UPDATE/DELETE + C5 VARBINARY（prepared 写入）

**Files:**
- Modify: `ci/OldConnectorProbe.java`（在 STAGE5 段之后、`statement` catch 之前插入 STAGE6；新增私有 helper `cmCell`/`cmCount`）
- Test: 探针自身即测试（无独立单测文件）；判别力靠"临时错常量→FAIL"

**Interfaces:**
- Produces: STAGE6 脚手架——独立库 `dboldcm_<ts>`（建表走 Mycat 正道：`CREATE DATABASE`→`USE`→`CREATE TABLE ... dbpartition by mod_hash(\`id\`) dbpartitions 2`），helper `report("cm.<tag>", ok, detail)` 纳入既有 `pass` 累积、`finally` 内 `DROP DATABASE`；后续 Task 复用同一 `st` 连接与 helper。
- Consumes: 现有 STAGE1-5 连接 `st`（`connection.createStatement()`）。

- [ ] **Step 1: 加 STAGE6 脚手架骨架（空跑）** — 建 `dboldcm`，定义 `cmCell(tag, cols)` 返回限定表名（内部先 `drop if exists` 再建分片表），末尾 `finally` drop 库；不塞格逻辑。
- [ ] **Step 2: 加 C2 `updateAndDelete`** — 表 `c2(id bigint pk, c_var varchar(64))`；插 `(1,'a'),(2,'b')`；`update ... set c_var='z' where id=1`；读回断言 id=1 → "z"；`delete ... where id=1`；`select count(*)` 断言 =1。`report("cm.c2", ok, detail)`。**GT**：值 "z" 与计数 1（照抄现代网 C2）。
- [ ] **Step 3: 加 C5 `varbinary`（prepared 写入路径）** — 表 `c5(id bigint pk, c_bin varbinary(64))`；用 `PreparedStatement.setBytes` 绑 payload `{0,1,2,(byte)0xFE,(byte)0xFF,'A','B'}` 插 id=1；`getObject`/`getBytes` 读回逐字节比对（归一 byte[]，参照探针既有 `checkBit` 的 byte[]/Number 兜底思路）。`report("cm.c5", ok, detail)`。
- [ ] **Step 4: 判别力自证** — 临时把 C2 期望改 "Z"、C5 payload 末字节改 `'X'` → 跑：`OLD_CONNECTOR_COORDS="mysql:mysql-connector-java:5.1.49" bash ci/verify-old-connector.sh`（需 server jar 就绪，脚本自起）。**Expected**：`STAGE cm.c2 FAIL`、`STAGE cm.c5 FAIL`、`verdict=FAIL`、退出非 0。
- [ ] **Step 5: 改回正确 GT，两 coord 全绿** — 跑 `bash ci/verify-old-connector.sh`（缺省 5.1.49+8.0.33 明文轴）。**Expected**：两线 `STAGE cm.c2 OK` `STAGE cm.c5 OK` `OLDLINE RESULT ... verdict=PASS`。
- [ ] **Step 6: Commit** — `git add ci/OldConnectorProbe.java && git commit -m "test(I-DEBT-9-旧线矩阵): STAGE6 脚手架 + C2 UPDATE/DELETE + C5 VARBINARY prepared 保真"`

---

### Task 2: C6 BIT 单片列头保真（继承跨片 BIT 边界）

**Files:**
- Modify: `ci/OldConnectorProbe.java`（STAGE6 内追加 C6，复用 Task 1 的 `cmCell`/`report`）

**Interfaces:**
- Consumes: Task 1 `cmCell(tag,cols)`、`report("cm.<tag>",ok,detail)`。

- [ ] **Step 1: 加 C6 `bitSingleShard`** — 表 `c6(id bigint pk, c_bit1 bit(1), c_bit8 bit(8), c_bit16 bit(16), c_bitnull bit(8))`；`insert ... values (1, b'1', b'10110011', b'1000000000000001', NULL)`；**单片命中** `where id=1`（列头来自后端）。断言：bit1→Boolean true；bit8→byte[]{0xB3}；bit16→{0x80,0x01}；bitnull→null。归一 byte[] 用探针既有 `checkBit` 思路（大端最小无符号剔前导 0x00/0xFF）。`report("cm.c6", ok, detail)`。
- [ ] **Step 2: 显式注释边界** — 在 C6 上方一行注明：跨片归并 BIT 列头（CalciteRowMetaData 归数值、行值仍 byte[]）属"类型表征统一"专轮（`UserCaseTest#caseCrossShardBitColumnFidelity`），本格**只测单片**、不重开。
- [ ] **Step 3: 判别力自证** — 临时把 bit16 期望末字节改 `0x02` → 跑（5.1.49）→ **Expected** `STAGE cm.c6 FAIL`。改回。
- [ ] **Step 4: 两 coord 全绿** — 跑 `bash ci/verify-old-connector.sh`。**Expected** 两线 `STAGE cm.c6 OK` verdict=PASS（若某 coord 因驱动表征差异红，按 Global Constraints 据实挂账、另立票，不放宽掩盖）。
- [ ] **Step 5: Commit** — `git commit -am "test(I-DEBT-9-旧线矩阵): C6 BIT 单片列头保真(继承跨片边界)"`

---

### Task 3: 跨分片归并族 C7/C8/C11/C12（硬编码 GT）

**Files:**
- Modify: `ci/OldConnectorProbe.java`（STAGE6 追加四格）

**Interfaces:**
- Consumes: Task 1 `cmCell`/`report`。
- Produces: 常量 `AVG_INT_GT`、`AVG_DEC_GT`、`STDDEV_GT`、`VAR_GT`（String/BigDecimal，来自现代网 stdout 固化）。

- [ ] **Step 1: 采集权威 GT** — 现代网跑一次：`mvn -o -pl example -am test -Dtest=CrudResultMatrixTest#crossShardAvgFidelity+crossShardStddevVarianceFidelity`，读 stdout `C11：... => mycat=X oracle=Y` 与 `C12 ... => ...`，把 MYSQL_8_0 persona 下两侧一致的实际值原样填为本格常量（若现代网红则先据实现值确认，不臆造）。
- [ ] **Step 2: 加 C7 `crossShardAggregate`** — 表 `c7(id bigint pk, val int)`；插 id 1..6 val=10..60（步10）；断言 `count(*)`=6、`sum(val)`=210、`select val order by val desc` 首行=60。`report("cm.c7", ...)`。
- [ ] **Step 3: 加 C8 `crossShardLimitOffset`** — 表 `c8(id pk, val int)`；插 id1..6 val=i；`select val order by val limit 2 offset 1` 断言 2 行、首行=2。`report("cm.c8", ...)`。
- [ ] **Step 4: 加 C11 `avgFidelity`** — 表 `c11(id pk, i int, d decimal(20,4))`；插现代网同款行集（i=1,2,3,4,5,7；d 对应 1.0000..7.0000）；`select avg(i)` 读回 BigDecimal，`compareTo(AVG_INT_GT)`==0；`select avg(d)` `compareTo(AVG_DEC_GT)`==0。`report("cm.c11", ...)`。
- [ ] **Step 5: 加 C12 `stddevVarianceFidelity`** — 表 `c12(id pk, i int)`；同款行集；`select stddev(i)`、`select variance(i)` 按**相对容差 1e-5**（`Math.abs(o)>?`：`1e-5*max(1,|GT|)`）比对 `STDDEV_GT`/`VAR_GT`。`report("cm.c12", ...)`。
- [ ] **Step 6: 判别力自证** — 临时把 C7 sum 期望改 211、C11 GT 末位改一、C12 GT 改样本口径值 → 跑 → **Expected** 对应 `STAGE cm.c7/c11/c12 FAIL`。改回。
- [ ] **Step 7: 两 coord 全绿** — `bash ci/verify-old-connector.sh` → 两线 `cm.c7/c8/c11/c12 OK` verdict=PASS。
- [ ] **Step 8: Commit** — `git commit -am "test(I-DEBT-9-旧线矩阵): C7/C8 跨片聚合分页 + C11/C12 归并 AVG/STDDEV 硬编码 GT 保真"`

---

### Task 4: C9 TIMESTAMPDIFF 下推 + C10 除零/未知列错误契约

**Files:**
- Modify: `ci/OldConnectorProbe.java`（STAGE6 追加两格）

**Interfaces:**
- Consumes: Task 1 `cmCell`/`report`。

- [ ] **Step 1: 加 C9 `timestampDiff`** — 表 `c9(id pk, a datetime, b datetime)`；插 `(1,'2021-03-01 00:00:00','2022-02-28 00:00:00')`（**跨月跨年不等间隔**，单位真具鉴别力）；断言 `timestampdiff(SECOND|MINUTE|HOUR|DAY|MONTH|YEAR, a, b)` = 31449600/524160/8736/364/11/0；NULL 传播：`timestampdiff(SECOND, NULL, b)` 读回 null 且会话不崩。`report("cm.c9", ...)`。
- [ ] **Step 2: 加 C10 `divideZeroAndSessionSurvival`** — **独立连接**（`DriverManager.getConnection` 同 URL，非类共享 `st`，干净起点）：① `select 1/0 as d` 断言单行且值 null（对齐 MySQL SELECT 期语义）；② 随后 `select 1` 断言会话存活可继续；③ `select \`no_such_col_zzz\` from (select 1) t` 断言抛 SQLException（旧线用 try/catch 判 SQLException 真即 ok）；④ 再 `select 2` 断言错误后会话仍活。`report("cm.c10", ...)`；用完关闭该连接。
- [ ] **Step 3: 判别力自证** — 临时把 C9 MONTH 期望改 12、C10 ①期望值改非 null → 跑 → **Expected** `cm.c9/c10 FAIL`。改回。
- [ ] **Step 4: 两 coord 全绿** — `bash ci/verify-old-connector.sh` → 两线 `cm.c9 OK cm.c10 OK` verdict=PASS。
- [ ] **Step 5: Commit** — `git commit -am "test(I-DEBT-9-旧线矩阵): C9 TIMESTAMPDIFF 下推 + C10 除零/未知列会话存活契约"`

---

### Task 5: TLS 轴矩阵执行确认 + 负控判别力 + 看板回写（M2 闭环）

**Files:**
- Modify: `docs/superpowers/specs/DEBT-QUEUE.md`、`docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`
- Test: `ci/verify-old-connector.sh`（不改逻辑，只确认 STAGE6 在 TLS 轮被跑到）

**Interfaces:**
- Consumes: Task 1-4 全部 STAGE6 格。

- [ ] **Step 1: TLS×矩阵全跑** — `bash ci/verify-old-connector.sh`（脚本明文后自动重启 REQUIRED 跑 TLS 轴）。**Expected**：5.1.49 与 8.0.33 两线、明文与 TLS 两轴，`STAGE cm.c2/c5/c6/c7/c8/c9/c10/c11/c12 OK` 齐全，`verdict=PASS`。若脚本某段跳过 TLS（`OLDLINE_SKIP_TLS=1`），显式不带该变量重跑。
- [ ] **Step 2: 影子断裂守卫** — 确认脚本尾部 `NoSuchMethodError|NoClassDefFoundError` 守卫未因新格触发（STAGE6 全用标准 JDBC 接口，5.1/8.x 皆备）。
- [ ] **Step 3: CI 复现路径核对** — 确认 `pr-validation-main-test.yml` 的 `old-connector` job 调 `verify-old-connector.sh`，新 STAGE6 随 push 自动进 CI（无需改 workflow）。据实记录"锁口径 CI⏳（待 push 后 run）"。
- [ ] **Step 4: 全量本地护栏复跑（B 层仪器）** — 因未动产品码，重打 jar 非必需；但为背书，跑 `pwsh -NoProfile -File ci/run-example-guard.ps1 -Persona MYSQL_8_0 > target/ci/guard-<date>-oldline-matrix.log 2>&1`（用 Monitor persistent），读日志 `RESULT=PASS`/`GUARD_EXIT=0`。
- [ ] **Step 5: 回写看板** — DEBT-QUEUE I-DEBT-9 行"仍挂账"改为"旧 Java 线 full-CRUD 矩阵已收口（STAGE6 9 格×2 coord×{plain,TLS} 本地全绿，CI⏳待 push 升格）"；PROGRESS 最后更新补 M2 连接达标闭环。本地✅/CI⏳ 双态口径。
- [ ] **Step 6: Commit（不 push，待拍板）** — `git add ci/OldConnectorProbe.java docs/... && git commit -m "test(I-DEBT-9): 旧线 full-CRUD 矩阵收口 + 看板 M2 闭环回写(未 push 待拍板)"`；汇报未 push、请用户拍板 push 以出 CI 徽标。

---

## 完成后

Task 5 Step 6 后进入 fresh-context whole-branch review（`executing-plans` 收尾门）：`../subagent-driven-development/scripts/review-package` 切 `git merge-base main HEAD`..HEAD；按效果 re-grade，Critical/Important 一轮修复（各修复 RED→GREEN + 全绿），Minor 记账。CI 徽标待用户拍板 push 后回写 CI✅。
