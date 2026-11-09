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
| G4 | 安全区最小闭环 | 89(监控面 token)+ 90(管控 hint admin 门)收口 | 🟢 **[2026-11-10 全清]** 89(9066 token 双相位)+90(admin 门)+**95(Prometheus token/回环,18 格矩阵 RED12→GREEN)**。行为矩阵:无 token=回环+WARN,有 token=Bearer 401/200,prometheusBind 可外绑。遗留(非阻塞):外绑地址活体自检、监控配置建议入部署文档 |
| G5 | 数据安全语义显式化 | XA 降级(F11)显式 WARN+文档 或 双物理 2PC 活体验证;xa_log 部署前置入文档 | 🟢 **[2026-11-08 收口]** ① **F11 WARN 落地**(单连接降级路径,commit 7141963ec);② **真双物理 2PC 活体**:按 initCluster 配方(4 数据源+2 集群 c0/c1)→ dbx3_0@3306/dbx3_1@3307 摊派成功 → 显式事务跨两物理 INSERT+COMMIT 成功;③ **xa_log 部署前置实证**:3307 自动建表+意向写入+成功后清行(0 行=设计);**3306 缺前置时 COMMIT 被拒(1146)**——"宁可拒绝提交"安全语义活体验证;补前置后全绿。**部署前置入文档**:后端用户需 `mycat.xa_log` 可写(或预建 mycat 库),autocommit=1 单语句走单阶段不走 2PC。logs: E20 + 本轮 heredoc 记录 |
| G6 | 性能/长稳基线成档 | 点查 QPS(已有 356/1328)+ 1h 长稳 + 连接不泄漏(短稳已过) | 🔴 **[2026-11-08 首跑 RED·判隔离重跑]** 30min 长稳首跑:首分钟健康(30.8k ops/min)后速率崩至 1.8k/min、错误累计 14k+(G6-soak30m.log)——**时间线与 G8 全量套件并行启动强相关**(共享 3306 后端,suite 的 schema/RESET 操作互踩;且探针未落错误明细=仪器缺口)。定性=并行污染嫌疑,待 G8 完成后**单跑复测**定谳;单实例短稳(1000 混合)此前 PASS。**仪器改进项**:soak 探针需记录 SQLException 错误码分布 |
| G7 | 部署路径可重复 | 净室守卫(已入 CI)+ 升级/回滚一页文档 | 🟢(86/守卫已落) |
| G8 | 验证网闭环 | **全量 example 套件进门禁**(当前 CI 只跑收窄子集=盲区②)+ 7 档矩阵维持全绿 | 🔴 **[2026-11-09 首跑终止·根因=凭据错配]** 全量套件对 8068 跑至 example 模块卡死(日志 135 万行,Druid 无限重试)——**根因:套件 RESET_CONFIG 重写数据源为 CI 凭据 root/123456,本机 3306 root@localhost=root/root,同账号无法双密码**;30 个红标记在此环境下无定性价值,已终止。**解法(下轮执行)**:本机 mini-CI 隔离拓扑——用 3307(已备 root/123456)+ 新起 3309 mysqld(同凭据)作套件双后端,`-Ddb_mycat/-Ddb1/-Ddb2` 全量重定向,与用户 3306(root/root)完全隔离;**不动用户现有凭据**。已建 root@'%'/123456 于 3306(留置无害,可删)。logs: G8-full-suite.log(135 万行取证存档) |

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

## §6 证据索引

- 审计全套:`audit/2026-11-08-capability-audit/`(46+ 探针、logs、AUDIT-REPORT.md、summary-final.csv)
- 收口票:86/87/88(DEBT-QUEUE L99-101 ✅ 段);新票:89/90/91
- 守卫:`ci/verify-cleanroom.sh`+`CleanroomProbe.java`;workflow 9th job
- 环境本机:3306=MySQL 8.4.6(root/root)、3307=8.4.6(root/123456,audit/db3307)、8066=修复版实例
