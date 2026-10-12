# 前端 caching_sha2_password 原生认证（R3）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Mycat 前端（vertx 握手）原生处理 MySQL 8.0/8.4/9.x 默认插件 `caching_sha2_password` 的 fast-auth 与 RSA full-auth，彻底替换"0xFE 强降 native 蒙混"，且对 5.7/MariaDB 的 native 客户端零回归。

**Architecture:** 三模块增量——config 把 `authPlugin` 改为按 persona 驱动（I-AU-5）；server 新增三块纯逻辑组件（`RsaKeyPairProvider` / `CachingSha2ServerAuth` / `CachingSha2AuthCache`）+ `AuthMoreData` 包编码，并在 `VertxMySQLAuthHandler` 里用一个 `AuthStage` 状态机把它们接起来；persona 经系统属性在启动期可覆盖，使本地矩阵能按后端版本起对应画像。crypto 全用 JDK，无新外部依赖。

**Tech Stack:** Java 21（LibericaJDK-21-Full）、Netty/Vertx 握手、`javax.crypto` RSA + `MessageDigest` SHA-256、JUnit 5、mysql-connector-j（测试客户端）、本地 Docker（e2e 门）。

**Spec:** `docs/superpowers/specs/2026-10-12-frontend-caching-sha2-auth-design.md`

## Global Constraints

- 构建/测试**必须** `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"`（shell 默认 JDK-8 会报"无效的目标发行版: 21"）。离线：`mvn -o ...`。
- `ServerPersona` 常量精确名：`MYSQL_5_7 / MYSQL_8_0 / MYSQL_8_4 / MYSQL_9_X / MARIADB_10_6 / MARIADB_10_11 / MARIADB_11_4`。
- caching_sha2 wire 语义（铁证 `server/.../ConnectHandler.java:244-278`）：服务端 `AuthMoreData` 载荷首字节恒 `0x01`；其后 `0x03`=fast-ok、`0x04`=需 full-auth；下发公钥 = `0x01 + PEM`。客户端求公钥发裸包 `0x02`，随后发 RSA 密文。
- RSA 变换按版本阈值：生效版本 ≥ 8.0.5 → `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`，否则 `RSA/ECB/PKCS1Padding`（与 `common/.../CachingSha2PasswordPlugin.java:75-83` 客户端选择对齐）。
- PEM 公钥格式必须与 MySQL 下发一致：`-----BEGIN PUBLIC KEY-----\n<Base64(X.509 SPKI DER)>\n-----END PUBLIC KEY-----\n`（可被 `CachingSha2PasswordPlugin.decodeRSAPublicKey` 解析，`:116-131`，其解析依赖 `\n` 与 `-----END PUBLIC KEY-----`）。
- **不动 native 语义**：显式请求 `mysql_native_password`（或插件名为 null）的客户端仍走 `scramble411` 直校，且**删除** `VertxMySQLAuthHandler` 的 0xFE 降级分支。
- 私钥/明文口令仅在栈内出现，不落日志、不进配置/序列化。
- 提交精确 `git add`，**排除** `server/src/main/resources/version.txt`、`.qoder/`、`.superpowers/`。
- **Evidenced-Done 铁律**：单元绿 + 本地 docker 8.0 档 live 格真 PASS 才标 Done；否则 WIP。

## Review Focus

计划测试未完全覆盖、但最能伤"客户端眼中无缝替代"的输入类（评审时逐一盯）：
1. **server 广告 sha2、客户端仍请求 native**（老驱动/MariaDB）→ 必须 native 直校通过，不得被 sha2 分支吞掉。
2. **空口令用户走 caching_sha2** → `scrambleCachingSha2` 对空口令返回长度 0（`CachingSha2PasswordPlugin.java:58`），full/fast 两路都要把"空"当合法凭据比对，不能崩或误拒。
3. **冷路错口令** → 走完 RSA full-auth 解密→比对失败 → 必须**干净 ERR**，绝不挂起（对齐后端 60s 挂起教训）。
4. **OAEP/PKCS1 版本阈值选错** → 解不出即认证失败；用往返锁死。
5. **多包 full-auth 的 packetId 序** → `0x04`、公钥、密文跨包，序列号必须连续递增；live 兜底。

---

## Task 1: config — persona 驱动 authPlugin（I-AU-5）

**Files:**
- Modify: `config/src/main/java/io/mycat/config/VersionProfileFactory.java`
- Test: `config/src/test/java/io/mycat/config/VersionProfileTest.java`

**Interfaces:**
- Consumes: `ServerPersona`（现有）、`VersionProfile.authPlugin()`（record 组件）。
- Produces: `VersionProfileFactory.of(persona, override)` 当 persona∈{MYSQL_8_0,MYSQL_8_4,MYSQL_9_X} 时 `authPlugin()=="caching_sha2_password"`，其余 `"mysql_native_password"`。常量 `PluginNames` 无新增（字符串字面量即可）。

- [ ] **Step 1: 写失败测试**（加进 `VersionProfileTest`）

```java
@Test public void persona80AdvertisesCachingSha2() {
    assertEquals("caching_sha2_password",
        VersionProfileFactory.of(ServerPersona.MYSQL_8_0, null).authPlugin());
    assertEquals("caching_sha2_password",
        VersionProfileFactory.of(ServerPersona.MYSQL_8_4, null).authPlugin());
    assertEquals("caching_sha2_password",
        VersionProfileFactory.of(ServerPersona.MYSQL_9_X, null).authPlugin());
}
@Test public void persona57AndMariadbAdvertiseNative() {
    assertEquals("mysql_native_password",
        VersionProfileFactory.of(ServerPersona.MYSQL_5_7, null).authPlugin());
    assertEquals("mysql_native_password",
        VersionProfileFactory.of(ServerPersona.MARIADB_11_4, null).authPlugin());
}
```

- [ ] **Step 2: 跑，确认失败**

Run: `mvn -o -pl config test -Dtest=VersionProfileTest`
Expected: FAIL — `persona80AdvertisesCachingSha2` expected `caching_sha2_password` but was `mysql_native_password`。

- [ ] **Step 3: 实现**

在 `of()` 里按 persona 选插件（替换现固定 `DEFAULT_AUTH_PLUGIN`）：
```java
String authPlugin = switch (persona) {
    case MYSQL_8_0, MYSQL_8_4, MYSQL_9_X -> "caching_sha2_password";
    default -> "mysql_native_password";
};
```
以该 `authPlugin` 传入 `new VersionProfile(...)`。更新类 javadoc："I-AU-5 插件差异已按 persona 落地"。

注意：`VersionProfileTest` 现有断言 `assertEquals("mysql_native_password", p.authPlugin())`（persona=MYSQL_5_7）应仍绿（5.7 走 default 分支）。

- [ ] **Step 4: 跑，确认通过**

Run: `mvn -o -pl config test -Dtest=VersionProfileTest` → 全绿（原 5 + 新 2）。

- [ ] **Step 5: 提交**

```bash
git add config/src/main/java/io/mycat/config/VersionProfileFactory.java config/src/test/java/io/mycat/config/VersionProfileTest.java
git commit -m "feat(compat): I-AU-5 persona 驱动握手宣称插件(8.0+ caching_sha2) (R3-T1)"
```

---

## Task 2: server — RsaKeyPairProvider（密钥对 + MySQL 格式 PEM 公钥）

**Files:**
- Create: `server/src/main/java/io/mycat/vertx/auth/RsaKeyPairProvider.java`
- Test: `server/src/test/java/io/mycat/vertx/auth/RsaKeyPairProviderTest.java`

**Interfaces:**
- Consumes: JDK `KeyPairGenerator`。
- Produces: 单例式 `RsaKeyPairProvider.getInstance()`；`String publicKeyPem()`；`java.security.PrivateKey privateKey()`；`java.security.PublicKey publicKey()`。PEM 可被 `io.mycat.util.CachingSha2PasswordPlugin.decodeRSAPublicKey(String)` 解回 `RSAPublicKey`。

- [ ] **Step 1: 写失败测试**

```java
@Test public void pemRoundTripsThroughConnectorDecoder() throws Exception {
    RsaKeyPairProvider p = RsaKeyPairProvider.getInstance();
    String pem = p.publicKeyPem();
    assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n"));
    assertTrue(pem.contains("-----END PUBLIC KEY-----"));
    // 用同树客户端解码器证 PEM 可被 MySQL 生态解析
    RSAPublicKey pub = CachingSha2PasswordPlugin.decodeRSAPublicKey(pem);
    assertEquals(p.publicKey(), pub);
}
```

- [ ] **Step 2: 跑，确认失败**

Run: `mvn -o -pl server test -Dtest=RsaKeyPairProviderTest` → 编译失败（类不存在）。

- [ ] **Step 3: 实现 `RsaKeyPairProvider`**

懒生成 2048-bit RSA（`KeyPairGenerator.getInstance("RSA")`，`SecureRandom`），双检锁持有 `KeyPair`；`publicKeyPem()` 用 `Base64`（MIME 无换行 + 手工按 64 字符折行，或 `Base64.getEncoder` 后插换行）包成上面确切格式，主体为 `X509EncodedKeySpec`/`spki` = `publicKey().getEncoded()`。`privateKey()` 返回私钥。**不打印任何密钥**。

- [ ] **Step 4: 跑，确认通过**

Run: `mvn -o -pl server test -Dtest=RsaKeyPairProviderTest` → PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/io/mycat/vertx/auth/RsaKeyPairProvider.java server/src/test/java/io/mycat/vertx/auth/RsaKeyPairProviderTest.java
git commit -m "feat(compat): R3-T2 服务端 RSA 密钥对+MySQL 格式 PEM (R3-T2)"
```

---

## Task 3: server — CachingSha2ServerAuth.fastAuthOk（纯 fast-auth 校验）

**Files:**
- Create: `server/src/main/java/io/mycat/vertx/auth/CachingSha2ServerAuth.java`
- Test: `server/src/test/java/io/mycat/vertx/auth/CachingSha2ServerAuthFastAuthTest.java`

**Interfaces:**
- Consumes: `io.mycat.protocol.mysql.auth.SecurityUtil.scrambleCachingSha2(byte[],byte[])`。
- Produces: `static boolean fastAuthOk(String rightPassword, byte[] seed, byte[] clientScramble)` — 服务端算期望 scramble 并常量时间比对。空口令：`rightPassword==null||isEmpty` ⇒ 仅当 `clientScramble` 为空/长度 0 才 true。

- [ ] **Step 1: 写失败测试**

```java
@Test public void correctPasswordMatchesClientScramble() throws Exception {
    byte[] seed = "0123456789abcdefght".getBytes(); // 20 字节示例
    byte[] client = SecurityUtil.scrambleCachingSha2("s3cret".getBytes(), seed);
    assertTrue(CachingSha2ServerAuth.fastAuthOk("s3cret", seed, client));
    assertFalse(CachingSha2ServerAuth.fastAuthOk("wrongpw", seed, client));
}
@Test public void emptyPasswordMatchesEmptyScramble() {
    assertTrue(CachingSha2ServerAuth.fastAuthOk("", "seed".getBytes(), new byte[0]));
    assertFalse(CachingSha2ServerAuth.fastAuthOk("", "seed".getBytes(), new byte[]{1,2,3}));
}
```

- [ ] **Step 2: 跑，确认失败** → 类不存在。

- [ ] **Step 3: 实现** `fastAuthOk`：空口令按上面规则；否则 `byte[] exp = SecurityUtil.scrambleCachingSha2(rightPassword.getBytes(UTF_8), seed);` 再常量时间逐字节比 `exp` 与 `clientScramble`（长度不等即 false）。`DigestException` 包成 `MycatException` 或返回 false。

- [ ] **Step 4: 跑，确认通过**。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/io/mycat/vertx/auth/CachingSha2ServerAuth.java server/src/test/java/io/mycat/vertx/auth/CachingSha2ServerAuthFastAuthTest.java
git commit -m "feat(compat): R3-T3 caching_sha2 fast-auth 纯校验 (R3-T3)"
```

---

## Task 4: server — CachingSha2ServerAuth.fullAuthOk（RSA 解密 + 去 XOR + 比对，往返锁）

**Files:**
- Modify: `server/src/main/java/io/mycat/vertx/auth/CachingSha2ServerAuth.java`（加 `fullAuthOk`）
- Test: `server/src/test/java/io/mycat/vertx/auth/CachingSha2ServerAuthFullAuthTest.java`

**Interfaces:**
- Consumes: `RsaKeyPairProvider.privateKey()`、JDK `Cipher`、`SecurityUtil.xorString`。客户端加密参照 `io.mycat.util.CachingSha2PasswordPlugin`（common，仅测试里用来产密文）。
- Produces: `static boolean fullAuthOk(String rightPassword, byte[] seed, byte[] clientCipher, java.security.PrivateKey rsaPrivate, boolean oaep)`。oaep→`RSA/ECB/OAEPWithSHA-1AndMGF1Padding`，否则 `RSA/ECB/PKCS1Padding`；解密得 `XOR(口令, seed)`（含末尾 `0x00`），`xorString` 还原，去尾 `0x00` 后与 `rightPassword` 比。

- [ ] **Step 1: 写失败测试（往返锁）**

```java
@Test public void serverDecryptsClientRsaFullAuth() throws Exception {
    RsaKeyPairProvider kp = RsaKeyPairProvider.getInstance();
    byte[] seed = "0123456789abcdefght".getBytes();
    // 用同树"客户端向"加密器产出密文：XOR(口令,seed) 后 RSA 公钥加密（OAEP）
    byte[] cipher = CachingSha2PasswordPlugin.encryptPassword(
        "RSA/ECB/OAEPWithSHA-1AndMGF1Padding", kp.publicKeyPem(), "s3cret",
        new String(seed), "UTF-8");
    assertTrue(CachingSha2ServerAuth.fullAuthOk("s3cret", seed, cipher, kp.privateKey(), true));
    assertFalse(CachingSha2ServerAuth.fullAuthOk("nope", seed, cipher, kp.privateKey(), true));
}
```

- [ ] **Step 2: 跑，确认失败** → `fullAuthOk` 未定义。

- [ ] **Step 3: 实现** `fullAuthOk`：`Cipher de = Cipher.getInstance(oaep?"RSA/ECB/OAEPWithSHA-1AndMGF1Padding":"RSA/ECB/PKCS1Padding"); de.init(DECRYPT_MODE, rsaPrivate); byte[] xored = de.doFinal(clientCipher); byte[] plain = new byte[xored.length]; SecurityUtil.xorString(xored, plain, seed, xored.length);` 截到首个 `0x00` 转 UTF-8 串，与 `rightPassword` 比。异常→false（不抛穿握手）。

> **实现者核对点**：`CachingSha2PasswordPlugin.encryptPassword` 内部对口令做了 `getBytesNullTerminated` + `xorString(input, ..., seed.getBytes())`（`CachingSha2PasswordPlugin.java:85-92`），故服务端须以同序 `xorString(cipher, out, seed)` 还原并**剥掉末尾 `0x00`**。测试即锁这闭环。

- [ ] **Step 4: 跑，确认通过**。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/io/mycat/vertx/auth/CachingSha2ServerAuth.java server/src/test/java/io/mycat/vertx/auth/CachingSha2ServerAuthFullAuthTest.java
git commit -m "feat(compat): R3-T4 caching_sha2 RSA full-auth 解密+往返锁 (R3-T4)"
```

---

## Task 5: server — AuthMoreData 包编码

**Files:**
- Create: `server/src/main/java/io/mycat/vertx/auth/AuthMoreData.java`
- Test: `server/src/test/java/io/mycat/vertx/auth/AuthMoreDataTest.java`

**Interfaces:**
- Produces: `static byte[] fastOk()` → `{0x01,0x03}`；`static byte[] fullAuth()` → `{0x01,0x04}`；`static byte[] publicKey(String pem)` → `0x01` + PEM 的 UTF-8 字节。（裸载荷，不含 MySQL 4 字节包头；调用方经 `MySQLPacketUtil.generateMySQLPacket(packetId, payload)` 封装。）

- [ ] **Step 1: 写失败测试**

```java
@Test public void shape() {
    assertArrayEquals(new byte[]{0x01,0x03}, AuthMoreData.fastOk());
    assertArrayEquals(new byte[]{0x01,0x04}, AuthMoreData.fullAuth());
    byte[] pk = AuthMoreData.publicKey("PEM");
    assertEquals(0x01, pk[0] & 0xff);
    assertArrayEquals("PEM".getBytes(StandardCharsets.UTF_8),
        java.util.Arrays.copyOfRange(pk, 1, pk.length));
}
```

- [ ] **Step 2: 跑，确认失败** → 类不存在。

- [ ] **Step 3: 实现**（`ByteArrayOutputStream` 拼字节）。

- [ ] **Step 4: 跑，确认通过**。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/io/mycat/vertx/auth/AuthMoreData.java server/src/test/java/io/mycat/vertx/auth/AuthMoreDataTest.java
git commit -m "feat(compat): R3-T5 AuthMoreData 包编码 (R3-T5)"
```

---

## Task 6: server — CachingSha2AuthCache（warmed 标记）

**Files:**
- Create: `server/src/main/java/io/mycat/vertx/auth/CachingSha2AuthCache.java`
- Test: `server/src/test/java/io/mycat/vertx/auth/CachingSha2AuthCacheTest.java`

**Interfaces:**
- Produces: 进程级单例 `CachingSha2AuthCache.getInstance()`；`boolean isWarmed(String user)`；`void markWarmed(String user)`；`void invalidateAll()`。有界（`ConcurrentHashMap` + 容量上限/ LRU 或简单上限裁剪，超限丢最旧）。

- [ ] **Step 1: 写失败测试**

```java
@Test public void coldThenWarmThenFlush() {
    CachingSha2AuthCache c = CachingSha2AuthCache.getInstance();
    c.invalidateAll();
    assertFalse(c.isWarmed("u_fe"));
    c.markWarmed("u_fe");
    assertTrue(c.isWarmed("u_fe"));
    c.invalidateAll();
    assertFalse(c.isWarmed("u_fe"));
}
```

- [ ] **Step 2: 跑，确认失败** → 类不存在。

- [ ] **Step 3: 实现**（key=username，value 存占位；上限如 1024，超限 `LinkedHashMap`  eldest 驱逐或简单 clear-oldest；`invalidateAll` 清空。不存明文/口令/scramble，只存"该用户已 warmed"事实）。

- [ ] **Step 4: 跑，确认通过**。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/io/mycat/vertx/auth/CachingSha2AuthCache.java server/src/test/java/io/mycat/vertx/auth/CachingSha2AuthCacheTest.java
git commit -m "feat(compat): R3-T6 caching_sha2 warmed 标记缓存 (R3-T6)"
```

---

## Task 7: config/server — persona 启动覆盖（`-Dmycat.persona`）

**Files:**
- Modify: `server/src/main/java/io/mycat/MycatCore.java`（`MycatCore(...)` 构造内 `serverConfig` 就绪后、`newMycatServer` 前）
- Test: `config/src/test/java/io/mycat/config/PersonaOverrideTest.java`（纯逻辑助手放 config 或 server，见下）

**Interfaces:**
- Consumes: `ServerConfig.setPersona(ServerPersona)`（现有）、`System.getProperty("mycat.persona")`。
- Produces: 静态助手 `PersonaOverride.apply(String sysPropValue, ServerConfig cfg)`：值非空且能 `ServerPersona.valueOf` → `cfg.setPersona(...)`；非法值 → 记 WARN 不改（不崩启动）。

- [ ] **Step 1: 写失败测试**

```java
@Test public void appliesValidPersonaOverride() {
    ServerConfig cfg = new ServerConfig(); // 默认 MYSQL_5_7
    PersonaOverride.apply("MYSQL_8_0", cfg);
    assertEquals(ServerPersona.MYSQL_8_0, cfg.getPersona());
}
@Test public void ignoresInvalidValueWithoutThrowing() {
    ServerConfig cfg = new ServerConfig();
    PersonaOverride.apply("BOGUS", cfg);
    assertEquals(ServerPersona.MYSQL_5_7, cfg.getPersona()); // 不变
}
```

> 若 `ServerConfig` 无公开无参构造或 persona getter 不便，测试改测 `PersonaOverride.resolve(String, ServerPersona current) -> ServerPersona` 纯函数（返回覆盖后值），`MycatCore` 调它再 `setPersona`。实现者按现状择一，保持"非法值不改、不抛"。

- [ ] **Step 2: 跑，确认失败** → 类不存在。

- [ ] **Step 3: 实现** `PersonaOverride`（放 config `io.mycat.config`），并在 `MycatCore` 构造中 `serverConfig` 就绪处调用 `PersonaOverride.apply(System.getProperty("mycat.persona"), serverConfig.getServer())`（确保在 `VersionProfileFactory.from(...)` 注册画像**之前**，见 `MycatCore.java:143`）。

- [ ] **Step 4: 跑，确认通过**；再 `mvn -o -pl server compile` 确认接线编译。

- [ ] **Step 5: 提交**

```bash
git add config/src/main/java/io/mycat/config/PersonaOverride.java server/src/main/java/io/mycat/MycatCore.java config/src/test/java/io/mycat/config/PersonaOverrideTest.java
git commit -m "feat(compat): R3-T7 persona 启动覆盖 -Dmycat.persona (R3-T7)"
```

---

## Task 8: server — VertxMySQLAuthHandler 状态机接线（集成，live 门）

**Files:**
- Modify: `server/src/main/java/io/mycat/vertx/VertxMySQLAuthHandler.java`

**Interfaces:**
- Consumes: `CachingSha2ServerAuth.fastAuthOk/fullAuthOk`、`CachingSha2AuthCache`、`AuthMoreData`、`RsaKeyPairProvider`、`MySQLPacketUtil.generateMySQLPacket`。
- Produces: 行为——(a) native/null 插件客户端：`scramble411` 直校，**删 0xFE 降级分支**（`:87-101`）；(b) caching_sha2 客户端：查 warmed→命中走 `fastAuthOk`→`AuthMoreData.fastOk()`+OK；未 warmed→`AuthMoreData.fullAuth()`→待 `0x02`→`AuthMoreData.publicKey(pem)`→待密文→`fullAuthOk`→成功 `markWarmed`+OK / 失败 ERR。

- [ ] **Step 1: 加状态与分诊字段**

`enum AuthStage { NATIVE, SHA_FAST, SHA_FULL_ASK_PUBKEY, SHA_FULL_RECV_PW }` + 字段 `AuthStage stage`、缓存本轮 `seed=seedParts[2]`、`rightPassword`、`username`、递增 `outPacketId`。`handle()` 按 `authPacket.getAuthPluginName()` 分诊。

- [ ] **Step 2: native 路径**——插件名 native/null：保留现 `checkPassword`(scramble411) 成功→现有 `auth()` 会话+OK；失败→ERR。**移除**向非 native 下发 `AuthSwitchRequestPacket(0xFE)` 的分支（不再降级）。

- [ ] **Step 3: caching_sha2 路径**——
  - 收 AuthResponse（`authPacket.getPassword()` = 32B scramble）：置 `stage=SHA_FAST`。若 `cache.isWarmed(user)` 且 `fastAuthOk(pw,seed,scramble)` → 写 `AuthMoreData.fastOk()` 后走 OK；否则写 `AuthMoreData.fullAuth()`、置 `SHA_FULL_ASK_PUBKEY`。
  - `SHA_FULL_ASK_PUBKEY` 收裸 `0x02` → 写 `AuthMoreData.publicKey(RsaKeyPairProvider.getInstance().publicKeyPem())`、置 `SHA_FULL_RECV_PW`。
  - `SHA_FULL_RECV_PW` 收密文 → `fullAuthOk(pw, seed, cipher, priv, oaep=(生效版本≥8.0.5))` → 真则 `markWarmed`+OK，假则 ERR（**不挂起**）。
  - 每步出站 `packetId` 连续递增（对齐 Review Focus #5）。

- [ ] **Step 4: 编译 + 无回归静态确认**

Run: `mvn -o -pl server compile` → BUILD SUCCESS。人工核：native 分支逻辑未变（仅删降级）、caching_sha2 出站全走 `AuthMoreData`。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/io/mycat/vertx/VertxMySQLAuthHandler.java
git commit -m "feat(compat): R3-T8 前端握手 caching_sha2 状态机接线(fast+full-auth) (R3-T8)"
```

> 本任务行为**只在 Task 9 live e2e 才得证**；提交后 R3 仍 **WIP**，未达 Done。

---

## Task 9: example compat — caching_sha2 前端 live 格 + 矩阵按版本起 persona（DoD 硬门）

**Files:**
- Modify: `example/src/test/java/io/mycat/compat/FrontAuthMatrixTest.java`（新增 FA4–FA7）
- Modify: `.superpowers/sdd/2026-09-30-m1-verification-net/run-matrix.ps1`（起 mycat 时按后端版本传 `-Dmycat.persona`）
- Modify（如非 git-ignored）：`ci/run-example.sh` / workflow 若需同 persona 传参（本地为准，CI 仍 R1 BLOCKED）

**Interfaces:**
- Consumes: 已 boot 的 mycat（persona=8.0）、后端 8.0 caching_sha2 账户、Connector/J 前端。
- Produces: `FrontAuthMatrixTest` 新方法，逐格 PASS。

- [ ] **Step 1: run-matrix 起 mycat 带 persona**

`Start-Process` 的 `-ArgumentList` 加 `-Dmycat.persona=<映射 $V→MYSQL_8_0 等>`：`mysql:8.0→MYSQL_8_0`、`8.4→MYSQL_8_4`、`9.0→MYSQL_9_X`、`5.7→MYSQL_5_7`、`mariadb:*→MYSQL_5_7`（MariaDB 无 sha2，保 native 不回归）。

- [ ] **Step 2: 写 live 格（先跑=红，因未接线前 mycat 仍广告 native）**

```java
// 前置：仅当 persona/后端为 8.0+ 才跑，否则 assumeTrue 记 N/A（5.7/MariaDB 无 sha2）
@Test public void cachingSha2ColdFullAuth() throws Exception {
    assumeTrue(backendIsShaCapable, "后端/画像无 caching_sha2 → N/A");
    // 建 sha2 用户 + 保证冷（flush/新进程）→ 用 Connector/J 强制 caching_sha2 连 mycat 前端
    try (Connection c = DriverManager.getConnection(FRONT_URL, SHA_USER, SHA_PW)) {
        assertEquals(1, executeQuery(c, "select 1").size());
    }
}
@Test public void cachingSha2WarmFastAuth() { /* 紧接上一格重连，应 fast-auth 过 */ }
@Test public void cachingSha2WrongPasswordRejected() { /* 冷/热错口令 assertThrows(SQLException) */ }
```

- [ ] **Step 3: 清残留 + boot 本地 8.0 档**

```powershell
$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
mvn -o -pl server install -DskipTests -am   # 产 fat-jar（含 R3 接线）
pwsh -File .superpowers/sdd/2026-09-30-m1-verification-net/run-matrix.ps1 *>&1 | Tee-Object target/r3-matrix.log
```
Expected: `mysql:8.0` 档 `FrontAuthMatrixTest` 新格由 FAIL/红 → **PASS**；5.7/MariaDB 档 native 格全绿（不回归）。

- [ ] **Step 4: 复核红地图**

读 `matrix-report.md`：8.0 档 caching_sha2 三格 PASS、native 格仍 PASS；其余档不因 R3 变红。若 live 未过 → 用 systematic-debugging 定位（优先查 packetId 序、PEM 格式、OAEP 阈值），**不得**为过而假标 Done。

- [ ] **Step 5: 提交 + 回台账**

```bash
git add example/src/test/java/io/mycat/compat/FrontAuthMatrixTest.java
git add -f .superpowers/sdd/2026-09-30-m1-verification-net/run-matrix.ps1   # 若被 gitignore 则不纳入提交，仅本地驱动
git commit -m "test(compat): R3-T9 前端 caching_sha2 live 格 + 矩阵按版本起 persona (R3-T9)"
```
回写 `DEBT-QUEUE.md`（I-AU-1/I-AU-3/I-AU-5 → 🟡/✅ 视 live 结果）、`COMPAT-MILESTONE-PLAN.md` R3 行、`2026-09-30-mysql-mariadb-compat-PROGRESS.md` R3 条目。**仅当 8.0 档三格真 PASS 才标 Evidenced-Done**，否则 WIP 并说明残余。
