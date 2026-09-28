# Mycat2 Phase 3: MariaDB 接入 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 MySQL/MariaDB 双协议一等公民支持，包括 eddsa fork、认证插件体系、MariaDB 协议兼容层、完整的集成测试套件。

**Architecture:** 基于 Phase 1 创建的 protocol 模块，先实现认证插件体系（AuthPlugin 接口 + 多实现），再实现 MariaDB 协议差异处理（能力标志位、字符集扩展），最后通过 TestContainers 构建 MySQL + MariaDB 集成测试矩阵。

**Tech Stack:** JDK 21, protocol module, eddsa 0.3.0 fork, TestContainers 1.21.0, JUnit 5.12.2

**Spec:** `docs/superpowers/specs/2026-09-28-mycat2-module-redesign-design.md`

**Prerequisite:** Phase 0 + Phase 1 + Phase 2 完成

## Global Constraints

- 所有主力支持版本（MySQL 5.7/8.0/8.4/9.x, MariaDB 10.11.x/11.x）必须有对应的 CI 测试
- 每个任务完成后 `mvn compile -DskipTests` 必须通过
- 每个任务一个 git commit
- 新增认证插件必须先写测试（红绿规则）
- eddsa fork 源码保持 `net.i2p.crypto.eddsa.*` 包名不变

---

## Milestone 3.1: 认证插件体系

**Gate:** 所有认证插件单元测试通过 + MariaDB ed25519 认证可工作

### Task 1: fork eddsa 源码到 protocol 模块

**Files:**
- Create: `protocol/src/main/java/net/i2p/crypto/eddsa/`（从 eddsa 0.3.0 源码 fork）

**Steps:**

- [ ] **Step 1: 下载 eddsa 0.3.0 源码**

```bash
mvn dependency:copy -Dartifact=net.i2p.crypto:eddsa:0.3.0:jar:sources -DoutputDirectory=eddsa-sources
```

- [ ] **Step 2: 解压并分析源码结构**

```bash
cd eddsa-sources
jar xf eddsa-0.3.0-sources.jar
dir /s /b *.java
```

记录所有文件列表（~10-15 个文件）。

- [ ] **Step 3: 复制源码到 protocol 模块**

保持原始包名 `net.i2p.crypto.eddsa.*` 不变：

```bash
cp -r eddsa-sources/net/i2p/crypto/eddsa/ protocol/src/main/java/net/i2p/crypto/eddsa/
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile -pl protocol -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: fork eddsa 0.3.0 source into protocol module"
```

---

### Task 2: 实现 NativePasswordAuthPlugin（mysql_native_password）

**Files:**
- Create: `protocol/src/main/java/io/mycat/protocol/mysql/auth/NativePasswordAuthPlugin.java`
- Create: `protocol/src/test/java/io/mycat/protocol/mysql/auth/NativePasswordAuthPluginTest.java`

**Steps:**

- [ ] **Step 1: 写失败的测试（红）**

```java
package io.mycat.protocol.mysql.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativePasswordAuthPluginTest {

    @Test
    void shouldReturnCorrectPluginName() {
        var plugin = new NativePasswordAuthPlugin();
        assertThat(plugin.name()).isEqualTo("mysql_native_password");
    }

    @Test
    void shouldGenerateCorrectAuthentication() {
        byte[] password = "mypassword".getBytes();
        byte[] seed = new byte[20];
        var plugin = new NativePasswordAuthPlugin();
        byte[] result = plugin.generateAuthentication(password, seed);
        assertThat(result).isNotNull();
        assertThat(result.length).isEqualTo(20);
    }

    @Test
    void shouldVerifyValidAuthentication() {
        byte[] password = "mypassword".getBytes();
        byte[] seed = new byte[20];
        var plugin = new NativePasswordAuthPlugin();
        byte[] authData = plugin.generateAuthentication(password, seed);
        assertThat(plugin.verifyAuthentication(authData, password, seed)).isTrue();
    }

    @Test
    void shouldRejectInvalidAuthentication() {
        byte[] password = "mypassword".getBytes();
        byte[] wrongPassword = "wrongpassword".getBytes();
        byte[] seed = new byte[20];
        var plugin = new NativePasswordAuthPlugin();
        byte[] authData = plugin.generateAuthentication(password, seed);
        assertThat(plugin.verifyAuthentication(authData, wrongPassword, seed)).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl protocol -Dtest=NativePasswordAuthPluginTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现 NativePasswordAuthPlugin（绿）**

```java
package io.mycat.protocol.mysql.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class NativePasswordAuthPlugin implements AuthPlugin {
    @Override
    public String name() { return "mysql_native_password"; }

    @Override
    public byte[] generateAuthentication(byte[] password, byte[] seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash1 = md.digest(password);
            md.reset();
            byte[] hash2 = md.digest(hash1);
            md.reset();
            md.update(seed);
            byte[] hash3 = md.digest(hash2);
            byte[] result = new byte[hash1.length];
            for (int i = 0; i < hash1.length; i++) {
                result[i] = (byte) (hash1[i] ^ hash3[i]);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean verifyAuthentication(byte[] authData, byte[] password, byte[] seed) {
        byte[] expected = generateAuthentication(password, seed);
        return MessageDigest.isEqual(authData, expected);
    }
}
```

- [ ] **Step 4: 运行测试确认通过（绿）**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: implement NativePasswordAuthPlugin with tests"
```

---

### Task 3: 实现 CachingSha2AuthPlugin（caching_sha2_password）

**Files:**
- Create: `protocol/src/main/java/io/mycat/protocol/mysql/auth/CachingSha2AuthPlugin.java`
- Create: `protocol/src/test/java/io/mycat/protocol/mysql/auth/CachingSha2AuthPluginTest.java`

**Steps:**

- [ ] **Step 1: 写测试（红）**

MySQL 8.0 caching_sha2_password 协议：`SHA256(password) XOR SHA256(SHA256(SHA256(password)) + seed)`

- [ ] **Step 2: 实现（绿）**

使用 `MessageDigest.getInstance("SHA-256")` 实现。

- [ ] **Step 3: 运行测试 + Commit**

```bash
git add -A
git commit -m "feat: implement CachingSha2AuthPlugin with tests"
```

---

### Task 4: 实现 Ed25519AuthPlugin（client_ed25519）

**Files:**
- Create: `protocol/src/main/java/io/mycat/protocol/mysql/auth/Ed25519AuthPlugin.java`
- Create: `protocol/src/test/java/io/mycat/protocol/mysql/auth/Ed25519AuthPluginTest.java`

> MariaDB ed25519 认证：SHA512(password) 的前 32 字节作为 Ed25519 私钥，对 seed 进行签名。

**Steps:**

- [ ] **Step 1: 写测试（红）**

- [ ] **Step 2: 实现（绿）**

使用 Task 1 中 fork 的 eddsa 源码实现。

- [ ] **Step 3: 运行测试 + Commit**

```bash
git add -A
git commit -m "feat: implement Ed25519AuthPlugin with eddsa fork"
```

---

### Task 5: 创建 AuthPluginRegistry

**Files:**
- Create: `protocol/src/main/java/io/mycat/protocol/mysql/auth/AuthPluginRegistry.java`
- Create: `protocol/src/test/java/io/mycat/protocol/mysql/auth/AuthPluginRegistryTest.java`

**Steps:**

- [ ] **Step 1: 写测试（红）**

- [ ] **Step 2: 实现（绿）**

```java
package io.mycat.protocol.mysql.auth;

import java.util.*;

public class AuthPluginRegistry {
    private final Map<String, AuthPlugin> plugins = new HashMap<>();

    public AuthPluginRegistry() {
        register(new NativePasswordAuthPlugin());
        register(new CachingSha2AuthPlugin());
        register(new Ed25519AuthPlugin());
    }

    public void register(AuthPlugin plugin) { plugins.put(plugin.name(), plugin); }
    public Optional<AuthPlugin> find(String name) { return Optional.ofNullable(plugins.get(name)); }
    public Collection<AuthPlugin> all() { return Collections.unmodifiableCollection(plugins.values()); }
}
```

- [ ] **Step 3: 运行测试 + Commit**

```bash
git add -A
git commit -m "feat: implement AuthPluginRegistry with default plugins"
```

---

## Milestone 3.2: MariaDB 协议兼容

**Gate:** MariaDB 10.11.x/11.x 客户端可连接到 Mycat2

### Task 6: 实现 MariaDBProtocolCodec（能力标志位探测）

**Files:**
- Create: `protocol/src/main/java/io/mycat/protocol/codec/MariaDBProtocolCodec.java`
- Create: `protocol/src/test/java/io/mycat/protocol/codec/MariaDBProtocolCodecTest.java`

**Steps:**

- [ ] **Step 1: 研究 MariaDB 握手协议差异**

MariaDB 与 MySQL 的关键差异：
- 握手包 server version 包含 "MariaDB" 字符串
- MariaDB 有额外的 capability flags（第 4 个 32 位字）
- 默认认证插件不同

- [ ] **Step 2: 写测试（红）**

- [ ] **Step 3: 实现（绿）**

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: implement MariaDBProtocolCodec with handshake detection"
```

---

### Task 7: 扩展 CapabilityFlags 支持 MariaDB 扩展位

**Files:**
- Modify: `protocol/src/main/java/io/mycat/protocol/mysql/capability/CapabilityFlags.java`

**Steps:**

- [ ] **Step 1: 添加 MariaDB 特有的 capability flags**

```java
MARIADB_CLIENT_PROGRESS(1L << 32),
MARIADB_CLIENT_COM_MULTI(1L << 33),
MARIADB_CLIENT_STMT_BULK_OPERATIONS(1L << 34),
MARIADB_CLIENT_EXTENDED_TYPE_INFO(1L << 35),
MARIADB_CLIENT_CACHE_METADATA(1L << 36),
```

- [ ] **Step 2: 编译验证 + Commit**

```bash
git add -A
git commit -m "feat: extend CapabilityFlags with MariaDB-specific flags"
```

---

### Task 8: 扩展字符集映射表支持 MariaDB 排序规则

**Files:**
- Modify: `protocol/src/main/java/io/mycat/protocol/mysql/charset/` 相关文件

**Steps:**

- [ ] **Step 1: 添加 MariaDB 额外排序规则**

- [ ] **Step 2: 编译验证 + Commit**

```bash
git add -A
git commit -m "feat: extend charset mapping for MariaDB collations"
```

---

### Task 9: datasource 模块新增 MariaDB 后端类型

**Files:**
- Modify: `datasource/pom.xml`
- Modify: `datasource/src/main/java/io/mycat/datasource/jdbc/` 相关文件

**Steps:**

- [ ] **Step 1: 添加 MariaDB JDBC 驱动依赖**

```xml
<dependency>
    <groupId>org.mariadb.jdbc</groupId>
    <artifactId>mariadb-java-client</artifactId>
</dependency>
```

- [ ] **Step 2: 实现 MariaDB 数据源类型**

- [ ] **Step 3: 编译验证 + Commit**

```bash
git add -A
git commit -m "feat: add MariaDB backend type to datasource module"
```

---

## Milestone 3.3: 集成测试套件

**Gate:** MySQL + MariaDB 混合场景全量回归通过

### Task 10: 搭建 TestContainers 测试基础设施

**Files:**
- Create: `example/src/test/java/io/mycat/example/TestContainersBase.java`
- Modify: `example/pom.xml`

**Steps:**

- [ ] **Step 1: 添加 TestContainers 依赖**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mariadb</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: 创建基础测试类**

- [ ] **Step 3: 编译验证 + Commit**

```bash
git add -A
git commit -m "feat: add TestContainers infrastructure for MySQL/MariaDB"
```

---

### Task 11: MySQL 全量集成测试

**Files:**
- Create: `example/src/test/java/io/mycat/example/MySQLIntegrationIT.java`

**Steps:**

- [ ] **Step 1: 实现 CRUD + 事务 + 分片查询测试**

- [ ] **Step 2: 运行集成测试**

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: add MySQL integration test suite"
```

---

### Task 12: MariaDB 全量集成测试

**Files:**
- Create: `example/src/test/java/io/mycat/example/MariaDBIntegrationIT.java`

**Steps:**

- [ ] **Step 1: 实现 MariaDB 10.11.x + 11.x 测试**

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "test: add MariaDB integration test suite"
```

---

### Task 13: 协议兼容性测试套件（TCK）

**Files:**
- Create: `protocol/src/test/java/io/mycat/protocol/ProtocolTCKTest.java`

**Steps:**

- [ ] **Step 1: 设计 TCK 测试矩阵**

| 测试场景 | MySQL 5.7 | MySQL 8.0 | MySQL 9.x | MariaDB 10.11 | MariaDB 11.x |
|---------|-----------|-----------|-----------|--------------|-------------|
| 握手协商 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 认证（native） | ✓ | ✓ | ✓ | ✓ | ✓ |
| 认证（caching_sha2） | - | ✓ | ✓ | - | - |
| 认证（ed25519） | - | - | - | ✓ | ✓ |
| 基本查询 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 事务 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 字符集协商 | ✓ | ✓ | ✓ | ✓ | ✓ |
| LOAD DATA | ✓ | ✓ | ✓ | ✓ | ✓ |

- [ ] **Step 2: 实现参数化测试**

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: add protocol compatibility TCK"
```

---

### Task 14: 书面测试用例文档

**Files:**
- Create: `protocol/src/test/resources/TEST-CASES.md`

**Steps:**

- [ ] **Step 1: 编写 TEST-CASES.md**

按 spec 模板编写每个测试用例的详细文档。

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "docs: add protocol module TEST-CASES.md"
```

---

## Phase 3 完成验证

- [ ] eddsa fork 源码编译通过
- [ ] 三种认证插件单元测试全绿
- [ ] MariaDB 协议编解码测试通过
- [ ] datasource 模块支持 MariaDB 后端
- [ ] MySQL + MariaDB 集成测试全绿
- [ ] TEST-CASES.md 书面用例完整
- [ ] `mvn clean compile -DskipTests` BUILD SUCCESS

---

## 全项目最终验证（Phase 0-3 全部完成后）

```bash
# 1. 全量编译
mvn clean compile -DskipTests

# 2. 单元测试
mvn test

# 3. 集成测试
mvn verify -Dtest=*IT

# 4. 依赖 DAG 验证
mvn dependency:tree

# 5. 内部 API 扫描
findstr /s /r "import sun\.\|import com\.sun\." */src/main/java/*.java
```

**预期结果：**
- 18 模块 5 层架构完整
- JDK 21 零错误编译
- Virtual Thread 无 pinning
- MySQL/MariaDB 双协议全功能
- 测试覆盖率 >15%
