# 开放债务队列（驾驶舱 · 只列未关项）

> **这是什么**：Mycat2「客户端眼中**无缝替代 MySQL** 5.7/8.0/8.4(LTS)/9.x(Innovation)，**并为 MariaDB 10.6/10.11/11.4(LTS) 预留扩展位**」招牌目标的**唯一开放项队列**。每轮对话开头扫一眼这里就知道整头象、点一格往下关，不用再从 296 行散文中重新拼。
>
> **怎么用**：本文件只放🔴**真正没关的**。已收口的不在此列（去 §5 绿灯区指针查）。"下一步"永远 = 关闭序里最靠前的、依赖全已满足的那一格。
>
> **深度证据在哪**：本队列是**索引**，不是证据本体。逐格的红/绿判定、file:line、实跑日志一律回看权威看板 `2026-09-30-mysql-mariadb-compat-PROGRESS.md`（进度总表 L52–117）+ 对应 SDD 账本 `.superpowers/sdd/*/progress.md`。
>
> **校准**：2026-10-12（凭当前 `git log` + 总表逐行只读复核，非凭记忆）。

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
| M1 验证网 | 矩阵出 pass/fail + CI 绿 | 🟢 ~80% | 认证轴+**操作轴(CRUD)双轴 7 档全跑绿**（2026-10-12）；剩 connector 只测 Connector/J（目标含 5 类）+ CI 背书未 push |
| **M2 L1 连接** | native+sha2、TLS on/off、全 connector | 🔴 **<10%** | 前端 I-AU-1/2/3/5 全没动；仅 native 降级通路通 |
| M3 L2 工具 | 各版本 mysqldump 往返回灌 | 🔴 0% | I-ME-1..4 全 TODO |
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
| **I-VE-2** | EOF 能力位连接级化，让 VersionProfile persona 在连接层真正生效 | I-VE-1 ✅（已提交，见 §4）→ **本格已解锁，可直接开工** | 有界 | **A** |
| I-AU-1 | 前端 caching_sha2 完整协商（现在靠 0xFE 强降 native 蒙混，非原生执行 fast/full-auth） | I-CI-3 本地✅ / I-VE-1 ✅ | 架构 | **A** |
| **I-AU-2** | **前端 TLS 没实现**——云/托管 DB、`require_secure_transport=ON` 直接连不上；招牌最硬对外缺口 | I-CI-3 本地✅ / I-VE-2 ⬜（未做） | 架构 | **A** |
| I-AU-3 | 非 TLS 下 RSA 公钥检索（配合前端 sha2 full-auth） | I-AU-1 ⬜ | 架构 | **A** |
| I-AU-5 | 按版本画像切换宣称的认证插件（persona 驱动握手通告） | I-VE-1 ✅ | 有界偏中 | **A** |
| I-AU-4 | 后端 ed25519（MariaDB `client_ed25519`） | I-VE-1 ✅ / M2 ⬜ | 架构·BLOCKED | **A**(M6) |
| **I-AU-6** | **后端 ed25519/多驱动**：mysql-connector-j 不支持 `client_ed25519`（实测 `Unable to load authentication plugin`），需引入 MariaDB Connector/J 或驱动 SPI 按后端类型选驱动。**用户明确"必做"** | I-AU-4 ⬜ | 架构 | **A**(M6) |

> 前端认证 posture 的真根（活路径 `VertxMySQLAuthHandler`：通告 native + 0xFE 强降、只做 scramble411）已钉在看板 I-DEBT-37 前端面；I-AU-1/3/5 就是把它从"降级蒙"升级成"原生协商"。

---

## 2. 🟡 8.0 深债红族（M2「基本 CRUD 全绿」门槛——example e2e 实测还红的）

| ID | 一行说明 | 分类 | 对齐 |
|---|---|---|---|
| I-DEBT-7C | example 全量重基线（当前 479 tests / 7F / 63E / 30skip）——下面这几族的收口总账 | 挂账·WIP | C |
| I-DEBT-8 | Rx 算子 `filter/topN/sort/toEnumerable/materialize` 仍声明 `Observable<Object[]>`、合成桥烘焙 `(Object[])` 输入强转（`[B`→ByteString CCE 面已收口，此面 TODO） | 有界偏中 | C |
| I-DEBT-34 | BIT(n>1) 列 codegen 声明面 Boolean vs 运行时 Long 错配（`knownGap` 已钉现状，无 e2e 覆盖） | 挂账 | C |
| I-DEBT-35 | BINARY/BLOB 列 codegen 声明面 ByteString vs 运行时 byte[] 错配（牵连 RexToLix 字面量 + 结果端字节保真，面大） | 挂账 | C |
| I-DEBT-36 | `TIMESTAMPDIFF(unit,t1,t2)` 下推 deparse 不支持 Calcite interval/内部算子（`REINTERPRET` SPECIAL unparse 抛）；正解=建原生三参透传通路。特性分支 `fix/i-debt-36-timestampdiff` 未合主干 | 架构·特性级 | C |
| I-DEBT-39 | 多语句运行期错误（如 `select 1/0`）以 ERR 2013 断连终止会话，而非干净 MySQL ERR 包（规划期错误则正常回 ERR，两条路径不一致） | 挂账 | C |
| I-DEBT-23e | `CURTIME()+0` TIME→NUMERIC：`Duration→TIME` java 反查映射缺，落 OTHER 不可 coerce | 有界 | C |
| （7C 分诊·独立根） | `ClientTest.testDBeaver`=druid 相邻 `@@` 变量丢逗号（上游/或需裁定破"不 fork druid"）；函数签名族 BIN/CONV 严格 overload；`information_schema ORDER BY` 重复列名 ambiguous；`BlobTest` BOM/EFBFBD 字节保真；`RwTest` 分片越界；`HbtTest` 测试隔离(先 DROP)+PARK VARCHAR 影子 | 各独立·多为架构 | C |

---

## 3. ⚪ 里程碑后段（M1 收尾背书 + M3/M4/M5）

**M1 尾巴（验证网）**
| ID | 一行说明 | 分类 | 对齐 |
|---|---|---|---|
| I-CI-2 | 多库服务矩阵：本地全矩阵已跑通并出权威表；**唯一没做 = push fork 跑 Actions 公共背书**（卡 github.com 网络 + 用户凭据） | 背书·BLOCKED | **B** |
| I-CI-3 | 兼容性 harness：认证轴 11 格 + **操作轴 CRUD/结果集 10 格（`CrudResultMatrixTest`）双轴本地 7 档全跑绿**（2026-10-12）；三格版本无关红=引擎深债 I-DEBT-34/36/39（落第 3 步专轮，非版本兼容缺口） | 有界·本地✅ | **B** |
| I-CI-4 | MigrateTest 接矩阵（迁移轴） | 有界 | **B** |
| I-DEBT-9 | example 测试面结构性健全：9-A/9-B 认证矩阵✅；剩 TLS / mysqldump 往返 / 多 connector 矩阵 0 覆盖 | 挂账 | **B** |

**M3 元数据/mysqldump（L2）【A】**：I-ME-1 排序规则表补全 · I-ME-2 information_schema dump 面 · I-ME-3 performance_schema 完整 · I-ME-4 SHOW MASTER/SLAVE STATUS（均 TODO，多依赖 I-VE-1✅/I-AU-1）
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
- **M1 认证轴+操作轴本地矩阵**（2026-10-12 全 7 档重跑，四件套含 `CrudResultMatrixTest`）：5.7=14/3/4 · 8.0=18/3/0 · 8.4=15/3/3 · 9.0=15/3/3 · MariaDB×3=14/3/4。**每档 FAIL 恒=3 且跨全部后端版本完全一致**——即三格版本无关引擎深债 `bitColumnFidelity`(I-DEBT-34)/`timestampDiffFunctionCell`(I-DEBT-36)/`divideByZeroIsCleanError`(I-DEBT-39)，非版本兼容性缺口；其余 7 个 CRUD 格（int/varchar·update/delete·decimal·date/datetime·varbinary·跨片聚合·跨片 limit/offset）7 档全绿。认证轴 N/A 为版本相关（8.0 有 caching_sha2 插件故 NA=0，5.7/MariaDB 无插件→合法 N/A）
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
  I-VE-2（已解锁·有界起手）→ I-AU-5 → I-AU-1（前端原生 caching_sha2）→ I-AU-3（RSA）→ I-AU-2（前端 TLS·对外最硬门）
  并行评估：I-AU-6 / I-AU-4（后端 ed25519/多驱动，用户必做，独立于前端链，属 M6）

第 2 步（招牌本体·A→M3/M4）：
  M3 I-ME-1..4（mysqldump 往返，依赖 I-AU-1/I-VE-1）
  M4 I-RP-1（MigrateTest 接矩阵，随 I-CI-4）

第 3 步（地基债·C，靠第 0 步的 CRUD 矩阵出红地图后逐个专轮，不占主线名额）：
  I-DEBT-8 → I-DEBT-34/35 → I-DEBT-36 → I-DEBT-39 → 23e → 各独立根 → I-DB-1

主线外（D）：I-PR-1/2/3（生产稳健，与"无缝替代"正交，独立排期）
```

**为什么这么排**：第 0 步先让"每关一债都能逐版本自动出红/绿证据"成立（治"我说了算"）；第 1–2 步全在 A 层，是把 M2/M3 判据一格一格推绿；第 3 步的引擎债之所以降到最后，是因为它喂的是"基本 CRUD"这条 M2 子门，但**前端连不上（无 TLS/sha2）比一条 SQL 崩栈更挡招牌**——客户端第一脚都迈进不来，谈不上跑查询。每完成一步，回仪表盘更新对应里程碑的完成度。
