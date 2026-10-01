# I-DEBT-6 冒烟 CCE 修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 `ConnectionSmokeTest` 的 `SHOW DATABASES` `ClassCastException: String→Object[]`，拿到首个真绿冒烟证据并产出 example e2e 首轮 pass/fail 清单。

**Architecture:** 诊断先行——先让服务端把被吞的执行异常栈落到日志（F10），凭栈钉死单列标量投影行在消费边界的确切强转点（F9），再做最小修复使该行到达结果集写行前确为 `Object[]`。不改 SQL 改写规则、不改可视化表数据产出。

**Tech Stack:** JDK 21、Calcite fork + hbt 引擎、janino codegen、RxJava3、Vert.x、slf4j、maven-shade 单 jar。

**Spec:** `docs/superpowers/specs/2026-10-01-smoke-cce-rootcause-design.md`

## Global Constraints

- JDK 21；构建/测试离线（`-o`，本地仓库 `D:\Nexus\repository`；WSL 侧 mvn 须 `export MAVEN_ARGS="-Dmaven.repo.local=/mnt/d/Nexus/repository -o"`）。
- Windows 侧 mvn 前须 `$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"`（会话内会漂回 JDK8）。
- 测试后端 = Docker m1-mysql-a:3306 / m1-mysql-b:3307（root/123456），本机 MySQL 服务禁动；起服务用 `server/target/server-1.22-release-jar-with-dependencies.jar`（8066）。
- 状态只允许 Evidenced-Done：每门当轮真实输出。TDD 红线：产品码改动先有复现它的失败（本债项 RED=现有冒烟红 + 复现的裸 String 触达 (Object[])）。
- 单文件改动 >20 行或触断言语义 → 停手挂账（预批硬停三）。

## Review Focus（承自 spec §4，逐条必查）
1. 异常不再被吞：服务端运行异常须在服务日志落带栈 ERROR 行，logger 关闭时无噪声、不裸 print stdout。
2. 升箱对"元素类型谎报"的鲁棒性：以运行时真实元素类型为准升箱，或自述不符即失败可见。
3. 投影矩阵：单列标量 / 多列 / 常量单列三形态行元素运行时类型各有钉死测试，防修 A 引 B 回归。
4. plan 缓存一致性：若根因为缓存裸 bindable，修复覆盖命中与首建两条来路。
5. CI 可解析：`mvn -B -o test-compile -pl example` 与 `install -pl server -am` 保持绿。

---

### Task 1: 服务端结果集消费边界补异常栈日志（诊断面 / F10）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/PrepareExecutor.java`（`getMysqlPayloadObjectObservable` 的 `onError`/`catch` 分支，约 :83-94）
- Test: `hbt/src/test/java/io/mycat/calcite/PrepareExecutorErrorLoggingTest.java`（Create）

**Interfaces:**
- Consumes: 现有 `PrepareExecutor` 类持有的 slf4j `Logger`（若无则新增 `private static final Logger`）。
- Produces: 一条 ERROR 级、带 `throwable` 栈的日志；不改方法签名与对外行为（异常仍原样 `emitter.onError`）。

- [ ] **Step 1: 写失败测试——异常路径落栈日志**

用 slf4j 测试桩（`org.slf4j.simple` 或项目既有测试日志捕获约定，接手时先 grep `LoggerFactory` 在 hbt 测试里的用法择一）钉：当 `bindable.bind` 抛异常时，`getMysqlPayloadObjectObservable` 返回的 Observable 订阅到错误，且捕获到一条含该异常栈的 ERROR 记录。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -o test -pl hbt -Dtest=PrepareExecutorErrorLoggingTest`（WSL，带 MAVEN_ARGS）
Expected: FAIL——当前 error 分支不落日志，断言"有 ERROR 记录"失败。

- [ ] **Step 3: 在 onError/catch 分支加 `logger.error("<context>", throwable)`**

保持原有 `emitter.onError(...)` 语义不变，仅新增日志；上下文串带被执行的 SQL/parameterizedSQL 便于归位。

- [ ] **Step 4: 跑测试确认通过 + 复现拿栈**

Run: `mvn -B -o test -pl hbt -Dtest=PrepareExecutorErrorLoggingTest`
Expected: PASS
再 `bash target/i6_probe.sh`（WSL），grep 服务日志：
Expected: 出现 `ClassCastException ... cannot be cast to [Ljava.lang.Object;` 的**完整服务端栈**，锁定确切强转调用帧（消解 F9）。

- [ ] **Step 5: 记账本 + Commit**

把钉死的确切强转帧记入账本 `Task 1: Ruling: 强转点=<栈帧>`；提交日志改动。

```bash
git add hbt/src/main/java/io/mycat/calcite/PrepareExecutor.java hbt/src/test/java/io/mycat/calcite/PrepareExecutorErrorLoggingTest.java
git commit -m "fix(hbt): log swallowed result-set execution exception with stack (I-DEBT-6 diagnosis)"
```

---

### Task 2: 按栈修复单列标量行升箱（修复面 / F9 定位后）

**Files:**
- Modify: 依 Task 1 栈择一（候选 a=`ExecutorProviderImpl` 缓存/构造；b=`MycatBindable.asObjectArray` 守卫或 `RxBuiltInMethodImpl.select` 元素类型；c=janino 生成模板单列投影 bridge）
- Test: 对应类的合同/回归测试（优先扩 `MycatBindableContractTest`；若落 codegen 模板则加 `hbt` codegen 层测）

**Interfaces:**
- Consumes: Task 1 钉死的强转帧与运行时元素类型事实。
- Produces: 单列标量投影行到达结果集写行前为 `Object[]`（长度=列数）；`ConnectionSmokeTest` 绿。

- [ ] **Step 1: 依栈写/扩失败测试**（RED=当前该形态产裸标量或未升箱）

若修复点可单测覆盖，先写钉运行时元素类型的失败测试；若仅端到端可证，则以 `ConnectionSmokeTest` 为 RED（Step 2 已红即天然基线）。

- [ ] **Step 2: 复跑确认失败**

Run: `bash target/i6_probe.sh`
Expected: 仍 FAIL（CCE），栈与 Task 1 一致——确保测试/复现指向同一断点。

- [ ] **Step 3: 最小修复**

按栈择一候选改，单文件 ≤20 行；每形态（单列标量/多列/常量）运行时类型对齐。超阈则停手挂账。

- [ ] **Step 4: 复跑确认通过**

Run: `bash target/i6_probe.sh`
Expected: `Tests run: 1, Failures: 0, Errors: 0` → ConnectionSmokeTest 绿。

- [ ] **Step 5: 全量回归 + Commit**

Run: `mvn -B -o test-compile -pl example` + `mvn -B -o test -pl router,hbt`（数字不回退）
```bash
git add -A
git commit -m "fix(hbt): box single-column scalar projection rows to Object[] (I-DEBT-6)"
```

---

### Task 3: example 全量 pass/fail 清单 + 看板收口

**Files:**
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`

- [ ] **Step 1: 重建服务 jar 并跑 run-example（xa 排除）**

Run（WSL）：`mvn -B -o install -Dmaven.test.skip=true -pl server -am` 后 `bash ci/run-example.sh`，`-Dsurefire.excludesFile=target/xa-excludes.txt` 限时保护；证据转存 workspace。
Expected: ConnectionSmokeTest 绿；产出逐类 pass/fail 首轮清单（I-CI-3 跑绿门达成）。

- [ ] **Step 2: 看板收口**

I-DEBT-6 TODO→Evidenced-Done（附冒烟绿 + 全量清单数字）；I-CI-3 行跑绿注记；变更日志追加行（保留历史）；下一步顺位指向 I-DEBT-5。

- [ ] **Step 3: Commit**

```bash
git checkout -- server/src/main/resources/version.txt
git add docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -F target/msg.txt
```

---

## Final: 整支终审

`review-package docs/superpowers/plans/2026-10-01-smoke-cce-rootcause.md <本债项起点> HEAD` → 派 fresh CodeReview 子代理（explicit_target），附 Review Focus 5 条逐查、账本 Ruling 指针、报告 ≤120 行。Re-grade → Critical/Important 一轮修复（各自 RED→GREEN + 全绿）→ 删 workspace → finishing-a-development-branch（人不在场默认选项 3）。
