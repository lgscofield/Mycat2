# 开放债务队列（驾驶舱 · 只列未关项）

> **这是什么**：Mycat2「客户端眼中**无缝替代 MySQL** 5.7/8.0/8.4(LTS)/9.x(Innovation)，**并为 MariaDB 10.6/10.11/11.4(LTS) 预留扩展位**」招牌目标的**唯一开放项队列**。每轮对话开头扫一眼这里就知道整头象、点一格往下关，不用再从 296 行散文中重新拼。
>
> **怎么用**：本文件只放🔴**真正没关的**。已收口的不在此列（去 §5 绿灯区指针查）。"下一步"永远 = 关闭序里最靠前的、依赖全已满足的那一格。
>
> **深度证据在哪**：本队列是**索引**，不是证据本体。逐格的红/绿判定、file:line、实跑日志一律回看权威看板 `2026-09-30-mysql-mariadb-compat-PROGRESS.md`（进度总表 L52–117）+ 对应 SDD 账本 `.superpowers/sdd/*/progress.md`。
>
> **校准**：2026-10-13（凭当前 `git log` + `server`/`example` 代码实读复核，非凭记忆）。**R4 前端 TLS Task3 已 Evidenced-Done**（`e01368405`：方案 A 传输升级 + `FrontendStartTlsUpgradeTest` 2/2 + server 全量 33/0/0 + 真 Connector/J 离线探针定谳不合并），**Task4 矩阵已建格**（`TlsAxisMatrixTest` 编译过 + `2026-10-13-tls-compat-matrix.md`，全矩阵 e2e = NO_DATA 待 infra）。此前：**R5 CRUD 红族三根收口**（I-DEBT-39/34 完全关，I-DEBT-36 单片下推面关，跨片 caseI36 另立专项）已据 `run-matrix` ×7 全档实证回写（`matrix-report.md`：`CrudResultMatrixTest` 10 格 × 7 档全 PASS）。**2026-10-12 整分支终审+修复 pass 后窄化**：I-DEBT-34 跨片归并/本地计划列头实证为真残口→闭合面窄化为单片/下推(见 §2 I-DEBT-34·残)，C10 除零语义口径窄化(见 §2 I-DEBT-39·残)，C9 数据换跨月跨年获真鉴别力；因生产码未变、C9 仅 test 数据变且值版本无关，已 `run-matrix` ×7 重跑全档 FAIL=0、数字不变（`matrix-report.md` 2026-10-12 20:27 重生）。

---

## 🎯 招牌对齐仪表盘（进度 + 方向，一眼看清）

**北极星（spec 目标一句话 + §1.1）**：客户端眼中**无缝替代 MySQL** 5.7/8.0/8.4(LTS)/9.x(Innovation)；**MariaDB 10.6/10.11/11.4(LTS) 是"预留扩展位"（M6，排在 M2 之后），与 MySQL 不是并列的"无缝替代"**。
**尺子（spec §3）**：某版本"被支持" = **M2 连接达标**（native+caching_sha2、TLS on/off、全 connector 跑基本 CRUD）→ **M3** mysqldump 往返 → **M4** 迁移矩阵绿。关键路径 `M1 → M2 → M3`。

**对齐分层**（每个开放项都带此列，比分类更能回答"离目标多近"）：
- **A** = 招牌硬门本体（spec 点名的 M2/M3/M4/M6 判据项）——关掉它 = 直接朝目标走
- **B** = 度量前置（M1 验证网）——为 A 出证据
- **C** = 引擎地基债（喂 M2 的"基本 CRUD"，但不在 spec 关键路径上）
- **D** = 生产稳健（与"无缝替代"正交，spec 明说独立并行）

| 里程碑 | 招牌判据 | 完成度 | 卡在哪 |
|---|---|---|---|
| M1 验证网 | 矩阵出 pass/fail + CI 绿 | 🟢 ~85% | 认证轴+**操作轴(CRUD)双轴 7 档全跑绿** + 已 push fork（2026-10-12）；剩 CI 徽标（**Actions 已启用 ✅，缺首个触发**：workflow 无 `workflow_dispatch`、需一次新 push 到 main 才会跑）+ connector 轴（Connector/J 9.x 已 PASS，Go/Python/C/.NET 已建格记 NO_DATA+provision runbook 就绪，真跑属 I-DEBT-9 polyglot/docker 专轮，触装运行时红线）|
| **M2 L1 连接** | native+sha2、TLS on/off、全 connector | 🟡 **认证轴✅ · TLS 传输实证+矩阵建格 · connector 真跑待 infra** | 前端 I-AU-1/3/5 已落（native + caching_sha2 原生 fast/full-auth，2026-10-12 R3 live 8.0/8.4/9.0 实证）；I-AU-2 TLS：配置/解析/Connector/J 传输升级 Evidenced-Done（`e01368405`），e2e 矩阵与非 Java connector = NO_DATA 待 infra（`2026-10-13-tls-compat-matrix.md`）|
| M3 L2 工具 | 各版本 mysqldump 往返回灌 | 🟡 ~60% | **I-ME-1 已落**（persona 驱动三张 i_s 表，锚官网，TDD 6/6）；**I-ME-2 双半区已落**（列覆盖锁 8/8 + FILES 补四列，e2e 矩阵格 NO_DATA 待 infra）；**I-ME-3 进程内已落**（p_s 列面+行面锁 8/8，锚 8.0.42 源码，rgm 补列+变量/状态同源行供应）；**I-ME-4 已落**（透传路径回归锁 10/10，`707c9a5bc`，锚官网 §15.7.7.23/36） |
| M4 L3 迁移 | MigrateTest 矩阵绿 | 🟡 ~40% | 迁移轴矩阵格已建（`MigrateMatrixTest` NO_DATA）+ 流水线 bug修复（`dc22f5b92` PK检测时序+标识符反引号）+ **I-RP-3 已关**（README Non-Goals N1-N4 落文档，`400a99d33`，与 spec §1.2 同构）；I-RP-2 binlog 客户端属"保持"维护面（`BinlogUtil.observe` 已存）无当前 active bug；真跑待 infra |
| M5 / M6 | 生产就绪 / MariaDB ed25519 | ⚪ 0% | 关键路径外 |

**方向判词（诚实）**：已收口的一大批 I-DEBT 几乎全在 **C 层**（引擎地基），而招牌的 **A 层头条（前端 TLS / caching_sha2 原生协商 / 版本 persona 连接级 / mysqldump 往返）几乎原封未动**。⚠️ 别被嗓门大的 e2e 崩栈（C）拽着走——关闭序严格按下方 §6 的 `M1→M2→M3` 走。

---

## 0. ⚠ 铁律：状态必须复核，别信表

本队列在建立时就抓到总表两处过期（详见 §4 订正）。**任何一格在动手"关闭"或"汇报"前，先拿当前代码/`git log` 坐实它的真实状态。** 总表、看板快照、记忆都会腐坏；实跑和 commit 不会。

分类口径（每格后缀）：
- **架构** = 需 spec→plan→implement 专轮，blast 大。
- **有界** = 单点/单文件，直接 TDD。
- **背书** = 东西已做，只差 CI/push 出公共证据。
- **挂账** = 已定性、已知根，但非 crisp，需专轮。

> **两个正交的标签**：`分类`=改动体量/性质；`对齐`(A/B/C/D，见仪表盘)=离招牌多近。**排序与取舍以「对齐 + 关键路径」为准，不以哪条红嗓门大为准**——C 层的崩栈再响，也不该插到 A 层硬门前头。

---

## 1. 🔴 招牌关键路径（M2「连接达标」——能不能无缝替代的硬门）

| ID | 一行说明 | 依赖（→是否已满足） | 分类 | 对齐 |
|---|---|---|---|---|
| **I-VE-2** | ✅ **EOF 能力位连接级化完成**（2026-10-12：config/handshake 段—`VersionProfileFactory` 按版本烘 `CLIENT_DEPRECATE_EOF`、`VertxMycatServer` 握手改读 profile 去全局 static；连接级编码段—活编码器 `MySQLServerSession.writeColumnEndPacket/writeRowEndPacket` 按 `isDeprecateEOF()`→`dataContext.getServerCapabilities()`(每连接实例字段，握手 `VertxMySQLAuthHandler:233` 存客户端上报 caps)分支，已架构正确。回归锁 `ConnectionEofDeprecationTest` 5/0/0 取证。债务点名的 `NewMycatConnectionImpl:305` 读全局 static 属**死码**（`ProxyReceiverImpl:32` 连同门控 `:27` 注释），不投机改动；该全局现仅喂后端 datasource 协商，非前端 persona） | I-VE-1 ✅ | 有界 | **A** |
| I-AU-1 | ✅ **前端 caching_sha2 原生协商**（2026-10-12 R3 落地：删 0xFE 降级，`VertxMySQLAuthHandler` 状态机 fast-auth(warmed)+full-auth(RSA)；live 8.0/8.4/9.0 `FrontAuthMatrixTest` sha2 格全 PASS） | I-CI-3 本地✅ / I-VE-1 ✅ | 架构 | **A** |
| **I-AU-2** | **前端 TLS：配置面/SSLRequest 解析/Connector/J 传输升级已 Evidenced-Done**（R4 Task1/2 + Task3 `e01368405`：`FrontendStartTlsUpgradeTest` 2/2 RED→GREEN + server 全量 33/0/0 + 真 Connector/J 离线探针 `firstReadTail=0 → cipher=TLS_AES_256_GCM_SHA384`，方案 A 对主目标客户端定谳；BC 装依赖红线**已获用户批准并落地** compile 域）。**仍缺**：全矩阵 e2e（`TlsAxisMatrixTest` 已建格+三层诚实门控，真跑待 run-matrix infra）与非 Java connector×TLS（=NO_DATA，判据口径/runbook 见 `2026-10-13-tls-compat-matrix.md`）。合并型客户端（MariaDB/go）行为未知属残留风险，不得类推） | I-CI-3 本地✅ / I-VE-2 ✅ | 架构 | **A** |
| I-AU-3 | ✅ **非 TLS 下 RSA 公钥检索**（2026-10-12 R3：`RsaKeyPairProvider` 进程级 2048 RSA + PEM 下发、`fullAuthOk` OAEP/PKCS1 解密；live 冷态 full-auth 实测通过） | I-AU-1 ✅ | 架构 | **A** |
| I-AU-5 | ✅ **persona 驱动握手宣称插件**（2026-10-12 R3-T1：`VersionProfileFactory` 按 persona 选 native/caching_sha2；T7 `-Dmycat.persona` 启动覆盖） | I-VE-1 ✅ | 有界偏中 | **A** |
| I-AU-4 | 后端 ed25519（MariaDB `client_ed25519`） | I-VE-1 ✅ / M2 ⬜ | 架构·BLOCKED | **A**(M6) |
| **I-AU-6** | **后端 ed25519/多驱动**：mysql-connector-j 不支持 `client_ed25519`（实测 `Unable to load authentication plugin`），需引入 MariaDB Connector/J 或驱动 SPI 按后端类型选驱动。**用户明确"必做"** | I-AU-4 ⬜ | 架构 | **A**(M6) |

> 前端认证 posture 的真根（活路径 `VertxMySQLAuthHandler`：通告 native + 0xFE 强降、只做 scramble411）已钉在看板 I-DEBT-37 前端面；I-AU-1/3/5 就是把它从"降级蒙"升级成"原生协商"。

---

## 2. 🟡 8.0 深债红族（M2「基本 CRUD 全绿」门槛——example e2e 实测还红的）

| ID | 一行说明 | 分类 | 对齐 |
|---|---|---|---|
| I-DEBT-7C | example 全量重基线（当前 479 tests / 7F / 63E / 30skip）——下面这几族的收口总账 | 挂账·WIP | C |
| I-DEBT-8 | ✅ **同族泛型化 Evidenced-Done**（本次）：`RxBuiltInMethodImpl` 对 `filter/topN/sort/limit/offset/unionAll/toEnumerable/toObservable/toObservableCache` 取 `<T>` 签名，同 `select`（`81f228c17` I-DEBT-6）一致；`RxBuiltInMethod` 枚举的 erased `Method` 命中不变，既有 ARRAY 行 codegen 零回归。新回归锁 `RxBuiltInMethodImplRxOperatorsTest` 8/0/0（包含标量 Observable<String>/Integer 经各算子 + Object[] 反向护栏）；hbt 170/0/0 + server 33/0/0。`matierial`/`batch`/`asObservable`/`asGather` 保留 `Object[]` 声明（内部 `OffHeapObjectList.addObjects(Object[])` 依赖行形态），上游须先经 `MycatBindable.asObjectArray` 升箱（已契约化）。`[B`→ByteString CCE 面先前已 `9fd7ac2cf` 收口。 | 已关·C | C |
| I-DEBT-35 | BINARY/BLOB 列 codegen 声明面 ByteString vs 运行时 byte[] 错配（牵连 RexToLix 字面量 + 结果端字节保真，面大）。Root B 的 BIT byte[] 样板已立（I-DEBT-34 收口），BINARY/BLOB 应用留另轮。**🆕 2026-10-13 专轮勘察**：证伪“局部 `getJavaClass(BINARY)`→byte[] 即可翻”——仓内既定跨切不变式“BINARY 内部规范表示=ByteString”（`BaseRowObservable` 行值 wrap、`NewMycatDataContextImpl` 参数 wrap、`DrdsRunnerHelper` byte[]→BINARY、codegen `(ByteString)` cast，I-DEBT-8/17/33 三度固化）；半翻致 `(byte[]) slot` 对每个 BINARY/blob 投影 CCE。正确翻=同时转整条表征链 + BINARY/blob e2e 验证 → **NO_DATA 待 infra，本轮回退不交**（knownGap `TypeRepresentationThreePathConsistencyTest` 仍钉 ByteString 现状 34/34 绿）。留专用表征链专轮 | 挂账·跨切表征链，非局部改动，待 infra | C |
| I-DEBT-36·残 | `TIMESTAMPDIFF(unit,t1,t2)` **跨片全计划**去回环重解析后落标准算子→convertlet 展平成含 `REINTERPRET`(`SqlSyntax.SPECIAL`) 算术→Mycat dialect 无法 deparse（红锁 `UserCaseTest#caseI36`）。单片下推面已 R5 收口 ×7 绿（见 §5）；跨片正解=方向 A「原生三参整体下推」，2026-10-12 用户拍板**另立专项**、本里程碑不强推。根因图见 `.superpowers/sdd/2026-10-12-r5-crud-debt-roots/c2-pushdown.md` | 架构·特性级 | C |
| I-DEBT-34·残 | **终审 Important #1**：BIT 列对外官方 byte[] 口径**仅单片/下推直通列头闭合**（Root B，见 §5）。跨片归并/本地计划列头走 `CalciteRowMetaData.getColumnType`（取 Calcite RelDataType jdbcOrdinal），BIT 经 fork 类型系统（无 `BIT` SqlTypeName）归一为**数值**列头、失 BIT 性，而行值仍是后端原生 byte[]→`BaseRowObservable.getObjects:355` 数值分支崩（live 复现 `UnsupportedOperationException:[B@`）。即使只透传不崩，对外列头仍为数值而非 `FIELD_TYPE_BIT`、官方口径仍未守。修复须联动 fork 类型系统/归并列头来源 = **“类型表征统一”专轮**（与 I-DEBT-36 跨片 caseI36 同族）。红锁 `UserCaseTest#caseCrossShardBitColumnFidelity`（live RED）。**🆕 2026-10-13 类型表征专轮**：dormant 辅助件 `RowMetaDataFidelity.effective`（`da613da3f`）落地——以后端忠实轨 `getColumnType`(=`parseDataType`→`Types.BIT`) 归一有损计划轨；列头 payload 契约锁 `MySQLPacketUtilBitHeaderTest` 3/3（归一视图经 else 分支 `fromJdbcType(BIT)`→16，`0d596d21e`）。**⚠ 整分支独立 CodeReview 后 Task 2 生产 wiring 已回退**：初版（`66a47bffe`）仅在 `VertxExecuter.onColumnDef` 行解码缝接忠实轨，而响应列头/wire 仍取有损 plan(BIGINT) → 跨片 bit(n>1) 行值成 byte[] 却按数值列头 wire，`ResultSetMapping`（server）落 default 分支 `Objects.toString(byte[])`=`[B@...` → **沉默数据损坏**（Critical#1，严格劣于原响亮崩溃）；且 wiring 面扩大至 BOOLEAN/JSON 跨片解码（Important#2）。裁定：沉默损坏不可夹带 → **回退 `VertxExecuter` 至历史 `orElse` 行为（恢复响亮崩溃 status quo）、`resolveSplitRowMetaData` 删除**，`RowMetaDataFidelity`+`CrossShardBitRowDecodeTest`（重写为直测 `effective`+`getObjects` 的 dormant 契约锁，标“⚠ 未接入生产装配”4/4）保留。**正解**：跨片 split 行解码缝与响应列头缝须“**两缝同移**”——把首分片 `MycatMySQLRowMetaData` 经 `runQuery→getObservables→PrepareExecutor` 异步契约上抛至列头装配点，与行解码一并切忠实轨。本轮 + 最终 CodeReview 均判此 wiring **NO_DATA 待 Docker 2 分片 infra**（不得宣称跨片 e2e 已通），与用户排定“先扫干净、后起 infra”一致。**✅ 2026-10-13~14 两缝同移专轮收口**：infra 已起（m1-mysql-a:3306/b:3307），Task1（`8027e55ea` 列头缝经 per-query carrier 惰性重建）+ Task2（`ae4b71ffc` 行解码缝 `orElse`→`effective`，两缝合一）落地；含 order by 排序键尾部下推（`effective` 守卫 `==`→`>=`）与 BIT 真实位长（`ColumnDefPacketImpl` BIT 分支取 displaySize 非硬编 1）两根因修复。**活 e2e `caseCrossShardBitColumnFidelity` 由响亮红转 GREEN**（2 行跨 2 分片 order by，bit(8)=0xB3 忠实 byte[]）；hbt 180/180 + CrudResultMatrix 10/10 + 纯 fat-jar 活 e2e 终验绿；UserCaseTest 仅 3 例经基线实验证实既有红（case14/case611/caseI36，非本次回归）。**identity/同构 split 跨片列头保真已闭合**。唯余多节点异构同-arity（union/lookup）carrier 作用域沉默面另立 **I-DEBT-41**（同轮独立 CodeReview Important #1）。 | ✅ identity/同构跨片两缝同移 Evidenced-Done（`8027e55ea`+`ae4b71ffc`，活 e2e GREEN）；多节点异构残留 → I-DEBT-41 | C |
| I-DEBT-41 | **跨片两缝同移·carrier 作用域收口（终轮独立 CodeReview Important #1，2026-10-14）**：I-DEBT-34 跨片列头两缝同移已就 identity/同构 split 落地转绿（`8027e55ea`+`ae4b71ffc`，活 e2e `caseCrossShardBitColumnFidelity` GREEN + hbt 180/180 + CrudResultMatrix 10/10）。终审发现新引入的沉默面：侧信道 `backendRowMeta` 是**每查询单槽·首写者胜**（`AsyncMycatDataContextImpl:67-74`），未限定到顶层结果节点。列头缝（`PrepareExecutor:83-85`）用这唯一 carrier 为**整个顶层结果集**建**唯一列头**，而解码缝（`VertxExecuter:918`）用**各扫描自己的**后端轨独立 `effective`。当某查询含**多个与前导投影结构不同构的后端扫描且列数 ≥ 顶层计划**（最现实=同 arity 异构 `union all`：分支 a `bit(8)`、分支 b `BIGINT`；及 `MycatSQLTableLookup:272` 复用外层 carrier 的 lookup）时，先回包分支的轨被当顶层列头，另一分支行值按自身忠实轨解码 → 列头类型≠行值类型 → **沉默损坏**（改前列头恒用有损计划轨、与有损解码一致=响亮安全，故此面为本次新增）。**正解须两缝共决**：把“忠实/有损”做成**每查询一次、发首行前对全部源 schema 结构核对后定**的一致性决策（异构→两缝同回计划轨，即改前一致态）；仅翻列头或仅 invalidate carrier 会落“行忠实/列头有损”=INV-④ 禁止的更劣窗口 → **不可局部塞**。跨切/需设计，另立专轮；本轮不越界。红锁缺口=无跨片同-arity 异构 union/lookup 活靶（评审亦 Declined to judge 可达性，回归矩阵未证伪）。**关联**：I-DEBT-42 的 A′ 列名对齐守卫（`aac7f36ff`）已为本面提供部分缓解——carrier 后端轨列名与顶层计划列名异名时列头回计划轨（=改前一致安全态）；但同名异构仍可能按位误覆写，且解码缝不受列头守卫约束，故本票仍开待源头形状谓词正解。<br>**🔴 2026-10-14 对拍基准活靶红锁落地（依 ADJUDICATION.md，非口头推断）**：新探针 `CrossShardUnionHeaderDifferentialTest`（example/compat）——Mycat 建 `t_bit(c bit(8))` 与 `t_bigint(c bigint)` 两张分片逻辑表，`select c from t_bit union all select c from t_bigint`（同 arity 异构跨源=本票点名面）。**期望（直连真 MySQL 8.0.46，JDBC `ResultSetMetaData` + CLI `--column-type-info` 双背书）=列归并 DECIMAL(precision/displaySize=19)、行值 bit 归成整数 [1,7,45,123,170,255] 干净返回；实际（经 Mycat 跨源）=服务端崩 `java.lang.ClassCastException: class [B cannot be cast to class java.lang.Long`**（header seam 把合并列定成数值/Long 轨，decode seam 拿 bit 分支 `byte[]` 行值强转 Long → 崩）。此即两缝/carrier 不一致的活靶现形（本轮响亮崩，根因同沉默损坏）。**spec 目标由该差异表定义**：Mycat 须把异构合并列解析到 MySQL 该 persona 的归并类型（本例 DECIMAL），header 与 decode 两缝据此一致，bit 行值按数值渲染。**待补**：跨 MariaDB/其它版逐 persona 跑此活靶；lookup 异构活靶另加。<br>**✅ 2026-10-14 专轮收口（plan 2026-10-14-cross-shard-column-type-authority，executing-plans 内联）**：两形状谓词（`MycatMergeShape` 异构归并 + `hasInternalReconsumingOperator` 内部重消费 Join/Agg/Window/Correlate/SetOp 族）+ 两标志三接线（解码缝 `isPlanTrackDecode` 单一入口、列头缝 `PrepareExecutor` 对应分支、`ObservablePlanImplementorImpl` 置位）；① 单后端 describe 取归并列元数据（`BackendMergedMetaProbe`）落地；活靶 3/3 转绿（union 五元组+lookup BIGINT 列头+cross-ds ③ 边界）；persona 矩阵实跑（docker 5.7/8.0/8.4/9.0/mariadb 11.4）：8.x 🟢、5.7/MariaDB 🔴 定性为**列头重建形态失真**（probe gate PASS 却 LONGBLOB≠VARBINARY，jshell 三形态实证）→ 另立 **I-DEBT-45**，矩阵红如实挂账、不计兼容。唯余残留=列头重建非 raw 透传（I-DEBT-45）与极端跨 ds describe 不可行回 ③（Review Focus#5 已显式标注）。 | ✅ 专轮收口（两缝一致性闭合；列头重建残留 → I-DEBT-45） | C |
| I-DEBT-45 | 🔴 **列头缝重建形态失 wire 保真（FaithfulTypeView+MySQLColumnDef.of 重建而非 raw 透传；persona 矩阵实证，2026-10-14 I-DEBT-41 专轮）**：① 8.x：异构 union 列头 DECIMAL 型对、Length 形变——直连文本/prepared 均 p=19/ds=19（jshell 实证 union/limit 0/三分支全形态），经 Mycat p=254/ds=84（`FaithfulTypeView.getPrecision/getScale` 无条件委派计划轨，Calcite DECIMAL 默认精度）。② 5.7/MariaDB：后端三形态一致报 **VARBINARY(20)**（jshell 实证），probe ① 取到忠实 meta（gate PASS、authority=MycatMySQLRowMetaData），但客户端看到 **LONGBLOB(255)**——列头重建把 binary 族 wire VARBINARY 展成 BLOB 大类型并丢长度。正解方向=列头缝 raw column-def 字节透传（仅改名/逻辑列时才重建），与 I-DEBT-34 残列 wire 编码面同族。<br>**persona 对拍差异表（I-DEBT-41 Task 6，docker 实跑 mysql:5.7/8.4/9.0/mariadb:11.4，活靶 union+lookup）**：MYSQL_8_0 🟢（lookup 全对；union Type/Decimals/Flags 对、Length 形变=本票①）；MYSQL_8_4 🟢（同 8.0，含本票①）；MYSQL_9_X 🟢（同 8.0，caching_sha2 后端认证通路实跑无恙）；MYSQL_5_7 🔴（union 列头 VARBINARY→LONGBLOB=本票②；lookup 🟢）；MARIADB_11_4 🔴（同 5.7 形态；lookup 🟢）。另：lookup/内部重消费族列头回计划轨后 displaySize=6 vs 直连 19（计划轨形态差，属本票重建族窄面）。矩阵原始档 `.superpowers/sdd/2026-10-14-cross-shard-column-type-authority/persona-matrix.md`（与仓内活靶输出一致）。<br>**⚠ 2026-10-14 整支终审 Important-2 重定性 + 修复**：值级取证（活靶 5.7 persona 实跑）发现比“列头失真”更严：Mycat 5.7 跨源 union 行值**全 `null`**（7 行七 null），而直连=ascii 数字节 [0x31,0x313233,...]——非仅类型标签差，而是**两缝分叉致真实数据静默丢失**（binary 列头 probe 重建 + 计划轨 numeric 解码分叉→客户端读 null），**严格劣于改前响亮崩**（=本债群一贯防的 INV-④ 沉默面，我初“不计兼容”仅凭 Type 证据漏看此面）。**已修（本轮终审修复轮，TDD）**：`RowMetaDataFidelity.isBinaryFamilyAuthority` 纯谓词（任一列 Types.BINARY/VARBINARY/LONGVARBINARY/BLOB 即真）+ `probeMergedBackendMeta` 接线：探测权威命中 binary 族即**整体拒绝回 ③ 计划轨**（两缝同 numeric）。见证：5.7 行值 null×7 → `[Long:1,7,45,123,170,170,255]` 非 null（数据正确）；8.0 probe=DECIMAL 数值族不被拒→① 保留（活靶 3/3）；common 28/28 + hbt 185/185。**残留（仍属本票）**：5.7/MariaDB 列头类型标签 BIGINT vs 直连 VARBINARY + 值形态 numeric vs ascii-字节（③ 计划轨保真差，非数据丢失；需 wire raw 透传专轮）。<br>**终审 Important-3 边界（本轮未改代码·已裁）**：`hasInternalReconsumingOperator` 覆盖面宽（Join/Agg/Window/Correlate/SetOp 五族），将**单源** `select distinct bit_col`/`sum(bit_col)`/join 直通 bit 输出列一票退计划轨→numeric（I-DEBT-34 保真部分回退，无崩、值数据正确仅失 BIT 标签）；此为泛化谓词既定代价（ledger 已录），收窄谓词恐重新引入 agg 消费 bit byte[] 裸转崩溃，不采。**终审 Important-1 注**：probe 同步阻塞最长~4s（连接 2s+describe 2s）——非本轮新引入危害，属既有架构同型（`VertxExecuter:395`/`VertxUpdateExecuter:323` 同线程已有 `.get(1s)` 有界阻塞）；probe 有界且每查询一次、失败回 ③，异构跨源 union 属稀形，不单独修（如后续需可将 timeout 可配/脱离 event-loop）。 | 挂账·列头重建保真（wire raw 透传专轮；silent-null 数据丢失已修，仅余类型标签保真差） | C |
| I-DEBT-39·残（除零对外语义） | **终审 Important #3**：I-DEBT-39 已关的是**传输层**（运行期错后会话不被 2013 打挂，C10 钉此）。MySQL 于 SELECT 期 `1/0` 语义=**NULL + Warning 1365**（仅 strict 模式下 DML 才升 1365），Mycat 此处返回 SQLException = 值语义分歧。**除零“对外返回什么”属独立语义对拍债**，不由 C10 宣称兼容（C10=错误路径格、非对拍格）。<br>**🔬 2026-10-14 现场取证重定性（架构·需专轮，非 Bounded）**：实测坐实两端分歧——真 MySQL 8.0（m1-mysql-a）`SELECT 1/0`/`1 DIV 0`/`1 MOD 0`/`1%0` **全 NULL** 且每条 `SHOW WARNINGS` 出 `Warning 1365 Division by 0`；Mycat 代理（8066）`SELECT 1/0` 抛 `java.lang.ArithmeticException: / by zero`→SQLException，且 `SHOW WARNINGS` **恒返回空**。缺口四块（均超单文件窄修）：① 值语义触碰**所有 `/ DIV % MOD` 算子的全局求值**（`MycatCalciteMySqlNodeVisitor:1158-1164` 把 Divide→DIVIDE、DIV→DIVIDE_INTEGER，除零走 Calcite 本地整数除抛异常），R5 设计当初即警告“勿为错误码动执行器异常语义全局”；② **告警子系统缺失**——`ShowWarningsSQLHandler.java:38` 硬编码 `setRows(emptyList())`，无 per-session/per-statement 告警缓冲，要出 1365 须新建累积→按语句清空→`SHOW WARNINGS`/`SHOW COUNT(*) WARNINGS`（含 LIMIT）真读；③ **sql_mode 维度**（`ERROR_FOR_DIVISION_BY_ZERO`+`STRICT` 下 SELECT 恒 NULL+warning、DML 升错误）；④ 须**翻转既有绿锁** C10 `divideByZeroIsCleanError`（现断言抛 SQLException）。综合=新增子系统+跨切算子语义+协议+模式+翻锁 → **架构专轮**（brainstorming→spec→plan），本里程碑不实现。**可选第一片**：若只要最扎眼分歧，可仅做值语义（除零返 NULL、C10 断言改 NULL），但须诚实标注 warning 未对拍（SHOW WARNINGS 仍空）。 | 架构·需专轮 | C |
| I-DEBT-23e | ✅ **Evidenced-Done `90ede2f86`**（2026-10-08）：`MycatRelDataTypeFactory.createJavaType` 已 override 补 `Duration.class → TIME` 正向映射，与 I-DEBT-12 反向对称；回归锁 `MycatDurationTimeMappingTest` 3/0/0（本地复验绿）；`PushDownFunctionShardingTableTest#testTimeFunction` 校验期错已消失（推进至 23f DAYOFWEEK 独立根 I-DEBT-32 挂账） | 已关·C | C |
| （7C 分诊·独立根） | `ClientTest.testDBeaver`=druid 相邻 `@@` 变量丢逗号（上游/或需裁定破"不 fork druid"）；~~函数签名族 BIN/CONV 严格 overload~~ **已关** `I-DEBT-23a/23b`（见 §5 绿灯区）；~~`information_schema ORDER BY` 重复列名 ambiguous~~ **已关** `44012c5e1` I-DEBT-26（回归锁 `OrderByDuplicateAliasTest` 2/2 绿）；`BlobTest` BOM/EFBFBD 字节保真；`RwTest` 分片越界；~~`HbtTest` 测试隔离(先 DROP)~~ **已关（2026-10-14）**：`MycatTest.dropTableIfExists` helper 初版直连 DB1/DB2 扫后端 stale schema（临时绕过），`HbtTest` L48–L70 前置调用；现场验证种入 stale `db1.company` (travelrecord 形态) 后测试能自扫并前进到 L84（隔离 RED→GREEN）→ 剥出两个独立根 I-DEBT-42/I-DEBT-43（见下）→ **I-DEBT-43 已同日 `d35ccf89d` 修复**（代理层 DROP 假 OK 回接 onPhysics 兑底），helper 已回切走 Mycat 代理（与同族 deleteData/execute 一致）→ **I-DEBT-42 已同日 `aac7f36ff` 修复**（HBT join 列头忠实覆写加列名对齐守卫，非初判的 DATE wire 编码）；本行余下项仍存；`PARK VARCHAR 影子` 本票未碰，待现场复现另定 | 各独立·多为架构 | C |
| I-DEBT-42 | ✅ **已关（2026-10-14 `aac7f36ff`）**：HbtTest L84 `Invalid length (5) for type DATE`（隔离修复剥出的新独立根）。**现场取证订正根因（非初判的 DATE wire 编码）**：在 `ResultSetMapping` 打点实锤——报错的 5 字节实为字符串 `"Intel"`（`companyname`），而非日期整数；`innerJoin` 数据行 `[id0,id,companyname,addressid]` 正确，但列头 `rowMetaData` 声明成左输入 travelrecord `SELECT *` 的前几列类型（`traveldate=DATE` 落到了装 "Intel" 的列）。真因＝`RowMetaDataFidelity.effective` 守卫 `backend.getColumnCount()>=plan`（原为跨片 ORDER BY 下推放宽）在 join 失效：carrier 首写者胜拿到输入叶子扫描 meta，与 join 输出计划轨不按位对齐，后端类型沉默套到语义不同的输出列→列头/行值错位的沉默数据损坏。**修复（A′）**：`FaithfulTypeView.getColumnType`/`getColumnDisplaySize` 仅在“后端列名 equalsIgnoreCase 计划列名”时取忠实轨，异名列回计划轨（单表直通/下推前导对齐照旧保真，join/聚合/计算列投影逐位异名自动回退）。红锁 `RowMetaDataFidelityTest.effectiveSuppressesOverrideWhenColumnNameMisaligned`（同名→BIT、异名→VARCHAR，RED -7→GREEN）；回归 `CrossShardHeaderFidelityTest` 绿、HbtTest e2e 全绿。**附带**：`ResultSetMapping` 补 `case Types.DATE`（真·单源直通 Integer 日计数→ISO 的正交健壮性加固，`MysqlDateWireEncodingTest` 4/4），经取证证实非本 bug 病根但保留。**边界**：同名列但语义/类型不同的极端错位仍可能按位误覆写（概率低、多为同类数值），彻底解需源头“直通 vs 计算计划”形状谓词（归 I-DEBT-34 spec §2 末尾显式 defer 的 C 兜底另票）。 | 已关·C（列头错位根因，源头形状谓词另票） | C |
| I-DEBT-43 | ✅ **已关（2026-10-14 `d35ccf89d`）**：Mycat 代理 `DROP TABLE IF EXISTS` 对 Mycat 元数据未登记的物理表不会下沉——旧实现“假 OK”。真因定位到 `server/src/main/java/io/mycat/sqlhandler/ddl/DropTableSQLHandler.java:63-77`：`if (tableHandler != null) { ... executeOnDataNodes ... }`，未登记直接 `response.sendOk()`，同文件 L87-97 `onPhysics(schema,tableName)` 早期已写但从未调用（死码）。现场取证订正：与 mysql client vs JDBC 会话态无关——当时 mysql client 能删因为之前测试已经 Mycat 建同名表→TableHandler 已登记。修：`if (tableHandler != null)` 补 else 分支 `onPhysics(schema, tableName)`，回归 MySQL 语义（后端物理表存在即删，不依 Mycat 元数据）。红锁 `MycatDdlDropRoutingTest#dropTableIfExistsShouldReachBackendWhenMycatMetadataLacksHandler`：绕 Mycat 直连后端建物理表 → 经 Mycat 代理 `DROP TABLE IF EXISTS` → 绕回后端断言已消失（修前 RED at L59 assertFalse，修后 GREEN，幂等复验不报错）。联动：`MycatTest.dropTableIfExists` 从绕过版回切至走 Mycat 代理，与同族 deleteData/execute 一致（日志证 HbtTest setup 段推至 L84→既存 I-DEBT-42，非本票新回归）。**作域限制（诚实标注）**：本兑底仅盖 prototype 映射到的单 db schema（典型 HbtTest 场景）；多 datanode 分片且元数据未登记的 schema 仍需后续票按 SchemaConfig 枚数据节点。同族 handler `Truncate/DropIndex/CreateIndex/AlterTable` 中同款 `if (tableHandler != null)` 门槛本票不治，另归后续。 | 已关·C（同族盖盖面与多 datanode 兑底另票） | C |
| I-DEBT-44 | 🔴 **bit 字面量经 Mycat INSERT 写库被折成 1（写路径，新独立根，2026-10-14 I-DEBT-41 专轮取证现场发现）**：活靶 setup 中 `insert into t_bit values (2, b'10101010'),(3, b'11111111')` 经 Mycat 代理落库后，直连物理表 `select hex(c)` 实测全为 `1`（期望 AA/FF）——非零 bit 模式被沉默折成 1，**写侧数据损坏**，严格劣于报错。与 I-DEBT-41 读侧列类型权威无关（本分支 diff 未触写路径，现场以同构旧码交叉印证）。嫌疑面：b'' 字面量解析/下推改写/参数转译某处按 boolean/BIT(1) 语义取值；需最小复现（单片直插 hex 对拍）定位层位后拆雷。活靶 `CrossShardUnionHeaderDifferentialTest` 已以直连物理灌数隔离变量（注释标另案）。 | 挂账·写路径独立根，未动 | C |

---

## 3. ⚪ 里程碑后段（M1 收尾背书 + M3/M4/M5）

**M1 尾巴（验证网）**
| ID | 一行说明 | 分类 | 对齐 |
|---|---|---|---|
| I-CI-2 | 多库服务矩阵：本地全矩阵已跑通并出权威表；compat workflow（`.github/workflows/pr-validation-main-test.yml`）已 push 到 fork `origin/main`=`a29744e68`。**用户已启用 Actions（`Allow all actions and reusable workflows`）✅**（2026-10-13）。但启用**不补跑历史 push**：该 workflow 触发= `push:[main]`+`pull_request:[main]`、**无 `workflow_dispatch`**，故首个 CI run 需一次**新 push**（将本地领先的 ~49 commit 推上即触发 push 事件）。push 属用户红线、待拍板；建议顺带给 workflow 加 `workflow_dispatch` 以便日后手动重跑 | 背书·待 push 触发 | **B** |
| I-CI-3 | 兼容性 harness：认证轴 11 格 + **操作轴 CRUD/结果集 10 格（`CrudResultMatrixTest`）双轴本地 7 档全跑绿**（2026-10-12）；三格版本无关红=引擎深债 I-DEBT-34/36/39（落第 3 步专轮，非版本兼容缺口） | 有界·本地✅ | **B** |
| I-CI-4 | ✅ MigrateTest 接矩阵（迁移轴）——`c17265134`：`MigrateMatrixTest` normal→normal 端到端建格， NO_DATA 门控（Skipped:1+须响），待 run-matrix 真跑 | 有界·本地✅ | **B** |
| I-DEBT-9 | example 测试面结构性健全：9-A/9-B 认证矩阵✅；剩 mysqldump 往返矩阵 0 覆盖；**TLS 轴已建格**：`TlsAxisMatrixTest`（sslMode 严格性判据+三层诚实门控，编译过）+ `2026-10-13-tls-compat-matrix.md`（on/off×7 persona NO_DATA 矩阵+runbook）；**connector 轴已建格**：2026-10-12 出 `2026-10-12-connector-compat-provision.md`—Connector/J 9.x=PASS、Go/Python/C/.NET=NO_DATA+官网锚定 provision runbook 就绪；真跑 polyglot/docker 网触装运行时红线待用户拍板） | 挂账 | **B** |

**M3 元数据/mysqldump（L2）【A】**：I-ME-1 排序规则表补全 **Evidenced-Done**（persona 驱动三张 i_s 仿真表，`PersonaCollations`+回归锁 `PersonaCollationsTest` 6/6，行值锚官网；SHOW COLLATION 回退分支共用本表口径同源；MariaDB 11.4 默认仍 general_ci，uca1400 属 11.5+） · I-ME-2 information_schema dump 面 **进程内 Evidenced-Done**（`3cc944dba`：官网 mysqldump.cc 8.0/8.4/MariaDB 11.4 逐行锚定查询面；回归锁 `DumpSurfaceRegressionTest` 8/8；FILES 仿表补 TOTAL_EXTENTS/INITIAL_SIZE/MAXIMUM_SIZE/AUTOEXTEND_SIZE；e2e 矩阵格 `DumpSurfaceMatrixTest` NO_DATA 待 infra；诚实结论：三引擎 dump 源码均不查 CHECK_CONSTRAINTS，不虚构缺口） · I-ME-3 performance_schema 完整 **进程内 Evidenced-Done**（`c35c14803`：列面逐锚官网 mysql-8.0.42 标签 storage/perfschema/table_*.cc Plugin_table Definition；rgm 补齐 MEMBER_COMMUNICATION_STACK+MEMBER_HOST 对齐官方；p_s session/global_variables 与 i_s 同源行供应，setup_consumers 官方 16 行集；MGR 心跳行仿真属后端复制 persona 语义层不入本票） · I-ME-4 SHOW MASTER/SLAVE STATUS **Evidenced-Done**（`707c9a5bc`：透传路径回归锁 `ShowBinlogStatusRoutingTest` 10/10；锁定 AST 类型识别+MySqlShowStatement 接口路由+toString 转发文本正确性；锚官网 MySQL 8.0 §15.7.7.23 SHOW MASTER STATUS 5 列/§15.7.7.36 SHOW SLAVE|REPLICA STATUS；e2e 由 DumpSurfaceMatrixTest SHOW MASTER STATUS 格覆盖 NO_DATA 待 infra）
**M4 迁移/复制（L3）【A】**：I-RP-1 迁移流水线修复+CI 已落 `dc22f5b92` · I-RP-2 binlog 客户端维护（TODO）· I-RP-3 文档化排除 N1 **Evidenced-Done**（本次：README 新增《Non-Goals (Scope Exclusions)》章 涵盖 N1-N4，含 MySQL 8.0 §13.7.4.13/§13.7.4.14 官方参考；源码侧核实：`COM_BINLOG_DUMP` 仅常量无 handler、`COM_BINLOG_DUMP_GTID`/`CLIENT_MASTER` 完全不存在，README 精确表述与代码行为一致）
**M5 生产就绪【D】**：I-PR-1 线程池名实相符+背压 · I-PR-2 MycatCore 参数 bug · I-PR-3 虚拟线程 spike（均 TODO·与招牌正交，挂主线外）
**横切长尾【C·不阻塞】**：I-DB-1 遮蔽类逐类归一（TODO，spec 明说"不阻塞兼容目标"）
**NON-GOAL（划掉，别摸）**：N1 代理作复制主库 · N2 InnoDB 内核职责 · N3 MySQL Shell AdminAPI · N4 对外 CDC 订阅产品化

---

## 4. ✅ 本轮订正（把"表说没关其实关了"清出去）

凭 `git log` 坐实，总表以下行**状态过期**，已移出开放队列：

| ID | 总表原状态 | 实际（证据） |
|---|---|---|
| **I-VE-1** | TODO | **已 DONE** — `8ca4690e3 feat(I-VE-1)` + `b7294f94d fix(I-VE-1)` + `3fdb6d53b docs(I-VE-1)` + `3538006cb docs(compat-board): 补 I-VE-1 收口` |
| **I-VE-3** | TODO | **已 DONE** — 随 I-VE-1 提交一并收口（`8ca4690e3` 正文注明"移除 MySQLVersion 可变静态，I-VE-3 收口"） |

> **Ruling（2026-10-13）**：总表 I-VE-1/I-VE-3 **保持 WIP、不改 Done**——看板铁律（PROGRESS L4）要求"可重跑自动化检查当前为绿 + CI 证据"才准 Done，而 fork Actions 刚启用、首个 CI run 尚未产出（见 §3 I-CI-2）。DEBT-QUEUE 的"DONE"是**索引级本地证据口径**（commit 在树 + 本地矩阵绿），二者是**门槛差非对错差**。**禁止为"对齐"而放宽看板口径**；等首个 CI 绿 run 出来再据实升 Done。（原“顺手把总表改 DONE”的 TODO 已废：它会诱导违反铁律。）

---

## 5. 🟢 绿灯区（已收口，别再当开放项摸）

连接/认证/影子地基几乎全绿，逐项证据在看板对应行：
- **影子/编译/冒烟地基**：I-DEBT-1 / 2 / 3 / 4 / 5 / 6 / 7A / 7A-2 / 7B
- **planner/执行器根治**：I-DEBT-10（GSI+JOIN 不收敛，两阶段执行模型，`1d47b9bee`）/ 11 / 12 / 13 / 14 / 15 / 16 / 17 / 18 / 19 / 20 / 21 / 22 / 23(23a/23b/23c) / 24
- **认证矩阵**：I-DEBT-9 的 9-A（后端认证，`BackendAuthMatrixTest` 能力感知）+ 9-B（前端认证，`FrontAuthMatrixTest`）；I-DEBT-37 后端面（`ConnectHandler` auth-switch/caching_sha2 RSA full-auth，`08c35dcd2` 收口）；I-DEBT-38（NATIVE 跨分片 SELECT 合并，`ObjectArrayDecoder` 实参顺序根，双层修+锁测 5/0/0）
- **M1 认证轴+操作轴本地矩阵**（2026-10-12 R5 收口后全 7 档重跑，四件套含 `CrudResultMatrixTest` 10 格）：**每档 FAIL=0** — 5.7=17/0/6 · 8.0=23/0/0 · 8.4=20/0/3 · 9.0=20/0/3 · MariaDB×3=17/0/6。三枚原红格 `bitColumnFidelity`(I-DEBT-34)/`timestampDiffFunctionCell`(I-DEBT-36 单片)/`divideByZeroIsCleanError`(I-DEBT-39) 经 R5 全绿，10 个 CRUD 格 × 7 档全 PASS。认证轴 N/A 为版本相关（8.0 有 caching_sha2 插件故 NA=0，5.7/MariaDB 无插件→合法 N/A）。权威报告 `.superpowers/sdd/2026-09-30-m1-verification-net/matrix-report.md`。
- **R5 CRUD 红族三根收口（2026-10-12，Evidenced-Done）**：Root A I-DEBT-39 列头惰性化使流式首行错回干净 ERR 会话存活（`f61775a88`）〔**口径边界（终审 #3）**：关的是**传输层不断连**；除零对外=NULL+Warning 的**值语义**属独立债，见 §2 I-DEBT-39·残，C10=错误路径格非对拍格〕；Root B I-DEBT-34 BIT 列对外官方 byte[] 口径 + 消行解码 byte[] 崩（`e35f44ba2`，对拍网 `94859304a`）〔**口径边界（终审 #1）**：仅**单片/下推直通列头**闭合；跨片归并/本地计划列头未闭合，见 §2 I-DEBT-34·残 + 红锁 `caseCrossShardBitColumnFidelity`〕；Root C I-DEBT-36 单片 TIMESTAMPDIFF 下推收割（`d94587d00`）+ C9 六单位/NULL 回归锁（`60016e3cc`；**终审 #2**：C9 数据已换跨月跨年不等间隔以获真鉴别力）。**跨片全计划 caseI36 另立专项**（见 §2 I-DEBT-36·残 + `c2-pushdown.md`）。
- **误判撤回**：~~I-DEBT-40~~（多版本宽度"不落物理"= 假红，prototype DDL 写读错配方法学伪影，2026-10-11 撤回）

---

## 6. 推荐关闭序（严格贴关键路径，不贴红的响度）

> 铁律：**A 层优先于 C 层，B 是 A 的前置仪器。** 引擎崩栈（C）再吵，也不插到前端 TLS/认证（A）前头——除非它挡住了 M2 的"基本 CRUD"门本身。

```
第 0 步（前置·B）：让验证网四轴齐 + 出公共背书
  I-CI-3 补 CRUD/结果集矩阵 ✅ 已完成（`CrudResultMatrixTest` 双轴 7 档全跑，2026-10-12）→ 认证轴+操作轴齐；红地图已出（三格版本无关引擎深债 I-DEBT-34/36/39，喂第 3 步）
  I-CI-4 接 MigrateTest → 迁移轴
  网络恢复后 I-CI-2 push fork 跑 Actions → CI 背书（把 M1 从本地✅推到 spec 定义的 Evidenced-Done）

第 1 步（招牌本体·A→M2）：前端连接达标，按依赖链
  I-VE-2 ✅（连接级 EOF 编码已架构完成+回归锁取证 2026-10-12；死码 `NewMycatConnectionImpl:305` 不改）→ I-AU-5 ✅ → I-AU-1 ✅（前端原生 caching_sha2）→ I-AU-3 ✅（RSA）→ I-AU-2（前端 TLS·对外最硬门，R4：配置/SSLRequest/Connector/J 传输升级已 Evidenced-Done `e01368405`；Task4 矩阵已建格 `TlsAxisMatrixTest`+判据口径/runbook 见 `2026-10-13-tls-compat-matrix.md`，全矩阵 e2e 真跑待 infra）
  并行评估：I-AU-6 / I-AU-4（后端 ed25519/多驱动，用户必做，独立于前端链，属 M6）

第 2 步（招牌本体·A→M3/M4）：
  M3 I-ME-1..4（mysqldump 往返，依赖 I-AU-1/I-VE-1）
  M4 I-RP-1（MigrateTest 接矩阵，随 I-CI-4）

第 3 步（地基债·C，靠第 0 步的 CRUD 矩阵出红地图后逐个专轮，不占主线名额）：
  ✅ **R5 已关**：I-DEBT-39（列头惰性化）/ I-DEBT-34（BIT byte[]）/ I-DEBT-36 单片下推面（×7 矩阵锤定）
  ⏳ **待办**：I-DEBT-35（BINARY/BLOB 应用 BIT 样板）→ **I-DEBT-36 跨片全计划 caseI36 原生透传（已另立专项，方向 A）** → 各独立根 → I-DB-1  （23e 已 `90ede2f86` 收口；I-DEBT-8 Rx 同族泛型化已关）

主线外（D）：I-PR-1/2/3（生产稳健，与"无缝替代"正交，独立排期）
```

**为什么这么排**：第 0 步先让"每关一债都能逐版本自动出红/绿证据"成立（治"我说了算"）；第 1–2 步全在 A 层，是把 M2/M3 判据一格一格推绿；第 3 步的引擎债之所以降到最后，是因为它喂的是"基本 CRUD"这条 M2 子门，但**前端连不上（无 TLS/sha2）比一条 SQL 崩栈更挡招牌**——客户端第一脚都迈进不来，谈不上跑查询。每完成一步，回仪表盘更新对应里程碑的完成度。
