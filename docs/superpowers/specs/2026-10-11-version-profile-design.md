# VersionProfile 版本画像抽象设计（I-VE-1）

## 0. 背景与问题

Mycat2 的头条目标是：在客户端眼中**无缝替代** MySQL 5.7/8.0/8.4/9.x 与 MariaDB 10.6/10.11/11.4。要让客户端"信"我们是某个版本的服务器，代理必须在**握手、能力协商、版本自述**这几个面上成套、自洽地呈现该版本的特征。

当前这些自述信息**散落在多处硬编码、互不联动**，且只有一根"版本串"可配、还被塞进可变静态量。凭当前代码核实的现状：

| 面 | 现状（file:line） | 问题 |
|---|---|---|
| 握手包 serverVersion | `proxy/.../front/MySQLClientAuthHandler.java:251` `new String(MySQLVersion.SERVER_VERSION)` | 硬读可变静态 |
| 握手包 charset | 同上 `:256` `setCharacterSet(8)` | 写死 utf8_general_ci |
| 握手包 statusFlags | 同上 `:257` `setStatusFlags(2)` | 写死 |
| 握手包认证插件名 | 同上 `:260` `MysqlNativePasswordPluginUtil.PROTOCOL_PLUGIN_NAME` | 写死 native |
| 能力位掩码 | `config/.../MySQLServerCapabilityFlags.java:178` `getDefaultServerCapabilities()` | 固定掩码；`CLIENT_SSL`(:195)、`CLIENT_COMPRESS`(:188)、`CLIENT_CONNECT_ATTRS`(:201)、`CLIENT_SESSION_TRACK`(:203) 全被注释掉 |
| 版本函数 | `hbt/.../infofunction/MycatVersionFunction.java:40` `return MySQLVersion.SERVER_VERSION_STRING` | `SELECT VERSION()` 读可变静态 |
| @@ 变量族 | `protocol/.../type/MySQLVariablesEnum.java` 声明 `version`/`version_comment`/`license` = GLOBAL；值实际来源 = `hbt/.../MysqlVariableServiceImpl.java:34-52` 对后端真实 `SHOW GLOBAL VARIABLES` 探测（fallback 分支 :53-96 **不 seed 这三项**） | **非读 `MySQLVersion` 静态量**，走后端透传；与 `VERSION()` 存在已知不一致（见 §1.1） |
| 可变静态量 | `protocol/.../type/MySQLVersion.java:31/34/36` `SERVER_VERSION_STRING`/`SERVER_VERSION`/`setServerVersion()` | 全局可变量，反模式（对应 I-VE-3） |
| 唯一配置口 | `config/.../ServerConfig.java:64` `private String serverVersion = "5.7.33-mycat-2.0"` → `server/.../MycatCore.java:108` `MySQLVersion.setServerVersion(...)` | 只有一根串可配，能力位/插件/字符集不受它管 |

**活的握手路径核实**：`server/.../vertx/VertxMySQLAuthHandler.java:58` 借用 `MySQLClientAuthHandler.createHandshakePayload(...)`（静态方法）产出握手字节；proxy 模块 `MySQLClientAuthHandler` 的 NIO `onSocketRead` 路径无实例化点 = 死码，仅其静态 `createHandshakePayload` 被借用（与既有 review 结论一致）。启动期能力位在 `server/.../vertx/VertxMycatServer.java:147` 计算后传给每个 `VertxMySQLAuthHandler`。

**已有的可复用件**：`config/.../util/MySQLDatabaseMetadata.java:31` `parse(String)` 已能解析 major/minor/micro 并检测 MariaDB 前缀；`ServerConfig.computeClientDeprecateEof()`(:126) 已用它把版本串联动到 `CLIENT_DEPRECATE_EOF` 位。

一句话概括差距：**只有一根版本串可配，且它管不了能力位/插件/字符集，彼此不联动。**

## 1. 目标与边界（已拍板）

**VersionProfile（版本画像）= 一个不可变对象**，打包"Mycat 对客户端伪装成哪个 MySQL/MariaDB 版本"所需的全部自述信息，把上面散落的硬编码**收敛成配置驱动、成套联动**的单一来源。

- ✅ **仅抽象 + 接线**（首版范围）：把散落的硬编码收敛成画像对象；握手、能力位、`version()`、`@@version` 族统一从画像读取，彼此自洽。
- ❌ **不做真实认证协商 / TLS / caching_sha2 / RSA / ed25519**：画像只"宣称"（自述）插件名与能力位，不改密码校验逻辑。认证协议的真实执行属独立里程碑 **T-AUTH**（I-AU-2 前端 TLS / I-AU-6 后端 ed25519）。
- ✅ **服务级全局粒度**（已拍板）：一个 Mycat 实例一个画像。理由：代理对客户端只呈现"一个服务器"，后端物理库可能异构，但对前的自述必须统一。
- ✅ **顺带消灭可变静态**（已拍板，坐实 I-VE-3）：本轮移除 `MySQLVersion` 可变静态量，改为启动期构造不可变画像单例并走 DI。

### 关键设计决策（用户 2026-10-11 拍板）

1. **persona 首版范围**：`ServerPersona` 枚举**定义全部 7 个**（MYSQL_5_7/8_0/8_4/9_X、MARIADB_10_6/10_11/11_4），但 **bundle 值只坐实 `MYSQL_5_7`**（逐字节等于当前硬编码 = 零回归锚点）；其余 persona 的版本串可由 `MySQLDatabaseMetadata.parse()` 派生，但**能力位/插件差异（如 8.0 的 caching_sha2 默认、CLIENT_SESSION_TRACK）暂不填充**——现在填等于往 T-AUTH 领域投机，等有真实认证实现再坐实。
2. **可变静态移除**：本轮一并做，接受略微扩大的 blast radius，用零回归验收锁兜底。

### 1.1 接线范围收敛 + 已知不一致（凭当前代码复核订正）

写实施计划时核实：真正读 `MySQLVersion` 可变静态的**只有两处**——握手 `createHandshakePayload`（`SERVER_VERSION`）与 `SELECT VERSION()`（`MycatVersionFunction` → `SERVER_VERSION_STRING`）；能力位在 `VertxMycatServer:147`/`getDefaultServerCapabilities`；配置注入在 `MycatCore:108`。而 `@@version`/`@@version_comment`/`@@license` **不读该静态**，是 `MysqlVariableServiceImpl` 对后端真实 `SHOW GLOBAL VARIABLES` 的透传。

- **本首版接线范围**（4 个真实落点）：握手字节、`VERSION()`、启动能力位、配置注入。`@@` 变量透传路径**不动**（改动它 = 破坏零回归，且透传后端版本本身属"无缝"正确行为）。
- **已知不一致（显式记录，非本刀修）**：当 persona 宣称版本 ≠ 后端真实版本时，`SELECT VERSION()`（回宣称串）与 `SELECT @@version`（回后端真版本）会不一致。统一二者属"版本自述一致性"的特性级工作，推给后续（与 T-AUTH/T-META 对齐），本版不投机。

## 2. 架构设计

### 2.1 组件划分

```
ServerPersona (enum)          ── 定义"有哪些画像" + 每个画像的默认版本串
   │
VersionProfile (immutable)    ── 一个画像的具体 bundle：
   • serverVersionString         版本串（握手 serverVersion、SELECT VERSION()、@@version 同源）
   • capabilityMask              能力位掩码（握手 caps；5.7 persona = 现掩码）
   • declaredAuthPlugin          宣称的认证插件名（握手 authPluginName；首版恒 native）
   • defaultCharacterSet         字符集 id（握手 charset；首版 5.7 = 8）
   • statusFlags                 状态位（首版 = 2）
   （versionComment/license 不纳入本画像：@@ 变量走后端透传、本版不接线，见 §1.1，YAGNI）
   │
VersionProfileFactory         ── 由 ServerConfig(persona + 可选覆盖串) 构造画像单例
   │
   ├── VertxMycatServer:147        启动期算 capabilityMask → 传入每个 handler
   ├── createHandshakePayload      读画像各字段生成握手字节
   └── MycatVersionFunction.version()   读画像 versionString
   （MySQLVariablesEnum 的 @@version 族不在接线范围：走后端透传，见 §1.1）
```

画像单例经 `MetaClusterCurrent`（DI 缝合点，`MycatCore.java:107` 已有 `MycatServerConfig` 注册先例）注册，消费点从 DI 取，替代直接读静态。

### 2.2 配置机制

扩展 `config/.../ServerConfig.java`：

- 新增 `ServerPersona persona`（枚举），缺省 `MYSQL_5_7`。
- 保留 `String serverVersion`（:64，语义不变 = 自由串覆盖）。
- 解析优先级：**显式 `serverVersion` 覆盖串 > `persona` 内置默认串**。两者都给时以覆盖串为准，persona 只决定 bundle 中"串以外"的能力位/字符集默认（首版这些仅 5.7 坐实）。
- `VersionProfileFactory.from(serverConfig)` 产画像；`computeClientDeprecateEof()`(:126) 改为画像 capabilityMask 的一个派生位（或保持等价），确保现有 DEPRECATE_EOF 联动零漂移。

### 2.3 消费点接线（改动清单）

| 文件 | 改动 |
|---|---|
| `protocol/.../type/MySQLVersion.java` | **移除**可变静态 `SERVER_VERSION_STRING`/`SERVER_VERSION`/`setServerVersion()`；`PROTOCOL_VERSION` 常量可保留或并入画像 |
| `config/.../ServerConfig.java` | 新增 `persona` 字段；`serverVersion` 保留 |
| `config`（新增类） | `ServerPersona` 枚举 + `VersionProfile` 不可变类 + `VersionProfileFactory`（画像归属 config 模块，与 `MySQLServerCapabilityFlags`/`MySQLDatabaseMetadata` 同域，避免 protocol→config 反向依赖） |
| `server/.../MycatCore.java:108` | 由 `setServerVersion(...)` 改为构造画像单例并 `MetaClusterCurrent.register(VersionProfile.class, profile)` |
| `server/.../vertx/VertxMycatServer.java:147` | 从画像取 `capabilityMask`（替代 `getDefaultServerCapabilities()` 直调），仍叠加 DEPRECATE_EOF |
| `proxy/.../front/MySQLClientAuthHandler.java:248` `createHandshakePayload` | 改为接收画像参数（或在方法内取 DI 画像），version/charset/statusFlags/plugin/caps 全读画像；`VertxMySQLAuthHandler:58` 调用点传入 |
| `hbt/.../MycatVersionFunction.java:40` | `version()` 从画像取 versionString（DI 或静态 holder）替代可变静态 |
| `MySQLVariablesEnum` / `MysqlVariableServiceImpl` | **本刀不改**：@@version 族走后端真实探测透传，不读 `MySQLVersion` 静态，移出接线范围（理由见 §1.1） |

> **模块归属（已 file:line 核实，无环）**：读取各模块 pom 内部依赖边——`config` 无任何 io.mycat 内部依赖（最上游叶子），而 `protocol`→config、`hbt`→config、`proxy`→config 均直接依赖，`server`→proxy/hbt→config 传递依赖。故 `ServerPersona`/`VersionProfile`/`VersionProfileFactory` 三个新类**确定落 `config` 模块**（与 `MySQLServerCapabilityFlags`、`MySQLDatabaseMetadata` 同域），全部消费方可直达且零循环依赖。`MySQLVersion` 的可变静态成员从 `protocol` 移除；`PROTOCOL_VERSION=10` 常量可留在 protocol 或并入画像（实施期就近选择，不影响依赖方向）。

## 3. 零回归策略

- 缺省 `persona = MYSQL_5_7`，其 bundle 逐字节等于当前硬编码：version=`"5.7.33-mycat-2.0"`、charset=`8`、statusFlags=`2`、caps=`getDefaultServerCapabilities()` 现值、plugin=`mysql_native_password`。
- 验收即证明：**不配置时**，握手字节、`SELECT VERSION()`、`@@version`、启动 caps 与改动前**完全一致**。

## 4. 测试与 DoD

**测试**
1. **persona→bundle 映射单测**：`MYSQL_5_7` 逐字段等于当前硬编码常量（断言数值，防漂移）。
2. **握手字节回归锁**：同一 sessionId/capabilities/seed 输入下，改动前后 `createHandshakePayload` 产出字节完全相同。
3. **版本自述一致性**：默认 persona 下 `SELECT VERSION()` 值不变（= `"5.7.33-mycat-2.0"`）；`@@version` 族维持后端透传行为不变（本刀不接线，作 no-change 断言）。
4. **活路径 e2e 零回归**：`ConnectionSmokeTest`、`FrontAuthMatrixTest` 连跑绿；全单测基线（config/common/router/va/hbt/executor）无新增红。

**DoD（出口判据）**
- 默认 persona 零回归（上述 1–4 实证通过）；
- 画像可配置（persona + 覆盖串两条路径均可构造出自洽 bundle）；
- 可变静态 `MySQLVersion` 已移除（I-VE-3 就此收口）；
- 全单测基线绿。

## 5. 明确不做（推给后续里程碑）

- 真实 caching_sha2 fast/full-auth、RSA、TLS、ed25519、per-user 认证插件配置 → **T-AUTH**（I-AU-2/I-AU-6，M2/M6）。
- 8.0/8.4/9.x/MariaDB persona 的能力位/插件差异填充 → 待 T-AUTH 落地后坐实（本设计只定义枚举与版本串派生，不投机填 bundle）。
- MariaDB 画像的前端字符集/排序差异、`5.5.5-` 前缀语义细化 → M6。

## 6. 与全局里程碑的关系

- 本项 = 兼容设计 §3 里程碑表 **M2（L1 连接达标）** 内的 **I-VE-1**，依赖 **M1 验证网**（CI 矩阵）做宽度背书，但本首版**以本地零回归验收即可 Evidenced-Done**（宽度真跑属 M1/M2 后续）。
- 与依赖图（`2026-09-30-...compatibility-design.md` §4）：`VER[T-VER 版本档案] → AUTH / META`，即 VersionProfile 是认证宽度三件套与元数据仿真的上游地基。
- 顺带收口 **I-VE-3**（消除启动期全局可变量）。
