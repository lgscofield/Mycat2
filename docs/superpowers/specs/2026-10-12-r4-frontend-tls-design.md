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

## 4. 核心技术闸口：Vert.x 服务端 STARTTLS 升级（spike 已证，前提修正）

> **修正记录（2026-10-12 · Task 0 spike）**：本节初稿假设"Vert.x server 端 `NetSocket` 无公开 `upgradeToSsl`、必须把 acceptor 迁裸 Netty"。该假设被 spike **实证证伪**——Vert.x 4.5.14 `NetSocket.upgradeToSsl` 于 server 端可完成同连接 STARTTLS 握手、通道确为加密（`mvn -o -B test -pl server -Dtest=StartTlsSpikeTest` 1/1 绿，`sslSession().getCipherSuite()` 返回 `TLS_*`；权威依据：Vert.x Core 手册"Upgrading connections to SSL/TLS"+ `NetSocket#upgradeToSsl` javadoc："server name…should be null on a server"、"SSL must be configured"）。故方案 A 改为**保留 Vert.x acceptor**，Netty 迁移降为后手。

MySQL STARTTLS 要求**在同一条已建立明文 TCP 上、读完 SSLRequest 后把后续流切成 TLS**。Vert.x `NetServerOptions.setSsl(true)` 只做**接入即隐式 TLS**（整条连接从第一字节即 TLS），与协议不符，不可直接用；正确姿势是 server 端配好证书（不设 `setSsl(true)`），收到 SSLRequest 后对该连接调 `socket.upgradeToSsl(handler)`。

⇒ 三条路线（spike 后重排）：

### 方案 A（推荐 · spike 证实可行 · blast 由"中偏大"降为"中"）：保留 Vert.x acceptor，就地 `upgradeToSsl`
`VertxMycatServer` 的 `NetServer` 维持明文接入 + 配 `KeyCertOptions`（cert 由 `ServerConfig.tlsCertFile/tlsKeyFile` 提供）。前端握手中继收到并**按 MySQL 包长度边界吃满整个 SSLRequest 包**后，对该 `NetSocket` 调 `upgradeToSsl`；TLS 握手由客户端发起 ClientHello、服务端等入。升级成功后续 HandshakeResponse/认证帧天然走 Vert.x 解密流，既有 Rx/`VertxSession`/背压/装配不动。
- 优：**协议正确**、改动集中在握手 handler、不耦合 Vert.x 内部类、不重接 socket 抽象层。
- **两条硬约束（spike 挖出）**：
  1. **按包边界升级**——MySQL 客户端发完 SSLRequest **不等服务端回复**即发 ClientHello（无往返，区别于 SMTP STARTTLS）。若两次写被 TCP 合并进同一次 `NetSocket` 读，而我们在 handler 内直接升级，会**丢弃同 buffer 里尾部的 ClientHello → 握手卡死**（spike 实测：452 字节合并读失败、6 字节干净读成功）。⇒ Task 3 必须借 `VertxMySQLPacketResolver` 的 3 字节长度分帧，确保只在"恰好消费完 SSLRequest 包、无残留字节"时才升级，不能靠时机侥幸。
  2. **BouncyCastle 运行时依赖**——Vert.x 服务端经 Netty `SslContextBuilder` 装载 PKCS12/PEM key 需 `bcpkix`/`bcprov`（`BouncyCastlePemReader`），server 模块原缺（spike 期临时加 test-scope 验证后已还原）。生产落地须以 compile 域正式引入 ⇒ **属"装依赖须拍板"红线**，Task 3 前向用户确认。
- 附注：JDK21 已封 `sun.security.x509`，Vert.x 内置 `SelfSignedCertificate` 生成器在 JDK21 失效（spike 期改用 `keytool` 造 PKCS12 绕开）——影响测试脚手架，非生产能力。

### 方案 A'（后手 · 仅当 upgradeToSsl 在真实 Connector/J 下暴露未探边界时才启用）：前端 acceptor 迁裸 Netty pipeline
MySQL 包 `LengthFieldBasedFrameDecoder` + `pipeline.addFirst(sslHandler)`。blast 中偏大（前端 socket 抽象 Vert.x→Netty），当前证据**不需要**，列为回退后手。

### 方案 B（否决 · 脆弱）：反射/强转探 `NetSocketImpl` 内部 `Channel` 注 `SslHandler`
耦合 Vert.x 私有内部类，安全路径"静默不加密"风险高，**否决**（既然 A 有公开 API 且实证可行，无理由走 B）。

### 方案 C（否决 · 破无缝）：独立隐式 TLS 端口
违背"同端口无缝替代"招牌，**否决**。

**裁定**：走 **方案 A**（保留 Vert.x acceptor，收到 SSLRequest 包后就地 `socket.upgradeToSsl`）。依据：Task 0 spike 实证 Vert.x 4.5.14 服务端 STARTTLS 可行且真加密；公开 API 路径比 B 的反射脆弱性、与 A' 的 Netty 重写都稳。B/C 否决，A' 仅作后手。

> **风险挂账（spike 后更新）**：A 的净新增风险已从"前端 socket 抽象大迁移"收敛为两条明确约束：①按 MySQL 包长度边界"吃满 SSLRequest 再升级"以避免同读合并丢 ClientHello（可在 `VertxMySQLPacketResolver` 层保证）；②以 compile 域引入 BouncyCastle 作运行时依赖（装依赖红线，Task 3 前向用户拍板）。TLS 仍属安全敏感路径，故 Task 4 必含"真 Connector/J `sslMode=REQUIRED` e2e + `SHOW STATUS LIKE 'Ssl_cipher'` 非空防伪加密"的实跑验收，不以单元测代劳。

---

## 5. 里程碑任务分解（ bite-sized · TDD · 出口可跑 ）

**Task 0（Spike·可行性，交付结论非产品码）✅ 已完成**：Vert.x NetServer+NetClient 回环（非 Netty 裸接）实证：明文 greeting(宣称 CLIENT_SSL) → 读 SSLRequest → server 端 `socket.upgradeToSsl` → TLS 握手 → 升级后 echo + `sslSession().getCipherSuite()` 为 `TLS_*`。**结论**：①Vert.x 4.5.14 原生支持服务端 STARTTLS（方案 A 无需迁 Netty）；②同读合并坑——必须按包边界升级；③需 BouncyCastle 运行时依赖（JDK21 下测试证书改 keytool）。label throwaway，已清理。

**Task 1（配置面 · config 模块）**：`ServerConfig` 增 `tlsMode`（DISABLED/PREFERRED/REQUIRED，默认 DISABLED=零回归）+ 证书来源（`keystore`/`selfSigned`）；`MySQLServerCapabilityFlags`/profile 依 `tlsMode!=DISABLED` 置 `CLIENT_SSL`。TDD：`VersionProfileFactory`/`ServerConfig` 单测锁三档能力位与默认零回归。

**Task 2（SSLRequest 解析 · protocol/common）**：按 §2 字节表实现 `SSLRequest` 包解析器 + 单测（golden bytes：client_flag|CLIENT_SSL、max_packet_size、charset、23×0 filler）；缺 CLIENT_SSL 位则拒绝。纯单元，transport-agnostic。

**Task 3（Vert.x 握手中继内 `upgradeToSsl` 升级 · 不迁 Netty）**：依 Task 0 修正，`VertxMycatServer` NetServer 配 `KeyCertOptions`（cert 取 `ServerConfig.tlsCertFile/tlsKeyFile`）；`VertxMySQLAuthHandler`/`VertxMySQLPacketResolver` 按 MySQL 3 字节长度分帧**吃满整个 SSLRequest 包且无残留**后调 `socket.upgradeToSsl`；其后 HandshakeResponse/认证走 Vert.x 解密流，明文路径不变（PREFERRED 两路并存）。前置：以 compile 域引入 BouncyCastle（装依赖红线，先向用户拍板）。RED→GREEN：起 TLS-on Mycat，Connector/J `sslMode=REQUIRED` 连上完成认证+`select 1`。

**Task 4（compat TLS 轴矩阵格）**：`example` compat 增 `TlsAxisMatrixTest`：`sslMode=REQUIRED`（on）与 `DISABLED`（off）× 逐版本 persona 断言连通+CRUD；把 R0 的 NO_DATA 占位点亮为 PASS/FAIL（不静默绿）。run-matrix ×7 重跑背书。

**Task 5（MariaDB 协议复核 + 看板回写）**：查 MariaDB Connector 文档确认 STARTTLS 一致；回写 `DEBT-QUEUE` I-AU-2→关、`COMPAT-MILESTONE-PLAN` R4→✅、仪表盘 M2 完成度。

---

## 6. 测试/验证策略

- **单元（快回归）**：SSLRequest golden-bytes、capability 置位三档、tlsMode 默认零回归。
- **e2e（真 TLS 往返）**：JDK21 下 Vert.x 内置 `SelfSignedCertificate` 失效→用 keytool 造 PKCS12 作测试证书（test scope）；服务端 TLS 需 **BouncyCastle**（compile 域，装依赖红线待拍板）；Connector/J `sslMode=REQUIRED&verifyServer=false`（自签免校验）、`sslMode=VERIFY_*` 用下发的 CA；断言：握手成功 + CRUD + `SHOW STATUS LIKE 'Ssl_cipher'` 非空（真加密，防"静默不加密"）。
- **矩阵**：on/off × {5.7,8.0,8.4,9.0,MariaDB×3}；明文全绿不得因 TLS 回退。
- **附注（认证×TLS）**：`require_secure_transport` 语义下 TLS 建立后 caching_sha2 full-auth 可走明文口令（无需 RSA），与 I-AU-3 非 TLS 路径并存；本 R4 只保证 TLS 通道 + 既有认证在 TLS 上照常，full-auth-over-TLS 优化属后续细节，如触发即挂账不夹带。

---

## 7. 遗留与边界（诚实挂账）

- 后端→真实 MySQL 的 TLS（`ConnectHandler.upgradeToSsl`）不在 R4；云场景常需前后端皆 TLS，另立后端 TLS 专轮。
- 生产证书（受管 keystore / Let's Encrypt）不在 R4（用 test 自签）；证书热加载/`RELOAD TLS` 属运维细节，另论。
- TLS 版本/套件白名单（禁 TLS1.0/1.1、`tls_version` 变量对外语义）作为 `tlsMode` 的后续细化，先保证 REQUIRED 往返正确。
- 压缩协议（CLIENT_COMPRESS）与 TLS 叠加顺序本 R4 不涉。
