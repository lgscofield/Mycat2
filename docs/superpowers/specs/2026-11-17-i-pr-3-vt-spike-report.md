# I-PR-3 虚拟线程 spike — 最终裁定报告

日期：2026-11-17 · 性质：**throwaway 探针，零产品码改动** · HEAD=`1c6a5313a`

**证据归档位置（git  tracked，本文件与全部原始数据已入库）**：
- 本报告 → `docs/superpowers/specs/2026-11-17-i-pr-3-vt-spike-report.md`
- 静态清单 → `docs/superpowers/specs/2026-11-17-i-pr-3-pinning-inventory.md`
- 探针 / 跑批脚本 / TSV / 原始日志 → **`ci/vtspike/`**
  （`VtSpike.java`、`DruidPinProbe.java`、`run-spike{,2,2b,2c,3}.sh`、`summary{,2,2b,2c,3}.tsv`、`logs{,2,2b,2c,3}/`、`spike{,2,2b,2c,3}.log`）
- 下文所有相对路径（`pinning-inventory.md` / `summary*.tsv` / `logs*/`）均指 **`ci/vtspike/`** 下的同名归档件；
  静态清单指上述 specs 目录件。跑批脚本的 `WS` 已改为 `ci/vtspike`，**从仓库根目录可直接复跑**。
- 复跑前置：JDK21（`D:/Program Files/BellSoft/LibericaJDK-21-Full`，**不可用 JAVA_HOME，它会漂到 JDK-25**）、
  fat jar `server/target/server-1.22-release-jar-with-dependencies.jar`、活体后端 `m1-mysql-a`（mysql:8.0，:3306）。
- **数据 hygiene 订正**：round 1 的 `summary.tsv` 原含 15 行由 `PIN=$(grep -c … || echo 0)` 双零 bug 产生的
  残行（`0\ttrue`），归档前已按"15 字段行 + 后继残行 → 补 `finished=true`"确定性合并，
  现为 **18 行 × 16 字段、零畸形行**；**数值未改动**。round 2 起的脚本已用 `|| true` 修掉该 bug。
- **归档例外说明**：仓库 `.gitignore:5` 有全局 `*.log` 规则（针对构建/运行期 transient 日志）。
  本 spike 的 **53 份 per-run 日志 + 5 份跑批汇总日志是裁定的一手证据**
  （Q1 的 `<== monitors:1` 热点帧、Q3 的"热点统计为空"都只能从原始 trace 复核），
  故以 `git add -f` **刻意破例入库**，属归档证据而非运行日志。
  **复跑时新产生的 `.log` 仍被 ignore**（不会污染工作树），需要重新归档时再 `-f` 一次。

配套：`pinning-inventory.md`（静态半区）· `summary.tsv`/`logs/`（round 1，18 跑）·
`summary2.tsv`/`logs2/`（round 2，11 跑）· `summary2b.tsv`/`logs2b/`（round 2b 修正臂，9 跑）·
`summary2c.tsv`/`logs2c/`（round 2c 交错复跑，9 跑）· `summary3.tsv`/`logs3/`（round 3 第三方隔离，6 跑）
**合计 53 跑**（round1 18 + round2 11 + round2b 9 + round2c 9 + round3 6）。

---

## 0. 这轮要回答什么

用户拍板「**先 spike 出数据再定池形态**」⇒ I-PR-1（IOExecutor 无界池+背压+名实相符）的池形态
不由先验决定，由本探针的实测数据决定。三个必须回答的问题：

| # | 问题 | 为什么它决定 I-PR-1 |
|---|---|---|
| Q1 | 把 `IOExecutor.DEFAULT` 的 `newCachedThreadPool()` 直接换成 VT，会不会 pin？pin 在哪？ | 若 pin 在主查询路径，VT 不是 drop-in，I-PR-1 就不能选「直接换 VT」这条最省事的路 |
| Q2 | VT 相比平台池，吞吐/尾延迟/线程数各差多少？ | 决定「加界平台池」与「改 VT」哪个更划算 |
| Q3 | 第三方（Druid 1.2.28 / Connector/J 9.3.0）自己会不会 pin？ | 若第三方 pin，则改 Mycat 的锁也救不回来，VT 形态直接否决 |

**探针形状对齐 Mycat 真实路径**：`createStatement(TYPE_FORWARD_ONLY, CONCUR_READ_ONLY)` +
**`setFetchSize(Integer.MIN_VALUE)`**（Mycat 的流式读标志）+ 完整 `while(rs.next())` 循环；
后端 = 活体 `m1-mysql-a`（mysql:8.0，Docker，:3306）；连接池 = Druid，与生产同版本。
负载 SQL = `WITH RECURSIVE seq(n) ... SELECT n, REPEAT('x',64)`（200 行，纯后端产生，排除客户端解析开销）。

---

## 1. 测量口径与一个必须揭露的假象

### 1.1 延迟窗口

`VtSpike.java:104` 起表 → `:107 lanes.take()`（**排队等连接**）→ `:108 runQuery` → `:110` 停表。
故 round 1 的 `p50/p95/p99` **含 lane 排队**，是端到端口径，不是纯 SQL 耗时。

### 1.2 ⚠ vt+sync 的"尾延迟更好"是假象，不可采信

round 1 原始数据里 `vt/sync` 的 p99 只有 94ms，而 `vt/reentrant` 是 3860ms。
**这不是 pinning 反而更快**，而是计时窗口错位：

- `peakInFlight` 在 `vt/sync` 三行分别是 **40 / 15 / 20**，而其余所有行是 **64 / 512 / 2048**（= 提交的并发数）。
- `inFlight` 在**任务体内**才自增（`:102`）。它只有 20 ⇒ **2048 个任务里只有约 20 个真正开始跑了**。
- 机制：VT 在 monitor 内阻塞 ⇒ carrier 被 pin 住 ⇒ ForkJoinPool 的 carrier（≈核数）全被占满 ⇒
  其余 VT **停在 FJP 提交队列里尚未启动** ⇒ 排队发生在 `:104` 起表**之前**，完全不被计量。

⇒ **pinning 在这里充当了"意外的准入控制"**：它把并发压到 carrier 数，于是每个真正跑起来的任务都很快，
但代价是总墙钟时间暴涨。**唯一可跨臂比较的诚实指标是 `wallMs` / `qps`。**

round 2 已给探针补 `startDelay`（submit→任务启动）与 `e2e = startDelay + lat`，把这个隐藏排队显式化。

### 1.3 方差

同一 C 下不同 lock 臂的墙钟差可达 50%（如 cached/C2048：sync 3035 / reentrant 2956 / none 4578），
`none` 反而最慢属反直觉 ⇒ 存在 JVM 预热顺序 + Docker 宿主负载的运行间方差。
**故本报告只对"跨全部三个 C 一致成立"的大效应下结论**，小差异一律标注为噪声内，不作裁定依据。
round 2 含同配置复跑以量化方差。

---

## 2. Round 1 实测：无界 cached 平台池 vs VT（18 跑，全 2048 ops，errors=0，finished=true）

固定 `pool=16`（lane 数）`rows=200`，`C ∈ {64,512,2048}`，`iter=2048/C`。

### 2.1 平台线程数 —— 最大且无争议的效应

| C | cached（三臂） | VT（三臂） | 倍数 |
|---|---|---|---|
| 64 | 75 / 75 / 75 | **27 / 27 / 27** | 2.8× |
| 512 | 495 / 494 / 508 | **27 / 27 / 27** | ~18× |
| 2048 | 1595 / 1551 / 1782 | **27 / 27 / 27** | **57–66×** |

**cached 的线程数随并发线性增长**（C=2048 → 1595~1782 个平台线程），这正是 I-PR-1 要治的"无界"。
**VT 恒为 27**，与并发完全解耦。
⇒ 单看线程占用，VT 是压倒性的。

**本机口径（实测）**：`availableProcessors = 12`；JDK21 VT 调度器默认
`jdk.virtualThreadScheduler.parallelism = 核数 = 12`、`maxPoolSize = 256`。
27 ≈ 12 个 carrier + main/sampler/Druid 后台/FJP commonPool 等固定开销。

**关键补充**：`vt/sync` 三跑在 carrier 被 pin 满的情况下，`peakPlatformThreads` **仍是 27，没有向 256 补偿扩容**
⇒ **JDK21 的 FJP 不会为"VT 被 monitor pin 住"自动补 carrier**（pinning 不是 `ManagedBlocker` 式的受管阻塞）。
这就是 §2.3 吞吐掉 46–63% 的直接机制：并发被硬限在 12 个 carrier 上，且**不会自愈**。
（对照 round 3：无 Mycat 锁时 `peakPlatformThreads=28`、`peakInFlight=C`，VT 正常 unmount，无需补偿。）

### 2.2 pinning 热点 —— 只有 Mycat 形状的锁，第三方零命中

`-Djdk.tracePinnedThreads=full` 全 18 跑统计：

```
9 VtSpike.runQuery(VtSpike.java:167) <== monitors:1
```

- **9 = 3 个 vt/sync 跑 × 每跑 3 条 trace**，全部指向探针自己那句 `synchronized (lane)`。
- **`vt/reentrant` 与 `vt/none` 六跑：pinnedFrames = 0。**
- trace 栈里虽然出现大量 `com.mysql.cj.*` 与 `com.alibaba.druid.*` 帧，但 `<== monitors:1` 标记
  **落在 `VtSpike.runQuery:167`（我的 monitor）上，不在任何第三方帧上**。

⇒ **Q1 答案：pin 100% 来自 Mycat 自己的 `synchronized`，不是第三方。**
这与静态清单 §2 的 P0 判定完全吻合：`NewMycatConnectionImpl:109` 的方法级 synchronized
包住 `executeQuery` + 整个流式 `rs.next()` 循环，形状与探针的 `synchronized(lane){ jdbc(...) }` 同构。

trace 还坐实了**被 pin 住的具体阻塞点**（三处，全在流式路径上）：
1. `StatementImpl.setupStreamingTimeout` → `executeSimpleNonQuery` → socket read（**流式查询开场的那次往返**）
2. `StatementImpl.executeQuery:1274` → `sendQueryPacket` → socket read
3. `ResultsetRowsStreaming.close:134` → `ResultSetImpl.close` → socket read（**流式收尾 drain**）

⇒ 即 §2.1 静态预判的"**从 executeQuery 到流结束全程 pin**"被实测逐帧证实。

### 2.3 pinning 的吞吐代价 —— 跨全部 C 一致，效应大

| C | vt/sync wallMs | vt/reentrant wallMs | 倍数 | vt/sync qps | vt/reentrant qps |
|---|---|---|---|---|---|
| 64 | 8007 | 2992 | **2.68×** | 255.8 | 684.5 |
| 512 | 6646 | 3304 | **2.01×** | 308.2 | 619.9 |
| 2048 | 7229 | 3896 | **1.86×** | 283.3 | 525.7 |

⇒ **不换锁直接上 VT，吞吐掉 46%–63%。** 静态清单 §6.1 的预判（"大概率劣于今天的 cached 平台池"）
被证实且更严重：vt/sync 的 255–308 qps **连 cached 的 447–770 qps 都远不及**。

### 2.4 换锁后 VT vs 无界 cached —— 这才是可比的对照

| C | cached/reentrant qps | vt/reentrant qps | VT 相对 | cached 线程 | VT 线程 |
|---|---|---|---|---|---|
| 64 | 607.5 | **684.5** | **+12.7%** | 75 | 27 |
| 512 | 598.3 | **619.9** | +3.6%（噪声内） | 494 | 27 |
| 2048 | 692.8 | 525.7 | **−24.1%** | 1551 | 27 |

（`cached/none` 与 `vt/none` 同向：C64 672.1 vs 622.9、C512 604.1 vs 502.6、C2048 447.4 vs 492.7。）

**读法**：lane 只有 16 条 ⇒ 吞吐天花板 ≈ 16 / 单查询耗时 ≈ 700 qps，cached 在三个 C 都基本贴着天花板。
VT 在 C≤512 也贴住甚至略超；C=2048 时 round 1 显示掉到 ~525 qps。

### 2.5 ⚠ 复跑推翻上表的 C=2048 结论：VT 与 cached 的吞吐差**在方差内**

round 2 同配置复跑（`summary2.tsv`）：

| 臂 | round 1 qps | round 2 复跑 qps | 变化 |
|---|---|---|---|
| cached/reentrant C2048 | 692.8 | **503.6** | **−27%** |
| vt/reentrant C2048 | 525.7 | **488.8** | −7% |
| vt/sync C2048 | 283.3 | **299.8** | +6% |

⇒ cached 在 C=2048 的实测区间是 **447–693 qps**（含 `none` 臂），VT/reentrant 是 **489–526 qps**。
**两区间重叠**。复跑那一轮里 cached 503.6 vs VT 488.8，差仅 **−3%**。

**故 §2.4 的"VT 高并发掉 24%"不成立，本报告撤回该说法。**
正确表述：**换锁后 VT 与无界 cached 的吞吐在本探针形状下无可辨差异（差值落在运行间方差内），
而平台线程数差 57–66×。** 唯一稳健的吞吐效应是 §2.3 的 **pinning 代价（1.86–2.68×，跨全部 C 一致，且复跑确认）**。

### 2.6 startDelay 把 §1.2 的假象量化坐实

round 2 新增的 submit→启动 延迟（`rep-vt-sync-C2048`）：

| 指标 | 值 |
|---|---|
| `p50ms`（任务体内计时，round 1 口径） | **35.42** |
| `startP50ms`（提交→任务真正启动） | **3486.60** |
| `startP95ms` | **6404.14** |
| `e2eP50ms`（= startDelay + lat） | **3556.39** |
| `peakInFlight` | **17** |

⇒ **3.5 秒的排队完全发生在计时窗口之外**。§1.2 的推断由"peakInFlight 异常"升级为**直接计量证据**。
对照 `rep-vt-reentrant-C2048`：`startP50=8.92ms`（可忽略），`e2e≈lat`；
`rep-cached-reentrant-C2048`：`startP50=65.92ms / startP95=354.25ms`（= 无界池现场造线程的成本）。

⇒ **Q2 答案：VT 不是"用吞吐换线程"，而是"吞吐持平、线程数降 57–66×"——前提是 P0 锁先换掉。
不换锁则吞吐掉 46–63%（§2.3，稳健）。**

---

## 3. Round 2 实测：有界平台池臂 —— 撞出 I-PR-1 的头号设计地雷

### 3.1 ⚠⚠ 最重要发现：`corePoolSize` 小 + `maxPendingLimit` 深队列 = **静默退化成单线程**

round 2 的 bounded 臂用 `ThreadPoolExecutor(core=0, max=64, 60s, ArrayBlockingQueue(65535), AbortPolicy)`，
结果**全部 7 跑**都是：

| tag | C | max | queue | qps | **peakInFlight** | **peakPlatformThreads** | startP50ms |
|---|---|---|---|---|---|---|---|
| bounded64-reentrant | 64 | 64 | 65535 | **146.6** | **1** | **12** | 6583 |
| bounded64-reentrant | 512 | 64 | 65535 | **130.5** | **1** | **12** | 8249 |
| bounded64-reentrant | 2048 | 64 | 65535 | **127.4** | **1** | **12** | 8232 |
| bounded64-none | 2048 | 64 | 65535 | 103.8 | **1** | **12** | 10890 |
| bounded16-reentrant | 2048 | 16 | 65535 | 115.6 | **1** | **12** | 8918 |
| bounded256-reentrant | 2048 | 256 | 65535 | 129.2 | **1** | **12** | 7576 |
| bounded64-sync | 2048 | 64 | 65535 | 115.4 | **1** | **12** | 9220 |

`peakInFlight=1` ⇒ **2048 个任务里同一时刻只有 1 个在跑**；`peakPlatformThreads=12` = 纯基线
（Druid + sampler + main），**池一个工作线程都没多造**。`max` 设 16 / 64 / 256 **完全无差别**。

**根因（`ThreadPoolExecutor` 的既定语义，不是 bug）**：
TPE 的扩容规则是「core 满 → **入队** → **队列满** → 才扩到 max」。
`cap=65535` 而只提交 2048 个任务 ⇒ **队列永远填不满** ⇒ 永远不扩容 ⇒
`core=0` 时 TPE 只保证**一个**线程在 drain 队列 ⇒ **整个"有界池"退化成单线程串行**。

**这不是探针的无关失误，这是 I-PR-1 的真实地雷**，因为生产配置正是这个组合：

| 来源 | corePoolSize | maxPoolSize | maxPendingLimit |
|---|---|---|---|
| `config/.../ThreadPoolExecutorConfig.java:19-24`（字段默认） | **0** | 1024 | **65535** |
| `config/.../ServerConfig.java:40-48`（workerPool 默认） | `availableProcessors` | 1024 | **65535** |
| **`server/src/main/resources/server.json`（实际生效）** | **1** | 1024 | **65535** |

⇒ **若 I-PR-1 把 `maxPendingLimit` 直接实现成 `ArrayBlockingQueue(65535)` 挡在 `corePoolSize=1` 前面，
Mycat 的全部阻塞 IO 会静默串行化成单线程**，吞吐从 ~700 qps 掉到 ~127 qps（**−82%**），
而且**不报任何错、不抛任何异常、监控看起来"队列很健康"**。
这比今天的无界池更糟，且极难在生产中被发现（症状只是"变慢"）。

**I-PR-1 spec 的强制约束（由本数据直接推出）**：
1. **不得**把 `maxPendingLimit` 实现为 TPE 的 work queue 容量而同时保留小 `corePoolSize`。
2. 若要有界线程 + 背压，必须**显式解耦**「线程数上限」与「准入队列深度」——
   例如 `core = max`（或 `prestartAllCoreThreads`）+ **独立的准入信号量/闸门**在提交前挡，
   而不是依赖 TPE 的队列满才扩容语义。
3. `corePoolSize` 的默认值 **0**（`ThreadPoolExecutorConfig:19`）本身就是危险默认，须一并裁定。
4. 必须有一个**活体断言**锁住"池真的并发起来了"（如 `peakInFlight > 1` / `count()` 反映真实并发），
   否则这类静默串行化无法被回归测试发现。

> 本轮已用 `run-spike2b.sh` 显式设 `core` 重跑修正臂（含**故意复刻 server.json 的地雷组合**
> `core=1, max=1024, queue=65535` 作为反例证据）。数据见 `summary2b.tsv`，本节 §3.4 待填。

### 3.2 拒绝臂：浅队列下的硬拒绝被量化

`bounded16q8-reentrant-C2048`（`core=0, max=16, cap=8`）：

| 指标 | 值 |
|---|---|
| ops（成功） | **34** |
| **rejected** | **2014** |
| errors | 0 |
| peakPlatformThreads | 27 |
| peakInFlight | 16 |
| wallMs | 123 |

⇒ 队列浅（8）时 TPE **确实扩到了 max=16**（`peakInFlight=16`、线程 27），然后**硬拒绝 98.3% 的提交**
（2014/2048）。`errors=0` 是因为探针把 `RejectedExecutionException` 单独计数了。

**这正是已拍板 invariant「释放腿独立车道永不拒绝」要避开的失败模式**：
若归还/abandonConnection/abandonQuery/force-close 走这条被拒的路，
**连接归还不了 ⇒ 池泄漏 ⇒ 后续全部查询借用超时**，即 I-DEBT-81/84 刚治好的那类故障复发。
对照 §3.1：深队列不拒绝但静默串行化，浅队列并发正常但硬拒绝 ⇒
**单靠 TPE 的 core/max/queue 三个旋钮无法同时满足"有界 + 不串行化 + 释放腿不拒"**，
必须显式分车道。这是本 spike 给 I-PR-1 的最硬一条设计输入。

### 3.3 方差复跑（见 §2.5）与 startDelay（见 §2.6）

`rep-cached-reentrant-C2048` / `rep-vt-reentrant-C2048` / `rep-vt-sync-C2048` 三跑推翻了对 cached 的
单点读数依赖，并把 pinning 的隐藏排队量化为 **startP50 = 3486.60ms**。

### 3.4 修正臂实测（`core` 显式设定，9 跑，全 `errors=0 finished=true`）

| tag | core | max | queue | C | **qps** | **peakPlatformThreads** | **peakInFlight** | startP50ms |
|---|---|---|---|---|---|---|---|---|
| b-core64-reentrant | 64 | 64 | 65535 | 64 | 334.6 | 75 | 64 | 53 |
| b-core64-reentrant | 64 | 64 | 65535 | 512 | 264.2 | 75 | 64 | 2478 |
| b-core64-reentrant | 64 | 64 | 65535 | 2048 | **657.9** | 75 | 64 | 1701 |
| b-core64-none | 64 | 64 | 65535 | 2048 | 621.9 | 75 | 64 | 1840 |
| **b-core64-sync** | 64 | 64 | 65535 | 2048 | **694.9** | 75 | 64 | 1570 |
| **b-core16-reentrant** | **16** | 64 | 65535 | 2048 | **677.0** | **27** | **16** | 1747 |
| b-core256-reentrant | 256 | 256 | 65535 | 2048 | 630.9 | 267 | 256 | 1375 |
| **b-core1-max1024（地雷反例）** | **1** | **1024** | **65535** | 2048 | **129.1** | **12** | **1** | 7486 |
| b-core64q8（拒绝臂） | 64 | 64 | **8** | 2048 | ops=**102** / rejected=**1946** | 75 | 64 | 30 |

#### 结论 A：地雷用**生产原值**复刻成功，且是确定性的

`core=1, max=1024, queue=65535`（= `server.json` workerPool 的三个原值）⇒
`peakInFlight=1`、`peakPlatformThreads=12`（纯基线）、**qps 129.1**。
对照正确配置的 `core=16` ⇒ **qps 677.0**。**−81%，且 `max=1024` 完全没起作用。**
round 2 的 `core=0` 与本轮 `core=1` 两个独立配置都复现同一症状 ⇒ **不是偶然，是 TPE 语义的必然**。

#### 结论 B：**深队列下 `maxPoolSize` 是装饰品，`corePoolSize` 才是真上限**

`b-core16`（core=16, **max=64**）实测 `peakInFlight=16`、线程 27 ⇒ **从未扩到 max**。
`b-core64`（core=64, max=64）恒 64。`b-core256` 恒 256。
⇒ 队列填不满时，**有效并发 == corePoolSize，与 maxPoolSize 无关**。

这给 I-PR-1 的票名「**名实相符**」补了第二个具体含义：
今天 `MycatCore:125` 把 `workerPool.getMaxPoolSize()`（=1024）喂给 `vertxOptions.setWorkerPoolSize`，
而真实 IO 腿走的是 `:135` 的 `IOExecutor.DEFAULT`（无界 cached，1024 也不生效）。
**若改成 TPE+深队列，1024 仍然不生效，生效的会是 `corePoolSize=1`。**
配置项的"名"与"实"在两种实现下都是错的，只是错法不同。spec 必须明确**哪个旋钮真正约束什么**。

#### 结论 C：`synchronized` 的代价**确属 VT 特有**（干净对照）

同为 `core=64` 平台池、同 C=2048：**sync 694.9 qps vs reentrant 657.9 qps** ⇒
平台线程上 `synchronized` **不但没有惩罚，还略快**（差值在方差内）。
对照 round 1 的 VT：sync 283.3 vs reentrant 525.7 ⇒ **−46%**。
⇒ **§2.3 的 pinning 代价归因成立，不是"锁本身贵"，而是"VT + monitor + 阻塞"三者叠加才贵。**

#### 结论 D：有界平台池的线程数可压到与 VT 相同（**吞吐部分已由 §3.5 撤回**）

`b-core16`：平台线程 **27** —— 与 VT 的 27 **完全相同**（`peakInFlight=16`，从未扩到 max=64）。
机制：lane 只有 16 条，**16 个线程就足以喂满 16 条 lane**，多造线程纯属浪费。
⇒ **"加界"不必然牺牲线程治理效果；界设在"真实并发资源数"附近时，线程占用与 VT 等效。**

> ⚠ **本结论的 qps 部分（原写"677.0 贴住天花板、高于 VT"）已被 §3.5 的交错复跑撤回**：
> 复跑掉到 233.8 / 270.7，四臂区间重叠，吞吐无可分辨差异。**只有线程数 27 这一半成立。**

#### ⚠ 结论 D 的适用边界（不可外推，I-PR-1 spec 必须处理）

`core=16` 够用的前提是**探针里每个线程拿到的活都恰好对应一条可用 lane**。
生产不是这样：阻塞腿里有一类活是**等池借用**（`JdbcConnectionManager.getConnection` → Druid `maxWait`；
心跳腿 `JdbcConnectionManager:242-251`；序列腿 `DefaultPrototypeGenerator:108`），它**不持有 lane，而是在等 lane**。

⇒ 若把 IO 线程数硬界在 N，而 N 个线程全部卡在"等一个饱和池的借用"，
则**没有任何线程能去执行归还/释放**，而归还恰恰是解锁借用的前提 ⇒ **自锁死**。
这正是已拍板 invariant「**释放腿独立车道永不拒绝**」的硬理由，且它比"被拒绝"更严重：
**被拒绝只是失败，自锁死是永久挂起。**

**VT 的结构性优势正在此处**：VT 把"线程数"与"并发数"解耦，
一个 VT 卡在借用等待上**不消耗稀缺 worker**（carrier 被释放），故不存在"N 个 worker 全卡住"的自锁死。

> **论据强度订正（2026-11-17）**：本节原引用 `TransactionSessionTemplate:200`
> 「全局监视器内做阻塞池借用」作为该风险的**活体锁证据**。**该引用已撤销——那个类是死码**
> （abstract + 唯一子类整文件注释 + `createSession` 注释 + 全仓零引用；活体 `TransactionSession`
> 是 `proxy/.../MycatXaTranscation`，其 synchronized 命中数 = **0**）。详见 `pinning-inventory.md` §2.4。
> ⇒ 自锁死风险目前**只有机制推演 + 活体借用点（Druid maxWait / 心跳 / 序列）支撑，无活体锁证据**，
> 且**本轮未做运行时演示**（探针未建模释放腿）。**降级为待证推演**，
> 须由 I-PR-1 实现期的活体矩阵证实，**不得作为裁定的硬依据**。

#### 结论 E：拒绝臂在正常核数下仍然硬拒

`core=64, cap=8` ⇒ ops **102** / rejected **1946**（**95%**）。
（比 round 2 的 `core=0,cap=8` 的 34/2014 略好，因为 64 个核线程先接了一批。）
⇒ **浅队列 = 硬拒绝**，与 §3.2 同结论：**释放腿绝不能走这条路**。

> 方差说明：`b-core64` 在 C64/C512/C2048 三档读出 334.6 / 264.2 / 657.9，跨度 2.5×，
> 显系宿主负载漂移（C64/C512 两跑紧接 round 3 之后）。故本节裁定**只采信结论 A/B/C/E 这类
> 有机制解释且量级巨大的效应**；结论 D 的 qps 绝对数字**已由 `run-spike2c.sh` 交错复跑撤回**（§3.5），
> 只保留其线程数一半。

### 3.5 交错复跑确认（round 2c，9 跑）—— **结果：吞吐差异不可分辨，必须撤回结论 D 的绝对数字**

`bcore16` / `vt` / `bcore64` / `cached` 各 2 次**交错**执行（排除宿主负载漂移系统性偏袒某臂）+ 地雷反例 1 次：

| 臂 | 2b 读数 | **2c-r1** | **2c-r2** | 全轮区间 | peakPlatformThreads |
|---|---|---|---|---|---|
| bounded core16 | 677.0 | **233.8** | **270.7** | **234–677** | **27**（恒定） |
| vt reentrant | — | **243.7** | **466.4** | **244–526**（含 r1/r2 轮） | **27**（恒定） |
| bounded core64 | 657.9 | **242.3** | **625.5** | **242–658** | **75**（恒定） |
| cached reentrant | — | **303.5** | **693.8** | **304–694** | **1515–1782** |
| **地雷 core1/max1024/q65535** | **129.1** | — | **126.5**（r3） | **126.5–129.1** | **12**（恒定） |

#### 结论 F：**宿主负载是共模噪声，吞吐裁决在本环境不可做**

`2c-r1` 四臂**全部**慢（233.8 / 243.7 / 242.3 / 303.5），`2c-r2` 四臂**全部**快（270.7 / 466.4 / 625.5 / 693.8）
⇒ 波动是**共模的宿主负载漂移**，不是臂间差异。四臂区间**大幅重叠**（234–694）。

⇒ **撤回结论 D 的"core16 = 677 qps 贴住天花板"**：那是单次幸运读数，复跑掉到 234–271。
**正确表述：cached / VT / bounded-64 / bounded-16 四臂的吞吐在本测量环境下无可分辨差异。**

⇒ **唯一稳健的吞吐效应是地雷臂**：`core=1,max=1024,queue=65535` 三次独立运行
（round2 `core=0` 127.4、2b `core=1` 129.1、2c `core=1` 126.5）**全部落在 126–130 qps**，
`peakInFlight` 恒 **1**、线程恒 **12**。**它稳定，因为它单线程、不受争用噪声影响。**
这反而是最强的证据形态：**一个对噪声免疫的 −81% 效应。**

#### 结论 G：**线程数是唯一可分辨的性能维度**

| 形态 | 平台线程数（C=2048，全轮恒定） |
|---|---|
| cached（今天的 `IOExecutor.DEFAULT`） | **1515–1782** |
| bounded core64 | **75** |
| bounded core16 | **27** |
| **VT** | **27** |
| 地雷 core1 | 12（但并发=1，不可用） |

⇒ **VT 与 bounded-core16 的线程占用完全相同（27），且都不随并发增长。**
「加界」与「改 VT」在**线程治理**这个 I-PR-1 的核心目标上**等效**。

#### 对裁定的直接影响

既然**吞吐不可分辨、线程治理等效**，则池形态**不能按性能选**，只能按**工作量与风险**选。
这是本 spike 最重要的方法论结论：**它否决了"用性能数据决定池形态"这个原始预期**——
数据说的是"性能分不出胜负"，于是决定权回到工程成本与回归面。详见 §8。

> **诚实边界**：本环境（Windows + Docker Desktop 后端 + 共享宿主）噪声达 2–3×，
> **不足以支撑任何吞吐层面的形态裁定**。若将来需要按吞吐选形态，
> 必须在静默硬件 + 生产形状负载（多后端、真实前端会话数、真实结果集大小）上重做基准。
> 本轮**不声称**做到了这一点。

---

## 4. Round 3 实测：第三方自己会不会 pin（Q3）—— **答案：不会**

`DruidPinProbe`：路径内**无任何 Mycat 锁**，每 op 自行 `ds.getConnection()` → 流式查询 → `close()`。
6 跑全 `ops=2048 errors=0 finished=true`。

| tag | mode | C | pool | qps | **borrowP95ms** | p95ms | peakPlatformThreads | peakInFlight | **pinnedFrames** |
|---|---|---|---|---|---|---|---|---|---|
| vt-C64-pool16 | vt | 64 | 16 | 354.3 | 589.87 | 622.90 | **28** | 64 | **0** |
| cached-C64-pool16 | cached | 64 | 16 | 352.0 | 623.07 | 666.59 | 75 | 64 | 0 |
| vt-C512-pool16 | vt | 512 | 16 | 361.3 | 4541.40 | 4573.02 | **28** | 512 | **0** |
| cached-C512-pool16 | cached | 512 | 16 | 377.6 | 3909.17 | 3934.06 | 502 | 473 | 0 |
| **vt-C512-pool4** | vt | 512 | **4** | 254.9 | **6159.51** | 6176.37 | **28** | 512 | **0** |
| cached-C512-pool4 | cached | 512 | **4** | 280.0 | 5600.03 | 5611.71 | 519 | 503 | 0 |

`-Djdk.tracePinnedThreads=full` 下 **vt 三跑的 pinning 热点统计为空**（零条 `<== monitors`）。

### 4.1 三条结论

1. **Druid 1.2.28 的饱和借用等待不 pin。** `pool=4, C=512` 制造了深度饱和——
   `borrowP95 = 6159ms`（借用真的阻塞等了 6 秒），而 `pinnedFrames` 仍是 **0**。
   Druid 内部用 `ReentrantLock` + `Condition.await`，VT 可正常 unmount。
   ⇒ 静态清单 §5 表里「须实测」的那一格，**实测结果是干净的**。
2. **Connector/J 9.3.0 的 classic protocol 流式读不 pin**（`setFetchSize(Integer.MIN_VALUE)` + 全 `rs.next()` 循环）。
   与 round 1 对照可精确定位因果：round 1 的 vt/sync trace 里**同样这些 mysql socket read 帧**被 pin，
   唯一差别是外层多了探针的 `synchronized(lane)`。⇒ **pin 的充要条件是 Mycat 自己的 monitor，不是 JDBC 栈。**
3. **`peakInFlight` 恒等于 C**（64/512/512）⇒ 所有 VT 都真正启动了，无 §1.2 那种隐藏排队。
   这独立佐证了"零 pinning"。

### 4.2 顺带得到的第二个对照：真实借用路径下 VT vs cached

本探针每 op 真借真还（含 Druid 开销），故 qps 天花板（~350）低于 `VtSpike` 的预借 lane（~700）。
在这个更接近 Mycat 的形状下：**VT 与 cached 吞吐持平**（354.3 vs 352.0；361.3 vs 377.6；254.9 vs 280.0，
差值 −4%~−9% 落在 §1.3 的方差内），**而平台线程数 28 恒定 vs 75/502/519 随并发增长**。

⇒ **Q3 答案：第三方不是 VT 的阻塞项。VT 的全部前置条件都在 Mycat 自己手里**
（P0 的 22 处锁 + §6 的线程发现机制改造）。这是个**好消息**：不需要等上游、不需要换连接池、
不需要 fork Connector/J。

---

## 5. 静态半区结论（详见 `pinning-inventory.md`，此处只列裁定相关项）

1. **活体 synchronized = 140 处 / 61 文件**（仅 reactor 模块、剔注释）。
   旧 plan `2026-09-28-phase2-virtual-threads.md` 的「55 文件 / 146 处」虚高：
   含**不在 root pom `<modules>` 的休眠 `vertx/` 模块**与整类注释死码。
2. **VT 前置条件 = P0 共 3 文件 / 22 处**（不是 146）：
   `NewMycatConnectionImpl` **6** · `DbPlanManagerPersistorImpl` **13** · `DefaultPrototypeGenerator` **3**。
   三者均已附**活性证据**（直接委托 / `MycatRouterConfigOps:602,:684` 两处 `new` / `SequenceGenerator.create` 反射装载）。
3. **P1/P2 约 118 处不构成 VT 阻塞** ⇒ 旧 plan 的「全仓零 synchronized」目标属**过度工程，应否决**。
4. **换锁语义安全性已坐实**：三个活体 P0 文件内 `.wait(`/`.notify(`/`.notifyAll(` 命中数 **= 0**
   ⇒ 无 monitor-condition 依赖，`synchronized → ReentrantLock` 机械替换不破坏语义（不需要 `Condition` 迁移）。
5. **锁拓扑（换锁前必须遵守）**：
   - `NewMycatConnectionImpl`：:109/:444/:496/:595 方法级 `this` + :199/:298 显式 `synchronized(NewMycatConnectionImpl.this)`
     ⇒ **同一监视器、可重入嵌套**，**单个 per-instance `ReentrantLock` 覆盖全 6 处**。拆成多把锁会破坏嵌套语义。
   - `DefaultPrototypeGenerator`：:69/:92 `synchronized(this)` + :108 方法级 ⇒ 同一监视器，单锁覆盖 3 处。
   - `DbPlanManagerPersistorImpl`：13 处全方法级 `this` ⇒ 单锁；`ReentrantLock` 可重入，嵌套调用安全。
6. ~~**`:200` 全局锁内含阻塞池借用 ⇒ I-PR-1 spec 必须一并裁定**~~ ❌ **本条已撤销（2026-11-17 活性复核）**：
   `TransactionSessionTemplate` 是**死码**——`abstract` 类，唯一子类 `LocalTransactionSession.java` 整文件注释
   （非注释行数 = 0），会实例化它的 `DruidDatasourceProvider:204-207 createSession` 四行全注释，
   全仓（含测试）除自身定义外**零引用**。**活体 `TransactionSession` = `proxy/.../MycatXaTranscation`，
   其 `synchronized` / `.wait` / `.notify` 命中数均为 0** ⇒ 事务会话层**无换锁工作量、无争用放大点**。
   原「自锁死」推演因此**失去活体锁论据，降级为待证推演**（§3.4 边界注）。
   **教训**：本轮初判重犯了旧 plan 的同一错误——只看锁形状、不验类活性。
   凡列 P0 必须附活性证据，否则不得进入裁定依据。详见 `pinning-inventory.md` §2.4/§2.5。

---

## 6. 🆕 VT 的非性能回归面（本轮新发现，旧 plan 完全未提）

**这条比性能更硬：三个运维 hint 会静默失效。**

`common/.../ThreadUtil.java:10-29 findAllThreads()` 用 `ThreadGroup.enumerate()` 发现线程。
**虚拟线程不属于任何 ThreadGroup**（JDK21 起 VT 的 `getThreadGroup()` 是空占位组，enumerate 枚举不到）。

| 消费点 | VT 下后果 |
|---|---|
| `IOExecutor:29 findAllThreads()` | 只剩 carrier，所有 IO 工作线程消失 |
| `IOExecutor:35 interrupt(pid)` / `:49 kill(pid)` | 按 id 匹配不到 ⇒ 返回 false |
| `HintHandler:160` **`killThread`** hint | `affectedRows` 恒 **0**，却仍 `sendOk()` ⇒ **客户端以为杀成功，实际什么都没杀** |
| `HintHandler:167` **`interruptThread`** hint | 同样静默失效 |
| `HintHandler:177` **`showThreadInfo`** hint | 结果集只剩 carrier ⇒ **运维看不到任何正在跑的查询** |

⇒ 选 VT 形态就**必须同时改线程发现机制**（提交时登记 `Thread.currentThread()` 到并发集合、完成时移除），
这是**强制附带工作量，不是可选项**。I-PR-1 的 spec 若选 VT，此项须进任务清单。

**顺带观察（不属本轮范围，仅记录）**：`HintHandler:164` 在 `interruptThread` 分支先执行
`Thread.currentThread().interrupt()`，打断的是**调用方线程**而非目标；疑似遗留缺陷，建议另立小账。

---

## 7. 另一条本轮坐实的 I-PR-1 输入：心跳腿也共用同一个 IOExecutor

`datasource/.../JdbcConnectionManager.java:242-251`：后端**心跳**（`putHeartFlow` 回调）
同样走 `MetaClusterCurrent.wrapper(IOExecutor.class).executeBlocking(...)`，内部 `getConnection(datasource)` + 探活。

⇒ I-PR-1 的车道划分**不止"查询腿 / 释放腿"两类**：
心跳是**第三类**，且它是 Mycat 判定后端死活的唯一手段。
若心跳与查询共用一个有界池，**查询打满池 ⇒ 心跳排不进去 ⇒ 后端被误判为死 ⇒ 触发不必要的切换/摘除**。
这是比"释放腿被拒"更隐蔽的级联故障。spec 须裁定心跳腿的隔离或优先级。

全仓 `executeBlocking` 调用点 = ~~35 处 / 18 文件~~ **30 处 / 15 文件**
（**2026-11-17 I-PR-1 spec 期订正**：原口径未排除定义处 `IOExecutor:25/66/101` 与已注释处
`FileRepositoryImpl:56`、`MySQLMetadataStorageManager:237`。完整四车道归类见
`2026-11-17-i-pr-1-ioexecutor-vt-design.md` §1.3）。

---

## 8. 最终裁定

### 8.0 三问的最终答案

| # | 答案 | 证据 |
|---|---|---|
| **Q1** | **会 pin，且 pin 在主查询路径。** 直接换 VT 不是 drop-in。pin 的充要条件是 **Mycat 自己的 `synchronized`**，热点唯一：`VtSpike.runQuery:167 <== monitors:1`（9 次命中） | §2.2 · §2.3 |
| **Q2** | **吞吐不可分辨**（四臂区间 234–694 大幅重叠，波动是共模宿主噪声）；**线程数是唯一可分辨维度**：cached **1515–1782** vs VT **27** vs bounded-core16 **27** vs bounded-core64 **75** | §2.5 · §3.5 结论 F/G |
| **Q3** | **第三方零 pinning。** Druid 1.2.28 在 `pool=4,C=512` 深度饱和（`borrowP95=6159ms`，真等了 6 秒）下 `pinnedFrames=0`；Connector/J 9.3.0 流式读同样 0 | §4.1 |

### 8.1 裁定（a）：**先加界（有界平台池 + 独立永不拒绝释放腿 + 心跳腿隔离），VT 另立后续票**

> ## ⚠️ 本裁定已被用户拍板取代（2026-11-17，保留原文不改写历史）
>
> **下文是我的建议，不是最终方向。** 我就池形态请示后，**用户选择「一步到位上 VT」**——
> 加界 + 虚拟线程 + P0 22 处换锁 + 线程发现重写**全部进 I-PR-1 本轮**，
> **不**采用下文推荐的「先加界，VT 另立后续票」。
>
> **现行权威文档**：`docs/superpowers/specs/2026-11-17-i-pr-1-ioexecutor-vt-design.md`
> （plan：`docs/superpowers/plans/2026-11-17-i-pr-1-ioexecutor-vt.md`）。
>
> **下文仍然成立的部分**（已被新 spec 继承，不必重读推导）：
> - 「这不是性能裁定」——吞吐不可分辨、相关 qps 数字已撤回，**新 spec 同样禁止宣称吞吐收益**；
> - §8.1 表格里 VT 的三项净成本（22 处换锁 / 线程发现重写 / 三个运维 hint 回归风险）**全部真实存在**，
>   新 spec 把它们**全部纳入本轮范围**并各配验收断言（G4 / L1 / L8）；
> - 下方「VT 后续票的前置条件」三条**已全部成为 I-PR-1 的强制项**，不再是「将来若启动」的条件；
> - `TransactionSessionTemplate:200` 裁定要求**已撤销**（死码），新 spec 不含此项。
>
> **下文已被新 spec 订正的部分**：
> - 「全仓 35 处 / 18 文件」→ **30 处 / 15 文件**（排除定义处与已注释处）；
> - 可拒绝集**不是** 19 处车道 Q，而是 **5 处前端语句入口**——
>   给内层腿加闸会触发**嵌套准入死锁**（新 spec §3.3，实读确认的 3～4 层嵌套链）。
>   **这是危险性订正：按本报告旧口径实现会直接死锁。**
> - 自死锁机制此前降级为「待证推演」，新 spec 已定位到**活体载体**
>   `DefaultPrototypeGenerator.get()`（自旋睡眠 + 递归等一个同闸异步 fetch），仍未运行时复现，
>   由新 spec 的 L5 断言证明。

**这不是性能裁定——数据明确否决了"按性能选形态"这个原始预期**（§3.5）。
既然吞吐分不出胜负、线程治理等效（VT 27 = bounded-core16 27），决定权只能落到**工作量与回归面**：

| 维度 | 有界平台池 | VT + P0 换锁 |
|---|---|---|
| 线程治理（I-PR-1 核心目标） | ✅ 达标（界设在真实并发资源数附近时 = VT） | ✅ 达标 |
| 吞吐 | 无可分辨差异 | 无可分辨差异 |
| **强制附带工作量** | 车道划分 + 背压 + 拒绝语义 + `count()` 修复 + 配置真接线 | **上述全部** ＋ **22 处锁转换（3 文件）** ＋ **线程发现机制改造** |
| **功能回归面** | 无新增（平台线程仍在 ThreadGroup 内，三个 hint 照常工作） | **`killThread`/`interruptThread`/`showThreadInfo` 三个 hint 静默 no-op**，除非同时重写 `ThreadUtil.findAllThreads()`（§6） |
| 换锁风险 | 不涉及 | `NewMycatConnectionImpl` 6 处是**同一监视器的可重入嵌套**，必须单锁覆盖；拆锁会静默破坏嵌套语义（§5.5） |
| 可回退性 | 高（池参数是配置） | 低（锁语义 + 线程模型双改） |

⇒ **VT 的净收益在本轮测量中为零，净成本为正**（22 处锁 + 一次线程发现机制重写 + 三个运维 hint 的回归风险）。
**推荐先加界**。VT 不是被否决，是**被排到加界之后**：加界完成后车道缝已就位，
届时换 VT 只需替换 executor 实现 + 22 处换锁 + 线程登记，**风险面比现在直接上小得多**。

> **VT 后续票的前置条件（若将来启动，缺一不可）**：
> 1. P0 **3 文件 / 22 处** `synchronized → ReentrantLock`（活性证据见 `pinning-inventory.md` §2.5；三文件 `.wait/.notify` = 0，机械换锁语义安全）；
> 2. `ThreadUtil.findAllThreads()` 改为**自登记**机制（提交时记 `Thread.currentThread()` 入并发集合、完成时移除），
>    否则三个 hint 静默降级（§6）——**这是强制项，不是可选项**；
> 3. 补测残留项：vertx-core 4.5.14 Future 完成链、`DbPlanManagerPersistorImpl` 的 DDL 型 JDBC、
>    `DefaultPrototypeGenerator` 的 `SELECT ... FOR UPDATE` 型序列语句（`pinning-inventory.md` §9）。
>
> **`TransactionSessionTemplate:200` 全局锁裁定要求已撤销**（死码，§5.6）——VT 后续票**不含**此项工作量。

### 8.2 裁定（b）：I-PR-1 必须处理的**头号地雷**——`corePoolSize` 小 + 深队列 = 静默单线程

**这是本轮最有生产价值的发现，且与池形态选择无关（选哪个形态都要防）。**

生产 `server/src/main/resources/server.json` 的 `workerPool` 现值：
`corePoolSize=1, maxPoolSize=1024, maxPendingLimit=65535`。
若 I-PR-1 把 `maxPendingLimit` 直接当成 `ThreadPoolExecutor` 的队列容量、`corePoolSize` 照抄配置，
则 **TPE 的增长语义（core 满 → 入队 → 队列满 → 才扩到 max）会让它永远停在 1 个线程**：
2048 个任务填不满 65535 的队列 ⇒ `maxPoolSize=1024` **是装饰品**。

实测三次独立复刻，**对噪声免疫的 −81% 效应**：

| 轮 | 配置 | qps | peakInFlight | 平台线程 |
|---|---|---|---|---|
| round2 | core=0, max=1024, q=65535 | **127.4** | **1** | 12 |
| round2b | core=1, max=1024, q=65535 | **129.1** | **1** | 12 |
| round2c | core=1, max=1024, q=65535 | **126.5** | **1** | 12 |

对照 `core=16,max=64,q=65535` ⇒ qps 234–677、`peakInFlight=16`、线程 27。

⇒ **spec 必须明确回答"哪个旋钮真正界住什么"**，并且：
- **不得**把 `maxPendingLimit` 当作小 `corePoolSize` 前面的深队列；
- `corePoolSize` 必须承载真实并发意图（不是 1）；
- **必须有一条活体断言证明池真的在并发跑**（`peakInFlight > 1`），
  否则"加了界"和"退化成单线程"在指标上无法区分——本轮探针自己就踩过这个坑（round2 七跑全 `peakInFlight=1`，
  起初被误读为"有界池吞吐差"，实为 `spike.core` 默认 0 的探针 bug）。

### 8.3 裁定（c）：释放腿永不拒绝 + 心跳腿隔离（两条已坐实的车道约束）

**释放腿**（用户已拍板 invariant）：归还 / `abandonConnection` / `abandonQuery` / force-close
必须走**独立车道、永不拒绝**。本轮给出了它的量化理由：

- 浅队列 = **硬拒绝**：`core=64,cap=8` ⇒ ops **102** / rejected **1946**（**95%**）；
  `core=0,cap=8` ⇒ ops **34** / rejected **2014**（**98%**）。§3.2 · §3.4 结论 E
- 释放腿被拒 ⇒ 连接/结果集不归还 ⇒ 池永久饱和 ⇒ **比查询失败严重一个量级**。
- **自锁死推演**（N 个 worker 全卡在饱和池借用等待 ⇒ 无线程执行归还）：
  **机制成立，但本轮无活体锁证据、无运行时演示**（原引用的 `TransactionSessionTemplate:200` 是死码，已撤销）。
  ⇒ **降级为待证推演**，由 I-PR-1 实现期的活体矩阵证实，**不得作为裁定的硬依据**。
  但 invariant 本身已由"硬拒绝 95%"独立支撑，**不依赖该推演**。

**心跳腿**（本轮新坐实，§7）：`JdbcConnectionManager:242-251` 的后端心跳**共用同一个 IOExecutor**。
⇒ 车道划分**不止两类**：心跳是第三类，且它是 Mycat 判定后端死活的唯一手段。
查询打满池 ⇒ 心跳排不进去 ⇒ **后端被误判为死 ⇒ 触发不必要的切换/摘除**。
这是比"释放腿被拒"更隐蔽的级联故障，**spec 须裁定心跳腿的隔离或优先级**。

全仓 `executeBlocking` = ~~35 处 / 18 文件~~ **30 处 / 15 文件**（订正依据同 §8 上文；
完整车道归类**已交付**，见 `2026-11-17-i-pr-1-ioexecutor-vt-design.md` §1.3：
车道 A 可拒绝 **5 处前端入口** / 车道 U 永不拒绝 **25 处**）。

### 8.4 裁定（d）（e）：两项已拍板/已坐实的附带工作量

- **(d) `fromVertx` 保留并一并加界**（用户拍板）。`IOExecutor.fromVertx`（L89-91）当前同样是
  `newCachedThreadPool()` 无界、**零调用方**。裁定：**不删除**，与 `DEFAULT` 一同加界，
  保持两条入口的界语义一致（避免将来有人接线时踩到无界旧路）。
- **(e) `count()` 语义修复**。`IOExecutor.DEFAULT` 现在 L67 自增、L79 `finally` 自减 ⇒ **恒约 0**，
  监控数字名不副实。正确范式已在仓内：`ThreadMycatConnectionImplWrapper` 的
  `stat.plusThread()` / `decThread()` **在 runnable 内部**正确括住工作段。
  裁定：**提交时自增、任务完成时自减**（不是在提交方法的 finally 里自减）。

### 8.5 诚实边界（无论裁定如何都成立）

- 本探针是**单机 Docker 后端 + 16 lane + 200 行**的受控形状，**不是生产负载画像**。
  绝对 qps 数字不可外推到生产；**可外推的是比值与机制**（线程数解耦、pinning 热点归属、pinning 吞吐代价）。
- **未覆盖**：真实 Mycat 全代理路径（前端协议解析 / 路由 / calcite 优化 / 跨片归并）、
  多后端、TLS、prepared 协议、大结果集、长事务。
- **未做**全代理 A/B。缝已坐实可用：`MetaClusterCurrent.register(Class,Object)` 走 `copyContext` **拷贝合并**
  （非整表替换，故不会触发 I-VE-1 那类 NPE），且所有消费点都是**每次调用惰性 `wrapper(IOExecutor.class)`**
  ⇒ 启动后重注册一个 VT 版 IOExecutor 即可做**零产品码改动**的全代理 A/B。
  本轮**未执行**该 A/B（成本/收益：探针已把 Q1/Q3 的机制问题隔离清楚，全代理 A/B 属 I-PR-1 实现后的验收手段，
  不是形态裁定的前置）。**若用户要求，可后补。**
- 运行间方差显著（§1.3），小差异不作裁定依据。