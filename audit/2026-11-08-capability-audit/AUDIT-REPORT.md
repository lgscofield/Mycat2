# Mycat2 二开 · 文档 vs 实证 能力核验报告

- 审计日期: 2026-11-08
- 被测构建: 本地源码 `mvn package` 成功(BUILD SUCCESS, 6min), jar=server/target/server-1.22-release-jar-with-dependencies.jar
- 后端: 本机 3306 **MySQL 8.4.6 Community**(注意: 仓库 commit 记录为"MySQL 8.0.46", 环境已漂移); 无 Docker; 第二后端未起
- persona: 缺省 MYSQL_5_7(握手 `5.7.33-mycat-2.0`, 与 CI posture 门牙判据一致)
- 隔离面: dbaudit_a/dbaudit_b 库 + audit 用户(dbaudit% 授权 + XA_RECOVER_ADMIN + PROCESS), 独立 mycat-home
- 探针: 42 项, 每项独立脚本+独立日志, 出口码 0/1/2; 全部可重跑(`probes/run-all.sh`)

---

## 一、总评: 文档可信度

**大方向诚实, 局部失真, 且存在"代码在但面不可达"类新缺陷。**

1. **诚实面**: 看板自评 "M5 生产就绪 0%、无任何版本严格满足六项验收、不得对外称支持" 与实测一致; 12 项差距里 7 项被逐字坐实(见 §三)。
2. **低估面(文档比实际差)**: 协议三缺口声明已过时——**游标、压缩协议、多结果集全部真实可用**; `disallowSqlTypes` 黑名单真实生效。上一轮评审(基于代码阅读)在这四处同样低估。
3. **虚高/漂移面**: I-DEBT-85 声称"与分片无关、**配置无关**"——实测与 Connector/J 版本强相关(9.3 不发裸 HAVING 则不复现); 环境记录漂移(8.0.46→8.4.6)。
4. **新缺陷(文档未记载)**: 13 项, 含 1 个"净室启动即崩"级别的 F1 和 1 个"YEAR() 杀连接"P1(F2), 见 §四。

---

## 二、能力矩阵修订版(实测口径)

| 能力 | 上轮评审口径 | **实测** | 证据 |
|---|---|---|---|
| 前端认证 native/caching_sha2 | 原生 | ✅ 坐实(含错误密码拒绝) | P-L1-01/02/04 |
| 前端 TLS | 原生(需配置) | ✅ 坐实: REQUIRED 模式真跑出 TLS_AES_256_GCM_SHA384(自签回落) | P-L1-03 |
| server-side prepare | 模拟 | ✅ 可用: 全类型往返保真(中文/NULL/DECIMAL/DATETIME/VARBINARY) | P-A-01 |
| 游标 COM_STMT_FETCH | **缺失** | ✅ **实际可用** | P-A-02 |
| 压缩协议 | **缺失** | ✅ **实际可用** | P-A-03 |
| 多结果集 | **休眠(写路径注释)** | ✅ **实际可用**(2 个结果集均可达) | P-A-04 |
| long data | 支持 | ✅ 1MB blob 逐字节往返 | P-A-05 |
| KILL 会话 | 支持 | ✅ + 事务中 kill 后 1s 无孤儿事务 | P-A-06/P-C-05 |
| 分片 CRUD/路由/分布 | 原生 | ✅ 120 行 55+55 均匀分布, 点查/范围/扇出全对 | P-B-01 |
| 跨片聚合/GROUP BY | 两段式 | ✅ 6 项与直连基线全等 | P-B-03 |
| 跨片 ORDER BY+LIMIT | TopN 归并 | ✅ 与基线一致 | P-B-11 |
| 跨片 UNION 列头保真 | 已修 | ✅ 类型/精度/scale 与直连一致 | P-B-10 |
| 跨片 JOIN | 内存执行 | ⚠️ 分片×全局 OK; **分片×分片自连接返 0 行(F6)** | P-B-04 |
| 标量函数 codegen | ~17 个已接 | ⚠️ 19/20 一致; **YEAR() 杀连接(F2), CAST 脏串崩(F3), 1/0 无告警(F5)** | P-B-05 |
| 告警子系统 | I-DEBT-69 已收口 | ⚠️ SHOW WARNINGS 泄漏内部错误行(F4); sql_mode 不传导(F7) | P-B-06 |
| 视图 | 支持 | ✅ | P-B-12 |
| information_schema | 模拟壳 | ✅ 基本面(SHOW TABLES/DESC/i_s)可用; getTables(null) 171 行 | P-B-07/09 |
| I-DEBT-85(GUI getTables) | 🔴 开放 | ⚠️ SQL 层坐实(逐字一致); 但 Connector/J 9.3 恰好绕开——**"配置无关"声明不成立** | SQL-BARE-HAVING |
| 本地事务/Savepoint | 原生 | ✅ | P-C-01 |
| XA | 原生+刚补强 | ⚠️ 同物理后端多 schema 静默降级为本地提交; 意向持久化有未记载部署前置(F11) | P-C-02/03 |
| statementTimeout | 默认 30s+3013 | ❌ **透传路径不生效(F8)** | P-C-04 |
| 微基准 | 空白 | ✅ 点查 1 线程 356 QPS / 8 线程 1328 QPS / 零错误 | P-E-01 |
| mysqldump 往返 | M3 ~100% | ⚠️ 普通表 HEX 逐值全等; **分片表 dump 含 DBPARTITION 私有语法不可回灌原生(F12)** | P-E-02 |
| 9066/9090 管控面 | 无认证 | ❌ 坐实 + **showDatasources 明文回显后端密码(F10)** | P-D-02/03 |
| 库表授权 | 代码注释 | ❌ 坐实: 新建用户全域可见 | P-D-04 |
| fullTableScanLimit | 死旋钮 | ❌ 坐实(limit=1+exception=true 仍放行) | P-D-05 |
| migrate 状态持久化 | 纯内存 | ❌ 坐实: 重启即失 | P-D-06B |
| disallowSqlTypes | 存疑 | ✅ **实际生效**(DELETE 被拦, 报错清晰) | P-D-08 |

NO_DATA 项(环境不支持, 非缺陷): 读写分离/副本延迟(无第二后端)、XaReaper 活性(无 docker pause)、old-connector 5.1.49(jar 不可得)、后端 native 认证(MySQL 8.4 默认禁用 mysql_native_password)。

---

## 三、上轮差距清单逐项复核

| 上轮差距 | 复核结果 |
|---|---|
| 1. 多实例组网/hint 配置重启即失 | ✅ 一致且**更糟**: createDataSource hint 连解析都过不了(F9), 面不可达 |
| 2. 管控面无认证/无库表权限 | ✅ 坐实(9066/9090 + 新用户全域) + 明文密码回显新证据 |
| 3. 无压测/容量 | ✅ 一致; 本轮已产出首个基线(356/1328 QPS) |
| 4. GUI 破口 I-DEBT-85 | ⚠️ 缺陷真实(SQL 层)但"配置无关"声明被证伪; Connector/J 9.3 客户端不受影响 |
| 5. XA 收尾 | ⚠️ 部分一致: 单物理后端场景连 2PC 都不进(F11); kill 清理面健康(1s 无孤儿) |
| 6. 语义残留 | ✅ 一致 + 新增 F2/F3/F5/F6 |
| 7. migrate 状态不持久 | ✅ 坐实 |
| 8. 虚拟线程未落地 | 未重验(代码级结论沿用) |
| 9. 文档陈旧 | ✅ 一致 + 环境漂移实证(8.0.46→8.4.6) |
| 10. i_s 深面缺口 | ⚠️ 基本面可用; 深面未逐项测 |
| 11. TLS 默认 DISABLED/自签回落 | ✅ 一致(REQUIRED+自签回落实测可用) |
| 12. MariaDB 方言腿 | 未测(无 MariaDB 后端) |

---

## 四、新发现缺陷清单(文档未记载)

| # | 严重度 | 缺陷 | 复现 |
|---|---|---|---|
| F1 | **高(冷启动)** | **净室启动即坏**: mycat-home 只有 datasources+users 无 clusters/ 时, `MysqlVariableServiceImpl` 回落默认变量表, session 表缺 `max_allowed_packet` → Connector/J 引导查询 `SELECT @@session.max_allowed_packet,...` → `MySQLVariablesUtil.toInt("null")` NumberFormatException → **任何 JDBC 客户端无法连接**。`fixValue` 对 null 不设防 | 第一次 boot(P-L1-01/02/03 全 RED), 补 clusters/ 后消失 |
| F2 | **P1** | `SELECT YEAR('2026-11-08')` → mycat codegen 产出 `java.sql.Date 2026-01-01` → 序列化抛 `UnsupportedOperationException: 2026-01-01` → **连接被强断**(ERROR 2013)。11 个兄弟日期函数(MONTH/QUARTER/WEEK/DAYOFYEAR/DAYNAME/MONTHNAME/LAST_DAY/TO_DAYS/DATEDIFF...)全部正常 | 3 次独立复现 |
| F3 | 中 | `SELECT CAST('abc' AS SIGNED)` → `NumberFormatException: "abc"`(真库: 0 + warning 1292) | 手工复现 |
| F4 | 中 | `SHOW WARNINGS` 在**全新会话**吐出内部错误行 `CalciteContextException: Column '$$' not found in any table`(直连为空)——内部解析失败缓冲泄漏到客户端 | 手工复现 |
| F5 | 低 | `SELECT 1/0` → NULL 但无 1365 告警(真库有), warning_count 不涨 | P-B-06 |
| F6 | 中 | 分片×分片自连接 `j1 a JOIN j1 b ON a.id = b.id - 1` 返回 **0 行**(应 29); 同查询分片×全局正确 | P-B-04 |
| F7 | 中 | 会话 `SET sql_mode=''` 只放宽 mycat 校验层, **不向后端物理连接传导**(截断插入仍 1406 报错) | P-B-06 |
| F8 | 中 | `statementTimeout`(默认 30s)对 prototype 透传路径不生效: `SELECT SLEEP(35)` 35s 正常返回, 无 3013 | P-C-04 |
| F9 | 中 | `/*+ mycat:createDataSource{...} */` **Druid 解析层直接拒绝**(expect `(`), 空 JSON 同死——管理 hint 面不可达 | P-D-01 |
| F10 | 高(安全) | `/*+ mycat:showDatasources{} */` **明文回显后端数据源密码**(叠加 9066/9090 无认证) | P-D-03 日志 |
| F11 | 中 | 单物理后端多 schema 的 XA 事务静默降级为本地提交(无 2PC 无意向); 真 2PC 意向持久化要求后端存在 `mycat` 库且用户可写——**未记载部署前置**(CI 用 root 后端掩盖) | P-C-03 + 源码 LocalXaSqlConnection.commit 分支 |
| F12 | 低 | 分片表 mysqldump 含 `DBPARTITION BY` 私有语法不可回灌原生 MySQL; dump 的 CREATE 限定源库名+INSERT 不限定 → 跨库恢复失败; 普通表同库回灌逐值保真 | P-E-02 |
| F13 | 低 | 解析失败路径日志伪影 `sqlStatement:SELECT $$ AS $$` | boot 日志 |

---

## 五、对账结论(你要的"文档能不能信")

- **DEBT-QUEUE 的"红项"可信**: M5=0%、管控裸奔、死旋钮、migrate 不持久——全被实测坐实。
- **"绿项"要打七折读**: 协议三缺口其实是好消息(能力已存在); I-DEBT-85 的"配置无关"是坏的偏差(真实但条件不同); XA 意向持久化的"已落地"在真实部署约束(非 root 后端)下不成立。
- **未记载的部署前置(生产必踩)**: 后端用户需要 mycat 库写权限(xa_log)、max_connections 需抬升(1000/DS 超订)、mysqldump 需 `--default-character-set=utf8mb4`、XA 真正 2PC 需多物理后端。
- **环境记录会漂移**: 以探针为准, 不以 commit 记录为准。

## 六、复跑方式

```bash
cd audit/2026-11-08-capability-audit/probes
export PROBE_CLASSPATH="<repo>/server/target/server-1.22-release-jar-with-dependencies.jar"
bash run-all.sh P-L1-01.java P-A-01.java ...   # java 探针
bash P-D-01.sh A; bash P-E-02.sh; ...          # shell 探针
# 明细: ../summary-final.csv + ../logs/<probe>.log
```

隔离残留: dbaudit_a/b 库、dbaudit_shard* 分片库、audit 用户留在本机 3306; mycat 实例(TLS REQUIRED 档)仍在 8066 运行, 如需清理: `taskkill` 掉 8066 进程 + `DROP DATABASE dbaudit%` + `DROP USER audit@'%'`。
