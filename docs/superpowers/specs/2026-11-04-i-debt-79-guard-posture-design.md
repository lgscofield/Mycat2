# Spec：I-DEBT-79 —— 护栏缺「干净 8066 前置」，脏进程状态持续制造假红

- 状态：🟢 **刀① 已落地并实证**（`ci/run-example-guard.ps1`，2026-11-04）；刀② 动 CI PR 门、刀③ 全局 abort 语义**仍待拍板**
- 分类：测试/流程侧仪器债 · 对齐 **B**（M1 验证网，A 层的前置仪器）
- 立案轮：2026-11-04（`.superpowers/sdd/2026-11-04-i-debt-35-probe/progress.md`）
- 关联：I-DEBT-35（被本债的假红波及，差点被误翻案）、I-DEBT-80（同现场残留）、I-DEBT-71/72/73（同族"测试隔离前置缺失"，三票已收口，本票是该族的**最后一块**：进程级而非用例级污染）

## 1. 症状（实测，非推断）

2026-11-03 那轮 I-DEBT-70 的收口护栏跑出 `example 550/9F/3E/37skip`，其中 6 类红（`BinaryRepresentationFidelityDifferentialTest` shape1/shape2、`BIT(8)` 读成 `1`、`version_comment [MySQL]→[null]` 等）被当场归因为"既存环境/后端形态族"。2026-11-04 复勘（四格单变量）：

| 格 | 唯一变量 | 结果 |
|---|---|---|
| ① | 无（persona=MYSQL_8_0 + 后端 3306，单跑差分锁） | **5/5 绿**（13.60s） |
| ② | 仅换 persona=MARIADB_10_11 | **5/5 绿**（8.959s） |
| ③ | 仅换后端引擎为 3308 MariaDB（pymysql hint 重绑） | 经 8066 与直连 3308 **逐字节一致** |
| ④ | **干净重启 8066** 后跑全量串跑口径 | 该类 **5/5 绿**（1.559s）；6 类红**整体消失**，实得 `550/0F/5E/37skip`（**F 轮订正**：该 5E 已由 I-DEBT-81 清零，现口径 `550/0F/0E/37skip`） |

**根因**：11-03 那台 8066 在活体取证段被 hint `mycat:createDataSource` 把 `prototypeDs` 热重绑到了 3308 MariaDB，此后**没有重启**就跑了全量护栏。热重绑只改内存。**2026-11-04 复核把这条根因说得更准（并纠正本票初稿两处不精确）**：① 真正被护栏里那台 8066 载入的 home 是 `server/target/.mycat-home`（`MycatCore.findMycatHome()` 未显式 `-DMYCAT_HOME` 时的回退口径），初稿引用的 `.mycat-home-e2e` 是更早探针脚本显式指定的另一份 home——两者不是同一进程载的同一目录；新证据改为"跑完两轮全量后 `server/target/.mycat-home/datasources/` 仍只有 `prototypeDs.datasource.json`（url 恒 `localhost:3306`）、**没有** 测试用 hint 建的 `newDs.datasource.json`，而同族 `clusters/c0.*`、`schemas/db1.*`、`users/root.*` 会被刷新" ⇒ 内存-only 得到独立复证。② 污染源不止人工探针：`createDataSource` / `resetConfig` hint **测试套件自己就在发**（`i70-attrib-pristine.log` L616/L792/L1216…、`attr compat.log` L43/L219 的 `MycatTest.execute:105`）⇒ 连续两轮全量不重启本就会让进程内配置态漂移，"干净前置"是护栏前提而非探针卫生。于是护栏里一部分用例连到的是"上一段活体留下的后端画像"，而用例的期望值按 mysql:8.0 后端写死 ⇒ **假红**（列头两侧逐字相同、仅 BINARY/VARBINARY 值变 null、BLOB 正常，正是后端引擎错位的指纹）。

**代价已量付**：① 一整轮 pristine 对照（`git stash push -u` + 重编 + 逐格比对）本来完全可以避免；② 错误的归因前提被写进 commit `ffeaad0c2` 正文与长期记忆（"compat/assemble 测试进程内跑引擎"），已在本轮推翻并订正——example 全套实为**跨进程 JDBC 客户端**（`example/pom.xml:155`、`MycatTest.java:30-33` 全为普通 JDBC URL、全仓 example 无一处进程内起 `MycatServer`）。

## 2. 缺口定位（当前代码事实）

1. **本地护栏无固定入口**：`mvn -o -B -pl server,example -am test` 一直是手敲命令，散落在各轮 `scratch/*.ps1`，既不重启 8066、也不校验它当前载的是哪个 jar、哪个 persona、指向哪个后端。
2. **CI 侧 `ci/run-example.sh` 只有一半**：它**自己起** Mycat（L33），所以 CI 没有进程残留问题；但就绪判据只是 TCP 连通（L38-44），**不读握手包、不校验 persona 是否真生效**。`MYCAT_PERSONA` 传了非法值会被 `PersonaOverride` **静默回退**到缺省画像——历史上已两次踩（`MYSQL_9_0`→5.7、以及元数据矩阵旧配方对 mariadb 三档一律盖 `MYSQL_5_7`）。静默回退 = 假绿的温床。
3. **活体段无收尾义务**：hint 重绑类探针跑完就散场，既不复原 datasource、也不重启 8066，污染留给下一轮。

## 3. 修法（三刀，按依赖序）

### 刀①（B 层仪器，零产品码，先落）：durable 本地护栏入口 `ci/run-example-guard.ps1`

把 2026-11-04 已在用的 `scratch/clean-guard.ps1` 提升为入库资产，固定五步：

1. **杀**：`Get-NetTCPConnection -LocalPort 8066 -State Listen` → `Stop-Process`（绝不复用任何在活进程）；
2. **起重**：用 `-Jar`（缺省 `server/target/server-1.22-release-jar-with-dependencies.jar`）+ `-Persona`（必填，禁缺省）起服，并打印 jar 的 `LastWriteTime`（防"改了码没重打 jar"）；
3. **就绪** = TCP 通 **且** 读到握手包首字节 `>0`，再 settle（沿用 `run-conn-matrix.ps1` 的 `Test-MycatReady` 配方，替代纯 TCP 判据）；
4. **姿态门（posture gate，本票核心）**：两条都必须过——
   - 前端：经 8066 `select version()` 必须落在 `-Persona` 期望的版本族（`MYSQL_8_0`→`8.0.`、`MARIADB_10_11`→含 `MariaDB` 且 `10.11.`…）；
   - 后端：读**磁盘态** `prototypeDs.datasource.json` 的 url host:port 并直连取 `select version()`，与 persona 族做一致性判定；
   - 任一不符 ⇒ **abort 并打印实测两端版本串，不跑任何测试**（宁 NO_DATA 不伪绿/不假红）。
   - 注意：容器内 `mysql` 客户端连 Mycat 必须带 `--get-server-public-key`，否则非 TLS 的 caching_sha2 full-auth 报 `ERROR 2061 … requires secure connection`（本轮实测踩过，属仪器坑非产品坑）。
5. **跑测**：`mvn -o -B -pl server,example -am test`（`-am` 是硬要求：不带 `-am` 会从本地仓库取上游 jar，引入"两个 jar"的第二变量）；收尾再杀一次 8066。

### 刀②（B 层，动 CI 资产，**需用户拍板**）：`ci/run-example.sh` 同口径加固

- 就绪判据从"TCP 可连"升级为"读到握手首字节 + settle"；
- 起服后加同样的 posture gate：`MYCAT_PERSONA` 设了值就必须被 `select version()` 证实，**非法/被静默回退的 persona 直接 fail 该 CI step**（今天的静默回退会把一档画像测成另一档，属假绿）；
- 判据本身不改测试语义、只让既绿更硬，但它动的是 PR 阻断门，故列为待拍板。

### 刀③（可选，B 层）：`example` 侧 `BackendPostureLockTest`

在 example 测试面加一个自检格：读 8066 `select version()` 与 `-Dcompat.persona`（缺省 MYSQL_8_0）声明比对，不符即 `Assumptions.abort` ⇒ 该轮全 compat 族记 NO_DATA 而非假红。与刀①互为冗余（一个挡在跑测前、一个挡在跑测中）。**是否引入新的全局 abort 语义需拍板**（会让"忘带参数"从红变 skip，可读性下降）。

## 4. 判据（收口硬门）

1. 刀①脚本入库，且 gate 有**判别力**（两格负控 + 一格正控，2026-11-04 全部实测达成）：
   - 负控①：`-Persona MYSQL_9_0`（非法名，历史上真被静默回退过）⇒ **起服前就 abort**，EXITCODE=1；
   - 负控②：`-Persona MARIADB_10_11` 而磁盘态后端仍是 mysql:8.0 ⇒ 前端确实宣称 `10.11.8-MariaDB`（**证伪"只看前端就够"**），磁盘后端腿读出 `8.0.46` ⇒ **引擎族错配 abort**，EXITCODE=1；
   - 正控：`-Persona MYSQL_8_0 -GateOnly` ⇒ `POSTURE OK` + EXITCODE=0 + 收尾把本轮起的 8066 杀掉（不给下一轮留活进程）。
   > **原判据订正（重要）**：本票最初写的是"用 hint 热重绑后端后不重启 ⇒ gate 必 abort"。**该判据不可实现**，2026-11-04 复核推翻了它：hint 建/改的 datasource 只落内存、**不写磁盘**（跑完两轮全量后磁盘 `datasources/` 仍只有 `prototypeDs.datasource.json`，而同族 `clusters/c0.*`、`schemas/db1.*`、`users/root.*` 会被刷新），而 gate 的后端腿读的是磁盘态 ⇒ 内存-only 脏态在 gate 眼里无痕迹。拦住它的正确机制是第 1 步 **kill+restart 本身**（结构性消除），不是 gate。gate 的职责边界因此是"拦画像与后端引擎族错配 + 拦非法 persona 名"，二者已由上表负控证明。
2. 干净重启后跑同一脚本 ⇒ gate 通过、`example` 全量回到本轮实测基线 `550/0F/5E/37skip`（5E 为 I-DEBT-80，与本票正交，不得因本票冒绿）；
   > **基线订正（2026-11-04 F 轮）**：该格的 `5E` 是本票背书时点的真实读数，其后已由 **I-DEBT-81** 产品码修复清零，
   > 现口径为 **`550/0F/0E/37skip`**。"5E 与本票正交、不得因本票冒绿"的纪律本身不变，只是那 5E 后来被真修掉了。
3. `BinaryRepresentationFidelityDifferentialTest` 在全量串跑口径下 5/5 绿（把 11-03 的 6 类假红永久钉死）；
4. 刀②若获批：CI 侧构造"非法 persona 名"负控（如 `MYSQL_9_0`）必须让 step 失败，改回合法名必须绿（判别力双检）。

## 5. 边界（明确不做）

- 不改产品码：hint 热重绑只改内存是**既定设计**（运行期临时改后端姿态本就不该落盘），本票不追求"重绑自动落盘"；
- 不碰 XA 残留 5E（→ I-DEBT-80 独立复定性）；
- 不把 `.superpowers/sdd/**` 下的一次性驱动脚本迁成正式测试资产入库（探针入库+CI 化是另一笔资产治理账，见 I-DEBT-29-EXT-CONN 边界 E3）。
