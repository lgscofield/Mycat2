# ClickHouse 升级与构建插件集中化设计

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 升级 ClickHouse JDBC 驱动到新版 `com.clickhouse`，并通过 `<pluginManagement>` 集中化所有构建插件版本管理。

**Architecture:** 在根 POM 中引入 `<pluginManagement>` 统一管理所有插件版本和基础配置，子模块不再重复声明版本号。同时将 ClickHouse JDBC 驱动从已废弃的 `ru.yandex.clickhouse` 迁移到官方新坐标 `com.clickhouse`。

**Tech Stack:** Maven 3.9+, JDK 21, ClickHouse JDBC 0.7.2

## Global Constraints

- 项目使用 JDK 21 编译，`<source>`, `<target>`, `<release>` 均为 21
- calcite-core 为 fork 隔离模块，其 POM 独立管理，但插件版本仍从父 POM 继承
- migrate 和 testsuite 模块不在主构建中，本次不修改
- maven-replacer-plugin（server 专用）保持 1.5.3 不变
- 不改变任何插件的功能行为，仅升级版本和集中管理

## 插件版本清单

| 插件 | 旧版本 | 新版本 | 位置 |
|---|---|---|---|
| maven-compiler-plugin | 3.14.1 | 3.14.1（不变） | 根 POM、calcite-core、hbt |
| spotbugs-maven-plugin | 3.1.11 + spotbugs 4.0.0-beta2 | **4.9.3.0**（移除 beta 覆盖） | 根 POM |
| maven-install-plugin | 2.5.2 | **3.1.4** | 根 POM |
| maven-surefire-plugin | 3.5.3 | 3.5.3（不变，修复 forkMode→forkCount） | 根 POM |
| maven-assembly-plugin | 未指定 | **3.7.1** | assistant |
| maven-shade-plugin | 3.1.0 | **3.6.0** | server |
| buildnumber-maven-plugin | 1.4 | **3.2.1** | server |

---

### Task 1: ClickHouse JDBC 升级

**Files:**
- Modify: `pom.xml` (根 POM `<clickhouse.version>` 属性 + DM groupId)
- Modify: `datasource/pom.xml` (依赖 groupId)
- Modify: `server/pom.xml` (依赖 groupId)

**变更详情:**

1. 根 POM `<properties>` 中 `clickhouse.version` 从 `0.3.2` 改为 `0.7.2`
2. 根 POM `<dependencyManagement>` 中 clickhouse-jdbc 的 `<groupId>` 从 `ru.yandex.clickhouse` 改为 `com.clickhouse`
3. `datasource/pom.xml` 中 clickhouse-jdbc 的 `<groupId>` 从 `ru.yandex.clickhouse` 改为 `com.clickhouse`
4. `server/pom.xml` 中 clickhouse-jdbc 的 `<groupId>` 从 `ru.yandex.clickhouse` 改为 `com.clickhouse`

**验证:** `mvn clean compile -DskipTests` 全量编译通过

---

### Task 2: 根 POM `<pluginManagement>` 集中化 + 死代码清理

**Files:**
- Modify: `pom.xml` (根 POM `<build>` 段)

**变更详情:**

1. **引入 `<pluginManagement>`**：将以下插件的版本和基础配置移入 `<pluginManagement><plugins>`：
   - `maven-compiler-plugin:3.14.1`（source=21, target=21, encoding=UTF-8, --add-exports）
   - `spotbugs-maven-plugin:4.9.3.0`（移除内部 `<dependencies>` 中的 spotbugs beta 覆盖）
   - `maven-install-plugin:3.1.4`
   - `maven-surefire-plugin:3.5.3`（`<forkMode>once</forkMode>` → `<forkCount>1</forkCount><reuseForks>true</reuseForks>`）
   - `maven-assembly-plugin:3.7.1`
   - `maven-shade-plugin:3.6.0`
   - `buildnumber-maven-plugin:3.2.1`

2. **简化 `<plugins>` 段**：根 POM 自身需要的插件（compiler、surefire）保留在 `<plugins>` 中但不写 version（从 pluginManagement 继承）

3. **清理死代码**：删除所有注释掉的：
   - `<resources>` SVN 配置段
   - `maven-source-plugin` 配置段
   - `maven-jar-plugin` 配置段

4. **保留 `<testResources>`**：src/test/resources 配置保留

**验证:** `mvn clean compile -DskipTests` 全量编译通过

---

### Task 3: 子模块 build 段简化

**Files:**
- Modify: `calcite-core/pom.xml` (移除冗余 compiler 配置)
- Modify: `server/pom.xml` (移除硬编码版本 + 清理死代码)
- Modify: `assistant/pom.xml` (无需改动 — assembly 自动继承版本)

**calcite-core/pom.xml 变更:**
- 移除 `<source>`, `<target>`, `<encoding>`, `<compilerArgs>` 配置（与根 POM pluginManagement 完全一致，冗余）
- 保留 `annotationProcessorPaths`（immutables，模块独有配置）

**server/pom.xml 变更:**
- `maven-shade-plugin` 移除 `<version>3.1.0</version>`（从 pluginManagement 继承 3.6.0）
- `buildnumber-maven-plugin` 移除 `<version>1.4</version>`（从 pluginManagement 继承 3.2.1）
- 删除注释掉的 `appassembler-maven-plugin` 段（约 100 行）
- 删除注释掉的 `maven-antrun-plugin` 段（约 40 行）
- `maven-replacer-plugin` 保持不变（server 专用，1.5.3）

**验证:** `mvn clean compile -DskipTests` 全量编译通过 + `mvn package -DskipTests` server 模块打包验证
