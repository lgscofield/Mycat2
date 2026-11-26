# 开放债务队列（当前执行清单）

> **最后对账**：2026-11-26 · 实现基线 `9b960a73a`（I-DEBT-143 修复与 CI 背书）· 只相信代码、测试与活体证据。
> **历史档案**：[DEBT-COMPLETED.md](DEBT-COMPLETED.md)。已关闭、已失效、历史订正与里程碑证据均在该文件，不在本队列重复堆叠。
> **当前阶段**：P0 资源与安全，2/5。用户指定顺序：136 → 143 → 127 → 134 → 133 → P1 验证地基 → P1 架构专轮 → P2 容量与兼容。

## 0. 状态与关闭铁律

| 状态 | 含义 | 是否可关 |
|---|---|---|
| OPEN | 缺陷仍在，尚无完整修复证据 | 否 |
| PARTIAL | 代码或部分活体已落，仍有明确残留 | 否 |
| UNPROVEN | 可能执行过，但证据未归档或未被当前 HEAD 覆盖 | 否 |
| CLOSED | 实现、回归与关闭边界均有证据 | 是，移入完成档案 |
| STALE | 当前依赖/源码已使原指控失效，仅保留历史观察 | 是，移入完成档案 |

- 只有当前 HEAD 的 CI 全绿，才可写 `CI✅ / Evidenced-Done`；本地绿灯不等于 CI 背书。
- `UNPROVEN / 未归档` 不等于 `未执行`；不得抹掉历史订正。
- 生产代码修改必须重打 jar；活体矩阵与完整护栏须归档到仓库并用 SHA-256 绑定被测 jar。
- 架构级票据必须 `spec → plan → 用户拍板 → 实施`；不得把本队列视为实施授权。
- `.qoder/*` 与 `scratch/` 不是债务票据产物，不提交。

## 1. 🎯 当前驾驶舱

| 维度 | 当前事实 |
|---|---|
| 招牌路径 | I-PR-1 / M5 已 Evidenced-Done；准入仅五个批准前端入口，控制面 SQL 仅两个文本 COM_QUERY 入口；VT-only，`fromVertx` 保留并加界 |
| P0 资源与安全 | 2/5 已关闭：136、143 均为 CI✅ / Evidenced-Done；127、134、133 仍 OPEN，下一票为 I-DEBT-127 |
| P1 验证地基 | 未开始：server 定向测试未进 CI；full-suite/soak 触发器与测试范围仍不完整；轮2 多版本证据未归档 |
| P1 架构专轮 | 未开始：146–149 均须 spec/plan/拍板 |
| P2 容量与兼容 | 未开始：容量、deadline、事务、引擎、字符集、协议与配置残留仍在 |
| 证据基线 | 实现提交 `9b960a73a`；CI run `45829181264` 成功，9 个实际 job 成功，`full-suite`/`soak-g6` 按 main 分支规则 skipped；[CI 摘要](../../../ci/idebt143/ci-round1/summary.tsv) |

## 2. 🔴 第一步：P0 资源与安全（严格按序）

| 票据 | 当前问题 | 完成判据 |
|---|---|---|
| **I-DEBT-127** | ①未知异常错误码 0、自定义 3034 撞真实 GIS 错误；②COM_RESET_CONNECTION 空实现；③MycatdbCommand 静态初始化吞异常；④MERGE_UNION_SIZE 缺 break；⑤无前端 idle timeout；⑥NIO prepare 平台编码且列数恒 0；⑦connect-attrs 按 char 而非 byte 计长 | 七案各有失败回归与最小修复；未知异常不再发 0；reset 清理会话/事务态；初始化失败响亮；hint 路由不误入超时；idle 关闭可配置；prepare 元数据/编码正确；非 ASCII attrs 不越界 |
| **I-DEBT-134** | native 池忽略 `DatasourceConfig.maxCon/minCon`，实现恒为 1000/1 | 配置值、默认值与非法值行为有测试；native 创建路径真实应用容量；非默认配置端到端可观测 |
| **I-DEBT-133** | `reset()` 只保 `prototypeDs` 三元组，其他数据源、clusters、schemas、users、改过的 root 密码/RSA encodeKey 会被恢复出厂并持久化 | 明确 100 族允许重置键与必须保留面；回归锁证明非目标配置、用户密码与 RSA key 不被破坏；持久化失败不产生半配置 |

## 3. 🟡 第二步：P1 验证地基

| 票据 | 缺口 | 完成判据 |
|---|---|---|
| **I-CI-7** | 当前 CI 不执行新增 server 定向测试 | PR/push 常规路径显式运行 server 定向回归；失败能阻断合并 |
| **I-CI-8** | full-suite/soak 仅在 dispatch 或 `g8-full-suite-gate` 分支运行；example guard 在 mysql client 缺失时可 WARN/skip | 常规验证入口可见且可阻断；mysql client 缺失记 FAIL/NO_RESULT，不得静默跳过；保留长护栏独立触发能力 |
| **I-CI-9** | full-suite 先 `-Dmaven.test.skip=true` 再只跑 `example`；persona 脚本硬编码外部工作区，轮2 证据未进仓库 | 构建与测试范围覆盖承诺模块；脚本使用仓库相对路径/显式输入；证据、命令、版本、jar SHA-256 可复现归档 |
| **I-DEBT-96** | 轮1 七版本环境已备；轮2 逐版本 persona + compat 四件套 + 深面格未证明/未归档 | 七版本逐格有 PASS/FAIL/NO_RESULT；深面格覆盖 8.4/9.x 新语法、MariaDB `@@old_mode`/uca1400；证据绑定当前 jar |
| **I-DEBT-110** | XA 意向删除修复已落，离线恢复路径锁与活体窗口证据仍缺 | 补 XA 会话模拟回归锁；失败保留日志供重试；活体或可复现仪器证据齐全后方可关 |
| **I-DEBT-116** | `<=>` 分区裁剪修复已落且活体通过，离线回归锁仍待补 | 增加 NULL 绑定/literal、非 NULL v、混合 OR 与全分片回归锁；当前 HEAD 全套绿灯 |

## 4. 🟠 第三步：P1 架构专轮（未授权生产改动）

| 票据 | 架构问题 | 专轮前置 |
|---|---|---|
| **I-DEBT-146** | DDL 先提交逻辑元数据，物理建表失败仅记日志，留下元数据/物理表分叉 | spec 明确原子边界、补偿与失败恢复；plan 与用户拍板 |
| **I-DEBT-147** | `addDatasource` 先关旧池再创建新池；无同步、原子替换、借用排空与失败恢复 | spec 明确替换状态机、并发借用、回滚与可观测性；plan 与用户拍板 |
| **I-DEBT-148** | `XaReaper.sweep()` 在共享单线程 `ScheduleUtil` 上执行阻塞 JDBC，占用调度器 | spec 明确独立执行域、取消/超时与故障隔离；plan 与用户拍板 |
| **I-DEBT-149** | `AsyncMycatDataContextImpl.share()` 仍有第三方 VT pinning 风险；直接删除会重复后端查询 | spike 先量化；spec 设计 VT-safe multicast；plan 与用户拍板 |

## 5. 🟢 第四步：P2 容量与兼容

### 5.1 容量与运行时

| 票据 | 当前问题 |
|---|---|
| **I-DEBT-150** | SQL-log 与前端业务共用 `JdbcConnectionManager` / prototype 池；`maxCon=2` 下一笔阻塞 INSERT 可饿死前端。须隔离消费者并计入前端会话持有的后端连接 |
| **I-DEBT-151** | 首个未预热 MYCAT-3007 约 1267–1614ms，热态约 60–87ms；约 1.2s 冷段未定位 |
| **I-DEBT-152** | 准入无全局 pending-submission 上限；永不完成的 promise 会长期占用许可。`executeBlocking` 绕开准入属既定设计，不在此票偷偷改变 |
| **I-DEBT-153** | local/Calcite deadline 扫描全部 datasource 并取最小正超时，无关短超时数据源会缩短本地腿 |

### 5.2 兼容、事务、引擎与配置

| 票据 | 状态 | 残留范围 |
|---|---|---|
| **I-DEBT-99** | PARTIAL | JDBC 分片腿 sql_mode 对齐已落；prototype 直写、native 同构、事务中途改 sql_mode、initSqls 基线未收 |
| **I-DEBT-128** | OPEN | persona charset 广告、Vertx 会话 charset 落地、COM_STMT_EXECUTE 参数解码 |
| **I-DEBT-129** | OPEN | 分帧、CLIENT_FOUND_ROWS、COM_FIELD_LIST、useSchema/authenticator、RSA event-loop、Process、背压、traceId 比较 |
| **I-DEBT-114** | OPEN | commit/rollback 对称、retryCommit、nextXid、reaper fail-open、savepoint、PROXY 连接归还 |
| **I-DEBT-120** | OPEN | 浮点类型、GSI 确定性、projection 不变式、异常可见性 |
| **I-DEBT-137** | PARTIAL | persistence 部分失败会留下内存/磁盘分叉与半写状态；单文件半 JSON 子项已关 |
| **I-DEBT-138** | OPEN | mongodb 计数、isValid、provider 异常、CallableStatement、Arrow 泄漏、Bearer 常数时间比较；已修复子项按证据裁剪 |
| **I-DEBT-145** | OPEN | binlog/热重载、cluster/schema 隔离、时区、XA 恢复、dbType 缺省、类型转换与迁移计数 |
| **I-DEBT-39·残** | PARTIAL | 传输层已关；warning、溢出、DML、列头元数据与相关值语义残留 |
| **I-DEBT-32-FAMILY** | PARTIAL | 已落地 11 函数；GREATEST/LEAST、JSON path 等待专轮 |
| **I-DEBT-69** | PARTIAL | SHOW WARNINGS/warning_count 已落；跨片行序、瞬态表与 BKA lookup 残留 |
| **I-DEBT-62** | OPEN·挂账 | 非严格 GROUP BY 裸列任意行值；触发场景出现前不投机开轮 |

## 6. 关闭与迁移

1. 每票先写失败测试，再最小修复；相关测试与项目全套验证均须读输出。
2. 生产代码变更重打 jar，活体与完整护栏归档；证据绑定当前 HEAD 和 jar SHA-256。
3. 当前 HEAD CI 全绿后，才把票据连同最终裁定、关闭边界和证据链接移入 [DEBT-COMPLETED.md](DEBT-COMPLETED.md)。
4. 部分完成只更新残留，不整票搬走；STALE 子项保留历史原因。

### 旧章节追溯映射

清理前 `DEBT-QUEUE.md` 的 §1–§6 已迁入 [DEBT-COMPLETED.md](DEBT-COMPLETED.md) 同名历史章节；本文件 §1–§6 已重新定义为当前驾驶舱与四阶段队列。旧文档中的章节引用按此映射追溯。
