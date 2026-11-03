## I-DEBT-80 取证设计：XA 残留 5E 到底是「后端容量」还是「连接借出不还」

- 状态：🟡 Spec 先行，取证协议待执行（2026-11-04 立）
- 票：`docs/superpowers/specs/DEBT-QUEUE.md` §2 I-DEBT-80
- 前置事实来源：同轮 I-DEBT-79 刀① 干净护栏两轮全量（`scratch/guard-clean.log`、
  `scratch/guard-durable-full2.log`）＋ `target/ci-guard/proxy-MYSQL_8_0.log` 逐行统计
  ＋ 后端容器 `m1-mysql-a` 只读查询。账本：`.superpowers/sdd/2026-11-04-i-debt-35-probe/progress.md` §6.2
- 铁律约束：本轮**先取证、后定性**。在拿到 §3 的取样数据之前，任何人（包括我）不得把该 5E
  写死成「环境/容量族」或「产品泄漏」。看板旧记（`I-DEBT-7C`、`I-DEBT-29-EXT` 护栏注）为待复核对象，不是依据。

### 0. 目标

一次性把 `io.mycat.connection.XaBackendConnectionTest` 残留 5E 的**因果层**定位清楚，
并给出「谁该改、改什么」的可执行结论；同时判定它是否构成 I-DEBT-71/72 之后的**第三枚 XA 真缺陷**。

### 1. 已坐实的事实（当前代码 file:line ＋ 实测输出）

| # | 事实 | 出处 |
|---|---|---|
| F1 | 5E 中 4 枚抛在 `@Before`，语句是 `/*+ mycat:readXARecoveryLog{} */`，栈顶 `XaBackendConnectionTest.before(...):21`；仅 `testGlobalNoTranscationSelect` 抛在测试体 `repeatSql` | `example/target/surefire-reports/io.mycat.connection.XaBackendConnectionTest.txt`（两轮逐字同一组 5 个方法） |
| F2 | `init` 是**实例字段**，JUnit4 每方法新建实例 ⇒ `@Before` 每条方法都发一次 recovery hint（作者意图显然是整类一次） | `example/src/test/java/io/mycat/connection/XaBackendConnectionTest.java:15` |
| F3 | 父/子两类都挂 JUnit5 `@Disabled`，而测试方法是 JUnit4 `@Test` ⇒ vintage 不识别，实际照跑 17 条 | `XaBackendConnectionTest.java:13`、`DefaultBackendConnectionTest.java:20` |
| F4 | 每条测试跑 400 次 `SELECT ... LIMIT 0,1000`，并大量发 `resetConfig` / `createDataSource` / `createCluster` hint | `DefaultBackendConnectionTest.java:480-484`、`:266-279`、`MycatTest.java`（`RESET_CONFIG`） |
| F5 | 签名不是池满：`active 49→51→54` ≪ `maxActive 1000`，`createErrorCount 7→13→20→27→33` 单调爬 ⇒ 失败发生在**建物理连接** | surefire 报告两轮；默认值 `config/src/main/java/io/mycat/config/DatasourceConfig.java:24`（maxCon=1000）、`:27`（maxConnectTimeout=30*1000，对应 `wait millis 3000x`）、`:41`（removeAbandoned=false） |
| F6 | `readXARecoveryLog()` 对**每个可写数据源各借一条**连接并串行执行；连接在 `finally` 里关闭 ⇒ 该路径本身不泄漏 | `server/src/main/java/io/mycat/commands/MycatMySQLManagerImpl.java:161-179`、`va/src/main/java/cn/mycat/vertx/xa/impl/XaLogImpl.java:137-202` |
| F7 | 同名 datasource 重建即 `close()` 旧池；`JdbcDataSource.close()` 对「仍有 borrowed」只记日志，**不强制收回已借出的物理连接** | `datasource/src/main/java/io/mycat/datasource/jdbc/datasource/JdbcConnectionManager.java:93-99`、`JdbcDataSource.java:117-134` |
| F8 | 一轮全量内 druid 池 `INITED=407 / CLOSED=406`（关池纪律在），但 `close ,but pool count is N` 出现 6 次，其中两条正落在 XA 窗口：`ds0 ... is 52`、`ds1 ... is 44` | `target/ci-guard/proxy-MYSQL_8_0.log` 逐行统计（脚本 `scratch/i80-poolchurn.ps1`） |
| F9 | 后端容量与拒绝计数：`max_connections=151`、`Max_used_connections=152`、`Connection_errors_max_connections=331`、`Aborted_clients=152`、`wait_timeout=28800`、`xa_detach_on_prepare=ON`、`innodb_trx` 当时 0 行 | 容器 `m1-mysql-a` 只读查询（脚本 `scratch/i80-backend-probe{,2,3}.ps1`） |
| F10 | 运行中 `172.17.0.1`（宿主 Mycat 池）会话数峰值 119，稍后回落 89、58 ⇒ 需求冲高后回落，非单向永久占用 | 同上，三次采样 |
| F11 | **H-B 的原始表述被证伪**：前端会话关闭路径 `BaseXaSqlConnection.close(consumer)` 在 `inTranscation` 时**先强制 rollback、再逐个 `consumer.apply` 关闭 `map` 里全部连接**并 `map.clear()` ⇒ 该路径设计上会归还，不是「不还」的缝位 | `va/src/main/java/cn/mycat/vertx/xa/impl/BaseXaSqlConnection.java:413-432` |
| F12 | 归还动作挂在**上一条语句的 future** 上：`NewMycatConnectionImpl.close()` = `future.transform(… JdbcUtils.close(connection) …)`。future 若不完成，归还就永不发生，且**无超时、无兜底** ⇒ 这才是「借出不还」的机制候选（记 H-B′） | `common/src/main/java/io/mycat/newquery/NewMycatConnectionImpl.java:643-651`、`:638` |
| F13 | 借出计数是**同时刻真实值**：`getConnection` 借 +1（L151）、`closeConnection` 还 −1（L185）；池关时 `counter>0` 即打印 `pool count is N` 并 dump `showConnections` ⇒ 格 B 的 `ds0 39 / ds1 48 / prototypeDs 65` 是「当时确实有 N 条未还」，不是累计值 | `datasource/.../jdbc/datasource/JdbcConnectionManager.java:140-207`、`JdbcDataSource.java:117-134` |
| F14 | 仓内自带一台**被默认关掉的定位仪器**：`JdbcDataSource.close()` 仅当 `removeAbandoned=true` 才打印 druid `getActiveConnectionStackTrace()`（即借出点堆栈）；而 `DatasourceConfig` 默认 `removeAbandoned=false`（实测 `prototypeDs.datasource.json` 原文 `removeAbandoned: false, logAbandoned: true`）⇒ 可开格 E 取借出栈，无需改产品码 | `JdbcDataSource.java:121-129`、`config/.../DatasourceConfig.java:41-43`、`server/target/.mycat-home/datasources/prototypeDs.datasource.json` |
| F15 | 非事务分支里存在**重复借出累加点**：`getConnection(target)` 若 `map` 已含该 target，则再借一条塞进 `extraConnections`；只有 `clearConnections()`（由 `closeStatementState()` 驱动）会排空 ⇒ 语句结束钩子一旦缺席，每执行一条语句就多挂一条 | `BaseXaSqlConnection.java:103-111`、`:444-455`、`:478-498` |
| F16 | 该套件**单线程**：父类全文无 `Executor`/`new Thread`/`CompletableFuture`/`parallel` ⇒ 观察到的 65 条同时借出不能用「并发」解释 | `example/src/test/java/io/mycat/connection/DefaultBackendConnectionTest.java`（相关正则 0 命中）、`:480-484` |
| F17 | F3 的**机制根因**坐实：根 pom 在全局 `<dependencies>` 里同时注入 `junit:junit:4.13.2` 与 `junit-vintage-engine`，注释写明「补 vintage，让 JUnit 4 兼容层的测试真正被执行」⇒ jupiter `@Disabled` 挂在 JUnit4 类上就是**空注解**，父＋两子三个类照跑 | `pom.xml:210-225` |
| F18 | **格 E 的主仪器落空（如实记 NO_DATA）**：`removeAbandoned=true / timeout 20s` 全程 **0 次真实回收**。日志里 1629 条 `abandon` 命中中 1305 条是配置转储，另 2 条是 druid 启动告警「removeAbandoned is true, not use in production」，`abandon connection` 文案 **0 命中** ⇒ 「借出后长期不还」不是这批连接的形态；原因是这些 borrowed 所属的池已被 `close()`，druid 的 DestroyTask 随池停止，abandon 任务再也不会检视它们 | `cellE-proxy.log` grep 计数；`JdbcDataSource.java:117-134` |
| F19 | **格 E 意外拿到正确的那台仪器**：`removeAbandoned=true` 使 `JdbcDataSource.close:128` 打印 druid `getActiveConnections()` 的**借用点栈**。关池瞬间共转储 **84 个栈**，**84/84 全部**经 `LocalXaSqlConnection.getConnection:145` ＋ `SavepointSqlConnection.getConnection:133`；`BaseXaSqlConnection.getConnection` **0 命中**。业务帧自下而上：`IOExecutor$1.executeBlocking:72 → MycatVertxMySQLHandler.handleQuery:531 → MycatdbCommand → ShardingSQLHandler.onExecute:56 → ReceiverImpl.proxySelect:91 → ProxyReceiverImpl.execute:38 → ReceiverImpl.execute:205 → MycatXaTranscation.getConnection:81` | `cellE-proxy.log`（03:56:13 ds0=37／ds1=47；03:56:49 prototypeDs=127）|
| F20 | **累加点与排空腿的确切形态**：`LocalXaSqlConnection.getConnection` 的**非事务（autocommit）分支**无条件 `extraConnections.add(connection)`（`:145-148`），即 autocommit 下每执行一条语句就多占一条物理连接；唯一排空点 `BaseXaSqlConnection.clearConnections()`（`:478-498`）① 在 `closeStatementState()` 里被 `future.onComplete(event -> clearConnections().onComplete(…))` 调用，**返回的 Future 被丢弃**（fire-and-forget，无人等待其完成）；② 关闭是**串行 `compose` 链**（`:481-483`），任一条 `close()` 不完成即队头阻塞其后全部；③ `extraConnections.clear()` 挂在该链的 `onComplete`（`:484`），链不走完列表只增不减 | `va/src/main/java/cn/mycat/vertx/xa/impl/LocalXaSqlConnection.java:145-148`、`BaseXaSqlConnection.java:445-455`、`:478-498` |
| F21 | **守卫的盲区**：`LocalXaSqlConnection.closeStatementState()`（`:195-241`）对 `localSqlConnection` 与 `map` 各写了不变式检查并 `LOGGER.error`，**唯独从不检查 `extraConnections`**。格 E 实测这四条检查文案全部 **0 命中**（`localSqlConnection is not closed,not in transaction`／`is closed,not in transaction`／`xa connection map is not empty`／`localSqlConnection is null but existed transcation connections`）⇒ 事务态与 `map` 一直是干净的，唯一在脏的是没有守卫的那一格 | `LocalXaSqlConnection.java:198-224`；`cellE-proxy.log` 计数 |
| F22 | 后端会话的**归属排他**：测试侧 17 处 `getMySQLConnection(...)` **全部**传 `DB_MYCAT`（无一处直连 3306）⇒ `information_schema.processlist` 里 `172.17.0.1` 的来源只可能是 Mycat，「冲高可能是测试 JVM 直连造成」的对冲解释被排除 | `DefaultBackendConnectionTest.java` 全量 `getMySQLConnection(` 分组计数 = 17×`DB_MYCAT` |
| F23 | **红不是确定性的**：格 E 在 `max_connections=151`（未抬容量）下仍 `17/0F/0E` 全绿，但同轮 `Connection_errors_max_connections` 差分 =6（446→452）、关池 borrowed 三条照打（37/47/127）、`GetConnectionTimeout` 0 命中。与格 B（同码同容量、3 红）对照 ⇒ 结论是「借出峰值与 151 天花板赛跑」，单轮绿/红都不足以定性，必须看计数 | `cellE-guard.txt`、`cellE/sample-backend.csv`、`cellE-proxy.log` |

三个 ds（`prototypeDs`/`ds0`/`ds1`）全部指向**同一后端** `localhost:3306`
（`DB1 = jdbc:mysql://localhost:3306/mysql?...`，`MycatTest.java` 常量），故三池共享 151 槽位。

### 2. 候选定性与各自的可证伪预测

| 代号 | 定性 | 若为真，隔离取样必须看到 |
|---|---|---|
| H-A | **纯后端容量**：151 太小，套件的瞬时并发需求本就 > 151，与 XA 无关 | 单跑 `DefaultBackendConnectionTest`（非 XA 同 17 条）也出现同等 `Threads_connected` 峰值；XA 与非 XA 的 `USE_CON` 时序无系统性差异 |
| H-B | **XA 路径借出不还（第三枚真缺陷）**：`transaction_policy='xa'` 下每条查询/每个分支借用后未归还，`USE_CON` 随测试序号单调上升且**关池也收不回**。2026-11-04 补：原始表述（会话关闭路径不还）已被 F11 证伪，收缩为 **H-B′**＝归还挂在未完成 statement future 上（F12）＋ 非事务分支重复借出（F15） | 同一测试内 `USE_CON` 单调升；测试结束（前端连接 close）后不回落；`JdbcDataSource.close ,but pool count` 在 XA 类里逐条变大 |
| H-C | **测试前置缺失（仪器债）**：`@Before` 每条方法都发 recovery hint + `resetConfig` 重建池的时机撞上 borrowed 峰值，属套件写法而非产品 | 单条方法隔离跑必**全绿**；把 hint 改成整类一次（或 `maxWait` 提高）后整类跑不再复现，而产品码路径无任何异常 |

三者不互斥：容量可能是压垮点，同时泄漏是抬升点。取证要给出的是**份额**，不是单一标签。

### 3. 取样协议（须干净 8066，用 I-DEBT-79 刀① 资产起床）

仪器改动（唯一必要项）：`ci/run-example-guard.ps1` 增加 `-Test <surefire 过滤器>` 透传
（拼成 `-Dtest=... -DfailIfNoTests=false`），保留 persona 白名单门、JDK 门、姿态门与 `finally` 收尾。

四个格子，顺序执行，每格之间由脚本重启 8066：

1. **格 0 基线采样**：不起测试，只连 8066 跑一次 `/*+ mycat:showDataSources{} */`，记 `NAME/MAX_CON/EXIST_CON/USE_CON`；
   同时记后端 `Threads_connected`、`Connection_errors_max_connections`、`Max_used_connections` 起始值。
2. **格 A 单方法隔离**：`-Test 'XaBackendConnectionTest#testGlobalTranscationSelectCommit'`。
   预期：绿 ⇒ 支持 H-C；仍红 ⇒ 指向 H-A/H-B。
3. **格 B 整类跑（复现格）**：`-Test 'XaBackendConnectionTest'`，测试侧每 20 s 由采样脚本
   （后端直连 + 经 8066 的 hint 两条腿）落一行 CSV：时间戳、`Threads_connected`、
   `Connection_errors_max_connections` 增量、每 ds 的 `USE_CON`。同时抓 `close ,but pool count is` 行。
4. **格 C 非 XA 对照**：`-Test 'ProxyBackendConnectionTest'`（同族、不带 `set transaction_policy='xa'`），
   同采样口径。**这是 H-A 与 H-B 的分界线**：若 C 的峰值显著低于 B 的峰值，容量说不足以独立成立。
5. **格 D 容量抬升对照**（2026-11-04 立本协议时补入，先写判据后跑）：`SET GLOBAL max_connections=500`
   （动态、可回退，跑完必须 `SET GLOBAL max_connections=151` 复原并复核），再跑格 B 同一配方。
   这一格**不是收口手段**，只是把「压垮点」与「抬升点」解耦：
   - 格 D 全绿 **且** 采样里 `USE_CON` 仍单调升、`close ,but pool count is` 仍出现
     ⇒ 容量是必要条件、借出不还是抬升条件 ⇒ H-A + H-B **双成立**，须派生产品码票；
   - 格 D 全绿 **且** `USE_CON` 随测试生命周期升落、无 borrowed 关池日志 ⇒ H-A 为主，H-B 证伪；
   - 格 D 仍红 ⇒ 容量说被证伪，红因在 XA 归还路径或 hint 时机 ⇒ 主攻 H-B/H-C。
6. **格 E 借出栈定位（2026-11-04 依 F14 补入，属配置项、非产品码改动）**：把测试用 datasource 的
   `removeAbandoned` 置 `true`（`removeAbandonedTimeoutSecond` 取足够大以免误杀在途事务），重跑格 B 同一配方。
   关池时 `JdbcDataSource.close()` 会打印 druid 的 `getActiveConnectionStackTrace()`，即「这 39/48/65 条
   究竟是谁、在哪一行借走的」。判据：栈若集中在 `BaseXaSqlConnection.getConnection`/`getWriteableConnection`
   ⇒ H-B′ 成立并可精确到行；栈若来自心跳/建池/`XaLogImpl` 等公共路径且各自合法 ⇒ 偏 H-A/H-C。
   注意 `removeAbandoned=true` 本身会**主动回收**疑似废弃连接，故本格只用于取栈定位，**不得**当作修法。

判据（预先写死，不许事后挪）：

- 计数一律用**差分**（结束值 − 起始值），因为容器跨天复用、累计值不可归因。
- `USE_CON` 单调升且前端连接关闭后不回落 ⇒ 判 H-B 成立；升落跟随测试生命周期 ⇒ 判 H-C/H-A。
- 若 B、C 两格后端 `Threads_connected` 峰值都触及 151 且差分 `Connection_errors_max_connections>0`
  ⇒ H-A 至少是必要条件；此时**不得**用「把 `max_connections` 调大让红消失」当收口，
  只能作为「抬高后是否仍有红」的对照实验记录。

### 4. 判决树与修法（按定性分派，含边界）

- **判 H-A 为主**：改测试环境，不改产品码。后端容器显式 `--max-connections`（CI 与本地一致化），
  并把该值写进护栏文档；同时评估给 `DatasourceConfig.maxCon` 一个「远端单实例」护栏提示——
  三个池各 1000 而共享一个 151 的后端，属结构性超订，至少要留下一条 WARN 的立案（新票，不在本票偷跑）。
- **判 H-B 为主**：产品码缺陷，走完整 TDD（RED：一个断言 `USE_CON` 在事务结束后归零的最小测试；
  GREEN：修 XA 归还路径）。范围锚点：`BaseXaSqlConnection.clearConnections()` 与回滚失败分支
  （`va/src/main/java/cn/mycat/vertx/xa/impl/BaseXaSqlConnection.java:155-213`）——失败路径走
  `kill()+tryRecovery()`，成功路径才 `clearConnections()`，需逐条核对 borrowed 是否必还。
- **判 H-C 为主**：测试侧卫生，两条已知事实先修：F2（`init` 实例字段 ⇒ hint 每条都发）与
  F3（JUnit5 `@Disabled` 挂 JUnit4 类上形同虚设，须改成 `@Ignore` 或迁到 Jupiter API，二者取其一，
  不许混用）。改完必须仍能在**故意造脏**的负控下复现预期行为，否则等于把红藏起来。
- **任何一支**都不得顺手动 `.github/workflows/*`（刀②属 I-DEBT-79，待用户拍板）。

### 5. 完成定义

1. 四格取样数据入库（CSV + 日志摘要进 `.superpowers/sdd/2026-11-04-i-debt-80-forensics/`），
   每个数字都能追到一条命令输出。
2. `DEBT-QUEUE.md` I-DEBT-80 行按实测**重定性**（含份额表述），并明确它是否派生出新的产品码票；
   派生则开新票、不派生则本票按上述修法关闭。
3. 若判 H-B：新票走 RED→GREEN→全量护栏→活体→负控，收口硬门沿用 I-DEBT-71/72 那两枚的写法。
4. 全量护栏口径不因本票改变：`550/0F/5E/37skip` 仍是当前基线，红类清单逐字可比。

### 6. 实测判决（2026-11-04 收口，六格取样全部出数）

**判决：H-B′ 为主因，以 F19-F21 的确切形态坐实；H-A 为放大器（必要条件）；H-C 部分成立（套件卫生）。**
派生产品码新票 **I-DEBT-81**，本票（I-DEBT-80）作为取证票到此关闭。

因果链（每一环都有 `file:line` 或日志原文）：

1. XA 策略下，autocommit 的每条语句都走 `LocalXaSqlConnection.getConnection:145`，
   借一条物理连接并无条件塞进 `extraConnections`（F19：84/84 栈全在这一行，`BaseXaSqlConnection` 0 命中）。
2. 排空腿 `clearConnections()` 是 fire-and-forget ＋ 串行 `compose` 链（F20）：一处 `close()` 不完成即队头阻塞，
   `extraConnections.clear()` 也随之永不执行 ⇒ 列表只增不减，且这一格没有任何守卫（F21）。
3. 归还动作本身还挂在「上一条语句的 future」上、无超时无兜底（F12），是链头阻塞的候选触发点。
4. 测试的 `resetConfig` / `createDataSource` hint 重建数据源 ⇒ `JdbcDataSource.close()` 时 borrowed=37/47/127
   照打日志后把池关掉；druid **不强收已借出的物理连接**（F7/F13），且 abandon 任务随池停止（F18）
   ⇒ 这批连接成为**孤儿**：后端 `Threads_connected` 单调抬升（151 容量下 147，500 容量下 224），
   而新建的池在 `showDataSources` 里 `USE_CON` 只有 0-3 ⇒ 「借出数很小」与「后端会话很多」并不矛盾，
   它们是**两个不同世代的池**（这正是上一轮误读的地方）。
5. `Threads_connected` 触到 `max_connections` ⇒ 建物理连接被拒（`Connection_errors_max_connections` 差分 >0）、
   druid 等满 `maxWait 30s` ⇒ `GetConnectionTimeoutException` 抛在 `@Before` ⇒ JUnit4 记为 Error，即残留 5E。

四格/六格对照（详见 `.superpowers/sdd/2026-11-04-i-debt-80-forensics/cell*-notes.md` 与 CSV）：

| 格 | 变量 | 结果 | 判别力 |
|---|---|---|---|
| A | 单方法隔离 | 1/0F/0E，后端峰值 2 | H-C 的必要条件成立（隔离必绿） |
| B | 整类复现 | 17 跑 3E，签名 `active 65, maxActive 1000, createErrorCount 7` | 复现成功 |
| C | 非 XA（`transaction_policy='proxy'`）同 17 条 | 17/0F/0E 463s，峰值 14，关池 borrowed **0 命中** | **分界线确在 XA 策略**（H-A 单独不成立） |
| D | `max_connections` 151→500 | 17/0F/0E 290.9s，峰值 224，关池 borrowed 仍 47/37/126 | H-A 只是放大器；跑完已回退 151 |
| E | `removeAbandoned=true/20s`（cap 151） | 17/0F/0E，回收 0 次，但 `close:128` 打出 84 个借用栈 | 主仪器落空、副仪器命中 ⇒ F18-F20 |
| F | 越阈值自动 `jcmd Thread.print` | tc141 时 12 个工人线程 11 个 idle | 「无人使用但会话仍在」= 孤儿世代，非并发需求 |

**明令禁止的收口方式**（都已在实测中出现过，都不算修好）：抬 `max_connections`（格 D）、
开 `removeAbandoned`（格 E）、把 `@Before` 的 hint 改成整类一次、给类加回 `@Ignore` 让它别跑。
前三者只是把与 151 的赛跑改成稳赢，孤儿连接仍在产生；后者是把红藏起来。

