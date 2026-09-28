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
| Vert.x 4.2.5→4.5.14 | **~80+** | 极高 | 大量使用 `impl.*` 内部 API（PromiseInternal、impl.logging、ConcurrentHashSet） |
| Arrow 5.0→18.1 | **~38+** | 高 | VectorSchemaRoot 包路径变更 + allocator API 签名变更 |
| RxJava 3 使用面 | ~58+ | 中 | 若迁移 Observable→Flowable 则工作量巨大 |
| Guava 31→33.4 | ~62+ | 中低 | `UnsignedLong` 废弃（1 文件），集合工具类 API 稳定 |
| javax→jakarta | ~12 生产 + 40 测试 | 中高 | validation + annotation 全量替换 |
| mysql-connector 重命名 | 5 pom + 17 java | 低 | 仅 pom.xml 变更，`com.mysql.cj` 包名不变 |
| Jackson 2.10→2.18 | 1 | 极低 | 仅 1 个文件，API 兼容 |

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

#### Milestone 1.2: 创建 calcite-core 模块（~70 文件迁入）
Gate: `mvn compile` 全项目通过

| # | 原子任务 | 验收标准 |
|---|---------|----------|
| 1.2.1 | 创建 `calcite-core/` 目录 + `pom.xml` | `mvn validate -pl calcite-core` |
| 1.2.2 | 移动 `org/apache/calcite/` 全部 68 文件 + Druid 非 parser 文件 (~2) | `mvn compile -pl calcite-core` |
| 1.2.3 | 更新 hbt `pom.xml` 添加 calcite-core 依赖 | `mvn compile -pl hbt` |
| 1.2.4 | 删除 hbt 中的旧 fork 文件 | `mvn compile` 全项目通过 |

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

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| **Vert.x 内部 API 不兼容（最大风险）** | **极高** | **极高** | 80+ 文件使用 impl.* 内部 API，升级前逐一确认公开替代方案 |
| Arrow API 包路径变更 | 高 | 高 | 38+ 文件受影响，逐文件修复 + 编译验证 |
| 模块迁移导致循环依赖 | 中 | 高 | 严格 DAG 约束 + Maven Enforcer 插件 |
| MariaDB 协议差异遗漏 | 中 | 高 | 协议兼容性测试套件（TCK） |
| synchronized → ReentrantLock 引入 Bug | 中 | 中 | 逐文件替换 + 单元测试覆盖 |
| Calcite fork 升级冲突 | 低 | 中 | calcite-core 隔离后影响面可控 |
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
