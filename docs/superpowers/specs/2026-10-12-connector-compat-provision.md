# R0 剩余 · 多 Connector 兼容轴：建格 + 运行时 Provision Runbook

> **这是什么**：招牌"全 connector 跑基本 CRUD"（主 spec §3 M2 / §7 判据 4）里 **connector 轴**的建格现状与点亮清单。口径冲突以主 spec `2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md` §1.1/§7 + 各 connector **官方文档**为准（落实"拿不准查官网"）。
>
> **校准**：2026-10-12，凭当前 `example/.../compat/` 测试族 + 根 `pom.xml` 依赖实读 + MySQL/MariaDB/go-sql-driver 官网实读，非凭记忆。

---

## 1. 硬边界（为何非 Java connector 只能记 NO_DATA）

主 spec L43 明文：兼容性 harness 住在 `example/.../compat/`（**Java** 模块，连 8066 打真实后端），**Java JUnit 测不了 Go/Python/.NET/C 原生客户端**。这一轴**超出 example 能力**，需另立 **polyglot/docker 客户端冒烟网**（= 债务 `I-DEBT-9`「多 connector 矩阵」，分类=架构·挂账）。

- 真跑 Go/Python/.NET/C 需 **装各语言运行时 + docker + 联网** ⇒ 触"装依赖/联网须拍板"红线，助手不自主越线。
- 在此之前，M2 的"全 connector"对非 Java connector **一律记 NO_DATA（未覆盖），不得当 PASS**（spec 图例：NO_DATA=该格未跑出，须响、不得静默当绿）。
- 本文件 = 把"洞"显式钉成格 + 给出用户侧可执行的**点亮 runbook**；实跑点亮属 I-DEBT-9 专轮（红线后由用户启）。

---

## 2. Connector 轴矩阵（目标 5 类，逐类现状）

| Connector | 版本档 | 覆盖方式 | 当前态 | 依据 / 缺口 |
|---|---|---|---|---|
| **Connector/J** | **9.x**（根 pom `mysql-connector-j:9.3.0`） | `ConnectionSmokeTest`+`CrudResultMatrixTest`（连 8066 打真实后端）× 7 档 | 🟢 **PASS** | 已跑绿（见 `matrix-report.md`）；此即 M2"至少一个 connector"底线 |
| Connector/J | 5.1 / 8.x | 需切 classpath 到旧版 Connector/J 再跑四件套 | ⚪ NO_DATA | 目标 §1.1 点名 5.1/8.x/9.x；现类路径仅 9.x，旧线未跑=未覆盖（换 jar 非装新语言，红线较轻，但仍需用户裁定引入旧 jar） |
| **Go** `go-sql-driver/mysql` | — | 独立 `go run` 冒烟（连 8066 打 CRUD） | ⚪ NO_DATA | Java 测不了；见 §3.1 runbook；触 Go 工具链红线 |
| **Python** `PyMySQL` / `mysqlclient` | — | 独立 `python` 冒烟 | ⚪ NO_DATA | Java 测不了；见 §3.2 runbook；触 Python/pip 红线 |
| **C** MariaDB Connector/C（或 MySQL Connector/C） | — | 编译 `smoke.c` 调 `mysql_real_connect` | ⚪ NO_DATA | Java 测不了；见 §3.3 runbook；触 C 工具链/libmysqlclient 红线 |
| **.NET** MySQL Connector/NET（或 Npgsql 族外的 `MySql.Data`） | — | `dotnet run` 冒烟 | ⚪ NO_DATA | Java 测不了；见 §3.4 runbook；触 .NET SDK/NuGet 红线 |

> TLS 轴（R4）点亮时，同 runbook 各语言追加 `tls`/`ssl-mode` 参数（§3 已附参数名），TLS on/off 逐 connector 再展开为二维格。TLS 传输升级(R4 Task3)因 BouncyCastle 装依赖红线暂停，故 connector×TLS 全格当前 NO_DATA。

---

## 3. 逐 connector Provision Runbook（用户侧执行·当前均 NO_DATA）

**公共前置**：Mycat2 前端在 `127.0.0.1:8066` 起（见 `start.bat` / `server` 模块），后端 ds0/ds1 指向真实 MySQL/MariaDB；测试库/表用 compat 四件套同款最小面（建表→insert→select→update→delete）。凭据以运行环境实际注入，**勿写死进仓库**。

### 3.1 Go — `go-sql-driver/mysql`
- 依据：`github.com/go-sql-driver/mysql` README「DSN」节。DSN 形如 `user:password@tcp(127.0.0.1:8066)/dbname`；TLS 用 `?tls=true`（校验证书链）/`tls=skip-verify`（自签免校验）/`tls=preferred`（服务端广告才升级，配 `allowFallbackToPlaintext=true` ≈ CLI `--ssl-mode=PREFERRED`）。
- 冒烟：`sql.Open("mysql", dsn)` → `db.QueryRow("SELECT 1")` → 建表/insert/select 往返。判绿＝无 error 且 CRUD 值保真。
- 点亮命令（用户）：`go mod init compat && go get github.com/go-sql-driver/mysql && go run smoke.go`。

### 3.2 Python — `PyMySQL` / `mysqlclient`
- 依据：PyMySQL 官方 / MySQL Connector/Python「Connection Methods」。连接参数 `host/port/user/password/db`；TLS：PyMySQL `ssl={'ca':...}` 或 `ssl_ca`/`ssl_verify_cert`，`ssl_disabled=True` 对应 DISABLED。
- 冒烟：`pymysql.connect(host="127.0.0.1", port=8066, ...)` → `cursor.execute("SELECT 1")` → CRUD 往返。
- 点亮命令（用户）：`pip install PyMySQL && python smoke.py`。

### 3.3 C — MariaDB Connector/C（或 MySQL Connector/C）
- 依据：`mariadb.com/docs/connectors/mariadb-connector-c/connect-with-mariadb-connector-c`。`mysql_init` + `mysql_real_connect(conn, host, user, pass, db, port, NULL, 0)`；TLS 经 `mysql_options(MYSQL_OPT_SSL_*)` / `mysql_real_connect` 前设 ssl 选项（MariaDB 亦可 `MYSQL_SET_SERVER_CERT/SSL_CA`）。
- 冒烟：连 8066 → `mysql_query("SELECT 1")` → CRUD；`mysql_errno` 须 0。
- 点亮命令（用户）：`gcc smoke.c $(mariadb_config --include --libs) && ./a.out`（或 `mysql_config`）。

### 3.4 .NET — MySQL Connector/NET (`MySql.Data`)
- 依据：MySQL Connector/NET 连接字符串。`server=127.0.0.1;port=8066;uid=..;pwd=..;database=..`；TLS：`sslmode=Required|Prefer|None`（`--ssl-mode` 语义映射）+ `certificate`。
- 冒烟：`MySqlConnection.Open()` → `SELECT 1` → CRUD 往返。
- 点亮命令（用户）：`dotnet add package MySql.Data && dotnet run`。

> 各语言 CLI 冒烟（`mysql --host=127.0.0.1 --port=8066 -e "SELECT 1"`）可作最低端 sanity，但**不替代**编程式 connector 格（spec 点名的是驱动库非 CLI）。

---

## 4. 看板/债务回写口径

- `DEBT-QUEUE` M1 仪表盘"connector 只测 Connector/J（目标含 5 类）"→ 保留为**开放**，但注明 Connector/J 9.x 已 PASS、其余 4 类=本文 NO_DATA 建格 + runbook 已备。
- `I-DEBT-9`（TLS/mysqldump/多 connector 矩阵 0 覆盖）之"多 connector"子面 → 由"未建格"推进为"**已建格记 NO_DATA + provision runbook 就绪**"；**真跑（polyglot/docker 网）仍为架构·挂账专轮**，须用户拍板装运行时后启。
- **绝不**把非 Java connector 写成 PASS——当前无一条非 Java connector 实跑证据。
