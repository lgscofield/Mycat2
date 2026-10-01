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

- **当前里程碑**：M2-1（druid 影子清理）—— ✅ 6 个影子文件（5 类）已删、`MycatOutputVisitor` 直接继续官方、抢加载 hack 已移除；✅ JDK21 全链构建绿；✅ **D1 已端到端关闭**（新 jar 服务端日志 `NoSuchMethodError` 次数 0，认证 + Connector/J 会话初始化全过）；✅ **L1 首绿（I-DEBT-4，2026-10-01）**：`bash ci/verify-l1.sh` rc=0 输出 `L1 OK rows=1`，真客户端过认证+会话+一条查询拿回结果集
- **✅ 战果（A 探路）**：修 calcite-core `ConverterRule:114-117`（退上游写法）后，Mycat 启动、Vertx server started、8066 监听、客户端连接过认证建 session
- **✅ 第2条战果（M2-1 主体）**：druid 影子全除。`git ls-files "*com/alibaba/druid*"` = 0 行；19234 行删除；
  **零代码适配**即编译通过（spec 第 5 节止损阈值实测 0）；黄金基线 17/17 已对账（#5 移植回 `SEPARATOR`，#16 从报错变正常）
- **✅ 第4条战果（I-DEBT-3，2026-10-01 收口）**：`SqlValidatorImpl` 副本（6723 行）已删、fork 1.42 归一胜出；黄金基线 48 条对账 **SAME 31 + 从错到对 17、零回归**；L1 旧靶心 `ClassCastException: SelectScope→MeasureScope` 实测消失，查询首次走通“校验→转换”全段；fork 保持零 `import io.mycat`；提交 8529638..df9856c14
- ✅ **第5条战果（I-DEBT-4，2026-10-01 收口，原阻碍线关闭）**：原断层——janino 生成类缺 `bind` 外壳（旧记“Bindable.bind 缺失”，根因是 Mycat 私有契约寄生在 hbt `DataContext` 副本的 ROOT 重 typed 里）。交付：`MycatBindable` 自有契约（ROOT 类型化 + bind 双态 + asObjectArray 上提，合同测试 5 例钉死）、hbt 21 处 ROOT 引用归零、`DataContext` 影子副本删除（孪生对 11→10）；L1 一轮即绿 `L1 OK rows=1`；workflow `Verify L1` 软门已摘转硬门
- ✅ **第6条战果（I-DEBT-2/D13，2026-10-01 收口）**：example 编译面归零（实测 39 文件/200 错 → 0）。根因非“坏测试”而是 pom 声明两层缺失：① server 被 maven-shade install 为 dependency-reduced-pom，兄弟模块传递依赖全剥除（dependency:tree 实证的零子节点孤 node）→ 显式补 hbt/datasource/plug/replica/jsr305/commons-csv 六 test 依赖；② 硬码 junit-jupiter 5.7.0 把 platform 压到 1.7 与 surefire 3.5.3 断裂 → Tests run: 0 假绿，删硬码交还根 pom 5.12.2。**run-example.sh 实测能真跑起来（58 类出 surefire 报告）**；冒烟测已执行但红（String→Object[] CCE，产品断层另立 I-DEBT-6）；挂点两类立 I-DEBT-7
- ✅ **第7条战果（I-DEBT-6，2026-10-01 收口）**：冒烟 CCE 根因钉死并修复。真根因非初猜的“select 输入吐 String”——探针实证 select 输入=Object[]，CCE 在 `RxBuiltInMethodImpl.select` 对 `map.apply` **返回值**烘焙的 `(Object[])` 强转 × 单列标量投影返回 String；修复=泛型化 `select<T,R>`（多列行为不变，升箱交 asObjectArray）。**ConnectionSmokeTest 端到端转绿（Tests run:1/0/0，服务端零 CCE）**；example e2e 首轮全量清单 60 类（18 绿/42 红），**全库 surefire 报告 grep ClassCastException=NONE**，42 红全为 SQLException 176 条（特性缺口/环境前置假设，与 CCE 不同域）+ 挂点类 → 归 I-DEBT-7；诊断面副产品：PrepareExecutor 补 log.error 修“异常被吞”可诊断性缺陷。提交 b736d5d70..81f228c17
- **M2-1 取证结论（推翻“该 fork druid”的初判）**：影子是 **druid 1.0.18（2017）整拷贝**（官方 1.0.18 构造器就是 `(Appendable)`，1.2.28 已改 `(StringBuilder)`）；12 个文件里 `import io.mycat` **0 处**；27 处 `MYCAT CHANGE`（终审实测，本节原记 25）全为“1.2.28 removed/renamed X”兼容补丁；官方 1.2.28 已自带 `shardingSupport`；`ExportParameterVisitorUtils`/`SQLIndexDefinition` 与官方只差 36/24 行。**⇒ 裁定：不建 `druid-core` fork**（那意味着永久持有 1644 文件+224 资源且锁死升级），改为**删 5 类真影子 + 把 Mycat 自增类搬出 druid 包 + `MycatOutputVisitor` 直接继续官方类**，与 `2026-09-29` 设计第 114 行“能用原码最好、逐步消除不必要覆盖”同一路径。**方法论已在实战验证：删影子零适配、黄金基线只出 2 条差异**
- **下一步**：已裁定（用户 2026-10-01 预批口径，顺序按新证据微调已入账本 Ruling）→ **D13 已收口**；**I-DEBT-6 已收口**；**I-DEBT-5 已收口（ColumnMappingTest 23 error 清零，hbt 36/0/0）** → 顺位下一步：**I-DEBT-7A 已收口（Root A harness 消解）→ I-DEBT-7C（全量重新基线，服务端 selectList NPE 等真产品红）→ I-DEBT-7B（池耗尽挂点）→ I-DEBT-8（Rx 算子泛型化收口）→ M3（L2 mysqldump 往返）**
- **关键路径**：M1(✅建网+boot) → **M2-1(✅拆 druid 影子) → I-DEBT-3(✅删 validator 副本、CCE 消失) → I-DEBT-4(✅ bind 契约归位、L1 OK rows=1、硬门生效) → I-DEBT-2(✅ example 编译面归零) → I-DEBT-6(✅冒烟绿、全量零 CCE) → M3**（I-DEBT-5/7 可并行）
- **最后更新**：2026-10-02（I-DEBT-7A 收口：Root A=e2e harness 缺陷钉死，`MycatTest.execute()` 改 `Statement.execute()`，子集实测 executeUpdate 残留 0/冒烟绿/PushDownNormal 转绿；I-DEBT-7 拆 7A/7B/7C；下一步指 7C 全量重新基线）
- **上一版**：2026-10-01（I-DEBT-5 收口：ColumnMappingTest 纯脚手架修复（createRelBuilder 补空 RelOptSchema），hbt 23 error→0、36/0/0）

> "下一步"永远等于：优先级最高的、其所有 Dep 均为 ✅ DONE 且自身尚非 DONE 的项。

---

## 进度总表

| ID | 项 | Track | Milestone | 状态 | Dep | Done 证据（检查名 + 版本矩阵） |
|---|---|---|---|---|---|---|
| I-CI-1 | 修复现有 workflow | T-CI | M1 | WIP | — | 交付物已提交(76bc806..847961e)，YAML/bash 静态校验过；待构建绿 |
| I-CI-2 | 多库服务矩阵 | T-CI | M1 | WIP | I-CI-1 | 7版本 matrix 已写入 workflow；待 CI 实跑 pass/fail |
| I-CI-3 | 兼容性 harness | T-CI | M1 | WIP | I-CI-1 | ConnectionSmokeTest 已建且能执行；**冒烟已跑绿（I-DEBT-6 消解）**：`Tests run:1 Failures:0 Errors:0`、服务端零 CCE（M1 达标表“连接认证”列证据）；仍 WIP：多版本矩阵/CI 实跑 pass/fail 未完 |
| I-CI-4 | MigrateTest 接矩阵 | T-CI | M1 | TODO | I-CI-2 | |
| I-DEBT-1 | druid 影子类清理（不 fork） | T-DEBT | M2 | Evidenced-Done | I-CI-1 | G1 `mvn -B install '-Dmaven.test.skip=true' -pl server -am` exit=0；G2 `mvn -B test -pl hbt '-Dtest=DruidOutputGoldenTest'` → 3/3 pass（基线 17/17）；G6 `git ls-files "*com/alibaba/druid*"` → 空输出；G5 `mvn -B test -pl router,executor,hbt` → router 15/0/0、hbt 26/0/23，与开工基准逐条对齐（多出的 3 条是本计划 golden 全绿；executor 在 hbt 失败后被跳过，基准本就 0 条）；G4 已按根因归属拆为两行并回写 spec 第 3 节：**G4a 认证+会话初始化已过**（新 jar 日志 NoSuchMethodError=0），**G4b 结果集未过** → 根因在 Calcite 影子，不属本影子系统，已另开 I-DEBT-3 |
| I-DEBT-3 | 删 SqlValidatorImpl 影子、fork 归一（L1 拿 rows 后半程转 I-DEBT-4） | T-DEBT | M2 | Evidenced-Done | I-DB-1(B组 SqlValidatorImpl) | G1 `mvn -B -o install '-Dmaven.test.skip=true' -pl server -am` → BUILD SUCCESS（jar 10-01 08:34）；G2v 黄金基线对账（锚点已转 fork）`mvn -B -o test -pl hbt -Dtest=CalciteValidatorGoldenTest` → 1/0/0，SAME 31+从错到对 17+零回归；G6v 同 FQCN 孪生对 12→11；G7 `git grep -l "^import io.mycat" calcite-core/src/main/java` → 0；G5 `mvn -B -o test -pl router,executor,hbt -Dmaven.test.failure.ignore=true` → router 15/0/0、hbt 29/0/23（基准 26/0/23 + 3 新测全绿，23 error 全为既有 ColumnMappingTest）、executor 0；提交 8529638..df9856c14，账本 `2026-10-01-calcite-validator-shadow-removal/progress.md` |
| I-DEBT-4 | executor codegen 与 1.42 Bindable 契约对齐（L1 拿 rows 最后一公里） | T-DEBT | M2 | Evidenced-Done | I-DEBT-3 | G4b `bash ci/verify-l1.sh`（WSL，Docker m1-mysql-a/b，L1_CONNECTOR=connector-j-9.3.0）→ rc=0 `L1 OK rows=1`（task-4-l1-evidence.log）；G1 `mvn -B -o install '-Dmaven.test.skip=true' -pl server -am` → BUILD SUCCESS；G5 `mvn -B -o test -pl router,executor,hbt` → router 15/0/0、hbt 34/0/23（+5 合同用例；23 error 全为既有 ColumnMappingTest=I-DEBT-5）、executor 0；G2v CalciteValidatorGoldenTest 1/0/0；G6v 孪生对 11→10、G7=0、hbt org/apache 文件 16→15、`DataContext.ROOT` 引用 0；软门已摘（workflow Verify L1 删 continue-on-error，YAML 解析实证 ABSENT）；提交 392072a0c..cb795bf5b，账本 `2026-10-01-exec-bindable-contract/progress.md` |
| I-DEBT-5 | planner 测试基建修复（ColumnMappingTest 23 error 另案转正） | T-DEBT | M2 | Evidenced-Done | — | 纯测试脚手架缺陷（取证 spike 实证，非产品逻辑漂移）：`ColumnMappingTest.createRelBuilder()` 传 `relOptSchema=null`，Calcite 1.42 fork `RelBuilder.transientScan(String,RelDataType)` 新增 `requireNonNull(relOptSchema)`（RelBuilder.java:3165）→ 23/23 均 NPE:relOptSchema（栈全经 `createTable_user:54`）。修复=给脚手架一个空 root schema（`CalciteCatalogReader` 适配 `RelOptSchema`，同仓 `MycatView:188`/`CalciteValidatorGoldenTest:79` 已用 `CalciteSchema.createRootSchema`）。**G-5h `mvn -B -o test -pl hbt` → BUILD SUCCESS、Tests run:36 Failures:0 Errors:0**（ColumnMappingTest 单独 23/0/0，原 34/0/23 的 23 error 清零）；RED（23×NPE）→GREEN（修复后 23/0/0）直证 `ColumnRefResolver` 在 1.42 下无回归；仅改测试文件，产品代码零动；账本本行属同记录（bounded，无独立 spec/plan 文件） |
| I-DEBT-2 | example 模块依赖修复（编译面） | T-DEBT | M2 | Evidenced-Done | I-DEBT-1 | G-E1 `mvn -B -o test-compile -pl example` → BUILD SUCCESS、[ERROR] / 行数 200→0（ge1-round1/2.log）；附加修复：删硬码 jupiter 5.7.0 → 假绿消解，run-example.sh 实跑出 58 类 surefire 报告（ge2-run3.log）；G-E2 部分达成：冒烟测已执行但红 → 转 I-DEBT-6；G-E3 router 15/0/0、hbt 34/0/23、install BUILD SUCCESS；提交 98892ca06..168e1999b，账本 `2026-10-01-example-module-deps/progress.md` |
| I-DEBT-6 | ConnectionSmokeTest 冒烟红：String→Object[] CCE（SHOW DATABASES 单列行形态） | T-DEBT | M1/M2 | Evidenced-Done | I-DEBT-2 | 真根因（探针实证）：`RxBuiltInMethodImpl.select(Observable<Object[]>, Function1<Object[],Object[]>)` 被收窄，javac 对 `map.apply` **返回值**烘焙 `(Object[])` 强转；单列标量投影匿名 Function1 实际返回 String→炸（探针钉死输入=Object[]，推翻仅凭栈的“输入吐 String”初猜）。修复=泛型化 `select<T,R>`（去烘焙强转，多列调用点 R 仍推 Object[]，升箱交 asObjectArray；vanilla Calcite SELECT 本就泛型）。**G-S 冒烟绿** `ConnectionSmokeTest Tests run:1 Failures:0 Errors:0`；**G-C 全量零 CCE** example e2e 首轮清单 60 类 surefire 报告 grep ClassCastException=NONE、服务端日志 CCE=0；**G-T 契约单测** `RxBuiltInMethodImplSelectTest` 2/2；42 红全为 java.sql.SQLException 176 条（Mycat 特性缺口/套件环境前置假设，与 CCE 不同域）+ 挂点类 → 属 I-DEBT-7；提交 b736d5d70..81f228c17，账本 `.superpowers/sdd/2026-10-01-smoke-cce-rootcause/progress.md` |
| I-DEBT-7A | example e2e Root A：harness `MycatTest.execute()` 走 executeUpdate 被驱动拒（39 类 setup 崩） | T-DEBT | M1/M2 | Evidenced-Done | I-DEBT-6 | **定性（磁盘栈+connector-j 源码独立复核）= 纯 e2e 脚手架缺陷，非 Mycat 产品 bug**：`MycatTest.execute()→JdbcUtils.execute→PreparedStatement.executeUpdate()`，Connector/J 在 **发送前** 的纯客户端静态分类守卫（`ClientPreparedStatement:1145 isNonResultSetProducingQuery`）把 `/*+ mycat:... */` 注释/hint 串（无被识别起始关键字）误判为“产出结果集”直接抛 "Can not issue executeUpdate() ... produce result sets"，根本没走到网络（Mycat `HintHandler` sendOk 正确）。42 红里 39 类第一行 setup `execute(mycatConnection, RESET_CONFIG)` 即崩、跑不到任何断言。**修复=`execute()` 改 `Statement.execute()`（对任意形态不抛，返回 boolean 忽略；方法 void 调用方不依赖 updateCount，行为不变）**，单方法 10 行、产品码零动。**证据（Docker m1-mysql-a/b 后端，有界子集实跑）**：子集内 `Can not issue executeUpdate` 残留=0；控制组 ConnectionSmokeTest 1/0/0 绿；`PushDownFunctionNormalTableTest` 由 Root-A 红转绿 3/0/0；MegreJoin/BkaJoin setup 不再崩→转 7C 域。**残留（整支终审 Important·已核）：本单方法修复只覆盖走 `MycatTest.execute()` helper 的调用；另有 ~7 类直调 `JdbcUtils.execute(conn,"/*+ mycat:...")` 绕过 helper（AssembleTest/CreateGsiTest/SavepointTest/MycatMonitorTest/ProxyBackendConnectionTest/XaBackendConnectionTest），同一守卫仍会炸——属 Root A 同根、不扩本 bounded 提交（多文件越界），随 7C 全量基线一并收编。账本本行属同记录（bounded，无独立 spec/plan） |
| I-DEBT-7B | example e2e Root B：连接池耗尽挂点（maxActive=8） | T-DEBT | M1/M2 | TODO | I-DEBT-7A | `SqlFunctionTest.testCharFunction/testTimeFunction` 各挂 ~110s、`GetConnectionTimeoutException: active 8, maxActive 8`——MycatTest `DruidDataSource.setMaxActive(8)` 下连接泄漏/未归还致耗尽（真挂起，CI 会撞 job timeout）；须查 harness 连接生命周期或提上限+归还，限时保护 |
| I-DEBT-7C | example e2e Root C：真产品红（harness 解阻后首露） | T-DEBT | M1/M2 | TODO | I-DEBT-7A | 7A 修好 setup 后暴露的服务端缺陷，非脚手架：`MegreJoinExecuteTest:116`/`BkaJoinExecuteTest` 经 `executeQuery→count` 抛 **服务端 `NullPointerException: selectList`**（Mycat 执行 sharded merge/BKA join 的真断层）+ 少量真断言失败。全量重新基线待专轮（避 7B 挂点）逐类列清单后按子域归类；**含 7A 未覆盖的 ~7 类直调 `JdbcUtils.execute(hint)` 同根 harness 残留**（本批类过了 RESET_CONFIG 首行后会在 body 里再炸 executeUpdate 守卫，须同法改走 Statement.execute 或收进 helper） |
| I-DEBT-8 | Rx 算子泛型化收口 + 升箱契约边角 | T-DEBT | M2 | TODO | I-DEBT-6 | I-DEBT-6 整支终审（fresh CodeReview、javap 字节码取证）发现：**select 泛型化后标量行首次可达同族下游算子**——`filter/topN/sort/toEnumerable/matierial` 仍声明 `Observable<Object[]>`/`Predicate1<Object[]>`，合成桥仍烘焙 `(Object[])` **输入**强转，若规划器产出此类形态会抛同名 CCE；相关：`MycatBindable.asObjectArray` 用 `isArray()` 判升箱对基元数组列（单列 VARBINARY→byte[]）会漏升（非 Object[]）、`MycatBindable` bind Javadoc 仍写死返回 Object[] 与新契约矛盾。本轮按止损棘轮不扩面，已加 select Javadoc 指针；待专轮统一泛型化+契约用例。证据：账本 `review-8e3bc3f5b..b6c8f0d46.diff` |
| I-DEBT-9 | example 测试面结构性健全（认证/TLS/dump/connector 矩阵） | T-TEST | M2/M3 | TODO | I-DEBT-7A, I-CI-3 | 用户 2026-10-02 问“example 是否够全”时的实测评估：广度尚可（85 类/24 维度），但对项目头条目标（MySQL/MariaDB 版本兼容）有结构性空白——认证协商 caching_sha2/native/ed25519、前端 TLS、mysqldump 往返、多 connector × 多版本矩阵均 **0 覆盖**（连接器全 `useSSL=false`）。与兼容性设计的 T-CI/I-CI-3 四维 harness 同源；待 7A 修好 harness、套件能真跑到断言后再专项补（先修后补，不往不能执行的地基浇砖） |
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
- [ ] **M2 L1 连接**：I-DEBT-1 ☑（druid 影子已清）I-DEBT-3 ☑（validator 副本已删）I-DEBT-4 ☑（bind 契约归位、**L1 OK rows=1 已达成**）I-VE-1/2/3 ☐ I-AU-1/2/3/5 ☐
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

**B. 真·遮蔽类（原 11，已减 2 剩 9：SqlValidatorImpl→I-DEBT-3、DataContext→I-DEBT-4）——与上游同 FQCN，需三方合并（遮蔽版×fork×上游1.42），每类一次回归：**

| 类 | 状态 | 备注 |
|---|---|---|
| DataContext | ✅ DONE（I-DEBT-4） | 副本已删（cb795bf5b）：ROOT 寄生归位 `io.mycat.calcite.MycatBindable`（21 处引用改写+合同测试 5 例），删除三重实证（ROOT=0/剩余消费者只用标准成员/fork↔副本全量 diff）；孪生对 11→10 |
| adapter/enumerable/RexToLixTranslator | TODO | ~2924 行 |
| rel/externalize/RelJson | TODO | |
| rel/externalize/RelJsonWriter | TODO | |
| rel/metadata/RelMdCollation | TODO | |
| rel/metadata/RelMdMaxRowCount | TODO | |
| rel/metadata/RelMdRowCount | TODO | |
| rel/metadata/RelMdUniqueKeys | TODO | |
| rel/metadata/RelMdUtil | TODO | |
| rex/RexLiteral | TODO | |
| sql/validate/SqlValidatorImpl | ✅ DONE（I-DEBT-3） | 副本已删（ae8a80b8b，6723 行），fork 1.42 归一胜出；`<=>` 定制上提算子 inference（8529638）+ Task 5 两处移植（df9856c14）；48 条基线零回归；旧 CCE `SqlToRelConverter:735` 实测消失。剩余 10 类中 DataContext/RexToLixTranslator 两族与 I-DEBT-4 codegen 契约相关，归 I-DEBT-4 一并裁定 |

---

## 变更日志（防丢失：每次合并追加一行）

| 日期 | 项 | 动作 | 证据 |
|---|---|---|---|
| 2026-09-30 | — | 看板初始化，全 TODO | 本文件 |
| 2026-10-01 | I-DEBT-1 | M2-1 交付，改 Evidenced-Done（G1/G2/G6 全过，G4 部分过） | 提交 6020939ca..6d89b6545；账本 `.superpowers/sdd/2026-09-30-druid-shadow-removal/progress.md` |
| 2026-10-01 | I-DEBT-3（新开） | L1 结果集受 Calcite 影子阻；I-DB-1 的 B 组排序被实测推翻 | `bash ci/verify-l1.sh` 新 jar rc=1，日志 NoSuchMethodError=0、CCE 在 `SqlToRelConverter:735` |
| 2026-10-01 | I-DEBT-1 | M2-1 整支终审（fresh reviewer）后回写 spec：无代码返工，仅纠正文档事实 + G4 拆为 G4a/G4b | 实测 `git grep -o "MYCAT CHANGE" d9415b55e^ -- "*com/alibaba/druid*"` = 27 行（原写 25）；旧 `MycatOutputVisitor` 内标记 = 0 处；终审重跑 G1 exit=0、G2 3/3、G5 router 15/0/0 + hbt 26/0/23、G6 0 行、G4a 日志计数 0 |
| 2026-10-01 | I-DEBT-3 | 用户裁三项（仅做 L1 路径必要类 / 先建校验行为黄金基线 / 待移植 >10 条即止损），设计文档已落盘待评审 | `docs/superpowers/specs/2026-10-01-calcite-validator-shadow-removal-design.md`；实测新证：`calcite-core` 内 42 个文件命中 `SqlValidatorImpl`（含自身）、fork 侧该类零 `MYCAT CHANGE`、hbt 副本仅 1 处真定制（`StrictEqualFunction` 强判 BOOLEAN） |
| 2026-10-01 | I-DEBT-3 | **收口交付（Evidenced-Done）**：副本删/基线零回归/CCE 消失/G5 不劣化/G6v=11/G7=0；L1 后半程转 I-DEBT-4；L1 转硬门推迟（workflow 本轮未改，软门保留） | G1 BUILD SUCCESS；G2v 1/0/0（锚点已转 fork）；G5 router 15/0/0 + hbt 29/0/23；G6v=11；G7=0；CCE 计数 0（l1-server.log）；提交 8529638..df9856c14 |
| 2026-10-01 | I-DEBT-4（新开） | executor codegen 缺 `Bindable.bind(DataContext)` 成 L1 新阻断；用户裁定另立专项不扩 I-DEBT-3 面 | `l1-server.log:547` janino CompileException；`StreamMycatEnumerableRelImplementor:60-85`；`ExecutorProviderImpl:82-86` 吞异常坐实 NPE 因果；用户补充裁定：测试后端一律 Docker 容器（m1-mysql-a/b），本地 MySQL84 服务不用 |
| 2026-10-01 | I-DEBT-4 | 设计获批（方向 A：MycatBindable 自有契约，L1 为界），spec+计划落盘；用户确认 planner 后续闭环 | spec 03d1fec1c；计划 e70bfd245（5 任务，合同测试 Proxy fixture 绕开 planner 泥潭） |
| 2026-10-01 | I-DEBT-5（新开） | ColumnMappingTest 23 error 从"另案挂账"转正为独立债项，用户确认后续闭环 | G5 日志 23/23 error 全在该类；I-DEBT-4 计划 Task 1 已留绕开裁定 |
| 2026-10-01 | I-DEBT-4 | **收口交付（Evidenced-Done）**：MycatBindable 自有契约归位（ROOT 类型化+bind 双态+asObjectArray 上提）、hbt 21 处 ROOT 引用归零、`DataContext` 影子副本删（G6v 11→10）、**L1 首绿 `L1 OK rows=1`**；**软门摘除转硬门**（workflow `Verify L1` 删 continue-on-error；matrix.experimental 未动）；hbt 基线 29→34 | G4b rc=0（task-4-l1-evidence.log，崩溃关键字 grep=0）；G1 BUILD SUCCESS；G5 router 15/0/0 + hbt 34/0/23 + executor 0；G2v 1/0/0；合同测试 5/5；G6v=10；G7=0；YAML 解析 step 字段 ABSENT 实证；提交 392072a0c..cb795bf5b+收口提交 7169aaca0，账本 `.superpowers/sdd/2026-10-01-exec-bindable-contract/progress.md` |
| 2026-10-01 | I-DEBT-4 | **整支终审（fresh CodeReview）+ 修复 pass**：Important-1——`Verify L1` 原排在已知编译红的 `Run example`（D13）之后且无 `if:`，CI 上永被跳过、“硬门”假绿：已前移至 Build server jar 之后（services 3306/3307 与本机容器同拓扑、connector 由多模块 pom 必拉进 .m2，静态取证过）；可达性断言脚本 RED→GREEN；spec 两处数字/测试描述订正（G5 30→34、流式态已入单测）；无 Critical；Minor×3 延后入账本 | 终审报告 `.superpowers/sdd/2026-10-01-exec-bindable-contract/final-review-report.md`；合同测试独立复跑 5/5；l1_reach_check RED→GREEN |
| 2026-10-01 | I-DEBT-2 | **收口交付（Evidenced-Done）**：example 编译面 200→0（根因=server shade reduced-pom 剥除传递依赖 + jsr305/jcip 包名混淆，补 6 test 依赖）；删硬码 jupiter 5.7.0 消解 Tests run: 0 假绿（Review Focus-5 实战命中）；run-example.sh 实跑出 58 类报告；G-E2 部分达成——冒烟测已执行但红（CCE 产品断层），按止损棘轮拆债不扩面 | G-E1 `mvn -B -o test-compile -pl example` 0 错（ge1-round1/2.log）；G-E2 run-example 58 类（ge2-run3.log）+ probe 复现 CCE（ge2_probe）；G-E3 router 15/0/0、hbt 34/0/23、install BUILD SUCCESS；提交 98892ca06..168e1999b，账本 `.superpowers/sdd/2026-10-01-example-module-deps/progress.md` |
| 2026-10-01 | I-DEBT-6（新开） | ConnectionSmokeTest 冒烟红：`String→Object[]` CCE，SHOW DATABASES 单列行在产品侧输出裸 String 非 Object[]（SELECT 1 过）；疑点 hbt MetadataManager/MysqlMetadataManager 行形态；预批顺位插队到 I-DEBT-5 前（冒烟绿是 M1 达标表“连接认证”列直接证据，已入账本 Ruling） | surefire-reports 栈：druid JdbcUtils→ClientPreparedStatement→MycatTest:92；ge2 probe 实测复现 |
| 2026-10-01 | I-DEBT-7（新开） | example e2e 挂点两类：LocalXaSavepointTest 挂 9min+、SqlFunctionTest 挂 6min+，日志仅 Mycat oshi 心跳滚动无测试侧进展；容器 XA RECOVER 双向空=非孤儿锁；不跑 25min 全量扫荡的止损裁定 | ge2-run2/run3.log；XA RECOVER CONVERT XID 双容器空输出 |
| 2026-10-01 | I-DEBT-6 | **收口交付（Evidenced-Done）**：探针钉死真因=`RxBuiltInMethodImpl.select` 对 `map.apply` **返回值**烘焙 `(Object[])` 强转 × 单列标量投影返回 String（推翻仅凭栈的“输入吐 String”初猜）；修复=泛型化 `select<T,R>`；诊断面 Task1 PrepareExecutor 补 log.error 使服务端异常不再被吞 | G-S `ConnectionSmokeTest Tests run:1 Failures:0 Errors:0`（i6f-client.log）；G-C example e2e 首轮清单 60 类（18绿/42红）surefire 报告 grep ClassCastException=NONE、服务端日志 CCE=0（i6f-passfail.txt/i6f-server.log）；G-T `RxBuiltInMethodImplSelectTest` 2/2；42 红=SQLException 176 条（与 CCE 不同域→归 I-DEBT-7）；提交 b736d5d70..81f228c17，账本 `.superpowers/sdd/2026-10-01-smoke-cce-rootcause/progress.md` |
| 2026-10-02 | I-DEBT-7 → 拆 7A/7B/7C + **7A 收口（Evidenced-Done）** | 取证钉死 42 红塌缩为 3 正交根因（A 39 类 harness executeUpdate / B 池耗尽挂点 / C 真产品红）；7A=Root A 定性为纯 e2e 脚手架缺陷（`MycatTest.execute()→JdbcUtils.execute→executeUpdate`，Connector/J 发送前静态分类把 hint 误判结果集抛异常，未走网络），修复改 `Statement.execute()`；用户拍板拆债+起手 7A；example 完整性评估另立 I-DEBT-9（认证/TLS/dump/connector 结构性空白，广度尚可但 39 类跑不到断言） | 有界子集实跑（Docker m1-mysql-a/b）：`Can not issue executeUpdate` 残留=0、ConnectionSmokeTest 1/0/0、PushDownFunctionNormalTableTest 3/0/0 转绿、MegreJoin/BkaJoin 转 7C 域（服务端 NPE selectList）；证据 target/i7a_client.log+i7a_mycat.log；提交见本次 7A |
| 2026-10-01 | I-DEBT-8（新开） | I-DEBT-6 整支终审（fresh CodeReview，`javap` 字节码级复核确认烘焙强转已消失、42 红与 CCE 零关联）发现同族未收口：select 泛型化后标量行首次可达 `filter/topN/sort/toEnumerable/matierial`（仍烘焙 `(Object[])` 输入强转）→ 同名 CCE 风险；无 Critical，本债项修复正确可放行 | 终审包 `.superpowers/sdd/2026-10-01-smoke-cce-rootcause/review-8e3bc3f5b..b6c8f0d46.diff`；处置：select Javadoc 加同族指针 + 登记 I-DEBT-8（含 asObjectArray 基元数组守卫/契约 Javadoc 陈旧两项 Minor），本轮不扩面 |
