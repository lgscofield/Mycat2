# VersionProfile 版本画像抽象（I-VE-1）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把散落的握手/能力位/`VERSION()` 硬编码收敛成一个配置驱动、成套联动的不可变 `VersionProfile` 画像对象，并移除 `MySQLVersion` 可变静态量。

**Architecture:** 三个新类（`ServerPersona` 枚举 / `VersionProfile` record / `VersionProfileFactory`）落 `config` 模块（最上游叶子，protocol/hbt/proxy/server 均可直达、无环）。启动期 `MycatCore` 构造画像单例并经 `MetaClusterCurrent` 注册进 DI；四个真实消费点（握手字节、`VERSION()`、启动能力位、配置注入）从画像取。`@@version` 族走后端透传，本刀不动。

**Tech Stack:** Java 21（用 `record` 做不可变画像）、Maven 多模块、JUnit 5（junit-jupiter，根 pom 管理 `${junit.version}=5.12.2`）、Lombok（`ServerConfig` 用 `@Data`）、自制 DI `io.mycat.MetaClusterCurrent`。

**Spec:** `docs/superpowers/specs/2026-10-11-version-profile-design.md`

## Global Constraints

- 缺省 persona = `MYSQL_5_7`，其 bundle 逐字段等于当前硬编码：version=`"5.7.33-mycat-2.0"`、characterSet=`8`、statusFlags=`2`、authPlugin=`"mysql_native_password"`、capabilityMask=`MySQLServerCapabilityFlags.getDefaultServerCapabilities()` 当前返回值。
- 零回归：不配置时，握手字节、`SELECT VERSION()` 输出、启动能力位与改动前**完全一致**。
- 画像类只落 `config` 模块；不得让 `config` 依赖 `common`（`MetaClusterCurrent` 在 common，config 无内部依赖，DI 取用只发生在 proxy/hbt/server 消费方）。
- 构建/测试用 JDK 21：`$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'`。
- 精确 `git add <文件>`，排除 `.qoder`、`.mycat-home-e2e`。

## Review Focus

- **persona + 覆盖串优先级**：显式 `serverVersion` 覆盖串胜过 `persona` 内置默认串 → Task 2 测试钉。
- **DI 未注册时 `VERSION()` 不可 NPE**：`MycatVersionFunction.version()` 在 `MetaClusterCurrent.exist(VersionProfile.class)==false`（如单测/引导早期）须回落 `MYSQL_5_7` 默认画像而非抛错 → Task 4 测试钉。
- **握手字节逐字节零回归**：默认 5.7 画像产出的握手字节 == 改动前硬编码产出的字节 → Task 3 golden 测试钉。
- **`getDefaultServerCapabilities()` 的 DEPRECATE_EOF 叠加不变**：启动仍按 `VertxMycatServer:148-152` 逻辑叠加，画像只提供基础掩码 → Task 4 步骤保留原叠加、e2e 兜底。
- **移除可变静态后无悬挂引用**：全仓 `SERVER_VERSION_STRING`/`SERVER_VERSION`/`setServerVersion` 仅 3 处引用（`MySQLVersion` 自身、`MycatVersionFunction:40`、`MySQLClientAuthHandler:251`、`MycatCore:108`）——Task 3/4 全部改写，Task 4 Step 5 用 grep 证明归零。

---

## 实现状态（2026-10-11 · Native 执行 + 整分支独立 CodeReview）

全部 4 任务已交付并本地提交（**未 push**）：T1 `d24542286` / T2 `a85c0ec55` / T3 `534b423f8` / T4 `8ca4690e3`；评审修复 `b7294f94d`。全套 reactor `BUILD SUCCESS`，0 failures / 0 errors（实测 config 3 / common 15 / proxy 2 / router 18 / va 12 / hbt 130 / executor 0）。

**偏离计划之处**（正文对应步骤保留原文 + 就地 `⚠️ 订正`）：
- **Critical#1**：Task2 Step4 / Task4 Step5 的"把画像加进 `Maps.of`（:107）注册"是错的——`MetaClusterCurrent.register(Map)` 整表替换会抹掉它，启动即 NPE（已 boot 实测：修复前致命 NPE @ `VertxMycatServer:147`）。修复=画像 `context.put` 进持久 context（`MycatCore.java` L143 前）。
- **Important#4**：Task3 追加正向测试 `profileVersionStringDrivesHandshakeBytes`（补 golden 同值参照的"退化写死"盲区）。
- **Rulings**：persona 首版为"声明未坐实"旋钮（`serverVersion` 非空且驱动 DEPRECATE_EOF，不改）；`VERSION()` 保留 `defaultProfile()` 回落（spec 明确不可 NPE）。
- **Deferred minors（记档，未进修复轮）**：握手 caps 仍走形参非 `profile.capabilityMask()`；`VersionProfileFactory.of` 无 persona null 防护；消费点用全限定名而非 import；persona 热更不重建画像快照。
- **e2e 门**：Task4 Step7 的 `ConnectionSmokeTest`/`FrontAuthMatrixTest` 需 Docker+8066，仍未跑，属交付前必跑。

---

### Task 1: VersionProfile 画像三件套（config 新增，纯加法）

**Files:**
- Create: `config/src/main/java/io/mycat/config/ServerPersona.java`
- Create: `config/src/main/java/io/mycat/config/VersionProfile.java`
- Create: `config/src/main/java/io/mycat/config/VersionProfileFactory.java`
- Modify: `config/pom.xml`（加 junit-jupiter test 依赖，版本走根 pom 管理）
- Test: `config/src/test/java/io/mycat/config/VersionProfileTest.java`

**Interfaces:**
- Produces:
  - `enum ServerPersona { MYSQL_5_7, MYSQL_8_0, MYSQL_8_4, MYSQL_9_X, MARIADB_10_6, MARIADB_10_11, MARIADB_11_4 }`，每个常量携带 `String defaultVersionString()`。
  - `record VersionProfile(String serverVersionString, int capabilityMask, String authPlugin, int characterSet, int statusFlags)`。
  - `static VersionProfile VersionProfileFactory.of(ServerPersona persona, String overrideVersionString)`。
  - `static VersionProfile VersionProfileFactory.from(io.mycat.config.ServerConfig serverConfig)`（Task 2 用到 persona 字段后完善）。
  - `static VersionProfile VersionProfileFactory.defaultProfile()`（= MYSQL_5_7 零回归画像）。

- [x] **Step 1: 写失败测试**

```java
// config/src/test/java/io/mycat/config/VersionProfileTest.java
package io.mycat.config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class VersionProfileTest {
    @Test
    public void mysql57BundleEqualsCurrentHardcodedDefaults() {
        VersionProfile p = VersionProfileFactory.of(ServerPersona.MYSQL_5_7, null);
        assertEquals("5.7.33-mycat-2.0", p.serverVersionString());
        assertEquals(8, p.characterSet());
        assertEquals(2, p.statusFlags());
        assertEquals("mysql_native_password", p.authPlugin());
        // 能力位必须等于当前 getDefaultServerCapabilities() 返回值（零回归锚点）
        assertEquals(MySQLServerCapabilityFlags.getDefaultServerCapabilities(), p.capabilityMask());
    }
    @Test
    public void explicitVersionOverridesPersonaDefault() {
        VersionProfile p = VersionProfileFactory.of(ServerPersona.MYSQL_5_7, "8.0.40-mycat");
        assertEquals("8.0.40-mycat", p.serverVersionString());
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn -o -q -pl config test -Dtest=VersionProfileTest`
Expected: 编译失败（类不存在）。

- [x] **Step 3: 实现三件套**

`ServerPersona` 枚举：每个常量存一个 `defaultVersionString`，仅 `MYSQL_5_7 = "5.7.33-mycat-2.0"` 坐实；其余常量给占位版本串（如 `MYSQL_8_0 = "8.0.34"`、`MARIADB_10_6 = "10.6.14-MariaDB"` 等），但**能力位/字符集对非 5.7 暂不特殊化**——`of()` 对所有 persona 统一返回 `capabilityMask=getDefaultServerCapabilities()`、`characterSet=8`、`statusFlags=2`、`authPlugin="mysql_native_password"`（spec §1 决策：非 5.7 的能力位差异留 T-AUTH，不投机）。

`VersionProfileFactory.of(persona, override)`：`serverVersionString = (override != null && !override.isEmpty()) ? override : persona.defaultVersionString()`；其余字段如上（当前对所有 persona 相同，5.7 为唯一坐实值）。`defaultProfile()` = `of(MYSQL_5_7, null)`。

`config/pom.xml` 加：
```xml
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

- [x] **Step 4: 跑测试确认通过**

Run: `mvn -o -q -pl config test -Dtest=VersionProfileTest`
Expected: PASS（2 方法）。

- [x] **Step 5: 提交**

```bash
git add config/src/main/java/io/mycat/config/ServerPersona.java config/src/main/java/io/mycat/config/VersionProfile.java config/src/main/java/io/mycat/config/VersionProfileFactory.java config/pom.xml config/src/test/java/io/mycat/config/VersionProfileTest.java
git commit -m "feat(I-VE-1): 新增 VersionProfile 画像三件套（config），MYSQL_5_7 零回归锚点"
```

---

### Task 2: ServerConfig.persona + 工厂入口 + MycatCore 注册画像入 DI（与旧静态共存）

**Files:**
- Modify: `config/src/main/java/io/mycat/config/ServerConfig.java`（加 `persona` 字段）
- Modify: `config/src/main/java/io/mycat/config/VersionProfileFactory.java`（补 `from(ServerConfig)`）
- Modify: `server/src/main/java/io/mycat/MycatCore.java:107`（把画像加进 `Maps.of` 注册；**暂保留** :108 `setServerVersion`）
- Test: `config/src/test/java/io/mycat/config/VersionProfileTest.java`（加 `from` 用例）

**Interfaces:**
- Consumes: `ServerConfig.getServerVersion()`（Lombok `@Data` 生成）、`ServerPersona`。
- Produces: `ServerConfig.getPersona()`（Lombok 生成）、`static VersionProfile VersionProfileFactory.from(ServerConfig)`。

- [x] **Step 1: 写失败测试**

```java
@Test
public void fromServerConfigUsesOverrideThenPersona() {
    io.mycat.config.ServerConfig cfg = new io.mycat.config.ServerConfig();
    // 默认：persona=MYSQL_5_7、serverVersion 仍为其字段默认 "5.7.33-mycat-2.0"
    assertEquals("5.7.33-mycat-2.0", VersionProfileFactory.from(cfg).serverVersionString());
    cfg.setPersona(ServerPersona.MYSQL_8_0);
    cfg.setServerVersion(null); // 清掉覆盖串 -> 走 persona 默认
    assertEquals("8.0.34", VersionProfileFactory.from(cfg).serverVersionString());
    cfg.setServerVersion("9.9.9-custom"); // 覆盖串优先
    assertEquals("9.9.9-custom", VersionProfileFactory.from(cfg).serverVersionString());
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl config test -Dtest=VersionProfileTest`
Expected: 编译失败（`getPersona`/`from` 不存在）。

- [x] **Step 3: 加字段与工厂方法**

`ServerConfig` 在 `serverVersion` 字段附近加：`private ServerPersona persona = ServerPersona.MYSQL_5_7;`（`@Data` 自动出 getter/setter）。
`VersionProfileFactory.from(ServerConfig cfg)` = `of(cfg.getPersona(), cfg.getServerVersion())`（覆盖串空/ null 时回落 persona，已由 `of` 处理）。

- [x] **Step 4: MycatCore 注册画像**

在 `MycatCore.java:107` 的 `Maps.of(...)` 追加一对：`, VersionProfile.class, VersionProfileFactory.from(serverConfig.getServer())`。保留 :108 `setServerVersion`（Task 4 删）。此时新旧并存，树可编译。

> ⚠️ **订正（评审 Critical#1，已 boot 实测）**：本步的注册落点是错的——`Maps.of(...)` 走 `register(Map)` 整表替换，而 MycatCore 构造在 L143/L172 会用新建 `context` 再 `register` 一次，把这里早注册的画像**抹除**，运行期 `VertxMycatServer:147`/`VertxMySQLAuthHandler:58` 的 `wrapper()` → NPE，启动即崩（单测不穿装配序列故当时全绿）。**最终修复（commit `b7294f94d`）= 画像改 `context.put(VersionProfile.class, VersionProfileFactory.from(serverConfig.getServer()))` 进持久 context（L143 前）**，并从 L106 的 `Maps.of` 移除。

- [x] **Step 5: 跑测试确认通过 + 编译 server**

Run: `mvn -o -q -pl config test -Dtest=VersionProfileTest && mvn -o -q -pl server -am test-compile`
Expected: 测试 PASS；server test-compile BUILD SUCCESS。

- [x] **Step 6: 提交**

```bash
git add config/src/main/java/io/mycat/config/ServerConfig.java config/src/main/java/io/mycat/config/VersionProfileFactory.java config/src/test/java/io/mycat/config/VersionProfileTest.java server/src/main/java/io/mycat/MycatCore.java
git commit -m "feat(I-VE-1): ServerConfig.persona + 工厂入口，MycatCore 注册 VersionProfile 入 DI"
```

---

### Task 3: 握手字节改读画像（createHandshakePayload + 活调用方）

**Files:**
- Modify: `proxy/src/main/java/io/mycat/proxy/handler/front/MySQLClientAuthHandler.java:248-264`（`createHandshakePayload` 加 `VersionProfile` 参、读画像）
- Modify: `server/src/main/java/io/mycat/vertx/VertxMySQLAuthHandler.java:58`（构造器内取 DI 画像并传入）
- Modify: `config/pom.xml` 无关；proxy 无需新依赖（proxy→config 已存在）
- Test: `proxy/src/test/java/io/mycat/proxy/handler/front/HandshakePayloadRegressionTest.java`（新建 proxy 测试根 + pom 加 junit-jupiter）

**Interfaces:**
- Consumes: `VersionProfile`（`serverVersionString/characterSet/statusFlags/authPlugin`）、`MySQLVersion.PROTOCOL_VERSION`（保留）。
- Produces: `static byte[] MySQLClientAuthHandler.createHandshakePayload(long sessionId, int serverCapabilities, byte[][] seedParts, io.mycat.config.VersionProfile profile)`。

- [x] **Step 1: 写 golden 回归测试（先失败——签名不含 profile）**

测试用固定 seedParts 输入，断言改后方法产出字节 == 用**当前硬编码值**手工序列化的 `HandshakePacket` 字节（零回归证明）：

```java
// proxy/src/test/java/io/mycat/proxy/handler/front/HandshakePayloadRegressionTest.java
package io.mycat.proxy.handler.front;
import io.mycat.beans.mysql.packet.HandshakePacket;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import io.mycat.protocol.mysql.type.MySQLVersion;
import io.mycat.protocol.mysql.type.MySQLPayloadWriter; // 与 createHandshakePayload L261 同款 writer
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class HandshakePayloadRegressionTest {
    @Test
    public void defaultProfileProducesIdenticalHandshakeBytes() {
        long sid = 42L;
        int caps = MySQLServerCapabilityFlags.getDefaultServerCapabilities();
        byte[][] seed = { "12345678".getBytes(), "abcdefghijklmnopqrst".getBytes(), "u".getBytes() };
        VersionProfile p = VersionProfileFactory.defaultProfile(); // MYSQL_5_7

        // 参照：按改动前的硬编码字段直接拼装
        HandshakePacket ref = new HandshakePacket();
        ref.setProtocolVersion(MySQLVersion.PROTOCOL_VERSION);
        ref.setServerVersion("5.7.33-mycat-2.0");
        ref.setConnectionId(sid);
        ref.setAuthPluginDataPartOne(new String(seed[0]));
        ref.setCapabilities(new MySQLServerCapabilityFlags(caps));
        ref.setHasPartTwo(true);
        ref.setCharacterSet(8);
        ref.setStatusFlags(2);
        ref.setAuthPluginDataLen(21);
        ref.setAuthPluginDataPartTwo(new String(seed[1]));
        ref.setAuthPluginName("mysql_native_password");
        MySQLPayloadWriter refWriter = new MySQLPayloadWriter();
        ref.writePayload(refWriter);

        byte[] actual = MySQLClientAuthHandler.createHandshakePayload(sid, caps, seed, p);
        assertArrayEquals(refWriter.toByteArray(), actual);
    }
}
```

> 包路径已核实：`MySQLPayloadWriter`= `io.mycat.protocol.mysql.type`（L22）、`HandshakePacket` = `io.mycat.beans.mysql.packet`（L26）。测试顶部 `HandshakePacket` import 用后者。

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl proxy test -Dtest=HandshakePayloadRegressionTest`
Expected: 编译失败（4 参重载不存在）。若 proxy 无 junit 依赖，先给 `proxy/pom.xml` 加 junit-jupiter(test)。

- [x] **Step 3: 改 `createHandshakePayload` 读画像**

签名加 `VersionProfile profile`；方法体把 L251 `new String(MySQLVersion.SERVER_VERSION)` → `profile.serverVersionString()`；L256 `setCharacterSet(8)` → `profile.characterSet()`；L257 `setStatusFlags(2)` → `profile.statusFlags()`；L260 `MysqlNativePasswordPluginUtil.PROTOCOL_PLUGIN_NAME` → `profile.authPlugin()`。`setCapabilities(new MySQLServerCapabilityFlags(serverCapabilities))`、`PROTOCOL_VERSION`、seed 相关、`setAuthPluginDataLen(21)` 保持不变。
同文件 `sendAuthPackge`(L234，死码路径) 若仍引用旧签名：改为 `createHandshakePayload(sessionId, serverCapabilities, seedParts, MetaClusterCurrent.wrapper(VersionProfile.class))`（proxy→common 已有依赖）。

- [x] **Step 4: 改活调用方 VertxMySQLAuthHandler:58**

在构造器内取画像并传入：
```java
io.mycat.config.VersionProfile profile = io.mycat.MetaClusterCurrent.wrapper(io.mycat.config.VersionProfile.class);
byte[] handshakePacket = MySQLClientAuthHandler.createHandshakePayload(
        mycatDataContext.getSessionId(), defaultServerCapabilities, seedParts, profile);
```

- [x] **Step 5: 跑测试确认通过 + server 编译**

Run: `mvn -o -q -pl proxy test -Dtest=HandshakePayloadRegressionTest && mvn -o -q -pl server -am test-compile`
Expected: golden 测试 PASS；server test-compile SUCCESS。

- [x] **Step 6: 提交**

```bash
git add proxy/src/main/java/io/mycat/proxy/handler/front/MySQLClientAuthHandler.java server/src/main/java/io/mycat/vertx/VertxMySQLAuthHandler.java proxy/pom.xml proxy/src/test/java/io/mycat/proxy/handler/front/HandshakePayloadRegressionTest.java
git commit -m "feat(I-VE-1): 握手字节改读 VersionProfile，加零回归 golden 字节锁"
```

---

### Task 4: `VERSION()`/启动能力位改读画像 + 移除 MySQLVersion 可变静态（I-VE-3 收口）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/sqlfunction/infofunction/MycatVersionFunction.java:39-41`
- Modify: `server/src/main/java/io/mycat/vertx/VertxMycatServer.java:147`（caps 从画像取）
- Modify: `protocol/src/main/java/io/mycat/protocol/mysql/type/MySQLVersion.java`（删 `SERVER_VERSION_STRING`/`SERVER_VERSION`/`setServerVersion`，留 `PROTOCOL_VERSION`）
- Modify: `server/src/main/java/io/mycat/MycatCore.java:108`（删 `setServerVersion` 调用）
- Test: `hbt/src/test/java/io/mycat/calcite/sqlfunction/infofunction/MycatVersionFunctionTest.java`

**Interfaces:**
- Consumes: `VersionProfile.serverVersionString()`、`VersionProfile.capabilityMask()`、`MetaClusterCurrent.wrapper/exist`。
- Produces: 无新对外接口（内部改读）。

- [x] **Step 1: 写 `VERSION()` 回退 + DI 用例（先失败）**

```java
// hbt/src/test/java/io/mycat/calcite/sqlfunction/infofunction/MycatVersionFunctionTest.java
package io.mycat.calcite.sqlfunction.infofunction;
import io.mycat.MetaClusterCurrent;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MycatVersionFunctionTest {
    @AfterEach
    public void clear() { MetaClusterCurrent.register(new java.util.IdentityHashMap<>()); }

    @Test
    public void versionReturnsProfileStringWhenRegistered() {
        MetaClusterCurrent.register(new java.util.IdentityHashMap<>() {{
            put(VersionProfile.class, VersionProfileFactory.of(
                io.mycat.config.ServerPersona.MYSQL_5_7, "8.8.8-probe"));
        }});
        assertEquals("8.8.8-probe", MycatVersionFunction.version());
    }
    @Test
    public void versionFallsBackToDefaultWhenDiEmpty() {
        MetaClusterCurrent.register(new java.util.IdentityHashMap<>()); // 无画像
        assertEquals("5.7.33-mycat-2.0", MycatVersionFunction.version()); // 不 NPE
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o -q -pl hbt test -Dtest=MycatVersionFunctionTest`
Expected: FAIL（version() 仍读静态串，两断言不符/未回落）。

- [x] **Step 3: 改 `MycatVersionFunction.version()`**

```java
public static String version(){
    return MetaClusterCurrent.exist(VersionProfile.class)
            ? MetaClusterCurrent.wrapper(VersionProfile.class).serverVersionString()
            : VersionProfileFactory.defaultProfile().serverVersionString();
}
```
删去对 `io.mycat.protocol.mysql.type.MySQLVersion` 的 import，加 `io.mycat.MetaClusterCurrent`、`io.mycat.config.VersionProfile`、`io.mycat.config.VersionProfileFactory`（hbt→common/config 已有依赖）。

- [x] **Step 4: 改 `VertxMycatServer:147` 能力位来源**

`int defaultServerCapabilities = MySQLServerCapabilityFlags.getDefaultServerCapabilities();` →
`int defaultServerCapabilities = MetaClusterCurrent.wrapper(VersionProfile.class).capabilityMask();`
保留紧随其后的 `CLIENT_DEPRECATE_EOF` 叠加逻辑（L148-152）不动。加 `io.mycat.config.VersionProfile` import。

- [x] **Step 5: 移除可变静态 + 悬挂引用归零**

`MySQLVersion.java`：删 `SERVER_VERSION_STRING`、`SERVER_VERSION`、`setServerVersion(...)`，保留 `PROTOCOL_VERSION`。
`MycatCore.java:108`：删 `MySQLVersion.setServerVersion(...)` 整行（画像已在 :107 注册）。

> ⚠️ **订正（承接 Task2 Step4）**：删 `setServerVersion` 正确，但"画像已在 :107 注册"不再成立——评审修复后画像落点在**持久 context（L143 前 `context.put`）**，非 :107 的 `Maps.of`（那会被整表替换抹除，见 Critical#1）。
Run: `Select-String -Path (Get-ChildItem -Recurse -Filter *.java) -Pattern 'SERVER_VERSION_STRING|setServerVersion|MySQLVersion\.SERVER_VERSION\b'`
Expected: 仅剩 `MySQLVersion.java` 内 `setServerVersion` 若保留则无；期望命中为 0（握手/VERSION 已改读画像；`HandshakePacket.setServerVersion` 是包内不同方法，不算）。

- [x] **Step 6: 跑测试确认通过 + 编译面全绿**

Run: `mvn -o -q -pl hbt test -Dtest=MycatVersionFunctionTest && mvn -o -q -pl protocol,config,proxy,server -am test-compile`
Expected: 测试 PASS；各模块 test-compile BUILD SUCCESS（证明静态移除无悬挂引用）。

- [x] **Step 7: 活路径零回归 e2e + 全单测基线**

Run: `mvn -o -q -pl config,common,router,va,hbt,executor test -Dmaven.test.failure.ignore=true`
Expected: config/common/router/va/hbt/executor 无新增红（对齐既有基线：common 15/0/0、router 18/0/0、va 12/0/0、hbt 128/0/0、executor 0）。
> ⚠️ **交付实况**：实测 hbt 基线升至 **130/0/0**（128 + `MycatVersionFunctionTest` 2）、config 3/0/0、proxy 2/0/0；全套 reactor `BUILD SUCCESS` 0/0。下方 e2e（`ConnectionSmokeTest`/`FrontAuthMatrixTest`）**仍待 Docker+8066，属交付前必跑门，本首版尚未实跑**。
（活路径握手/`SELECT VERSION()` 端到端由 `example` 的 `ConnectionSmokeTest`/`FrontAuthMatrixTest` 覆盖，需 Docker m1-mysql + 8066 实跑，属验收环节，若环境具备则跑，否则记为交付前必跑门。）

- [x] **Step 8: 提交**

```bash
git add hbt/src/main/java/io/mycat/calcite/sqlfunction/infofunction/MycatVersionFunction.java server/src/main/java/io/mycat/vertx/VertxMycatServer.java protocol/src/main/java/io/mycat/protocol/mysql/type/MySQLVersion.java server/src/main/java/io/mycat/MycatCore.java hbt/src/test/java/io/mycat/calcite/sqlfunction/infofunction/MycatVersionFunctionTest.java
git commit -m "feat(I-VE-1): VERSION()/启动能力位改读画像，移除 MySQLVersion 可变静态（I-VE-3 收口）"
```

---

## 出口判据（对齐 spec §4 DoD）

- 默认 persona 零回归：握手 golden 字节锁 + `VERSION()` 默认值断言 + e2e 活路径绿。
- 画像可配：`persona` + 覆盖串两路径产出自洽 bundle（Task 2 测试）。
- 可变静态 `MySQLVersion` 已移除、悬挂引用 grep 归零（Task 4 Step 5）。
- 全单测基线无新增红（Task 4 Step 7）。
