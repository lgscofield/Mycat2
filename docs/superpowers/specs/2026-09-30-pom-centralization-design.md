# POM 依赖集中化与治理设计

## 概述

系统性修复 Mycat2 项目中 10 个 Maven POM 文件的依赖管理问题：移除 JDK 8 编译覆盖、消除硬编码版本号、统一 JSON 库为 Jackson BOM、修复 scope 错误、去重臃肿声明。

## 约束与决策

| 决策 | 选择 | 理由 |
|------|------|------|
| calcite-core | 不动 | Fork 隔离策略，保持独立依赖声明 |
| 版本冲突 | 以根 POM 为准 | 单一版本源原则 |
| Jackson | 单 BOM `com.fasterxml.jackson:jackson-bom:2.22.3` | 2.x 最新稳定版，与 calcite-core fork 兼容 |
| fastjson | 彻底移除 | EOL 且有安全漏洞，用量仅 2 个文件 |
| hbt POM | 彻底去重 | 476 行含大量重复，目标约 150 行 |

## 变更清单

### 1. 根 POM 变更 (`pom.xml`)

#### 1.1 Jackson BOM 替换

删除 `<dependencyManagement>` 中 4 个单独 Jackson 条目：
- `com.fasterxml.jackson.core:jackson-core`
- `com.fasterxml.jackson.core:jackson-databind`
- `com.fasterxml.jackson.core:jackson-annotations`
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`

替换为 BOM 导入（放在 dependencyManagement 最前面以确保优先级）：
```xml
<dependency>
  <groupId>com.fasterxml.jackson</groupId>
  <artifactId>jackson-bom</artifactId>
  <version>2.22.3</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

删除 `<properties>` 中的 `<jackson.version>2.18.4</jackson.version>`。

#### 1.2 新增版本属性

在 `<properties>` 中新增以下属性：

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

注：`oshi.version` 已存在（6.8.2），此处仅确认值正确。

#### 1.3 新增 dependencyManagement 条目

按分组添加约 40 个条目，每个使用对应的 `${property}` 引用：

**连接池**
- `com.zaxxer:HikariCP:${hikaricp.version}`

**路由 / 脚本**
- `org.codehaus.groovy:groovy-all:${groovy.version}` (type=pom)
- `com.yevdo:jwildcard:${jwildcard.version}` (scope=test)

**计算引擎**
- `it.unimi.dsi:fastutil:${fastutil.version}`
- `net.openhft:compiler:${openhft-compiler.version}`
- `org.apache.commons:commons-csv:${commons-csv.version}`
- `org.apache.commons:commons-lang3:${commons-lang3.version}`
- `commons-io:commons-io:${commons-io.version}`
- `com.carrotsearch:hppc:${hppc.version}`
- `org.reflections:reflections:${reflections.version}`
- `com.github.akarnokd:rxjava3-extensions:${rxjava3-extensions.version}`

**NoSQL 驱动**
- `org.mongodb:mongo-java-driver:${mongo-driver.version}` (scope=provided)
- `com.sequoiadb:sequoiadb-driver:${sequoiadb.version}` (scope=provided)
- `javax.transaction:jta:${jta.version}`
- `org.jooq:jooq:${jooq.version}`

**HTTP / 序列化 / 工具**
- `com.squareup.okhttp3:okhttp:${okhttp.version}`
- `org.yaml:snakeyaml:${snakeyaml.version}`
- `org.objenesis:objenesis:${objenesis.version}`
- `javax.validation:validation-api:${validation-api.version}`
- `joda-time:joda-time:${joda-time.version}`
- `net.sf.ehcache:ehcache:${ehcache2.version}`
- `com.imadcn.framework:idworker:${idworker.version}`
- `org.questdb:questdb-jdk8:${questdb.version}`
- `com.github.oshi:oshi-core:${oshi.version}`

**UI 工具**
- `tech.tablesaw:tablesaw-core:${tablesaw.version}`
- `org.testfx:testfx-core:${testfx.version}` (scope=test)
- `org.testfx:testfx-junit:${testfx.version}` (scope=test)
- `org.hibernate:hibernate-validator:${hibernate-validator.version}`

**Calcite 生态**（供 hbt / calcite-core 使用）
- `com.github.sisyphsu:dateparser:${dateparser.version}`
- `org.dom4j:dom4j:${dom4j.version}`
- `jaxen:jaxen:${jaxen.version}`
- `com.github.jinahya:database-metadata-bind:${database-metadata-bind.version}`
- `net.jpountz.lz4:lz4:${lz4.version}`
- `com.esri.geometry:esri-geometry-api:${esri-geometry.version}`
- `org.checkerframework:checker-qual:${checker-qual.version}`
- `org.aspectj:aspectjweaver:${aspectjweaver.version}`
- `org.apiguardian:apiguardian-api:${apiguardian.version}`
- `com.google.code.findbugs:jsr305:${jsr305.version}`
- `com.jayway.jsonpath:json-path:${json-path.version}`
- `com.yahoo.datasketches:sketches-core:${sketches-core.version}`
- `net.hydromatic:aggdesigner-algorithm:${aggdesigner.version}`
- `org.apache.commons:commons-dbcp2:${commons-dbcp2.version}`
- `org.apache.commons:commons-math3:${commons-math3.version}`
- `org.apache.commons:commons-text:${commons-text.version}`
- `com.google.uzaygezen:uzaygezer-core:${uzaygezer.version}`
- `org.jooq:joou-java-6:${joou.version}`
- `com.google.errorprone:error_prone_annotations:${error-prone.version}`
- `org.locationtech.jts:jts-core:${jts.version}`
- `org.locationtech.jts.io:jts-io-common:${jts.version}`
- `org.locationtech.proj4j:proj4j:${proj4j.version}`

**测试辅助**
- `com.github.stephenc.jcip:jcip-annotations:${jcip-annotations.version}` (scope=test)
- `io.vertx:vertx-junit5:${vertx.version}` (scope=test)
- `com.google.code.gson:gson:${gson.version}`

#### 1.4 根 POM 自身修复

1. 删除 `<dependencyManagement>` 中重复的 `io.mycat:server` 条目（第 186-190 行）
2. 将根 `<dependencies>` 中 `org.jetbrains.annotations` 的硬编码 `1.2.0` 改为 `${jetbrains-annotations.version}`

### 2. 移除 JDK 8 覆盖（6 个模块）

删除以下模块 POM 中的整个 `<properties>` 块（仅包含 maven.compiler.source/target=8）：

| 模块 | 文件 | 删除内容 |
|------|------|---------|
| executor | `executor/pom.xml` | 第 14-17 行 `<properties>...</properties>` |
| va | `va/pom.xml` | 第 14-17 行 |
| assistant | `assistant/pom.xml` | 第 14-17 行 |
| console | `console/pom.xml` | 第 14-17 行 |
| vertx | `vertx/pom.xml` | 第 24-27 行 |
| internalexample | `internalexample/pom.xml` | 第 14-17 行 |

删除后这些模块继承根 POM 的 `<source>21</source><target>21</target>`。

### 3. 子模块硬编码版本移除

#### 3.1 config/pom.xml

移除以下依赖的 `<version>` 标签：
- `org.slf4j:slf4j-api` (原 1.7.36 → 由 BOM 管理为 2.0.16)
- `javax.validation:validation-api` (原 2.0.1.Final)
- `com.squareup.okhttp3:okhttp` (原 4.7.2，保留 exclusions)
- `org.yaml:snakeyaml` (原 1.23)
- `org.objenesis:objenesis` (原 3.0.1)

**删除** `com.alibaba:fastjson` 依赖（原 1.2.79）。

#### 3.2 router/pom.xml

移除 `<version>`：
- `org.codehaus.groovy:groovy-all` (原 3.0.6，保留 type=pom)
- `com.yevdo:jwildcard` (原 1.4)

#### 3.3 executor/pom.xml

移除 `<version>`：
- `it.unimi.dsi:fastutil` (原 8.5.6)
- `net.openhft:compiler` (原 2.4.1)
- `org.apache.arrow:arrow-algorithm` (原 5.0.0 → 由 BOM 管理为 18.1.0)
- `org.apache.arrow:arrow-dataset` (原 5.0.0 → 由 BOM 管理为 18.1.0)
- `org.apache.commons:commons-csv` (原 1.9.0)
- `org.apache.commons:commons-lang3` (原 3.12.0)
- `com.carrotsearch:hppc` (原 0.9.0)
- `org.reflections:reflections` (原 0.10)
- `com.github.akarnokd:rxjava3-extensions` (原 3.0.1)

修复：
- `junit:junit`：删除 `<version>4.13.2</version>`，改 `<scope>compile</scope>` 为 `<scope>test</scope>`
- 删除第二个重复的 `io.mycat:common` 声明（第 99-103 行）

#### 3.4 va/pom.xml

移除 `<version>`：
- `com.zaxxer:HikariCP` (原 3.4.5)
- `io.vertx:vertx-jdbc-client` (原 4.2.5)
- `io.vertx:vertx-junit5` (原 4.2.5)
- `com.github.stephenc.jcip:jcip-annotations` (原 1.0-1)

#### 3.5 internalexample/pom.xml

移除 `<version>`：
- `com.github.stephenc.jcip:jcip-annotations` (原 1.0-1)

#### 3.6 datasource/pom.xml

移除 `<version>`：
- `com.sequoiadb:sequoiadb-driver` (原 5.0.2)
- `org.mongodb:mongo-java-driver` (原 3.12.10)
- `com.zaxxer:HikariCP` (原 3.3.1)
- `javax.transaction:jta` (原 1.1)
- `org.jooq:jooq` (原 3.11.9)
- `ru.yandex.clickhouse:clickhouse-jdbc` (原 0.3.2)

#### 3.7 common/pom.xml

移除 `<version>`：
- `joda-time:joda-time` (原 2.10.1)
- `net.sf.ehcache:ehcache` (原 2.10.6)
- `com.github.oshi:oshi-core` (原 5.8.5)
- `com.imadcn.framework:idworker` (原 1.4.0)
- `org.questdb:questdb-jdk8` (原 6.0.1)

#### 3.8 plug/pom.xml

移除 `<version>`：
- `com.imadcn.framework:idworker` (原 1.4.0)

#### 3.9 example/pom.xml

移除 `<version>`：
- `com.zaxxer:HikariCP` (原 4.0.3)
- `com.google.code.gson:gson` (原 2.8.2)
- `org.springframework.boot:spring-boot-test-autoconfigure` (原 2.4.1)
- `org.junit.jupiter:junit-jupiter-api` (原 5.7.0)
- `org.junit.jupiter:junit-jupiter-engine` (原 5.7.0)
- `org.springframework.boot:spring-boot-starter-data-jpa` (原 2.4.1)
- `org.springframework.boot:spring-boot-starter` (原 2.4.1)
- `org.hibernate:hibernate-core` (原 5.4.25.Final)
- `junit:junit` (原 4.13.1)
- `org.springframework:spring-context` (原 5.3.2)
- `io.vertx:vertx-junit5` (原 4.2.5)
- `com.github.stephenc.jcip:jcip-annotations` (原 1.0-1)

**处理原则**：example 是示例模块，Spring Boot / Hibernate / Spring Context 属于示例专用依赖，不纳入根 POM 集中管理，保留硬编码版本。

| 依赖 | 处理方式 |
|------|----------|
| HikariCP (4.0.3) | 移除版本，由根 POM 管理 |
| gson (2.8.2) | 移除版本，由根 POM 管理 |
| vertx-junit5 (4.2.5) | 移除版本，由根 POM 管理 |
| jcip-annotations (1.0-1) | 移除版本，由根 POM 管理 |
| junit (4.13.1) | 移除版本，由根 POM 管理 |
| spring-boot-test-autoconfigure (2.4.1) | **保留硬编码**（示例专用） |
| spring-boot-starter-data-jpa (2.4.1) | **保留硬编码**（示例专用） |
| spring-boot-starter (2.4.1) | **保留硬编码**（示例专用） |
| hibernate-core (5.4.25.Final) | **保留硬编码**（示例专用） |
| spring-context (5.3.2) | **保留硬编码**（示例专用） |

#### 3.10 assistant/pom.xml

移除 `<version>`：
- `tech.tablesaw:tablesaw-core` (原 0.38.3)
- `org.testfx:testfx-core` (原 4.0.16-alpha)
- `org.testfx:testfx-junit` (原 4.0.16-alpha)
- `org.hibernate:hibernate-validator` (原 5.2.0.Final)

删除整个 `<repositories>` 块（第 70-95 行），由根 POM 统一管理仓库。

### 4. hbt/pom.xml 彻底去重

当前 476 行 → 目标约 150 行。

操作清单：
1. **删除所有重复声明**（保留第一次出现的）：
   - `com.github.sisyphsu:dateparser` (×2→×1)
   - `io.mycat:common` (×2→×1)
   - `org.codehaus.janino:janino` (×2→×1)
   - `org.codehaus.janino:commons-compiler` (×2→×1)
   - `commons-io:commons-io` (×3→×1)
   - `org.apache.commons:commons-lang3` (×3→×1，保留版本最高的)
   - `commons-codec:commons-codec` (×1)
   - `com.fasterxml.jackson.core:jackson-annotations` (×2→×1)
   - `org.apache.calcite.avatica:avatica-core` (×2→×1)
   - `org.apiguardian:apiguardian-api` (×2→×1)
   - `com.esri.geometry:esri-geometry-api` (×2→×1)
   - `com.fasterxml.jackson.core:jackson-core` (×2→×1)
   - `com.jayway.jsonpath:json-path` (×2→×1)
   - `com.fasterxml.jackson.core:jackson-databind` (×2→×1)
   - `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` (×2→×1)
   - `com.google.code.findbugs:jsr305` (×2→×1)
   - `com.google.guava:guava` (×3→×1)
   - `com.yahoo.datasketches:sketches-core` (×2→×1)
   - `net.hydromatic:aggdesigner-algorithm` (×2→×1)
   - `org.apache.commons:commons-dbcp2` (×2→×1)
   - `com.alibaba:druid` (×1)
   - `io.mycat:calcite-core` (×1)
   - `org.apache.calcite:calcite-linq4j` (×1)
   - `org.immutables:value` (×1)
   - `io.reactivex.rxjava3:rxjava` (×1，但实际不在 hbt 中直接使用 — 检查是否需要)

2. **移除所有硬编码版本**，改由根 POM dependencyManagement 管理。

3. **保留 test scope 依赖**（hydromatic 测试数据、hsqldb 等），移除版本。

4. **保留 `<build>` 插件配置**（immutables + lombok annotation processor）。

5. **删除空的 `<repositories>` 块**。

6. **删除注释掉的 slf4j 条目**。

### 5. fastjson → Jackson 代码迁移

#### 5.1 config/src/main/java/io/mycat/util/JsonUtil.java

替换 import：
```java
// 删除
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

// 新增
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
```

重写方法实现：
```java
private static final ObjectMapper MAPPER = new ObjectMapper();

public static <T> T from(String text, Class<T> clazz) {
    try {
        return MAPPER.readValue(text, clazz);
    } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
    }
}

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
```

#### 5.2 hbt/src/main/java/io/mycat/gsi/mapdb/MapDBUtils.java

替换 import 和实现：
```java
// 删除
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.util.TypeUtils;

// 新增
import com.fasterxml.jackson.databind.ObjectMapper;

public class MapDBUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Object cast(Object value, Class javaClass) {
        return MAPPER.convertValue(value, javaClass);
    }
}
```

### 6. 验证策略

按以下顺序验证：
1. `mvn clean compile -DskipTests` — 确认零编译错误
2. 如有因版本升级导致的 API 不兼容（如 Arrow 5→18、SLF4J 1.7→2.0），逐一定向修复
3. `mvn dependency:tree -pl config` — 确认 fastjson 已消除
4. `mvn dependency:tree -pl executor` — 确认 Arrow 统一到 18.1.0
