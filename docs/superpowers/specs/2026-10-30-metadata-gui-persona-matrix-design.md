# I-DEBT-29-EXT｜元数据 GUI / DBeaver 面全 7 persona 扩档 —— 设计 Spec

- 状态：✅ Closed（2026-10-30 全 7 persona 档收口·本地 runtime-evidenced·CI⏳；7 档 × 5 格 = 35 格活体全 PASS + `@@version` 透传后端真值破口修复，证据 `.superpowers/sdd/2026-10-30-metadata-gui-persona-matrix/metadata-matrix-report.md`；原 Draft 立项如下）
- 债务票：`DEBT-QUEUE.md` §1 I-DEBT-29-EXT（自 I-DEBT-29 拍板范围拆出）
- 前置：I-DEBT-29 已收口 MYSQL_5_7 一档（commits `039b97eb4`，修法 A = `DrdsRunnerHelper.preParse` 入口对整棵 AST 的 `SQLVariantRefExpr.setHasPrefixComma(true)` 属性归一）
- 判据面：`2026-09-30-mysql-mariadb-compat-PROGRESS.md` §7 验收面第六列「元数据 GUI」

## 1. 问题与目标

第六列现状 = 1☑/6☐：DBeaver 元数据 12 步序列仅在 `persona=MYSQL_5_7` 活体取证过。元数据仿真按 persona 驱动
（`PersonaCollations` 的 CHARSET/COLLATION 行集分 MySQL/MariaDB 系；版本号串、i_s 列集亦有版本差），
**不可由 5.7 外推**。本票目标：把该列逐档点亮或据实挂账——

- MySQL 8.0 / 8.4 / 9.x + MariaDB 10.6 / 10.11 / 11.4 六档，各以**同版后端**起 infra、
  `-Dmycat.persona=<对应画像>` 跑同一套 DBeaver 元数据活体序列；
- 每档加**persona 差分格**（直连同版后端为 oracle 的对拍），钉「按 persona 仿真」的真实行为，
  而非只验「不崩」。

## 2. 方案（复用已证 infra 配方，零新轮子）

### 2.1 活体测试缝（example/compat，JUnit5，矩阵惯例同 `CrudResultMatrixTest`）

新建 `io.mycat.compat.MetadataGuiMatrixTest`：
- **infra 守卫**：读 `-Dcompat.db=<image>`（矩阵驱动传入），探测 8066 与 3306 后端；后端 `select version()`
  与 `compat.db` 版本前缀不符 → `abort`（NO_DATA，绝不假绿）；persona 由服务端启动侧
  `-Dmycat.persona` 决定，测试断言 `select @@version` 与 persona 自述版本串前缀一致（**同时坐实服务端
  persona 确实生效**，防"忘传 persona 拿 5.7 画像冒充他档"的串档假绿）。
- **活体序列格**：12 步 DBeaver 序列逐条重放（SET autocommit 带 ApplicationName 注释、SHOW ENGINES/CHARSET/
  COLLATION/PLUGINS/VARIABLES、`SELECT @@GLOBAL.character_set_server, @@GLOBAL.collation_server`（含
  1 行 2 列非空值断言，即 I-DEBT-29 卖灯修复形）、`SELECT DATABASE()`、`show databases`、i_s.TABLES）。
  其中 `@@GLOBAL.x, @@GLOBAL.y` 逗号格是**跨 persona 的 reprint 回归锁**——修法 A 若在非 5.7 画像下被
  persona 驱动路径绕开/失效，此格即红。
- **persona 差分格**（oracle = 直连 3306 同版后端）：
  - D1 `select @@version`：Mycat 值以 persona 版本串为前缀（如 MARIADB_10_11 → `10.11.x-MariaDB` 口径）；
  - D2 `SHOW COLLATION`：utf8mb4 general_ci 行必存在；行数与后端差不作硬断（Mycat 为仿真行集），但
    **后端有的 utf8mb4 系 collation Mycat 必报**（子集断言，按 persona 系别取 MySQL/MariaDB 差异口径）；
  - D3 `SHOW CHARACTER SET`：utf8mb4 行存在且 DEFAULT COLLATION 与 D2 报的默认 collation 自洽。

### 2.2 矩阵驱动（一次性脚本，`.superpowers/` 下，不入库）

PowerShell 复刻 `run-matrix.ps1` 配方并**加严两处**：
1. **逐档先杀 8066 残留监听**（当前存在上轮 PID 13740，不清端口 = 后档拿前档服冒烟 = 假绿事故源）；
2. persona 映射扩全 7 档：`mysql:5.7→MYSQL_5_7 … mariadb:10.6→MARIADB_10_6、10.11→MARIADB_10_11、11.4→MARIADB_11_4`
   （旧 run-matrix 对 mariadb 一律盖 MYSQL_5_7，本票不允许——第六列测的就是 persona 仿真本体）。
每档：`docker rm -f m1-mysql-a/b` → 起同版双实例（仅用 a:3306）→ `Ready-Backend` → fat-jar（**含修法 A 的
现产物 `server-1.22-release-jar-with-dependencies.jar`，`039b97eb4` 后构建，本票零产品码则不重建**）
起 8066 `-Dmycat.persona=$P` → `mvn -o -B -pl example test -Dcompat.db=$V -Dtest=MetadataGuiMatrixTest`
→ 清 surefire 残留防串档 → 解析 XML 出逐格 PASS/FAIL/NO_DATA → 杀 8066。

### 2.3 离线锁扩档（hbt）

`VariantReprintFidelityTest` 矩阵补 MariaDB 方言形态变体（如 `SELECT @@GLOBAL.sql_mode, @@GLOBAL.character_set_server`
小写/大写混排 + `@` 前缀组合），先 RED（若现状已绿则以变异核验：摘修法 A 必红）再定性是否需产品改动。
**预期本票主体不改产品码**——修法 A 在解析层与 persona 正交；改的是测量网。

## 3. RED-first 与判别力核验（测量网 ≠ 免检绿）

- 差分格 D1/D2/D3 若"写出来就全绿"，须各做一次**变异核验**（临时把断言 oracle 改坏一位，如版本前缀换串、
  剔除必存在 collation 名，确认锁咬人后还原），核验痕记入证据档——防止度量网本身是无断言空转。
- 逐档序列格红项裁决树：签名含 `token VARIANT`/reprint 形态 → 修法 A 跨 persona 失效，本票内修；
  persona 仿真差异（列集/行集/插件名）→ 独立破口，**另立债务票**，该档在 §7 维持 ☐ 不冒绿；
  环境族（连接超时等）→ 定性重跑，不计产品红。

## 4. 验收判据（全满足才关票）

1. `MetadataGuiMatrixTest` × 7 persona 档逐档实跑 **0 FAIL**（`Tests run>0`、`Skipped=0`，NO_DATA=绿不成立）；
2. persona 差分格 D1/D2/D3 逐档绿且变异核验留痕；
3. hbt 离线锁扩档后全量绿（`-pl hbt test`）；
4. example 全量对基线（539/0F/5E/33skip）**零新增红**（XA 5E 环境族口径不变）；
5. 护栏模块（server/proxy/router）零回归；
6. §7 第六列逐档据实回写：绿档 ☑、发现独立破口的档维持 ☐ 并挂新票；DEBT-QUEUE I-DEBT-29-EXT 行收口或转态。

## 5. 边界（不承诺）

- 非 JDBC/真 GUI 客户端（DBeaver 本体、Navicat）不实测，序列为其抓包回放形；
- 8.4/9.x `performance_schema`/i_s 全列集保真不在本票（只钉 DBeaver 序列触达的 12 步 + 差分 3 格）；
- 非 Java connector × 元数据深面属 connector 矩阵 ◐ 线，不在本票；
- CI 徽标（push）不在本票自主范围，本地收口后另请拍板。

## 6. 任务序

| # | 任务 | 产物 |
|---|---|---|
| T1 | infra/端口校准（已做：镜像 7 档全在库；8066 残留 PID 13740 坐实须逐档先杀） | 本节即证 |
| T2 | 写 `MetadataGuiMatrixTest`（序列格 + D1/D2/D3 差分格 + 守卫） | example/compat 新类 |
| T3 | hbt 离线矩阵扩 MariaDB 变体（RED 或变异核验） | VariantReprintFidelityTest |
| T4 | 一次性矩阵驱动脚本逐档实跑，出逐档逐格基线 | `.superpowers/sdd/2026-10-30-…/metadata-matrix-report.md` |
| T5 | 红项裁决树逐名定性：本票修 / 另立案 / 环境族 | DEBT-QUEUE 回写 |
| T6 | 判据 1-6 复核 + 看板回写（§7 第六列、I-DEBT-29-EXT 行） | PROGRESS/DEBT-QUEUE |
