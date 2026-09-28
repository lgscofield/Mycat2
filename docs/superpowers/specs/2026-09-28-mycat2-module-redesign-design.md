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

- 包名保持 `org.apache.calcite.*` 不变（避免修改 import）
- 独立 pom.xml，版本号与 Calcite 上游对齐（基础 1.26.0）
- hbt 通过 `io.mycat:calcite-core` 依赖引入
- 升级 Calcite 上游时，只需在 calcite-core 内部 merge 冲突

### eddsa fork 策略

- `net.i2p.crypto:eddsa` 源码 fork 到 protocol 模块内
- 包名保持 `net.i2p.crypto.eddsa.*` 不变
- MariaDB `client_ed25519` 认证依赖此 fork
- 独立维护，不受上游停更影响

## JDK 21 依赖升级矩阵

### 编译阻断项（不升级则 JDK 21 无法编译）

| 依赖 | 当前 → 目标 | 原因 |
|------|------------|------|
| maven-compiler-plugin | 2.3.2 → 3.14.1 | 不支持 release 21 |
| lombok | 1.18.8 → 1.18.42 | <1.18.30 JDK 21 编译失败 |
| janino | 3.0.11/3.1.0 → 3.1.12 | 旧版无法处理 JDK 21 字节码 |
| jakarta.annotation-api | 无 → 2.1.1（新增） | javax.annotation 已从 JDK 11 移除 |
| arrow-* | 5.0.0 → 18.1.0 | 内部使用 sun.misc.Unsafe |

### 安全关键项

| 依赖 | 当前 → 目标 |
|------|------------|
| jackson-* | 2.10.0 → 2.18.4 |
| logback-* | 1.2.3 → 1.5.18 |
| netty-* | 4.1.74 → 4.1.119 |
| guava | 31.0.1 → 33.4.8-jre |

### 功能升级项

| 依赖 | 当前 → 目标 | 说明 |
|------|------------|------|
| vertx-* | 4.2.5 → 4.5.14 | JDK 21 Virtual Thread 支持 |
| rxjava | 3.0.6 → 3.1.10 | 性能改进 |
| mysql-connector-java | 8.0.25 → 9.3.0 | MySQL 9.x 支持 |
| mariadb-java-client | 无 → 3.5.3（新增） | MariaDB 后端支持 |
| druid | 1.2.6 → 1.2.25 | 连接池 VT 兼容修复 |
| kafka-clients | 2.7.2 → 4.0.0 | 安全修复 |
| clickhouse-jdbc | 0.3.2 → 0.7.3 | 新协议支持 |

### JDK 21 P0 代码修复项

| 文件 | 问题 | 修复 |
|------|------|------|
| `MycatDirectByteBufferPool.java` | `import sun.nio.ch.DirectBuffer` | 替换为 `java.lang.foreign.MemorySegment` 或 VarHandle |
| `HTTPServer.java` | `import com.sun.net.httpserver.*` | 替换为 Vert.x HttpServer 或 JDK 标准 `jdk.httpserver` |
| `NewMycatConnectionImpl.java` | 6 个 synchronized + 阻塞 JDBC | synchronized → ReentrantLock；JDBC → Virtual Thread 友好 |
| ~30+ 文件 | synchronized 导致 VT pinning | 逐文件替换为 ReentrantLock |
| `IOExecutor.java` | CachedThreadPool | 替换为 `Executors.newVirtualThreadPerTaskExecutor()` |

## 实施路线图（4 阶段）

### Phase 0: JDK 21 编译通过（2 周）

1. 升级父 POM 所有依赖版本到目标矩阵
2. 修复 6 个 P0 编译阻断项
3. 验证 `mvn compile` 在 JDK 21 下通过
4. 替换 Dockerfile 基础镜像为 JDK 21

### Phase 1: 模块重构（4 周）

1. 创建 `protocol` 模块，迁移 ~95 个协议文件
2. 创建 `calcite-core` 模块，迁移 ~70 个 fork 文件
3. 重命名 `mycat2` → `server`，调整内容
4. 迁移 148 个函数文件到 `executor`
5. 更新所有模块 pom.xml 依赖
6. 验证全量编译和基础功能测试

### Phase 2: Virtual Thread 迁移（3 周）

1. ~30+ 文件 synchronized → ReentrantLock
2. IOExecutor → VirtualThread executor
3. 连接池 Druid → HikariCP 5.x（或 Druid 最新版）
4. 验证 VT 下无 carrier thread pinning

### Phase 3: MariaDB 接入（3 周）

1. protocol 模块实现 `client_ed25519` 认证
2. 新增 MariaDBProtocolCodec（能力标志位探测）
3. datasource 模块新增 MariaDB 后端类型
4. 集成测试：MariaDB 10.6/11.x 全量回归

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 模块迁移导致循环依赖 | 中 | 高 | 严格 DAG 约束 + Maven Enforcer 插件 |
| Calcite fork 升级冲突 | 低 | 中 | calcite-core 隔离后影响面可控 |
| synchronized → ReentrantLock 引入 Bug | 中 | 中 | 逐文件替换 + 单元测试覆盖 |
| MariaDB 协议差异遗漏 | 中 | 高 | 协议兼容性测试套件（TCK） |
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
