# 前端 caching_sha2_password 原生认证（R3 · I-AU-1 / I-AU-3 / I-AU-5）设计

> **状态**：设计 spec（brainstorming 产物，待用户评审后进 writing-plans）。
> **里程碑**：`COMPAT-MILESTONE-PLAN.md` **R3**（前端 caching_sha2 原生协商）。承 R2（I-VE-2 EOF 位已档案化）。
> **招牌目标**：让 Mycat 在客户端眼中"就是"一台对应 persona 版本的 MySQL——8.0/8.4/9.0 默认 `caching_sha2_password` 客户端**原生走通** fast-auth + full-auth(RSA)，**不再靠 0xFE 强降 native 蒙混**。

## 1. 背景与现状（亲验 `file:line`）

- **活的前端握手 = vertx 版** `io.mycat.vertx.VertxMySQLAuthHandler`（`MycatCore` 只 `new VertxMycatServer`）。proxy 模块 `MySQLClientAuthHandler` 的 NIO `onSocketRead` **无实例化点=死码**，不参与本特性（其 `createHandshakePayload` 是**静态工具**，被 vertx 版复用）。
- **现 posture**（`VertxMySQLAuthHandler.java:80-106`）：
  - 握手经 `createHandshakePayload(..., profile)` 通告 `profile.authPlugin()`（`MySQLClientAuthHandler.java:263`），当前恒为 `mysql_native_password`。
  - 客户端若用**非 native** 插件（8.0 默认 caching_sha2）应答 → 服务端下发 `AuthSwitchRequestPacket(0xFE, "mysql_native_password")` **强制降级**（`:91-100`），再按 `scramble411` 校验（`:169`）。
  - 校验只用 `MysqlNativePasswordPluginUtil.scramble411`（`:169`），**无 caching_sha2 语义**。
- **可复用原语（已在树）**：
  - `io.mycat.protocol.mysql.auth.SecurityUtil.scrambleCachingSha2(byte[] password, byte[] seed)`（protocol 模块，`SecurityUtil.java:114`）——服务端算期望 scramble 的正解。
  - `SecurityUtil.xorString(from,to,scramble,len)`（`:170`）——full-auth 去 XOR。
  - `io.mycat.util.CachingSha2PasswordPlugin`（common）——**仅客户端向**（`scrambleCachingSha2`、`encryptPassword` 用公钥**加密**），服务端**解密**需另就。
  - **wire 语义铁证**（Mycat 作客户端侧解码器）`ConnectHandler.java:244-278`：服务端 `AuthMoreData` = 头字节 `0x01` + 状态字节：`0x03`=`FAST_AUTH_STATUS_FLAG`（成功后随 OK/ERR）、`0x04`=`FULL_AUTHENTICATION_STATUS_FLAG`（需 full-auth）；非加密连接客户端回 `0x02`=`AUTH_PUBLIC_KEY_REQUEST_FLAG` 求公钥，服务端下发 PEM 公钥，客户端 `RsaPublicKeyEncryptor.encrypt`（≥8.0.5 用 `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`，否则 `PKCS1Padding`）回送密文。

## 2. 目标 / 非目标

**目标（本 R3）**
1. `VersionProfile.authPlugin()` **按 persona 驱动**（I-AU-5）：MySQL 8.0/8.4/9.0 → `caching_sha2_password`；5.7 / MariaDB 各档 → `mysql_native_password`。
2. **最小 auth cache（faithful 触发两路的关键）**：进程内、**按用户维度的"已温(warmed)"标记**（非存原始 scramble——caching_sha2 fast-auth scramble 是 **nonce 绑定**的，存下条连也永不同以它比对）。首次 full-auth 成功后置该用户 `warmed`；fast-auth **仅当** `warmed` 才尝试（因 Mycat 持口令，仍用存库口令+当连 seed 实算 `scrambleCachingSha2` 比对来验证）；未 `warmed`（冷）→ 回 `0x04` 走 full-auth。**`FLUSH PRIVILEGES` 类动作清空 `warmed`**（对齐真库、也保证 live 能稳定复现冷态）。**这使 full-auth 被合法客户端首次连接真实驱动**（非仅错口令）。
3. `VertxMySQLAuthHandler` **原生处理 caching_sha2**：
   - **fast-auth（`warmed`=热）**：用存库口令+当连 seed 实算比对→ `AuthMoreData[0x01,0x03]` + OK。
   - **full-auth（RSA，冷/未 warmed）**：`AuthMoreData[0x01,0x04]`；客户端 `0x02` 求公钥→ 服务端下发 **PEM 公钥**（`AuthMoreData[0x01]+PEM`）；客户端回 RSA 密文→ 服务端**私钥 OAEP 解密**→ 去 XOR(×seed)→ 还原明文口令→ **比对存库口令**→ OK 并置 `warmed` / ERR。
4. **双插件并存**：通告 caching_sha2 后，显式请求 native 的客户端（老驱动/MariaDB）仍走 native 校验，**不回归**。
5. **live e2e 门**：`FrontAuthMatrixTest` 新增 sha2 格（冷 full-auth / 热 fast-auth / 错口令），boot 真实 mycat + Connector/J caching_sha2 账户，逐格 PASS。

**非目标（留后续，明确划出）**
- **TLS full-auth 支**（`0x01,0x04,0x03` 走安全连接传明文）：属 R4 前端 TLS；本 R3 只做非加密连接上的 **RSA** full-auth。R4 落地前 TLS 语义不启用。
- `client_ed25519`、按用户可配 `UserConfig.encryptType`：正交缺口（I-AU-4/6），另轮。

## 3. 架构与组件

改动集中三模块，接口清晰、可独立测：

| 组件 | 模块 | 职责 | 依赖方向 |
|---|---|---|---|
| `VersionProfileFactory` persona→插件 | **config** | I-AU-5：按 persona 定 `authPlugin()` | 零环叶子（既有） |
| `RsaKeyPairProvider`（新） | **server** | 进程级 RSA 2048 密钥对；导出 MySQL 格式 PEM 公钥；供私钥解密 | server 内，不碰 config |
| `CachingSha2ServerAuth`（新，纯逻辑） | **server**（或 protocol） | fast-auth 校验、full-auth 密文解密+去 XOR+比对的**无 socket 纯函数**（便于单测） | 复用 protocol `SecurityUtil` |
| `CachingSha2AuthCache`（新） | **server** | 进程内、按用户维度的 `warmed` 标记（非存 scramble）；提供 isWarmed/markWarmed/invalidateAll(flush)，有界 | 纯内存 |
| `VertxMySQLAuthHandler` 状态机扩展 | **server** | INIT→(native 快路 / sha2 fast-auth)→sha2 FULL_AUTH(求公钥→解密)；写 `AuthMoreData` 包 | 调上二者 |
| `AuthMoreData` 包编码 | **common**（beans.mysql.packet，新增小类或复用 writer） | `0x01`+状态/PEM 载荷，仿 `AuthSwitchRequestPacket` 用 `MySQLPayloadWriter` | 零逻辑 |

**握手状态机（`VertxMySQLAuthHandler.handle`）**：新增 `enum AuthStage { NATIVE, SHA_FAST_PENDING, SHA_FULL_ASK_PUBKEY, SHA_FULL_RECV_PW }`。按 `authPacket.getAuthPluginName()` 分诊：
- native（或 null）→ 现路 `scramble411`（**删 0xFE 降级分支**，改为直校）。
- caching_sha2 → fast-auth：**查 `CachingSha2AuthCache`**——命中→回 `0x01,0x03`+OK；未命中（冷）→置 `SHA_FULL_ASK_PUBKEY`，回 `0x01,0x04`；收到 `0x02` → 回 PEM 公钥置 `SHA_FULL_RECV_PW`；收到密文 → 解密→比对存库口令→命中则 **写缓存**+OK / 否则 ERR。

## 4. 密钥生命周期与安全

- `RsaKeyPairProvider` **进程级**懒生成 2048-bit RSA（首次需要 full-auth 时生成，`SecureRandom`），暴露：`String publicKeyPem()`（`-----BEGIN PUBLIC KEY-----` + Base64(SPKI DER) + `-----END PUBLIC KEY-----\n`，与 MySQL 下发格式对齐）、`PrivateKey privateKey()`。
- 解密变换按**版本阈值**选：persona≥8.0.5 → `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`，否则 `RSA/ECB/PKCS1Padding`（对齐 `CachingSha2PasswordPlugin.encrypt` 的客户端选择，`CachingSha2PasswordPlugin.java:75-83`）。
- 密文解密得到 `XOR(口令, seed)`（口令末尾 `0x00`），再 `xorString(...,seed)` 还原。**明文口令仅在解密栈内出现，不落日志**。私钥只驻内存、不进配置/日志/序列化。
- **无新增外部依赖**：全用 JDK `javax.crypto` / `java.security`（protocol 已有同等原语）。
- **auth cache 有界**：`CachingSha2AuthCache` 按用户维度、上限条目 + 失效（flush/淘汰）；只存派生 scramble（非明文口令），内存驻留、不进日志。

## 5. 测试与定义完成（Definition of Done）

**单元（无 socket，先红后绿）**
1. config `VersionProfileTest`：persona→`authPlugin()` 映射（8.0/8.4/9.0=sha2；5.7/mdb=native）。扩 R2 已有断言。
2. `CachingSha2ServerAuth` fast-auth：给定口令+seed，`scrambleCachingSha2` 正/误两路命中判定。
3. `CachingSha2ServerAuth` full-auth **往返锁**：用 `RsaKeyPairProvider` 公钥**按客户端法**（复用 `CachingSha2PasswordPlugin.encryptPassword`/`RsaPublicKeyEncryptor`）加密 `XOR(口令,seed)`，服务端私钥解密→还原→断言等于原口令（钉死 OAEP/PKCS1 选择、末尾 `0x00`、PEM 解析）。
4. `AuthMoreData` 编码：`0x01,0x03` / `0x01,0x04` / `0x01+PEM` 字节形状断言。

**live e2e（R3 完成硬门，`FrontAuthMatrixTest` 新增格）**
- 前置：boot `mysql:8.0` 后端 + mycat（persona=MYSQL_8_0，前端 8066）+ 建 caching_sha2 账户。
- FA4 **冷态 full-auth/RSA PASS**：新连接（未 warmed）或先 flush → Connector/J caching_sha2 用户连 mycat → 服务端回 `0x04`、走 `allowPublicKeyRetrieval=true` RSA full-auth → `select 1` 一行。
- FA5 **热态 fast-auth PASS**：FA4 已焐热后紧接第二连（新 seed）→ `warmed` 命中走 fast-auth `0x03`（服务端用口令+新 seed 实算比对）→ 连上。
- FA6 **错口令干净拒绝**：无论冷/热，错口令抛 SQLException（冷路下 full-auth 解密后比对失败也必 ERR）。
- FA7 **不回归**：native 客户端（5.7 persona 或强制 `authenticationPlugins=mysql_native_password`）仍通；`ConnectionSmokeTest`/`CrudResultMatrixTest` native 格全绿。

**DoD**：单元 1–4 绿（TDD 先红）；live 矩阵 FA4–FA7 于本地 docker 8.0 档全 PASS（R1 CI 仍 BLOCKED on fork Actions，故以 `run-matrix.ps1` 本地跑为证据）；native 路零回归。**只有 live 格真 PASS 才标 `Evidenced-Done`**，否则维持 WIP（守看板铁律）。

## 6. 风险与缓解
- **通告 sha2 打挂老客户端**：靠"双插件并存 + 按 `AuthPluginName` 分诊 + 保留 native 直校"缓解；FA7 专门锁回归。
- **RSA 参数/格式与真库不一致**：靠单元 3 往返锁（用同树客户端加密器 ↔ 服务端解密器闭环）先证协议自洽，再 live 证跨实现（真 Connector/J）。
- **状态机包序/packetId 竞态**：full-auth 跨多包，packetId 递增须严丝；live 格兜底，单测锁编码序。
- **进程级密钥复用**：性能无忧；若安全审计要求轮换，留后续增强（非本 R3 阻塞）。
