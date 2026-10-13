# Mycat2 兼容性 · 债务关闭里程碑计划（可验证梯）

> **这是什么**：把开放债务队列 `DEBT-QUEUE.md` 重组成**一串可验证里程碑**（R0–R8 + 并行轨）。每个里程碑的出口判据都是一条**能跑的客观检查**（矩阵某格转绿 / CI 徽标 / dump 往返），不是"做完了"的自述。
>
> **它不是什么**：不是新范围、不是第二事实源。内容全部来自已批准的 spec `2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md`（§3 里程碑 + §5 Evidenced-Done + ★验收标准节）与队列 §6 关闭序。**口径冲突一律以 spec §3/§5 为准。**
>
> **怎么用**：同一时刻只有一个里程碑在跑。里程碑的所有出口判据勾满 → 回 `DEBT-QUEUE.md` 仪表盘更新对应 M 的完成度 → 进下一个里程碑。逐格证据回看 `2026-09-30-mysql-mariadb-compat-PROGRESS.md`。
>
> **粒度约定**：一个里程碑 ≈ 一个可独立评审、能被"通过/未通过"二值判定的交付。比 spec 的 M1–M6 细、比实现任务粗。
>
> **校准**：2026-10-13（基于 DEBT-QUEUE 2026-10-13 复核态 + 当前代码/`git log` 独立坐实：R2 前端结果集 EOF 编码已改由活编码器 `MySQLServerSession` 承担、`:305` 死码；R4 `e01368405` 已落；R6 I-ME-1..4 进程内落；R7 I-RP-3 `400a99d33` 关）。

---

## 里程碑梯（沿 `M1 → M2 → M3` 关键路径，A 层优先于 C 层）

| # | 里程碑 | 关掉哪些 ID | **可验证出口判据（跑什么算过）** | 依赖 | 状态 |
|---|---|---|---|---|---|
| **R0** | 验证网补全（认证+操作双轴） | I-CI-3(CRUD轴)✅ · I-DEBT-9(TLS/dump/connector 建格 NO_DATA ⬜未做) | `matrix-local.sh`/`ci/run-example.sh` 跑 compat **四件套**（+`CrudResultMatrixTest`）×7 档，CRUD 每格出 PASS/FAIL、零串档、零 NO_DATA；TLS/多connector 轴先建格记 NO_DATA（不假绿）；产出 CRUD×版本红地图进 `matrix-report.md` | — | 🟡 **CRUD 轴✅**（2026-10-12 四件套×7 档全跑、红地图已出）；TLS/connector NO_DATA 建格—裁定归 R4/R5 随真格子一同建 |
| **R1** | CI 背书落地（M1→真 Done） | I-CI-1 · I-CI-2 | fork 上 GitHub Actions 的 7 版 matrix job 跑完、出 pass/fail 徽标；看板记 run 链接 | R0 ✅已 push + 用户启用 fork Actions | 🟡 **Actions 已启用，缺首个触发**（2026-10-13：用户已在 Settings→Actions 勾选 `Allow all actions and reusable workflows` ✅；workflow `.github/workflows/pr-validation-main-test.yml` 已在 `origin/main`=`a29744e68`。但**启用不补跑历史 push**，且该 workflow 触发=`push:[main]`+`pull_request:[main]`、**无 `workflow_dispatch`**→首个 CI run 需一次新 push（将本地领先 origin/main 的 ~49 commit 推上即触发）。push 属用户红线、待拍板；建议顺带加 `workflow_dispatch` 以便日后手动重跑） |
| **R2** | 版本 persona 连接级生效 | I-VE-2 · I-AU-5 | 同进程分别以 5.7 / 8.4 画像应答，握手 capabilities/默认插件/EOF 位**按连接级 profile 正确**；harness 出差异锁格 | —(已解锁) | 🟢 **收口**（2026-10-12→13 复核：`VersionProfileFactory` 按版本烘 `CLIENT_DEPRECATE_EOF` 进 capabilityMask、`VertxMycatServer` 握手改读 profile 去全局 static；前端结果集 EOF 编码改由**活编码器 `MySQLServerSession.isDeprecateEOF()`**（proxy `writeColumnEndPacket/writeRowEndPacket` 按每连接 caps 分支）承担，`NewMycatConnectionImpl:305` 读全局 static 之旧路径其前端唯一调用点 `ProxyReceiverImpl:32` **已注释=死码**，不投机改动；回归锁 `ConnectionEofDeprecationTest` 5/0/0。I-AU-5 已随 R3 落地）|
| **R3** | 前端 caching_sha2 原生协商 | I-AU-1 · I-AU-3 | 对 8.0/8.4/9.0，caching_sha2 客户端走 **fast-auth + full-auth(RSA) 原生认证**（非 0xFE 降级）；`FrontAuthMatrixTest` 新增 sha2 格 PASS | R2 | ✅ **Evidenced-Done**（2026-10-12：R3-T1..T9 全绿，TDD 单元 fast/full-auth+AuthMoreData+warmed+RsaKeyPair+persona覆盖 9/9；live 矩阵 mysql 8.0/8.4/9.0 三档 `FrontAuthMatrixTest` 5/5 全 PASS——冷 full-auth(RSA 公钥往返)+热 fast-auth(warmed)+错口令拒绝，native 三格不回归；5.7/MariaDB sha2 格正确 N/A。残余：client_ed25519/按用户可配插件另属专轮）|
| **R4** | 前端 TLS（招牌最硬门） | I-AU-2 | `sslMode=REQUIRED` / `--ssl-mode=REQUIRED` connector 连上 8066；compat **TLS 轴 on/off 逐版本转 PASS**（把 R0 的 NO_DATA 点亮） | R0,R2 | 🟡 **Task1/2/3 已落 + Task4 矩阵建格**（配置/SSLRequest 解析 + Connector/J STARTTLS 传输升级 Evidenced-Done `e01368405`：`FrontendStartTlsUpgradeTest` 2/2 + server 全量 33/0/0 + 离线探针 `cipher=TLS_AES_256_GCM_SHA384`；`TlsAxisMatrixTest` 已建格 + `2026-10-13-tls-compat-matrix.md`。剩：全矩阵 e2e 真跑（NO_DATA 待 run-matrix infra）+ 非 Java connector×TLS） |
| **R5** | 基本 CRUD 全绿 + 全 connector | 深债 C 族(I-DEBT-8/34/35/36/39/23e+各根) · I-DEBT-9(connector) | compat **CRUD 矩阵 × 各 MySQL 版全 PASS/N-A**；§1.1 connector 集（Go/Python/.NET/C）各有冒烟网或诚实 NO_DATA | R0 | 🟡 **第一半 CRUD 全绿 Evidenced-Done**（2026-10-12：红族三根 I-DEBT-39/34/36 专轮——`run-matrix` ×7 全档 `CrudResultMatrixTest` 10 格全 PASS/FAIL=0，证见 `matrix-report.md`；commits `f61775a88`/`e35f44ba2`/`d94587d00`/`60016e3cc`）。**未闭合**：I-DEBT-36 跨片全计划 `caseI36` 原生透传已**另立专项**（方向 A，见 `c2-pushdown.md`）；**R5 完成仍开放**（含 connector 冒烟网，用户裁定拆另轮） |
| **R6** | mysqldump 往返（L2） | I-ME-1/2/3/4 | 对每 MySQL 版，`mysqldump <db>` 结构+数据产物在**原生 V 回灌成功**（往返进 harness 出格）；DBeaver/Navicat 列库列表 | R3 | 🟡 **I-ME-1..4 进程内全落（e2e 往返待 infra）**（I-ME-1 排序规则表 `PersonaCollationsTest` 6/6；I-ME-2 dump 面 `DumpSurfaceRegressionTest` 8/8 `3cc944dba`；I-ME-3 p_s 列行面 `c35c14803`；I-ME-4 SHOW MASTER/SLAVE `ShowBinlogStatusRoutingTest` 10/10 `707c9a5bc`，均锚官网。mysqldump 往返回灌 e2e 矩阵格 NO_DATA 待 infra） |
| **R7** | 迁移矩阵绿（L3） | I-CI-4 · I-RP-1/2/3 | MigrateTest 进 7 版矩阵全绿；`MIGRATE_*`/`BINLOG_*` 回归；N1 排除已文档化 | R0 | 🟡 **迁移轴建格 + I-RP-3 已关（真跑待 infra）**（I-RP-1 流水线 bug 修 `dc22f5b92`；I-RP-3 README Non-Goals N1-N4 落文档 `400a99d33`、与 spec §1.2 同构；I-RP-2 binlog 客户端=被动维护面无 active bug（`BinlogUtil.observe` 在）；`MigrateMatrixTest` NO_DATA 待 infra） |
| **R8** | MariaDB 扩展位 | I-AU-4 · I-AU-6 | mariadb 10.6/10.11/11.4 profile + **ed25519 后端多驱动认证通**（引 MariaDB Connector/J 或驱动 SPI）；compat ed25519 格 PASS | R1–R6 精神 | ⚪ |
| **R-D**（并行） | 生产就绪 | I-PR-1/2/3 | 线程池背压压测报告 · 参数解析单测 · 虚拟线程 spike 出数据 | 独立 | ⚪ |
| **R-C**（并行） | 遮蔽类归一 | I-DB-1 | 每类一次三方合并 + 全量回归绿（M1 网保护） | R1 | ⚪ |

状态图例：🔴 进行中 · ⚪ 未起 · ⚪ BLOCKED(外部依赖)。

---

## 里程碑 → 招牌目标兑现

- 完成 **R0–R5** ⇒ **M2 连接达标**（某 MySQL 版进入"支持"候选）。
- 加 **R6** ⇒ **M3**；加 **R7** ⇒ **M4** ⇒ 该 MySQL 版**可对外称"支持"**（spec ★验收标准六项齐）。
- **R8** ⇒ MariaDB 10.6/10.11/11.4 扩展位兑现。
- **R1** 全程是"证据来源"（把本地绿升级成 spec 定义的 Evidenced-Done）。

## 排序判断（可推翻）

1. **R0→R1 先落地**：让 R2 之后每关一格都能逐版本自动出红/绿，治"我说了算"。
2. **R2–R4 是前端连接硬门**：客户端第一脚迈进不来（无 TLS/原生 sha2），谈不上跑查询——**前端连不上比一条 SQL 崩栈更挡招牌**。
3. **R5 把引擎深债(C)绑在"CRUD 矩阵转绿"这个招牌判据上**，不按崩栈响度：C 层只有在**挡住某版 CRUD 转绿**时才提前插队，否则留 R5 统一收。
4. **R8 排最后**：MariaDB 是扩展位（M6），依赖 M1–M3 地基。

## 证据怎么产（R0 具体化）

- 本地矩阵驱动：`matrix-local.sh`（bash）→ 逐版本起 `m1-mysql-a/b` 双实例 → 调 `ci/run-example.sh`（起 Mycat@8066 + `mvn -B test -pl example`）。
- compat 四件套写死在 `matrix-local.sh:11` 的 `COMPAT`；R0 把 `CrudResultMatrixTest` 加进去。
- 逐格红/绿 = JUnit 断言（PASS/FAIL）+ 能力缺失 `assumeTrue` 记 N/A；缺数据必须显式暴露为 NO_DATA，**不得静默当绿**。
- 产物归档：`verify-<tag>/` 存各档 surefire XML；`matrix-report.md` 存权威红/绿表。
