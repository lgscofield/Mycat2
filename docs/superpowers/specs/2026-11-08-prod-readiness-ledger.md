# PROD-READINESS-LEDGER · 生产级可用账本(v1 · 2026-11-08)

> 维护人约定:本账本是**生产可用的唯一进度真相源**,与 DEBT-QUEUE(兼容性债务队列)并行。
> DEBT-QUEUE 回答"兼容性账清没清",本账本回答"**能不能上生产**"。每项判定一律以可重跑探针为准(证据索引见 §6),不信文档描述。

## §1 目标(对齐后)

**原目标**(LTS 兼容总纲):客户端眼中无缝替代 MySQL 5.7~9.x / MariaDB 预留位 —— 这是**兼容性完备**目标,M5(生产就绪)自述"关键路径外 0%"且永远排不进关键路径。

**再对齐后目标(本账本采用)——PROD-GATE:在一张明确的生产画像下,八条硬门全绿即"生产级可用"**:

> 生产画像 v1(可按你的真实场景修订,这是本账本唯一需要你确认的输入):
> 后端 = MySQL 8.0/8.4 × 2~N(同版本族);客户端 = Connector/J 8.x/9.x + go-sql-driver;
> 拓扑 = 分片表(单物理后端多库 或 双物理后端)+ 读写分离;MyCat 部署 = 单实例(HA 后置);
> 事务 = 本地事务为主,XA 限定双物理后端场景才启用。

| # | 硬门 | 判据 | 状态 |
|---|---|---|---|
| G1 | 配置写路径活:hint 配置/落盘/重启保持 | I-DEBT-91 收口 + V-F9-recheck PASS + 重启保持 | 🟢 **[2026-11-08 收口]** 修法A:5 文件 import 换 JsonUtil(toJson/from/clone)+ DatabindCodec.mapper()(va 两文件,XA 意向日志在列);活体:`execute ok` + **DISK_OK**(probeDs9.datasource.json 落盘)+ **RESTART_PERSIST_OK**(重启仍在)+ 9066 200。logs: E7c/E8d/V-F9-recheck5 |
| G2 | **错误结果类清零**(正确性红线) | F6(分片×分片自连接 0 行)收口;F10 列头类已验 | 🟢 **[2026-11-08 收口·I-DEBT-92]** 根因=`SQLRBORewriter.pushDownERTable` 对全派生表达式 join 条件(pairs 全被 isDerived 跳过)→ keysMap/equals 空 → 空 allMatch+空 isErJoin 恒 true → 误判 ER 下推为每分片本地 JOIN。窄修:equals 空即回退内存 JOIN(SQLRBORewriter,MYCAT CHANGE)。活体:表达式自连接 **29/29**(修复前 0)+ shard×global 30/30 不回归 + 纯等值 5 不回归。logs: P-B-04-final/E15 |
| G3 | 崩溃/挂死类清零 | F3(CAST 脏串崩)、F8(statementTimeout 不生效→挂)收口;87/86 已修 | 🟢 **[2026-11-08 收口]** F3=`SqlFunctions.toInt/toLong(String)` MySQL 语义(非法串→0,活体 0/42/42 对齐真库);F8 根因重定性=SLEEP 走**本地计划腿**(MycatProject(MycatValues),非后端腿)→ `DrdsRunnerHelper.runOnDrds` 包 StatementDeadline(全库 statementTimeout 最小值,缺省 30s),活体 32s 击杀 3013;残留=本地 janino 线程自然耗尽(非泄漏,客户端已解挂)。logs: E12/P-C-04 |
| G4 | 安全区最小闭环 | 89(监控面 token)+ 90(管控 hint admin 门)收口 | 🟢 **[2026-11-11 追加 98]** 89(9066 token 双相位)+90(admin 门)+95(Prometheus 18 格)+**98(孤儿配置启动崩三层修复:WARN+跳过/心跳流防线/自愈隔离区,RED 与生产逐字一致)**。运维健壮性升级:清理过的实例可自愈重启 |
| G5 | 数据安全语义显式化 | XA 降级(F11)显式 WARN+文档 或 双物理 2PC 活体验证;xa_log 部署前置入文档 | 🟢 **[2026-11-08 收口]** ① **F11 WARN 落地**(单连接降级路径,commit 7141963ec);② **真双物理 2PC 活体**:按 initCluster 配方(4 数据源+2 集群 c0/c1)→ dbx3_0@3306/dbx3_1@3307 摊派成功 → 显式事务跨两物理 INSERT+COMMIT 成功;③ **xa_log 部署前置实证**:3307 自动建表+意向写入+成功后清行(0 行=设计);**3306 缺前置时 COMMIT 被拒(1146)**——"宁可拒绝提交"安全语义活体验证;补前置后全绿。**部署前置入文档**:后端用户需 `mycat.xa_log` 可写(或预建 mycat 库),autocommit=1 单语句走单阶段不走 2PC。logs: E20 + 本轮 heredoc 记录 |
| G6 | 性能/长稳基线成档 | 点查 QPS(已有 356/1328)+ 1h 长稳 + 连接不泄漏(短稳已过) | 🔴 **[2026-11-12 状态]** 本机长跑三次被环境杀死(休眠/会话终止,实证记录在案)——**结论:长稳与全量套件的权威归宿都是 CI**(full-suite job 已 🟢 验证此路线可行);G6 应做成 CI job(30 分钟,GitHub runner 不休眠),本机不再承载长任务。116 票的活体验证同样待健康环境 | 30min 长稳首跑:首分钟健康(30.8k ops/min)后速率崩至 1.8k/min、错误累计 14k+(G6-soak30m.log)——**时间线与 G8 全量套件并行启动强相关**(共享 3306 后端,suite 的 schema/RESET 操作互踩;且探针未落错误明细=仪器缺口)。定性=并行污染嫌疑,待 G8 完成后**单跑复测**定谳;单实例短稳(1000 混合)此前 PASS。**仪器改进项**:soak 探针需记录 SQLException 错误码分布 |
| G7 | 部署路径可重复 | 净室守卫(已入 CI)+ 升级/回滚一页文档 | 🟢(86/守卫已落) |
| G8 | 验证网闭环 | **全量 example 套件进门禁**(当前 CI 只跑收窄子集=盲区②)+ 7 档矩阵维持全绿 | 🟢 **[2026-11-11 达成]** CI full-suite job **success**(run 42923560040,00:23Z 完成,~1h 跑完 555 例;修正三连:启动实例步骤缺失/YAML 冒号断裂/触发层分支缺失)。**盲区②正式关闭**:全量套件成为 CI 门禁常驻形态(gate 分支 push 触发)。首跑 5h 取消教训入簿(无实例=连接超时空转)。旧 run 已取代 | 本机长跑三次被环境杀死(休眠/控制台生命周期)后,G8 终态形态落地:① **CI full-suite job**(workflow_dispatch+gate 分支 push 触发,300min 超时,CI 凭据直配,**run 42849914398 in_progress**,555 例在 GitHub runner 上跑);② 本机 13 批断点续跑器并行(健康批次判据=done.txt 增长)。**双坑入簿**:workflow run 值含冒号空格→YAML 断裂 startup failure 零 job(已修);`on.push.branches` 不含 gate 分支则 job if 永不评估(已补+其余 job gate 分支跳过)。**结构性结论(证据链完整)**:测试的 RESET_CONFIG 强制出厂凭据(123456@localhost:3306),与任何非 CI 凭据宿主结构性冲突 → **全量套件的权威归宿=CI**,本机长跑仅作补充。CI run 完成后红项定性→G8 🟢/立票 |

**生产级可用 = G1~G8 全绿 + 画像内探针矩阵全 PASS。**

## §2 已验证能力(可信底座,全部有探针证据)

- **连接/认证/TLS**:native+caching_sha2+RSA、TLS REQUIRED 真跑出 cipher、错误密码拒(L1-01~04)
- **server-side prepare 全类型保真**(含中文/NULL/DECIMAL/VARBINARY)、1MB long-data(A-01/05)
- **游标/压缩/多结果集实际可用**(协议三缺口声明已证伪,A-02/03/04)
- **分片核心**:mod_hash 路由/分布 55+55、点查/范围/扇出、跨片聚合+GROUP BY 全等、ORDER BY+LIMIT 归并、UNION 列头保真、非分片键广播、视图(B-01/02/03/10/11/12)
- **信息面**:SHOW TABLES/DESC/i_s 基本面、getTables(null)(B-07/09)
- **事务**:本地+savepoint、autocommit=0 跨分片、kill 事务中会话 1s 无孤儿(C-01/05/07)
- **运维**:净室启动可连+21 变量可读(86-B)、mysqldump 普通表原生回灌 HEX 逐值全等、微基准 356/1328 QPS 零错误、KILL 边界、1000 笔混合零泄漏(E-01/02/05)
- **底座**:JDK21+Calcite fork+依赖治理(影子清除)、CI 9 job(7 档矩阵全 experimental:false + old-connector + cleanroom)

## §3 债务分级(生产视角,四类)

**A 数据安全**(上生产前必须显式化):
- F11 XA 单物理后端静默降级为本地提交(无 2PC/无意向);xa_log 需后端 mycat 库+写权限(未记载前置) → G5

**B 正确性/稳定性**(红线类,乱序结果=事故):
- 🔴 91 vertx Json classpath 分裂 → 配置写路径运行时全崩 + 全量套件不可能绿(G1/G8 根因,**最高杠杆**)
- 🔴 F6 分片×分片自连接返 0 行(错误结果)
- 🔴 F3 CAST('abc' AS SIGNED) NumberFormatException(真库=0+告警)
- 🔴 F8 statementTimeout 对透传路径不生效(SLEEP 挂 35s 无 3013)
- 🟡 F5 1/0 无 1365 告警;F7 会话 sql_mode 不向后端传导;F4 SHOW WARNINGS 泄内部 `$$` 错误行;F13 日志伪影

**C 安全**:
- 🟡 89 监控面无认证;🟡 90 管控 hint 无授权(任意账号可改集群配置);88 已修;URL 内嵌口令形参(随 90 一并裁定)

**D 运维/产品化**:
- 多实例组网(无);migrate 状态不持久(I-DEBT-73 仪器已修,产品态仍纯内存);doc/ 陈旧(README java8 vs 实际 21);F12 分片表 dump 私有语法不可回灌原生(需文档明示);后端部署前置未文档化(max_connections=1000、mycat.xa_log 权限、mysqldump --default-character-set)

## §4 序贯路线(按杠杆率排序,预估以"你的节奏"折算)

1. **91**(1 轮):3 处 import 换 JsonUtil/DatabindCodec + fat jar 单版本断言 → 解锁 G1+G8(全量套件进门禁)
2. **F6+F3+F8**(1 轮):三个探针都在,RED→GREEN 即可 → G2/G3 清零
3. **89+90**(1~2 轮):token+回环缺省、admin 门+hint 三态分类 → G4
4. **G5**(0.5 轮):XA 降级显式化(WARN+文档)或双物理 2PC 验证(3307 已就绪)
5. **G6**(0.5 轮):1h 长稳 + 基线成档
6. **G8 收尾**(0.5 轮):全量套件 CI 化(91 后)+ 画像矩阵固化
7. 文档一页(部署前置)+ PROD-GATE 终审 → **宣布生产级可用(画像 v1 内)**

## §5 与 DEBT-QUEUE 的关系

DEBT-QUEUE 仍为兼容性债务真相源(85+89/90/91 已登记);本账本引用其票号,不重复维护细节。冲突时:生产判定以本账本为准,兼容性判定以 DEBT-QUEUE 为准。


## §5b · LTS 版本支持差距矩阵(评审6 · 2026-11-14)

**结论:七版均可宣称 CRUD 级(CI 硬门背书);无一达"深度级"。** 证据:`agent_d33463ed`(评审6 全文)、`example/.../compat/` 17 类、persona 代码面。

| 版本 | 可宣称等级 | 到深度级差距 |
|---|---|---|
| MySQL 5.7 | CRUD 级(CI 硬门) | GROUP BY 隐式排序/SQL_CALC_FOUND_ROWS/旧式密码负向保真/5.7 专项语法矩阵 |
| MySQL 8.0 | CRUD 级 | I-DEBT-85 GUI 列库(远端已收口·待 CI 复证);CTE/窗口/JSON_TABLE 专项矩阵(零用例);i_s 8.0 列面逐列对拍 |
| MySQL 8.4 | CRUD 级 | 8.4 专项语法矩阵未建;native password 禁用负向保真(伪装比真库宽松);utf8mb3 弃用面 |
| MySQL 9.x | 连接+CRUD 级 | VECTOR 系统面(仅 I-DEBT-67 单点);9.x 特性全 NO_DATA |
| MariaDB 10.6 | CRUD 级(ed25519+dump 全绿) | sequence 面(CREATE SEQUENCE/NEXT VALUE FOR 全仓无实现) |
| MariaDB 10.11 | CRUD 级(uca1400 修复后) | @@old_mode 读面(I-DEBT-70 挂账残留·实缺口) |
| MariaDB 11.4 | CRUD 级(离线) | 11.4 档活体往返(I-DEBT-70 残留);SHOW 新旧语法实发验证 |

**系统性债(全版本)**:会话变量透传覆盖度(@@old_mode 同类)、SQL 审计/权限透传无实现面(代理前后端身份解耦)。

## §5c · 模式族灭绝度(评审5 · 2026-11-14)

| 族 | 灭绝度 |
|---|---|
| 族1 vacuous-true | 已评审域清;**存活 2 确认(BinlogUtil:419 死代码、recoveryXA:836)+2 疑似**(I-DEBT-145) |
| 族2 盲转/裸 parse | **最大聚集地=replica 心跳策略族**(I-DEBT-142,4 处同构);hbt datefunction 残留(StringToTimestampFunction,I-DEBT-144);divide/CAST/MOD 已清 |
| 族3 静态无上下文 | 基本收敛(divide/CAST/MOD 已落诊断槽);残留 NOW 系时区(F11/F12 疑似) |
| 族4 classpath Json | 主运行路径灭绝(残留仅注释+assistant 不进 fat jar) |
| 族5 凭据回显 | 存活 1 处(F5:URL/DRIVER 列,自认欠账);showDataSources/showConfigText/MIGRATE_LIST 已修 |
| 族6 配置覆写 | 灭绝(全量替换语义) |
| 族7 启动孤儿 | ConfigUpdater 侧清;**运行时半程存活**(I-DEBT-140 空集群裸崩) |
| 族8 危险默认 | 存活 3 处(热重载 alive=true/未知 target 缺省 mysql/出厂弱凭据) |

## §6 证据索引

- 审计全套:`audit/2026-11-08-capability-audit/`(46+ 探针、logs、AUDIT-REPORT.md、summary-final.csv)
- 收口票:86/87/88(DEBT-QUEUE L99-101 ✅ 段);新票:89/90/91
- 守卫:`ci/verify-cleanroom.sh`+`CleanroomProbe.java`;workflow 9th job
- 环境本机:3306=MySQL 8.4.6(root/root)、3307=8.4.6(root/123456,audit/db3307)、8066=修复版实例
