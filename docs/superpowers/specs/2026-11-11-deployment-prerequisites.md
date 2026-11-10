# Mycat2 生产部署前置清单(画像 v1 · PROD-GATE 交付物)

> 每条都有活体证据出处(本机审计轮 2026-11-08~11);不满足即按"后果"列处理。

## 1. 后端数据库前置(每个物理实例)

| 前置 | 原因 | 后果(不满足) | 证据 |
|---|---|---|---|
| `max_connections` ≥ 数据源数×maxCon 建议值 | 数据源缺省 maxCon=1000/DS,出厂 151 会结构性超订 | 池借连接阻塞→GetConnectionTimeout 长挂 | I-DEBT-7B;P-D-07 |
| **XA 启用时**:后端用户需可写 `mycat.xa_log`(或预建 `mycat` 库) | 提交意向持久化写各参与者后端;缺权限=**COMMIT 被拒(1146)**——设计如此(宁拒不假持久) | XA 事务无法提交 | G5 活体(3306 缺前置→1146) |
| 后端用户具 `XA_RECOVER_ADMIN` | mycat 恢复流程 XA RECOVER | reaper 收敛失效 | XaTestSuite:497 |
| autocommit=1 下单语句 XA 走**单阶段**(无 2PC) | 语义事实:跨机原子性需显式事务 | 误以为单语句有分布式原子性 | G5 记录 |

## 2. 客户端兼容前置

| 前置 | 说明 |
|---|---|
| mysqldump 用 `--default-character-set=utf8mb4` | 缺省下中文经代理转码损化(实测);分片表 dump 含 `DBPARTITION` 私有语法**不可回灌原生 MySQL**——分片表备份走 mycat 侧或用物理库 dump |
| `allowPublicKeyRetrieval=true`(Connector/J) | caching_sha2 full-auth over 明文必需 |
| 会话 `SET sql_mode=''`(放宽)只影响 mycat 校验层 | **不向后端传导**(I-DEBT-99 已修传导对齐,但 SET 发生在借出时;已持连接事务中途改不重对齐——见 99 遗留) |
| `SELECT 1/0` 告警 1365 已由诊断槽落地(97) | 需 ≥ 本轮构建;Arrow 向量路径遗留 |

## 3. Mycat 实例运维前置

| 项 | 说明 | 后果 |
|---|---|---|
| **孤儿配置自愈已内置**(98) | 集群引用缺失数据源:启动 WARN+跳过+移入 `<home>/orphan/`(orphan-record.jsonl 审计) | 此前:启动即死(IllegalArgumentException) |
| **RESET_CONFIG 会重写数据源为出厂凭据**(123456@localhost:3306) | 运维 hint/套件行为——**部署特定凭据会被覆盖** | 实例后端连接全断(实测多次)——生产禁用 resetConfig 或改后 reset 后核对 datasources |
| 监控面:9066 缺省绑 localhost;配置 `monitorToken` 后强制 Bearer 401/200 | 无 token=无认证(仅回环) | 外网暴露=任何人拉监控数据 |
| Prometheus:配置 `prometheusPort` 才起;**无 token 时强制回环+WARN**,`monitorToken`+`prometheusBind` 可外绑 | I-DEBT-95 行为矩阵 | 同上 |
| `fullTableScanLimit` 为**死旋钮**(无消费点) | 广播全扫无拦截——容量规划须自行约束 | 大结果集内存风险 |
| hint `createDataSource` 等配置写**持久化到 mycat-home**(91 修复后);重启保持 | 运行态改配置不再重启即失 | — |

## 4. XA 使用边界(画像 v1)

- 单物理实例多 schema:**本地事务(proxy)即为最强语义**——XA 会静默降级为本地 commit(WARN 可见,G5);无需也无法走 2PC
- 双物理实例:显式事务(autocommit=0)+COMMIT 才走真 2PC;前置见 §1
- 事务中途 `SET sql_mode` 不重对齐(99 遗留)

## 5. 部署健康自检(部署后 5 分钟)

```sql
-- ① 连通与画像
SELECT 1; SELECT version();          -- 应返回 persona 宣称版本族
-- ② 净室启动健康(缺 clusters/ 也可用的证明)
SELECT @@session.max_allowed_packet;  -- 应 16777216(非 NULL 非 3013)
-- ③ XA 前置
SELECT COUNT(*) FROM mycat.xa_log;    -- 表存在即可;权限不足会启动后 XA commit 拒绝
-- ④ 监控面
-- curl 127.0.0.1:9066/ShowInstanceMonitor   (配置 monitorToken 后需 Bearer)
```
