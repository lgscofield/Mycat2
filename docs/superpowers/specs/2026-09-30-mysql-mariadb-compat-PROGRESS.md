# Mycat2 MySQL/MariaDB LTS 兼容性 — 实施看板（Live Progress Board）

> **单一事实源**。spec 主体（`2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md`）保持稳定；实施阶段只更新本文件。
> **铁律**：状态只能是 `Evidenced-Done`（有可重跑自动化检查当前为绿并附证据链接/命令），**禁止**凭 commit message 或口头标 Done。CI 变红即打回 `Regressed`。

## 图例
| 状态 | 含义 |
|---|---|
| TODO | 未开始 |
| WIP | 进行中 |
| BLOCKED | 被依赖项阻塞（见 Dep） |
| REVIEW | 待评审 |
| ✅ DONE | Evidenced-Done（附证据） |
| ⚠ REGRESSED | 曾绿后 CI 变红，需重做 |
| 🚫 NON-GOAL | 明确排除 |

---

## 当前快照（随时看这里 = "进行到哪 / 下一步"）

- **当前里程碑**：M1（验证网）—— **尚未开工，全仓库无有效 CI**
- **下一步（唯一入口）**：`I-CI-1` 修复 workflow（JDK21 + `server/target` 路径）
- **关键路径**：M1 → M2 → M3；M4/M5 并行；M6 依赖 M2
- **最后更新**：2026-09-30（初始化，全部 TODO）

> "下一步"永远等于：优先级最高的、其所有 Dep 均为 ✅ DONE 且自身尚非 DONE 的项。

---

## 进度总表

| ID | 项 | Track | Milestone | 状态 | Dep | Done 证据（检查名 + 版本矩阵） |
|---|---|---|---|---|---|---|
| I-CI-1 | 修复现有 workflow | T-CI | M1 | TODO | — | |
| I-CI-2 | 多库服务矩阵 | T-CI | M1 | TODO | I-CI-1 | |
| I-CI-3 | 兼容性 harness | T-CI | M1 | TODO | I-CI-1 | |
| I-CI-4 | MigrateTest 接矩阵 | T-CI | M1 | TODO | I-CI-2 | |
| I-VE-1 | VersionProfile 抽象 | T-VER | M2 | TODO | I-CI-1 | |
| I-VE-2 | EOF 能力位连接级化 | T-VER | M2 | TODO | I-VE-1 | |
| I-VE-3 | 消除启动期全局可变量 | T-VER | M2 | TODO | I-VE-1 | |
| I-AU-1 | 前端 caching_sha2 完整协商 | T-AUTH | M2 | TODO | I-CI-3, I-VE-1 | |
| I-AU-2 | 前端 TLS | T-AUTH | M2 | TODO | I-CI-3, I-VE-2 | |
| I-AU-3 | 非 TLS RSA 公钥检索 | T-AUTH | M2 | TODO | I-AU-1 | |
| I-AU-5 | 按版本宣称插件 | T-AUTH | M2 | TODO | I-VE-1 | |
| I-AU-4 | ed25519 (MariaDB) | T-AUTH | M6 | BLOCKED | I-VE-1, M2 | 依赖 MariaDB profile |
| I-ME-1 | 排序规则表补全 | T-META | M3 | TODO | I-VE-1 | |
| I-ME-2 | information_schema dump 面 | T-META | M3 | TODO | I-VE-1, I-AU-1 | |
| I-ME-3 | performance_schema 完整 | T-META | M3 | TODO | I-ME-2 | |
| I-ME-4 | SHOW MASTER/SLAVE STATUS | T-META | M3 | TODO | I-VE-1 | |
| I-RP-1 | 迁移流水线修复+CI | T-REPL | M4 | TODO | I-CI-4 | |
| I-RP-2 | binlog 客户端维护 | T-REPL | M4 | TODO | I-CI-2 | |
| I-RP-3 | 文档化排除 N1 | T-REPL | M4 | TODO | — | |
| I-PR-1 | 线程池名实相符+背压 | T-PROD | M5 | TODO | I-CI-1 | |
| I-PR-2 | MycatCore 参数 bug | T-PROD | M5 | TODO | I-CI-1 | |
| I-PR-3 | 虚拟线程 spike | T-PROD | M5 | TODO | — | 仅探针，先出数据 |
| I-DB-1 | 遮蔽类逐类归一 | T-DEBT | 横切 | TODO | I-CI-2 | 不阻塞关键路径，长期并行 |
| N1 | 代理作复制主库 | — | — | 🚫 NON-GOAL | — | 不实现 COM_BINLOG_DUMP |
| N2 | InnoDB 内核职责 | — | — | 🚫 NON-GOAL | — | |
| N3 | MySQL Shell AdminAPI | — | — | 🚫 NON-GOAL | — | |
| N4 | 对外 CDC 订阅产品化 | — | — | 🚫 NON-GOAL | — | |

---

## 里程碑勾选板

- [ ] **M1 验证网**：I-CI-1 ☐ I-CI-2 ☐ I-CI-3 ☐ （I-CI-4 属 M4 前置）
- [ ] **M2 L1 连接**：I-VE-1/2/3 ☐ I-AU-1/2/3/5 ☐
- [ ] **M3 L2 工具**：I-ME-1/2/3/4 ☐（mysqldump 往返全版本绿）
- [ ] **M4 L3 迁移**：I-CI-4 ☐ I-RP-1/2/3 ☐
- [ ] **M5 生产就绪**：I-PR-1/2/3 ☐
- [ ] **M6 MariaDB**：I-AU-4 ☐ + mariadb profile 全绿
- [ ] **横切 T-DEBT**：I-DB-1（逐类，见子清单，可长尾）

---

## 版本矩阵达标追踪（验收面 §7）

| 版本 | 连接认证 | TLS | CRUD/事务 | connector 矩阵 | mysqldump 往返 | 元数据 GUI |
|---|---|---|---|---|---|---|
| MySQL 5.7 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| MySQL 8.0 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| MySQL 8.4 (LTS) | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| MySQL 9.x (Innov) | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| MariaDB 10.6 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| MariaDB 10.11 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| MariaDB 11.4 (LTS) | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |

---

## I-DB-1 遮蔽类归一子清单（T-DEBT，逐类回合）

> 实测 hbt 下 `org.apache.calcite.*` 共 17 类，分两组。（开工前已 `git ls-files` 清点，非估算）

**A. Mycat 自研寄生 calcite 包（6）——只需改包名，低风险，可先行：**

| 类 | 状态 | 处理 |
|---|---|---|
| MycatContext | TODO | 迁 io.mycat.* |
| mycat/MycatBuiltInMethodImpl | TODO | 迁 io.mycat.* |
| rel/rules/MycatHepJoinClustering | TODO | 迁 io.mycat.* |
| runtime/NewMycatDataContext | TODO | 迁 io.mycat.* |
| util/RxBuiltInMethod | TODO | 迁 io.mycat.* |
| util/RxBuiltInMethodImpl | TODO | 迁 io.mycat.* |

**B. 真·遮蔽类（11）——与上游同 FQCN，需三方合并（遮蔽版×fork×上游1.42），每类一次回归：**

| 类 | 状态 | 备注 |
|---|---|---|
| DataContext | TODO | 接口 |
| adapter/enumerable/RexToLixTranslator | TODO | ~2924 行 |
| rel/externalize/RelJson | TODO | |
| rel/externalize/RelJsonWriter | TODO | |
| rel/metadata/RelMdCollation | TODO | |
| rel/metadata/RelMdMaxRowCount | TODO | |
| rel/metadata/RelMdRowCount | TODO | |
| rel/metadata/RelMdUniqueKeys | TODO | |
| rel/metadata/RelMdUtil | TODO | |
| rex/RexLiteral | TODO | |
| sql/validate/SqlValidatorImpl | TODO | 6124 行最大，最后做 |

---

## 变更日志（防丢失：每次合并追加一行）

| 日期 | 项 | 动作 | 证据 |
|---|---|---|---|
| 2026-09-30 | — | 看板初始化，全 TODO | 本文件 |
