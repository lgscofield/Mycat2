# I-DEBT-69·残 NATIVE SELECT 结果集 EOF warnings 腿（专轮设计 Spec）

- 日期：2026-11-03
- 债务：I-DEBT-69 告警子系统在 **NATIVE datasource（`-Dserver=native` / `FORCE_NATIVE_DATASOURCE`）** 下 **SELECT 结果集腿** 的告警生产缺失——账本 DEBT-QUEUE 明记「仍挂：NATIVE SELECT 腿结果集 EOF warnings（`QueryCommand:114`）未接」。
- 对齐北极星：客户端眼中无缝替代 MySQL/MariaDB。跨语言驱动与 mysql CLI 肉眼依赖「warning-producing SELECT 后 `@@warning_count`>0 且 `SHOW WARNINGS` 有明细」。JDBC 后端腿与 DML 腿已收口（见 §2 对照），NATIVE 直通腿仍整体失踪 → 破面。
- 状态：**✅ 已收口**（架构级专轮 · Spec 先行 → 逐刀 TDD → 全量护栏 → 活体 L1 差分 + 负向对拍坐实归因）。详见 §9 落地收口记录。

---

## 1. 症状（当前代码实测口径）

NATIVE datasource 下一条**自身产告警的 SELECT**（例：`SELECT CAST('abc' AS SIGNED)` 触 1292、全扫描里的隐式截断等）执行后：

- 前端 `SELECT @@warning_count` → **0**（真 MySQL 后端返 ≥1）。
- 前端 `SHOW WARNINGS` → **空**（真库有 Level/Code/Message 明细行）。

即 NATIVE 腿的结果集告警**整体失踪**——不是计数错，是从后端结果集 EOF/OK 终止包到会话诊断区（DA）这一整条生产链**根本没接**。

---

## 2. 机制取证（当前代码 file:line，非记录推断）

### 2.1 断点一：`QueryCommand` 丢弃终止包 warnings
`server/src/main/java/io/mycat/mysqlclient/command/QueryCommand.java:109-121`（HANDLING_ROW_DATA_OR_END_PACKET 终包分支）：
```java
} else if (first == EOF_PACKET_HEADER && payload.length() < 0xFFFFFF) {
    serverstatus = PacketUtil.decodeOkPacketPayload(payload).serverStatusFlags();  // L114
    close = true;
    onEnd();
    emitter.onComplete();
}
```
仅取 `serverStatusFlags`，终止包的 warnings 字段**从未读取**（`decodeOkPacketPayload` 本身也不返 warnings）。类无 `warningCount` 出口。

### 2.2 断点二：NATIVE 池 collector 从不 `onWarnings`
`server/src/main/java/io/mycat/mysqlclient/VertxMycatConnectionPool.java:120-149`（`prepareQuery(sql, params, collector)`，即 Calcite/合并路径经 `VertxExecuter.runQuery` 调进来的 NATIVE 实现）：
```java
Observable<Object[]> query = connection.query(sql, objectArrayDecoder);   // L136 → QueryCommand
query.subscribe(objects -> collector.onRow(objects),
        throwable -> { promise.tryFail(throwable); collector.onError(throwable); },
        () -> { onRev(); promise.tryComplete(); collector.onComplete(); });  // L142-146
```
回调了 `onColumnDef/onRow/onError/onComplete`，**唯独从不回调 `collector.onWarnings(...)`**。

### 2.3 sink 链早已就位（缺的只是 NATIVE 生产者）
- 接口位：`common/src/main/java/io/mycat/newquery/MysqlCollector.java:20` `default void onWarnings(List<WarningMessage>) {}`（注释：「必须在 `onComplete()` 之前、Statement/连接关闭前取走」）。
- 消费端：`hbt/src/main/java/io/mycat/vertx/VertxExecuter.java:921-923` 与 `:1025-1027` `onWarnings → warningSink.accept(warnings)`；
- 落 DA：`hbt/src/main/java/io/mycat/AsyncMycatDataContextImpl.java:378-380` `warnings -> warnings.forEach(dataContext::addWarning)`。

**∴ NATIVE 腿只要把 `onWarnings` 喂上，下游 DA/`@@warning_count`/`SHOW WARNINGS` 读面自动复用既有闭环。**

### 2.4 对照：两条已收口的腿（镜像对象）
- **JDBC 后端腿**（默认 datasource，非 `-Dserver=native`）：`common/src/main/java/io/mycat/newquery/NewMycatConnectionImpl.java:135` 与 `:159` `collector.onWarnings(toWarningMessages(statement.getWarnings()))`——走 JDBC `Statement.getWarnings()` 原生明细，已绿。
- **DML/OK 腿（Blade G）**：`server/src/main/java/io/mycat/mysqlclient/VertxConnection.java:70-117`（`update`）——OK 包 `setBackendWarningCount(decodeOkPacketWarnings(payload))`（`OkCommand.java:67`），**仅 warnings>0** 时在同一串行 `future` 队列接力 `queryAsObjectArray("SHOW WARNINGS")` 取**真明细**（L107-113），连接归还前 `caller.tryComplete`，明细经 `convertNativeWarningRows`（L125-145）入 SqlResult。**B 即镜像此范式到 SELECT 流。**

### 2.5 终止包字节布局（承重 · 须实测坐实）
`common/src/main/java/io/mycat/MySQLPacketUtil.java:152-158` `generateEof` = `0xFE + warningCount(fixint 2 LE) + status(fixint 2 LE)`（**经典 EOF 形**，5 字节，warnings 在 status 之前）。
后端 SELECT 终止包有两种形，取决于 `config.isClientDeprecateEof()`（`QueryCommand.java:99` 已据此走状态机）：
- **DEPRECATE_EOF = ON**（全目标 persona：8.0/8.4/9.x + MariaDB 10.6/10.11/11.4，见 `MySQLDatabaseMetadata.isClientDeprecateEof()` 与 `MySQLDatabaseMetadataTest.deprecateEofCapabilityIsUnchangedByTheFix`）：终止包为 **OK 形** `0xFE + affected_rows(lenenc) + last_insert_id(lenenc) + status(fixint 2) + warnings(fixint 2)`——warnings 在 status **之后**（同 `decodeOkPacketWarnings`）。
- **DEPRECATE_EOF = OFF**（< 5.7.5 旧版）：**经典 EOF 形** `0xFE + warnings(fixint 2) + status(fixint 2)`——warnings 在 status **之前**。

⚠ **取证门（已坐实·非记忆）**：本会话代码路径 + MySQL 官方协议双证钉死布局——Mycat **对后端握手主动置 `CLIENT_DEPRECATE_EOF`**：`server/.../mysqlclient/command/ConnectHandler.java:106-108` `if (config.isClientDeprecateEof()) capabilitiesFlags |= CLIENT_DEPRECATE_EOF;`，而 `config.clientDeprecateEof` 经 `MycatMySQLManagerImpl.java:130`←`MycatCore.java:122 computeClientDeprecateEof()`（persona 驱动）对全目标档（8.0/8.4/9.x + MariaDB 10.6/10.11/11.4 + 5.7.5+）为 **true**。既协商该位，后端必按 DEPRECATE_EOF 语义发**结果集终止包 = OK-body 形（header 0xFE）**：warnings 在 status **之后**。∴ `deprecateEof=true` 分支可直接复用现成 `PacketUtil.decodeOkPacketWarnings(payload)`（skip1+lenenc+lenenc+`readFixInt(2)`status+`readFixInt(2)`warnings，残长退化 0）。经典 EOF 形仅 `deprecateEof=false`（<5.7.5）时出现，离线协议锁单验。**活体 L1 差分对拍仍作端到端最终复核**——若布局判错，L1 直 RED（验行为强于验形态）。

---

## 3. 修法（镜像 Blade G · 逐刀）

> 总原则与 `update()` 同构：**零告警零扰动**（warnings==0 不发起任何 SHOW WARNINGS 往返），**取不到明细如实空**（往返失败降级、绝不按计数合成假条目——承重约束见 warning-subsystem spec §2）。

- **刀1（解码出口）**：`QueryCommand` 按 `config.isClientDeprecateEof()` 分支解终止包 warnings，暴露 `getWarningCount()`（新增字段 + getter，与既有 `getServerstatus()` 同式）。`PacketUtil` 加 `decodeEofPacketWarnings(Buffer, boolean deprecateEof)`（ON→跳 header+lenenc+lenenc+fixint2(status)+`readFixInt(2)`；OFF→跳 header+`readFixInt(2)`；残长不足退化 0，绝不反抛）。
- **刀2（连接层透传）**：`VertxConnection.query(sql, decoder)` 在 QueryCommand `onEnd`→promise 完成时，像 L200 捕获 `serverstatus` 一样把 `warningCount` 交回调用侧（新增回调参数或 per-query holder；避免共享可变字段跨语句串扰——以 holder/回调为准，非连接级全局）。
- **刀3（池腿接力 + 喂 sink）**：`VertxMycatConnectionPool.prepareQuery(…, collector)`（L120-149）在主 SELECT 流 `onComplete` 前，若该 query 的 `warningCount>0`，在同一 `queryCloseFuture` 串行链上接力 `connection.queryAsObjectArray("SHOW WARNINGS")` → `VertxConnection.convertNativeWarningRows(rows)` → `collector.onWarnings(details)`，**再** `collector.onComplete()`；warnings==0 直接 `onComplete`。连接归还（`onRev()`）必须在接力之后。

### 3.4 边界（划界防蔓延）
- **raw Buffer 直通腿**（`VertxMycatConnectionPool.java:185-205` / `VertxConnection.query(String)`→`ResponseBufferCommand`，同后端连接逐字节转发）：**不动**——客户端紧随的 `SHOW WARNINGS` 天然落同一条后端连接、由后端自答，无跨连接丢失问题。B 只修 collector 型池腿（跨连接归还场景）。
- **旧版经典 EOF 分支**：刀1 已按 `deprecateEof` 双面解码覆盖；活体验证目标 persona 全为 ON，OFF 形仅离线协议锁验。

---

## 4. 锁计划（RED→GREEN）

| 锁 | 类型 | 内容 | 判据 |
|---|---|---|---|
| N1 | 离线协议脚本（镜像 `VertxConnectionWarningDetailTest`） | 假后端：握手→SELECT 列定义+行+**终止包 warnings=2**→`SHOW WARNINGS` 三列两行 → 断言 `collector.onWarnings` 恰一次、两真明细、且往返序列 = `[SELECT…, "SHOW WARNINGS"]` | RED：当前 `prepareQuery` 从不 onWarnings |
| N2 | 离线协议脚本 | 零告警：终止包 warnings=0 → **不得**发起任何 SHOW WARNINGS 往返，主路径零扰动 | 防多余往返 |
| N3 | 离线协议脚本 | 接力降级：SHOW WARNINGS 回 ERR 包 → `onComplete` 仍达、明细如实空、不合成 | 承重复绿禁令 |
| N4 | 离线解码锁 | `decodeEofPacketWarnings` 双形（ON OK形 / OFF 经典形）字节对拍，残长退化 0 | 钉 §2.5 |
| L1 | 活体差分对拍 | `-Dserver=native` + persona=MARIADB_10_11 + 产告警 SELECT：经 Mycat vs 直连真库 oracle，断言 `@@warning_count`>0 且 `SHOW WARNINGS` 码集合含目标码（复用 `CrossShardWarningRelayTest` 读回型判据风格） | benign=两端一致 |
| G | 全量护栏 | `mvn -o -pl example -am test`（不带 compat.db） | 零新增红，基线 549/0F/0E/37skip 口径 |

- 活体 infra：先按 Docker 恢复流程确保 m1-mysql-a/b(3306/3307) 在跑，`restart-i69.ps1` 载**重打的 fat jar** 起 8066（`-Dserver=native` 杠杆须加进启动参数），settle 8-12s 防 boot-race。
- 改生产后活体**必须重打 fat jar**（铁律）。

---

## 5. Blast 与回归面

- **命中范围**：仅 NATIVE datasource（`-Dserver=native` opt-in）的 collector 型 SELECT 腿。**默认 JDBC datasource 零涉及**（其经 `NewMycatConnectionImpl:135/159` 已独立工作）。
- **热路径**：NATIVE 池 SELECT。刀3 接力在 warnings==0 时**短路**（同 `update()` L103），主路径零额外往返；warnings>0 时多一次同连接 SHOW WARNINGS（语义必需，非回归）。
- **fork 关系**：纯 server/hbt/common 产品码，**不触 calcite-core fork**，非大 blast fork 改动。
- **状态机风险**：不改结果流出序（rows 仍逐行 `onNext`），仅在**流结束、连接归还前**插一条串行后续命令——与 `update()` 已验证的链式接力同构，不改 EOF 到前端的转发。

---

## 6. 残留 / 挂账（宁挂账不伪绿）

- **本地求值告警（G5）**：跨片在 Mycat 本地算子产出的告警（非后端 EOF 携带）不属本腿，warning-subsystem spec §4 G5 另计。
- **`SHOW WARNINGS` LIMIT/`COUNT(*)` 精确截断**：属 G2 读面，已在本架构下游，若 NATIVE 腿接力后读面有偏差另立。
- **max_error_count 截断**：本腿原样透传后端明细，超 `@@max_error_count` 的截断行为以真库活体对拍为准，偏差留账。

---

## 7. 账本对齐（收口后）

- DEBT-QUEUE I-DEBT-69 行：划掉「仍挂：NATIVE SELECT 腿结果集 EOF warnings（`QueryCommand:114`）未接」。
- PROGRESS 表：追加 I-DEBT-69 NATIVE SELECT 腿收口行。
- SDD progress.md：本专轮收口节。
- 本 spec 状态 🔴 立案 → ✅ 收口。
- commit 不 push（铁律）。

---

## 8. 北极星指针

关掉它 = NATIVE datasource 的 SELECT 告警面与 JDBC datasource 拉平，客户端眼中无缝替代在「告警子系统」维度对 `-Dserver=native` 形态补齐最后一块生产腿。

---

## 9. 落地收口记录（2026-11-03）

**三刀（均 MYCAT CHANGE 注释钉档，纯 server 产品码，不触 calcite-core fork）**：
1. `PacketUtil.decodeEofPacketWarnings(Buffer, boolean)`（刀1a）：双形解终止包 warnings（deprecateEof=true→OK 形 affected/insertId/status/**warnings**；false→经典形 0xFE/**warnings**/status），残长退化 0、绝不反抛。
2. `QueryCommand.warningCount` + 终止包分支解码（刀1b）；`VertxConnection.query(sql,decoder)` 委派新增 `query(sql,decoder,IntConsumer)` 重载，于 `onEnd` 交回 per-query 计数（SHOW WARNINGS 接力自身走单出口重载防递归）（刀2）。
3. `VertxMycatConnectionPool.prepareQuery(sql,params,collector)`：warnings>0 时于同一串行 `queryCloseFuture` 队列、连接归还前接力 `queryAsObjectArray("SHOW WARNINGS")`→`convertNativeWarningRows`→`collector.onWarnings`（明细先于 `onComplete`），往返失败降级如实空、绝不合成（刀3，镜像 `update()` Blade G）。

**离线锁**：`NativeSelectWarningRelayTest` N1-N4 = **4/0/0**（先 RED：N1/N3 `Tests run:3, Failures:2` 证 warnings 丢弃 → GREEN）；Blade G `VertxConnectionWarningDetailTest` 3/0/0、`NativeWarningsConverterTest` 5/0/0 无回归。

**全量护栏**：`mvn -o -pl server,example -am test`（完整基线环境：m1-mysql-a/b@3306/3307 + m6-mariadb-a@3308 全开）→ server 段全绿；example `Tests run: 550, Failures: 0, Errors: 5, Skipped: 37`。**5E 全部落在 `XaBackendConnectionTest.before()` 的 JUnit `@Timeout(30s)`**（`readXARecoveryLog` hint 超时，紧接 `ProxyBackendConnectionTest` 503s 之后的冷启/资源争用）。**负向归因实证**：还原我 4 文件至 HEAD pristine + 移出本轮测试后单跑 `XaBackendConnectionTest` = **17 tests / 0F / 4E**，与带我改动单跑 4E 逐格同 ⇒ **既有环境 flake，非我回归**（且全部 example 测试走默认 JDBC datasource，root/example/server pom 均无 `-Dserver=native`，我的 NATIVE 三刀在此模式下休眠）。**零新增红成立**。

**活体 L1 + 负向对照（决定性归因）**：
- native 8066（`-Dserver=native`·persona=MARIADB_10_11·后端 m1-mysql-a@3306 MySQL 8.0）载**我的修复 fat jar**：`CrossShardWarningRelayTest` = **2/0/0**（`@@warning_count`=2 与 oracle 逐格一致、`SHOW WARNINGS` 含 1292）。
- 同环境载 **pristine jar**（无我三刀）：同测 = **2 tests, Failures 2**（`oracle=2 mycat=0`、`SHOW WARNINGS` 空 `[]`）。
- 二者唯一差量 = 我的三刀 ⇒ 坐实该活体确走 `VertxMycatConnectionPool.prepareQuery`（mycat=0 反证 relay 缺席），RED→GREEN 完全归因本改动。
- 证据日志：正向 GREEN 见 `scratch/i69-live-native.log`（重打修复 jar 复跑落盘）、负向对照 RED 见 `scratch/i69-live-native-pristine-NEGCTL.log`、护栏见 `.superpowers/sdd/i69-guard2.log`、Xa 归因见 `.superpowers/sdd/i69-xa-baseline.log`/`i69-xa-alone.log`。

**账本**：DEBT-QUEUE I-DEBT-69「NATIVE SELECT 腿 `QueryCommand:114`」划除；PROGRESS 追加收口行；commit 不 push（铁律）。
