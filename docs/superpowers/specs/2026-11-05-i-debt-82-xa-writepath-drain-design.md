# I-DEBT-82 · XA 写路径 closeStatementState 被调两次（fire-forget #1 + awaited #2）—— Spec 先行

> **状态**：✅ 已取证收口（2026-11-05 五格活体⇒ **H1 by-design**，详 §7；**未改生产码**）。
> **分层**：C（引擎地基 / 生产稳健交界，与"无缝替代"招牌 A 层正交，承 I-DEBT-80/81 资源泄漏族）。
> **前置**：承 I-DEBT-81（已本地收口并 commit，不 push）。I-DEBT-81 把 `BaseXaSqlConnection.closeStatementState()`
> 从"排空腿丢在 onComplete 里不等待"改成"用 `Promise<Void> drained` 真等 `clearConnections()` 排空"。
> **本票正是那次修复把写路径的一处既有形态变成了新的可疑面**，故必须单独立票取证。

---

## §0 北极星对齐

招牌 = 客户端眼中无缝替代 MySQL；尺子 M2 判据含"基本 CRUD"。写路径（UPDATE/INSERT）是 CRUD 的主干。
若写路径存在连接不归还 / 排空竞态，其后果与 I-DEBT-80/81 同族：**长跑后端会话泄漏、撞 `max_connections`**。
本票不制造功能，只核实"写路径的会话资源是否被正确、一次性地排空归还"。

---

## §1 立案订正（诚实推翻我上一轮的粗表述）

DEBT-QUEUE L252-254 与 I-DEBT-81 spec §6 曾把 G2 记为：
> "UPDATE/INSERT 路径 `ReceiverImpl.java:232-233` 以 `.onSuccess(unused -> closeStatementState())` 丢弃返回 Future
> ⇒ 写路径的响应**不等归还**，泄漏窗口仍在。"

**本段对当前 HEAD 实读复核，该表述过粗、且方向部分错误**，据实订正如下（结论须由 `file:line` 坐实，铁律）：

写路径对**同一个** `transactionSession` 对象调了 **两次** `closeStatementState()`：

| # | 位置 | 形态 | 是否被等待 |
|---|---|---|---|
| #1 | `ReceiverImpl.java:232-233` `join(updateInfoList).onSuccess(unused -> ...getTransactionSession().closeStatementState())` | `.onSuccess` 处理器返回值丢弃 | **否**（fire-and-forget） |
| #2 | `ReceiverImpl.java:245 sendOk(affectedRow,lastInsertId)` → `:266/:253` → `:256 transactionSession.closeStatementState().flatMap(writeOk)` | 链在返回给客户端的 Future 上 | **是**（awaited） |

对象同一性证据：`ReceiverImpl.java:59` 字段 `protected final XaSqlConnection transactionSession;`；
`:69` `this.transactionSession = (XaSqlConnection) this.dataContext.getTransactionSession();`
⇒ `#1` 里的 `dataContext.getTransactionSession()` 与 `#2` 里的 `transactionSession` 是**同一实例**。

**因此"响应不等排空"不成立**——`:256` 的 awaited 腿已经把排空挂在了写 OK 之前。
真正可疑的不是"不等"，而是"**不等的那条（#1）与等的那条（#2）在同一会话上并发跑两次排空**"。

---

## §2 机制表（当前 HEAD 实证 file:line）

- **排空实现**：`BaseXaSqlConnection.java:445-468` `closeStatementState()`
  - `:446` `getAllConnections().stream().map(abandonQuery)`（`getAllConnections` = `map.values() + extraConnections`，见 `:481-486`）
  - `:448` `future.flatMap(join closeList)` → `:449` `closeList.clear()`
  - `:456-467` `Promise<Void> drained`；`future.onComplete(ev -> clearConnections().onComplete(... drained.tryComplete/tryFail ...))`；`return drained.future()`
- **实际归还**：`BaseXaSqlConnection.java:491-510` `clearConnections()`
  - `:497` `List draining = new ArrayList<>(extraConnections)`（快照）
  - `:498-499` `joinCloses(draining).onComplete(ev -> extraConnections.removeAll(draining))`
- **写路径会真的填 extraConnections（关键·非理论）**：
  - `BaseXaSqlConnection.java:105-108`：对**已在 `map` 中的同一 target** 再借一条 → `extraConnections.add(connection1)`
  - `LocalXaSqlConnection.java:145-148`：**autocommit（非事务）** 下 `getConnection` 直接把每条连接 `extraConnections.add(connection)`
  - ⇒ XA **autocommit 写路径**每借一条都进 `extraConnections`；多 target 写、或写前先读过同库，也会进。

**并发形态**：#1 触发 `closeStatementState()` → 排 `abandonQuery` → `onComplete` 起 `clearConnections()` 异步 close `draining`，
完成后 `extraConnections.removeAll(draining)`。#2 几乎紧随其后再次 `closeStatementState()`，其 `:446` 读 `getAllConnections()`
（含 `extraConnections`）此刻 #1 的 `removeAll` 未必已发生 ⇒ **#2 可能对正被 #1 `close()` 的连接再发 `abandonQuery()`**，
且两条 `clearConnections()` 并发操作同一 `extraConnections`/`closeList`。

---

## §3 候选假设（互斥，取证判决，不预设结论）

- **H1｜良性冗余**：autocommit 写路径的 `extraConnections` 由 #2 一条权威排空归还，#1 只是提早发起、`CopyOnWriteArrayList`
  与 `removeAll(snapshot)` 使并发安全，`close()` 幂等 ⇒ 无泄漏、无竞态。**若 H1 成立则本票 by-design、不改码。**
- **H2｜双清竞态（use-after-close）**：#1 与 #2 对同批 `extraConnections` 并发 `close()`/`abandonQuery()`，
  产生"对已归还连接再操作"的窗口 ⇒ 可能报错、可能把刚归还的连接又从池里二次移除、或计数抖动。
- **H3｜#1 冗余应删**：`:233` 的 fire-and-forget 在 #2 已 awaited 排空的前提下**语义多余**，删掉 `:233` 即消除并发双调，
  行为等价于单条 awaited 排空（属最小修候选，**非结论**）。

判别力关键：**H1 与 H2/H3 的区分只能靠活体计数/时序取证，不能靠读码推理**（承 I-DEBT-80 教训：上一轮纯推理定性被实测推翻）。

---

## §4 活体取证协议（承 I-DEBT-80 六格法，先判有无，再决定动不动码）

**统一仪器**：`ci/run-example-guard.ps1`（已硬化：三档后端 start+ping 门、JDK21 门、jar 新鲜度、finally 收尾）
+ 后端 `SHOW GLOBAL STATUS LIKE 'Threads_connected'` 采样 + `jcmd Thread.print` 看门器（抓 `Connection.close`/`abandonQuery` 栈）。

- **格 A（单条 autocommit UPDATE 隔离）**：只跑一条走 `LocalXaSqlConnection` autocommit 的 UPDATE，
  采 `Threads_connected` 峰值与稳态回落。判：写完 OK 后连接是否**一次性**回落到基线；有无残留。
- **格 B（多 target 写·同库二次借）**：构造跨片 UPDATE 命中同一 datasource 二次借（进 `extraConnections`），
  看 #1/#2 双调窗口内计数时序，是否有"归零后又抬头"或抖动。
- **格 C（写+前置读同会话）**：同一会话先 SELECT（填 `extraConnections`）再 UPDATE，放大 #1/#2 并发面，
  看门器若抓到对正 `close()` 连接发 `abandonQuery` 的栈 ⇒ 倾向 H2。
- **格 D（负控·临时摘 #1）**：仅观察用途，临时把 `:233` 的 `.onSuccess(... closeStatementState())` 注释掉，
  重跑格 A/B/C 计数对照。**只取证、收口后必回退**（`git checkout`），并复核 `dirtyFiles=0`。
  判：摘掉 #1 后若计数/时序无变化 ⇒ #1 冗余（H1/H3）；若摘掉后暴露新的等待或异常 ⇒ #1 承重。
- **格 E（重复写压测）**：循环 N 条 autocommit UPDATE，看 `Threads_connected` 是否单调爬升（泄漏指纹）还是锯齿回落（健康）。

**判据**：五格里只要出现"稳态爬升 / 对已关连接二次操作栈 / 计数抬头"任一硬指纹 ⇒ 定性 H2 或 H3，进 §5 立 RED；
若全部锯齿健康、摘 #1 无差异 ⇒ 定性 H1，本票 by-design 关闭（仅留一条"清理冗余调用"的低优先工程卫生候选，不阻塞）。

---

## §5 若取证的结论为需改码，收口硬门（预先声明，防伪绿）

1. **RED 先行**：先写可复现泄漏/竞态的活体或单元锁，目视红；不允许先改码后补测。
2. **最小修取向（候选，非定案）**：`:233` fire-and-forget 与 `:256` awaited 二选一——
   倾向"删 `:233` 冗余发起、保留 `:256` 唯一权威排空"（H3），而非"两条都等"（会把串行排空翻倍）。
3. **改生产必重打 fat jar 再验**：`mvn -o -B -pl server -am package -DskipTests` 后跑护栏，禁"改码不重打就验活体"。
4. **全量护栏背书**：`example` 段维持基线 `550/0F/0E/37skip`（承 I-DEBT-81 F 轮新基线），红类清单空。
5. **变异负控**：修法落地后人为回退一处（如把 `:256` 改回不等 / 把 `:233` 保留双调），证 RED 锁确有判别力。

---

## §6 待办与拍板边界

- 本票 §4 取证需起后端容器 + 8066，属**较大新工作**（多格活体跑测）。**先交付本 spec 与 §1 订正供用户过目**，
  因它**推翻了我上一轮"写路径不等排空"的定性**；是否投入取证跑测由用户裁定。
- 待用户既有拍板项不变（不因此票解锁）：I-DEBT-79 刀②（CI PR 门）、刀③（abort→NO_DATA）、connector 矩阵 CI 化。
- 若取证定性 H1（by-design），则在 DEBT-QUEUE 就地追加订正关闭，**不改生产码、不制造回归**。

---

## §7 五格实测结果与定性（2026-11-05 活体取证 · 判决 = H1 by-design）

**仪器**：`ci/run-example-guard.ps1 -Persona MYSQL_8_0 -GateOnly -KeepRunning`（三档后端 start+ping 门 + posture 门，
persona 报 8.0.34）起代理；直驱 `set transaction_policy='xa'` + autocommit UPDATE 打向 Mycat(8066) schema `db1.i82t`→`prototypeDs`(3306)；
读数用 `/*+ mycat:showDataSources{} */` 的池计数（`ActiveCount`=USE_CON 借出、`CreateCount`=累计物理连接、`Use/Close`=借/还次数）。
脚本 `​.superpowers/sdd/2026-11-05-i-debt-82-forensics/{q8066,cell,smoke}.ps1`。

| 格 | 负载 | ActiveCount | CreateCount | Use/Close | 异常 |
|---|---|---|---|---|---|
| A | 单条 autocommit XA UPDATE | 0→**0**（写后同会话即刻回落） | 1→1 | +1/+1 | 无 |
| E | 同会话 500 条 UPDATE | 0→**0** | 1→**1**（写 500 零新增） | +500/+500 | 无 |
| C | 200 组 SELECT+UPDATE 交叠 | 0→**0** | 1→**1** | +400/+400 | 无 |
| D | 摘除 `:233` #1 重打 jar 复跑 E/C | 0→**0** | 2→**2** | +500/+500、+400/+400 | 无 |

**判决 = H1（良性冗余·by-design）**：
1. **无泄漏**：I-DEBT-80 的泄漏签名是 `CreateCount`/后端 `Threads` 随语句单调爬升（孤儿连接另起物理会话）；本票 500× 写入
   `CreateCount` **恒定**（同一条池化连接复用 500 次），`ActiveCount` 每条写完即归 0 ⇒ 写路径连接被 awaited 腿（`:256`）正确归还。
2. **无 use-after-close 竞态（否证 H2）**：读写交叠 200 轮 + 单会话 500 写，`Use==Close`（借还严格配对），
   未见对已归还连接二次操作、无池记账损坏、proxy 日志零相关异常（仅往轮失败 DDL 探针的噪声，早于负载时刻）。
3. **H3 反证（格 D）**：摘掉 `:233` fire-and-forget 后，逐指标与保留时**完全一致** ⇒ `#1` 对可观测资源行为**无影响**
   （`#2` awaited 排空先跑完并 `removeAll` 快照，`#1` 的二次 `closeStatementState` 见 `extraConnections` 已空即 no-op）。

**收口决定**：**不改生产码**（`:233` 冗余但无害，删除它属 H3 工程卫生，无功能收益且有回归面，收益不抵成本）。本票以 **H1 by-design 关闭**。
**残留低优先卫生候选**（不阻塞、不立开码票）：`:233` 的 fire-and-forget 可归并为单行注释说明「排空由 :256 唯一负责」防后人误读为“不等排空”。

**取证诚实项**：格 B（多 datasource 分片写同库二次借 `BaseXaSqlConnection:105-108`）因所用 schema 为单数据源未**单独**仪表；
但 autocommit 路由下每条写均经 `LocalXaSqlConnection:145-148` 进 `extraConnections`，格 C 已在同会话内 400 次借用覆盖“多条入 extraConnections”形态，
泄漏签名（CreateCount 增长）在格 C/E 缺席 ⇒ 格 B 由等价性覆盖，未伪称已独立跑。
