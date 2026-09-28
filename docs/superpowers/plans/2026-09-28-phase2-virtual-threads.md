# Mycat2 Phase 2: Virtual Thread 迁移 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除所有导致 Virtual Thread carrier thread pinning 的 synchronized 块，使 Mycat2 充分利用 JDK 21 Virtual Thread 的并发优势。

**Architecture:** 按风险从高到低分批处理 55 个文件、146 处 synchronized。高风险文件（阻塞 JDBC + synchronized）优先处理，每替换一个文件立即运行对应模块的单元测试验证行为不变。

**Tech Stack:** JDK 21, Virtual Threads, ReentrantLock, Executors.newVirtualThreadPerTaskExecutor()

**Spec:** `docs/superpowers/specs/2026-09-28-mycat2-module-redesign-design.md`

**Prerequisite:** Phase 0 + Phase 1 完成

## Global Constraints

- 每个任务完成后 `mvn compile -DskipTests` 必须通过
- 每个任务一个 git commit
- synchronized → ReentrantLock 替换**不改变任何业务逻辑**，仅替换锁机制
- 替换前后使用相同的测试用例验证行为一致性
- 禁止使用 `synchronized` 在可能由 Virtual Thread 执行的路径上
- `Thread.currentThread()` 获取 carrier thread 信息的代码需要额外审查

---

## Milestone 2.1: synchronized → ReentrantLock（55 文件，146 处）

**Gate:** JDK 21 下无 carrier thread pinning 警告（`-Djdk.tracePinnedThreads=full`）

### Task 1: 建立 pinning 检测基准

**Steps:**

- [ ] **Step 1: 用 JDK 21 的 pinning 检测工具扫描**

使用以下 JVM 参数启动 Mycat2，记录所有 pinning 事件：
```bash
-Djdk.tracePinnedThreads=full
```

或通过静态分析扫描所有 synchronized 使用：
```bash
grep -rn "synchronized" --include="*.java" common/src/ server/src/ proxy/src/ datasource/src/ va/src/ hbt/src/ executor/src/
```

记录完整清单（预期 55 文件、146 处）。

- [ ] **Step 2: 按风险等级分类**

将 55 个文件按以下优先级分类：
- **P0 高风险**：阻塞 IO（JDBC/网络）路径上的 synchronized（~8 文件）
- **P1 中风险**：Future/回调实现中的 synchronized（~2 文件）
- **P2 低风险**：工具类/缓存/配置中的 synchronized（~45 文件）

- [ ] **Step 3: Commit 分析结果**

将分析结果写入 `docs/superpowers/plans/vt-pinning-inventory.md`。

```bash
git add docs/superpowers/plans/vt-pinning-inventory.md
git commit -m "docs: add Virtual Thread pinning inventory"
```

---

### Task 2: 修复 NewMycatConnectionImpl (6 处 synchronized)

**Files:**
- Modify: `common/src/main/java/io/mycat/newquery/NewMycatConnectionImpl.java`（或 Phase 1 后的实际路径）

**Context:** 这是 Virtual Thread 执行 JDBC 操作的核心连接类，6 处 synchronized 全部会导致 pinning。

**Steps:**

- [ ] **Step 1: 阅读当前实现**

读取文件，记录每个 synchronized 的位置和用途：
- 方法级 synchronized
- 代码块级 synchronized（标注行号）

- [ ] **Step 2: 逐个替换 synchronized → ReentrantLock**

替换模式：

```java
// 旧 - 方法级 synchronized
public synchronized void doSomething() {
    // ...
}

// 新
private final ReentrantLock lock = new ReentrantLock();
public void doSomething() {
    lock.lock();
    try {
        // ...
    } finally {
        lock.unlock();
    }
}
```

```java
// 旧 - 代码块级 synchronized
synchronized (this) {
    // ...
}

// 新
lock.lock();
try {
    // ...
} finally {
    lock.unlock();
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl common -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: 运行现有测试（如果有）**

Run: `mvn test -pl common`

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(vt): replace synchronized with ReentrantLock in NewMycatConnectionImpl"
```

---

### Task 3: 修复 SyncMessageReader (6 处 synchronized)

**Files:**
- Modify: 定位 `SyncMessageReader.java` 的当前路径

**Steps:**

- [ ] **Step 1-5: 同 Task 2 的模式**

读取 → 逐个替换 → 编译验证 → 测试 → commit

```bash
git add -A
git commit -m "fix(vt): replace synchronized with ReentrantLock in SyncMessageReader"
```

---

### Task 4: 修复 XaLogImpl (6 处 synchronized)

**Files:**
- Modify: `va/src/main/java/cn/mycat/vertx/xa/impl/XaLogImpl.java`（或 Phase 1 后的实际路径）

**Steps:**

- [ ] **Step 1-5: 同 Task 2 的模式**

```bash
git add -A
git commit -m "fix(vt): replace synchronized with ReentrantLock in XaLogImpl"
```

---

### Task 5: 修复 FutureNewMycatConnectionImpl (5 处 synchronized)

**Files:**
- Modify: 定位 `FutureNewMycatConnectionImpl.java` 的当前路径

**Steps:**

- [ ] **Step 1-5: 同 Task 2 的模式**

```bash
git add -A
git commit -m "fix(vt): replace synchronized with ReentrantLock in FutureNewMycatConnectionImpl"
```

---

### Task 6: 修复 VertxMycatConnectionPool (7 处 synchronized)

**Files:**
- Modify: 定位 `VertxMycatConnectionPool.java` 的当前路径

**Steps:**

- [ ] **Step 1-5: 同 Task 2 的模式**

```bash
git add -A
git commit -m "fix(vt): replace synchronized with ReentrantLock in VertxMycatConnectionPool"
```

---

### Task 7: 修复 VertxPoolConnectionImpl (5 处 synchronized)

**Files:**
- Modify: 定位 `VertxPoolConnectionImpl.java` 的当前路径

**Steps:**

- [ ] **Step 1-5: 同 Task 2 的模式**

```bash
git add -A
git commit -m "fix(vt): replace synchronized with ReentrantLock in VertxPoolConnectionImpl"
```

---

### Task 8: 修复 ReplicaDataSourceSelector (8 处 synchronized)

**Files:**
- Modify: `replica/src/main/java/io/mycat/replica/ReplicaDataSourceSelector.java`

**Steps:**

- [ ] **Step 1-5: 同 Task 2 的模式**

```bash
git add -A
git commit -m "fix(vt): replace synchronized with ReentrantLock in ReplicaDataSourceSelector"
```

---

### Task 9: 修复 DbPlanManagerPersistorImpl (13 处 synchronized)

**Files:**
- Modify: 定位 `DbPlanManagerPersistorImpl.java` 的当前路径

> ⚠️ 这是单文件中 synchronized 最多的类（13 处），需要仔细审查每个锁的粒度和保护范围。考虑是否可以合并多个锁或使用 `ReadWriteLock`。

**Steps:**

- [ ] **Step 1: 阅读完整实现，理解锁策略**

特别关注：
- 是否有嵌套 synchronized（死锁风险）
- 读操作 vs 写操作的比例
- 是否适合使用 `ReentrantReadWriteLock`

- [ ] **Step 2: 替换策略决策**

如果读多写少，使用 `ReentrantReadWriteLock`：
```java
private final ReadWriteLock lock = new ReentrantReadWriteLock();

// 读操作
lock.readLock().lock();
try { /* read */ } finally { lock.readLock().unlock(); }

// 写操作
lock.writeLock().lock();
try { /* write */ } finally { lock.writeLock().unlock(); }
```

如果读写均衡，使用 `ReentrantLock`。

- [ ] **Step 3-5: 替换 + 编译验证 + Commit**

```bash
git add -A
git commit -m "fix(vt): replace synchronized with ReadWriteLock in DbPlanManagerPersistorImpl"
```

---

### Task 10: 修复 FutureImpl (9 处) + VertxConnection (4 处)

**Files:**
- Modify: `FutureImpl.java`
- Modify: `VertxConnection.java`

**Steps:**

- [ ] **Step 1-5: 同 Task 2 的模式**

```bash
git add -A
git commit -m "fix(vt): replace synchronized in FutureImpl and VertxConnection"
```

---

### Task 11: 批量修复低风险 synchronized（45 文件）

**Files:**
- Modify: 剩余 ~45 个工具类/缓存/配置文件

**Steps:**

- [ ] **Step 1: 列出所有剩余文件**

从 Task 1 的清单中排除已处理的文件。

- [ ] **Step 2: 按模块分批处理**

每个模块一批，每批替换后编译验证：
1. common 模块的剩余文件
2. server 模块的剩余文件
3. proxy 模块的剩余文件
4. 其他模块的剩余文件

- [ ] **Step 3: 对每个文件执行替换**

使用统一的替换模式（参考 Task 2）。

- [ ] **Step 4: 每批编译验证**

Run: `mvn compile -DskipTests`

- [ ] **Step 5: Commit（每批一个 commit）**

```bash
git add -A
git commit -m "fix(vt): replace remaining synchronized in utility/cache/config classes"
```

---

### Task 12: VT pinning 验证

**Steps:**

- [ ] **Step 1: 全量编译**

Run: `mvn clean compile -DskipTests`

- [ ] **Step 2: 扫描残留 synchronized**

```bash
grep -rn "synchronized" --include="*.java" common/src/ server/src/ proxy/src/ datasource/src/ va/src/ hbt/src/ executor/src/
```

Expected: 零 synchronized（或仅剩注释/文档中的引用）

- [ ] **Step 3: 使用 pinning 检测运行**

如果条件允许，使用 `-Djdk.tracePinnedThreads=full` 启动 Mycat2，验证无 pinning 事件。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: verify zero synchronized in VT execution paths"
```

---

## Milestone 2.2: 执行器迁移

**Gate:** IOExecutor 使用 Virtual Thread + 功能测试通过

### Task 13: IOExecutor → newVirtualThreadPerTaskExecutor()

**Files:**
- Modify: `common/src/main/java/io/mycat/IOExecutor.java`

**Steps:**

- [ ] **Step 1: 阅读当前 IOExecutor 实现**

理解线程池的创建方式和使用场景。

- [ ] **Step 2: 替换线程池创建方式**

```java
// 旧
ExecutorService executor = Executors.newCachedThreadPool();

// 新
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

注意：`newVirtualThreadPerTaskExecutor()` 返回的 executor 不支持 `shutdownNow()` 的强制终止语义（Virtual Thread 不响应 interrupt 如果正在阻塞 IO）。评估是否需要额外处理。

- [ ] **Step 3: 编译验证 + 功能测试**

Run: `mvn compile -DskipTests`
Run: `mvn test -pl common`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(vt): use VirtualThreadPerTaskExecutor in IOExecutor"
```

---

### Task 14: Druid 连接池 VT 兼容性验证

**Steps:**

- [ ] **Step 1: 确认 Druid 1.2.28 的 VT 兼容性**

查阅 Druid 1.2.28 的 release notes，确认是否支持 Virtual Thread。

- [ ] **Step 2: 评估连接池中的 synchronized**

检查 `druid` 库内部的连接池实现是否有 synchronized 导致 pinning。

- [ ] **Step 3: 如果 Druid 不支持 VT，评估 HikariCP 替代方案**

作为备选方案记录评估结果。

- [ ] **Step 4: Commit（仅文档）**

```bash
git add -A
git commit -m "docs: Druid VT compatibility assessment"
```

---

### Task 15: MySQL JDBC 驱动 VT pinning 评估

**Steps:**

- [ ] **Step 1: 评估 mysql-connector-j 9.3.0 的 VT 支持**

MySQL Connector/J 9.x 声称支持 Virtual Thread。验证是否完全无 pinning。

- [ ] **Step 2: 评估 MariaDB Connector/J 3.5.3 的 VT 支持**

MariaDB Connector/J 通常比 MySQL 官方驱动更轻量。

- [ ] **Step 3: 记录评估结果**

```bash
git add -A
git commit -m "docs: MySQL/MariaDB JDBC driver VT pinning assessment"
```

---

## Phase 2 完成验证

- [ ] 所有 55 个文件的 synchronized 已替换为 ReentrantLock
- [ ] `mvn clean compile -DskipTests` BUILD SUCCESS
- [ ] IOExecutor 使用 Virtual Thread executor
- [ ] `-Djdk.tracePinnedThreads=full` 无 pinning 事件（或已记录已知 pinning 及原因）
- [ ] Druid 和 JDBC 驱动的 VT 兼容性已评估并记录

## 后续阶段

- **Phase 3**（MariaDB 接入）→ `2026-09-28-phase3-mariadb.md`
