# Mycat2 兼容性 · 债务关闭里程碑计划（可验证梯）

> **这是什么**：把开放债务队列 `DEBT-QUEUE.md` 重组成**一串可验证里程碑**（R0–R8 + 并行轨）。每个里程碑的出口判据都是一条**能跑的客观检查**（矩阵某格转绿 / CI 徽标 / dump 往返），不是"做完了"的自述。
>
> **它不是什么**：不是新范围、不是第二事实源。内容全部来自已批准的 spec `2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md`（§3 里程碑 + §5 Evidenced-Done + ★验收标准节）与队列 §6 关闭序。**口径冲突一律以 spec §3/§5 为准。**
>
> **怎么用**：同一时刻只有一个里程碑在跑。里程碑的所有出口判据勾满 → 回 `DEBT-QUEUE.md` 仪表盘更新对应 M 的完成度 → 进下一个里程碑。逐格证据回看 `2026-09-30-mysql-mariadb-compat-PROGRESS.md`。
>
> **粒度约定**：一个里程碑 ≈ 一个可独立评审、能被"通过/未通过"二值判定的交付。比 spec 的 M1–M6 细、比实现任务粗。
>
> **校准**：2026-10-12（基于 DEBT-QUEUE 2026-10-12 复核态）。

---

## 里程碑梯（沿 `M1 → M2 → M3` 关键路径，A 层优先于 C 层）

| # | 里程碑 | 关掉哪些 ID | **可验证出口判据（跑什么算过）** | 依赖 | 状态 |
|---|---|---|---|---|---|
| **R0** | 验证网补全（认证+操作双轴） | I-CI-3(CRUD轴) · I-DEBT-9(TLS/dump/connector **先建格**) | `matrix-local.sh`/`ci/run-example.sh` 跑 compat **四件套**（+`CrudResultMatrixTest`）×7 档，CRUD 每格出 PASS/FAIL、零串档、零 NO_DATA；TLS/多connector 轴先建格记 NO_DATA（不假绿）；产出 CRUD×版本红地图进 `matrix-report.md` | — | 🔴 进行中 |
| **R1** | CI 背书落地（M1→真 Done） | I-CI-1 · I-CI-2 | fork 上 GitHub Actions 的 7 版 matrix job 跑完、出 pass/fail 徽标；看板记 run 链接 | R0 + 用户 push | ⚪ BLOCKED(网络) |
| **R2** | 版本 persona 连接级生效 | I-VE-2 · I-AU-5 | 同进程分别以 5.7 / 8.4 画像应答，握手 capabilities/默认插件/EOF 位**按连接级 profile 正确**；harness 出差异锁格 | —(已解锁) | ⚪ |
| **R3** | 前端 caching_sha2 原生协商 | I-AU-1 · I-AU-3 | 对 8.0/8.4/9.0，caching_sha2 客户端走 **fast-auth + full-auth(RSA) 原生认证**（非 0xFE 降级）；`FrontAuthMatrixTest` 新增 sha2 格 PASS | R2 | ⚪ |
| **R4** | 前端 TLS（招牌最硬门） | I-AU-2 | `sslMode=REQUIRED` / `--ssl-mode=REQUIRED` connector 连上 8066；compat **TLS 轴 on/off 逐版本转 PASS**（把 R0 的 NO_DATA 点亮） | R0,R2 | ⚪ |
| **R5** | 基本 CRUD 全绿 + 全 connector | 深债 C 族(I-DEBT-8/34/35/36/39/23e+各根) · I-DEBT-9(connector) | compat **CRUD 矩阵 × 各 MySQL 版全 PASS/N-A**；§1.1 connector 集（Go/Python/.NET/C）各有冒烟网或诚实 NO_DATA | R0 | ⚪ |
| **R6** | mysqldump 往返（L2） | I-ME-1/2/3/4 | 对每 MySQL 版，`mysqldump <db>` 结构+数据产物在**原生 V 回灌成功**（往返进 harness 出格）；DBeaver/Navicat 列库列表 | R3 | ⚪ |
| **R7** | 迁移矩阵绿（L3） | I-CI-4 · I-RP-1/2/3 | MigrateTest 进 7 版矩阵全绿；`MIGRATE_*`/`BINLOG_*` 回归；N1 排除已文档化 | R0 | ⚪ |
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
