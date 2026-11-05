# I-DEBT-83 · 语句级排空 awaited 后单条 `close()` 无超时兜底（G1）—— Spec 先行

> **状态**：🔴 待拍板（Spec 先行，取证/实现均未启动）。
> **分层**：C（引擎地基 / 生产稳健交界，与"无缝替代"招牌 A 层正交，承 I-DEBT-80/81/82 资源族）。
> **前置**：本票是 I-DEBT-81 §4 明确挂账的 **G1**（"单条归还的超时兜底，防 `close()` 永久悬挂拖死 `eventually`"）。
> I-DEBT-81 把 `closeStatementState()` 从"排空腿丢在 onComplete 不等待"改成"用 `Promise<Void> drained` 真等排空"。
> **那次修复用"客户端等待排空"换掉了"跨语句泄漏"——于是新增了一个此前不存在的失效面：排空腿若悬挂，客户端响应也悬挂。**

---

## §0 北极星对齐

招牌 = 客户端眼中无缝替代 MySQL。真正的 MySQL：后端僵死时客户端会因 `socketTimeout`/`queryTimeout` 在**有界**时间内拿到错误，
而非无限挂着。I-DEBT-81 后，Mycat 的语句结束路径把"发 OK/EOF"排在了"等所有借出连接归还完成"之后；
若某条归还永不完成，客户端就**无限挂起**——这是"无缝替代"承诺上一个可被触发的可用性退化。本票核实该悬挂是否真可达、并评估兜底设计。

---

## §1 失效面来源（诚实标注：这是 I-DEBT-81 自身引入的等待，非历史遗留）

| 点 | file:line | 事实 |
|---|---|---|
| 排空被真等待 | `va/.../BaseXaSqlConnection.java:456-467` | `drained` 在 `clearConnections().onComplete` 里才 tryComplete ⇒ **`drained` 完成时刻 = 排空完成时刻** |
| 写路径 await 它 | `server/.../ReceiverImpl.java:245→:256` | `sendOk` 走 `transactionSession.closeStatementState().flatMap(u -> writeOk(...))` ⇒ **OK 包在排空完成后才发** |
| 读路径 await 它 | `server/.../ReceiverImpl.java:391` | SELECT EOF 同样挂在 `closeStatementState().onComplete(writeRowEndPacket)` 之后 |
| 排空逐条 join | `va/.../BaseXaSqlConnection.java:515-528` | `joinCloses` 对每条 `close()` 单独 `.recover(warn)` 再 `CompositeFuture.join` ⇒ 每条都得完成；`recover` 吸收"失败"，**吸收不了"永不完成"** |
| 单条 close 无超时 | `common/.../NewMycatConnectionImpl.java:643-651` | `close()` = `future.transform(→ 阻塞 `JdbcUtils.close`)` ⇒ 完成时刻系于语句 `future`，**全链无超时** |

---

## §2 悬挂可达性（默认配置坐实）

`close()` 的语句 `future` 何时可能永不完成？取决于物理连接是否被超时兜住。实读建池与配置默认值：

| 旋钮 | file:line | 默认值 | 对悬挂的作用 |
|---|---|---|---|
| 语句查询超时 | `datasource/.../DruidDatasourceProvider.java:76` + `config/.../DatasourceConfig.java:40` | `queryTimeout = 0` | **0 = Druid 不设 `Statement.setQueryTimeout`** ⇒ 后端无响应时语句可无限跑，`future` 永不完成 |
| 建连/借用等待 | `DruidDatasourceProvider.java:68` + `DatasourceConfig.java:27` | `maxConnectTimeout = 30000` | **只兜 `getConnection` 借池等待**（I-DEBT-80 那 30s 签名），**不兜已借出连接的语句/关闭** |
| 回收泄漏连接 | `DruidDatasourceProvider.java:78-82` + `DatasourceConfig.java:41` | `removeAbandoned = false` | 默认不回收长时间未归还的连接 |
| 物理 socket 读超时 | 无代码注入 | 取决于 URL | 未在代码层强制 `socketTimeout`；不带该参数的 datasource URL ⇒ TCP 黑 hole / 后端 hang 时读永不返回 |

**结论**：**默认配置 + 后端在语句执行中途僵死/网络黑 hole** 是可达路径 ⇒ 语句 `future` 永不完成 → `close()` 永不完成 → `joinCloses` 永不完成 → `drained` 永不完成 → **客户端在写 OK/EOF 之前无限挂起**。
（缓解条件：若运维在 datasource URL 显式配了 `socketTimeout`/`queryTimeout`，则该腿被后端超时以异常完成，悬挂不成立——但代码层默认不设。）

---

## §3 候选设计（互斥，代价各异）

- **D-A｜by-design 可接受，交运维配置**：不改码，把"datasource URL 必须带 `socketTimeout`/`queryTimeout`"写成部署硬性要求 + 文档。
  代价：把可用性责任外推到配置；默认配置仍暴露无限挂起。收益=零回归风险。
- **D-B｜逐条 close 超时兜底**（承挂账原意，候选定时器 `mySQLManager().setTimer`，同文件 `:225/:376` 已有用法）：
  `joinCloses` 里给每条 close 包一个"超时即视为完成并 warn"的 race，`drained` 至多等 N ms。
  需处理：① 定时器泄漏（close 先完成时要能取消或让 timer 变 no-op，用 `tryComplete` 幂等 + 一次性 timer）；
  ② 超时后 `extraConnections.removeAll(snapshot)` 的语义（`:497-499`）——**超时即归还未完成，该连接既移出快照又未真正回池 = 又造一个孤儿**，须改为"超时不移除、留待后续语句再清"或显式 abandon，避免把等待问题换成泄漏问题。
- **D-C｜整条排空腿总超时**：对 `clearConnections()`/`drained` 套一个总预算，超时放行响应并 warn；粒度粗、实现面小，但同样有"超时 vs 真归还"的孤儿语义待定。

三者的共同难点都在 **§2 尾注**：超时只能保证"客户端不再无限挂"，不能凭空让一次未完成的物理归还变得已归还——**必须在"有界延迟"与"可能留一个孤儿连接"之间显式取舍**，这属架构级择路，须 spec→plan，非顺手改。

---

## §4 取证协议（若投实现，判决前置）

1. **可达性实测**：backend 故障注入——语句执行中途 `docker kill m1-mysql-a` 或对 3306 做 TCP 黑 hole（`iptables DROP`/`socat` 黑洞），
   发一条 autocommit UPDATE，观测客户端是否在**无超时**下无限挂（当前预期：挂）。此为"问题真存在"的活体坐实，不推理定性（承 I-DEBT-80 教训）。
2. **缓解对照**：同注入下给 datasource URL 加 `socketTimeout=5000`，观测是否改为有界报错 ⇒ 界定 D-A 的适用边界。
3. **兜底验证**（若选 D-B/D-C）：注入同故障，断言客户端在 `N + ε` 内有界返回；且用 `showDataSources` 计数核对超时后是否产生孤儿（CreateCount 是否 +1 且 ActiveCount 不归 0）。

---

## §5 收口硬门（若投实现）

- 新增确定性单测锁（仿 `XaExtraConnectionDrainTest` R1-R5 缝）：注入一条"永不完成的 close"，断言 `closeStatementState()` 结果在有界时间完成、且 `extraConnections` 语义符合选定设计（无孤儿或有界）。
- 变异负控：去掉超时兜底 ⇒ 该锁必须红（证明锁住的是兜底本身）。
- 全量护栏（L1-L4）+ `va`/`common` 模块测试绿；改生产码必重打 fat jar 再验（承 jar 一致性铁律）。
- 账本 + DEBT-QUEUE + PROGRESS 对齐；commit 不 push。

---

## §6 待用户拍板

本票触碰生产码且核心是"有界延迟 vs 孤儿连接"的架构取舍，另需后端故障注入（较重取证、可能扰动三档后端容器）。
**不擅自动生产码 / 不动后端容器**。请拍板下一步：

- **(a) 走 D-A**：确认"由运维 URL 配 `socketTimeout`/`queryTimeout` 兜住"即可接受，本票以文档/部署约束关闭（最轻、零回归）；
- **(b) 投实现 D-B/D-C**：先在当前 HEAD 跑 §4.1 可达性活体取证坐实悬挂，再进 spec→plan 定超时值与孤儿语义；
- **(c) 仅挂账**：认可问题真实但优先级低，保留本 spec 于队列，先推其他债务。

（I-DEBT-82 已以 H1 by-design 关闭；本 G1 是 I-DEBT-81 挂账的最后一枚兜底票。）
