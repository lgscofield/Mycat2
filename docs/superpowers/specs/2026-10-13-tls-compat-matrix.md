# R4 · 前端 TLS 兼容轴：建格现状 + 运行时 Provision Runbook

> **这是什么**：招牌 M2 判据「TLS on/off × 各 persona × connector」中 **TLS 轴**的建格现状与点亮清单。口径以主 spec `2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md` §3/§7 + MySQL/MariaDB/Connector/J **官方文档**为准（落实「拿不准查官网」）。
>
> **校准**：2026-10-13 建立；**2026-10-15 全矩阵 e2e 实跑点亮**（凭 `.superpowers/sdd/2026-10-15-tls-matrix/tls-matrix-report.md` 实跑证据 + surefire XML 归档，非凭记忆）。设计裁决见 `2026-10-12-r4-frontend-tls-design.md` §4（方案 A，修正记录 3）。

---

## 1. 分层现状：传输层已实证 · 全矩阵 e2e 已点亮（2026-10-15）

R4 前端 TLS 走**方案 A**（保留 Vert.x acceptor，收到 SSLRequest 后同连接 `socket.upgradeToSsl` 内带 STARTTLS 升级，对齐 MySQL 官网「明文握手宣称 CLIENT_SSL → 客户端回 SSLRequest → 同连接 TLS 握手 → TLS 上 HandshakeResponse」）。进度分三层，**不得混谈**：

| 层 | 内容 | 当前态 | 证据 |
|---|---|---|---|
| 配置面 | `tlsMode`(DISABLED/PREFERRED/REQUIRED) + `tlsCertFile/tlsKeyFile`；非 DISABLED 时握手广告 CLIENT_SSL | 🟢 **DONE** | Task1：`TlsMode`/`ServerConfig`/`VersionProfileFactory`；`VersionProfileTest` 9/0/0 |
| 协议解析 | `Protocol::SSLRequest`（32 字节：client_flag·max_packet·charset·filler23） | 🟢 **DONE** | Task2：`SSLRequest` + `SSLRequestTest` 2/0/0（对齐官网字节布局） |
| 传输升级（Connector/J） | `VertxMySQLAuthHandler` 收 SSLRequest→`upgradeToSsl`；`VertxMycatServer` 挂 KeyCertOptions | 🟢 **Evidenced-Done（主客户端）** | Task3 commit `e01368405`：单元锁 `FrontendStartTlsUpgradeTest` 2/2（RED→GREEN）+ server 全量 33/0/0 + **真 Connector/J 离线探针** `firstReadTail=0 → cipher=TLS_AES_256_GCM_SHA384` |
| **全矩阵 e2e** | 起 Mycat(tlsMode!=DISABLED)+真实后端，逐 persona × on/off，认证+CRUD 在 TLS 通道往返 | 🟢 **Evidenced-Done（2026-10-15 实跑）** | `TlsAxisMatrixTest` × 7 persona 档 × on/off **14/14 PASS**（`tls-matrix-report.md`；批跑 5.7/9.0/mariadb×3 双格即绿，8.0/8.4 明文格批跑遇启动预热假 N/A、redo80/redo84 补测 `Tests run: 2, Skipped: 0` 真 PASS；烟测佐证 HANDSHAKEN TLSv1.3/AES_256_GCM_SHA384） |
| 非 Java connector × TLS | Go/Python/C/.NET 客户端在 TLS 下的冒烟 | ⚪ **NO_DATA** | 超 example(Java) 能力，属 `I-DEBT-9` polyglot/docker 专轮（装运行时红线） |

⇒ **诚实底线**：`connector 轴`文档 §2 那句「TLS on/off 逐 connector 全格 NO_DATA」已推进到位：**Connector/J 客户端 × 7 persona 档 TLS on/off 全矩阵 e2e 已实跑点亮（14/14 PASS，2026-10-15）**；仍 NO_DATA 的只剩**非 Java connector**（Go/Python/C/.NET，属 `I-DEBT-9` polyglot 专轮）。绝不把未跑格写成 PASS。

---

## 2. 加密态判据口径（为何 harness 用 Connector/J `sslMode` 严格性，而非 `SHOW STATUS Ssl_cipher`）

- **官网事实**：裸 MySQL 上 `SHOW STATUS LIKE 'Ssl_cipher'` 非空 = 该会话已 TLS（Connector/J 文档 / MySQL Server Status）。
- **代理陷阱**：Mycat 是代理，其 `SHOW STATUS` 语义可能**透传后端连接**的 Ssl_cipher，反映的是「Mycat↔后端 MySQL」而非「客户端↔Mycat」前端 TLS 态 → 据此判前端加密会**假绿/假红**。
- **采用的权威客户端信号**：Connector/J `sslMode=REQUIRED`（=CLI `--ssl-mode=REQUIRED`）官方语义 = 服务端未广告 CLIENT_SSL 时**直接失败、不回退明文**。故「REQUIRED 能建连」本身即「客户端↔Mycat 已 TLS」的可观测证据，不依赖服务端内部状态。`TlsAxisMatrixTest` 正据此断言。

---

## 3. TLS 轴矩阵格（tlsMode on/off × persona × Connector/J）——2026-10-15 实跑全点亮

| persona 档 | sslMode=DISABLED（明文往返） | sslMode=REQUIRED（TLS 往返） | 备注 |
|---|---|---|---|
| MySQL 5.7 | ✅ PASS | ✅ PASS | 批跑双格即绿（verify-mysql_5_7 XML） |
| MySQL 8.0 | ✅ PASS | ✅ PASS | caching_sha2 over TLS e2e 覆盖；明文格经 redo80 补测（预热假 N/A 订正） |
| MySQL 8.4(LTS) | ✅ PASS | ✅ PASS | 明文格经 redo84 补测订正 |
| MySQL 9.x(Innov) | ✅ PASS | ✅ PASS | 容器 mysql:9.0（detected 9.0.1） |
| MariaDB 10.6 | ✅ PASS | ✅ PASS | Connector/J 客户端实测**无合并阻塞**（该档 persona=MYSQL_5_7） |
| MariaDB 10.11 | ✅ PASS | ✅ PASS | 同上 |
| MariaDB 11.4(LTS) | ✅ PASS | ✅ PASS | 同上；11.4+ Connector/C 默认自动 TLS 属非 Java connector 面，仍未验 |

> 证据：`.superpowers/sdd/2026-10-15-tls-matrix/`（tls-matrix-report.md 终版 + verify-<tag>/ surefire XML + redo80/84-mvn.log）。
> 注：客户端均为 **Connector/J**；非 Java connector × TLS 仍属 `I-DEBT-9` NO_DATA，不在此表。

---

## 4. 逐 persona Provision Runbook（已于 2026-10-15 由 `run-tls-matrix.ps1` 按本节实跑；留作复跑规程）

**公共前置**（与 auth/CRUD 矩阵同，额外加服务端 TLS）：

1. **后端 MySQL/MariaDB 起好**（ds0/ds1 指向真实实例；persona 用 `-Dmycat.persona` 选择，如 `MYSQL_8_0`）。
2. **Mycat 以 TLS 开启启动**（关键差异项，写进 `wrapper.conf`/启动参数）：
   - `tlsMode=REQUIRED`（最硬，等价 `require_secure_transport=ON` 姿态）；生产配真实证书：`tlsCertFile=/path/server-cert.pem` `tlsKeyFile=/path/server-key.pem`。
   - **测试推荐 `-Dmycat.tls.mode=REQUIRED` 启动覆盖**（`TlsModeOverride`，commit 71b184dfc，免写 MYCAT_HOME/server.json 落盘污染后续启动）。
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

- **同读合并（coalescing）对非 Connector/J 客户端**：方案 A 的前提是客户端**不把 SSLRequest 与后续 ClientHello 合并进同一 TCP 读**。真 MySQL **Connector/J 实测不合并**（`firstReadTail=0`），且 **2026-10-15 全矩阵 e2e 再证**：MariaDB 后端档经 Connector/J 客户端 14/14 PASS，无握手合并阻塞。**MariaDB Connector/J、go-sql-driver、Connector/C 等非 Java 客户端的合并行为仍未验**——若某客户端合并，Vert.x 公开 API 无法回注吞掉的尾字节 → 该连接握手挂起（design §4 修正记录 2 已证机制）。若非 Java connector 验出合并 → 触发后手方案 A'（Netty pipeline 字节边界注 `SslHandler`），属安全敏感大 blast，须用户拍板。
- **`require_secure_transport` 对等**：Mycat 侧「强制 TLS」= `tlsMode=REQUIRED` 语义下服务端仍接受明文（仅广告 CLIENT_SSL）；若需 Mycat 拒绝非 TLS 连接（等价 MySQL `require_secure_transport=ON`），是配置面后续小格（本矩阵未纳入，另立）。
- **证书校验**：测试用自签 + `verifyServerCertificate=false`；生产 CA 链校验路径未跑（属 D 层生产稳健，与「无缝替代」正交）。

---

## 6. 看板/债务回写口径（2026-10-15 已按此回写 DEBT-QUEUE）

- `DEBT-QUEUE` **I-AU-2**（前端 TLS）：2026-10-13 推进为「配置面/解析/Connector/J 传输升级 Evidenced-Done，全矩阵待 infra」；**2026-10-15 再推进为「Connector/J × 7 persona TLS on/off 全矩阵 e2e 实跑 14/14 PASS；仅剩非 Java connector × TLS 属 I-DEBT-9 NO_DATA」**。BC 装依赖红线已获用户批准并落地（compile 域）。
- **M2 仪表盘**「TLS on/off」：2026-10-15 由「传输层实证+矩阵建格」推进为「**Java 主客户端全档实跑达标**」。
- **I-DEBT-9** TLS 子面：由「已建格待 infra」推进为「**Connector/J 侧已验毕；非 Java connector 真跑待 polyglot/docker 专轮（红线）**」。
- **诚实底线不变**：绝不把未跑格写成 PASS；现 §3 全格均有 surefire XML/补测日志指针。
