# I-DEBT-70·MariaDB uca1400 物化视图保真（专轮设计 Spec）

- 日期：2026-11-03
- 债务：**I-DEBT-70**（DEBT-QUEUE 唯一开放 🔴 A 层票，§7 第五列 MariaDB 10.11/11.4 两档 ◐ 的唯一成因）。
  Connector/NET（`MySql.Data` 26.10）经 Mycat 连 MariaDB ≥10.11 persona 时 `Open()` 期崩溃；直连真库正常。
- 对齐北极星：客户端眼中无缝替代 MySQL/MariaDB——**同一客户端**经 Mycat 的可见行为应与**该客户端直连同版真库**一致。
- 状态：**✅ 收口（2026-11-03 落地）**——三刀 + 离线锁（定向 20/0/0）+ 全量护栏（server 164/0/0；example
  550/9F/3E/37skip，经**决定性 pristine 对照**逐格同形 ⇒ 零新增红）+ 活体 L1（dotnet 经 8066 `OPEN OK`/1242/0，
  非 dotnet 全 506/184）+ 负向对照全部落地；落地中的两轮活体纠偏与残留见 §9。

---

## 1. 症状（实测口径）

| 客户端 | 直连 mariadb:10.11.19 | 经 Mycat（persona=MARIADB_10_11） |
|---|---|---|
| mysql CLI / PyMySQL / Go / Connector-J | 506 行、184 个 NULL `Id` → 正常 | 506 行、184 NULL → 正常（逐行零差异） |
| **MySql.Data（dotnet）** | **1242 行、0 NULL → 正常** | **506 行、184 NULL → `Open()` 抛 `Object cannot be cast from DBNull to other types`** |

矩阵红格：`dotnet_plain` / `dotnet_deep` × `MARIADB_10_11` / `MARIADB_11_4` = 4 格（`conn-matrix-report.md`）。
天然对照：`MARIADB_10_6`（无 uca1400）同驱动同格 PASS。

即：**不是 dotnet 太脆，是 Mycat 给它的行集和真库给它的行集不是同一档。**

## 2. 证据链（全部当前实测，非记录推断）

装置与原始产物均在 `.superpowers/sdd/2026-11-03-i-debt-70-uca1400/scratch/`。

- **E1 开关存在且可来回切**：服务端会话变量 `old_mode` 的 `NO_NULL_COLLATION_IDS` 位；消费点 MariaDB `sql/sql_show.cc:6436`。
- **E2/E3 两档渲染实况**：dotnet 直连被种（1242/0）；CLI 不被种（506/184）。
- **E4 MITM 抓包**：`i70-relay`(13308) 抓 dotnet 与 mysql CLI 两条真实 `HandshakeResponse41`，落 `i70-e4-relay-raw.log`（权威字节源，禁手抄）。
- **E4b 装置自检（承重）**：`i70-verify-replica.py` 用手同一 builder **逐字节复现**两条真实包（dotnet 262B、cli 192B 全等）→ `VERDICT: HARNESS GREEN`。
  由此钉死线格式：`[4 caps][4 maxpacket][1 charset][23 zeros][user NUL][auth 1B len+data][db NUL][plugin NUL][attrs lenenc-blob]`
  —— reserved 为 **23** 字节，user/db/plugin 为 **NUL 结尾串**（非 lenenc），仅 auth-response 带 1 字节长度。
- **E5 判定特征二分矩阵**（6 变体，`i70-e5-bisect.log`）：唯一触发 = 连接属性 **`_client_name == "mysql-connector-net"`**。
  `dotnetcaps_cliattrs`=不种 / `clicaps_dotnetname`=种 → capability 字、charset 字节、maxpacket、是否带 db **全部无关**（双向交叉验证）；
  `dotnetname_renamed`（改名）、`dotnetcaps_noattrs`（撤属性位）均不种。
- **E6 取数可行性门（本票关键）**：**未被种位**的普通连接（`_client_name=libmysql`）显式
  `SET old_mode=CONCAT_WS(',',NULLIF(@@old_mode,''),'NO_NULL_COLLATION_IDS')` 后
  `SHOW COLLATION` = **1242 行 / 0 NULL**，与 connector-net 被种的档**完全一致**。
  ⇒ Mycat 无需伪装客户端身份即可从真库取到物化档快照（此路此前未试：旧票只排除过"后端池能力位/会话字符集"）。
  **副作用实测**：裸 `SET old_mode='NO_NULL_COLLATION_IDS'` 会把既有 `UTF8_IS_UTF8MB3` **冲掉**；加法式两者共存 ⇒ 必须用加法式并复原。
- **E7 两档行级结构**（`i70-e7-rows.py`）：模板档 506 行含 **184 行 `Collation` 以 `uca1400_` 开头、`Charset`/`Id` 均 NULL**；
  物化档 1242 行 = 506 − 184 + 184×5，展开字符集恰为 `utf8mb3/ucs2/utf8mb4/utf16/utf32` 各 184 行，`Id` 区间 **2048…3271**；
  MariaDB 10.11 `SHOW COLLATION` 表头为 **6 列**（`Collation,Charset,Id,Default,Compiled,Sortlen`，**无** `Pad_attribute`）。
- **E8 谓词形状**（7 变体）：真库匹配式为**大小写敏感、全等**——`MYSQL-CONNECTOR-NET`、`MySQL Connector Net 8.0.30`、
  `mysql-connector-net 8.0.30`、`xmysql-connector-net`、空串、无该属性 **一律不种**。
  ⇒ 旧版 Connector/NET 直连真库同样拿模板档并同样崩；Mycat 若同样不种即**行为一致**，非缺陷。

## 3. 机制与缝合点（当前代码 file:line）

- 服务面：`server/src/main/java/io/mycat/sqlhandler/dql/ShowCollationSQLHandler.java:44-63`——先借 prototype 数据源试跑，
  成功即 `response.proxySelect(...)` **透传真库**（L54-56）；失败才回落 `information_schema.Collations` 仿真表（L62-63）。
  后端池固定 Connector-J，其 `_client_name` 非 `mysql-connector-net` ⇒ 真库按 E5/E8 不种位 ⇒ 透传回来的永远是**模板档**。
- 能力位装配：`config/src/main/java/io/mycat/config/VersionProfileFactory.java:40-48`（`capabilityMask` 唯一出口）→
  `server/src/main/java/io/mycat/vertx/VertxMycatServer.java:151,168` 传入握手；
  `config/src/main/java/io/mycat/config/MySQLServerCapabilityFlags.java:201` 的 `CLIENT_CONNECT_ATTRS` **被注释** ⇒
  Mycat 前端**从不宣告**该位 ⇒ 客户端从不下发属性 ⇒ Mycat 无从知道对面是谁。常量位本身在 `:117`。
- 属性解析能力已在库：`common/src/main/java/io/mycat/beans/mysql/packet/AuthPacket.java:132-147`（读）/`:176-185`（写）。
- 前端消费点：Vert.x 活路 `server/src/main/java/io/mycat/vertx/VertxMySQLAuthHandler.java:157-158` 解析 `AuthPacket`、
  `:291-296` 落会话态（`setServerCapabilities/setCharsetIndex`），**唯独不取 attrs**；
  NIO 路 `proxy/src/main/java/io/mycat/proxy/handler/front/MySQLClientAuthHandler.java:124` 取了 `attrs` **局部丢弃**。
- 会话态存放：`common/src/main/java/io/mycat/MycatDataContext.java:106-108` 邻近（能力位同区），新增 attrs 读写口。
- 合成结果集惯用法：`ResultSetBuilder.create().addColumnInfo(...).addObjectRowPayload(...)` + `response.sendResultSet(...)`
  （先例 `server/src/main/java/io/mycat/sqlhandler/dql/HintHandler.java:136-139`）。
- NATIVE 侧参照（非本票必改）：`server/src/main/java/io/mycat/mysqlclient/command/ConnectHandler.java:98-106` 已有 `needAttrs` 位。

## 4. 修法裁定

| 方向 | 裁定 | 理由 |
|---|---|---|
| ① 维持透传，§7 两档记 ◐ | **否决** | A 层红格 4 格不消；且 E6 已证取数可行，"做不到"不再成立 |
| ② **身份门控 + 真库自渲染物化快照** | **采纳** | 判定式即真库判定式（E5/E8），取数即真库自渲染（E6/E7），**零自造数据**；四语言现状档（模板）不动 |
| ③ persona 无条件物化（凡 MariaDB ≥10.11 都返 1242） | **否决** | 会让 CLI/PyMySQL/Go/Connector-J 经 Mycat 看到 1242 而其直连只看到 506 ⇒ **为修一格引入四格新失真**，违反"同客户端直连对拍"口径 |
| ④ 自造 2048+ 展开行 / ⑤ 删 NULL 行 / 换精选表 | **维持否决** | 无文档逆推 = 伪绿；删/换打红 `MetadataGuiMatrixTest.d2Utf8mb4CollationsSupersetOfBackend` 超集断言 |
| ⑥ 把前端 `_client_name` 灌进后端池连接以"骗"真库种位 | **否决** | 池连接跨会话共享，按会话身份改写池行为 = 架构级污染；且 E6 已提供无需欺骗的取数路 |

## 5. 落地切分（三刀 + 每刀锁）

**刀 1·前端宣告并留存连接属性**
- `VersionProfileFactory.of`：`capabilityMask |= CLIENT_CONNECT_ATTRS`（真 MySQL 5.6+/MariaDB 均宣告，属**更**保真）；
- `MycatDataContext` 增 `getClientConnectAttrs()/setClientConnectAttrs(Map<String,String>)`（接口给 `default` 空表实现，避免波及其他实现类），
  `MycatDataContextImpl` 落字段；`VertxMySQLAuthHandler.completeAuthentication`（`:291-296` 区）与 `MySQLClientAuthHandler`（`:124`）各补一行写入。
- 锁：`VersionProfileTest` 全 persona 能力位含该位（并更新既有等式断言）；`AuthPacket` attrs 解析往返锁；
  会话态 attrs 写入锁（含"未宣告/未下发 ⇒ 空表，行为同日"）。

**刀 2·物化档判定与取数（纯函数 + 可 mock 的取数器）**
- 新增 `io.mycat.sqlhandler.dql.CollationMaterialization`：
  - `boolean shouldMaterialize(Map<String,String> attrs, ServerPersona persona)`＝ `"mysql-connector-net".equals(attrs.get("_client_name"))`（E8 全等、区分大小写）
    `&& persona ∈ {MARIADB_10_11, MARIADB_11_4}`；
  - 取数序（同一借出连接内，`java.sql.Connection` 可 mock）：
    `SELECT @@old_mode` 存前值 → `SET old_mode=CONCAT_WS(',',NULLIF(@@old_mode,''),'NO_NULL_COLLATION_IDS')` →
    执行原 `SHOW COLLATION` 文本 → **finally 复原** `SET old_mode=<前值>`；
- 锁：假 `Connection`/`Statement`/`ResultSet` 记录调用序，断言四步语句文本与复原必达（含中途抛异常仍复原）；
  谓词锁：E5/E8 全变体（大小写、带后缀、前缀、空、缺属性、非 dotnet persona）逐一红/绿。

**刀 3·`ShowCollationSQLHandler` 分档渲染**
- 透传分支前插入身份门：`shouldMaterialize` 为真 → 刀 2 取数 → 按后端 `ResultSetMetaData` **原列原序**（6 列、`Id` 整型）经
  `ResultSetBuilder`+`sendResultSet` 回前端；取数任一步失败 → **回落到今日透传路径**（绝不比现状更差）。
- 锁：离线以 mock 断言"dotnet 身份 → 走物化取数、其他身份 → 仍 `proxySelect`"；persona 非 MariaDB → 一律不启用。

## 6. 影响面与风险

- **握手能力位变化**（刀 1）：所有 persona 的 Handshake Packet caps 多 `1<<20`。真实客户端据此追加 attrs 段，
  `AuthPacket.readPayload` 已能解析（E4b 逐字节复现背书）。风险 = 客户端发送的 attrs 段格式异常 ⇒ 解析失败即回退空表（不崩）。
- **池会话态污染**（刀 2）：`old_mode` 是物理会话态，必须 finally 复原；取数用**借出即还**的 prototype 连接，
  失败路径也复原。护栏另加一条：物化取数后同池再取连接查 `@@old_mode` 不含本位。
- **无 attrs 客户端**（旧驱动/自研客户端）：维持现状（模板档，dotnet 旧版仍崩——与真库直连行为一致，见 E8）。
- **正交不改**：`i_s.COLLATIONS` 仿真表、`SHOW CHARACTER SET` 表头锁、版本串解析（同票旁证项已闭）均不动。

## 7. 测试计划

1. **RED**：刀 1/2/3 各自的锁先跑红（身份不识别 / 取数序缺复原 / handler 不分档）。
2. **GREEN**：定向模块套 `config` + `server` + `hbt` + `common`。
3. **全量护栏**：`server` 全量 + `example` 全量（基线口径同 I-DEBT-69 轮），要求**零新增红**；
   `MetadataGuiMatrixTest` D2/D3/D4 与 `PersonaCollationsTest` 必全绿（超集/自洽/表头/装箱四锁不受身份门影响）。
4. **活体 L1**：`-Dmycat.persona=MARIADB_10_11` + 后端 mariadb:10.11（3308）重启 8066，跑既有 dotnet 探针
   （`.superpowers/sdd/2026-10-30-connector-persona-matrix/scratch/dotnetrepro`）经 8066 →
   期望 `OPEN OK` + `showCollation rows=1242 nullId=0`；同档 CLI/PyMySQL/Go/Connector-J 复跑仍 PASS 且 506 行（不被新档污染）。
5. **负向对拍**：pristine jar 同测必回 `Object cannot be cast from DBNull to other types` ⇒ 归因坐实。

## 8. 已知残留（据实挂账，不伪装）

- Mycat 前端 `SELECT @@old_mode` 读面仍未实现（本票不扩面）⇒ 经 Mycat 查该变量与真库不一致，属会话变量覆盖度债，另议。
- 物化档 `Default` 列在 10.11 实况全为 `Yes`（E7 采样）——本方案透传真库自身渲染值，不做二次判断。
- 探针/装置仅落 `.superpowers/sdd/**/scratch/`（一次性，不入生产代码）。

## 9. 落地收口记录（2026-11-03）

**改动清单**：生产 7（`config/VersionProfileFactory`、`common/MycatDataContext`、`proxy/MycatDataContextImpl`、
`proxy/handler/front/MySQLClientAuthHandler`、`server/vertx/VertxMySQLAuthHandler`、
`server/sqlhandler/dql/CollationMaterialization`〔新〕、`server/sqlhandler/dql/ShowCollationSQLHandler`）
+ 测试 4（`config/VersionProfileTest` 加能力位格；`server/.../dql/CollationMaterializationTest`〔新〕10、
`ShowCollationMaterializationDispatchTest`〔新〕10、`FakeBackendJdbc`〔新〕假驱动）。

**设计中未预见、须靠活体才暴露的两处纠偏**（铁律「改生产必重打 jar 再验」兑现的真捕）：
1. **后端门看错了字段**：§5 刀 2 原定"后端门"若采信 `DatabaseMetaData.getDatabaseProductName()` ⇒ 活体恒不物化。
   `scratch/JdbcProductProbe.java` 实测 Connector/J 9.3.0 连 mariadb:10.11：**谎报** `productName=[MySQL]`、
   `major/minor=5/5`，唯 `getDatabaseProductVersion()=[5.5.5-10.11.19-MariaDB-ubu2204]` 如实。
   ⇒ 门改为只看版本串并复用既有 `MySQLDatabaseMetadata.parse()`（`LEGACY_MARIADB_PREFIX` 跳过 + `contains("MariaDB")`），
   判 `major>10 || (major==10 && minor>=11)`；删自造 `parseMajorMinor`。该事实同步写进假件默认形，
   使离线锁本可提前抓住此错。
2. **wire 列名必须取 label**：物化生效后 dotnet 仍崩，但错型变为
   `IndexOutOfRangeException: Could not find specified column in results: charset`。
   裸协议 A/B（`scratch/i70-raw-client-v3.py`，greeting 改标准 HandshakePacket41 偏移以兼容 Mycat caps）钉死：
   直连 3308 上线 `name=Collation/Charset/Id/…` + `org_name=COLLATION_NAME/…`；
   Mycat 合成腿上线的却是 **`COLLATION_NAME/CHARACTER_SET_NAME/ID/IS_DEFAULT/IS_COMPILED/SORTLEN`**。
   根因链：Connector/J 把 `ResultSetMetaData.getColumnName()` 定义为**物理列名**（显示名在 `getColumnLabel()`），
   而 Mycat 重建轨 `common/.../ColumnDefPacketImpl:80-81` 把 `RowMetaData.getColumnName(idx)` 逐字写进 wire `name` 位
   （且 `columnOrgName` 置空）。⇒ 修法 = `Snapshot.toBuilder()` 的 `wireName` 取 label，label 空才退 name。
   **锁侧教训**：假件原把两个名合成同值 ⇒ 离线锁对这类错**天生失明**；本轮按驱动实况拆名并加"上线名锁"
   （断 `snapshot.toBuilder().build().getMetaData().getColumnName(i)` 必须等于 6 个显示名）。

**TDD 与负向对照**：RED = config 1 红（能力位）+ server 9 红 / 2 误（身份不识别、四步序缺复原、handler 不分档），
四条"维持透传"锁天然绿 → GREEN 定向 **20/0/0**。负向对照：临时回退 `wireName → spec.columnName` ⇒ 上线名锁变红，
实得值与活体裸协议**逐字一致**（`lock-server-negctl.log`）。

**活体 L1**（persona=MARIADB_10_11 · `mycat:createDataSource` hint 重绑 prototypeDs→mariadb:10.11@3308 · jar 20:02:39）：
- 真 `MySql.Data` 26.10 经 8066（`scratch/i70-dotnet-probe.ps1`）= `OPEN OK` + `showCollation rows=1242 nullId=0`。
- 非污染（§7-4）：pymysql / `cli_full` / `name_upper` / `name_title_spaced` / `name_suffixed` / `dotnetcaps_noattrs`
  经 8066 全 = 506 行 / 184 NULL，列 def 包逐字透传；真 Connector/J 经 8066（`scratch/JdbcViaMycatProbe.java`）
  = `cols=6 rows=506 nullId=184`，与直连 3308 同形 ⇒ 门不误判 Java 驱动。
- §6 挂的池污染护栏：交替序列 `dotnet→cli→dotnet→name_upper→cli` = 1242/506/1242/506/506 ⇒ 同池透传腿不漏出本位。

**全量护栏与归因**：`mvn -o -B -pl server,example -am test` = server **164/0/0**；example **550/9F/3E/37skip**。
9F/3E 经**决定性 pristine 对照**（`git stash push -u` 全部改动 + 重打 pristine jar + **从 pristine 源码重编**跑同 6 类）
证明与本刀**逐格同形** ⇒ 零新增红。**⚠ 方法论**：`AssembleTest`/`UserCaseTest`/`SqlFunctionTest` 系进程内跑引擎
（`MycatTest.execute:105`），只换外部 8066 的 jar **不构成有效对照**；persona 换档（MYSQL_8_0）亦不改红名红数。

**§7-5 负向对拍的满足方式（诚实标注）**：由"活体 RED 基线（修复前 dotnet 经 8066 = `InvalidCastException`，
直连 3308 却 1242/0 OK）+ 离线锁负向对照（回退列名源 ⇒ 锁红且值逐字同）"两钉代偿；未再重复
"pristine jar + dotnet 崩溃"活体往返（该 pristine 产物已被修复 jar 覆盖，往返成本高于增量信息）。

**残留**（承 §8，不翻本票 ✅）：前端 `SELECT @@old_mode` 读面未实现（会话变量覆盖度债·另议）；
无 attrs 的旧 dotnet 版仍崩（与真库直连一致·E8）；MARIADB_11_4 档未做活体往返（同门同路，仅版本串不同，
离线谓词锁覆盖 11.4 判定，据实记为"未活体复跑"而非"已验")。
