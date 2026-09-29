# Mycat2 Phase 0: JDK 21 编译通过 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使 Mycat2 项目在 JDK 21 下零错误编译通过，修复所有 P0/P1 运行时阻断项，完成全部依赖版本升级。

**Architecture:** 按风险从低到高的顺序逐步升级依赖和修复代码，每个原子任务完成后验证编译通过。先修构建工具 → 修 P0 编译阻断 → 修 P1 运行时阻断 → 按风险梯度升级依赖 → 基础设施收尾。

**Tech Stack:** JDK 21, Maven 3.14.1, Calcite 1.42.0, Vert.x 4.5.14, Arrow 18.1.0, Druid 1.2.28

**Spec:** `docs/superpowers/specs/2026-09-28-mycat2-module-redesign-design.md`

## Global Constraints

- JDK target: 21 (`<release>21</release>`)
- 每个任务完成后 `mvn compile -DskipTests` 必须通过
- 每个任务一个 git commit
- 禁止使用 `sun.*` 或 `com.sun.*` 内部 API（JDK 21 强封装）
- 依赖版本由根 `pom.xml` 的 `<dependencyManagement>` 集中管理
- 测试框架: JUnit 5.12.2 + Mockito 5.16.1 + AssertJ 3.27.3

## File Structure（本阶段涉及的文件）

### 修改文件

| 文件 | 职责 | 变更类型 |
|------|------|---------|
| `pom.xml` | 根 POM，依赖版本管理 | 大幅修改：compiler plugin、dependencyManagement、所有版本号 |
| `common/src/main/java/io/mycat/buffer/MycatDirectByteBufferPool.java` | 直接内存缓冲池 | 修复 sun.nio.ch.DirectBuffer 引用 |
| `common/src/main/java/io/mycat/IOExecutor.java` | IO 线程池 | 修复 Thread.stop() + Vert.x 内部 API |
| `common/src/main/java/io/mycat/NameableThreadFactory.java` | 线程工厂 | 修复 SecurityManager |
| `assistant/src/main/java/io/mycat/ui/UIMain.java` | JavaFX UI 入口 | 修复 com.sun.glass.events |
| `mycat2/src/main/java/io/mycat/exporter/HTTPServer.java` | Prometheus HTTP 端点 | 评估 com.sun.net.httpserver（JDK 21 仍可编译） |
| `mycat2/Dockerfile` | Docker 构建文件 | 更新基础镜像 |

### 各子模块 pom.xml（版本号由根 POM 统一管理后，子模块移除硬编码版本）

`common/pom.xml`, `config/pom.xml`, `hbt/pom.xml`, `executor/pom.xml`, `mycat2/pom.xml`, `proxy/pom.xml`, `datasource/pom.xml`, `va/pom.xml`, `router/pom.xml`, `replica/pom.xml`, `plug/pom.xml`, `vertx/pom.xml`, `seata/pom.xml`, `migrate/pom.xml`, `assistant/pom.xml`, `example/pom.xml`, `internalexample/pom.xml`

---

## Milestone 0.1: 构建工具升级

**Gate:** `mvn help:effective-pom` 输出 release=21

### Task 1: 升级 maven-compiler-plugin 并设置 JDK 21 target

**Files:**
- Modify: `pom.xml`（根 POM 的 `<build><plugins>` 部分）

**Steps:**

- [ ] **Step 1: 定位当前 compiler plugin 配置**

在 `pom.xml` 中搜索 `maven-compiler-plugin`，记录当前版本和 source/target 设置。

预期找到类似：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>2.3.2</version>
    <configuration>
        <source>1.8</source>
        <target>1.8</target>
    </configuration>
</plugin>
```

- [ ] **Step 2: 替换为 JDK 21 配置**

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.14.1</version>
    <configuration>
        <release>21</release>
        <encoding>UTF-8</encoding>
    </configuration>
</plugin>
```

注意：`<release>21</release>` 替代了 `<source>` + `<target>` 的组合，同时启用 JDK 模块系统的 API 检查。

- [ ] **Step 3: 验证 effective-pom**

Run: `mvn help:effective-pom -pl config | findstr release`
Expected: 输出包含 `<release>21</release>`

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build: upgrade maven-compiler-plugin to 3.14.1 with JDK 21 target"
```

---

### Task 2: 添加 dependencyManagement 集中管理版本号

**Files:**
- Modify: `pom.xml`（根 POM，添加 `<dependencyManagement>` 部分）

**Steps:**

- [ ] **Step 1: 扫描所有子模块中的硬编码版本号**

Run: `findstr /s /r "<version>1\." *.xml` (在 Mycat2 根目录下)

记录所有在子模块 pom.xml 中硬编码的版本号。重点关注以下模块中重复出现的版本：
- `hbt/pom.xml`（大量重复声明：guava 双版本、commons-lang3 双版本、janino 双版本等）
- `executor/pom.xml`
- `mycat2/pom.xml`

- [ ] **Step 2: 在根 pom.xml 中添加 `<dependencyManagement>`**

在 `<project>` 标签内添加（版本号来自 spec 的"完整版本号清单"）：

```xml
<properties>
    <!-- JDK -->
    <java.version>21</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <!-- 编译阻断项 -->
    <lombok.version>1.18.42</lombok.version>
    <janino.version>3.1.12</janino.version>
    <jakarta.annotation.version>2.1.1</jakarta.annotation.version>
    <arrow.version>18.1.0</arrow.version>

    <!-- 安全关键项 -->
    <jackson.version>2.18.4</jackson.version>
    <logback.version>1.5.18</logback.version>
    <netty.version>4.1.119.Final</netty.version>
    <guava.version>33.4.8-jre</guava.version>

    <!-- 核心框架 -->
    <calcite.version>1.42.0</calcite.version>
    <avatica.version>1.26.0</avatica.version>
    <druid.version>1.2.28</druid.version>
    <vertx.version>4.5.14</vertx.version>
    <rxjava.version>3.1.10</rxjava.version>

    <!-- 功能升级项 -->
    <mysql.connector.version>9.3.0</mysql.connector.version>
    <mariadb.connector.version>3.5.3</mariadb.connector.version>
    <kafka.version>4.0.0</kafka.version>
    <clickhouse.version>0.7.3</clickhouse.version>
    <oshi.version>6.8.2</oshi.version>
    <ehcache.version>3.10.8</ehcache.version>

    <!-- 测试框架 -->
    <junit.version>5.12.2</junit.version>
    <mockito.version>5.16.1</mockito.version>
    <assertj.version>3.27.3</assertj.version>
    <testcontainers.version>1.21.0</testcontainers.version>

    <!-- 内部模块版本 -->
    <mycat.version>1.22-release</mycat.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- 内部模块 -->
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>config</artifactId>
            <version>${mycat.version}</version>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>common</artifactId>
            <version>${mycat.version}</version>
        </dependency>
        <!-- ... 其余内部模块同理 ... -->

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-core</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
            <version>${jackson.version}</version>
        </dependency>

        <!-- Guava -->
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
            <version>${guava.version}</version>
        </dependency>

        <!-- Netty -->
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-bom</artifactId>
            <version>${netty.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>

        <!-- Vert.x -->
        <dependency>
            <groupId>io.vertx</groupId>
            <artifactId>vertx-core</artifactId>
            <version>${vertx.version}</version>
        </dependency>
        <dependency>
            <groupId>io.vertx</groupId>
            <artifactId>vertx-sql-client</artifactId>
            <version>${vertx.version}</version>
        </dependency>
        <dependency>
            <groupId>io.vertx</groupId>
            <artifactId>vertx-mysql-client</artifactId>
            <version>${vertx.version}</version>
        </dependency>

        <!-- Arrow -->
        <dependency>
            <groupId>org.apache.arrow</groupId>
            <artifactId>arrow-vector</artifactId>
            <version>${arrow.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.arrow</groupId>
            <artifactId>arrow-memory-netty</artifactId>
            <version>${arrow.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.arrow</groupId>
            <artifactId>arrow-jdbc</artifactId>
            <version>${arrow.version}</version>
        </dependency>

        <!-- Calcite -->
        <dependency>
            <groupId>org.apache.calcite</groupId>
            <artifactId>calcite-core</artifactId>
            <version>${calcite.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.calcite</groupId>
            <artifactId>calcite-linq4j</artifactId>
            <version>${calcite.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.calcite.avatica</groupId>
            <artifactId>avatica-core</artifactId>
            <version>${avatica.version}</version>
        </dependency>

        <!-- Druid -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>druid</artifactId>
            <version>${druid.version}</version>
        </dependency>

        <!-- RxJava -->
        <dependency>
            <groupId>io.reactivex.rxjava3</groupId>
            <artifactId>rxjava</artifactId>
            <version>${rxjava.version}</version>
        </dependency>

        <!-- MySQL Connector (artifact name changed!) -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>${mysql.connector.version}</version>
        </dependency>

        <!-- MariaDB Connector (NEW) -->
        <dependency>
            <groupId>org.mariadb.jdbc</groupId>
            <artifactId>mariadb-java-client</artifactId>
            <version>${mariadb.connector.version}</version>
        </dependency>

        <!-- Jakarta Annotation (NEW) -->
        <dependency>
            <groupId>jakarta.annotation</groupId>
            <artifactId>jakarta.annotation-api</artifactId>
            <version>${jakarta.annotation.version}</version>
        </dependency>

        <!-- Logback -->
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>${logback.version}</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-core</artifactId>
            <version>${logback.version}</version>
        </dependency>

        <!-- Janino -->
        <dependency>
            <groupId>org.codehaus.janino</groupId>
            <artifactId>janino</artifactId>
            <version>${janino.version}</version>
        </dependency>
        <dependency>
            <groupId>org.codehaus.janino</groupId>
            <artifactId>commons-compiler</artifactId>
            <version>${janino.version}</version>
        </dependency>

        <!-- Test frameworks -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>${assertj.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

- [ ] **Step 3: 更新 maven-surefire-plugin 到 3.5.3 支持 JUnit 5**

在 `<build><plugins>` 中添加：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.5.3</version>
</plugin>
```

- [ ] **Step 4: 验证 POM 格式正确**

Run: `mvn validate`
Expected: BUILD SUCCESS（不编译，仅验证 POM 语法）

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "build: add dependencyManagement with all target versions for JDK 21"
```

---

### Task 3: 更新 lombok 版本到 1.18.42

**Files:**
- Modify: `pom.xml`（已在 Task 2 的 dependencyManagement 中声明 lombok 1.18.42）

**Steps:**

- [ ] **Step 1: 确认 lombok 版本已在 dependencyManagement 中声明**

Run: `findstr /i "lombok" pom.xml`
Expected: 输出包含 `<lombok.version>1.18.42</lombok.version>`

- [ ] **Step 2: 验证 config 模块编译**

Run: `mvn compile -pl config -DskipTests`
Expected: BUILD SUCCESS（config 是最简单的模块，仅 POJO + lombok）

如果编译失败，检查是否有子模块 pom.xml 中硬编码了旧版 lombok，移除之。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "build: upgrade lombok to 1.18.42 for JDK 21 compatibility"
```

---

## Milestone 0.2: P0 编译阻断修复

**Gate:** `mvn compile -pl common,assistant -DskipTests` 通过

### Task 4: 修复 MycatDirectByteBufferPool.java 的 sun.nio.ch.DirectBuffer 引用

**Files:**
- Modify: `common/src/main/java/io/mycat/buffer/MycatDirectByteBufferPool.java`

**Context:** 该文件 L19 有 `import sun.nio.ch.DirectBuffer`，L85 和 L87 使用了 `DirectBuffer` 的 `attachment()` 方法获取底层 ByteBuffer。JDK 21 的模块强封装会阻止访问。

**Steps:**

- [ ] **Step 1: 阅读当前实现，理解 DirectBuffer 的使用方式**

读取 `common/src/main/java/io/mycat/buffer/MycatDirectByteBufferPool.java`，重点关注：
- L19: `import sun.nio.ch.DirectBuffer`
- L85: `DirectBuffer thisNavBuf = (DirectBuffer) theBuf;`
- L87: `DirectBuffer parentBuf = (DirectBuffer) thisNavBuf.attachment();`

理解这段代码的目的：获取 DirectByteBuffer 的 attachment（父 buffer），用于内存管理。

- [ ] **Step 2: 在 pom.xml 中添加 --add-exports 编译参数**

这是最小侵入的修复方式，保持现有代码逻辑不变：

在根 `pom.xml` 的 `maven-compiler-plugin` 配置中添加：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.14.1</version>
    <configuration>
        <release>21</release>
        <encoding>UTF-8</encoding>
        <compilerArgs>
            <arg>--add-exports</arg>
            <arg>java.base/sun.nio.ch=ALL-UNNAMED</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

- [ ] **Step 3: 验证 common 模块编译**

Run: `mvn compile -pl common -DskipTests`
Expected: BUILD SUCCESS，无 `sun.nio.ch` 相关编译错误

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "fix: add --add-exports for sun.nio.ch.DirectBuffer JDK 21 compatibility"
```

---

### Task 5: 修复 UIMain.java 的 com.sun.glass.events 引用

**Files:**
- Modify: `assistant/src/main/java/io/mycat/ui/UIMain.java`

**Context:** L3 有 `import com.sun.glass.events.WindowEvent`，这是 JavaFX 内部 API。JDK 21 模块系统拒绝访问。

**Steps:**

- [ ] **Step 1: 阅读 UIMain.java，理解 WindowEvent 的使用方式**

读取 `assistant/src/main/java/io/mycat/ui/UIMain.java`，找到所有 `WindowEvent` 的使用位置。

- [ ] **Step 2: 替换为 JavaFX 公开 API**

将：
```java
import com.sun.glass.events.WindowEvent;
```
替换为：
```java
import javafx.stage.WindowEvent;
```

注意：JavaFX 的 `javafx.stage.WindowEvent` 是公开 API，事件常量名可能不同（如 `WINDOW_CLOSE_REQUEST` 代替 `CLOSE`），需要根据实际使用调整。

- [ ] **Step 3: 验证 assistant 模块编译**

Run: `mvn compile -pl assistant -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add assistant/src/main/java/io/mycat/ui/UIMain.java
git commit -m "fix: replace com.sun.glass.events with javafx.stage.WindowEvent"
```

---

## Milestone 0.3: P1 运行时阻断修复

**Gate:** 代码审查通过 + `mvn compile -pl common -DskipTests` 通过

### Task 6: 修复 IOExecutor.java 的 Thread.stop() 调用

**Files:**
- Modify: `common/src/main/java/io/mycat/IOExecutor.java`

**Context:** L52 有 `thread.stop()`（JDK 21 中抛出 `UnsupportedOperationException`），L55 有 `catch (java.lang.ThreadDeath threadDeath)`（deprecated for removal）。

**Steps:**

- [ ] **Step 1: 阅读 IOExecutor.java 的完整实现**

读取文件，理解 `thread.stop()` 的使用上下文——通常在强制关闭线程池时调用。

- [ ] **Step 2: 替换 Thread.stop() 为 interrupt() + 协作取消**

将：
```java
thread.stop();
```
替换为：
```java
thread.interrupt();
```

并移除 `catch (java.lang.ThreadDeath threadDeath)` 块（或替换为通用的异常处理）。

如果该代码用于强制终止所有线程，改为：
```java
executorService.shutdownNow(); // 发送 interrupt 给所有活动线程
```

- [ ] **Step 3: 验证编译**

Run: `mvn compile -pl common -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/io/mycat/IOExecutor.java
git commit -m "fix: replace Thread.stop() with interrupt() for JDK 21"
```

---

### Task 7: 修复 NameableThreadFactory.java 的 SecurityManager 引用

**Files:**
- Modify: `common/src/main/java/io/mycat/NameableThreadFactory.java`

**Context:** L15-16 使用 `System.getSecurityManager()` 获取线程组。JDK 21 中 SecurityManager 已移除。

**Steps:**

- [ ] **Step 1: 阅读 NameableThreadFactory.java**

读取文件，找到 SecurityManager 使用位置。典型代码：
```java
SecurityManager s = System.getSecurityManager();
ThreadGroup group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
```

- [ ] **Step 2: 简化为直接使用当前线程组**

将上述代码替换为：
```java
ThreadGroup group = Thread.currentThread().getThreadGroup();
```

移除 `import java.lang.SecurityManager` 相关导入（如有）。

- [ ] **Step 3: 验证编译**

Run: `mvn compile -pl common -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/io/mycat/NameableThreadFactory.java
git commit -m "fix: remove SecurityManager usage for JDK 21"
```

---

## Milestone 0.4: 核心依赖版本升级

**Gate:** 每次升级后 `mvn compile -DskipTests` 全项目通过。按风险从低到高依次执行。

### Task 8: 升级 Jackson 2.10 → 2.18.4

**Files:**
- Modify: 各子模块 pom.xml（移除硬编码 jackson 版本号，由 dependencyManagement 接管）

**Steps:**

- [ ] **Step 1: 扫描 jackson 版本硬编码**

Run: `findstr /s /r "jackson" */pom.xml`
Expected: 找到所有硬编码的 jackson 版本号（预期为 2.10.0 或类似）

- [ ] **Step 2: 移除子模块中的硬编码版本**

在每个出现 jackson 版本硬编码的子模块 pom.xml 中，移除 `<version>` 标签（由根 POM dependencyManagement 统一管理）。

- [ ] **Step 3: 验证编译**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS（Jackson 2.10→2.18 API 兼容性好，仅 1 个文件使用）

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "deps: upgrade Jackson to 2.18.4"
```

---

### Task 9: 升级 Guava 31.0.1 → 33.4.8-jre

**Files:**
- Modify: 各子模块 pom.xml（移除硬编码 guava 版本）
- Modify: `executor/src/main/java/io/ordinate/engine/physicalplan/PhysicalSortProperty.java`（`UnsignedLong` → `Long`）

**Steps:**

- [ ] **Step 1: 移除子模块中的硬编码 guava 版本**

- [ ] **Step 2: 修复 UnsignedLong 废弃问题**

在 `PhysicalSortProperty.java` 中，将 `UnsignedLong` 替换为 `Long`（如果存在使用）。

- [ ] **Step 3: 验证编译**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "deps: upgrade Guava to 33.4.8-jre"
```

---

### Task 10: mysql-connector-java → mysql-connector-j (9.3.0)

**Files:**
- Modify: `common/pom.xml`, `config/pom.xml`, `datasource/pom.xml`, `example/pom.xml`, `va/pom.xml`

**Steps:**

- [ ] **Step 1: 替换 artifactId 和 groupId**

在所有出现 `mysql:mysql-connector-java` 的 pom.xml 中，替换为：
```xml
<groupId>com.mysql</groupId>
<artifactId>mysql-connector-j</artifactId>
```
（版本由 dependencyManagement 统一管理，移除 `<version>` 标签）

注意：Java 代码中的 `com.mysql.cj.*` import **不需要改变**（新 artifact 保持相同包名）。

- [ ] **Step 2: 验证编译**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "deps: rename mysql-connector-java to mysql-connector-j 9.3.0"
```

---

### Task 11: 升级 Logback 1.2.3 → 1.5.18 + SLF4J 2.x

**Files:**
- Modify: 各子模块 pom.xml

**Steps:**

- [ ] **Step 1: 移除硬编码 logback 版本，由 dependencyManagement 接管**

- [ ] **Step 2: 检查 SLF4J API 使用**

Logback 1.5.x 依赖 SLF4J 2.x。确认 `slf4j-api` 版本与 logback 配套（通常 logback 1.5.18 自带 SLF4J 2.0.x 依赖）。

- [ ] **Step 3: 验证编译**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "deps: upgrade Logback to 1.5.18 with SLF4J 2.x"
```

---

### Task 12: 升级 Netty 4.1.74 → 4.1.119

**Files:**
- Modify: 各子模块 pom.xml

**Steps:**

- [ ] **Step 1: 移除硬编码 netty 版本，由 dependencyManagement 的 netty-bom 接管**

- [ ] **Step 2: 验证编译**

Run: `mvn compile -pl proxy -DskipTests`
Expected: BUILD SUCCESS（proxy 模块是 NIO reactor 的核心使用者）

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "deps: upgrade Netty to 4.1.119.Final"
```

---

### Task 13: 升级 Arrow 5.0.0 → 18.1.0

**Files:**
- Modify: `common/pom.xml`, `executor/pom.xml`, `hbt/pom.xml`
- Modify: ~38 个 Java 文件（Arrow API 变更）

> ⚠️ 这是高风险任务。Arrow 12+ 变更了 `VectorSchemaRoot` 的部分 API。需要逐文件检查和修复。

**Steps:**

- [ ] **Step 1: 更新 pom.xml 中的 Arrow 版本**

移除硬编码版本，由 dependencyManagement 接管。

- [ ] **Step 2: 尝试编译，记录所有 Arrow 相关编译错误**

Run: `mvn compile -DskipTests 2>&1 | findstr "ERROR"`
Expected: 多个编译错误，记录到临时文件

- [ ] **Step 3: 逐文件修复 Arrow API 变更**

重点关注的 API 变更：
- `VectorSchemaRoot` 的 `getFieldVectors()` 返回类型可能变化
- `BufferAllocator` 的方法签名可能调整
- `FieldType` 构造器参数可能变化

对每个编译错误，查阅 Arrow 18.x 的 Javadoc 找到替代 API。

- [ ] **Step 4: 验证编译**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "deps: upgrade Arrow to 18.1.0 with API migration"
```

---

### Task 14: 升级 Janino 3.0/3.1 → 3.1.12

**Files:**
- Modify: `hbt/pom.xml`, `executor/pom.xml`

**Steps:**

- [ ] **Step 1: 移除硬编码 janino 版本（hbt 中有双版本声明 3.0.11 和 3.1.0，全部移除）**

- [ ] **Step 2: 验证编译**

Run: `mvn compile -pl executor -DskipTests`
Expected: BUILD SUCCESS（executor 使用 Janino 做动态代码生成）

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "deps: upgrade Janino to 3.1.12"
```

---

### Task 15: javax → jakarta 迁移

**Files:**
- Modify: ~12 个生产 Java 文件 + ~40 个测试文件
- Modify: 相关 pom.xml（添加 jakarta.annotation-api 依赖）

> ⚠️ **calcite-core 模块豁免：** calcite-core 是 Apache Calcite 的完整源码 fork，其中的 `javax.annotation` 引用来自 Calcite 上游代码，应保持与上游一致，不迁移到 jakarta。仅迁移 `hbt`、`common`、`va` 等 Mycat 自有模块中的 `javax.annotation`。

**Steps:**

- [ ] **Step 1: 添加 jakarta.annotation-api 依赖到需要 javax.annotation 的模块**

在 `hbt/pom.xml` 中添加：
```xml
<dependency>
    <groupId>jakarta.annotation</groupId>
    <artifactId>jakarta.annotation-api</artifactId>
</dependency>
```

- [ ] **Step 2: 全量替换 javax.annotation → jakarta.annotation**

在生产代码中：
```
javax.annotation.Nonnull  → jakarta.annotation.Nonnull
javax.annotation.Nullable → jakarta.annotation.Nullable
```

在测试代码中（如果有 javax.annotation.concurrent.NotThreadSafe），保留原有 `jsr305` 依赖或替换为等效注解。

- [ ] **Step 3: 替换 javax.validation → jakarta.validation**

在 `assistant/` 和 `config/` 模块中：
```
javax.validation.* → jakarta.validation.*
```

- [ ] **Step 4: 验证编译**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "deps: migrate javax.annotation/validation to jakarta namespace"
```

---

### Task 16: 升级 Vert.x 4.2.5 → 4.5.14 + 内部 API 迁移

**Files:**
- Modify: 各子模块 pom.xml
- Modify: ~80+ Java 文件（内部 API 替换）

> ⚠️ **这是 Phase 0 中最大、最复杂的任务。** 建议按子任务 16a-16g 逐步执行，每个子任务完成后编译验证。

**Steps:**

- [ ] **Step 1: 更新 Vert.x 版本**

移除子模块中硬编码的 vertx 版本，由 dependencyManagement 接管。

- [ ] **Step 2: 尝试编译，记录所有编译错误**

Run: `mvn compile -DskipTests 2>&1 > vertx-errors.txt`

- [ ] **Step 3 (Task 16a): 替换 PromiseInternal → Promise**

受影响文件（10 个）：
- `common/IOExecutor.java`
- `proxy/MySQLDatasourcePool.java`
- `proxy/MySQLPacketExchanger.java`
- `proxy/MycatSessionWriteHandler.java`
- `proxy/MySQLClientSession.java`
- `proxy/MySQLProxyServerSession.java`
- `proxy/MySQLServerSession.java`
- `proxy/MycatSession.java`
- `mycat2/MySQLTaskUtil.java`
- `hbt/AsyncMycatDataContextImpl.java`

对每个文件：
1. 将 `import io.vertx.core.impl.future.PromiseInternal` 替换为 `import io.vertx.core.Promise`
2. 将 `PromiseInternal<T>` 类型声明替换为 `Promise<T>`
3. 调整方法调用（`PromiseInternal` 可能有 `internalTryComplete` 等内部方法，改为 `tryComplete`）

验证: `mvn compile -DskipTests`

- [ ] **Step 4 (Task 16b): 替换 PromiseImpl → Promise**

受影响文件（1 个）：`proxy/MySQLProxyServerSession.java`

- [ ] **Step 5 (Task 16c): 替换 impl.logging → SLF4J**

受影响文件（7 个）：
- `mycat2/EmbeddedZKServer.java`
- `mycat2/AbstractMySQLManagerImpl.java`
- `va/ImmutableCoordinatorLog.java`
- `va/ImmutableParticipantLog.java`
- `va/AbstractXaSqlConnection.java`
- `va/OnePhaseXaSqlConnection.java`
- `va/XaLogImpl.java`

对每个文件：
```java
// 旧
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
private static final Logger LOG = LoggerFactory.getLogger(Xxx.class);

// 新
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
private static final Logger LOG = LoggerFactory.getLogger(Xxx.class);
```

- [ ] **Step 6 (Task 16d): 替换 impl.ConcurrentHashSet → JDK**

受影响文件（1 个）：`va/XaLogImpl.java`
```java
// 旧
import io.vertx.core.impl.ConcurrentHashSet;
Set<X> set = new ConcurrentHashSet<>();

// 新
import java.util.concurrent.ConcurrentHashMap;
Set<X> set = ConcurrentHashMap.newKeySet();
```

- [ ] **Step 7 (Task 16e): 适配 spi.metrics.* API**

受影响文件（1 个）：`common/IOExecutor.java`
评估 Vert.x 4.5 的 SPI metrics 接口变化，必要时移除或适配。

- [ ] **Step 8 (Task 16f): 评估 mysqlclient.impl.* codec 替代方案**

受影响文件（8 个）：
- `vertx/` 模块 4 个文件
- `hbt/BaseRowObservable.java`
- `hbt/EmitterObjectsCollector.java`
- `common/beans/mycat/MycatDataType.java`（原 `common/beans/mysql/` 下的文件，Phase 0 时还在 common）
- `common/MycatMySQLRowMetaData.java`

检查 Vert.x 4.5 是否提供了公开的 codec API 替代。如果没有，可能需要在 `vertx/` 模块的补丁中适配新的内部 API。

- [ ] **Step 9 (Task 16g): 替换 VertxImpl 内部类型**

受影响文件（1 个）：`common/IOExecutor.java`
将 `io.vertx.core.impl.VertxImpl` 替换为公开 API。

- [ ] **Step 10: 全量编译验证**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "deps: upgrade Vert.x to 4.5.14 with internal API migration"
```

---

## Milestone 0.5: 基础设施升级

**Gate:** Docker build 成功 + 全量编译通过

### Task 17: 更新 Dockerfile 基础镜像

**Files:**
- Modify: `mycat2/Dockerfile`

**Steps:**

- [ ] **Step 1: 替换基础镜像**

将 `openjdk-8u41-ri` 替换为 JDK 21 基础镜像，例如：
```dockerfile
FROM bellsoft/liberica-openjdk-debian:21
```

- [ ] **Step 2: Commit**

```bash
git add mycat2/Dockerfile
git commit -m "infra: update Dockerfile base image to JDK 21"
```

---

### Task 18: 全量编译最终验证

**Steps:**

- [ ] **Step 1: 全量编译**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS，零错误

- [ ] **Step 2: 检查无内部 API 引用残留**

Run:
```bash
findstr /s /r "import sun\." common\src\main\java\*.java hbt\src\main\java\*.java executor\src\main\java\*.java
findstr /s /r "import com\.sun\." common\src\main\java\*.java mycat2\src\main\java\*.java
```

Expected: 除 `--add-exports` 已授权的 `sun.nio.ch.DirectBuffer` 外，无其他内部 API 引用。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: final JDK 21 compilation verification"
```

---

## 完成标准

Phase 0 完成后，以下所有条件必须满足：

- [x] `mvn clean compile -DskipTests` 在 JDK 21 下 BUILD SUCCESS
- [x] 无 `sun.*` 或 `com.sun.*` 未授权内部 API 引用
- [x] 所有依赖版本升级到目标矩阵
- [x] Dockerfile 使用 JDK 21 基础镜像
- [x] 每个 Task 都有独立的 git commit

## 后续阶段预览

- **Phase 1**（模块重构）：创建 protocol、calcite-core 模块，重命名 mycat2→server，迁移 ~408 文件 → `2026-09-28-phase1-module-restructure.md`
- **Phase 2**（VT 迁移）：55 文件 synchronized→ReentrantLock → `2026-09-28-phase2-virtual-threads.md`
- **Phase 3**（MariaDB 接入）：eddsa fork + MariaDB 协议兼容 → `2026-09-28-phase3-mariadb.md`
