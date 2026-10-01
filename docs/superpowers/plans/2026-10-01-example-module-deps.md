# I-DEBT-2（D13）example 模块依赖归位 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans（本 harness 无 implementer 子代理类型，按决策图就地 inline 执行 + 末尾 fresh CodeReview 终审；执行方式已由用户 2026-10-01 预批口径裁定）。Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** example 模块测试源编译归零错误，`ci/run-example.sh` 冒烟真实可跑，解除 M1 验证网唯一残留阻断（D13）。

**Architecture:** 两处 pom 依赖声明缺失的修复（server shade 的 dependency-reduced-pom 剥除传递依赖 → example 显式声明 hbt/datasource/plug/replica；javax.annotation.concurrent 属 jsr305 而非 stephenc jcip → 补 jsr305），零计划内源码改动；残留面按 spec §2.2 处置原则逐条裁定。

**Tech Stack:** Maven 多模块 + dependencyManagement、WSL bash + Docker（m1-mysql-a:3306/m1-mysql-b:3307）、surefire。

**Spec:** `docs/superpowers/specs/2026-10-01-example-module-deps-design.md`（F1-F10 取证表、G-E1/G-E2/G-E3 门定义以 spec 为准）

## Global Constraints

- example/pom.xml 新依赖一律 `<scope>test</scope>`、不写 `<version>`（根 pom 管理）。
- JDK 21；本机验证命令默认离线 `-o`，CI 侧不新增离线步骤。
- 测试后端 = Docker m1-mysql-a/b，本机 MySQL 服务禁动；提交前恢复构建副作用文件（`git checkout -- server/src/main/resources/version.txt`）。
- 状态只允许 Evidenced-Done；单文件残留修复 >20 行或触断言语义 → 停手挂账（spec §2.2）。

---

### Task 1: example/pom.xml 依赖补齐 + G-E1 编译门

**Files:**
- Modify: `example/pom.xml`（`</dependencies>` 前追加一个注释块 + 5 个依赖）
- 残留时: `example/src/test/java/**`（按 spec §2.2，逐条账本 Ruling）

**Interfaces:**
- Consumes: 根 pom dependencyManagement 的 jsr305 3.0.2（pom.xml:133/:775）与 io.mycat 兄弟模块 1.22-release 构件（F9 实测在库）。
- Produces: example test classpath 含 io.mycat.calcite/datasource/plug/replica/javax.annotation.concurrent——Task 2 的 run-example.sh 依赖此编译面。

- [ ] **Step 1（RED 已存在）:** 取证轮已采集 `mvn -B -o test-compile -pl example` → BUILD FAILURE、200 行 `[ERROR] /`，日志在 `.superpowers/sdd/d13-evidence/example-testcompile.log`。无需重跑 RED。

- [ ] **Step 2: 修改 example/pom.xml**，在 `vertx-junit5` 依赖之后、`</dependencies>` 之前插入：

```xml
        <!-- MYCAT CHANGE: server 经 maven-shade install 为 dependency-reduced-pom，
             其 compile 依赖（含兄弟模块）全部剥除——消费方必须显式声明（见 server/pom.xml:232 先例）。
             将来给 server 新增 compile 依赖时，此处需人工同步，勿当重复声明删除。 -->
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>hbt</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>datasource</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>plug</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>replica</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- javax.annotation.concurrent.*（NotThreadSafe 等）在 jsr305 包内，
             stephenc jcip-annotations 只有 net.jcip.annotations.*，不可互替 -->
        <dependency>
            <groupId>com.google.code.findbugs</groupId>
            <artifactId>jsr305</artifactId>
            <scope>test</scope>
        </dependency>
```

前提核验（同一命令轮）：根 pom dependencyManagement 存在 io.mycat:hbt/datasource/plug/replica 的管理条目（若无版本管理条目则在此块补 `<version>${project.version}</version>` 并账本记录 Ruling）。

- [ ] **Step 3: 跑 G-E1**

Run: `mvn -B -o test-compile -pl example 2>&1 | Out-File -Encoding utf8 .superpowers/sdd/d13-evidence/ge1-round1.log; Select-String -Path .superpowers/sdd/d13-evidence/ge1-round1.log -Pattern "BUILD (SUCCESS|FAILURE)"`
Expected: `BUILD SUCCESS` 且 `(Select-String -Pattern "^\[ERROR\] /").Count -eq 0`

- [ ] **Step 4（条件步骤，仅 Step 3 FAIL 时）:** 对每条残留错误按 spec §2.2 三分法处置（改 import / @Ignore 登记 / >20 行停手挂账），每处置一条重跑 Step 3 并账本记 `Task 1: Ruling: <错误> — <处置> — <依据>`。

- [ ] **Step 5: Commit**

```bash
git checkout -- server/src/main/resources/version.txt 2>$null
git add example/pom.xml
# 残留修复时追加对应测试源文件
git commit -F target/msg.txt   # 信息含: 5 依赖 + G-E1 门数字 200→0
```

### Task 2: G-E2 冒烟门（run-example.sh 真实跑）

**Files:**
- 不改产品文件；证据落 `.superpowers/sdd/d13-evidence/`

**Interfaces:**
- Consumes: Task 1 的编译绿 classpath；`ci/run-example.sh` 既有三步逻辑（起 Mycat→等 8066→`mvn -B test -pl example`）；Docker m1-mysql-a/b。
- Produces: 首轮逐测试类 pass/fail 实录（Task 3 看板登记的输入）。

- [ ] **Step 1: 前置核验**

```bash
wsl bash -c "docker ps --format '{{.Names}} {{.Status}}' | grep m1-mysql; ! (ss -tln | grep -q ':8066 ')"
```
Expected: m1-mysql-a/b 均 Up；8066 空闲（verify-l1 残留进程先处理——只允许 kill 本项目 java 进程，逐个确认后操作）。

- [ ] **Step 2: 跑冒烟门**

Run: `wsl bash -c "cd /mnt/d/Workspaces/IdeaProjects/Mycat2 && bash ci/run-example.sh > .superpowers/sdd/d13-evidence/ge2-run.log 2>&1; echo RC=\$?"`
Expected: 脚本走完三步（allowed RC≠0：surefire 存在 e2e fail 时 rc=1）；日志含 Mycat 启动、`Tests run:` 汇总行。**RC=0 与 RC=1 都合法，缺"汇总行"或挂起才非法**（挂起 → 按 Review Focus-1 处理）。

- [ ] **Step 3: 断言 ConnectionSmokeTest 确实执行**

Run: `Select-String -Path .superpowers/sdd/d13-evidence/ge2-run.log -Pattern "ConnectionSmokeTest|Tests run:.*Time elapsed|BUILD"`；并数 surefire 报告类数：`Get-ChildItem example/target/surefire-reports -Filter *.txt | Measure-Object`
Expected: ConnectionSmokeTest 出现在执行面且**绿**（红则 root-cause 纳入本任务闭环——spec G-E2）；执行类数 vs 39+1 测试文件的对比记录进证据（Review Focus-5：确认 JUnit4/Jupiter 两类都被发现，0 执行=假绿须查明）。

- [ ] **Step 4: 整理首轮 pass/fail 清单**入 `.superpowers/sdd/d13-evidence/ge2-summary.md`（逐测试类：Tests run/Failures/Errors/Skipped），commit（无产品文件改动则证据目录本就在 git-ignored `.superpowers/`，仅当 ConnectionSmokeTest 修复产生代码改动时提交）。

### Task 3: G-E3 回归门 + 看板收口

**Files:**
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`（快照/进度总表 I-DEBT-2 行/勾选板 M1 残留/变更日志）

**Interfaces:**
- Consumes: Task 2 的 ge2-summary.md pass/fail 实录。
- Produces: 看板 D13 销账态。

- [ ] **Step 1: 回归门**

```bash
mvn -B -o test -pl router 2>&1 | Select-String "^\[INFO\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$"
mvn -B -o test -pl hbt 2>&1 | Select-String "^\[INFO\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$"
mvn -B -o install "-Dmaven.test.skip=true" -pl server -am 2>&1 | Select-String "BUILD (SUCCESS|FAILURE)"
```
Expected: router 15/0/0、hbt 34/0/23、executor 测试面 0、BUILD SUCCESS（数字=spec G-E3 基线，任何偏移账本记 Ruling 并核对归因）。

- [ ] **Step 2: 看板收口**——I-DEBT-2/D13 行 TODO→Evidenced-Done（附 G-E1 200→0、G-E2 冒烟实录摘要、G-E3 三门数字）；M1 阻碍条目"唯一残留阻断"销账；变更日志加行（保留历史行，追加式）；快照下一步指向 I-DEBT-5。

- [ ] **Step 3: Commit**（看板 + `git checkout -- version.txt` 先行）

### Final: 整支终审

review-package 范围 = 本计划起点提交..HEAD → fresh CodeReview 子代理（most capable、code-reviewer.md 模板、spec Review Focus 5 条逐查 + 账本 Rulings）→ re-grade → Critical/Important 一轮 TDD 修复 → deferred minors 入账本 → 看板终审行 → finishing-a-development-branch（呈现菜单，人不在场则默认选项 3 保持现状——预批口径硬停一禁止 merge/push）。

## Review Focus

（承 spec §4，评审时逐条核对：）
1. 环境缺失时 run-example.sh 显式失败不挂起。
2. 新依赖在 CI 全新环境可解析（无离线步骤单独构建 example）。
3. dependency-reduced-pom 同步点注释已写死在 pom。
4. javax/net.jcip 双注解并存不统一（YAGNI）。
5. surefire 两类框架都被发现执行，0 执行=假绿须查明。
