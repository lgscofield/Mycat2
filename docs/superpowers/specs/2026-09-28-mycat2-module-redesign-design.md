# Mycat2 模块重新设计：JDK 21 + MySQL/MariaDB 双兼容

## 概述

本文档定义了 Mycat2 的模块重新设计方案。核心目标是：

1. **拆解三个 God Module**（common 333 文件、hbt 617 文件、mycat2 218 文件）
2. **以 JDK 21 为第一性约束**（Virtual Thread 兼容、强封装、依赖升级）
3. **MySQL/MariaDB 双协议一等公民**（协议层独立模块，认证插件化）
4. **Fork 源码隔离**（Calcite fork、eddsa fork 独立模块管理）

## 设计原则

| # | 原则 | 含义 |
|---|------|------|
| 1 | 单一职责到模块级 | 每个模块只做一件事，能用一句话描述其职责 |
| 2 | 严格向下依赖 | 依赖图是 DAG，无循环，层间单向流动 |
| 3 | 协议与业务分离 | MySQL/MariaDB 协议编解码独立于 SQL 编译和执行 |
| 4 | 框架补丁就近原则 | Calcite fork、Druid 补丁、Vert.x 补丁跟随其所属框架模块 |

## 版本支持矩阵

### 后端存储节点

| 数据库 | 目标版本 | 默认认证插件 | 协议版本 |
|--------|---------|------------|---------|
| MySQL 5.7.x | 兼容支持 | `mysql_native_password` | HandshakeV10 |
| MySQL 8.0.x | 主力支持 | `caching_sha2_password` | HandshakeV10 |
| MySQL 8.4.x (LTS) | 主力支持 | `caching_sha2_password` | HandshakeV10 |
| MySQL 9.x (Innovation) | 跟踪支持 | `caching_sha2_password` | HandshakeV10 |
| MariaDB 10.6+ (LTS) | 主力支持 | `mysql_native_password` | HandshakeV10 + MariaDB 扩展 |
| MariaDB 11.x | 主力支持 | `mysql_native_password` + ed25519 | HandshakeV10 + MariaDB 扩展 |

### 前端协议（客户端连接到 Mycat2）

| 客户端 | 支持方式 |
|--------|---------|
| MySQL 客户端/驱动 | 原生支持 |
| MariaDB 客户端/驱动 | 协议兼容层支持（MariaDB 客户端可连 MySQL 协议服务器） |

## 新模块架构（18 模块，5 层）

```
Layer 0 (基础层):     config · common (精简后)
                            │
Layer 1 (协议层):     protocol [新建]
                            │
Layer 2 (基础设施):   router · plug · replica · datasource · va · calcite-core [新建]
                            │
Layer 3 (查询流水线): hbt (精简后) · executor (增强后)
                            │
Layer 4 (服务器运行时): server [原 mycat2 重命名] · proxy
                            │
Layer 5 (工具扩展):   seata · migrate · assistant · console · vertx
```

### 各模块职责

| 层 | 模块 | 一句话职责 | 预估文件数 | 状态 |
|---|------|-----------|-----------|------|
| 0 | config | 配置 POJO（数据源、集群、表等） | ~45 | 保持 |
| 0 | common | 核心抽象接口 + 通用工具 | ~185 | 精简 |
| 1 | protocol | MySQL/MariaDB 协议类型、包结构、编解码、认证 | ~105 | 新建 |
| 2 | router | 分片路由算法 | ~30 | 保持 |
| 2 | plug | SPI 扩展点 + 负载均衡 + 序列生成 + 框架补丁 | ~32 | 微调 |
| 2 | replica | 副本管理 + 心跳检测 + 读写分离 | ~20 | 保持 |
| 2 | va | Vert.x 异步数据访问层 | ~10 | 保持 |
| 2 | datasource | JDBC 连接池 + 多后端适配 | ~15 | 保持 |
| 2 | calcite-core | Calcite fork 源码隔离 | ~70 | 新建 |
| 3 | hbt | SQL 编译→关系代数→优化→物理计划 | ~475 | 精简 |
| 3 | executor | Arrow 向量执行 + 148 个 MySQL 函数实现 | ~156 | 增强 |
| 4 | server | 服务器运行时（SQL 处理/命令/监控/认证/配置） | ~250 | 重命名 |
| 4 | proxy | NIO Reactor 网络栈 | ~50 | 保持 |
| 5 | seata/migrate/assistant/console/vertx | 扩展工具 | 各异 | 保持 |

## 依赖 DAG

```
config          → []
common          → [config]
protocol        → [common, config]
router          → [config, common]
va              → [common]
plug            → [router, common]
replica         → [common, plug]
datasource      → [config, common, protocol, plug, replica]
calcite-core    → [] (仅 Calcite 1.26 + eddsa fork，无 io.mycat 依赖)
hbt             → [common, protocol, calcite-core, plug, datasource, config, va]
executor        → [common, protocol, hbt]
proxy           → [common, protocol, plug, router, replica, datasource, va]
server          → [common, protocol, hbt, executor, proxy, datasource, va, plug]
seata           → [server]
migrate         → [datasource]
assistant       → [server]
console         → []
vertx           → []
```

### 与当前依赖的关键变化

| 变化 | 当前 | 新设计 | 原因 |
|------|------|--------|------|
| common 不含 MySQL 类型 | `beans/mysql/*` 在 common | 移入 protocol | 协议与核心分离 |
| hbt 不直接依赖 Calcite 内部 | fork 混在 hbt 里 | 通过 calcite-core 间接 | fork 隔离 |
| executor 有实质内容 | 仅 ~8 文件 | 接收 148 个函数 | 执行层有深度 |
| server 显式依赖 protocol | mycat2 用 common 中的类型 | 显式依赖 | 协议层显式化 |
| datasource 依赖 protocol | 不依赖协议层 | 依赖 protocol | 后端连接需理解协议类型 |

## God Module 拆解详细方案

### 一、common（333 → ~185 文件）

#### 保留（~185 文件）

| 包 | 文件数 | 说明 |
|---|--------|------|
| `io/mycat/`（顶层接口） | ~75 | MycatServer、MycatDataContext、MycatConnection、TransactionSession、ConnectionManager、TableHandler、MetaClusterCurrent、Authenticator、LockService |
| `io/mycat/api/` | 28 | RowBaseIterator、ResultSetCollector、MySQLAPI、MysqlPayloadObject |
| `io/mycat/beans/mycat/` | 18 | MycatRowMetaData、MycatDataType、MycatField、TransactionType |
| `io/mycat/beans/resultset/` | 15 | MycatResultSet、MycatResponse、ResultSetWriter |
| `io/mycat/newquery/` | 11 | NewMycatConnection（接口 + 4 实现）、RowSet、SqlResult |
| `io/mycat/router/` | 4 | ShardingTableHandler、CustomRuleFunction |
| `io/mycat/replica/` | 5 | PhysicsInstance、BalanceType、InstanceType |
| `io/mycat/util/` | ~30 | 纯工具类（StringUtil、ByteUtil、CharsetUtil、DateUtil 等） |

#### 移出 → protocol（~95 文件）

| 包 | 文件数 |
|---|--------|
| `io/mycat/beans/mysql/` | 28 |
| `io/mycat/beans/mysql/charset/` | 4 |
| `io/mycat/beans/mysql/packet/` | 17 |
| `io/mycat/swapbuffer/` | 10 |
| `io/vertx/mysqlclient/impl/codec/` | 4 |
| MySQL 特定工具（SecurityUtil、MySQLUtil、PacketUtil） | ~10 |
| `io/mycat/util/packet/` | 8 |
| 新增协议抽象（ProtocolCodec、AuthPlugin、CapabilityFlags、ServerStatus） | ~5 |
| 新增 eddsa fork 源码 | ~10 |

#### 移出 → server（~55 文件）

| 包 | 文件数 |
|---|--------|
| `io/mycat/hint/` | 48 |
| `io/mycat/ratelimiter/` | 4 |
| `io/mycat/sequencemodifier/` | 2 |
| `io/mycat/sql/` | 1 |

#### 移出 → plug（1 文件）

| 包 | 文件数 |
|---|--------|
| `org/apache/doris/` (PatternMatcher) | 1 |

### 二、hbt（617 → ~475 文件）

#### 保留（~475 文件）

| 包 | 文件数 | 说明 |
|---|--------|------|
| `io/mycat/calcite/`（减去 sqlfunction） | 247 | SQL 编译/优化/物理计划核心 |
| `io/mycat/hbt/` | 60 | HBT 查询语言 |
| `io/mycat/`（根） | 17 | DRDS 编译入口 |
| `io/mycat/connectionschedule/` | 6 | 连接调度 |
| `io/mycat/gsi/` | 6 | 全局二级索引 |
| `io/mycat/nosql/` | 7 | NoSQL API |
| `io/mycat/querycondition/` | 13 | 查询条件分析 |
| `io/mycat/serializable/` | 9 | 序列化 |
| `io/mycat/taskmanager/` | 2 | 任务管理 |
| `io/mycat/util/` | 9 | SQL/Calcite 工具 |
| `io/mycat/vertx/` | 8 | Vert.x 执行桥接 |
| `io/mycat/statistic/` | 1 | 统计信息中心 |
| `com/alibaba/druid/`（parser 扩展） | 5 | Druid SQL 解析器扩展 |

#### 移出 → calcite-core（~70 文件）

| 包 | 文件数 |
|---|--------|
| `org/apache/calcite/`（全部） | 68 |

关键大文件：SqlFunctions.java (96KB)、RexBuilder.java (61KB)、SqlLiteral.java (50KB)、MycatBuiltInMethodImpl.java (48KB)

calcite-core 模块设计：
- 包名保持 `org.apache.calcite.*` 不变
- 独立 pom.xml，依赖 Calcite 1.26.0 作为 provided scope
- hbt 通过 `io.mycat:calcite-core` 依赖引入

#### 移出 → executor（~148 文件）

| 包 | 文件数 |
|---|--------|
| `io/mycat/calcite/sqlfunction/datefunction/` | 64 |
| `io/mycat/calcite/sqlfunction/stringfunction/` | 63 |
| `io/mycat/calcite/sqlfunction/infofunction/` | 11 |
| `io/mycat/calcite/sqlfunction/mathfunction/` | 5 |
| `io/mycat/calcite/sqlfunction/`（其他） | 5 |

#### 移出 → server（~11 文件）

| 包 | 文件数 |
|---|--------|
| `io/mycat/prototypeserver/` | 11 |

### 三、mycat2 → server（218 → ~250 文件）

#### 重命名为 server，调整内容

**保留/接收：**

| 包 | 文件数 | 来源 |
|---|--------|------|
| `io/mycat/MycatCore.java` | 1 | 原有 |
| `io/mycat/sqlhandler/` | 85 | 原有 |
| `io/mycat/commands/` | 21 | 原有 |
| `io/mycat/vertx/` | 11 | 原有 |
| `io/mycat/mycatmysql/` | 3 | 原有 |
| `io/mycat/vertxmycat/` | 19 | 原有 |
| `io/mycat/mysqlclient/` | 17 | 原有 |
| `io/mycat/config/` | 10 | 原有 |
| `io/mycat/connection/` | 10 | 原有 |
| `io/mycat/exporter/` | 14 | 原有 |
| `io/mycat/monitor/` | 11 | 原有 |
| `io/mycat/executor/` | 1 | 原有 |
| `io/mycat/hint/` | 48 | 从 common 移入 |
| `io/mycat/ratelimiter/` | 4 | 从 common 移入 |
| `io/mycat/sequencemodifier/` | 2 | 从 common 移入 |
| `io/mycat/prototypeserver/` | 11 | 从 hbt 移入 |

**移出：**

| 包 | 文件数 | 目标 |
|---|--------|------|
| `io/vertx/` 补丁 | 9 | → vertx 模块 |
| `com/alibaba/druid/` 补丁 | 7 | → plug 模块 |
| `io/mycat/plug/sequence/` | 2 | → plug 模块 |

## 文件迁移总账

| 迁移路径 | 文件数 | 说明 |
|----------|--------|------|
| common → protocol | ~95 | MySQL/MariaDB 协议类型+包+编解码 |
| common → server | ~55 | Hint 命令+限流+序列 |
| common → plug | ~1 | Doris fork |
| hbt → calcite-core | ~70 | Calcite fork 源码隔离 |
| hbt → executor | ~148 | MySQL 函数实现 |
| hbt → server | ~11 | 原型服务器 |
| mycat2 → vertx | ~9 | Vert.x 框架补丁 |
| mycat2 → plug | ~9 | Druid 补丁 + 序列生成器 |
| 新增 eddsa fork → protocol | ~10 | ed25519 认证库 fork |
| **总迁移量** | **~408** | **占 God Module 总量 ~35%** |

## protocol 模块双协议设计

### 核心接口

```
io.mycat.protocol.mysql.packet    — MySQL 包结构 (HandshakeV10, Auth, OK, ERR, EOF, ColumnDef, Row)
io.mycat.protocol.mysql.type      — MySQL 类型系统 (MySQLType, MySQLVariables, MySQLErrorCode, ServerVersion)
io.mycat.protocol.mysql.charset   — 字符集映射 (MySQLCharset, MySQLCollation, MySQLCollationIndex)
io.mycat.protocol.mysql.capability— 能力标志位 (CapabilityFlags, ServerStatus)
io.mycat.protocol.mysql.auth      — 认证插件 (AuthPlugin + 多实现)
io.mycat.protocol.codec           — 编解码器 (ProtocolCodec, PacketDecoder, PacketEncoder)
io.mycat.protocol.swapbuffer      — 底层协议写入 (SwapBuffer, MySQLSwapbufferBuilder)
```

### MySQL vs MariaDB 协议差异处理

| 差异维度 | MySQL 8.x | MariaDB 10.6+ | protocol 处理方式 |
|---------|-----------|---------------|------------------|
| 默认认证 | `caching_sha2_password` | `mysql_native_password` + ed25519 | `AuthPlugin` 接口多实现 |
| 能力标志 | `CLIENT_MULTI_STATEMENTS` | `MARIADB_CLIENT_*` 扩展位 | `CapabilityFlags` 统一枚举 |
| 数据类型 | `MYSQL_TYPE_VECTOR`(8.4) | 无 | `MySQLType` 枚举版本感知 |
| 字符集 | utf8mb4 默认 | utf8mb4 + 额外排序规则 | `MySQLCollationIndex` 联合映射表 |
| 握手协议 | HandshakeV10 | HandshakeV10 + MariaDB 扩展 | `ProtocolCodec` 自动探测 |

### AuthPlugin 接缝（MariaDB 扩展关键）

```java
public interface AuthPlugin {
    /** 插件名称，如 "mysql_native_password", "caching_sha2_password", "client_ed25519" */
    String name();

    /** 生成认证响应数据 */
    byte[] generateAuthentication(byte[] password, byte[] seed);

    /** 验证客户端认证数据（服务端使用） */
    boolean verifyAuthentication(byte[] authData, byte[] password, byte[] seed);
}
```

## calcite-core 模块 Fork 隔离策略

### 版本号

| 模块 | 版本号 | 含义 |
|------|--------|------|
| `io.mycat:calcite-core` | **1.42.0-mycat** | 基于 Calcite 1.42.0 的 Mycat 定制版 |
| eddsa fork（内嵌于 protocol） | **0.3.0-mycat** | 基于 eddsa 0.3.0 的 fork |

### 依赖策略（二选一，禁止同时依赖 fork 和原版）

| 模块 | 依赖策略 | 说明 |
|------|---------|------|
| hbt | `io.mycat:calcite-core:1.42.0-mycat`（**排除** `org.apache.calcite:calcite-core`） | fork 完全替代原版 |
| executor | 通过 hbt 传递依赖 | 不直接引入 calcite |
| calcite-linq4j | 继续依赖原版 `org.apache.calcite:calcite-linq4j:1.42.0` | linq4j 无 fork |
| avatica-core | 继续依赖原版 `org.apache.calcite.avatica:avatica-core:1.26.0` | avatica 无 fork |
| protocol | eddsa fork 源码内嵌，**不引入** Maven 依赖 | fork 源码直接编译 |

### pom.xml 示例

```xml
<!-- calcite-core 模块的 pom.xml -->
<groupId>io.mycat</groupId>
<artifactId>calcite-core</artifactId>
<version>1.42.0-mycat</version>
<dependencies>
    <dependency>
        <groupId>org.apache.calcite</groupId>
        <artifactId>calcite-core</artifactId>
        <version>1.42.0</version>
        <scope>provided</scope>  <!-- 编译参考，不传递 -->
    </dependency>
</dependencies>

<!-- hbt 模块的 pom.xml -->
<dependency>
    <groupId>io.mycat</groupId>
    <artifactId>calcite-core</artifactId>
    <version>1.42.0-mycat</version>
</dependency>
<dependency>
    <groupId>org.apache.calcite</groupId>
    <artifactId>calcite-linq4j</artifactId>
    <version>1.42.0</version>  <!-- 原版，无 fork -->
</dependency>
```

### Calcite 1.26.0 → 1.42.0 升级策略

Calcite 1.26→1.42 跨度较大（~5 年发布周期），61 个 fork 文件需重新 merge：

| 步骤 | 说明 |
|------|------|
| 1. 创建 calcite-core 模块，引入 Calcite 1.42.0 作为 provided 依赖 | 新模块可编译 |
| 2. 逐个将 61 个 fork 文件从 hbt 复制到 calcite-core，对照 Calcite 1.42.0 源码 merge 差异 | 每个文件 merge 后编译验证 |
| 3. hbt 切换依赖为 `io.mycat:calcite-core:1.42.0-mycat`，删除旧 fork 文件 | 全量编译通过 |

重点关注 API 变更：
- `RelNode` 接口可能新增方法
- `RexBuilder` 签名变更
- 优化器框架 (`HepPlanner`, `VolcanoPlanner`) API 调整
- `SqlFunctions` 运行时函数新增/删除

### 废弃模块清理

| 目录 | 当前状态 | 处理 |
|------|---------|------|
| `calcite/` | 废弃空壳（仅 pom.xml，版本 1.15-dev，未参与构建） | **删除** |
| `linq4j/` | 废弃空壳（仅 pom.xml，版本 1.13-realase，未参与构建） | **删除** |

## JDK 21 依赖升级矩阵

### 完整版本号清单（全部升级到最新稳定版）

#### 编译阻断项（不升级则 JDK 21 无法编译）

| 依赖 | 当前 | 目标 | 原因 |
|------|------|------|------|
| maven-compiler-plugin | 2.3.2 | **3.14.1** | 不支持 release 21 |
| lombok | 1.18.8 | **1.18.42** | <1.18.30 JDK 21 编译失败 |
| janino | 3.0.11/3.1.0 | **3.1.12** | 旧版无法处理 JDK 21 字节码 |
| jakarta.annotation-api | 无 | **2.1.1**（新增） | javax.annotation 已从 JDK 11 移除 |
| arrow-* | 5.0.0 | **18.1.0** | 内部使用 sun.misc.Unsafe |

#### 安全关键项

| 依赖 | 当前 | 目标 |
|------|------|------|
| jackson-* | 2.10.0 | **2.18.4** |
| logback-* | 1.2.3 | **1.5.18** |
| netty-* | 4.1.74 | **4.1.119** |
| guava | 31.0.1 | **33.4.8-jre** |

#### 核心框架升级项

| 依赖 | 当前 | 目标 | 说明 |
|------|------|------|------|
| **calcite-core** | **1.26.0** | **1.42.0** | **最新版（2026.03），JDK 21 原生支持，61 个 fork 文件需重新 merge** |
| **calcite-linq4j** | **1.26.0** | **1.42.0** | 随 calcite-core 同步升级 |
| **avatica-core** | **1.17.0** | **1.26.0** | 与 Calcite 1.42.0 配套 |
| **druid** | **1.2.6** | **1.2.28** | 连接池 VT 兼容 + 大量 Bug 修复 |
| vertx-* | 4.2.5 | **4.5.14** | JDK 21 Virtual Thread 支持 |
| rxjava | 3.0.6 | **3.1.10** | 性能改进 |

#### 功能升级项

| 依赖 | 当前 | 目标 | 说明 |
|------|------|------|------|
| mysql-connector-java → mysql-connector-j | 8.0.25 | **9.3.0** | MySQL 9.x 支持 + artifactId 变更 |
| mariadb-java-client | 无 | **3.5.3**（新增） | MariaDB 后端支持 |
| kafka-clients | 2.7.2 | **4.0.0** | 安全修复 |
| clickhouse-jdbc | 0.3.2 | **0.7.3** | 新协议支持 |
| oshi-core | 5.8.5 | **6.8.2** | JDK 21 兼容 |
| ehcache | 2.10.6 | **3.10.8** | JDK 21 兼容 |

#### 测试框架升级项

| 依赖 | 当前 | 目标 | 说明 |
|------|------|------|------|
| JUnit | 4.13.1 | **5.12.2** | JUnit 5 支持 |
| Mockito | 注释掉 | **5.16.1**（引入） | 单元测试必需 |
| AssertJ | 无 | **3.27.3**（引入） | 流式断言 |
| TestContainers | 无 | **1.21.0**（引入） | 容器化集成测试 |

### JDK 21 P0 代码修复项

| 文件 | 问题 | 修复 |
|------|------|------|
| `MycatDirectByteBufferPool.java` | `import sun.nio.ch.DirectBuffer` | 替换为 `java.lang.foreign.MemorySegment` 或 VarHandle |
| `HTTPServer.java` | `import com.sun.net.httpserver.*` | 替换为 Vert.x HttpServer 或 JDK 标准 `jdk.httpserver` |
| `NewMycatConnectionImpl.java` | 6 个 synchronized + 阻塞 JDBC | synchronized → ReentrantLock；JDBC → Virtual Thread 友好 |
| ~30+ 文件 | synchronized 导致 VT pinning | 逐文件替换为 ReentrantLock |
| `IOExecutor.java` | CachedThreadPool | 替换为 `Executors.newVirtualThreadPerTaskExecutor()` |

## 实施路线图（4 阶段，精细化原子任务）

### 防遗漏方法论

```
Phase（阶段）
 └── Milestone（里程碑，每个有 Gate 验收条件）
      └── Atomic Task（原子任务，每个做完后项目仍可编译）
           └── Checklist（检查清单，确保无遗漏）
```

| 规则 | 含义 |
|------|------|
| **每步可编译** | 每个原子任务完成后，`mvn compile` 必须通过 |
| **级联显式化** | 每次文件移动/重命名，用 `grep` 扫描所有引用方，列出级联清单 |
| **Gate 可机器验证** | 每个里程碑的验收条件是可执行命令，不是人工判断 |
| **每任务一 commit** | 每个原子任务一个 git commit，方便回滚 |

### 依赖升级级联影响总表

| 依赖升级 | 影响文件数 | 风险等级 | 关键风险点 |
|----------|-----------|---------|----------|
| **Calcite 1.26→1.42（新增）** | **61 fork + ~20 引用** | **极高** | 61 个 fork 文件需重新 merge，RelNode/RexBuilder/SqlFunctions API 变更 |
| Vert.x 4.2.5→4.5.14 | ~80+ | 极高 | 大量使用 `impl.*` 内部 API |
| Arrow 5.0→18.1 | ~38+ | 高 | VectorSchemaRoot 包路径变更 + allocator API 签名变更 |
| Druid 1.2.6→1.2.28 | ~15 | 中 | 连接池 API 微调，StatFilter 变更 |
| RxJava 3 使用面 | ~58+ | 中 | 若迁移 Observable→Flowable 则工作量巨大 |
| Guava 31→33.4 | ~62+ | 中低 | `UnsignedLong` 废弃（1 文件） |
| javax→jakarta | ~12 生产 + 40 测试 | 中高 | validation + annotation 全量替换 |
| mysql-connector 重命名 | 5 pom + 17 java | 低 | 仅 pom.xml 变更 |
| Jackson 2.10→2.18 | 1 | 极低 | 仅 1 个文件 |

---

### Phase 0: JDK 21 编译通过（预估 2-3 周）

#### Milestone 0.1: 构建工具升级
Gate: `mvn help:effective-pom` 输出 JDK 21 target

| # | 原子任务 | 涉及文件 | 验收标准 |
|---|---------|---------|----------|
| 0.1.1 | 更新 maven-compiler-plugin → 3.14.1，设 `<release>21</release>` | `pom.xml` | effective-pom 显示 release=21 |
| 0.1.2 | 添加 `<dependencyManagement>` 集中管理所有版本号 | `pom.xml` | 子模块不再硬编码版本号 |
| 0.1.3 | 更新 lombok → 1.18.42 | `pom.xml` | `mvn compile -pl config` 通过 |

#### Milestone 0.2: P0 编译阻断修复
Gate: `mvn compile -pl common,assistant` 通过

| # | 原子任务 | 涉及文件 | 改法 | 验收标准 |
|---|---------|---------|------|----------|
| 0.2.1 | 替换 `sun.nio.ch.DirectBuffer` | `MycatDirectByteBufferPool.java` (2 处) | `VarHandle` 或 `--add-exports` | compile 通过 |
| 0.2.2 | 替换 `com.sun.glass.events` | `assistant/UIMain.java` (1 处) | 改用 `javafx.stage.WindowEvent` | compile 通过 |

#### Milestone 0.3: P1 运行时阻断修复
Gate: 代码审查通过 + 启动 smoke test 不抛异常

| # | 原子任务 | 涉及文件 | 改法 |
|---|---------|---------|------|
| 0.3.1 | 替换 `Thread.stop()` | `IOExecutor.java` (L52,55) | `thread.interrupt()` + 协作取消 |
| 0.3.2 | 移除 `SecurityManager` | `NameableThreadFactory.java` (L15-16) | `Thread.currentThread().getThreadGroup()` |

#### Milestone 0.4: 核心依赖版本升级（按风险从低到高）
Gate: 每次升级后 `mvn compile` 全项目通过

| # | 原子任务 | 影响文件 | 关键改法 |
|---|---------|---------|----------|
| 0.4.1 | 升级 jackson 2.10→2.18 | 1 | 无 API 变更 |
| 0.4.2 | 升级 guava 31→33.4 | ~62 | `UnsignedLong`→`Long`（1 文件） |
| 0.4.3 | mysql-connector-java → mysql-connector-j | 5 pom | 仅改 artifactId/groupId |
| 0.4.4 | 升级 logback 1.2→1.5 | pom | SLF4J 2.x 配套升级 |
| 0.4.5 | 升级 netty 4.1.74→4.1.119 | pom | 验证 NIO reactor 无变更 |
| 0.4.6 | **升级 Arrow 5.0→18.1** | **~38** | **VectorSchemaRoot 包路径变更 + allocator API 签名变更，逐文件修复** |
| 0.4.7 | 升级 Janino 3.0→3.1.12 | pom + hbt | 验证代码生成兼容性 |
| 0.4.8 | javax→jakarta 迁移 | ~12 生产 + 40 测试 | 全量替换 import |
| 0.4.9 | **升级 Vert.x 4.2.5→4.5.14（最高风险）** | **~80+** | 见下方子任务分解 |

#### Milestone 0.4.9 子任务：Vert.x 内部 API 迁移

| # | 子任务 | 受影响文件 | 改法 |
|---|-------|-----------|------|
| 0.4.9a | `PromiseInternal` → `Promise` | IOExecutor, MySQLDatasourcePool, MySQLPacketExchanger, MycatSessionWriteHandler, MySQLClientSession, MySQLProxyServerSession, MySQLServerSession, MycatSession, MySQLTaskUtil, AsyncMycatDataContextImpl **(10 文件)** | 替换为公开 API `Promise` |
| 0.4.9b | `PromiseImpl` → `Promise` | MySQLProxyServerSession **(1 文件)** | 同上 |
| 0.4.9c | `impl.logging.Logger` → SLF4J | EmbeddedZKServer, AbstractMySQLManagerImpl, ImmutableCoordinatorLog, ImmutableParticipantLog, AbstractXaSqlConnection, OnePhaseXaSqlConnection, XaLogImpl **(7 文件)** | `LoggerFactory.getLogger()` |
| 0.4.9d | `impl.ConcurrentHashSet` → JDK | XaLogImpl **(1 文件)** | `ConcurrentHashMap.newKeySet()` |
| 0.4.9e | `spi.metrics.*` 适配 | IOExecutor **(1 文件)** | 评估移除或适配新 SPI |
| 0.4.9f | `mysqlclient.impl.*` codec | vertx 模块 4 文件 + BaseRowObservable, EmitterObjectsCollector, MycatDataType, MycatMySQLRowMetaData **(8 文件)** | 评估 Vert.x 4.5 公开替代 API |
| 0.4.9g | `VertxImpl` 内部类型 | IOExecutor **(1 文件)** | 改用公开 API |

#### Milestone 0.5: 基础设施升级
Gate: Docker build 成功 + smoke test 通过

| # | 原子任务 | 涉及文件 | 验收标准 |
|---|---------|---------|----------|
| 0.5.1 | 替换 Dockerfile 基础镜像 | `mycat2/Dockerfile` | `docker build` 成功 |
| 0.5.2 | 全量编译验证 | 全项目 | `mvn compile -DskipTests` 零错误 |
| 0.5.3 | 启动 smoke test | - | Mycat2 能启动并接受 MySQL 连接 |

---

### Phase 1: 模块重构（预估 3-4 周）

#### Milestone 1.1: 创建 protocol 模块（~95 文件迁入）
Gate: `mvn compile` 全项目通过

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 1.1.1 | 创建 `protocol/` 目录 + `pom.xml` | `mvn validate -pl protocol` |
| 1.1.2 | 移动 `beans/mysql/` (28) + `beans/mysql/charset/` (4) + `beans/mysql/packet/` (17) | `mvn compile -pl protocol` |
| 1.1.3 | 移动 `swapbuffer/` (10) + `util/packet/` (8) | 同上 |
| 1.1.4 | 移动 Vert.x codec 补丁 (4) + MySQL 工具类 (~10) | 同上 |
| 1.1.5 | **级联 import 更新**：`grep -rn` 扫描所有旧包路径，逐文件更新引用 | `mvn compile` **全项目**通过 |
| 1.1.6 | 创建新协议抽象接口（ProtocolCodec, AuthPlugin, CapabilityFlags, ServerStatus） | 接口定义 + 单元测试骨架 |
| 1.1.7 | 删除 common 中的旧文件 | `mvn compile` 全项目通过 |

#### Milestone 1.2: 创建 calcite-core 模块 + Calcite 1.42.0 升级
Gate: `mvn compile` 全项目通过

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 1.2.1 | 创建 `calcite-core/` 目录 + `pom.xml`（版本 1.42.0-mycat） | `mvn validate -pl calcite-core` |
| 1.2.2 | 逐个将 61 个 fork 文件从 hbt 复制到 calcite-core，对照 Calcite 1.42.0 源码 merge 差异 | `mvn compile -pl calcite-core` |
| 1.2.3 | 更新 hbt `pom.xml`：移除 `org.apache.calcite:calcite-core:1.26.0`，添加 `io.mycat:calcite-core:1.42.0-mycat` | `mvn compile -pl hbt` |
| 1.2.4 | 升级 calcite-linq4j 到 1.42.0 + avatica-core 到 1.26.0 | `mvn compile -pl hbt` |
| 1.2.5 | 删除 hbt 中的旧 fork 文件 | `mvn compile` 全项目通过 |
| 1.2.6 | 删除废弃的 `calcite/` 和 `linq4j/` 目录 | 编译不受影响 |

#### Milestone 1.3: mycat2 → server 重命名（调整 ~73 文件）
Gate: `mvn compile` 全项目通过

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 1.3.1 | 重命名 `mycat2/` → `server/`，更新 pom.xml artifactId + shade 插件配置 | `mvn validate -pl server` |
| 1.3.2 | 从 common 移入 `hint/` (48) + `ratelimiter/` (4) + `sequencemodifier/` (2) + `sql/` (1) | `mvn compile -pl server` |
| 1.3.3 | 从 hbt 移入 `prototypeserver/` (11) | 同上 |
| 1.3.4 | 移出 Vert.x 补丁 (9) → vertx 模块，Druid 补丁 (7) + sequence (2) → plug | 同上 |
| 1.3.5 | 级联 import 更新 + 全项目编译 | `mvn compile` 全项目通过 |

#### Milestone 1.4: 函数迁移到 executor（148 文件迁入）
Gate: `mvn compile` 全项目通过

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 1.4.1 | 移动 `sqlfunction/datefunction/` (64) → executor | `mvn compile -pl executor` |
| 1.4.2 | 移动 `sqlfunction/stringfunction/` (63) → executor | 同上 |
| 1.4.3 | 移动 `sqlfunction/infofunction/` (11) + `mathfunction/` (5) + 其他 (5) → executor | 同上 |
| 1.4.4 | 更新 hbt 对 sqlfunction 的引用 | `mvn compile -pl hbt` |
| 1.4.5 | 全项目编译验证 | `mvn compile` 全项目通过 |

---

### Phase 2: Virtual Thread 迁移（预估 2-3 周）

#### Milestone 2.1: synchronized → ReentrantLock（55 文件，146 处）
Gate: 无 carrier thread pinning 警告

按风险等级分批处理：

| # | 批次 | 文件数 | 优先处理文件 |
|---|------|--------|------------|
| 2.1.1 | 高风险：阻塞 JDBC + synchronized | 4 | NewMycatConnectionImpl (6处), SyncMessageReader (6处), XaLogImpl (6处), FutureNewMycatConnectionImpl (5处) |
| 2.1.2 | 高风险：连接池 + synchronized | 4 | VertxMycatConnectionPool (7处), VertxPoolConnectionImpl (5处), ReplicaDataSourceSelector (8处), DbPlanManagerPersistorImpl (13处) |
| 2.1.3 | 中风险：Future 实现 | 2 | FutureImpl (9处), VertxConnection (4处) |
| 2.1.4 | 低风险：工具类/缓存 | 45 | 逐个替换，每个替换后编译验证 |

#### Milestone 2.2: 执行器迁移
Gate: 功能测试通过 + 无 VT pinning

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 2.2.1 | IOExecutor → `newVirtualThreadPerTaskExecutor()` | 编译 + 功能测试通过 |
| 2.2.2 | Druid 连接池 VT 兼容性验证 | 连接池压测无 pinning |
| 2.2.3 | MySQL JDBC 驱动 VT pinning 评估 + 缓解方案 | 评估报告 |

---

### Phase 3: MariaDB 接入（预估 2-3 周）

#### Milestone 3.1: 认证插件
Gate: MariaDB 客户端能连接到 Mycat2

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 3.1.1 | fork eddsa 源码到 protocol 模块 | `mvn compile -pl protocol` |
| 3.1.2 | 实现 `Ed25519AuthPlugin` | MariaDB ed25519 认证通过 |
| 3.1.3 | 实现 `CachingSha2AuthPlugin` 服务端验证 | MySQL 8.x 认证通过 |

#### Milestone 3.2: 协议兼容
Gate: MariaDB 10.6/11.x 全量 CRUD 通过

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 3.2.1 | 实现 `MariaDBProtocolCodec`（能力标志位探测） | MariaDB 握手成功 |
| 3.2.2 | 扩展 `MySQLCollationIndex` 联合映射表 | 字符集协商正确 |
| 3.2.3 | datasource 模块新增 MariaDB 后端类型 | MariaDB 后端连接成功 |

#### Milestone 3.3: 集成测试
Gate: 全量回归通过

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 3.3.1 | MariaDB 10.6 CRUD + 事务 + 分片查询 | 功能测试通过 |
| 3.3.2 | MariaDB 11.x 同上 | 功能测试通过 |
| 3.3.3 | 协议兼容性测试套件（TCK） | MySQL + MariaDB 混合场景通过 |

---

### 每个原子任务的执行检查清单

```
□ 1. 确认目标文件存在且路径正确
□ 2. grep 扫描所有引用方（旧 import / 旧包路径）
□ 3. 列出完整的级联修改清单
□ 4. 执行修改
□ 5. mvn compile（当前模块 + 依赖此模块的模块）
□ 6. 如发现编译错误，记录为新的子任务
□ 7. git commit（每个原子任务一个 commit，方便回滚）
```

## 测试策略

### 当前测试现状

| 指标 | 现状 |
|------|------|
| 整体测试覆盖率 | ~6.2%（130 个测试文件 / 2105 个源文件） |
| 零测试核心模块 | proxy(92文件)、config(59)、datasource(32)、replica(21)、plug(19) |
| executor 测试 | 实质为空（3 个文件中 1 个全注释、1 个 @Test 注释） |
| Mockito | 依赖被注释掉，仅 1 个文件使用 |
| 测试类型 | 绝大多数是需要运行中 MySQL 的集成测试，CI/CD 无法执行 |
| 测试框架 | JUnit 4.13.1，无 failsafe 插件 |

### 测试金字塔（三层）

```
        ┌─────────┐
        │ E2E 测试 │  ← 需要完整 Mycat2 + MySQL/MariaDB 实例
        │ (example) │     保留现有集成测试，用 @Category 标记
        ├───────────┤
        │ 集成测试   │  ← 需要部分基础设施（Mock 数据源/内存数据库）
        │ (failsafe) │    maven-failsafe-plugin 管理
        ├─────────────┤
        │  单元测试    │  ← 纯逻辑测试，无外部依赖
        │  (surefire)  │   maven-surefire-plugin，CI 必须全绿
        └─────────────┘
```

### 红绿规则（TDD 纪律）

| 规则 | 含义 |
|------|------|
| **先红后绿** | 任何功能修改/新增，先写失败的测试（红），再写实现使其通过（绿） |
| **重构不改变行为** | 重构时测试必须保持绿色，如果变红说明行为改变了 |
| **迁移保护** | 模块迁移前后，同一功能的测试结果必须一致（书面用例对照） |
| **CI 门控** | `mvn test`（单元测试）必须全绿才能 merge，集成测试可标记 `@Disabled` 但需记录原因 |
| **禁止注释 @Test** | 禁止通过注释 `@Test` 注解来跳过失败测试，必须用 `@Disabled("原因")` |

### 各模块测试要求

| 模块 | 测试类型 | 优先级 | 预估新增测试 | 说明 |
|------|---------|--------|-------------|------|
| **protocol** | 单元测试 | P0 | ~15 | 包编解码、认证插件、字符集映射（纯逻辑） |
| **calcite-core** | 单元测试 | P0 | ~10 | Calcite fork 类的行为回归测试 |
| **executor** | 单元测试 | P0 | ~20 | 物理计划、SQL 函数（148 函数至少各 1 个测试） |
| **router** | 保持现有 | - | 0 | 已有 9 个质量较好的单元测试 |
| **plug** | 单元测试 | P1 | ~5 | 负载均衡算法、序列生成器 |
| **replica** | 单元测试 | P1 | ~8 | 心跳逻辑、副本切换、读写分离决策（Mock 数据源） |
| **datasource** | 单元测试 | P1 | ~5 | 连接池配置、多后端适配逻辑 |
| **proxy** | 单元 + 集成 | P1 | ~10 | NIO 协议解码（Mock Socket）、命令分发 |
| **server** | 集成测试 | P2 | ~10 | SQL Handler 路由、Hint 命令、监控指标 |
| **hbt** | 补充 | P2 | ~2 | 补充编译链测试，保留现有 ColumnMappingTest |
| **config** | **豁免** | - | 0 | 纯 POJO |
| **common** | **豁免** | - | 0 | 纯接口和工具类 |
| **总计** | | | **~85 新增** | 目标覆盖率 >15% |

### 书面测试用例文档（TEST-CASES.md）

每个有测试的模块维护 `src/test/resources/TEST-CASES.md`，作为永久参考文档：

```markdown
# protocol 模块测试用例

## TC-PROTOCOL-001: MySQL HandshakeV10 包编码
- **前置条件**: 构造标准 HandshakeV10 包
- **操作**: ProtocolCodec.encode()
- **预期结果**: 输出字节流与 MySQL 8.0 抓包一致
- **覆盖场景**: MySQL 8.0、MySQL 8.4、MariaDB 10.6
- **关联测试类**: HandshakeCodecTest

## TC-PROTOCOL-002: caching_sha2_password 认证
- **前置条件**: 服务端 seed = [已知值]
- **操作**: CachingSha2AuthPlugin.generateAuthentication()
- **预期结果**: 输出与 MySQL 8.0 服务端验证一致
- **回归保护**: 模块迁移前后结果必须一致
```

### 测试框架配置升级

| 配置项 | 当前 | 目标 | 原因 |
|--------|------|------|------|
| JUnit | 4.13.1 | **5.12.2** | JUnit 5 支持 `@ParameterizedTest`、`@Nested`、扩展模型 |
| Mockito | 注释掉 | **5.16.1**（引入） | 单元测试必需，隔离外部依赖 |
| AssertJ | 无 | **3.27.3**（引入） | 流式断言，测试可读性 |
| maven-surefire-plugin | 3.0.0-M5 | **3.5.3** | 支持 JUnit 5 |
| maven-failsafe-plugin | 无 | **3.5.3**（引入） | 分离集成测试 |
| TestContainers | 无 | **1.21.0**（引入） | MariaDB/MySQL 容器化集成测试 |

### 测试命名和组织约定

```
src/test/java/
  io/mycat/protocol/
    HandshakeCodecTest.java              ← 单元测试（Surefire 执行）
    ProtocolCodecIT.java                 ← 集成测试（Failsafe 执行，*IT.java 后缀）

src/test/resources/
  TEST-CASES.md                          ← 书面测试用例文档
  fixtures/                              ← 测试数据（抓包数据、SQL 样本）
```

### 测试与路线图的结合

| 阶段 | 测试任务 |
|------|----------|
| Phase 0 每个 Milestone | 升级后运行 `mvn test`，确保现有测试全绿 |
| Phase 1 每次模块迁移 | 迁移前记录测试结果 → 迁移后对比结果 → 记录到 TEST-CASES.md |
| Phase 2 synchronized→ReentrantLock | 每个文件替换后运行对应模块的单元测试 |
| Phase 3 MariaDB 接入 | 新增 ~15 个协议测试 + TestContainers MariaDB 集成测试 |

---

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| **Calcite 1.26→1.42 fork 重新 merge** | **极高** | **极高** | 61 个文件逐个 merge，RelNode/RexBuilder/SqlFunctions API 变更，每个文件编译验证 |
| **Vert.x 内部 API 不兼容** | **极高** | **极高** | 80+ 文件使用 impl.* 内部 API，升级前逐一确认公开替代方案 |
| Arrow API 包路径变更 | 高 | 高 | 38+ 文件受影响，逐文件修复 + 编译验证 |
| Druid 1.2.6→1.2.28 API 变更 | 中 | 中 | 连接池 API 微调，StatFilter 变更 |
| 模块迁移导致循环依赖 | 中 | 高 | 严格 DAG 约束 + Maven Enforcer 插件 |
| MariaDB 协议差异遗漏 | 中 | 高 | 协议兼容性测试套件（TCK） |
| synchronized → ReentrantLock 引入 Bug | 中 | 中 | 逐文件替换 + 单元测试覆盖 |
| eddsa fork 安全漏洞 | 低 | 高 | 定期安全审计 + 关注上游替代方案 |

## 验证标准

### 编译验证
- `mvn compile -DskipTests` 在 JDK 21 下零错误零警告
- 无 `sun.*` 或 `com.sun.*` 内部 API 引用

### 协议验证
- MySQL 8.0/8.4 客户端连接成功
- MariaDB 10.6/11.x 客户端连接成功
- LOAD DATA INFILE、游标、multi-packet（>16MB）通过

### 功能验证
- 现有 MySQL 后端功能全量回归
- MariaDB 后端基础 CRUD + 事务 + 分片查询通过
- 分片路由、读写分离、心跳检测正常
