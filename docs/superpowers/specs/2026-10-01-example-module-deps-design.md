# I-DEBT-2（D13）example 模块依赖归位 设计文档

> **执行授权来源：用户 2026-10-01 预批**（"下一个阶段我可能不在电脑旁边，你可以按照我们前面的思路和计划，自己往下执行吗？"→ 助手提出"一次性预批 + 四条硬停"口径，用户答复"可以"）。本 spec 的评审门由该预批替代，批态 = 自审通过落盘。

**Goal:** 修复 example 模块测试源编译面（实测 39 文件 / 200 行错误 → 0），使 `ci/run-example.sh` 在 CI 与本机都能真正跑起来测试，产出逐数据库版本的 pass/fail——解除 M1 验证网唯一残留阻断（D13）。

**Architecture:** 根因是两处 Maven 依赖声明缺失，不是测试代码坏：① server 模块被 maven-shade 打包，install 时以 dependency-reduced-pom 替换主 pom，全部 compile 依赖（含 hbt/datasource/plug/replica 兄弟模块）被剥除——下游消费方 `io.mycat:server` 拿不到任何传递类（`dependency:tree` 实证 server 节点为零子节点孤node）；② 测试源 `import javax.annotation.concurrent.NotThreadSafe` 需要 **jsr305**（该包属于 jsr305），而 example 现有 `com.github.stephenc.jcip:jcip-annotations` 只含 `net.jcip.annotations.*` 包（jar 内容实测），两者不可互替。修法 = example/pom.xml 显式补 5 个 test-scope 依赖，与 server/pom.xml:232-244 既有先例（"MYCAT CHANGE: explicit … transitive chain broken"注释块）同款式。

**Tech Stack:** Maven 多模块（根 pom dependencyManagement 集中版本，jsr305 3.0.2 已管理于 pom.xml:133/:775）、JUnit 4 + jupiter 混布、surefire、`ci/run-example.sh`（起 Mycat → 等 8066 → `mvn -B test -pl example`）。

**Spec:** 本文档即 spec。上游看板条目：`docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md` 之 I-DEBT-2 / D13。

## Global Constraints

- JDK 21；Maven 离线可用（本地仓库 `D:\Nexus\repository`，hbt/datasource/plug/replica 1.22-release 四构件实测在库）。
- 版本一律走根 pom dependencyManagement，example/pom.xml 新依赖不写 `<version>`。
- 测试后端 = Docker 容器 m1-mysql-a:3306 / m1-mysql-b:3307（root/123456），本机 MySQL 服务禁动。
- 状态只允许 Evidenced-Done：每个门都要有当轮真实输出。
- TDD 红线：`test-compile` 绿门即本债项的 RED→GREEN 主循环（RED=当轮 200 行错误日志，已采集）。

## 1. 根因取证（全部当轮实测，非转抄旧账）

| # | 事实 | 证据 |
|---|------|------|
| F1 | example test-compile 失败：39 个测试文件、200 行 `[ERROR]` | `mvn -B -o test-compile -pl example` → BUILD FAILURE，日志存 `.superpowers/sdd/d13-evidence/example-testcompile.log` |
| F2 | 缺失包/符号分六组：`javax.annotation.concurrent`(NotThreadSafe×66)、`io.mycat.replica.heartbeat[.strategy]`(HeartBeatStrategy×14)、`io.mycat.plug.loadBalance`、`io.mycat.calcite[.spm/.table]`(ReplicaSelectorManager×6/DbPlanManagerPersistorImpl×2/DrdsSqlWithParams×2)、`io.mycat.datasource.jdbc[.datasource]`(JdbcConnectionManager×2/ReplicaSelectorRuntime×2) | 同日志错误聚合 |
| F3 | 符号→模块归属：DrdsSqlWithParams/DbPlanManagerPersistorImpl→hbt；JdbcConnectionManager→datasource；ReplicaSelectorManager/HeartBeatStrategy→replica；loadBalance→plug | `git grep -l` 各符号定义文件 |
| F4 | server 在 example 依赖树中为**零传递子节点**（hbt/datasource/proxy/executor/va 全不出现） | `mvn -B -o dependency:tree -pl example`：`+- io.mycat:server:jar:1.22-release:compile` 无子节点 |
| F5 | 原因：server/pom.xml 配置 maven-shade-plugin（:248-284），shade 以 dependency-reduced-pom install，剥除 shaded 的 compile 依赖；server/pom.xml:232 已有先例注释 `MYCAT CHANGE: explicit Calcite dependencies (hbt transitive chain broken by invalid local repo POMs)` | server/pom.xml 阅读 |
| F6 | `com.github.stephenc.jcip:jcip-annotations:1.0-1` jar 内只有 `net/jcip/annotations/*.class`，**无** `javax/annotation/concurrent` 包 | 解包实测 jar Entries |
| F7 | jsr305 3.0.2 已在根 pom dependencyManagement（`<jsr305.version>3.0.2</jsr305.version>` pom.xml:133，管理条目 :775） | 根 pom 阅读 |
| F8 | 测试引用的 server 本体类（io.mycat.hint/commands/newquery/sqlhandler/ConfigReporter…）不在缺符号面——server 主 jar 自身 classes 可用；io.mycat.config/beans/util/protocol 经 router(test) 传递可用（router→config/common→protocol） | 错误清单与 dependency:tree 交叉核对 |
| F9 | 本地仓库 hbt/datasource/plug/replica 1.22-release jar 齐全（离线可解析） | `Test-Path` 四构件全 True |
| F10 | ConnectionSmokeTest（M1 逐版本冒烟核心）就在 example/src/test/java/io/mycat/compat/，被 D13 阻断 | `git grep -l` |

## 2. 设计

### 2.1 修改清单（计划内唯一改动文件：example/pom.xml；残留处置见 2.2，可能额外触碰测试源）

新增 5 个依赖，全部 `<scope>test</scope>`（example 的 main 源只有 MycatRunner 一个类，不碰这些模块），带 MYCAT CHANGE 风格注释说明根因：

```
io.mycat:hbt            （calcite 契约类、DrdsSqlWithParams、spm）
io.mycat:datasource     （jdbc/datasource 类）
io.mycat:plug           （loadBalance）
io.mycat:replica        （heartbeat/strategy、ReplicaSelector*）
com.google.code.findbugs:jsr305 （javax.annotation.concurrent 注解，版本走根 pom 管理）
```

不添加：config/protocol/common/router（已有或经传递可得，F8）；lombok（本轮错误面零 lombok 报告，说明已可得）。

### 2.2 残留处置原则（未知面）

依赖补齐后若出现**新错误**（API 搬迁/签名漂移类），逐条按下述处置原则处理（新增修复仍须先有复现它的失败编译输出，即 RED 已自然存在）：
- 属"测试引用了不存在的旧 API 且现世有对应物" → 改 import/调用点，最小化；
- 属"被引用的类确已从库中消失（历史特性删除）" → 该测试类整体 `@Ignore("I-DEBT-2: <类> API 已消失，见看板")` 并在看板登记，**不删测试文件**；
- 单文件改动超过 20 行或涉及断言语义 → 停手挂账待裁定（预批口径硬停三：棘轮式止损）。

### 2.3 验收门（全绿才叫 Evidenced-Done）

- **G-E1（编译门）:** `mvn -B -o test-compile -pl example` → BUILD SUCCESS，`[ERROR] /` 行数 = 0。
- **G-E2（冒烟门）:** 本机 Docker m1-mysql-a/b 就绪下 `bash ci/run-example.sh`（WSL 内跑）：脚本走通全三步（起 Mycat → 8066 就绪 → surefire 跑完并产出汇总行），且 `ConnectionSmokeTest` **确实执行**（surefire 报告含该类，pass/fail 如实记录）。允许 e2e 测试类存在失败——M1 的语义就是产出逐版本 pass/fail 清单；但 ConnectionSmokeTest 若红，必须root-cause 并纳入本债项闭环（它是纯连接冒烟，红=还有运行时断层，属 D13 本体）。
- **G-E3（回归门）:** 三门数字不回退：router 15/0/0、hbt 34/0/23（23 error=I-DEBT-5 既有基线）、executor 0；`mvn -B -o install -Dmaven.test.skip=true -pl server -am` 仍 BUILD SUCCESS。
- 看板收口：I-DEBT-2/D13 行 TODO→Evidenced-Done，登记逐版本 pass/fail 首轮实录；M1"唯一残留阻断"销账。

## 3. 非目标

- 不追求 39 个 e2e 测试类全绿（那是 M1 pass/fail 清单的输出内容，不是 D13 的完成条件）。
- 不动 server 的 shade 配置（改 shadedArtifactAttached/三分辨率布局会波及发布产物形态，另行设计）。
- 不做 A 组寄生包名迁移、不动 9 对孪生副本（各自债项）。
- 不改任何测试的断言语义（除 2.2 残留处置所列最小 import 修正）。

## 4. Review Focus（spec 隐含、任务测试未必覆盖的输入类）

1. **环境缺失时的行为**：8066/3306/3307 任一不可达时 `ci/run-example.sh` 必须显式失败退出（脚本已有 60s 就绪轮询+set -e），不得无限挂起——验证门 G-E2 之外，评审需确认残留处置不会引入新挂点。
2. **CI 全新 checkout 与本机离线差异**：新依赖在 GitHub runner 上首次解析需联网拉取 jsr305/兄弟模块（reactor 内构建可解决自身）；jsr305 3.0.2 是中央仓库常青件，风险≈0，但评审应确认 example 不在任何 `-o` 离线 CI 步骤中被单独构建。
3. **dependency-reduced-pom 的脆弱同步点**：将来任何人给 server 新增 compile 依赖，example 不会自动获得——pom 注释必须把这个约定写死在依赖块上方（防止后人只看到"重复声明"而手滑删除）。
4. **注解双轨**：测试源同时使用 `@javax.annotation.concurrent.NotThreadSafe` 与 `@net.jcip.annotations.NotThreadSafe`（AlterTableTest:15/:16 实测并存）——两者都保留、不统一（统一是化妆性改动，YAGNI）。
5. **surefire 发现面**：example 混布 JUnit4(@Test org.junit)/Jupiter——根 pom 已有 junit-vintage 相关注释（pom.xml:187），G-E2 的 surefire 汇总必须确认两类都被执行而非静默跳过（对比执行类数与文件数）。

## 5. 风险与止损

- **未知残留面**（最大风险）：200 行错误全部映射到 F2 六组，但 javac 报错有截断性（前 200 条后可能压着更深错误）。止损 = 2.2 原则 + 单文件 >20 行改动即停手挂账。
- **e2e 大面积红**：G-E2 允许 pass/fail 并存，但若 surefire 显示 0 个测试被执行（全被跳过/发现失败），那是验证网假绿，必须查明后才算收口。
- 撞不可逆操作/需 merge/push/路径全蒙 → 预批口径四条硬停照旧。
