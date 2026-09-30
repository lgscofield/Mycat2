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

- **当前里程碑**：M2-1（druid 影子清理）—— ✅ 6 个影子文件（5 类）已删、`MycatOutputVisitor` 直接继续官方、抢加载 hack 已移除；✅ JDK21 全链构建绿；✅ **D1 已端到端关闭**（新 jar 服务端日志 `NoSuchMethodError` 次数 0，认证 + Connector/J 会话初始化全过）；⚠ L1 “拿到结果集”被新暴露的 Calcite 影子阻（见下方 I-DEBT-3）
- **✅ 战果（A 探路）**：修 calcite-core `ConverterRule:114-117`（退上游写法）后，Mycat 启动、Vertx server started、8066 监听、客户端连接过认证建 session
- **✅ 第2条战果（M2-1 主体）**：druid 影子全除。`git ls-files "*com/alibaba/druid*"` = 0 行；19234 行删除；
  **零代码适配**即编译通过（spec 第 5 节止损阈值实测 0）；黄金基线 17/17 已对账（#5 移植回 `SEPARATOR`，#16 从报错变正常）
- **🔴 当前阻碍（网的第4条战果，新发现）**：`bash ci/verify-l1.sh` 在新 jar 上仍 rc=1，原因不是 druid 而是
  `ClassCastException: SelectScope cannot be cast to MeasureScope`（`calcite-core/.../SqlToRelConverter:735` 无条件强转，
  而运行时胜出的 `hbt/.../sql/validate/SqlValidatorImpl:1177` 返 SelectScope）。
  **⇒ 板上原写“SqlValidatorImpl 最大、最后做”的排序已被实测推翻：它在 L1 关键路径上，必须最先做**
- **🔴 第3条战果（D13）**：example 模块 ~40 个测试文件编译不过（pom 缺 replica/plug 依赖）→ 待与上一条一并裁定顺序
- **M2-1 取证结论（推翻“该 fork druid”的初判）**：影子是 **druid 1.0.18（2017）整拷贝**（官方 1.0.18 构造器就是 `(Appendable)`，1.2.28 已改 `(StringBuilder)`）；12 个文件里 `import io.mycat` **0 处**；27 处 `MYCAT CHANGE`（终审实测，本节原记 25）全为“1.2.28 removed/renamed X”兼容补丁；官方 1.2.28 已自带 `shardingSupport`；`ExportParameterVisitorUtils`/`SQLIndexDefinition` 与官方只差 36/24 行。**⇒ 裁定：不建 `druid-core` fork**（那意味着永久持有 1644 文件+224 资源且锁死升级），改为**删 5 类真影子 + 把 Mycat 自增类搬出 druid 包 + `MycatOutputVisitor` 直接继续官方类**，与 `2026-09-29` 设计第 114 行“能用原码最好、逐步消除不必要覆盖”同一路径。**方法论已在实战验证：删影子零适配、黄金基线只出 2 条差异**
- **下一步**：已裁定（用户 2026-10-01）→ 做 **I-DEBT-3**：设 `SqlValidatorImpl` 为首个归一类。
  设计已写：`docs/superpowers/specs/2026-10-01-calcite-validator-shadow-removal-design.md`（待用户评审后拆计划）。
  方案 P1：把 `<=>` 的 BOOLEAN 推导上提到算子自身 → 删 hbt 副本 → fork 1.42 胜出，**fork 保持零 `import io.mycat`**。
  D13（example，I-DEBT-2）顺延，不阻塞本路径
- **关键路径**：M1(✅建网+boot) → **M2-1(✅拆 druid 影子、D1 关闭) → I-DB-1 首个类(SqlValidatorImpl) → L1 拿到 rows → M3**（D13 example 依赖可并行）
- **最后更新**：2026-10-01（M2-1 交付：影子清零 + 黄金基线对账 + L1 探针入 CI；新登记 L1 结果集受 Calcite 影子阻）

> "下一步"永远等于：优先级最高的、其所有 Dep 均为 ✅ DONE 且自身尚非 DONE 的项。

---

## 进度总表

| ID | 项 | Track | Milestone | 状态 | Dep | Done 证据（检查名 + 版本矩阵） |
|---|---|---|---|---|---|---|
| I-CI-1 | 修复现有 workflow | T-CI | M1 | WIP | — | 交付物已提交(76bc806..847961e)，YAML/bash 静态校验过；待构建绿 |
| I-CI-2 | 多库服务矩阵 | T-CI | M1 | WIP | I-CI-1 | 7版本 matrix 已写入 workflow；待 CI 实跑 pass/fail |
| I-CI-3 | 兼容性 harness | T-CI | M1 | WIP | I-CI-1 | ConnectionSmokeTest 已建；待跑绿 |
| I-CI-4 | MigrateTest 接矩阵 | T-CI | M1 | TODO | I-CI-2 | |
| I-DEBT-1 | druid 影子类清理（不 fork） | T-DEBT | M2 | Evidenced-Done | I-CI-1 | G1 `mvn -B install '-Dmaven.test.skip=true' -pl server -am` exit=0；G2 `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest'` → 3/3 pass（基线 17/17）；G6 `git ls-files "*com/alibaba/druid*"` → 空输出；G5 `mvn -B test -pl router,executor,hbt` → router 15/0/0、hbt 26/0/23，与开工基准逐条对齐（多出的 3 条是本计划 golden 全绿；executor 在 hbt 失败后被跳过，基准本就 0 条）；G4 已按根因归属拆为两行并回写 spec 第 3 节：**G4a 认证+会话初始化已过**（新 jar 日志 NoSuchMethodError=0），**G4b 结果集未过** → 根因在 Calcite 影子，不属本影子系统，已另开 I-DEBT-3 |
| I-DEBT-3 | L1 拿到一条 SQL 的结果集 | T-DEBT | M2 | WIP | I-DB-1(B组 SqlValidatorImpl) | 未过项：`bash ci/verify-l1.sh` rc=1，`ClassCastException: SelectScope → MeasureScope`（`calcite-core/.../SqlToRelConverter:735` vs 运行时胜出的 `hbt/.../SqlValidatorImpl:1177`）；已过项：探针 RED 能精确复现并已消除 D1（commit 6d89b6545） |
| I-DEBT-2 | example 模块依赖/测试修复 | T-DEBT | M2 | TODO | I-DEBT-1 | ~40 个坏测试，阻塞 ConnectionSmokeTest |
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
- [ ] **M2 L1 连接**：I-DEBT-1 ☑（druid 影子已清）I-DEBT-3 ☐（结果集受 Calcite 影子阻）I-VE-1/2/3 ☐ I-AU-1/2/3/5 ☐
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
>
> 口径校对（M2-1 交付时补）：按“calcite-core 里是否存在同路径副本”数出来是 **12** 个 =
> 下面 B 组 11 个真遮蔽 + A 组的 `mycat/MycatBuiltInMethodImpl`（它是 Mycat 自研，但 fork 里也带了一份，
> 故两边同路径）。所以 B 组“逐类归一”是 11 件，而“需先去重”的同 FQCN 对是 12 对。

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
| sql/validate/SqlValidatorImpl | 🔴 BLOCKING-L1 | 实测：它不在“最后做”的位置——它在 L1 关键路径上。hbt 副本 6723 行（旧 Calcite）vs calcite-core 8782 行；:1177 `getMeasureScope` 返 `scopes.get(select)`（SelectScope）并注有“Calcite 1.42 added MEASURE clause; not yet used in hbt”，而 calcite-core :1554 返 `getScope(select, Clause.MEASURE)`且 :735 无条件强转 MeasureScope → 客户端 `SELECT 1` 直接 CCE |

---

## 变更日志（防丢失：每次合并追加一行）

| 日期 | 项 | 动作 | 证据 |
|---|---|---|---|
| 2026-09-30 | — | 看板初始化，全 TODO | 本文件 |
| 2026-10-01 | I-DEBT-1 | M2-1 交付，改 Evidenced-Done（G1/G2/G6 全过，G4 部分过） | 提交 6020939ca..6d89b6545；账本 `.superpowers/sdd/2026-09-30-druid-shadow-removal/progress.md` |
| 2026-10-01 | I-DEBT-3（新开） | L1 结果集受 Calcite 影子阻；I-DB-1 的 B 组排序被实测推翻 | `bash ci/verify-l1.sh` 新 jar rc=1，日志 NoSuchMethodError=0、CCE 在 `SqlToRelConverter:735` |
| 2026-10-01 | I-DEBT-1 | M2-1 整支终审（fresh reviewer）后回写 spec：无代码返工，仅纠正文档事实 + G4 拆为 G4a/G4b | 实测 `git grep -o "MYCAT CHANGE" d9415b55e^ -- "*com/alibaba/druid*"` = 27 行（原写 25）；旧 `MycatOutputVisitor` 内标记 = 0 处；终审重跑 G1 exit=0、G2 3/3、G5 router 15/0/0 + hbt 26/0/23、G6 0 行、G4a 日志计数 0 |
| 2026-10-01 | I-DEBT-3 | 用户裁三项（仅做 L1 路径必要类 / 先建校验行为黄金基线 / 待移植 >10 条即止损），设计文档已落盘待评审 | `docs/superpowers/specs/2026-10-01-calcite-validator-shadow-removal-design.md`；实测新证：`calcite-core` 内 42 个文件命中 `SqlValidatorImpl`（含自身）、fork 侧该类零 `MYCAT CHANGE`、hbt 副本仅 1 处真定制（`StrictEqualFunction` 强判 BOOLEAN） |
| 2026-10-01 | I-DEBT-3 | **Task 3 三方血统 diff 收口：止损闸门未触发（N=2 ≤ 10），P1 维持** | 实测 `target/shadowdiff/`：`copy-vs-1.23` `+191/-178`（副本≈1.23）、`copy-vs-1.42` `+1059/-2970`（代际漂移）；空白归一后副本独有且 fork 查无同形的 `+` 行仅 43/168；Mycat 语义定制 4 处—`<=>` 特判(`:5856-5862` A类·已移植 8529638)、`callToOperandTypesMap`(`:265` B类·fork:281 自带)、`getMeasureScope`(`:1177` B类·fork:1554 真实现)、`getOrderList();//fix bug`(`:2585` C类·hbt 无 SelectScope 副本故打 fork:178 懒加载，交 Task 5 G2v 对账)；需裁定去向 N=2。账本 `2026-10-01-calcite-validator-shadow-removal/progress.md` |
