# R4 前端 TLS（I-AU-2）设计

> **这是什么**：里程碑 `COMPAT-MILESTONE-PLAN.md` **R4**（前端 TLS · 招牌最硬门）的设计文档（spec）。口径冲突一律以兼容主 spec `2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md` §3/§5 + 本文件引用的 **MySQL/MariaDB 官方文档**为准。
>
> **架构级 · Spec 先行**：TLS 是净新增能力（主 spec L53 已评估"全仓前端无 TLS 路径…净新增能力，非清理"），非单点修补，故先出本设计。
>
> **校准**：2026-10-12，凭当前代码 `file:line` + MySQL/MariaDB/Vert.x 官方文档实读，非凭记忆/旧记录。

---

## 1. 北极星与验收判据

**目标**：客户端眼中无缝替代 MySQL 各版本 ⇒ Mycat 前端 8066 必须支持 MySQL 协议的 **TLS 加密连接**；否则云/托管 DB 场景 `require_secure_transport=ON` 下客户端第一脚都迈进不来（对齐层级 **A**，M2「连接达标」的 TLS on/off 判据）。

**R4 出口判据（可跑的客观检查）**：
- `sslMode=REQUIRED`（Connector/J）/ `--ssl-mode=REQUIRED`（CLI）能连上 8066 并完成 CRUD；
- compat **TLS 轴 on/off 逐版本转 PASS**（把 R0 留的 NO_DATA 占位点亮，绝不假绿）；
- 明文（`sslMode=DISABLED`）仍全绿（TLS 不得回退伤既有认证/CRUD）。

**不在 R4（划界）**：后端到真实 MySQL 的 TLS（`ConnectHandler` 的 `upgradeToSsl` 死变量属后端侧，另论）；caching_sha2 over-TLS 的 full-auth 简化路径（TLS 下明文送口令属认证细节，见 §6 附注）。

---

## 2. 官方协议依据（MySQL/MariaDB 文档实读）

**TLS 是同端口内带 STARTTLS 升级，非独立 TLS 端口**（`dev.mysql.com` Connection Phase · SSL Handshake）：

```
1. 明文：Server → Protocol::Handshake（capability 宣称 CLIENT_SSL）
2. 明文：Client → Protocol::SSLRequest（截断的 HandshakeResponse，置 CLIENT_SSL）
3. 同连接上做 TLS/SSL 握手（切换到加密流）
4. TLS 上：Client → Protocol::HandshakeResponse（真正的认证响应）
5. TLS 上：认证照常交换（AuthMoreData/OK/ERR）
```

**Protocol::SSLRequest 精确字节**（`CLIENT_PROTOCOL_41` 下，共 32 字节）：

| 字段 | 类型 | 说明 |
|---|---|---|
| client_flag | int<4> | Capabilities，**必须置 CLIENT_SSL(2048)** |
| max_packet_size | int<4> | 最大包 |
| character_set | int<1> | 字符集低 8 位 |
| filler | string[23] | 全 0 填充 |

**能力位**：`CLIENT_SSL = 2048`（0x800）。握手包必须宣称它，客户端才会发 SSLRequest。

**MariaDB 线级兼容**：MariaDB 沿用同一 MySQL 连接期协议（SSLRequest 内带升级），Connector/J `useSsl` 与 mariadb-connector-c `tls_*` 走同一 STARTTLS。**计划内单列一步用 MariaDB Connector 文档复核确认**（落实"拿不准查官网"，不臆断）。

---

## 3. 现状证据（当前代码）

- **前端从不宣称 CLIENT_SSL**：`config/.../MySQLServerCapabilityFlags.java:195` `getDefaultServerCapabilities()` 里 `// flag |= CLIENT_SSL;` 被注释；`CLIENT_SSL`/`isSwitchToSSLAfterHandshake`(:246)/`setSwitchToSSLAfterHandshake`(:402) 常量与判定已就位但无人置位。
- **前端 acceptor**：`server/.../vertx/VertxMycatServer.java:134-158` 用 Vert.x `vertx.createNetServer(netServerOptions)` + `connectHandler(socket -> new VertxMySQLAuthHandler(...))`；`NetServerOptions` 未设 SSL。
- **握手/认证**：`server/.../vertx/VertxMySQLAuthHandler.java:98-108` 发 handshake（capability 来自 profile），`:110-` `handle(Buffer)` 逐包解析；`MySQLClientAuthHandler.createHandshakePayload(...)` 拼握手。
- **后端死变量**：`server/.../mysqlclient/command/ConnectHandler.java:174` `boolean upgradeToSsl;` 声明未用（后端侧，本次不动）。
- **依赖栈**：`pom.xml` 真·Vert.x **4.5.14** + Netty **4.1.119.Final**；`server/pom.xml:188-192` `vertx-core` 在，Netty `netty-handler`（含 `SslHandler`/`SelfSignedCertificate`）随 vertx-core 传递在类路径。
- **全仓无任何证书/keystore/SslContext 代码或配置**（grep setSsl 仅命中注释掉的 datasource 池配置 L63）。

---

## 4. 核心技术闸口：Vert.x 服务端无公开"中途升级 TLS"API

MySQL STARTTLS 要求**在同一条已建立明文 TCP 上、读完 SSLRequest 后把后续流切成 TLS**。Vert.x `NetServerOptions.setSsl(true)` 只做**接入即隐式 TLS**（整条连接从第一个字节就是 TLS），与协议不符，**不可直接用**。Vert.x **server 端 `NetSocket` 无公开 `upgradeToSsl`**（社区 issue #2104 佐证：Vert.x 不干净地处理 connect handler 内 startTLS）。

⇒ 必须在 Netty 层把 `SslHandler` 插进这条连接的 pipeline。三条路线：

### 方案 A（推荐 · 正确但体量中偏大）：前端 acceptor 用裸 Netty pipeline 承载 STARTTLS
把 8066 前端接入改为 Netty `ChannelPipeline`：MySQL 包 `LengthFieldBasedFrameDecoder`（3 字节长 + 序号，按 MySQL packet header）+ 现有握手/认证处理器；收到 SSLRequest 时 `pipeline.addFirst(sslHandler)`（Netty `SslContextBuilder`），其后 HandshakeResponse/认证帧天然走解密流。
- 优：**协议正确**、TLS 语义在 Netty 原生掌控（安全路径稳）、后续 HTTP/压缩等升级同受益、不耦合 Vert.x 内部。
- 代价：需重接现有 Rx/`VertxSession`/背压与 `MetaClusterCurrent` 装配（前端 socket 抽象从 Vert.x `NetSocket` 迁到 Netty `ChannelHandlerContext`），blast 中等偏大。

### 方案 B（最小但脆弱 · 次选）：探入 Vert.x 内部拿 channel 注 SslHandler
`connectHandler` 里 `socket` 实为 `io.vertx.core.net.impl.NetSocketImpl`（内部类）；反射/强转取其 `Channel`，收到 SSLRequest 后 `channel.pipeline().addBefore(...)` 注入 `SslHandler`。
- 优：改动面最小，acceptor 仍走 Vert.x。
- 代价：**耦合 Vert.x 内部私有类**（升 Vert.x 即碎）；TLS 属安全敏感路径，内部时序若处理不当（如未先摘掉 Vert.x 明文解码 handler）可能**静默不加密或握手错乱**——风险不可接受地高。

### 方案 C（否决）：独立隐式 TLS 端口
`setSsl(true)` 单开一个 TLS-only 端口。
- 代价：客户端连 8066 期待的是内带 STARTTLS，独立端口**破坏"无缝替代"**（非 MySQL 协议行为）。**否决**。

**裁定**：走 **方案 A**（Netty pipeline 承载 STARTTLS）。理由：TLS 是安全敏感 + 招牌对外硬门，B 的"静默不加密"风险与内部类耦合是持久负债，不值当为省改动把正确性押在脆弱反射上。A 体量虽中偏大，但一次做对、协议正确、可长期演进。

> **风险挂账**：A 需触碰前端 socket 抽象层（Vert.x→Netty），是本设计最大的不确定点。实施以 **spike 先行**（见 §5 Task 0）验证 Netty 下 MySQL STARTTLS 与 Connector/J `sslMode=REQUIRED` 往返可行，再全量迁移；spike 失败则回退并如实挂账，不硬推。

---

## 5. 里程碑任务分解（ bite-sized · TDD · 出口可跑 ）

**Task 0（Spike·可行性，交付结论非产品码）**：最小 Netty server（自签证书 `SelfSignedCertificate`）实现：明文 handshake(CLIENT_SSL) → 读 SSLRequest(校验 32 字节) → addFirst SslHandler → 读 HandshakeResponse → 回 OK。Connector/J `sslMode=REQUIRED&verifyServer=false` 连上拿到 OK。证 A 路线可行；产出 `t0-netty-starttls-spike.md`。label throwaway。

**Task 1（配置面 · config 模块）**：`ServerConfig` 增 `tlsMode`（DISABLED/PREFERRED/REQUIRED，默认 DISABLED=零回归）+ 证书来源（`keystore`/`selfSigned`）；`MySQLServerCapabilityFlags`/profile 依 `tlsMode!=DISABLED` 置 `CLIENT_SSL`。TDD：`VersionProfileFactory`/`ServerConfig` 单测锁三档能力位与默认零回归。

**Task 2（SSLRequest 解析 · protocol/common）**：按 §2 字节表实现 `SSLRequest` 包解析器 + 单测（golden bytes：client_flag|CLIENT_SSL、max_packet_size、charset、23×0 filler）；缺 CLIENT_SSL 位则拒绝。纯单元，transport-agnostic。

**Task 3（前端 acceptor 迁 Netty + STARTTLS 升级）**：依 Task 0 结论，把 8066 接入改 Netty pipeline，收到 SSLRequest 后注入 SslHandler，其后 HandshakeResponse/认证走解密流；明文路径不变（PREFERRED 时两路并存）。RED→GREEN：起 TLS-on Mycat，Connector/J `sslMode=REQUIRED` 连上完成认证+`select 1`。

**Task 4（compat TLS 轴矩阵格）**：`example` compat 增 `TlsAxisMatrixTest`：`sslMode=REQUIRED`（on）与 `DISABLED`（off）× 逐版本 persona 断言连通+CRUD；把 R0 的 NO_DATA 占位点亮为 PASS/FAIL（不静默绿）。run-matrix ×7 重跑背书。

**Task 5（MariaDB 协议复核 + 看板回写）**：查 MariaDB Connector 文档确认 STARTTLS 一致；回写 `DEBT-QUEUE` I-AU-2→关、`COMPAT-MILESTONE-PLAN` R4→✅、仪表盘 M2 完成度。

---

## 6. 测试/验证策略

- **单元（快回归）**：SSLRequest golden-bytes、capability 置位三档、tlsMode 默认零回归。
- **e2e（真 TLS 往返）**：Netty `SelfSignedCertificate` 起 TLS-on Mycat（test scope，不引外部装依赖）；Connector/J `sslMode=REQUIRED&verifyServer=false`（自签免校验）、`sslMode=VERIFY_*` 用下发的 CA；断言：握手成功 + CRUD + `SHOW STATUS LIKE 'Ssl_cipher'` 非空（真加密，防"静默不加密"）。
- **矩阵**：on/off × {5.7,8.0,8.4,9.0,MariaDB×3}；明文全绿不得因 TLS 回退。
- **附注（认证×TLS）**：`require_secure_transport` 语义下 TLS 建立后 caching_sha2 full-auth 可走明文口令（无需 RSA），与 I-AU-3 非 TLS 路径并存；本 R4 只保证 TLS 通道 + 既有认证在 TLS 上照常，full-auth-over-TLS 优化属后续细节，如触发即挂账不夹带。

---

## 7. 遗留与边界（诚实挂账）

- 后端→真实 MySQL 的 TLS（`ConnectHandler.upgradeToSsl`）不在 R4；云场景常需前后端皆 TLS，另立后端 TLS 专轮。
- 生产证书（受管 keystore / Let's Encrypt）不在 R4（用 test 自签）；证书热加载/`RELOAD TLS` 属运维细节，另论。
- TLS 版本/套件白名单（禁 TLS1.0/1.1、`tls_version` 变量对外语义）作为 `tlsMode` 的后续细化，先保证 REQUIRED 往返正确。
- 压缩协议（CLIENT_COMPRESS）与 TLS 叠加顺序本 R4 不涉。
