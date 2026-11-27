# I-DEBT-133 resetConfig 保留面与配置事务 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** resetConfig 只清 schemas，完整保留部署配置；持久化提交具备 PREPARED/COMMITTED/RESTORED 恢复协议，失败不谎报 Ok，活体与当前 HEAD CI 全绿后才关闭 I-DEBT-133。

**Architecture:** 阶段 A 先把 reset 改为整份深拷贝后清 schemas，并把首次安装默认值改成显式 default-only 路径。阶段 B 增加原始 JSON 快照存储能力与独立配置事务，File 用跨进程锁/持久化记录，DB 用单连接单事务发布；运行态在隔离候选中构建，通过 `MetaClusterCurrent` 整上下文 CAS 激活，提交点固定在 COMMITTED。

**Tech Stack:** Java 21、Maven 多模块、JUnit 5、Mockito 5.17、Jackson/JsonUtil、NIO FileChannel/FileLock、POSIX 权限与 Windows ACL、JDBC/MySQL、Vert.x；当前启用的配置后端为 File 与 DB，ZK 默认不具备提交能力。

**Spec:** `docs/superpowers/specs/2026-11-28-i-debt-133-reset-config-preservation-design.md`（用户已批准第 8 节五项决定；本 plan 尚待执行方式拍板）。

## Global Constraints

- **两阶段门禁**：阶段 A 完成也不得关闭 I-DEBT-133；阶段 B 全部验收、重打 jar、完整护栏、证据归档和当前 HEAD CI 全绿后才可关闭。
- **TDD**：每个生产改动先写失败测试并观察正确 RED，再最小实现；编译错误、环境 NO_DATA 或测试自身错误不算 RED。
- **reset 语义**：只清 `schemas`；`clusters/datasources/users/sequences/sqlCacheConfigs/prototype` 从进入 reset 前的本轮 `newConfig` 完整深拷贝；不共享 list、元素或嵌套集合，不调用 `UserConfig.getPassword()`。
- **默认边界**：普通 reset 对空集合也不补默认值；只有首次安装显式 default-only 候选补 root、prototypeDs、prototype/c0；`defaultConfig()` 不调用 `fixPrototypeTargetName()` 偷改非空 cluster 面。
- **原始快照**：`Map<path, Map<key, rawJson>>` 必须保留原始 JSON 字节形状；禁止 File `toMap()` 的反序列化再序列化往返。目标快照从 oldSnapshot 深拷贝，只对 UpdateSet 的 delete/create 应用变更。
- **固定顺序**：schemas → clusters → datasources → sequences → sqlCacheConfigs → users；类内 key 按字典序，取消并行写。reset 的五类保留面 KV 写调用为零。
- **提交顺序**：纯配置校验 → PREPARED 强制落盘 → stage/verify target → 隔离构建运行态 → CAS 激活 → COMMITTED → 成功信号/Ok → 提交后初始化。
- **恢复**：失败恢复提交前完整 context、关闭本轮新资源、补偿 oldSnapshot；重读校验通过才标 RESTORED。补偿不完整保留记录并报“配置持久化失败且恢复不完整”，不得返回 Ok。
- **崩溃与并发**：File 锁从读取 oldSnapshot 持有到 COMMITTED/RESTORED，启动恢复和 File 快照读者共用；DB 用 `SELECT GET_LOCK('mycat.config.commit', ...)` 或等效存储锁。ZK 当前实现整体注释，提交前明确拒绝。
- **可见性**：DB 在 COMMITTED 所在同一事务发布完整目标代之前，active 可见快照不变；不得把恢复记录当 visible config。File 读者持锁，ZK 无 active generation 能力时拒绝。
- **安全**：恢复记录含明文凭据；File POSIX 0600/Windows 等价 owner-only ACL，无法强制权限即失败。日志/异常只含 recordId、路径/key、异常类型，禁止快照和口令。
- **提交后边界**：XA recovery/reaper、plan-store、物理 DDL 只在 COMMITTED 后执行；失败保持目标配置并明确“配置已提交、初始化失败”，不标 RESTORED。
- **公共接线回归**：`commit()` 改为事务路径后，普通配置写调用方必须独立回归；本票不据此关闭 I-DEBT-137 的直接 KV/通用写面。
- **构建**：JDK 21 使用 `D:/Program Files/BellSoft/LibericaJDK-21-Full`；Maven 本地仓库 `D:/Nexus/repository`；定向测试必须 `-pl server -am`，不得并发写同一 reactor target。
- **证据**：长 Maven/护栏用 Monitor；原始证据日志不得为 whitespace 改写；`.qoder/`、`ci/iopr1/`、`scratch/` 不提交；jar SHA-256 用 Python `hashlib` 流式计算。

## Review Focus

1. **RSA/原始 JSON**：用户密文、encodeKey、字段顺序和空白形状不得因 reset 或快照往返改变。→ Task A1、B1、B2、B6 测试。
2. **半写可见性**：PREPARED 期间其他进程/节点不能读到半快照，第二个提交必须看到第一个完成后的基线。→ Task B2、B3、B4 测试。
3. **不确定提交结果**：COMMITTED 写入抛错但实际已落盘时，必须先重读记录状态，不能盲目补偿覆盖已提交代。→ Task B1、B4 测试。
4. **运行态隔离**：新增 datasource/schema 不得原地改旧 manager；CAS 失败不得覆盖竞争 context、关闭旧池或留下半激活上下文。→ Task B4 测试。
5. **提交后初始化失败**：XA/plan-store/DDL 失败不得把已提交配置回滚，也不得对客户端伪装完整成功。→ Task B4、B6 测试。

---

## 阶段 A：reset 保留面与默认边界

### Task A1: reset 完整深拷贝，只清 schemas

**Files:**
- Modify: `config/src/main/java/io/mycat/util/JsonUtil.java`
- Modify: `server/src/main/java/io/mycat/config/MycatRouterConfigOps.java:181-194,1011-1034`
- Test: `server/src/test/java/io/mycat/config/ResetPreservesPrototypeCredentialsTest.java`

**Interfaces:**
- Consumes: 现有配置 bean、`MycatRouterConfig.prototype`；新增字段级 `JsonUtil.cloneConfig(Object, Class<T>)` / `JsonUtil.toConfigJson(Object)`，不使用 getter/setter。
- Produces: `public void reset()`；reset 后 `newConfig != previous`，除 schemas 外六项语义相等且对象图无共享引用。

- [ ] **Step 1: 扩展失败测试** — 保留现有凭据测试；将 `resetStillResetsRoutingFace` 的 cluster 断言改为“原 cluster 保留且 schemas 为空”。新增 `resetPreservesEveryNonSchemaFieldWithoutSharingReferences`：塞入两个 datasource、两个 cluster、sequence、sqlCache、两个 user、非默认 `prototype="customPrototype"` 和一个 schema；逐字段 `equals`，并断言每个 list、元素及 `masters/replicas` 等嵌套集合均 `assertNotSame`。
- [ ] **Step 2: 增加 RSA 保真失败测试** — `resetPreservesRsaCiphertextWithoutDecrypting`：root 设置 `encryptType="rsa"`、`encodeKey=<测试私钥>`、`password=<测试密文>`；用 Mockito spy 使 `getPassword()` 抛错，分别覆盖“构造 Ops 初始候选”和“执行 reset”两个入口；reset 后三字段逐字相等，两次均不得调用 getter。
- [ ] **Step 3: 增加默认边界失败测试** — `emptyOrdinaryResetDoesNotInjectDefaults`、`resetKeepsNonEmptyDatasourcesAndEmptyClustersUnchanged`、`resetPreservesCustomPrototype`；断言普通 reset 后 users/datasources/clusters 保持原集合内容，空 cluster 不补 c0/prototype。
- [ ] **Step 4: 运行确认正确 RED** — `JAVA_HOME="D:/Program Files/BellSoft/LibericaJDK-21-Full" PATH="D:/Program Files/BellSoft/LibericaJDK-21-Full/bin:$PATH" mvn -o -B -pl server -am test "-Dtest=ResetPreservesPrototypeCredentialsTest" "-Dsurefire.failIfNoSpecifiedTests=false"`。Expected：至少完整保留/RSA/默认边界断言失败，且原因是当前 reset 重建默认配置，不是编译错误。
- [ ] **Step 5: 最小实现 reset 与字段级拷贝** — 在 `JsonUtil` 增加配置专用 ObjectMapper：FIELD=ANY、GETTER=NONE、SETTER=NONE，`cloneConfig`/`toConfigJson` 复用现有宽松解析特性。Ops 两个构造器的初始候选和 reset 的 `previous` 拷贝都改用它；`next.setSchemas(new ArrayList<>())`；删除 prototypeDs 三元组特判和 reset 内 `defaultConfig()` 调用。
- [ ] **Step 6: GREEN 与回归** — 重跑 Step 4，Expected：全部通过；再跑 `CommitAtomicityOrderTest`，Expected：三案保持通过。
- [ ] **Step 7: Commit** — `git add config/src/main/java/io/mycat/util/JsonUtil.java server/src/main/java/io/mycat/config/MycatRouterConfigOps.java server/src/test/java/io/mycat/config/ResetPreservesPrototypeCredentialsTest.java && git commit -m "fix(I-DEBT-133): reset 仅清 schemas 并深拷贝保留部署配置"`。

### Task A2: 首次安装 default-only 路径与五类零写

**Files:**
- Modify: `server/src/main/java/io/mycat/config/MycatRouterConfigOps.java:1036-1081`
- Modify: `server/src/main/java/io/mycat/sqlhandler/ConfigUpdater.java:158-163`
- Test: `server/src/test/java/io/mycat/config/ResetDefaultBoundaryTest.java`（新建）
- Test: `server/src/test/java/io/mycat/config/ResetPersistenceWritesTest.java`（新建）

**Interfaces:**
- Produces: `public static MycatRouterConfig createDefaultConfig()`；`defaultConfig(MycatRouterConfig)` 只填空集合，不修复非空 cluster。
- Consumes: `ConfigUpdater.writeDefaultConfigToFile()` 显式创建 default-only 候选，不再调用普通 `reset()`。

- [ ] **Step 1: 写 default-only 失败测试** — `createDefaultConfigSeedsBundledDefaults` 断言 username=root/admin=true、prototypeDs 口令、prototype/c0 两个 cluster；`defaultConfigDoesNotRepairNonEmptyClusterFace` 构造 datasources 含 prototypeDs、clusters 仅含 custom、prototype="customPrototype"，调用后 clusters 仍仅含 custom，不追加 customPrototype；另断言 datasources 非空/clusters 为空时仍为空。
- [ ] **Step 2: 写五类零写失败测试** — `resetPersistenceWritesOnlySchemas`：Mockito mock `StorageManager` 和六个 `KV`；original 有一个 schema 及非空五类，执行 reset 后构造六 `UpdateSet` 并调用 `persistence(...)`；verify schema KV 恰好一次 `removeKey`，五个保留 KV 的 `put/removeKey` 均为 0 次。
- [ ] **Step 3: 运行确认 RED** — 使用 Global Constraints 的 JDK21 Maven 前缀，`-Dtest=ResetDefaultBoundaryTest,ResetPersistenceWritesTest`。Expected：default-only API 缺失/旧 `fixPrototypeTargetName()` 行为导致断言失败；零写测试可能已绿，若已绿记录为既有行为锁，不伪造 RED。
- [ ] **Step 4: 实现显式默认工厂** — `createDefaultConfig()` 新建配置、调用收窄后的 `defaultConfig()`、返回；`defaultConfig()` 删除末尾 `fixPrototypeTargetName()`。`writeDefaultConfigToFile()` 从 `MetaClusterCurrent` 取 StorageManager，以空 original 和 default-only newConfig 构造 Ops 后 commit。
- [ ] **Step 5: GREEN 与阶段 A 回归** — 重跑 Step 3，再跑 `-Dtest=ResetPreservesPrototypeCredentialsTest,ResetDefaultBoundaryTest,ResetPersistenceWritesTest,CommitAtomicityOrderTest`。Expected：全绿；票据仍 OPEN。
- [ ] **Step 6: Commit** — `git add server/src/main/java/io/mycat/config/MycatRouterConfigOps.java server/src/main/java/io/mycat/sqlhandler/ConfigUpdater.java server/src/test/java/io/mycat/config/ResetDefaultBoundaryTest.java server/src/test/java/io/mycat/config/ResetPersistenceWritesTest.java && git commit -m "fix(I-DEBT-133): 首次安装默认配置与普通 reset 分离"`。

---

## 阶段 B：快照事务、运行态交换、恢复与活体

### Task B1: 冻结快照/记录/事务接口与纯状态机

**Files:**
- Create: `server/src/main/java/io/mycat/sqlhandler/config/ConfigSnapshotStore.java`
- Create: `server/src/main/java/io/mycat/sqlhandler/config/ConfigRecoveryRecord.java`
- Create: `server/src/main/java/io/mycat/sqlhandler/config/ConfigSnapshots.java`
- Create: `server/src/main/java/io/mycat/sqlhandler/config/ConfigPersistenceTransaction.java`
- Create: `server/src/main/java/io/mycat/sqlhandler/config/ConfigRecoveryException.java`
- Modify: `server/src/main/java/io/mycat/sqlhandler/config/StorageManager.java`
- Test: `server/src/test/java/io/mycat/sqlhandler/config/ConfigPersistenceTransactionTest.java`（新建）
- Test: `server/src/test/java/io/mycat/sqlhandler/config/UnsupportedSnapshotCommitTest.java`（新建）

**Interfaces:**
- `ConfigSnapshotStore extends AutoCloseable`：`Map<String,Map<String,String>> readSnapshot()`、`void stageSnapshot(Map<String,Map<String,String>> target)`、`void verifyStagedSnapshot(Map<String,Map<String,String>> expected)`、`void verifyVisibleSnapshot(Map<String,Map<String,String>> expected)`、`void publishSnapshot(String recordId, Map<String,Map<String,String>> target)`、`void restoreSnapshot(Map<String,Map<String,String>> oldSnapshot)`、`void writeRecoveryRecord(ConfigRecoveryRecord record)`、`Optional<ConfigRecoveryRecord> readRecoveryRecord()`、`void updateRecoveryStatus(String recordId, ConfigRecoveryRecord.Status status)`、`void deleteRecoveryRecord(String recordId)`、`void close()`。`open` 时取得后端写锁；不支持的后端抛 `UnsupportedOperationException`。
- `StorageManager`：`default ConfigSnapshotStore openConfigSnapshotStore() throws IOException { throw new UnsupportedOperationException("configuration snapshot commit is unsupported"); }`。
- `ConfigRecoveryRecord`：`String recordId`、`Instant createdAt`、`Status status`（PREPARED/COMMITTED/RESTORED）、两份 snapshot、`Map<String,String> snapshotSha256`（OLD/TARGET）、`Map<String,String> backendState`（版本/指针等后端不透明状态）。
- `ConfigSnapshots.targetSnapshot(oldSnapshot, original, target)`：固定六路径、固定顺序、类内 key 排序；只删除 delete、只用 `JsonUtil.toConfigJson()` 序列化 create，未变 rawJson 原样深拷贝。哈希输入按 `path + '\0' + key + '\0' + value.length() + ':' + value + '\n'` 排序拼接后 SHA-256。
- `ConfigPersistenceTransaction`：`static begin(StorageManager, MycatRouterConfig original, MycatRouterConfig target)`、`void persistTarget()`、`void markCommitted()`、`void restore()`、`void close()`；只允许 PREPARED→COMMITTED/RESTORED。

- [ ] **Step 1: 写纯快照失败测试** — `targetSnapshotPreservesUnchangedRawJson` 使用带空白/RSA 密文的 raw 字符串，目标只删 schema；断言未变五路径 raw 完全相等、schema 删除、哈希稳定且深拷贝。
- [ ] **Step 2: 写状态机失败测试** — `preparedRecordPrecedesFirstTargetWrite`、`markCommittedRequiresPrepared`、`illegalRestoredToCommittedTransitionRejected`；fake store 记录调用序列。
- [ ] **Step 3: 写补偿失败测试** — `restoreAttemptsEveryPathBeforeVerifying` 在多个恢复写注入失败，断言全部路径都尝试、记录保留、异常为 `ConfigRecoveryException` 且消息为“配置持久化失败且恢复不完整”；异常/记录字符串不含测试口令。
- [ ] **Step 4: 写不确定提交测试** — `committedWriteFailureReconcilesRecordBeforeRestore`：fake 先写 COMMITTED 再抛错；事务重读发现 COMMITTED 后不得 restore/标 RESTORED，按已提交处理。另测读到 PREPARED 才恢复。
- [ ] **Step 5: 写能力拒绝测试** — `UnsupportedSnapshotCommitTest` 验证默认 `openConfigSnapshotStore()` 明确拒绝；不得退化为逐 key 写。
- [ ] **Step 6: 运行确认 RED** — `-Dtest=ConfigPersistenceTransactionTest,UnsupportedSnapshotCommitTest`。Expected：新类型缺失或行为断言失败，不是环境错误。
- [ ] **Step 7: 实现接口与状态机** — fake 测试驱动实现；错误聚合不得在消息中带 snapshot/value；事务 close 必须释放 store 且幂等。
- [ ] **Step 8: GREEN 与 Commit** — 重跑 Step 6；Expected：全绿。提交本 Task 列出的接口、状态机与测试，message：`feat(I-DEBT-133): 增加配置快照事务状态机`。

### Task B2: File 原始快照、跨进程锁、ACL 与强制落盘

**Files:**
- Create: `server/src/main/java/io/mycat/sqlhandler/config/FileConfigSnapshotStore.java`
- Modify: `server/src/main/java/io/mycat/sqlhandler/config/FileKV.java`
- Modify: `server/src/main/java/io/mycat/sqlhandler/config/FileStorageManagerImpl.java`
- Modify: `server/src/main/java/io/mycat/sqlhandler/config/StdStorageManagerImpl.java`
- Test: `server/src/test/java/io/mycat/sqlhandler/config/FileConfigSnapshotStoreTest.java`（新建）

**Interfaces:**
- `FileKV` 包内新增 `Set<String> rawKeys()`、`Optional<String> getRaw(String key)`、`void putRaw(String key, String rawJson)`；`putRaw` 与删除走强制落盘，不反序列化。
- `FileConfigSnapshotStore` 锁文件 `<home>/.config-commit.lock`，记录 `<home>/.config-recovery.json`；两者均 owner-only。`open` 持 `FileChannel.lock()`；File stage 对读者可见，故读者也必须 open 同一 store 或通过同一锁读取。
- `FileStorageManagerImpl.openConfigSnapshotStore()` 返回新 store；`StdStorageManagerImpl` 原样委托，不得因包装层丢失能力。

- [ ] **Step 1: 写原始 JSON 失败测试** — `readSnapshotPreservesRawBytes` 写入非规范化空白/字段顺序 JSON，readSnapshot 后逐字相等；`replaceTouchesOnlyChangedPaths` 用 mtime/写入计数证明未变五类零写。
- [ ] **Step 2: 写锁失败测试** — 两个 store 实例跨线程/进程语义竞争：第二个在第一个 PREPARED 未结束时不得进入；`snapshotReaderWaitsForCommit` 在写锁释放前不得读到半快照。Windows 用真实 FileChannel 锁，不以 synchronized 代替。
- [ ] **Step 3: 写安全失败测试** — `recoveryRecordIsOwnerOnly`：POSIX 断言权限恰为 0600；Windows 断言 ACL 仅当前 owner principal、不含 Everyone/Authenticated Users；`permissionFailureRejectsCommit` 注入权限设置失败，确认不静默降级。日志捕获断言不含口令/快照。
- [ ] **Step 4: 写持久化失败测试** — `preparedRecordIsDurableBeforeTargetWrite`、`targetFailureLeavesRecoverableRecord`、`restoreVerifiesBeforeMarkingRestored`；用 fake/文件锁制造第 N 项写失败，确认记录可读且补偿后 oldSnapshot 一致。
- [ ] **Step 5: 运行确认 RED** — `-Dtest=FileConfigSnapshotStoreTest`。Expected：API 缺失或锁/ACL/raw/持久化断言失败。
- [ ] **Step 6: 实现 File adapter** — temp 文件写后 `FileChannel.force(true)`，ATOMIC_MOVE 后 force 同目录文件句柄；首次创建记录/目录后 force 父目录。Windows ACL 设置失败即抛错，不回退为宽松权限。
- [ ] **Step 7: GREEN 与 Commit** — 重跑 Step 5，再跑 `ConfigPersistenceTransactionTest,FileConfigSnapshotStoreTest`。Expected：全绿。Commit message：`feat(I-DEBT-133): File 配置快照持久化与跨进程恢复`。

### Task B3: DB 单连接事务与 active 可见代

**Files:**
- Create: `server/src/main/java/io/mycat/sqlhandler/config/DbConfigSnapshotStore.java`
- Modify: `server/src/main/java/io/mycat/sqlhandler/config/DbStorageManagerImpl.java`
- Modify: `server/src/main/java/io/mycat/sqlhandler/config/StdStorageManagerImpl.java`
- Test: `server/src/test/java/io/mycat/sqlhandler/config/DbConfigSnapshotStoreTest.java`（新建）

**Interfaces:**
- `DbConfigSnapshotStore` 构造接收 `DatasourceConfig config`；open 时通过同一个 `Ds`/`Connection` 获取 MySQL advisory lock，关闭时释放。
- 恢复记录使用 config 表保留 key `__config_recovery__`，不进入六类 visible snapshot。stage 只写/更新 PREPARED 记录中的 target/backendState，不改变 active 六类行。
- publish 在一个 serializable、autoCommit=false 的事务中：删除六类旧可见版本、以一个 targetVersion 插入目标行、把同一记录更新为 COMMITTED，然后 commit。任一步失败 rollback。
- `DbStorageManagerImpl.openConfigSnapshotStore()` 返回 adapter；不支持所需隔离/锁/表约束时 open 失败，不退回 `DbKVImpl` 逐 key 写。

- [ ] **Step 1: 写单连接失败测试** — `publishUsesOneConnectionAndOneTransaction`：Mockito mock `DbStorageManagerImpl.Ds.create`/Connection/PreparedStatement；stage、publish、verify 全程只取一次 connection，publish 内 setAutoCommit(false) 后恰有一次 commit。
- [ ] **Step 2: 写可见性失败测试** — `preparedSnapshotIsNotVisible`：fake JDBC 状态模拟 active=old、record=target；stage 后 active 不变，publish 后 active=target 且 record=COMMITTED。
- [ ] **Step 3: 写回滚失败测试** — `publishFailureRollsBackAndKeepsOldVisibleSnapshot`：插入目标第 N 项抛错；assert rollback 被调用、无 commit、active 仍 old、记录仍 PREPARED。
- [ ] **Step 4: 写锁/能力失败测试** — `advisoryLockContentionRejectsSecondWriter`、`missingTransactionalCapabilityRejectsCommit`；验证不会尝试逐 key 独立提交。
- [ ] **Step 5: 写原始快照测试** — `dbReadAndRestorePreserveRawJson`：value 含 RSA 密文和空白，read/stage/restore 不解析对象。
- [ ] **Step 6: 运行确认 RED** — `-Dtest=DbConfigSnapshotStoreTest`。Expected：新 adapter/行为缺失导致断言失败。
- [ ] **Step 7: 实现 DB adapter** — SQL 全部参数化；recovery key 排除在 `readConfig()` 的 visible 结果外；锁等待必须有界并以 `MycatException` 明示后端不支持/锁竞争。
- [ ] **Step 8: GREEN 与 Commit** — 重跑 Step 6，再跑 B1/B2 对应测试。Expected：全绿。Commit message：`feat(I-DEBT-133): DB 配置代单事务发布与恢复`。

### Task B4: 隔离运行态候选、整上下文 CAS 与 commit 编排

**Files:**
- Create: `server/src/main/java/io/mycat/config/RuntimeCandidate.java`
- Modify: `common/src/main/java/io/mycat/MetaClusterCurrent.java`
- Modify: `server/src/main/java/io/mycat/config/MycatRouterConfigOps.java`
- Test: `server/src/test/java/io/mycat/config/CommitAtomicityOrderTest.java`
- Test: `server/src/test/java/io/mycat/config/ConfigCommitTransactionIntegrationTest.java`（新建）

**Interfaces:**
- `MetaClusterCurrent.replaceContext(Map<Class,Object> expected, Map<Class,Object> replacement)`：仅当 `context.get() == expected` 时 CAS，成功返回 true，否则 false。
- `RuntimeCandidate`：持有 `Map<Class,Object> context`、`List<Resource> ownedResources`、`List<Object> swappedOutResources`、旧/新 `ReplicaSelectorManager`；`boolean activate(Map<Class,Object> expected)`、`void giveUp()`、`void giveUpAndRestore()`、`void retireOldResources()`；`giveUp()` 只关闭本轮新资源，不触碰当前 context。构建不注册全局、不原地改旧 manager。
- `MycatRouterConfigOps.buildRuntimeCandidate(UpdateSet<...> sixSets)` 为包内可覆盖方法；`isInit()` 改为实例方法但保留原静态首次语义；`onCommitSuccess()`、`runPostCommitInitialization()` 为包内可覆盖 seam。
- `commit()`：persistence=true 使用 B1 事务；persistence=false 只构建/CAS，不写恢复记录。普通持久化调用方也走同一序列化边界。

- [ ] **Step 1: 写 CAS 失败测试** — `MetaClusterCurrentReplaceContextTest` 或并入集成测试：相同 expected map 成功；不同 identity 失败且原 context 不变。
- [ ] **Step 2: 写隔离失败测试** — `candidateBuildDoesNotMutateOldManagers`：仅新增 datasource/schema 时断言旧 `JdbcConnectionManager.addDatasource`、旧 `MetadataManager.addSchema` 零调用，构建失败后旧 context 每个 Class→对象 identity 完全一致。
- [ ] **Step 3: 写提交顺序失败测试** — `persistPrecedesRuntimeActivation`、`committedPrecedesSuccessSignal`、`postCommitInitializationFailureKeepsTargetConfig`；事件序列必须为 PREPARED→stage→verify→build→activate→COMMITTED→success→init，init 失败不得 restore。
- [ ] **Step 4: 写故障矩阵失败测试** — 第 N 项 stage 失败、verify 失败、build 失败、COMMITTED 失败分别断言：无 Ok/成功信号、旧池未关、旧 context 完整恢复、oldSnapshot 重读一致后才 RESTORED；正向与补偿都失败时明确“恢复不完整”。CAS 失败单独断言竞争 context 及其 replica 不被覆盖/重启，本轮新资源关闭，存储写锁内仍补偿 oldSnapshot 并标 RESTORED。
- [ ] **Step 5: 运行确认 RED** — `-Dtest=ConfigCommitTransactionIntegrationTest,CommitAtomicityOrderTest`。Expected：新接口缺失或顺序/identity 断言失败。
- [ ] **Step 6: 拆分运行态构建与激活** — 删除 datasource-only/schema-only 原地扩缩容分支；构造完整候选 context，延后 `DrdsSqlCompiler` 静态全局参数应用到 activate；activate 停旧 replica、CAS、启动新 replica。非 CAS 失败且 context 仍为 expected 时恢复旧 context/旧 replica；CAS 失败保留竞争 context，不重启其 replica，只 giveup 本轮新资源。
- [ ] **Step 7: 改编排与错误边界** — `COMMITTED` 后 `onCommitSuccess()` 才允许旧资源延迟退休；XA/plan-store/DDL 移到提交后，失败抛出“配置已提交、初始化失败”。HintHandler 仍只在 commit 正常返回后 sendOk；恢复不完整异常不得被吞。
- [ ] **Step 8: GREEN 与回归** — 重跑 Step 5，Expected：原 I-DEBT-130 三案和全部新故障矩阵通过。Commit message：`fix(I-DEBT-133): 配置提交先持久化并原子激活运行态`。

### Task B5: 启动恢复、记录清理与安全回归

**Files:**
- Modify: `server/src/main/java/io/mycat/sqlhandler/config/ConfigPersistenceTransaction.java`
- Modify: `server/src/main/java/io/mycat/sqlhandler/ConfigUpdater.java:63-72`
- Test: `server/src/test/java/io/mycat/sqlhandler/config/ConfigStartupRecoveryTest.java`（新建）
- Test: `server/src/test/java/io/mycat/sqlhandler/config/ConfigRecoverySecurityTest.java`（新建）

**Interfaces:**
- `static ConfigPersistenceTransaction.RecoveryResult recoverOnStartup(StorageManager)`：无记录返回 NONE；PREPARED 恢复 old、校验后标 RESTORED；COMMITTED/RESTORED 校验对应快照并保留记录待装载。`RecoveryResult` 含 `ConfigRecoveryRecord.Status status` 与可空 `String recordId`，供启动流程在装载后按同一身份清理。
- `static void cleanupRecoveryAfterLoad(StorageManager, RecoveryResult recovery, MycatRouterConfig loaded)`：仅当当前 recordId 与 `recovery.recordId` 一致、状态已 RESTORED/COMMITTED、loaded 对应快照且重读校验成功才按 recordId 条件删除；记录已换成新提交或删除失败只 WARN，不得删除新记录。NONE 不执行清理。
- `ConfigUpdater.loadConfigFromFile()` 顺序：recoverOnStartup → quarantineOrphanClusterConfigs → getFileConfigSnapshot → load → cleanupRecoveryAfterLoad。

- [ ] **Step 1: 写崩溃矩阵失败测试** — `preparedStartupRestoresOldSnapshot`、`committedStartupLoadsTargetSnapshot`、`restoredStartupLoadsOldSnapshotThenCleansRecord`；用真实临时 home 预置三种记录。
- [ ] **Step 2: 写拒绝启动失败测试** — `hashMismatchRefusesStartupAndKeepsRecord`、`unreadableRecordRefusesStartup`、`incompleteRestoreRefusesStartup`；断言不调用 load、记录仍存在。
- [ ] **Step 3: 写清理失败测试** — `cleanupFailureWarnsWithoutChangingConfig`：删除记录抛错时 loaded/visible 仍目标或旧快照；`cleanupDoesNotDeleteReplacementRecord`：装载期间替换为新 PREPARED recordId，旧恢复流程不得删除新记录，新提交并发激活时启动装载的 CAS 失败也不得覆盖其 context。
- [ ] **Step 4: 写安全回归** — `recordAndExceptionsNeverContainCredentials`：注入含测试口令的 snapshot，捕获日志与异常，断言仅 recordId/路径/key；File ACL 复用 B2 测试。
- [ ] **Step 5: 运行确认 RED** — `-Dtest=ConfigStartupRecoveryTest,ConfigRecoverySecurityTest`。Expected：恢复 API/启动顺序缺失或安全断言失败。
- [ ] **Step 6: 实现启动恢复** — 恢复先于孤儿扫描和普通快照读取；补偿/恢复共用 `restoreSnapshot`/verify 路径；装载成功后才清理记录。
- [ ] **Step 7: GREEN 与 Commit** — 重跑 Step 5，再跑 B1–B4 测试和 `CommitAtomicityOrderTest`。Expected：全绿。Commit message：`fix(I-DEBT-133): 启动恢复配置事务并保护恢复记录`。

### Task B6: 活体探针、重打 jar、完整护栏与关闭证据

**Files:**
- Create: `ci/idebt133/ResetConfigPreservationProbe.java`
- Create: `ci/idebt133/verify-reset-config-preservation.ps1`
- Create: `ci/idebt133/.gitattributes`
- Modify: `server/src/main/java/io/mycat/sqlhandler/dql/HintHandler.java`（仅在错误映射确需改动时）
- Evidence: `ci/idebt133/live-round1/`、`ci/idebt133/guard-round1/`、`ci/idebt133/ci-round1/`

**Interfaces:**
- 探针使用仓库测试默认后端连接参数，不新增依赖；用 `RSAUtils.genKeyPair()/encrypt()` 生成 root RSA 测试身份，以原始 JSON 种子写入临时 home。
- 探针输出固定 TSV：`preserve.rsa`、`preserve.datasource`、`preserve.cluster`、`reset.schemas`、`failure.old_config_boots`、`recovery.prepared`、`recovery.committed`；任一 FAIL 非零退出。
- 证据目录保留原始日志、`summary.tsv`、`fat-jar.sha256`、`SHA256SUMS`、`archive-provenance.txt`；`.gitattributes` 对日志/TSV/sha256/txt/SHA256SUMS 设 `-text`，根 SHA256SUMS 排除嵌套 SHA256SUMS。

- [ ] **Step 1: 写探针与脚本** — 临时 home 启动新 jar；原 root/RSA 与自定义 datasource/cluster 登录查询；执行 resetConfig 后 schema 消失、六类保留面仍相等。故障格用探针内的 `StorageManager` 装饰器包住真实 File store，仅在 `stageSnapshot()` 的 schemas 步骤抛一次异常，不向生产代码加故障开关；断言命令失败、RESTORED 后 visible 快照等于 oldSnapshot，再单独启动 jar 验证旧配置可加载。
- [ ] **Step 2: 验证探针能红** — 对阶段 A jar 或临时错误期望跑一次，Expected：保留面/事务探针 FAIL 并非零退出；随后用阶段 B 正确 jar 跑回 PASS。没有正确 RED 不得收证据。
- [ ] **Step 3: 重打 jar** — `JAVA_HOME="D:/Program Files/BellSoft/LibericaJDK-21-Full" PATH="D:/Program Files/BellSoft/LibericaJDK-21-Full/bin:$PATH" mvn -o -B -pl server -am package "-Dmaven.test.skip=true"`；Expected：BUILD SUCCESS，生成 `server/target/server-1.22-release-jar-with-dependencies.jar`。
- [ ] **Step 4: 活体验证** — `pwsh -NoProfile -File ci/idebt133/verify-reset-config-preservation.ps1 -Jar server/target/server-1.22-release-jar-with-dependencies.jar -JdkHome "D:\Program Files\BellSoft\LibericaJDK-21-Full"`；Expected：七格 PASS，jar SHA-256 写入证据，失败后旧配置启动格通过。
- [ ] **Step 5: 完整护栏** — `pwsh -NoProfile -File ci/run-example-guard.ps1 -Persona MYSQL_8_0 -Modules server,example -Jar server/target/server-1.22-release-jar-with-dependencies.jar -JdkHome "D:\Program Files\BellSoft\LibericaJDK-21-Full"`；Expected：退出 0/PASS。长命令用 Monitor；退出 1 为真实 RED，退出 2 为 NO_DATA，均不得标完成。
- [ ] **Step 6: 独立复审** — 对阶段 A 起点到当前 HEAD 做 fresh-context 全范围复审，重点核对 Review Focus；Critical/Important 逐项 RED→GREEN，Minor 记录，不盲目实现。
- [ ] **Step 7: 归档与推送** — 原始证据 `git add -f`；生成并校验 SHA256SUMS；提交实现/证据，推送 `github.com/lgscofield/Mycat2.git`，失败按既有命令级 curloptResolve+schannel 重试。
- [ ] **Step 8: 当前 HEAD CI 与关闭** — REST 查询当前完整 HEAD 的 latest run 和 jobs；仅当 `conclusion=success` 且所有非 `conclusion=="skipped"` job 均 success 后，回写完成档案、标 `CI✅ / Evidenced-Done`。否则保持 OPEN。

---

## Spec Coverage Map

| Spec 验收 | 归属 |
|---|---|
| 1 完整保留 / 2 默认边界 / 3 五类零写 | A1、A2 |
| 4 先持久化后切换 / 5 补偿失败 / 7 运行态构建失败 | B1、B4 |
| 6 DB 单事务 / 13 发布可见性 | B3 |
| 8 崩溃恢复 / 12 记录安全与清理 | B2、B5 |
| 9 并发序列化 | B2、B3、B4 |
| 10 既有回归 / 11 活体证据 / 14 两阶段 | A1/A2 回归锁、B6；A 阶段明确不关闭 |
| XA/plan-store/DDL 提交后边界 | B4、B6 |

## Execution Gate

本文件是实施拍板材料，不是生产代码执行授权。用户确认 plan 并选择 Native 或 Subagent-driven 之前，只允许继续修改 spec/plan；不得实施 Task A1–B6。
