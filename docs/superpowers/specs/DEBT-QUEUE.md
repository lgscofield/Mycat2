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
| M1 验证网 | 矩阵出 pass/fail + CI 绿 | 🟢 ~85% | 认证轴+**操作轴(CRUD)双轴 7 档全跑绿** + 已 push fork（2026-10-12）；剩 CI 徽标（卡在启用 fork Actions）+ connector 轴（Connector/J 9.x 已 PASS，Go/Python/C/.NET 已建格记 NO_DATA+provision runbook 就绪，真跑属 I-DEBT-9 polyglot/docker 专轮，触装运行时红线）|
| **M2 L1 连接** | native+sha2、TLS on/off、全 connector | 🟡 **认证轴✅ · TLS 传输实证+矩阵建格 · connector 真跑待 infra** | 前端 I-AU-1/3/5 已落（native + caching_sha2 原生 fast/full-auth，2026-10-12 R3 live 8.0/8.4/9.0 实证）；I-AU-2 TLS：配置/解析/Connector/J 传输升级 Evidenced-Done（`e01368405`），e2e 矩阵与非 Java connector = NO_DATA 待 infra（`2026-10-13-tls-compat-matrix.md`）|
| M3 L2 工具 | 各版本 mysqldump 往返回灌 | 🟡 ~60% | **I-ME-1 已落**（persona 驱动三张 i_s 表，锚官网，TDD 6/6）；**I-ME-2 双半区已落**（列覆盖锁 8/8 + FILES 补四列，e2e 矩阵格 NO_DATA 待 infra）；**I-ME-3 进程内已落**（p_s 列面+行面锁 8/8，锚 8.0.42 源码，rgm 补列+变量/状态同源行供应）；**I-ME-4 已落**（透传路径回归锁 10/10，`707c9a5bc`，锚官网 §15.7.7.23/36） |
| M4 L3 迁移 | MigrateTest 矩阵绿 | 🟡 ~20% | 本地修绿、未接矩阵 |
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
| I-DEBT-8 | Rx 算子 `filter/topN/sort/toEnumerable/materialize` 仍声明 `Observable<Object[]>`、合成桥烘焙 `(Object[])` 输入强转（`[B`→ByteString CCE 面已收口，此面 TODO） | 有界偏中 | C |
| I-DEBT-35 | BINARY/BLOB 列 codegen 声明面 ByteString vs 运行时 byte[] 错配（牵连 RexToLix 字面量 + 结果端字节保真，面大）。Root B 的 BIT byte[] 样板已立（I-DEBT-34 收口），BINARY/BLOB 应用留另轮 | 挂账 | C |
| I-DEBT-36·残 | `TIMESTAMPDIFF(unit,t1,t2)` **跨片全计划**去回环重解析后落标准算子→convertlet 展平成含 `REINTERPRET`(`SqlSyntax.SPECIAL`) 算术→Mycat dialect 无法 deparse（红锁 `UserCaseTest#caseI36`）。单片下推面已 R5 收口 ×7 绿（见 §5）；跨片正解=方向 A「原生三参整体下推」，2026-10-12 用户拍板**另立专项**、本里程碑不强推。根因图见 `.superpowers/sdd/2026-10-12-r5-crud-debt-roots/c2-pushdown.md` | 架构·特性级 | C |
| I-DEBT-34·残 | **终审 Important #1**：BIT 列对外官方 byte[] 口径**仅单片/下推直通列头闭合**（Root B，见 §5）。跨片归并/本地计划列头走 `CalciteRowMetaData.getColumnType`（取 Calcite RelDataType jdbcOrdinal），BIT 经 fork 类型系统（无 `BIT` SqlTypeName）归一为**数值**列头、失 BIT 性，而行值仍是后端原生 byte[]→`BaseRowObservable.getObjects:355` 数值分支崩（live 复现 `UnsupportedOperationException:[B@`）。即使只透传不崩，对外列头仍为数值而非 `FIELD_TYPE_BIT`、官方口径仍未守。修复须联动 fork 类型系统/归并列头来源 = **“类型表征统一”专轮**（与 I-DEBT-36 跨片 caseI36 同族）。红锁 `UserCaseTest#caseCrossShardBitColumnFidelity`（live RED） | 架构·特性级 | C |
| I-DEBT-39·残（除零对外语义） | **终审 Important #3**：I-DEBT-39 已关的是**传输层**（运行期错后会话不被 2013 打挂，C10 钉此）。MySQL 于 SELECT 期 `1/0` 语义=**NULL + Warning 1365**（仅 strict 模式下 DML 才升 1365），Mycat 此处返回 SQLException = 值语义分歧。**除零“对外返回什么”属独立语义对拍债**，不由 C10 宣称兼容（C10=错误路径格、非对拍格）。 | 挂账·语义对拍 | C |
| I-DEBT-23e | `CURTIME()+0` TIME→NUMERIC：`Duration→TIME` java 反查映射缺，落 OTHER 不可 coerce | 有界 | C |
| （7C 分诊·独立根） | `ClientTest.testDBeaver`=druid 相邻 `@@` 变量丢逗号（上游/或需裁定破"不 fork druid"）；函数签名族 BIN/CONV 严格 overload；`information_schema ORDER BY` 重复列名 ambiguous；`BlobTest` BOM/EFBFBD 字节保真；`RwTest` 分片越界；`HbtTest` 测试隔离(先 DROP)+PARK VARCHAR 影子 | 各独立·多为架构 | C |

---

## 3. ⚪ 里程碑后段（M1 收尾背书 + M3/M4/M5）

**M1 尾巴（验证网）**
| ID | 一行说明 | 分类 | 对齐 |
|---|---|---|---|
| I-CI-2 | 多库服务矩阵：本地全矩阵已跑通并出权威表；compat workflow 已**快进 push 到 fork**（`a29744e68`，R0 四件套上树）；**唯一没做 = 启用 fork 的 Actions**（历史 total_runs=0⇒默认关，需用户去 settings/actions 开）后跑公共背书 | 背书·BLOCKED | **B** |
| I-CI-3 | 兼容性 harness：认证轴 11 格 + **操作轴 CRUD/结果集 10 格（`CrudResultMatrixTest`）双轴本地 7 档全跑绿**（2026-10-12）；三格版本无关红=引擎深债 I-DEBT-34/36/39（落第 3 步专轮，非版本兼容缺口） | 有界·本地✅ | **B** |
| I-CI-4 | MigrateTest 接矩阵（迁移轴） | 有界 | **B** |
| I-DEBT-9 | example 测试面结构性健全：9-A/9-B 认证矩阵✅；剩 mysqldump 往返矩阵 0 覆盖；**TLS 轴已建格**：`TlsAxisMatrixTest`（sslMode 严格性判据+三层诚实门控，编译过）+ `2026-10-13-tls-compat-matrix.md`（on/off×7 persona NO_DATA 矩阵+runbook）；**connector 轴已建格**：2026-10-12 出 `2026-10-12-connector-compat-provision.md`—Connector/J 9.x=PASS、Go/Python/C/.NET=NO_DATA+官网锚定 provision runbook 就绪；真跑 polyglot/docker 网触装运行时红线待用户拍板） | 挂账 | **B** |

**M3 元数据/mysqldump（L2）【A】**：I-ME-1 排序规则表补全 **Evidenced-Done**（persona 驱动三张 i_s 仿真表，`PersonaCollations`+回归锁 `PersonaCollationsTest` 6/6，行值锚官网；SHOW COLLATION 回退分支共用本表口径同源；MariaDB 11.4 默认仍 general_ci，uca1400 属 11.5+） · I-ME-2 information_schema dump 面 **进程内 Evidenced-Done**（`3cc944dba`：官网 mysqldump.cc 8.0/8.4/MariaDB 11.4 逐行锚定查询面；回归锁 `DumpSurfaceRegressionTest` 8/8；FILES 仿表补 TOTAL_EXTENTS/INITIAL_SIZE/MAXIMUM_SIZE/AUTOEXTEND_SIZE；e2e 矩阵格 `DumpSurfaceMatrixTest` NO_DATA 待 infra；诚实结论：三引擎 dump 源码均不查 CHECK_CONSTRAINTS，不虚构缺口） · I-ME-3 performance_schema 完整 **进程内 Evidenced-Done**（`c35c14803`：列面逐锚官网 mysql-8.0.42 标签 storage/perfschema/table_*.cc Plugin_table Definition；rgm 补齐 MEMBER_COMMUNICATION_STACK+MEMBER_HOST 对齐官方；p_s session/global_variables 与 i_s 同源行供应，setup_consumers 官方 16 行集；MGR 心跳行仿真属后端复制 persona 语义层不入本票） · I-ME-4 SHOW MASTER/SLAVE STATUS **Evidenced-Done**（`707c9a5bc`：透传路径回归锁 `ShowBinlogStatusRoutingTest` 10/10；锁定 AST 类型识别+MySqlShowStatement 接口路由+toString 转发文本正确性；锚官网 MySQL 8.0 §15.7.7.23 SHOW MASTER STATUS 5 列/§15.7.7.36 SHOW SLAVE|REPLICA STATUS；e2e 由 DumpSurfaceMatrixTest SHOW MASTER STATUS 格覆盖 NO_DATA 待 infra）
**M4 迁移/复制（L3）【A】**：I-RP-1 迁移流水线修复+CI · I-RP-2 binlog 客户端维护 · I-RP-3 文档化排除 N1（均 TODO）
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

> ⚠ 待办：顺手把总表 L94（I-VE-1）、L96（I-VE-3）两行改 DONE + 填 commit 证据，避免下轮又被过期表误导。（本队列先如实反映，看板回写留你点头后做。）

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
  ⏳ **待办**：I-DEBT-8 → I-DEBT-35（BINARY/BLOB 应用 BIT 样板）→ **I-DEBT-36 跨片全计划 caseI36 原生透传（已另立专项，方向 A）** → 23e → 各独立根 → I-DB-1

主线外（D）：I-PR-1/2/3（生产稳健，与"无缝替代"正交，独立排期）
```

**为什么这么排**：第 0 步先让"每关一债都能逐版本自动出红/绿证据"成立（治"我说了算"）；第 1–2 步全在 A 层，是把 M2/M3 判据一格一格推绿；第 3 步的引擎债之所以降到最后，是因为它喂的是"基本 CRUD"这条 M2 子门，但**前端连不上（无 TLS/sha2）比一条 SQL 崩栈更挡招牌**——客户端第一脚都迈进不来，谈不上跑查询。每完成一步，回仪表盘更新对应里程碑的完成度。
