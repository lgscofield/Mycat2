# MariaDB 后端 ed25519 认证（I-AU-6 / M6）设计草案

> **状态：DRAFT — 待用户评审。** 本文件是 I-AU-6 起轮的勘察+设计草案，非实现计划。
> 未经用户批准不进入 writing-plans，不改动任何生产代码。
> **授权链**：用户 2026-10-23「OK，按照你建议的顺序」→ 步骤⑤「I-AU-6 勘察 + spec 草案，停用户评审 gate」。

## 1. 背景与北极星

北极星：Mycat 客户端眼中**无缝替代 MySQL 各版本**，MariaDB 为 M6 预留扩展位。
M6 的关键缺口之一即 **MariaDB 后端 `client_ed25519` 认证插件**：mysql-connector-j
不支持该插件（实测 `Unable to load authentication plugin 'client_ed25519'`），
连上启用 ed25519 的 MariaDB 后端会直接失败。

I-AU-6 是**后端**（Mycat → 物理 MariaDB）方向的驱动/认证改造，独立于前端
（客户端 → Mycat）认证链，二者正交。

## 2. 当前代码勘察结论（2026-10-23，铁律：不信旧口径，全部 file:line 复核）

2026-10-16 的只读复核四条断言，本轮逐条以当前磁盘证据复验，**均成立**：

| 断言 | 当前证据 | 结论 |
|------|----------|------|
| 根 pom 已版本管理 mariadb-java-client 3.5.3 | `pom.xml:67` `<mariadb.connector.version>3.5.3`；`pom.xml:474-476` dependencyManagement 条目 | ✅ 成立 |
| server/pom 声明该依赖但 `scope=test` | `server/pom.xml:40-42` `org.mariadb.jdbc:mariadb-java-client` `<scope>test</scope>` | ✅ 成立 |
| datasource/pom 该依赖仍被注释 | `datasource/pom.xml:46-50` 整段 `<!-- ... -->`，且注释内滞留**过期版本 `2.5.2`** | ✅ 成立（建后端连接的模块当前无 MariaDB 驱动） |
| `DruidDatasourceProvider` 的 `case mariadb:` 仅设 jOOQ 方言、非 JDBC 驱动路由 | `DruidDatasourceProvider.java:110-111` 仅 `dialect = SQLDialect.MARIADB`；URL 直取 `config.getUrl()`（:46）、`setUrl`（:57） | ✅ 成立 |

**本轮新证（旧口径未记，决定方案走向）：**

- **URL→dbType 派生已就绪**：`DatasourceConfig.getDbTypeRaw()` 已识别
  `jdbc:mariadb:` → `"mariadb"`（`DatasourceConfig.java:58-59`）；`getDbType()`
  缺省时由 URL 反推（:206-214）。故「配 `jdbc:mariadb://` 后端 → 被识别为 mariadb」
  的语义链**本就通**，无需新增类型判定。
- **后端默认走 JDBC 腿**：`DatasourceConfig.type` 默认值 = `JDBC.name()`
  （`DatasourceConfig.java:39`），并保留 `jdbcDriverClass` 字段（:37「保留属性」）。
  JDBC 腿经 `DruidDatasourceProvider` → Druid 依 URL scheme 自动选驱动
  （ServiceLoader），把认证**下压给真实 JDBC 驱动**——MariaDB 驱动原生支持
  `client_ed25519`。
- **NATIVE 腿不支持 ed25519**：`DatasourceType.NATIVE`(:235) 对应 Mycat 自研 MySQL
  wire 客户端（`com.mysql.cj.protocol.x.*`），其认证族无 ed25519 实现；
  ed25519 后端**必须走 JDBC 腿**（默认即 JDBC，符合直觉）。
- **前端无 ed25519 实体**：`protocol/.../auth/` 包仅有 `MysqlNativePasswordPluginUtil`
  等，`AuthPlugin.java:5` javadoc 声称实现含 `client_ed25519` 系**陈旧无实体表述**；
  ed25519 在全仓 java 仅出现在 `FrontAuthMatrixTest`（persona）与该注释。
  → **本 spec 范围明确排除前端 ed25519**。
- **测试床已就绪**：根 pom 管理 testcontainers `mariadb` 模块（`pom.xml:578-579`），
  mariadb CI 三档（10.6/10.11/11.4）已具 PR 阻断力。

**净剩余工作**（旧复核定性一致）= 运行时工程改造 + 活库端到端背书，装外部依赖这步实质已就位。

## 3. 范围

**In scope（M6 后端 ed25519）：**
- 让配 `jdbc:mariadb://` URL + `client_ed25519` 用户的 MariaDB 后端，Mycat 能建连、
  认证、跑通基本 CRUD 与结果集保真。

**Out of scope（另票/候补）：**
- 前端（客户端 → Mycat）ed25519 认证（独立于本链）。
- MariaDB 其余 M6 表征面（persona 冒烟已绿，非无缝部分另行拆票）。

## 4. 方案选型

### 方案 A（推荐）：驱动上 classpath + URL scheme 自然路由，零新代码
把 `mariadb-java-client` 由 test/注释态转为 **datasource 模块 compile 依赖并进 fat-jar**；
用户侧以 `jdbc:mariadb://` 配 MariaDB 后端，Druid 按 scheme 自动选 MariaDB 驱动，
`client_ed25519` 由驱动原生处理。
- 优点：改动面最小（pom 一处 + 版本号归一到 root 管理的 3.5.3），复用既有
  URL→dbType→Druid 链；不引入新抽象。
- 代价：fat-jar 体积增（多一个驱动）；须在文档明示「ed25519 后端只能用 JDBC 腿 +
  `jdbc:mariadb://` URL」。

### 方案 B：显式驱动路由 SPI
新增按 dbType 显式设置 `driverClassName`（激活 `jdbcDriverClass` 保留字段）的
路由层，`mariadb` → `org.mariadb.jdbc.Driver`。
- 优点：路由意图显式、不依赖 Druid 的 scheme 猜测。
- 代价：新增代码面与测试面；相对方案 A 的边际收益低（scheme 路由已实测可用）。

**草案倾向 A**，B 作为「A 的自动路由在真实配置下不稳」时的回退。此选型是**留给用户拍板的第一个决策点**。

## 5. 设计要点（以 A 展开）

1. **依赖**：`datasource/pom.xml` 解注释并去除过期的 `2.5.2`，改由 root
   dependencyManagement 的 `3.5.3` 提供版本（不写死版本）；确认打入 server fat-jar。
2. **URL 约定**：MariaDB 后端一律 `jdbc:mariadb://`；文档与示例 schema 给出范式。
3. **DatasourceType 约束**：ed25519 后端须 `type=JDBC`（默认满足）；NATIVE 腿对
   `jdbc:mariadb:` 的处理需给出**响亮报错**而非沉默失败（避免误配踩坑）。
4. **认证透传**：ed25519 由驱动完成，Mycat 不截获私钥；与既有 sql_mode/会话下发不冲突。
5. **可观测**：连错驱动/缺驱动时的错误信息要指向「MariaDB 后端需 mariadb 驱动 +
   `jdbc:mariadb://`」。

## 6. 测试与验收（关键：无活库即伪绿，坚决不并半成品）

- **端到端金标准**：起真实 MariaDB（testcontainers，persona 10.6/10.11/11.4 任一），
  建 `IDENTIFIED BY ed25519` 用户，配置 `jdbc:mariadb://` 后端，跑通
  ①握手认证成功 ②基本 CRUD ③结果集保真。
- **离线锁（不依赖活库的部分）**：URL→dbType→驱动类名解析的静态守卫；
  NATIVE 腿对 mariadb URL 的响亮报错断言。
- **CI**：compat 矩阵补一列真 MariaDB ed25519 后端用例（后端服务容器已具备）。
- **诚实门槛**：真实 ed25519 活库端到端未跑通前，I-AU-6 不得标 ✅、不得提进阻断绿矩阵。

## 7. 风险与依赖

- fat-jar 引入第二驱动，须确认与现有 mysql-connector-j 无 ServiceLoader 冲突（预期无，URL scheme 隔离）。
- 真 MariaDB ed25519 活库是验收前置；CI 服务容器需能建 ed25519 用户。
- 属 M6 关键路径外（不在 M1→M4 关闭序），排期独立。

## 8. 留给用户的决策点（评审 gate）

1. **选型 A vs B**（§4）——推荐 A。
2. **NATIVE 腿对 `jdbc:mariadb:` 是「响亮报错」还是「自动降级到 JDBC」**（§5.3）。
3. **是否现在就把 I-AU-6 从「推迟(M6 专轮)」转「起轮实施」**，还是本 spec 仅作预研存档、
   排期仍按 2026-10-16 拍板维持推迟。
4. **ed25519 用户建法与 persona 版本**（10.6/10.11/11.4 选哪档作 CI 端到端金标准）。

---
*本草案基于 2026-10-23 当前代码勘察；所有 file:line 均可 `git grep`/`Read` 复核。*
