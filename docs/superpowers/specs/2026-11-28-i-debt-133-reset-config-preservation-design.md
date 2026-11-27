# I-DEBT-133 resetConfig 安全保留与持久化补偿设计

- **状态**：SPEC-READY（2026-11-28；未改生产代码，未获实施拍板）
- **票据**：I-DEBT-133；关联 I-DEBT-100/G8（部署凭据保留）、I-DEBT-130（运行态资源交换）、I-DEBT-137（持久化半写残留）
- **入口**：管理员 hint `/*+ mycat:resetConfig{} */`；首次安装也复用 `writeDefaultConfigToFile()`。
- **目标**：reset 只收回逻辑 schema 面，不破坏部署资产或管理身份；持久化失败不把运行态与启动配置留成分叉，也无法恢复时不谎报成功。

## 1. 现状取证

| 事实 | 代码证据 |
|---|---|
| hint 调 `ConfigUpdater.getOps()` 后直接 `reset(); commit()`，没有持久化先行或失败补偿 | `server/src/main/java/io/mycat/sqlhandler/dql/HintHandler.java:576-580` |
| `reset()` 新建整份默认配置，只从旧配置回填 `prototypeDs` 的 url/user/password | `server/src/main/java/io/mycat/config/MycatRouterConfigOps.java:1012-1034` |
| 其余 datasource、cluster、user、sequence、sqlCache、非默认 `prototype` 字符串均被默认值替换 | 同上；`MycatRouterConfigOps.java:1036-1075` |
| root 用户整个对象被替换，`encryptType/encodeKey/ip/role/admin` 等不会保留；后续认证读取的是可解密口令 | `config/src/main/java/io/mycat/config/UserConfig.java`（`getPassword()`） |
| `commit()` 先构造并注册全部运行态资源，再执行可选物理建表，最后才持久化 | `server/src/main/java/io/mycat/config/MycatRouterConfigOps.java:580-734` |
| 六类 `UpdateSet` 没有跨文件事务；`execute()` 先删后写，文件层只有单文件 temp+move | `server/src/main/java/io/mycat/sqlhandler/config/UpdateSet.java:34-42`；`FileKV.java:55-70,132-150` |
| DB 层 `removeKey()` 与 `put()` 各自读改写提交，也不是跨六类原子事务；ZK 层逐 key 删改 | `DbKVImpl.java:34-49`；`ZookeeperKVImpl.java:39-46` |
| 物理建表不是可靠失败信号：实现会捕获后端异常并仅记日志 | `hbt/src/main/java/io/mycat/util/CreateTableUtils.java:44-80` |
| 运行态成功后已经调度旧资源 30 秒退休；物理建表和持久化失败不在该恢复保护范围内 | `MycatRouterConfigOps.java:96-108,697-731` |
| 首次安装（`server.json` 不存在）也走 reset+commit | `server/src/main/java/io/mycat/MycatCore.java:165-187` |

`ConfigUpdater.loadConfigFromFile()` 与 `startServer()` 是启动恢复扫描的接入点；该扫描必须先于 `getFileConfigSnapshot()`/`load()`，否则恢复前快照已被半配置污染。恢复协议只覆盖本提交协议内的配置写入；外部手工编辑仍按既有文件加载规则处理。

当前故障链因此是：**reset 构造破坏面 → 运行态先换 → 六类配置顺序落盘 → 任一 I/O 失败后内存与磁盘分叉，且文件已经可能处于半配置**。仅把 `reset()` 改深拷贝不能关闭本票；仅把 persistence 前移也不能解决多文件事务问题。

## 2. 语义边界：reset 到底重置什么

### 2.1 目标模型

reset 的可变对象只有 `schemas`：

| 配置面 | reset 后语义 |
|---|---|
| `schemas` | 全部移除（逻辑库/表定义面收回） |
| `clusters` | 原样深拷贝保留，包括名称、成员、切换类型、容量 |
| `datasources` | 原样深拷贝保留，包括连接参数、容量、类型和凭据，不只 `prototypeDs` 三元组 |
| `users` | 原样深拷贝保留，包括 root、自定义用户、RSA `encryptType/encodeKey`、角色和管理员标志 |
| `sequences` | 原样深拷贝保留 |
| `sqlCacheConfigs` | 原样深拷贝保留 |
| `prototype` | 原样保留，不恢复成字符串 `"prototype"` |

reset **不删除任何持久化文件**，只通过 schema `UpdateSet` 删除对应 schema key；其余五类持久化面无写操作。逻辑 schema 的物理库/表是否删除不属于 reset 契约，仍由既有显式 drop 流程负责。

### 2.2 深拷贝与身份不变式

`reset()` 从本轮 `newConfig`（而不是 `original`）取完整保留快照，逐项字段级 JSON 深拷贝到新对象；`MycatRouterConfigOps` 构造初始候选也使用同一字段级拷贝，避免通用 getter 序列化调用 `UserConfig.getPassword()`。`original` 继续表示“本轮 commit 前的运行态”，供 `UpdateSet` 计算差异。

- 不共享 list、元素或嵌套集合引用。
- 不调用 `UserConfig.getPassword()`，避免 RSA 解密失败或派生值覆盖密文。
- 深拷贝后的对象必须与快照 `equals`；若 setter 改变 URL 字符串形式，该测试先红并修正实现，不能静默接受。
- `prototype` 不属于 KVObject，仍需独立保留；它参与 `fixPrototypeTargetName()` 的 cluster 命名。

### 2.3 空集合默认值

只有首次安装生成默认配置时才补默认值；普通 reset 不调用 `defaultConfig()` 修复已有部署：

| 输入 | 输出 |
|---|---|
| 首次安装 bootstrap，且六类集合均为空、`prototype` 为缺省 `"prototype"` | 补出厂 root、`prototypeDs`、`prototype`/`c0` 两个 cluster；schemas 为空 |
| 任一类集合非空 | 非空项原样保留；**不再追加默认 root、默认 datasource 或任何 cluster** |
| datasources 非空、clusters 为空 | 保持 clusters 为空，不补 `c0`，也不补 `prototype` cluster |
| `prototype` 非空 | 原样保留，不补写默认值 |

为区分“首次安装补默认”和“普通 reset 保留”，`writeDefaultConfigToFile()` 必须显式生成 default-only 候选，不能隐式调用面向普通 reset 的逻辑。`defaultConfig()` 的职责也收窄为“填充空集合”，不通过 `fixPrototypeTargetName()` 偷偷改非空 cluster 面；目标 cluster 名是否修复由加载/构建既有路径负责，本票不扩大其职责。

## 3. 候选方案

### 方案 A：只修 reset 保留面

- 优点：改动小、直接消除凭据与用户身份破坏。
- 缺点：持久化仍后置且非原子，无法满足“失败不产生半配置”；**否决**。

### 方案 B：持久化整体前移 + 失败反向补偿

- 先捕获旧快照，再写新快照；任一失败则反向恢复旧快照，最后才构建/替换运行态。
- 优点：运行态不先于磁盘分叉；DB 层可一次写完整快照。
- 缺点：文件/ZK 没有跨 key 原子事务；补偿本身可能失败，进程崩溃会留下半配置；且写成功、运行态构建失败时仍需恢复磁盘。**只能作为补偿层，不能单独满足契约**。

### 方案 C（推荐）：纯配置校验、持久化提交、运行态切换 + 恢复记录

1. 以本轮 `newConfig` 为目标候选（reset 已按 §2.2 从进入 reset 前的 `newConfig` 深拷贝）；用 `original` 作为提交前基线构造六类 `UpdateSet`，进行不访问后端、不修改全局状态的纯配置校验。
2. 捕获持久化旧快照并写 `PREPARED` 恢复记录；顺序应用目标快照。
3. 重读并校验落盘快照；失败则应用反向补偿，并报告补偿是否完整。
4. 持久化校验成功后，构建并注册新运行态；旧资源继续存活，replica 的停止/启动属于交换阶段。
5. 运行态注册成功后，持久化 `COMMITTED` 标记；只有该标记落盘后才发送提交成功信号、允许旧资源延迟退休并向 hint 返回 Ok。
6. `COMMITTED` 标记失败或运行态构建/注册失败：恢复提交前 `MetaClusterCurrent` 上下文，关闭本轮新资源，补偿旧快照，校验后标记 `RESTORED`。

不提前构造完整运行态候选：现有 `getJdbcConnectionManager()` 的“仅新增 datasource”分支会原地 `addDatasource`，`getMetadataManager()` 的仅新增 schema 分支会原地 `addSchema`，资源构造器还可能修改全局编译参数或调度器。因此预检只做纯配置校验，运行态构建统一放在持久化校验之后；失败由上下文恢复与快照补偿兜底。构建阶段只使用隔离的候选资源/context，不注册目标、不原地扩缩容旧 manager；全部构建成功后通过 `MetaClusterCurrent.replaceContext(expected, replacement)` 一次 CAS 激活。CAS 失败按竞争提交处理，关闭本轮新资源并补偿旧快照。XA recovery 与 plan-store 检查依赖已注册上下文且会访问后端，属于配置提交后的初始化任务，不触发配置回滚。

优点：把可逆的配置写入与不可逆的运行态副作用分开；失败时旧运行态保持服务；恢复记录覆盖进程崩溃窗口。代价是需要拆分 `commit()`，并为一个 I/O 失败建立持久化恢复协议，属于架构级改动。

### 方案 D：只允许 FileStorageManager 原子切代

- 通过 home 下整代目录交换获得多文件原子性。
- 优点：单机文件部署最强。
- 缺点：改变外部工具/挂载路径约定，不能覆盖 DB/ZK；作为本票方案过重，**否决**。

## 4. 持久化协议（方案 C）

### 4.1 快照格式与范围

快照使用现有 `StorageManager.toMap()` 的 `Map<path, Map<key, rawJson>>` 形状，保留原始 JSON 字符串，不以对象反序列化后的 JSON 替代。这样用户 RSA 密文、encodeKey 与字段形状不经 setter 往返。

- `oldSnapshot`：提交前从同一 storage manager 读取。
- `targetSnapshot`：以 `oldSnapshot` 深拷贝为基础，按六类 `UpdateSet` 的 delete/create 精确应用；不得把整个目标对象重新序列化成快照，但 create 项必须使用字段级 JSON 序列化。
- 顺序固定为：`schemas → clusters → datasources → sequences → sqlCacheConfigs → users`；类内 key 按 key 字典序处理，取消并行写。
- 每个路径只操作本票六个注册类；非目标路径保持原样。

### 4.2 恢复记录

在第一次写目标前，写入单个不可变恢复记录，形状为：

```text
recordId
createdAt
status = PREPARED | COMMITTED | RESTORED
oldSnapshot
targetSnapshot
oldVersions / targetVersion
snapshotSha256
```

记录必须包含旧、目标两份快照及恢复所需版本/校验信息；具体记录格式由实施 plan 冻结。状态转换只允许 `PREPARED → COMMITTED` 或 `PREPARED → RESTORED`，每次转换都是原子写。记录先于任何业务 key 变更落盘。记录不打印口令；日志只出现 recordId、路径/key 和异常类型。

恢复记录包含明文凭据，必须与现有配置文件同级保护：File 记录仅属主可读写（POSIX 0600，Windows 等价 ACL），ZK 使用既有认证与受限 ACL，DB 使用现有配置表权限；禁止放入日志、异常消息或公开诊断输出。`COMMITTED`/`RESTORED` 记录在对应配置被启动装载并校验后清理；异常退出保留记录，清理失败只告警，不得改变已提交配置。

恢复是**尽力而为**：逐项失败要收集全部错误，不能因首个异常跳过其余补偿。恢复结束后重读并校验；无法完整恢复时保留记录，禁止宣告提交成功，并给出明确错误“配置持久化失败且恢复不完整”。

### 4.3 后端能力

在 `StorageManager` 增加最小批量接口（名称在 plan 冻结），供同一提交按快照执行：

- `readSnapshot()`：从存储读取原始 JSON 字符串，不得复用 File 的 `toMap()` 反序列化再序列化路径。
- `stageSnapshot(target)` / `verifyStagedSnapshot(expected)`：暂存并校验目标；File 的暂存即可见，DB 的暂存只写恢复记录、不改变 active 代。
- `publishSnapshot(recordId, target)`：发布目标；DB 在同一事务内把 active 代和 COMMITTED 标记一起提交。
- `verifyVisibleSnapshot(expected)` / `restoreSnapshot(oldSnapshot)`：重读可见代并执行补偿。
- 只写与 oldSnapshot 实际不同的路径/key，五类保留面零写。

File 后端暂存目标文件并保留恢复记录，快照读取在提交锁内执行，不把半快照作为已发布代。DB 后端应在**一个连接、一个事务**里删除旧可见版本并写目标版本，禁止复用 `DbKVImpl` 的逐 key 独立提交；若目标数据库不具备所需隔离/主键约束，提交前校验失败，不进入运行态交换。ZK 当前不支持事务提交，不进入回退的逐 key 写入路径。

不能提供该能力的后端必须在提交前明确失败，不得退回“写一半再试”。当前 `ZookeeperManagerImpl` 整体注释、`get()` 不支持，因此 ZK 不属于本票已支持后端；在真实 adapter 与受限 ACL、认证、active generation 发布能力落地并通过测试前，`supportsSnapshotCommit()` 必须返回 false。

### 4.4 启动与并发恢复

- 启动时，在任何新配置加载前扫描恢复记录。
- `PREPARED` 表示上次提交未完成：应用 `oldSnapshot`，重读校验；校验通过才标记 `RESTORED`，失败则拒绝启动并保留记录。
- `COMMITTED` 表示持久化已完整：加载 `targetSnapshot`，校验后清理记录；若运行态交换当时未完成，重启按目标配置重新构建。
- `RESTORED` 表示已恢复旧配置：加载 `oldSnapshot`，校验后清理记录。
- 同一 home/逻辑存储的配置提交必须串行，避免两个 record 交错覆盖。File 使用 home 下跨进程文件锁，锁从 oldSnapshot 读取一直持有到 `COMMITTED`/`RESTORED`；同 home 的启动恢复与快照读者也取同一把锁，避免读到半快照。DB/ZK 使用存储层原子锁或单主写入约束。不得用仅进程内锁冒充跨进程互斥。
- File 的 `PREPARED` 记录、目标文件与状态转换必须强制落盘（文件内容及首次创建所需的目录项）；仅 temp+move 不满足崩溃恢复要求。DB 在一个连接、一个事务内完成旧版本删除和目标版本写入并提交，失败回滚后可见快照不变。
- 多节点共享存储只保证写入互斥，不自动保证其他节点实时可见。DB/ZK 必须通过 active generation/manifest 指针发布快照，读取方只读已发布代；无法提供该能力的共享后端拒绝进入提交，不退回“提交到一半即全局可见”。
- 补偿、恢复和正常写入共用同一快照应用路径，避免“正常写”与“回滚写”语义漂移。

## 5. 运行态与副作用边界

### 5.1 允许恢复

- 未注册的新资源：`giveup`。
- 已注册但尚未发送成功信号：恢复提交前 `MetaClusterCurrent` 的完整上下文引用，关闭本轮新资源，replica 恢复启动。
- 持久化失败：不执行交换、不执行物理建表、不发送成功信号；旧运行态继续服务。
- 运行态交换后失败：恢复旧上下文与旧资源；已经执行的持久化通过恢复协议回旧快照。

### 5.2 不承诺回滚

- 配置事务边界在 `COMMITTED` 标记落盘；此后 XA recovery/reaper、plan-store 检查和物理 DDL 属于初始化任务，不触发整套配置回滚。
- 已执行的物理 DDL 可能已在部分后端建库/建表；不得反向盲删。
- XA recovery/reaper 的启动与调度、在途事务、旧池在途借用不能简单撤销。
- 已发送成功信号后旧资源的延迟关闭窗口不能收回。

物理建表与 XA/plan-store 初始化必须放在 `COMMITTED` 之后；失败按初始化失败记录并阻止服务就绪，但不冒充可回滚步骤。若要求“物理建表必须全部成功且自动回滚”，应作为 I-DEBT-146 的独立 DDL 事务专轮，本票不偷改其异常吞噬语义。

并发 reset 与其他配置写操作必须在同一提交序列化边界内；hint 入口不能绕过。

## 6. 验收判据

1. **完整保留**：构造非默认 datasource 全集、自定义 cluster、sequence、sqlCache、多个用户、root RSA 密文/encodeKey、自定义 `prototype`；reset 后除 schemas 外逐字段 `equals`，且所有对象均非原引用。
2. **默认边界**：空配置首次安装补默认；普通 reset 对 datasources 非空/clusters 为空不补 cluster；不新增默认 root；`prototype` 原样保留。
3. **无额外持久化写**：reset 目标相对 original 只有 schemas `UpdateSet`；五类保留面的 KV `put/remove` 调用为零。
4. **先持久化后切换**：故意在快照写入第 N 项失败，验证发生反向补偿、最终磁盘等于 oldSnapshot、`MetaClusterCurrent` 从未注册目标资源、旧池未关闭、未执行物理建表。
5. **补偿失败**：目标写与反向写都注入失败，命令失败且错误明确“恢复不完整”，恢复记录保留；不得发送 Ok 或提交成功信号。
6. **DB 单事务**：同一配置提交只使用一个 DB 连接/事务，删除旧版本与写目标版本同提交；失败后可见快照完全不变。
7. **运行态构建失败**：持久化已写目标但资源验证失败时，磁盘恢复 oldSnapshot，旧上下文与旧资源保持可用。
8. **崩溃恢复**：PREPARED 记录存在时启动先恢复 oldSnapshot；COMMITTED 记录存在时加载 targetSnapshot；恢复校验失败拒绝启动并保留证据。
9. **并发序列化**：两个并发提交不能交错写 record 或快照；第二个必须看到第一个完成后的基线。
10. **既有回归**：更新 `ResetPreservesPrototypeCredentialsTest` 的 cluster 断言为新契约；`CommitAtomicityOrderTest` 三案继续通过；完整 server/example 护栏零回归。
11. **活体证据**：真实 MySQL 上 reset 后仍能用原 root/RSA 配置、自定义 datasource/cluster 登录和查询；schemas 消失；制造持久化失败时旧配置仍可启动。证据必须绑定重打 jar 的 SHA-256，当前 HEAD CI 全绿后方可关闭。
12. **恢复记录安全**：File 权限限于属主，日志/异常不含快照内容；`COMMITTED`/`RESTORED` 在启动校验后清理，清理失败不改变配置。
13. **发布可见性**：共享 DB/ZK 在 `COMMITTED` 前不改变 active generation；读者看不到半快照；不具备该能力的后端明确拒绝提交。
14. **分阶段交付**：阶段 A 先完成 reset 保留面与默认边界；阶段 B 再完成事务持久化、启动恢复与活体护栏。阶段 A 不关闭 I-DEBT-133。

## 7. 非目标与关闭边界

- 不实现 schema 选择参数、白名单 reset 或部分 schema 重置；未来若需要，另立票据。
- 不改变管控 hint 的 admin 授权面（I-DEBT-90 已覆盖）。
- 不修复物理 DDL 的跨后端原子性（I-DEBT-146）。
- 不把 DB/ZK 夸大为多 key 原子事务；只保证失败补偿、启动恢复和可验证的最终状态。
- 不关闭 I-DEBT-137 的通用配置写面；本票验收与活体只覆盖 resetConfig。`MycatRouterConfigOps.commit()` 是公共接线，若改为事务路径，其他持久化调用方必须通过独立回归；直接 KV 写入及非本票配置面仍归 I-DEBT-137。

## 8. 待拍板决定

推荐一次拍板以下组合：

1. reset 只清 `schemas`，其余五类与 `prototype` 完整深拷贝保留。
2. 默认值仅限首次安装；普通 reset 对非空配置不补任何默认项。
3. 采用方案 C：纯配置校验 → `PREPARED` → 持久化并校验 → 构建/注册运行态 → `COMMITTED` → 返回 Ok；失败恢复旧上下文、补偿旧快照并标记 `RESTORED`。
4. 增加恢复记录与启动恢复；DB 单事务，File 暂存加补偿、跨进程锁与强制落盘，不承诺后端原生多 key 原子；记录 ACL、禁日志、可清理；当前 ZK 明确拒绝提交，共享后端须具备 active generation 发布能力。
5. 接受物理 DDL/XA/plan-store/在途资源为提交后初始化或不回滚边界；配置事务边界在 `COMMITTED`，成功信号与 Ok 均在其后。
