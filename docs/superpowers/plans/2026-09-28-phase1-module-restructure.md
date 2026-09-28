# Mycat2 Phase 1: 模块重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将三个 God Module 拆解为 18 模块 5 层架构，迁移 ~408 个文件，创建 protocol 和 calcite-core 新模块，重命名 mycat2 → server。

**Architecture:** 按照依赖 DAG 的自底向上顺序执行：先创建底层 protocol 模块（Layer 1）→ calcite-core 模块（Layer 2）→ 重命名 server（Layer 4）→ 迁移函数到 executor（Layer 3）。每次文件迁移后立即修复所有级联 import。

**Tech Stack:** JDK 21, Maven, Calcite 1.42.0, Arrow 18.1.0

**Spec:** `docs/superpowers/specs/2026-09-28-mycat2-module-redesign-design.md`

**Prerequisite:** Phase 0 完成（`mvn compile -DskipTests` 在 JDK 21 下 BUILD SUCCESS）

## Global Constraints

- 每个任务完成后 `mvn compile -DskipTests` 必须通过
- 每个任务一个 git commit
- 文件移动使用 `git mv`（保留 git history）
- 每次移动前用 `grep -rn` 扫描所有引用方，列出级联清单
- 包名变更后所有 import 必须同步更新
- 新建模块必须在根 `pom.xml` 的 `<modules>` 中注册

---

## Milestone 1.1: 创建 protocol 模块（~95 文件迁入）

**Gate:** `mvn compile -pl protocol` 通过 + `mvn compile -DskipTests` 全项目通过

### Task 1: 创建 protocol 模块骨架

**Files:**
- Create: `protocol/pom.xml`
- Modify: `pom.xml`（根 POM `<modules>` 部分）

**Steps:**

- [ ] **Step 1: 创建 protocol 目录结构**

```bash
mkdir -p protocol/src/main/java/io/mycat/protocol
mkdir -p protocol/src/main/resources
mkdir -p protocol/src/test/java/io/mycat/protocol
mkdir -p protocol/src/test/resources
```

- [ ] **Step 2: 创建 protocol/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.mycat</groupId>
        <artifactId>mycat-parent</artifactId>
        <version>1.22-release</version>
    </parent>

    <artifactId>protocol</artifactId>
    <name>Mycat2 Protocol</name>
    <description>MySQL/MariaDB protocol types, packet structures, encoding/decoding, authentication plugins</description>

    <dependencies>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>common</artifactId>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>config</artifactId>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-buffer</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

注意：`<parent>` 的 `<artifactId>` 需要与根 POM 的实际 artifactId 匹配。先读取根 `pom.xml` 确认。

- [ ] **Step 3: 在根 pom.xml 的 `<modules>` 中添加 protocol**

在 `<modules>` 中 `<module>common</module>` 之后添加：
```xml
<module>protocol</module>
```

- [ ] **Step 4: 验证模块注册**

Run: `mvn validate -pl protocol`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add protocol/ pom.xml
git commit -m "feat: create protocol module skeleton"
```

---

### Task 2: 创建 protocol 包结构

**Files:**
- Create: 多个 `package-info.java` 文件

**Steps:**

- [ ] **Step 1: 创建包目录和 package-info.java**

```bash
mkdir -p protocol/src/main/java/io/mycat/protocol/mysql/packet
mkdir -p protocol/src/main/java/io/mycat/protocol/mysql/type
mkdir -p protocol/src/main/java/io/mycat/protocol/mysql/charset
mkdir -p protocol/src/main/java/io/mycat/protocol/mysql/capability
mkdir -p protocol/src/main/java/io/mycat/protocol/mysql/auth
mkdir -p protocol/src/main/java/io/mycat/protocol/mysql/util
mkdir -p protocol/src/main/java/io/mycat/protocol/codec
mkdir -p protocol/src/main/java/io/mycat/protocol/swapbuffer
```

每个包创建一个 `package-info.java`：
```java
/**
 * MySQL/MariaDB protocol packet structures.
 */
package io.mycat.protocol.mysql.packet;
```

- [ ] **Step 2: Commit**

```bash
git add protocol/
git commit -m "feat: create protocol package structure"
```

---

### Task 3: 移动 beans/mysql/ (28 文件) → protocol/mysql/type/

**Files:**
- Move: `common/src/main/java/io/mycat/beans/mysql/*.java` (28 files)
- Target: `protocol/src/main/java/io/mycat/protocol/mysql/type/`

> ⚠️ 先确认精确文件列表。读取 `common/src/main/java/io/mycat/beans/mysql/` 目录获取完整文件清单。

**Steps:**

- [ ] **Step 1: 列出 beans/mysql/ 下的所有文件**

```bash
dir /b common\src\main\java\io\mycat\beans\mysql\*.java
```

记录所有 28 个文件名。

- [ ] **Step 2: 扫描引用——列出所有 import 这些类的文件**

```bash
grep -rn "import io.mycat.beans.mysql\." --include="*.java" .
```

记录完整的引用清单（预期 100+ 处引用），这是级联修改清单。

- [ ] **Step 3: 使用 git mv 移动文件**

对每个文件执行：
```bash
git mv common/src/main/java/io/mycat/beans/mysql/XxxType.java protocol/src/main/java/io/mycat/protocol/mysql/type/XxxType.java
```

- [ ] **Step 4: 更新移动文件的 package 声明**

对所有已移动文件：
```java
// 旧
package io.mycat.beans.mysql;
// 新
package io.mycat.protocol.mysql.type;
```

- [ ] **Step 5: 更新所有引用方的 import**

根据 Step 2 的引用清单，逐个文件更新：
```java
// 旧
import io.mycat.beans.mysql.XxxType;
// 新
import io.mycat.protocol.mysql.type.XxxType;
```

如果引用量大（>50 处），可使用 IDE 的全局重构功能或 sed/findstr 批量替换：
```bash
# PowerShell
Get-ChildItem -Recurse -Filter *.java | ForEach-Object { (Get-Content $_) -replace 'import io\.mycat\.beans\.mysql\.', 'import io.mycat.protocol.mysql.type.' | Set-Content $_ }
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -pl protocol -DskipTests`
Expected: BUILD SUCCESS

如果编译失败，分析错误原因（可能是跨模块依赖缺失），在 protocol/pom.xml 中添加必要的依赖。

- [ ] **Step 7: 全项目编译验证**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: move beans/mysql/ (28 files) to protocol/mysql/type/"
```

---

### Task 4: 移动 beans/mysql/charset/ (4 文件) → protocol/mysql/charset/

**Files:**
- Move: `common/src/main/java/io/mycat/beans/mysql/charset/*.java` (4 files)
- Target: `protocol/src/main/java/io/mycat/protocol/mysql/charset/`

**Steps:**

- [ ] **Step 1: 列出文件并扫描引用**

```bash
dir /b common\src\main\java\io\mycat\beans\mysql\charset\*.java
grep -rn "import io.mycat.beans.mysql.charset\." --include="*.java" .
```

- [ ] **Step 2: git mv 移动所有文件**

```bash
git mv common/src/main/java/io/mycat/beans/mysql/charset/ protocol/src/main/java/io/mycat/protocol/mysql/charset/
```

- [ ] **Step 3: 更新 package 声明和所有引用**

```java
// 旧 package
package io.mycat.beans.mysql.charset;
// 新 package
package io.mycat.protocol.mysql.charset;
```

- [ ] **Step 4: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move beans/mysql/charset/ (4 files) to protocol/mysql/charset/"
```

---

### Task 5: 移动 beans/mysql/packet/ (17 文件) → protocol/mysql/packet/

**Files:**
- Move: `common/src/main/java/io/mycat/beans/mysql/packet/*.java` (17 files)
- Target: `protocol/src/main/java/io/mycat/protocol/mysql/packet/`

**Steps:**

- [ ] **Step 1: 列出文件并扫描引用**

```bash
dir /b common\src\main\java\io\mycat\beans\mysql\packet\*.java
grep -rn "import io.mycat.beans.mysql.packet\." --include="*.java" .
```

- [ ] **Step 2: git mv 移动所有文件**

```bash
git mv common/src/main/java/io/mycat/beans/mysql/packet/ protocol/src/main/java/io/mycat/protocol/mysql/packet/
```

- [ ] **Step 3: 更新 package 声明和所有引用**

```java
// 旧 package
package io.mycat.beans.mysql.packet;
// 新 package
package io.mycat.protocol.mysql.packet;
```

- [ ] **Step 4: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move beans/mysql/packet/ (17 files) to protocol/mysql/packet/"
```

---

### Task 6: 移动 swapbuffer/ (10 文件) → protocol/swapbuffer/

**Files:**
- Move: `common/src/main/java/io/mycat/swapbuffer/*.java` (10 files)
- Target: `protocol/src/main/java/io/mycat/protocol/swapbuffer/`

**Steps:**

- [ ] **Step 1: 列出文件并扫描引用**

```bash
dir /b common\src\main\java\io\mycat\swapbuffer\*.java
grep -rn "import io.mycat.swapbuffer\." --include="*.java" .
```

- [ ] **Step 2: git mv 移动**

```bash
git mv common/src/main/java/io/mycat/swapbuffer/ protocol/src/main/java/io/mycat/protocol/swapbuffer/
```

- [ ] **Step 3: 更新 package 声明和所有引用**

- [ ] **Step 4: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move swapbuffer/ (10 files) to protocol/swapbuffer/"
```

---

### Task 7: 移动 util/packet/ (8 文件) → protocol/codec/

**Files:**
- Move: `common/src/main/java/io/mycat/util/packet/*.java` (8 files)
- Target: `protocol/src/main/java/io/mycat/protocol/codec/`

**Steps:**

- [ ] **Step 1: 列出文件并扫描引用**

```bash
dir /b common\src\main\java\io\mycat\util\packet\*.java
grep -rn "import io.mycat.util.packet\." --include="*.java" .
```

- [ ] **Step 2: git mv 移动**

```bash
git mv common/src/main/java/io/mycat/util/packet/ protocol/src/main/java/io/mycat/protocol/codec/
```

- [ ] **Step 3: 更新 package 声明和所有引用**

- [ ] **Step 4: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move util/packet/ (8 files) to protocol/codec/"
```

---

### Task 8: 移动 Vert.x codec 补丁 (4 文件) → protocol/mysql/codec/

**Files:**
- Move: `vertx/src/main/java/io/vertx/mysqlclient/impl/codec/*.java` (3-4 files)
- Target: `protocol/src/main/java/io/mycat/protocol/mysql/codec/`

> ⚠️ 这些是 Vert.x MySQL client 的内部 codec 补丁，移到 protocol 后需要考虑包名变化对 Vert.x SPI 加载的影响。如果 Vert.x 使用 SPI 按包路径加载 codec，可能需要保留在 vertx 模块中。

**Steps:**

- [ ] **Step 1: 阅读 vertx 模块的 codec 文件，确认 SPI 加载机制**

读取以下文件：
- `vertx/src/main/java/io/vertx/mysqlclient/impl/codec/` 下的所有文件
- 检查是否有 `META-INF/services/` 下的 SPI 声明

- [ ] **Step 2: 决策——是否移动**

如果 Vert.x codec 通过 SPI 按包路径加载（`io.vertx.mysqlclient.impl.codec.*`），则**不移动**到 protocol，保留在 vertx 模块中。在 spec 中标注此决策。

如果可以安全移动，执行 git mv + 更新 package + 更新引用。

- [ ] **Step 3: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move/retain Vert.x codec patches"
```

---

### Task 9: 移动 MySQL 工具类 (~10 文件) → protocol/mysql/util/

**Files:**
- Move: SecurityUtil, MySQLUtil, PacketUtil 等 MySQL 专用工具类
- Target: `protocol/src/main/java/io/mycat/protocol/mysql/util/`

**Steps:**

- [ ] **Step 1: 识别 common 中的 MySQL 专用工具类**

```bash
grep -rn "import io.mycat.util.SecurityUtil\|import io.mycat.util.MySQLUtil\|import io.mycat.util.PacketUtil" --include="*.java" .
```

同时检查 `common/src/main/java/io/mycat/util/` 中哪些文件是 MySQL 专用的（引用了 `beans.mysql.*`）。

- [ ] **Step 2: git mv 移动 + 更新 package + 更新引用**

同前面的模式。

- [ ] **Step 3: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move MySQL utility classes to protocol/mysql/util/"
```

---

### Task 10: 创建协议抽象接口

**Files:**
- Create: `protocol/src/main/java/io/mycat/protocol/codec/ProtocolCodec.java`
- Create: `protocol/src/main/java/io/mycat/protocol/mysql/auth/AuthPlugin.java`
- Create: `protocol/src/main/java/io/mycat/protocol/mysql/capability/CapabilityFlags.java`
- Create: `protocol/src/main/java/io/mycat/protocol/mysql/capability/ServerStatus.java`
- Create: 对应的单元测试骨架

**Steps:**

- [ ] **Step 1: 创建 AuthPlugin 接口**

```java
package io.mycat.protocol.mysql.auth;

/**
 * Authentication plugin interface for MySQL/MariaDB.
 * Implementations: mysql_native_password, caching_sha2_password, client_ed25519.
 */
public interface AuthPlugin {
    /** Plugin name, e.g. "mysql_native_password", "caching_sha2_password", "client_ed25519" */
    String name();

    /** Generate authentication response data */
    byte[] generateAuthentication(byte[] password, byte[] seed);

    /** Verify client authentication data (server-side) */
    boolean verifyAuthentication(byte[] authData, byte[] password, byte[] seed);
}
```

- [ ] **Step 2: 创建 CapabilityFlags 枚举**

定义 MySQL 和 MariaDB 的能力标志位（CLIENT_MULTI_STATEMENTS, CLIENT_SSL, MARIADB_CLIENT_* 等）。从现有的常量类（如果存在）中提取。

- [ ] **Step 3: 创建 ServerStatus 枚举**

定义服务器状态标志位。

- [ ] **Step 4: 创建 ProtocolCodec 接口**

```java
package io.mycat.protocol.codec;

/**
 * Protocol codec for MySQL/MariaDB handshake and packet encoding/decoding.
 */
public interface ProtocolCodec {
    /** Detect protocol variant from handshake packet */
    ProtocolVariant detect(byte[] handshakePacket);

    /** Encode a logical packet to bytes */
    byte[] encode(LogicalPacket packet);

    /** Decode bytes to a logical packet */
    LogicalPacket decode(byte[] data);

    enum ProtocolVariant {
        MYSQL_57, MYSQL_80, MYSQL_84, MYSQL_9X,
        MARIADB_1011, MARIADB_11X
    }
}
```

- [ ] **Step 5: 创建单元测试骨架**

为每个接口创建测试类，包含 `@Disabled("TODO: implement")` 的测试方法。

- [ ] **Step 6: 编译验证 + Commit**

Run: `mvn compile -pl protocol -DskipTests`

```bash
git add -A
git commit -m "feat: create protocol abstraction interfaces (AuthPlugin, ProtocolCodec, CapabilityFlags)"
```

---

### Task 11: 删除 common 中的已迁移文件 + 最终验证

**Steps:**

- [ ] **Step 1: 确认所有文件已正确移动**

检查 common 中以下目录应该为空或仅剩非 MySQL 相关文件：
- `common/src/main/java/io/mycat/beans/mysql/` → 应为空
- `common/src/main/java/io/mycat/swapbuffer/` → 应为空
- `common/src/main/java/io/mycat/util/packet/` → 应为空

- [ ] **Step 2: 删除空目录**

```bash
# 删除空目录（git mv 已移动文件，但可能留下空目录）
```

- [ ] **Step 3: 全项目编译验证**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: 验证 protocol 模块依赖 DAG 正确**

Run: `mvn dependency:tree -pl protocol`
Expected: 仅依赖 common, config, netty, slf4j

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: finalize protocol module - verify clean compilation"
```

---

## Milestone 1.2: 创建 calcite-core 模块 + Calcite 1.42.0 升级

**Gate:** `mvn compile -pl calcite-core` 通过 + `mvn compile -DskipTests` 全项目通过

> ⚠️ **这是 Phase 1 中风险最高的 Milestone。** 61 个 fork 文件需要逐个对照 Calcite 1.42.0 源码 merge 差异。建议每 merge 5-10 个文件做一次编译验证。

### Task 12: 创建 calcite-core 模块骨架

**Files:**
- Create: `calcite-core/pom.xml`
- Create: `calcite-core/src/main/java/`
- Modify: `pom.xml`（根 POM `<modules>`）

**Steps:**

- [ ] **Step 1: 创建目录结构**

```bash
mkdir -p calcite-core/src/main/java/org/apache/calcite
mkdir -p calcite-core/src/test/java/org/apache/calcite
```

- [ ] **Step 2: 创建 calcite-core/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.mycat</groupId>
        <artifactId>mycat-parent</artifactId>
        <version>1.22-release</version>
    </parent>

    <artifactId>calcite-core</artifactId>
    <version>1.42.0-mycat</version>
    <name>Mycat2 Calcite Fork</name>
    <description>Fork of Apache Calcite 1.42.0 with Mycat2 customizations</description>

    <dependencies>
        <dependency>
            <groupId>org.apache.calcite</groupId>
            <artifactId>calcite-core</artifactId>
            <version>1.42.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.calcite</groupId>
            <artifactId>calcite-linq4j</artifactId>
            <version>1.42.0</version>
        </dependency>
        <dependency>
            <groupId>org.apache.calcite.avatica</groupId>
            <artifactId>avatica-core</artifactId>
            <version>1.26.0</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 在根 pom.xml 的 `<modules>` 中添加 calcite-core**

在 `<module>hbt</module>` 之前添加：
```xml
<module>calcite-core</module>
```

- [ ] **Step 4: 验证**

Run: `mvn validate -pl calcite-core`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add calcite-core/ pom.xml
git commit -m "feat: create calcite-core module skeleton (1.42.0-mycat)"
```

---

### Task 13: 列出并分析 61 个 fork 文件

**Steps:**

- [ ] **Step 1: 列出 hbt 中所有 org/apache/calcite/ 下的文件**

```bash
dir /s /b hbt\src\main\java\org\apache\calcite\
```

记录完整文件列表（预期 ~61-68 个文件）。

- [ ] **Step 2: 按子包分组**

将文件按包分组统计，例如：
- `org.apache.calcite.runtime` — SqlFunctions.java 等
- `org.apache.calcite.rex` — RexBuilder.java 等
- `org.apache.calcite.sql` — SqlLiteral.java 等
- `org.apache.calcite.sql.type` — 类型相关文件
- 其他子包

- [ ] **Step 3: 下载 Calcite 1.42.0 源码**

从 Maven Central 或 Apache 仓库下载 Calcite 1.42.0 的源码 JAR，解压到临时目录作为 merge 参考：

```bash
# 下载 Calcite 1.42.0 源码
mvn dependency:copy -Dartifact=org.apache.calcite:calcite-core:1.42.0:jar:sources -DoutputDirectory=calcite-sources
```

- [ ] **Step 4: Commit（仅记录分析结果，不修改代码）**

将分析结果写入一个临时文件，commit 后在后续任务中删除。

---

### Task 14: 逐批 merge fork 文件到 calcite-core（第一批：runtime 包）

**Files:**
- Copy+Merge: `hbt/src/main/java/org/apache/calcite/runtime/*.java` → `calcite-core/src/main/java/org/apache/calcite/runtime/`

> 关键文件：`SqlFunctions.java` (96KB) — 这是最大的 fork 文件，包含 Mycat 自定义的 SQL 运行时函数。

**Steps:**

- [ ] **Step 1: 逐个文件对比 Calcite 1.26.0 和 1.42.0 的差异**

对每个 fork 文件：
1. 读取 hbt 中的当前文件（基于 1.26.0 的 Mycat 定制版）
2. 读取 Calcite 1.42.0 源码中的同名文件
3. 识别 Mycat 的定制修改点（与 1.26.0 的差异）
4. 将定制修改点应用到 1.42.0 版本上

- [ ] **Step 2: 将 merge 后的文件放入 calcite-core**

```bash
git mv hbt/src/main/java/org/apache/calcite/runtime/SqlFunctions.java calcite-core/src/main/java/org/apache/calcite/runtime/SqlFunctions.java
```

- [ ] **Step 3: 编译验证 calcite-core**

Run: `mvn compile -pl calcite-core -DskipTests`

- [ ] **Step 4: 处理编译错误**

如果出现 Calcite 1.42.0 API 变更导致的编译错误：
- 查阅 Calcite 1.42.0 Javadoc 找到替代 API
- 在 fork 文件中适配新 API，同时保留 Mycat 的定制逻辑

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: merge calcite runtime fork files to calcite-core (1.42.0)"
```

---

### Task 15: 逐批 merge fork 文件（第二批：rex + sql 包）

**Files:**
- Copy+Merge: `hbt/src/main/java/org/apache/calcite/rex/*.java`
- Copy+Merge: `hbt/src/main/java/org/apache/calcite/sql/*.java`

> 关键文件：`RexBuilder.java` (61KB), `SqlLiteral.java` (50KB)

**Steps:**

- [ ] **Step 1-5: 同 Task 14 的模式**

逐个文件 merge → 放入 calcite-core → 编译验证 → 修复 API 变更 → commit。

重点关注的 Calcite 1.26→1.42 API 变更：
- `RelNode` 接口可能新增方法
- `RexBuilder` 签名变更（方法参数类型/数量）
- `SqlLiteral` 枚举值增减
- `HepPlanner` / `VolcanoPlanner` API 调整

```bash
git add -A
git commit -m "feat: merge calcite rex+sql fork files to calcite-core (1.42.0)"
```

---

### Task 16: 逐批 merge fork 文件（第三批：剩余包）

**Files:**
- Copy+Merge: `hbt/src/main/java/org/apache/calcite/` 下剩余所有子包

**Steps:**

- [ ] **Step 1: 确认剩余文件列表**

```bash
dir /s /b hbt\src\main\java\org\apache\calcite\
```

排除已在 Task 14 和 Task 15 中处理的文件。

- [ ] **Step 2-5: 同 Task 14 的模式**

```bash
git add -A
git commit -m "feat: merge remaining calcite fork files to calcite-core (1.42.0)"
```

---

### Task 17: 切换 hbt 依赖到 calcite-core

**Files:**
- Modify: `hbt/pom.xml`

**Steps:**

- [ ] **Step 1: 修改 hbt/pom.xml**

移除：
```xml
<dependency>
    <groupId>org.apache.calcite</groupId>
    <artifactId>calcite-core</artifactId>
    <version>1.26.0</version>
</dependency>
```

替换为：
```xml
<dependency>
    <groupId>io.mycat</groupId>
    <artifactId>calcite-core</artifactId>
    <version>1.42.0-mycat</version>
</dependency>
```

同时排除传递的 calcite-core 原版：
```xml
<dependency>
    <groupId>io.mycat</groupId>
    <artifactId>calcite-core</artifactId>
    <version>1.42.0-mycat</version>
    <exclusions>
        <exclusion>
            <groupId>org.apache.calcite</groupId>
            <artifactId>calcite-core</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

- [ ] **Step 2: 更新 linq4j 和 avatica 版本**

在 `hbt/pom.xml` 中：
```xml
<dependency>
    <groupId>org.apache.calcite</groupId>
    <artifactId>calcite-linq4j</artifactId>
    <version>1.42.0</version>
</dependency>
<dependency>
    <groupId>org.apache.calcite.avatica</groupId>
    <artifactId>avatica-core</artifactId>
    <version>1.26.0</version>
</dependency>
```

- [ ] **Step 3: 编译验证 hbt**

Run: `mvn compile -pl hbt -DskipTests`
Expected: BUILD SUCCESS

如果编译失败，分析是否是 hbt 中的非 fork 代码引用了 Calcite 1.26.0 的旧 API。逐文件修复。

- [ ] **Step 4: Commit**

```bash
git add hbt/pom.xml
git commit -m "feat: switch hbt dependency to io.mycat:calcite-core:1.42.0-mycat"
```

---

### Task 18: 删除 hbt 中的旧 fork 文件 + 废弃目录

**Files:**
- Delete: `hbt/src/main/java/org/apache/calcite/`（整个目录，已迁入 calcite-core）
- Delete: `calcite/`（废弃空壳目录）
- Delete: `linq4j/`（废弃空壳目录）

**Steps:**

- [ ] **Step 1: 确认 calcite-core 包含所有 fork 文件**

对比 `hbt/src/main/java/org/apache/calcite/` 和 `calcite-core/src/main/java/org/apache/calcite/`，确保所有文件已迁移。

- [ ] **Step 2: 删除 hbt 中的旧 fork 目录**

```bash
git rm -r hbt/src/main/java/org/apache/calcite/
```

- [ ] **Step 3: 删除废弃的 calcite/ 和 linq4j/ 目录**

```bash
git rm -r calcite/
git rm -r linq4j/
```

- [ ] **Step 4: 全项目编译验证**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: remove old calcite fork files and deprecated directories"
```

---

## Milestone 1.3: mycat2 → server 重命名（~73 文件调整）

**Gate:** `mvn compile -DskipTests` 全项目通过

### Task 19: 重命名 mycat2 模块为 server

**Files:**
- Rename: `mycat2/` → `server/`
- Modify: `server/pom.xml`
- Modify: `pom.xml`（根 POM `<modules>`）

**Steps:**

- [ ] **Step 1: 使用 git mv 重命名目录**

```bash
git mv mycat2 server
```

- [ ] **Step 2: 更新 server/pom.xml**

```xml
<!-- 旧 -->
<artifactId>mycat2</artifactId>

<!-- 新 -->
<artifactId>server</artifactId>
```

保留 `<name>` 和 `<description>` 的更新：
```xml
<name>Mycat2 Server Runtime</name>
```

- [ ] **Step 3: 更新根 pom.xml 的 modules**

```xml
<!-- 旧 -->
<module>mycat2</module>

<!-- 新 -->
<module>server</module>
```

- [ ] **Step 4: 更新其他子模块对 mycat2 的依赖引用**

```bash
grep -rn "artifactId>mycat2<" --include="pom.xml" .
```

在每个引用处将 `mycat2` 改为 `server`。

- [ ] **Step 5: 更新 shade 插件配置**

如果 `mycat2/pom.xml` 中有 maven-shade-plugin 配置，更新其中的 artifactId 引用。

- [ ] **Step 6: 编译验证**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: rename mycat2 module to server"
```

---

### Task 20: 从 common 移入 hint/ (48 文件) → server

**Files:**
- Move: `common/src/main/java/io/mycat/hint/*.java` (48 files)
- Target: `server/src/main/java/io/mycat/hint/`

**Steps:**

- [ ] **Step 1: 列出文件并扫描引用**

```bash
dir /b common\src\main\java\io\mycat\hint\*.java
grep -rn "import io.mycat.hint\." --include="*.java" .
```

- [ ] **Step 2: git mv 移动**

```bash
git mv common/src/main/java/io/mycat/hint/ server/src/main/java/io/mycat/hint/
```

- [ ] **Step 3: package 声明不变**

因为 hint 文件的包名 `io.mycat.hint` 不需要改变（server 模块使用相同的包名空间），所以**无需修改 package 声明和 import 语句**。

> 注意：这是模块迁移的便利之处——Java 包名不变，只是文件物理位置从 common JAR 移到 server JAR。只要 server 模块在 classpath 上，所有 import 仍然有效。

- [ ] **Step 4: 编译验证**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS（如果 hint 依赖了 common 中的类，server 已依赖 common，所以应该没问题）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move hint/ (48 files) from common to server"
```

---

### Task 21: 从 common 移入 ratelimiter/ (4) + sequencemodifier/ (2) + sql/ (1) → server

**Files:**
- Move: `common/src/main/java/io/mycat/ratelimiter/*.java` (4 files)
- Move: `common/src/main/java/io/mycat/sequencemodifier/*.java` (2 files)
- Move: `common/src/main/java/io/mycat/sql/*.java` (1 file)
- Target: `server/src/main/java/io/mycat/`

**Steps:**

- [ ] **Step 1: git mv 移动三个包**

```bash
git mv common/src/main/java/io/mycat/ratelimiter/ server/src/main/java/io/mycat/ratelimiter/
git mv common/src/main/java/io/mycat/sequencemodifier/ server/src/main/java/io/mycat/sequencemodifier/
git mv common/src/main/java/io/mycat/sql/ server/src/main/java/io/mycat/sql/
```

- [ ] **Step 2: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move ratelimiter + sequencemodifier + sql from common to server"
```

---

### Task 22: 从 hbt 移入 prototypeserver/ (11 文件) → server

**Files:**
- Move: `hbt/src/main/java/io/mycat/prototypeserver/*.java` (11 files)
- Target: `server/src/main/java/io/mycat/prototypeserver/`

**Steps:**

- [ ] **Step 1: 扫描引用 + git mv + 编译验证**

```bash
grep -rn "import io.mycat.prototypeserver\." --include="*.java" .
git mv hbt/src/main/java/io/mycat/prototypeserver/ server/src/main/java/io/mycat/prototypeserver/
mvn compile -DskipTests
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "refactor: move prototypeserver/ (11 files) from hbt to server"
```

---

### Task 23: 从 server 移出 Vert.x 补丁 (9 文件) → vertx 模块

**Files:**
- Move: `server/src/main/java/io/vertx/*.java` (9 files)
- Target: `vertx/src/main/java/io/vertx/`

**Steps:**

- [ ] **Step 1: 列出 server 中的 io/vertx/ 文件**

```bash
dir /s /b server\src\main\java\io\vertx\
```

这些是 Vert.x 框架的补丁文件（覆盖 Vert.x 内部类）。

- [ ] **Step 2: 确认 vertx 模块中已有的文件**

```bash
dir /s /b vertx\src\main\java\
```

检查是否有文件冲突。

- [ ] **Step 3: git mv 移动**

```bash
git mv server/src/main/java/io/vertx/ vertx/src/main/java/io/vertx/
```

如果 vertx 模块已有 `io/vertx/` 子目录，逐个移动文件避免覆盖。

- [ ] **Step 4: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move Vert.x patches (9 files) from server to vertx module"
```

---

### Task 24: 从 server 移出 Druid 补丁 (7) + sequence (2) → plug 模块

**Files:**
- Move: `server/src/main/java/com/alibaba/druid/*.java` (7 files) → `plug/src/main/java/com/alibaba/druid/`
- Move: `server/src/main/java/io/mycat/plug/sequence/*.java` (2 files) → `plug/src/main/java/io/mycat/plug/sequence/`

**Steps:**

- [ ] **Step 1: git mv 移动**

```bash
git mv server/src/main/java/com/alibaba/druid/ plug/src/main/java/com/alibaba/druid/
git mv server/src/main/java/io/mycat/plug/sequence/ plug/src/main/java/io/mycat/plug/sequence/
```

- [ ] **Step 2: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move Druid patches + sequence generators to plug module"
```

---

### Task 25: server 模块最终验证

**Steps:**

- [ ] **Step 1: 验证 server 模块文件数**

预期 ~250 个文件。

- [ ] **Step 2: 验证依赖 DAG**

Run: `mvn dependency:tree -pl server`
Expected: 依赖 common, protocol, hbt, executor, proxy, datasource, va, plug

- [ ] **Step 3: 全项目编译验证**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: finalize server module restructuring"
```

---

## Milestone 1.4: 函数迁移到 executor（148 文件迁入）

**Gate:** `mvn compile -DskipTests` 全项目通过

### Task 26: 移动 sqlfunction/datefunction/ (64 文件) → executor

**Files:**
- Move: `hbt/src/main/java/io/mycat/calcite/sqlfunction/datefunction/*.java` (64 files)
- Target: `executor/src/main/java/io/mycat/calcite/sqlfunction/datefunction/`

**Steps:**

- [ ] **Step 1: 列出文件并扫描引用**

```bash
dir /b hbt\src\main\java\io\mycat\calcite\sqlfunction\datefunction\*.java
grep -rn "import io.mycat.calcite.sqlfunction.datefunction\." --include="*.java" .
```

- [ ] **Step 2: git mv 移动**

```bash
git mv hbt/src/main/java/io/mycat/calcite/sqlfunction/datefunction/ executor/src/main/java/io/mycat/calcite/sqlfunction/datefunction/
```

- [ ] **Step 3: 编译验证 executor**

Run: `mvn compile -pl executor -DskipTests`
Expected: BUILD SUCCESS

如果编译失败，可能是因为日期函数引用了 hbt 中的 Calcite 类型。检查 executor 是否需要添加对 hbt 的依赖（已有）或 calcite-core 的依赖。

- [ ] **Step 4: 全项目编译验证**

Run: `mvn compile -DskipTests`

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move datefunction/ (64 files) from hbt to executor"
```

---

### Task 27: 移动 sqlfunction/stringfunction/ (63 文件) → executor

**Files:**
- Move: `hbt/src/main/java/io/mycat/calcite/sqlfunction/stringfunction/*.java` (63 files)
- Target: `executor/src/main/java/io/mycat/calcite/sqlfunction/stringfunction/`

**Steps:**

- [ ] **Step 1: git mv 移动**

```bash
git mv hbt/src/main/java/io/mycat/calcite/sqlfunction/stringfunction/ executor/src/main/java/io/mycat/calcite/sqlfunction/stringfunction/
```

- [ ] **Step 2: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move stringfunction/ (63 files) from hbt to executor"
```

---

### Task 28: 移动 infofunction/ (11) + mathfunction/ (5) + 其他 (5) → executor

**Files:**
- Move: `hbt/src/main/java/io/mycat/calcite/sqlfunction/infofunction/*.java` (11 files)
- Move: `hbt/src/main/java/io/mycat/calcite/sqlfunction/mathfunction/*.java` (5 files)
- Move: `hbt/src/main/java/io/mycat/calcite/sqlfunction/` 下其他文件 (5 files)
- Target: `executor/src/main/java/io/mycat/calcite/sqlfunction/`

**Steps:**

- [ ] **Step 1: git mv 移动所有剩余 sqlfunction**

```bash
git mv hbt/src/main/java/io/mycat/calcite/sqlfunction/infofunction/ executor/src/main/java/io/mycat/calcite/sqlfunction/infofunction/
git mv hbt/src/main/java/io/mycat/calcite/sqlfunction/mathfunction/ executor/src/main/java/io/mycat/calcite/sqlfunction/mathfunction/
```

对于 `sqlfunction/` 根下的其他文件：
```bash
# 列出剩余文件
dir /b hbt\src\main\java\io\mycat\calcite\sqlfunction\*.java
# 逐个移动
git mv hbt/src/main/java/io/mycat/calcite/sqlfunction/XxxFunction.java executor/src/main/java/io/mycat/calcite/sqlfunction/
```

- [ ] **Step 2: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: move remaining sqlfunction files (21) from hbt to executor"
```

---

### Task 29: 更新 hbt 对 sqlfunction 的引用

**Steps:**

- [ ] **Step 1: 检查 hbt 中是否还有对 sqlfunction 的直接引用**

```bash
grep -rn "import io.mycat.calcite.sqlfunction\." hbt/src/main/java/
```

如果有引用，因为 executor 依赖 hbt（不是反过来），这些引用需要重构：
- 如果引用的是函数注册信息，改为通过 SPI 或接口间接引用
- 如果引用的是函数常量，考虑将公共常量提取到 common

- [ ] **Step 2: 处理循环依赖风险**

如果 hbt 引用了 executor 中的 sqlfunction，而 executor 依赖 hbt，则存在循环依赖。解决方案：
- 将函数注册机制从 hbt 移到 server 或 executor
- 使用 SPI 加载机制解耦

- [ ] **Step 3: 编译验证 + Commit**

Run: `mvn compile -DskipTests`

```bash
git add -A
git commit -m "refactor: update hbt references to relocated sqlfunction classes"
```

---

### Task 30: 函数迁移最终验证 + SPI 注册检查

**Steps:**

- [ ] **Step 1: 验证 executor 模块文件数**

预期 ~156 个文件。

- [ ] **Step 2: 检查 Calcite SPI 注册**

如果 sqlfunction 中有通过 Calcite SPI 注册的类（`META-INF/services/`），确保 SPI 声明文件也随文件迁移。

```bash
dir /s /b executor\src\main\resources\META-INF\
dir /s /b hbt\src\main\resources\META-INF\
```

- [ ] **Step 3: 全项目编译验证**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: 验证依赖 DAG 无循环**

Run: `mvn dependency:tree -pl executor`
Expected: executor 依赖 common, protocol, hbt（无循环）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: finalize function migration - verify clean compilation"
```

---

## Phase 1 完成验证

- [ ] `mvn clean compile -DskipTests` BUILD SUCCESS
- [ ] protocol 模块 ~105 文件，仅依赖 common, config
- [ ] calcite-core 模块 ~70 文件，版本 1.42.0-mycat
- [ ] server 模块 ~250 文件（原 mycat2 重命名）
- [ ] executor 模块 ~156 文件（含 148 个 SQL 函数）
- [ ] hbt 模块 ~475 文件（不含 fork 和 sqlfunction）
- [ ] common 模块 ~185 文件（精简后）
- [ ] 废弃的 `calcite/` 和 `linq4j/` 目录已删除
- [ ] 依赖 DAG 无循环
- [ ] 每个 Task 都有独立的 git commit

## 后续阶段

- **Phase 2**（VT 迁移）→ `2026-09-28-phase2-virtual-threads.md`
- **Phase 3**（MariaDB 接入）→ `2026-09-28-phase3-mariadb.md`
