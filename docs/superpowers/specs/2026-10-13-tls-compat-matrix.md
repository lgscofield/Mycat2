# R4 · 前端 TLS 兼容轴：建格现状 + 运行时 Provision Runbook

> **这是什么**：招牌 M2 判据「TLS on/off × 各 persona × connector」中 **TLS 轴**的建格现状与点亮清单。口径以主 spec `2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md` §3/§7 + MySQL/MariaDB/Connector/J **官方文档**为准（落实「拿不准查官网」）。
>
> **校准**：2026-10-13，凭当前 `git log` + `server`/`example` 代码实读 + Connector/J「Using TLS/SSL Connections」/ MySQL「Connection Phase·Protocol::SSLRequest」实读，非凭记忆。设计裁决见 `2026-10-12-r4-frontend-tls-design.md` §4（方案 A，修正记录 3）。

---

## 1. 分层现状：传输层已实证 · 全矩阵待 infra

R4 前端 TLS 走**方案 A**（保留 Vert.x acceptor，收到 SSLRequest 后同连接 `socket.upgradeToSsl` 内带 STARTTLS 升级，对齐 MySQL 官网「明文握手宣称 CLIENT_SSL → 客户端回 SSLRequest → 同连接 TLS 握手 → TLS 上 HandshakeResponse」）。进度分三层，**不得混谈**：

| 层 | 内容 | 当前态 | 证据 |
|---|---|---|---|
| 配置面 | `tlsMode`(DISABLED/PREFERRED/REQUIRED) + `tlsCertFile/tlsKeyFile`；非 DISABLED 时握手广告 CLIENT_SSL | 🟢 **DONE** | Task1：`TlsMode`/`ServerConfig`/`VersionProfileFactory`；`VersionProfileTest` 9/0/0 |
| 协议解析 | `Protocol::SSLRequest`（32 字节：client_flag·max_packet·charset·filler23） | 🟢 **DONE** | Task2：`SSLRequest` + `SSLRequestTest` 2/0/0（对齐官网字节布局） |
| 传输升级（Connector/J） | `VertxMySQLAuthHandler` 收 SSLRequest→`upgradeToSsl`；`VertxMycatServer` 挂 KeyCertOptions | 🟢 **Evidenced-Done（主客户端）** | Task3 commit `e01368405`：单元锁 `FrontendStartTlsUpgradeTest` 2/2（RED→GREEN）+ server 全量 33/0/0 + **真 Connector/J 离线探针** `firstReadTail=0 → cipher=TLS_AES_256_GCM_SHA384` |
| **全矩阵 e2e** | 起 Mycat(tlsMode!=DISABLED)+真实后端，逐 persona × on/off，认证+CRUD 在 TLS 通道往返 | ⚪ **NO_DATA** | `TlsAxisMatrixTest` 已建格（编译通过），**须 run-matrix 起 infra 才跑**；当前无一条全矩阵实跑证据 |
| 非 Java connector × TLS | Go/Python/C/.NET 客户端在 TLS 下的冒烟 | ⚪ **NO_DATA** | 超 example(Java) 能力，属 `I-DEBT-9` polyglot/docker 专轮（装运行时红线） |

⇒ **诚实底线**：`connector 轴`文档 §2 那句「TLS on/off 逐 connector 全格 NO_DATA」现**部分推进**：MySQL **Connector/J 的传输升级已实证**（离线，不需后端），但**跨 persona 的完整 TLS e2e 矩阵与非 Java connector 仍 NO_DATA**。绝不把未跑格写成 PASS。

---

## 2. 加密态判据口径（为何 harness 用 Connector/J `sslMode` 严格性，而非 `SHOW STATUS Ssl_cipher`）

- **官网事实**：裸 MySQL 上 `SHOW STATUS LIKE 'Ssl_cipher'` 非空 = 该会话已 TLS（Connector/J 文档 / MySQL Server Status）。
- **代理陷阱**：Mycat 是代理，其 `SHOW STATUS` 语义可能**透传后端连接**的 Ssl_cipher，反映的是「Mycat↔后端 MySQL」而非「客户端↔Mycat」前端 TLS 态 → 据此判前端加密会**假绿/假红**。
- **采用的权威客户端信号**：Connector/J `sslMode=REQUIRED`（=CLI `--ssl-mode=REQUIRED`）官方语义 = 服务端未广告 CLIENT_SSL 时**直接失败、不回退明文**。故「REQUIRED 能建连」本身即「客户端↔Mycat 已 TLS」的可观测证据，不依赖服务端内部状态。`TlsAxisMatrixTest` 正据此断言。

---

## 3. TLS 轴矩阵格（tlsMode on/off × persona × Connector/J）

| persona 档 | sslMode=DISABLED（明文往返） | sslMode=REQUIRED（TLS 往返） | 备注 |
|---|---|---|---|
| MySQL 5.7 | ⚪ NO_DATA | ⚪ NO_DATA | 传输层已实证，e2e 待 infra |
| MySQL 8.0 | ⚪ NO_DATA | ⚪ NO_DATA | caching_sha2 over TLS：full-auth 在安全通道走明文口令，需 e2e 覆盖 |
| MySQL 8.4(LTS) | ⚪ NO_DATA | ⚪ NO_DATA | |
| MySQL 9.x(Innov) | ⚪ NO_DATA | ⚪ NO_DATA | |
| MariaDB 10.6 | ⚪ NO_DATA | ⚪ NO_DATA | 协议线级同构（见 design §2 / Task5），**合并行为待实测**（见 §5） |
| MariaDB 10.11 | ⚪ NO_DATA | ⚪ NO_DATA | 同上 |
| MariaDB 11.4(LTS) | ⚪ NO_DATA | ⚪ NO_DATA | 11.4+ Connector/C 3.4 默认对非本地连接自动 TLS+强校验服务端证书 |

> 点亮后逐格填 PASS/FAIL。**在此之前一律 NO_DATA，须响不得静默。**

---

## 4. 逐 persona Provision Runbook（用户侧执行；当前均 NO_DATA）

**公共前置**（与 auth/CRUD 矩阵同，额外加服务端 TLS）：

1. **后端 MySQL/MariaDB 起好**（ds0/ds1 指向真实实例；persona 用 `-Dmycat.persona` 选择，如 `MYSQL_8_0`）。
2. **Mycat 以 TLS 开启启动**（关键差异项，写进 `wrapper.conf`/启动参数）：
   - `tlsMode=REQUIRED`（最硬，等价 `require_secure_transport=ON` 姿态）；生产配真实证书：`tlsCertFile=/path/server-cert.pem` `tlsKeyFile=/path/server-key.pem`。
   - **测试可省证书**：`VertxMycatServer.buildFrontendKeyCertOptions` 在无 `tlsCertFile/tlsKeyFile` 时回落 `SelfSignedCertificate`（JDK21 需 BouncyCastle，已 compile 域引入 server 模块）；日志会 WARN「self-signed DEV/TEST ONLY」。
   - `tlsMode=PREFERRED`：广告 CLIENT_SSL 但允许客户端回退明文（对应 CLI `--ssl-mode=PREFERRED`）。
3. **跑 TLS 矩阵格**（example 模块，连 `127.0.0.1:8066`）：
   ```
   # 令 TLS-on 格生效（否则该格记 N/A）；未起 TLS 服务端时切勿置此值以免假通过
   mvn -o -B test -pl example -Dtest=TlsAxisMatrixTest -Dmycat.tls.expected=true
   ```
   - `requiredTlsConnectsAndRoundTrips`：`sslMode=REQUIRED` 建连=已升级；连上且 `select 1` 往返 → PASS；`mycat.tls.expected` 未置 → N/A；无 8066 → NO_DATA(abort)。
   - `disabledTlsStaysPlaintextAndRoundTrips`：`sslMode=DISABLED` 明文往返 → PASS。
4. **纳入 run-matrix**（认证/CRUD 四件套同批）：对每 persona，服务端以 `tlsMode=REQUIRED` 起、追加 `-Dmycat.tls.expected=true` 跑 `TlsAxisMatrixTest`，把 §3 逐格点成 PASS/FAIL，汇入 `matrix-report.md`。
5. **非 Java connector × TLS**：沿用 `2026-10-12-connector-compat-provision.md` §3 各语言 runbook，追加其 TLS 参数（Go `?tls=...`、Python `ssl={'ca':...}`、C `MYSQL_OPT_SSL_*`、.NET `sslmode=Required`），真跑属 I-DEBT-9 polyglot/docker 专轮（红线）。

> 凭据由运行环境注入，**勿写死进仓库**。自签证书场景客户端用 `verifyServerCertificate=false`（仅测试；生产须校验链，否则失去 MITM 防护——这点与官网一致）。

---

## 5. 残留风险（挂账，不掩盖）

- **同读合并（coalescing）对非 Connector/J 客户端**：方案 A 的前提是客户端**不把 SSLRequest 与后续 ClientHello 合并进同一 TCP 读**。真 MySQL **Connector/J 实测不合并**（`firstReadTail=0`）；**MariaDB Connector/J、go-sql-driver、Connector/C 等的合并行为未知**——若某客户端合并，Vert.x 公开 API 无法回注吞掉的尾字节 → 该连接握手挂起（design §4 修正记录 2 已证机制）。⇒ §3 MariaDB 行点亮时，**必须先经 §4 全矩阵实跑确认不合并**，不得由 MySQL 结果类推。若 MariaDB 合并 → 触发后手方案 A'（Netty pipeline 字节边界注 `SslHandler`），属安全敏感大 blast，须用户拍板。
- **`require_secure_transport` 对等**：Mycat 侧「强制 TLS」= `tlsMode=REQUIRED` 语义下服务端仍接受明文（仅广告 CLIENT_SSL）；若需 Mycat 拒绝非 TLS 连接（等价 MySQL `require_secure_transport=ON`），是配置面后续小格（本矩阵未纳入，另立）。
- **证书校验**：测试用自签 + `verifyServerCertificate=false`；生产 CA 链校验路径未跑（属 D 层生产稳健，与「无缝替代」正交）。

---

## 6. 看板/债务回写口径

- `DEBT-QUEUE` **I-AU-2**（前端 TLS）：由「Task3 传输升级因 BC 红线待拍板」推进为「**配置面/解析/Connector/J 传输升级已 Evidenced-Done（`e01368405`）；全矩阵 e2e 与非 Java connector×TLS = 本文 NO_DATA + runbook 就绪，待 run-matrix infra 点亮**」。BC 装依赖红线已获用户批准并落地（compile 域）。
- **M2 仪表盘**「TLS on/off」：从「未做」推进为「**传输层实证 + 矩阵建格**」，仍属 M2 未完全达标（e2e 矩阵未跑）。
- **I-DEBT-9** TLS 子面：由「0 覆盖」推进为「**已建格 `TlsAxisMatrixTest`（编译通过）+ 本文 runbook/判据口径；真跑待 infra**」。
- **绝不**把 §3 任一 NO_DATA 格写成 PASS。
