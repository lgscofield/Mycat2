# MariaDB 后端 ed25519（I-AU-6 / M6）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让启用 `client_ed25519` 认证的 MariaDB 后端能被 Mycat 以 JDBC 腿建连、认证并跑通基本 CRUD，直指南极星 M6（MariaDB 无缝替代）。

**Architecture:** 方案 A+（spec §4 选型 A 加护）：mariadb-java-client 转 datasource 模块 compile 依赖进 fat-jar；`DruidDatasourceProvider` 对 `mariadb` dbType 显式 `setDriverClassName`（不赌 shade 产物里 ServiceLoader 描述文件的存活）；端到端用真 MariaDB 容器 + ed25519 用户做金标准对拍。

**Tech Stack:** mariadb-java-client 3.5.3（root pom 已管理）、Druid、testcontainers-mariadb、JUnit 5、Docker。

**Spec:** `docs/superpowers/specs/2026-10-23-mariadb-ed25519-backend-m6-design.md`（用户 2026-10-24 评审通过）

## Global Constraints

- 每条 mvn 命令必须内锁 JDK21：`$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; $env:Path="$env:JAVA_HOME\bin;$env:Path"`（shell 会话间会漂回 JDK25 致 lombok 症状崩）。
- 单模块测试必须带 `-am`；离线验证优先 `-o`。
- 提交严禁 `git add -A`：只加计划点名文件，`.qoder/` 面绝不入库；提交消息含 `[I-AU-6]`。
- MariaDB 驱动版本一律由根 pom `dependencyManagement`（`${mariadb.connector.version}`=3.5.3）提供，子模块不写死版本。
- 3.5.3 jar 已在本地仓库（server 模块 test scope 昨夜用过），离线 `-o` 可跑；若 `-o` 报缺工件则去掉 `-o` 联网拉一次并记录。
- 后端测试床必须是 Docker 容器（本机 3306/3307 保留给 m1-mysql-a/b）；新 mariadb 容器用独立端口（计划用 **3308**）。
- 诚实门槛：Task 4 活库端到端未跑通前，DEBT-QUEUE 的 I-AU-6 不得标 ✅。

## 前置条件（Task 0，环境类，验证不施工）

- [ ] **Task 0: Docker Desktop 恢复**

Run: `docker info`
Expected: 正常输出 Server 信息。若管道不可用（本计划起草时实测 `dockerDesktopLinuxEngine` 管道不存在），按既有恢复流程拉起 Docker Desktop 并确认 `m1-mysql-a/b` 等既有容器状态后再继续。**此步无代码产出，不满足则 Task 4 BLOCKED（Task 1-3 为离线工程改造，不依赖 Docker，可先行）。**

## Key file:line facts（勘察坐实，执行者不必重查）

- `config/.../DatasourceConfig.java:58-59` `getDbTypeRaw` 已认 `jdbc:mariadb:`→"mariadb"；`:39` type 默认 JDBC。
- `datasource/.../DruidDatasourceProvider.java:46` `url=config.getUrl()`；`:57` `setUrl(url)`（**无显式 driverClassName**）；`:84` 非 mysql dbType 走 jOOQ 包装；`:110-111` `case mariadb:` 仅 SQLDialect。
- `datasource/pom.xml:46-50` mariadb 依赖整段被注释且滞留过期 `2.5.2`。
- `pom.xml:67` `mariadb.connector.version=3.5.3`；`:472-476` dependencyManagement 条目；`:578-579` testcontainers mariadb。
- `server/pom.xml:39-42` mariadb 依赖 `scope=test`；`:259-295` fat-jar=maven-shade-plugin，filters 只排 `*.SF/*.DSA/*.RSA`。
- `server/.../MycatMySQLManagerImpl.java:57-75`：`FORCE_NATIVE_DATASOURCE`（`-Dserver=native` 才开）下 JDBC→NATIVE_JDBC，native 池首连失败 `recover` **静默**回退 JDBC 池。
- `server/.../sqlhandler/dql/HintHandler.java:645-655`：既有能力允许按 datasource 显式 `setUrl` 覆写（e2e 注 `jdbc:mariadb://` 的依据）。

---

### Task 1: mariadb-java-client 转 compile 进 classpath

**Files:**
- Modify: `datasource/pom.xml`（:45-50 注释块）
- Test: `datasource/src/test/java/io/mycat/datasource/MariadbDriverOnClasspathTest.java`（新建）

**Interfaces:**
- Produces: datasource 模块编译/测试 classpath 内可加载 `org.mariadb.jdbc.Driver`。

- [ ] **Step 1: 写失败测试**

```java
package io.mycat.datasource;
// JUnit5：断言 Class.forName("org.mariadb.jdbc.Driver") 成功
// 且 new DatasourceConfig().getDbType()=="mariadb"（url 设 jdbc:mariadb://localhost:3308/test 经 setUrl 链路，
// 若 setUrl 需 full url 则直接断 DatasourceConfig.getDbTypeRaw("jdbc:mariadb://x")）
class MariadbDriverOnClasspathTest {
    @Test void mariadbDriverIsLoadable() throws Exception {
        Class.forName("org.mariadb.jdbc.Driver");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$env:JAVA_HOME='D:\Program Files\BellSoft\LibericaJDK-21-Full'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn -o -pl datasource test -Dtest=MariadbDriverOnClasspathTest`
Expected: FAIL `ClassNotFoundException: org.mariadb.jdbc.Driver`

- [ ] **Step 3: 改 datasource/pom.xml**：删除 :46-50 注释块，代以无 `<version>` 的 compile 依赖（groupId/artifactId 同前，版本走根 pom 管理）。

- [ ] **Step 4: 重跑确认通过**（同上命令，Expected: PASS 1/1）

- [ ] **Step 5: Commit**

```bash
git add datasource/pom.xml datasource/src/test/java/io/mycat/datasource/MariadbDriverOnClasspathTest.java
git commit -m "feat(datasource): mariadb-java-client 转 compile 依赖，ed25519 后端驱动上 classpath [I-AU-6]"
```

### Task 2: DruidDatasourceProvider 显式驱动路由 + 错配响亮报错

**Files:**
- Modify: `datasource/src/main/java/io/mycat/datasource/jdbc/DruidDatasourceProvider.java`（:46-57 区域）
- Test: `datasource/src/test/java/io/mycat/datasource/DriverRoutingTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的 classpath 驱动。
- Produces: `static String driverClassFor(String dbType)`（"mariadb"→`org.mariadb.jdbc.Driver`、"mysql"→`com.mysql.cj.jdbc.Driver`、其它→null 保持现行为）；dbType 与 URL scheme 错配时抛 `IllegalStateException`，消息含 `MariaDB 后端需 jdbc:mariadb:// URL + mariadb 驱动`。

- [ ] **Step 1: 写失败测试**（纯函数断言，不建池）

```java
class DriverRoutingTest {
    @Test void mariadbDbTypeResolvesDriver() { // 断 driverClassFor("mariadb") 等值 + Class.forName 可加载 }
    @Test void mysqlDbTypeResolvesDriver()    { // "mysql" → com.mysql.cj.jdbc.Driver }
    @Test void unknownDbTypeReturnsNull()     { // "clickhouse" → null（不扩面） }
    @Test void schemeDbTypeMismatchFailsLoud(){ // dbType=mariadb + jdbc:mysql:// URL → IllegalStateException 消息含 jdbc:mariadb }
}
```

- [ ] **Step 2: 跑测试确认失败**（编译错=方法不存在，视为 RED）

Run: `mvn -o -pl datasource test -Dtest=DriverRoutingTest`（JDK21 前缀同上）
Expected: 编译失败或断言失败

- [ ] **Step 3: 实现**：在 `getDataSource` 的 `setUrl` 前插入：取 `config.getDbType()`；`driverClassFor` 非 null 则 `datasource.setDriverClassName(...)`；URL scheme（`getDbTypeRaw(url)` 等价判断 startsWith）与 dbType 错配则抛上述 IllegalStateException。mariadb dbType 但 URL 是 `jdbc:mysql:` = 错配（此形态认证必失败，宁可响亮）。

- [ ] **Step 4: 跑测试确认通过**（Expected: PASS 4/4）

- [ ] **Step 5: 全模块回归**

Run: `mvn -o -pl datasource -am install -DskipTests` 后 `mvn -o -pl hbt,server test`（JDK21 前缀）
Expected: hbt 239/239、server 72/72（与 61 收口基线一致，零回归）

- [ ] **Step 6: Commit** `git add` 恰两文件，消息 `"feat(datasource): mariadb dbType 显式驱动路由+scheme 错配响亮报错 [I-AU-6]"`

### Task 3: NATIVE_JDBC 回退链观测（去静默）

**Files:**
- Modify: `server/src/main/java/io/mycat/commands/MycatMySQLManagerImpl.java`（:71-74 recover  lambda）
- Test: 手工日志验证（该行为需 `-Dserver=native` + 活库，不配单测——Ruling：日志级改动不值得为 vertx Future 测试脚手架扩面）

- [ ] **Step 1: 在 `recover` 回退分支加 WARN 日志**：`LOGGER.warn("native datasource pool failed for {}, falling back to JDBC pool: {}", name, throwable.toString())`（沿用该类既有日志设施；若无 LOGGER 则按模块惯例引入）。

- [ ] **Step 2: 编译验证** `mvn -o -pl server -am compile`（JDK21 前缀）Expected: BUILD SUCCESS

- [ ] **Step 3: Commit** 消息 `"chore(server): native→JDBC 池静默回退加 WARN 日志 [I-AU-6]"`

### Task 4: 活库金标准端到端（ed25519 真连）

**Files:**
- Create: `example/src/test/java/io/mycat/compat/MariadbEd25519BackendTest.java`
- 环境: mariadb:10.11 容器（3308），ed25519 用户

**Interfaces:**
- Consumes: Task 1-2（驱动+路由）、Task 0（Docker）。
- Produces: I-AU-6 验收铁证（认证+CRUD+结果集逐值对拍）。

- [ ] **Step 1: 起后端容器**

```powershell
docker run -d --name m6-mariadb-a -p 3308:3306 -e MARIADB_ROOT_PASSWORD=123456 mariadb:10.11
docker exec m6-mariadb-a mariadb -uroot -p123456 -e "CREATE USER 'e25519'@'%' IDENTIFIED VIA ed25519 USING PASSWORD('p@ssw0rd'); GRANT ALL ON *.* TO 'e25519'@'%'; CREATE DATABASE m6db;"
```
Expected: 无错误；`SELECT plugin FROM mysql.user WHERE user='e25519'` 返回 `client_ed25519`。

- [ ] **Step 2: 写 e2e 测试类**（实现 `MycatTest` 惯例，参照 CrudResultMatrixTest 的 create database/RESET_CONFIG/purge 模式）：
  - 经 Mycat 管理面注册后端 datasource：URL `jdbc:mariadb://localhost:3308/mysql?...`、用户 `e25519`（利用 HintHandler :645-655 的 url 覆写能力或 UPDATE DATABASE 语法，执行时以当前代码实际支持的注册通道为准）；`type=JDBC`（默认）。
  - 判据①：`create database m6schema` 成功且后端池建连**零认证异常**（旧行为=mysql-connector-j `Unable to load authentication plugin 'client_ed25519'`，作为反向锁：测试开头用 mysql 驱动 URL 连 ed25519 用户断言必抛该错——证明测的是 ed25519 而非其它）。
  - 判据②：建表/插入/跨片查询 CRUD 逐值与直连后端对拍。
- [ ] **Step 3: 跑测试（先期望 RED 于 datasource 注册通道缺口，遇通道不可编程注册→记 Ruling 用 CLI 手工注册+测试只验连通）**

Run: `mvn -o -pl example -am test -Dtest=MariadbEd25519BackendTest`（JDK21 前缀）
Expected: GREEN 3/3 判据（认证成功、A/B 反向锁成立、CRUD 对拍逐值等）

- [ ] **Step 4: 全量背书**：`mvn -o -pl hbt,server test` + 本类，全绿后 Commit（测试类+账本）。

### Task 5: fat-jar 编排验证 + DEBT-QUEUE 回写

**Files:**
- Test(手工): `server/target/server-*-jar-with-dependencies.jar`
- Modify: `docs/superpowers/specs/DEBT-QUEUE.md`（I-AU-6 行、仪表盘 M5/M6 行）

- [ ] **Step 1: 打 fat-jar** `mvn -o -pl server -am install -DskipTests`；`jar tf` 确认含 `org/mariadb/jdbc/Driver.class` 与 `META-INF/services/java.sql.Driver`（若 services 描述被单驱动覆盖——用 `Select-String` 检查内容含 mariadb 行；缺失则给 server/pom shade 加 `ServicesResourceTransformer` 并记录）。
- [ ] **Step 2: fat-jar 直启冒烟**：`java -jar` 起服务，重复 Task 4 判据① 的连接串一次（Ruling：冒烟只验认证握手，不重跑 CRUD——CRUD 已在 Task 4 覆盖）。
- [ ] **Step 3: DEBT-QUEUE 回写**：I-AU-6 状态 🔴→✅（附 3 判据证据与 commit 范围），仪表盘 M5/M6 行 ⚪0%→按实更新；提交消息 `"docs(debt-queue): I-AU-6 后端 ed25519 收口回写 [I-AU-6]"`。

## Review Focus

spec 隐含但各 Task 测试不直接覆盖、用户真实会用到的形态（执行者须逐条对照检查）：
1. **用户仍配 `jdbc:mysql://` 连 MariaDB-ed25519 后端**（最可能的误用）——Task 2 错配报错只在 dbType=mariadb 时触发；纯 `jdbc:mysql://` URL 会被 getDbTypeRaw 判成 mysql 走 mysql 驱动，失败信息是驱动原生 `Unable to load authentication plugin`——验收标准要求该错误**出现在日志而非静默**（Task 4 判据①反向锁覆盖）。
2. **非 ed25519 的 mariadb 后端（mysql_native_password 用户）不得被路由改动波及**——Task 2 全路由对既有认证方式透明（mariadb 驱动兼容 native/caching 否？mariadb 驱动不支持 caching_sha2/mysql8 专属插件，但 MariaDB 后端本无该插件；执行时对既有 mariadb persona 冒烟复跑确认零回归）。
3. **jOOQ ParsingDataSource 包装腿（dbType!=mysql → :84 条件命中 mariadb）**——ed25519 连接建池后经 jOOQ 包装层执行 CRUD，Task 4 判据② 实际经过该腿，若包装层对 mariadb 方言改写致值变形，红在判据② 而非认证层（归因线索已在此）。
4. **CI 矩阵无 mariadb-ed25519 格**——本计划仅本地活库背书；CI 补格另票（不阻塞收口，账本记 minor）。
