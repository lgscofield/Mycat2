# I-DEBT-29-EXT-CONN｜connector 矩阵全 7 persona 扩面（§7 第五列 ◐→☑）—— 设计 Spec

- 状态：🟡 执行中（2026-10-30 立项开工；2026-10-31 T2 全档基线实跑完、T3 红项裁决完、T4 深面随档收口、T5 护栏实跑完，余 T6 回写+提交）
- 债务票：`DEBT-QUEUE.md` §1（本票收口后把 §7 第五列 connector 矩阵由 ◐×7 升 ☑×7 判据面；M2 余项本体）
- 前置：
  - Java 线 Connector/J 9.x 全 7 档 CI 绿（`CrudResultMatrixTest` 10 格 × 7 档，`run-matrix` 配方）——**本票不重复测 Java 线**；
  - 非 Java 四语言（Go/Python/C/.NET）明文基本 CRUD 已解 **MYSQL_8_0 一档**（2026-10-15，`polyglot-report.md`）；
  - 元数据 GUI 全 7 档刚由 I-DEBT-29-EXT 收口（`6c7d481c1`），infra persona 矩阵配方沿用。
- 判据面：`2026-09-30-mysql-mariadb-compat-PROGRESS.md` §7 验收面第五列「connector 矩阵」+ 主 spec §3 M2 "全 connector 跑基本 CRUD" + `2026-10-12-connector-compat-provision.md` runbook §3。

## 1. 问题与目标

第五列现状 ◐×7 的失真根源 = **非 Java 四语言只在 persona=MYSQL_8_0 一档实证过**；
且深面（预编译/结果集边缘）仅 go/C 两语言在 TLS 场景点亮，python/dotnet 深面全档 NO_DATA，
dotnet 尚欠 I-DEBT-48（分片 INSERT 具名/NULL variant(index=-1) 硬崩）的明文侧定性。
本票目标：把「基本 CRUD」判据扩到全 7 persona 档逐语言实证，并按判据把深面补齐或据实挂账——

- MySQL 5.7/8.0/8.4/9.x + MariaDB 10.6/10.11/11.4 七档，各起**同版后端容器**、
  `-Dmycat.persona=<对应画像>`，跑四语言冒烟（现有 smoke 已全 env 参数化，零改写即可挂档）；
- 每语言每档 = 明文基本面（判据本体）+ 深面格（`MYCAT_DEEP=1`：预编译/参数绑定、引号字面量、
  NULL 往返、跨片有序归并）+ 字符集保真（smoke 内建 utf8mb4 回环，中文+emoji）。

## 2. 方案（复用已证资产，零新轮子）

- **测试主体**：`.superpowers/sdd/2026-10-15-connector-polyglot/smoke/{go,py,c,dotnet}` 四探针原封复用
  （env 缝 `MYCAT_HOST/MYCAT_PORT/MYCAT_BACKEND_HOST/MYCAT_BACKEND_PORT/MYCAT_DEEP` 已具备）。
- **矩阵驱动**：新建一次性 `run-conn-matrix.ps1`（复刻 `run-meta-matrix.ps1` 配方并加严）：
  ① 逐档先杀 8066 残留；② `docker rm -f m1-mysql-a m1-mysql-b` 重起**同版双后端**
  （smoke 的分片建表需 c0(3306)+c1→ds_smoke(3307) 两库，`dbpartitions 2` 必跨库归并）；
  ③ `-Dmycat.persona=$P` 起服（mariadb 三档用 `MARIADB_*`）；④ 逐语言 docker 容器跑格
  （go=plaintext+deep、python=plaintext+deep、c=plaintext+deep、dotnet=plaintext+deep），
  每语言批前 `sanitize_pre`、批后 `sanitize_post` 清污染（storeNum 红线）；
  ⑤ 每档首格前跑 `*_negctl`（错端口须 FAIL@connect）证判别力；⑥ 解析 `POLYGLOT_RESULT` 行留证据痕。
  **收尾必须按目标 persona 重建 m1-mysql-a/b 默认档容器**（本轮护栏 example 依赖 MYSQL_8_0 后端实况）。
- **判别力锁**：负控格 + "FAIL 须打到 connect 层"口径沿用 v2 驱动（防编译失败冒充绿）。
- **产物归属**：驱动与日志置 `.superpowers`（一次性，不入库）；结论回写看板两件套 + 本 Spec 转态。
  把 polyglot 探针**入库固化为准，CI 化** = workflow/资产治理变更，本票不做，留拍板（边界 E3）。

## 3. TDD 定位说明（本票特殊性）

本票主体是**外部 connector 的实证矩阵**（测试资产已存在且被 10-15 轮验证），无新建产品码预期；
"RED-first"体现为：先逐档实跑取**新鲜基线**（当前 6 档为未知态，跑出什么报什么），
红项逐名定性后若坐实**产品破口**，则按标准 TDD 循环（离线/活体锁 RED → 产品码 GREEN → 护栏）修复，
宁挂账不伪绿。dotnet 深面若复现 I-DEBT-48 即按该票既有账归因，不重复立案。

## 4. 验收判据

- A1：7 档 × 4 语言 **明文基本 CRUD** 全 PASS（每格有 `POLYGLOT_RESULT status=PASS` 新鲜证据痕）；
- A2：7 档 × 4 语言 **深面格**（`MYCAT_DEEP=1`）全 PASS 或红项逐个立案归因（不允许静默剔除）；
- A3：每档 negctl 判别力格 FAIL@connect（防串档/防装配失败冒绿）；
- A4：字符集保真（中文+emoji 回环）随 CRUD 格全绿；
- A5：若产生产品码改动 → 受影响模块护栏 + example 全量零新增红；零改动则本条记不适用；
- A6：§7 第五列 ◐→☑ 的回写口径**仅含本票实证面**（明文 CRUD + 深面 + 字符集），
  旧"各语言深面×TLS"与"旧 Java 5.1/8.x 线"不在本票判据内，维持既有挂账。

## 5. 边界（明示不承诺）

- E1：MariaDB 后端接受 Connector/J 的 `jdbc:mysql://` URL（协议兼容，`run-matrix` mariadb 档
  CRUD 全绿先例 + I-AU-6 证 `jdbc:mariadb://` 亦通）；若实跑撞认证/方言红，据实立案不预修。
- E2：connector **版本矩阵**（旧 5.1/8.x Java 线、非 Java 各语言多版本）不在本票——本票档位 = 语言官方当前线。
- E3：探针入库 + CI 化 = 资产治理/workflow 变更，需用户拍板，本票只做本地实证与看板回写。
- E4：mysqldump/迁移轴的 connector 面不在本票（各属独立列/票，已收口）。
- E5：主机零装承诺不变——一切客户端跑在 docker 容器（golang:1.24-alpine / python:3.12-alpine /
  gcc:13-bookworm / dotnet sdk:8.0），网络依赖（goproxy.cn/pip/apt/nuget）若断则对应格 NO_DATA 如实报。

## 6. 任务序

- T1 驱动实现 + MYSQL_8_0 档烟测（资产已证档先跑通装配，防驱动本身带病）
- T2 全 7 档矩阵基线实跑（明文 + 深面），逐档留证据痕 → 红名单逐名定性（本票"RED 目视"）
- T3 产品破口（如有）另立案或本票 TDD 修复；环境红按既有口径归因不冒充
- T4 护栏（视改动面）+ 可证伪复核（摘修法必红）
- T5 看板回写：§7 第五列打格 + DEBT-QUEUE 登记/收口 + 本 Spec 转态 + commit

## 7. 红项裁决（T3 收口 · 2026-10-31）

全档基线见 `conn-matrix-report.md`（逐格 `POLYGLOT_RESULT` 证据痕）。真红仅 4 格：
`dotnet_plain`/`dotnet_deep` × MARIADB_10_11 与 × MARIADB_11_4。裁决：

- **定性 = MariaDB `SHOW COLLATION` 的 uca1400 物化视图保真缺口 + 驱动零 NULL 容忍**，非 Mycat 破口。
  驱动 `Driver.LoadCharacterSets` 对每行 `Convert.ToInt32(reader["id"])`；MariaDB 10.11/11.4 对 184 个
  uca1400 模板行有**两档渲染**：模板档（`Charset`/`Id`=NULL，官网文档口径，506 行）与物化档
  （按 utf8mb3/utf8mb4/ucs2/utf16/utf32 展开，1242 行、id 从 2048/2304/2560/2816/3072 起）。
  Mycat 经 Connector-J 取数并透传**文档档**，与 CLI/PyMySQL/Go/mysql:8.0 客户端/Connector-J 五方逐行一致；
  仅 MySql.Data 直连 MariaDB 拿物化档。**天然对照**：MariaDB 10.6（无 uca1400）同驱动 PASS。
- **推翻推导期的"版本串缺 `5.5.5-`"假设**：后端 general_log 坐实驱动直连真后端也发同一条 `SHOW COLLATION`
  且成功；`MySQLDatabaseMetadata.parse()` 的前缀假设是另一处真实缺陷（major/minor 归零），但不改变能力位、非崩因。
- **否决修法**：补版本串前缀（与崩因无关）/ 删 NULL 行（打红 D2 超集断言）/ 换 persona 精选子集表（同上）/ 靠后端池
  能力位复现（Connector-J 无开关，已试 3 能力位 + 4 会话字符集均 506/184）/ 自造 2048+ 展开行（无文档逆推，伪绿）。
- **落账**：另立 **I-DEBT-70**（A 层·M2/M6 MariaDB 扩展位），含"维持透传 vs 按版本复现物化视图"两方向待拍板。
- **§7 第五列据实**：MySQL 5.7/8.0/8.4/9.x + MariaDB 10.6 = ☑（四语言明文+深面全绿）；
  MariaDB 10.11/11.4 = ◐（仅 dotnet 两格红，已定性挂票）——**不冒全 ☑**（判据 A2 的"红项逐个立案归因"已满足）。

## 8. 护栏（T5 收口 · 2026-10-31）

- **受影响模块套**：`hbt` 277/0/0、`server` 100/0/0（含 `PersonaCollationsTest` 与 `SHOW CHARACTER SET` 别名格）。
- **example 全量**：`mvn -o -B -pl example test "-Dcompat.db=mysql:8.0"` → `Tests run: 545, Failures: 2, Errors: 35, Skipped: 31`（基线产物 `.tmp-example-full-ext2.log` = 544/0/0/36）。
- **37 个红项全部归因两个环境族，无一产品红**：
  1. XA 族 36 红（LocalXa(Savepoint) / OnePhaseXa(Savepoint) 四类）：后端缺 `mycat.xa_log`（全新容器）→
     `LocalXaSqlConnection.commit()` 的协调者日志写失败（1146）却被 `transform` 吞掉 → ds2 分支未提交（0 行）→
     首个失败不回滚，持锁事务还回池（SQL `begin;` + JDBC autoCommit 仍 true，Druid 不 rollback）→ 后续 `clearData()`
     全员 `Lock wait timeout`（50 s= `innodb_lock_wait_timeout`）。创建者 `XaLogImplTest` 在队尾跑，表补上后才可复绿。
  2. ed25519 1 红：`m6-mariadb-a`(3308) 执行时未起 → `Communications link failure`。
- **AB 干预复跑**：表就位 + 3308 起来后，这 5 类定向复跑 `81/0/0/4`、BUILD SUCCESS（601.4 s → 5.3 s）。
- **口径**：未重跑第二轮全量（成本 ~45 min），红项 100% 覆盖复跑、其余 464 项同轮全绿；另立 **I-DEBT-71**（测试隔离前置）与
  **I-DEBT-72**（`LocalXaSqlConnection` 吞提交失败 + 锁泄漏，生产 `JDBC_TRANSACTION_TYPE` 在用）。
