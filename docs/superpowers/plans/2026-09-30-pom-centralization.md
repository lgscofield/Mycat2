# POM 依赖集中化治理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Mycat2 的 Maven 依赖管理从散落在 10 个子模块的硬编码版本，统一为根 POM 单一版本源，同时移除 JDK 8 覆盖、去重 hbt POM、移除 fastjson。

**Architecture:** 根 POM 作为唯一版本管理中心，通过 `<dependencyManagement>` + 版本属性控制所有依赖版本。子模块 POM 只声明 groupId + artifactId，不声明 version。calcite-core 作为隔离 fork 不受影响。

**Tech Stack:** Maven 3.x, JDK 21, Jackson BOM 2.22.3

## Global Constraints

- calcite-core/pom.xml **不得修改**（fork 隔离策略）
- 版本冲突以根 POM 已定义版本为准
- Jackson 使用 `com.fasterxml.jackson:jackson-bom:2.22.3`（单 BOM）
- fastjson 彻底移除，迁移到 Jackson ObjectMapper
- example 模块中 Spring Boot / Hibernate / Spring Context 保留硬编码版本（示例专用）
- 每次 task 完成后执行增量编译验证

---

### Task 1: 根 POM 基础设施

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Produces: 所有版本属性 + dependencyManagement 条目，供后续 task 的子模块引用

- [ ] **Step 1: Jackson BOM 替换**

在 `pom.xml` 的 `<dependencyManagement><dependencies>` 中：

1. 在 `<dependencies>` 开始处（`<!-- 内部模块 -->` 注释之前）插入 Jackson BOM：
```xml
      <!-- Jackson BOM — 统一管理所有 Jackson 制品版本 -->
      <dependency>
        <groupId>com.fasterxml.jackson</groupId>
        <artifactId>jackson-bom</artifactId>
        <version>2.22.3</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
```

2. 删除以下 4 个单独 Jackson 条目（约第 243-262 行）：
```xml
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
      <dependency>
        <groupId>com.fasterxml.jackson.dataformat</groupId>
        <artifactId>jackson-dataformat-yaml</artifactId>
        <version>${jackson.version}</version>
      </dependency>
```

3. 删除 `<properties>` 中的 `<jackson.version>2.18.4</jackson.version>`（约第 52 行）。

- [ ] **Step 2: 新增版本属性**

在 `<properties>` 中 `<!-- 内部模块版本 -->` 之后、`</properties>` 之前，添加：

```xml

    <!-- 连接池 -->
    <hikaricp.version>3.4.5</hikaricp.version>

    <!-- 路由 / 脚本 -->
    <groovy.version>3.0.6</groovy.version>
    <jwildcard.version>1.4</jwildcard.version>

    <!-- 计算引擎 -->
    <fastutil.version>8.5.6</fastutil.version>
    <openhft-compiler.version>2.4.1</openhft-compiler.version>
    <commons-csv.version>1.9.0</commons-csv.version>
    <commons-lang3.version>3.12.0</commons-lang3.version>
    <commons-io.version>2.15.0</commons-io.version>
    <hppc.version>0.9.0</hppc.version>
    <reflections.version>0.10</reflections.version>
    <rxjava3-extensions.version>3.0.1</rxjava3-extensions.version>

    <!-- NoSQL 驱动 -->
    <mongo-driver.version>3.12.10</mongo-driver.version>
    <sequoiadb.version>5.0.2</sequoiadb.version>
    <jta.version>1.1</jta.version>
    <jooq.version>3.11.9</jooq.version>

    <!-- HTTP / 序列化 / 工具 -->
    <okhttp.version>4.7.2</okhttp.version>
    <snakeyaml.version>1.23</snakeyaml.version>
    <objenesis.version>3.0.1</objenesis.version>
    <validation-api.version>2.0.1.Final</validation-api.version>
    <joda-time.version>2.10.1</joda-time.version>
    <ehcache2.version>2.10.6</ehcache2.version>
    <idworker.version>1.4.0</idworker.version>
    <questdb.version>6.0.1</questdb.version>

    <!-- UI 工具 -->
    <tablesaw.version>0.38.3</tablesaw.version>
    <testfx.version>4.0.16-alpha</testfx.version>
    <hibernate-validator.version>5.2.0.Final</hibernate-validator.version>

    <!-- Calcite 生态 -->
    <dateparser.version>1.0.5</dateparser.version>
    <dom4j.version>2.1.3</dom4j.version>
    <jaxen.version>1.2.0</jaxen.version>
    <database-metadata-bind.version>3.2.0</database-metadata-bind.version>
    <lz4.version>1.3.0</lz4.version>
    <esri-geometry.version>2.2.0</esri-geometry.version>
    <checker-qual.version>3.10.0</checker-qual.version>
    <aspectjweaver.version>1.9.6</aspectjweaver.version>
    <apiguardian.version>1.1.2</apiguardian.version>
    <jsr305.version>3.0.2</jsr305.version>
    <json-path.version>2.10.0</json-path.version>
    <sketches-core.version>0.9.0</sketches-core.version>
    <aggdesigner.version>6.1</aggdesigner.version>
    <commons-dbcp2.version>2.11.0</commons-dbcp2.version>
    <commons-math3.version>3.6.1</commons-math3.version>
    <commons-text.version>1.11.0</commons-text.version>
    <uzaygezer.version>0.2</uzaygezer.version>
    <joou.version>0.9.5</joou.version>
    <error-prone.version>2.36.0</error-prone.version>
    <jts.version>1.19.0</jts.version>
    <proj4j.version>1.2.2</proj4j.version>

    <!-- 测试辅助 -->
    <jcip-annotations.version>1.0-1</jcip-annotations.version>
    <gson.version>2.11.0</gson.version>

    <!-- 根 POM 自身 -->
    <jetbrains-annotations.version>1.2.0</jetbrains-annotations.version>
```

- [ ] **Step 3: 新增 dependencyManagement 条目**

在 `</dependencyManagement>` 关闭标签之前，追加以下分组条目：

```xml

      <!-- ====== 新增集中管理的依赖 ====== -->

      <!-- 连接池 -->
      <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>${hikaricp.version}</version>
      </dependency>

      <!-- 路由 / 脚本 -->
      <dependency>
        <groupId>org.codehaus.groovy</groupId>
        <artifactId>groovy-all</artifactId>
        <version>${groovy.version}</version>
        <type>pom</type>
      </dependency>
      <dependency>
        <groupId>com.yevdo</groupId>
        <artifactId>jwildcard</artifactId>
        <version>${jwildcard.version}</version>
        <scope>test</scope>
      </dependency>

      <!-- 计算引擎 -->
      <dependency>
        <groupId>it.unimi.dsi</groupId>
        <artifactId>fastutil</artifactId>
        <version>${fastutil.version}</version>
      </dependency>
      <dependency>
        <groupId>net.openhft</groupId>
        <artifactId>compiler</artifactId>
        <version>${openhft-compiler.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-csv</artifactId>
        <version>${commons-csv.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-lang3</artifactId>
        <version>${commons-lang3.version}</version>
      </dependency>
      <dependency>
        <groupId>commons-io</groupId>
        <artifactId>commons-io</artifactId>
        <version>${commons-io.version}</version>
      </dependency>
      <dependency>
        <groupId>com.carrotsearch</groupId>
        <artifactId>hppc</artifactId>
        <version>${hppc.version}</version>
      </dependency>
      <dependency>
        <groupId>org.reflections</groupId>
        <artifactId>reflections</artifactId>
        <version>${reflections.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.akarnokd</groupId>
        <artifactId>rxjava3-extensions</artifactId>
        <version>${rxjava3-extensions.version}</version>
      </dependency>

      <!-- NoSQL 驱动 -->
      <dependency>
        <groupId>org.mongodb</groupId>
        <artifactId>mongo-java-driver</artifactId>
        <version>${mongo-driver.version}</version>
        <scope>provided</scope>
      </dependency>
      <dependency>
        <groupId>com.sequoiadb</groupId>
        <artifactId>sequoiadb-driver</artifactId>
        <version>${sequoiadb.version}</version>
        <scope>provided</scope>
      </dependency>
      <dependency>
        <groupId>javax.transaction</groupId>
        <artifactId>jta</artifactId>
        <version>${jta.version}</version>
      </dependency>
      <dependency>
        <groupId>org.jooq</groupId>
        <artifactId>jooq</artifactId>
        <version>${jooq.version}</version>
      </dependency>

      <!-- HTTP / 序列化 / 工具 -->
      <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>${okhttp.version}</version>
      </dependency>
      <dependency>
        <groupId>org.yaml</groupId>
        <artifactId>snakeyaml</artifactId>
        <version>${snakeyaml.version}</version>
      </dependency>
      <dependency>
        <groupId>org.objenesis</groupId>
        <artifactId>objenesis</artifactId>
        <version>${objenesis.version}</version>
      </dependency>
      <dependency>
        <groupId>javax.validation</groupId>
        <artifactId>validation-api</artifactId>
        <version>${validation-api.version}</version>
      </dependency>
      <dependency>
        <groupId>joda-time</groupId>
        <artifactId>joda-time</artifactId>
        <version>${joda-time.version}</version>
      </dependency>
      <dependency>
        <groupId>net.sf.ehcache</groupId>
        <artifactId>ehcache</artifactId>
        <version>${ehcache2.version}</version>
      </dependency>
      <dependency>
        <groupId>com.imadcn.framework</groupId>
        <artifactId>idworker</artifactId>
        <version>${idworker.version}</version>
      </dependency>
      <dependency>
        <groupId>org.questdb</groupId>
        <artifactId>questdb-jdk8</artifactId>
        <version>${questdb.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.oshi</groupId>
        <artifactId>oshi-core</artifactId>
        <version>${oshi.version}</version>
      </dependency>

      <!-- UI 工具 -->
      <dependency>
        <groupId>tech.tablesaw</groupId>
        <artifactId>tablesaw-core</artifactId>
        <version>${tablesaw.version}</version>
      </dependency>
      <dependency>
        <groupId>org.testfx</groupId>
        <artifactId>testfx-core</artifactId>
        <version>${testfx.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.testfx</groupId>
        <artifactId>testfx-junit</artifactId>
        <version>${testfx.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.hibernate</groupId>
        <artifactId>hibernate-validator</artifactId>
        <version>${hibernate-validator.version}</version>
      </dependency>

      <!-- Calcite 生态 -->
      <dependency>
        <groupId>com.github.sisyphsu</groupId>
        <artifactId>dateparser</artifactId>
        <version>${dateparser.version}</version>
      </dependency>
      <dependency>
        <groupId>org.dom4j</groupId>
        <artifactId>dom4j</artifactId>
        <version>${dom4j.version}</version>
      </dependency>
      <dependency>
        <groupId>jaxen</groupId>
        <artifactId>jaxen</artifactId>
        <version>${jaxen.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.jinahya</groupId>
        <artifactId>database-metadata-bind</artifactId>
        <version>${database-metadata-bind.version}</version>
      </dependency>
      <dependency>
        <groupId>net.jpountz.lz4</groupId>
        <artifactId>lz4</artifactId>
        <version>${lz4.version}</version>
      </dependency>
      <dependency>
        <groupId>com.esri.geometry</groupId>
        <artifactId>esri-geometry-api</artifactId>
        <version>${esri-geometry.version}</version>
      </dependency>
      <dependency>
        <groupId>org.checkerframework</groupId>
        <artifactId>checker-qual</artifactId>
        <version>${checker-qual.version}</version>
      </dependency>
      <dependency>
        <groupId>org.aspectj</groupId>
        <artifactId>aspectjweaver</artifactId>
        <version>${aspectjweaver.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apiguardian</groupId>
        <artifactId>apiguardian-api</artifactId>
        <version>${apiguardian.version}</version>
      </dependency>
      <dependency>
        <groupId>com.google.code.findbugs</groupId>
        <artifactId>jsr305</artifactId>
        <version>${jsr305.version}</version>
      </dependency>
      <dependency>
        <groupId>com.jayway.jsonpath</groupId>
        <artifactId>json-path</artifactId>
        <version>${json-path.version}</version>
      </dependency>
      <dependency>
        <groupId>com.yahoo.datasketches</groupId>
        <artifactId>sketches-core</artifactId>
        <version>${sketches-core.version}</version>
      </dependency>
      <dependency>
        <groupId>net.hydromatic</groupId>
        <artifactId>aggdesigner-algorithm</artifactId>
        <version>${aggdesigner.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-dbcp2</artifactId>
        <version>${commons-dbcp2.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-math3</artifactId>
        <version>${commons-math3.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-text</artifactId>
        <version>${commons-text.version}</version>
      </dependency>
      <dependency>
        <groupId>com.google.uzaygezen</groupId>
        <artifactId>uzaygezer-core</artifactId>
        <version>${uzaygezer.version}</version>
      </dependency>
      <dependency>
        <groupId>org.jooq</groupId>
        <artifactId>joou-java-6</artifactId>
        <version>${joou.version}</version>
      </dependency>
      <dependency>
        <groupId>com.google.errorprone</groupId>
        <artifactId>error_prone_annotations</artifactId>
        <version>${error-prone.version}</version>
      </dependency>
      <dependency>
        <groupId>org.locationtech.jts</groupId>
        <artifactId>jts-core</artifactId>
        <version>${jts.version}</version>
      </dependency>
      <dependency>
        <groupId>org.locationtech.jts.io</groupId>
        <artifactId>jts-io-common</artifactId>
        <version>${jts.version}</version>
      </dependency>
      <dependency>
        <groupId>org.locationtech.proj4j</groupId>
        <artifactId>proj4j</artifactId>
        <version>${proj4j.version}</version>
      </dependency>

      <!-- 测试辅助 -->
      <dependency>
        <groupId>com.github.stephenc.jcip</groupId>
        <artifactId>jcip-annotations</artifactId>
        <version>${jcip-annotations.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-junit5</artifactId>
        <version>${vertx.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>${gson.version}</version>
      </dependency>
```

- [ ] **Step 4: 根 POM 自身修复**

1. 删除 `<dependencyManagement>` 中重复的 `io.mycat:server` 条目（约第 186-190 行）：
```xml
      <dependency>
        <groupId>io.mycat</groupId>
        <artifactId>server</artifactId>
        <version>${mycat.version}</version>
      </dependency>
```
（这是第二个 server 声明，第一个在约第 177-180 行）

2. 在根 `<dependencies>` 中将 `org.jetbrains.annotations` 的硬编码版本改为属性引用：
```xml
    <dependency>
      <groupId>org.realityforge.org.jetbrains.annotations</groupId>
      <artifactId>org.jetbrains.annotations</artifactId>
      <version>${jetbrains-annotations.version}</version>
    </dependency>
```

- [ ] **Step 5: 提交**

```bash
git add pom.xml
git commit -m 'build: centralize dependency management in root POM with Jackson BOM and 40+ new entries'
```

---

### Task 2: JDK 8 覆盖移除 + 轻量模块版本清理

**Files:**
- Modify: `console/pom.xml`
- Modify: `vertx/pom.xml`
- Modify: `internalexample/pom.xml`
- Modify: `plug/pom.xml`
- Modify: `router/pom.xml`

**Interfaces:**
- Consumes: Task 1 产出的根 POM dependencyManagement 条目

- [ ] **Step 1: 移除 JDK 8 覆盖**

**console/pom.xml** — 删除第 14-17 行：
```xml
    <properties>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
    </properties>
```

**vertx/pom.xml** — 删除第 24-27 行：
```xml
    <properties>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
    </properties>
```

**internalexample/pom.xml** — 删除第 14-17 行（同上）。

- [ ] **Step 2: internalexample 版本移除**

在 `internalexample/pom.xml` 中，移除 `jcip-annotations` 的 `<version>` 标签：
```xml
        <dependency>
            <groupId>com.github.stephenc.jcip</groupId>
            <artifactId>jcip-annotations</artifactId>
        </dependency>
```

- [ ] **Step 3: plug 版本移除**

在 `plug/pom.xml` 中，移除 `idworker` 的 `<version>` 标签：
```xml
        <dependency>
            <groupId>com.imadcn.framework</groupId>
            <artifactId>idworker</artifactId>
        </dependency>
```

- [ ] **Step 4: router 版本移除**

在 `router/pom.xml` 中：

1. 移除 `groovy-all` 的 `<version>` 标签（保留 `<type>pom</type>`）：
```xml
    <dependency>
        <groupId>org.codehaus.groovy</groupId>
        <artifactId>groovy-all</artifactId>
        <type>pom</type>
    </dependency>
```

2. 移除 `jwildcard` 的 `<version>` 标签：
```xml
    <dependency>
        <groupId>com.yevdo</groupId>
        <artifactId>jwildcard</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 5: 编译验证**

```bash
mvn compile -pl vertx,console,internalexample,plug,router -am -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add vertx/pom.xml console/pom.xml internalexample/pom.xml plug/pom.xml router/pom.xml
git commit -m 'build: remove JDK 8 overrides and hardcoded versions from 5 leaf modules'
```

---

### Task 3: 核心模块版本清理

**Files:**
- Modify: `config/pom.xml`
- Modify: `common/pom.xml`
- Modify: `datasource/pom.xml`
- Modify: `va/pom.xml`
- Modify: `executor/pom.xml`
- Modify: `assistant/pom.xml`

**Interfaces:**
- Consumes: Task 1 产出的根 POM dependencyManagement 条目

- [ ] **Step 1: config/pom.xml 版本移除 + fastjson 删除**

1. 移除以下依赖的 `<version>` 标签：
   - `org.slf4j:slf4j-api` (原 1.7.36)
   - `javax.validation:validation-api` (原 2.0.1.Final)
   - `com.squareup.okhttp3:okhttp` (原 4.7.2，保留 exclusions 块)
   - `org.yaml:snakeyaml` (原 1.23)
   - `org.objenesis:objenesis` (原 3.0.1)

2. **删除** 整个 fastjson 依赖块：
```xml
        <!-- https://mvnrepository.com/artifact/com.alibaba/fastjson -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>fastjson</artifactId>
            <version>1.2.79</version>
        </dependency>
```

- [ ] **Step 2: common/pom.xml 版本移除**

移除以下依赖的 `<version>` 标签：
- `joda-time:joda-time` (原 2.10.1)
- `net.sf.ehcache:ehcache` (原 2.10.6)
- `com.github.oshi:oshi-core` (原 5.8.5)
- `com.imadcn.framework:idworker` (原 1.4.0)
- `org.questdb:questdb-jdk8` (原 6.0.1)

- [ ] **Step 3: datasource/pom.xml 版本移除**

移除以下依赖的 `<version>` 标签：
- `com.sequoiadb:sequoiadb-driver` (原 5.0.2)
- `org.mongodb:mongo-java-driver` (原 3.12.10)
- `com.zaxxer:HikariCP` (原 3.3.1)
- `javax.transaction:jta` (原 1.1)
- `org.jooq:jooq` (原 3.11.9)
- `ru.yandex.clickhouse:clickhouse-jdbc` (原 0.3.2)

- [ ] **Step 4: va/pom.xml JDK 8 + 版本移除**

1. 删除 `<properties>` 块（第 14-17 行）。
2. 移除以下依赖的 `<version>` 标签：
   - `com.zaxxer:HikariCP` (原 3.4.5)
   - `io.vertx:vertx-jdbc-client` (原 4.2.5)
   - `io.vertx:vertx-junit5` (原 4.2.5)
   - `com.github.stephenc.jcip:jcip-annotations` (原 1.0-1)

- [ ] **Step 5: executor/pom.xml JDK 8 + 版本移除 + 修复**

1. 删除 `<properties>` 块（第 14-17 行）。
2. 移除以下依赖的 `<version>` 标签：
   - `it.unimi.dsi:fastutil` (原 8.5.6)
   - `net.openhft:compiler` (原 2.4.1)
   - `org.apache.arrow:arrow-algorithm` (原 5.0.0)
   - `org.apache.arrow:arrow-dataset` (原 5.0.0)
   - `org.apache.commons:commons-csv` (原 1.9.0)
   - `org.apache.commons:commons-lang3` (原 3.12.0)
   - `com.carrotsearch:hppc` (原 0.9.0)
   - `org.reflections:reflections` (原 0.10)
   - `com.github.akarnokd:rxjava3-extensions` (原 3.0.1)
3. 修复 junit 4：删除 `<version>4.13.2</version>`，将 `<scope>compile</scope>` 改为 `<scope>test</scope>`。
4. 删除第二个重复的 `io.mycat:common` 声明（约第 98-103 行）：
```xml
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>common</artifactId>
            <scope>compile</scope>
        </dependency>
```

- [ ] **Step 6: assistant/pom.xml JDK 8 + 版本移除 + repositories 清理**

1. 删除 `<properties>` 块（第 14-17 行）。
2. 移除以下依赖的 `<version>` 标签：
   - `tech.tablesaw:tablesaw-core` (原 0.38.3)
   - `org.testfx:testfx-core` (原 4.0.16-alpha)
   - `org.testfx:testfx-junit` (原 4.0.16-alpha)
   - `org.hibernate:hibernate-validator` (原 5.2.0.Final)
3. 删除整个 `<repositories>` 块（第 70-95 行）。

- [ ] **Step 7: 编译验证**

```bash
mvn compile -pl config,common,datasource,va,executor,assistant -am -DskipTests
```

Expected: BUILD SUCCESS（如有编译错误，暂不修复，留到 Task 6 统一处理）

- [ ] **Step 8: 提交**

```bash
git add config/pom.xml common/pom.xml datasource/pom.xml va/pom.xml executor/pom.xml assistant/pom.xml
git commit -m 'build: remove hardcoded versions and JDK 8 overrides from 6 core modules'
```

---

### Task 4: hbt POM 彻底去重

**Files:**
- Rewrite: `hbt/pom.xml`（从 476 行重写为约 150 行）

**Interfaces:**
- Consumes: Task 1 产出的根 POM dependencyManagement 条目

- [ ] **Step 1: 重写 hbt/pom.xml**

用以下内容**完全替换** `hbt/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>parent</artifactId>
        <groupId>io.mycat</groupId>
        <version>1.22-release</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <artifactId>hbt</artifactId>

    <dependencies>
        <!-- 内部模块 -->
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>common</artifactId>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>plug</artifactId>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>datasource</artifactId>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>config</artifactId>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>va</artifactId>
            <scope>compile</scope>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>calcite-core</artifactId>
        </dependency>

        <!-- Druid -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>druid</artifactId>
        </dependency>

        <!-- Calcite 生态 -->
        <dependency>
            <groupId>com.github.jinahya</groupId>
            <artifactId>database-metadata-bind</artifactId>
        </dependency>
        <dependency>
            <groupId>net.jpountz.lz4</groupId>
            <artifactId>lz4</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.sisyphsu</groupId>
            <artifactId>dateparser</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.codehaus.janino</groupId>
            <artifactId>janino</artifactId>
        </dependency>
        <dependency>
            <groupId>org.codehaus.janino</groupId>
            <artifactId>commons-compiler</artifactId>
        </dependency>
        <dependency>
            <groupId>commons-io</groupId>
            <artifactId>commons-io</artifactId>
        </dependency>
        <dependency>
            <groupId>org.dom4j</groupId>
            <artifactId>dom4j</artifactId>
        </dependency>
        <dependency>
            <groupId>jaxen</groupId>
            <artifactId>jaxen</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-lang3</artifactId>
        </dependency>
        <dependency>
            <groupId>commons-codec</groupId>
            <artifactId>commons-codec</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-yaml</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.calcite.avatica</groupId>
            <artifactId>avatica-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apiguardian</groupId>
            <artifactId>apiguardian-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.esri.geometry</groupId>
            <artifactId>esri-geometry-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.jayway.jsonpath</groupId>
            <artifactId>json-path</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.code.findbugs</groupId>
            <artifactId>jsr305</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
        </dependency>
        <dependency>
            <groupId>com.yahoo.datasketches</groupId>
            <artifactId>sketches-core</artifactId>
        </dependency>
        <dependency>
            <groupId>net.hydromatic</groupId>
            <artifactId>aggdesigner-algorithm</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-dbcp2</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.calcite</groupId>
            <artifactId>calcite-linq4j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.immutables</groupId>
            <artifactId>value</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.akarnokd</groupId>
            <artifactId>rxjava3-extensions</artifactId>
        </dependency>
        <dependency>
            <groupId>org.checkerframework</groupId>
            <artifactId>checker-qual</artifactId>
        </dependency>
        <dependency>
            <groupId>org.aspectj</groupId>
            <artifactId>aspectjweaver</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-params</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.calcite.avatica</groupId>
            <artifactId>avatica-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>net.hydromatic</groupId>
            <artifactId>scott-data-hsqldb</artifactId>
            <version>0.1</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>sqlline</groupId>
            <artifactId>sqlline</artifactId>
            <version>1.9.0</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>net.hydromatic</groupId>
            <artifactId>foodmart-data-hsqldb</artifactId>
            <version>0.4</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.hamcrest</groupId>
            <artifactId>hamcrest-core</artifactId>
            <version>2.1</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>net.hydromatic</groupId>
            <artifactId>foodmart-queries</artifactId>
            <version>0.4.1</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>net.hydromatic</groupId>
            <artifactId>quidem</artifactId>
            <version>0.9</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.hsqldb</groupId>
            <artifactId>hsqldb</artifactId>
            <version>2.5.1</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.incava</groupId>
            <artifactId>java-diff</artifactId>
            <version>1.1.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.immutables</groupId>
                            <artifactId>value</artifactId>
                            <version>${immutables.version}</version>
                        </path>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl hbt -am -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add hbt/pom.xml
git commit -m 'build: deduplicate and centralize hbt POM (476 -> ~170 lines)'
```

---

### Task 5: fastjson → Jackson 代码迁移 + example 清理

**Files:**
- Modify: `config/src/main/java/io/mycat/util/JsonUtil.java`
- Modify: `hbt/src/main/java/io/mycat/gsi/mapdb/MapDBUtils.java`
- Modify: `example/pom.xml`

**Interfaces:**
- Consumes: Task 1 的 Jackson BOM（提供 `com.fasterxml.jackson.databind.ObjectMapper`）

- [ ] **Step 1: 重写 JsonUtil.java**

用以下内容完全替换 `config/src/main/java/io/mycat/util/JsonUtil.java`：

```java
package io.mycat.util;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Map<String, String> urlSplit(String s) {
        Map<String, String> mapRequest = new HashMap<>(2);
        String strUrlParam = truncateUrlPage(s);
        if (strUrlParam == null) {
            return mapRequest;
        }
        String[] arrSplit = strUrlParam.split("[&]");
        for (String strSplit : arrSplit) {
            String[] arrSplitEqual = strSplit.split("[=]");
            //解析出键值
            if (arrSplitEqual.length > 1) {
                //正确解析
                mapRequest.put(arrSplitEqual[0], arrSplitEqual[1]);
            } else {
                if (!"".equals(arrSplitEqual[0])) {
                    //只有参数没有值，不加入
                    mapRequest.put(arrSplitEqual[0], "");
                }
            }
        }
        return mapRequest;
    }

    private static String truncateUrlPage(String strURL) {
        String strAllParam = null;
        strURL = strURL.trim();
        String[] arrSplit = strURL.split("[?]");
        if (strURL.length() > 1) {
            if (arrSplit.length > 1) {
                for (int i = 1; i < arrSplit.length; i++) {
                    strAllParam = arrSplit[i];
                }
            }
        }
        return strAllParam;
    }

    public static <T> T clone(Object o, Class<T> clazz) {
        return from(toJson(o), clazz);
    }

    @SuppressWarnings("unchecked")
    public static <T> T from(String text, Class<T> clazz) {
        try {
            return MAPPER.readValue(text, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("rawtypes")
    public static Optional<String> fromMap(String text, String item) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            Map map = MAPPER.readValue(text, Map.class);
            return Optional.ofNullable(map).map(i -> (String) i.get(item));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public static String toJson(Object o) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: 重写 MapDBUtils.java**

用以下内容完全替换 `hbt/src/main/java/io/mycat/gsi/mapdb/MapDBUtils.java`：

```java
package io.mycat.gsi.mapdb;

import com.fasterxml.jackson.databind.ObjectMapper;

public class MapDBUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("rawtypes")
    public static Object cast(Object value, Class javaClass) {
        return MAPPER.convertValue(value, javaClass);
    }
}
```

- [ ] **Step 3: example/pom.xml 版本清理**

在 `example/pom.xml` 中，仅移除以下 5 个依赖的 `<version>` 标签（由根 POM 管理）：
- `com.zaxxer:HikariCP` (原 4.0.3)
- `com.google.code.gson:gson` (原 2.8.2)
- `junit:junit` (原 4.13.1)
- `io.vertx:vertx-junit5` (原 4.2.5)
- `com.github.stephenc.jcip:jcip-annotations` (原 1.0-1)

**保留硬编码版本**（示例专用，不纳入根 POM）：
- `org.springframework.boot:spring-boot-test-autoconfigure` (2.4.1)
- `org.junit.jupiter:junit-jupiter-api` (5.7.0)
- `org.junit.jupiter:junit-jupiter-engine` (5.7.0)
- `org.springframework.boot:spring-boot-starter-data-jpa` (2.4.1)
- `org.springframework.boot:spring-boot-starter` (2.4.1)
- `org.hibernate:hibernate-core` (5.4.25.Final)
- `org.springframework:spring-context` (5.3.2)

- [ ] **Step 4: 编译验证**

```bash
mvn compile -pl config,hbt,example -am -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 确认 fastjson 已消除**

```bash
mvn dependency:tree -pl config -Dincludes=com.alibaba:fastjson
```

Expected: 无 fastjson 输出

- [ ] **Step 6: 提交**

```bash
git add config/src/main/java/io/mycat/util/JsonUtil.java hbt/src/main/java/io/mycat/gsi/mapdb/MapDBUtils.java example/pom.xml
git commit -m 'refactor: migrate from fastjson to Jackson ObjectMapper, clean example POM'
```

---

### Task 6: 全量编译验证 + 问题修复

**Files:**
- 可能需要修复因版本升级导致的编译错误

- [ ] **Step 1: 全量编译**

```bash
mvn clean compile -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 修复编译错误（如有）**

常见预期问题：
- **Arrow 5→18 API 变更**：检查 executor 模块中 `arrow-algorithm` / `arrow-dataset` 的使用，可能需要适配 API
- **SLF4J 1.7→2.0**：config 模块中如有使用 `LoggerFactory.getLogger(Class)` 之外的旧 API，需要适配
- **oshi-core 5→6 API 变更**：common 模块中 oshi 相关代码可能需要适配
- **JUnit 4 scope 变更**：executor 中如果有非测试代码引用了 JUnit 4 的类，需要修复

逐个修复，每修复一个问题重新编译验证。

- [ ] **Step 3: 确认 Arrow 版本统一**

```bash
mvn dependency:tree -pl executor -Dincludes=org.apache.arrow
```

Expected: 所有 Arrow 制品版本为 18.1.0

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m 'fix: resolve compilation issues from dependency version upgrades'
```

（如果没有编译错误需要修复，跳过此步）
