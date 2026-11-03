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

三个 ds（`prototypeDs`/`ds0`/`ds1`）全部指向**同一后端** `localhost:3306`
（`DB1 = jdbc:mysql://localhost:3306/mysql?...`，`MycatTest.java` 常量），故三池共享 151 槽位。

### 2. 候选定性与各自的可证伪预测

| 代号 | 定性 | 若为真，隔离取样必须看到 |
|---|---|---|
| H-A | **纯后端容量**：151 太小，套件的瞬时并发需求本就 > 151，与 XA 无关 | 单跑 `DefaultBackendConnectionTest`（非 XA 同 17 条）也出现同等 `Threads_connected` 峰值；XA 与非 XA 的 `USE_CON` 时序无系统性差异 |
| H-B | **XA 路径借出不还（第三枚真缺陷）**：`transaction_policy='xa'` 下每条查询/每个分支借用后未归还，`USE_CON` 随测试序号单调上升且**关池也收不回** | 同一测试内 `USE_CON` 单调升；测试结束（前端连接 close）后不回落；`JdbcDataSource.close ,but pool count` 在 XA 类里逐条变大 |
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
