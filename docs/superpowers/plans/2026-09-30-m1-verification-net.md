# M1 验证网（T-CI）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前完全坏掉的 CI 修成能在 JDK 21 下构建 Mycat2、并对 MySQL/MariaDB 多版本自动跑 example 兼容性测试、产出逐版本 pass/fail 报告的验证网。

**Architecture:** 复用现有"裸跑 jar + JDBC 驱动 example 测试"模型（Mycat 读 jar 内置配置，前端 8066，后端 localhost:3306/3307）。CI 用 GitHub Actions `services` 起后端容器，`strategy.matrix` 换镜像版本、端口固定，从而不改 Mycat 配置即可跨版本复用。M1 只建网、不改任何产品代码。

**Tech Stack:** GitHub Actions（ubuntu-latest, setup-java@v4 temurin 21）、Docker 镜像 mysql:5.7/8.0/8.4/9.0 + mariadb:10.6/10.11/11.4、Maven、JDK 21、Connector/J 9.3.0。

**Spec:** `docs/superpowers/specs/2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md`（T-CI / §0 / §7）
**追踪:** 状态写回 `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`（每项 Done 须附 CI 运行证据 URL）。

## Global Constraints

- JDK 版本：**21**（`pom.xml:43 java.version=21`）；构建产物主类 `io.mycat.MycatCore`。
- jar 路径与名字（实证）：`server/target/server-1.22-release-jar-with-dependencies.jar`（`server/pom.xml:252 finalName`；旧 CI 写的 `mycat2/target/mycat2-...` 路径+名字均错）。
- Mycat 前端端口 **8066**（`server/src/main/resources/server.json`），后端 prototype 数据源写死 **localhost:3306**（`datasources/prototypeDs.datasource.json`）。
- 后端双实例端口 **3306 / 3307**（`MycatTest.java:29-30`），账号 **root / 123456**（`prototypeDs.datasource.json`、`MycatTest.java:28`）。
- **M1 不改产品代码**：只动 `.github/workflows/*` 与新增 `ci/` 脚本；任何前端/认证改动留待 M2。
- 单一事实源：任务完成后回写 PROGRESS 看板对应 `I-CI-*` 行状态与证据链接；CI 变红即打回 `Regressed`。

## Review Focus（M1 的测试必须钉住的隐性失败面）

- **就绪竞态**：Mycat 8066 未监听就跑测试 → 假失败。用端口轮询而非固定 `sleep`（旧 CI 用 `sleep 10s`，不稳）。
- **MariaDB 与 MySQL 环境变量名不同**：`MYSQL_ROOT_PASSWORD` vs `MARIADB_ROOT_PASSWORD` → 只设一个会导致 MariaDB 容器 root 密码没生效、全 MariaDB 行假红。两行都设。
- **matrix 镜像 tag 不存在**（如 mysql:9.0）→ 整条 job 崩而非逐版本报告。镜像 tag 先核对；不存在的版本单列、允许红但不出报告才算网失败。
- **合法的不支持 ≠ 网失败**：M1 目标是"能产出逐版本 pass/fail"，不是"全绿"。兼容未做导致某些版本红属正常 → matrix 用 `continue-on-error` 上报但不门阻塞 PR；待 M2/M3 逐档转绿后再撤。
- **网本身静默失效**：空 `steps:` 的 workflow 永远"绿"却不测任何东西（现状 dev/native 即如此）→ 要么补全要么删除，禁止留空壳假绿。

---

### Task 1: 修复主 CI 到 JDK 21 且单版本（MySQL 8.0）跑通（I-CI-1）

**Files:**
- Modify: `.github/workflows/pr-validation-main-test.yml`（整文件重写）
- Create: `ci/run-example.sh`（本地与 CI 共用的"起 Mycat→等就绪→跑 example 测试"脚本）
- Delete-or-Fill: `.github/workflows/pr-validation-dev-test.yml`、`.github/workflows/pr-validation-main-native-test.yml`（当前 `steps:` 为空壳）

**Interfaces:**
- Consumes: 无
- Produces: `ci/run-example.sh`（被 Task 2 的 matrix job 复用；入参：无，读环境变量 `DB_MYSQL_URL/DB1/DB2`，默认 localhost:3306/3307）

- [ ] **Step 1: 写 `ci/run-example.sh`（就绪轮询版）**

```bash
#!/usr/bin/env bash
set -euo pipefail
JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
java -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar "$JAR" & MYCAT_PID=$!
trap 'kill $MYCAT_PID 2>/dev/null || true' EXIT
# 轮询 8066 就绪（最多 60s），替代旧 CI 的不稳定 sleep
for i in $(seq 1 60); do
  if (exec 3<>/dev/tcp/127.0.0.1/8066) 2>/dev/null; then exec 3<&- 3>&-; break; fi
  sleep 1; [ "$i" = 60 ] && { echo "Mycat 8066 not ready"; exit 1; }
done
mvn -B test -pl example -am
```

- [ ] **Step 2: 本地跑一次构建，确认 jar 产物名对得上**

Run: `mvn -B package -DskipTests -pl server -am`
Expected: 生成 `server/target/server-1.22-release-jar-with-dependencies.jar`（若无本地 3306 后端，Step 3 前先在 Docker 起 mysql:8.0 两实例；本步骤只验构建产物存在）

- [ ] **Step 3: 验证脚本在本地 Docker 后端下能跑通（单版本绿）**

先起两实例：`docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=123456 mysql:8.0`、`docker run -d -p 3307:3306 -e MYSQL_ROOT_PASSWORD=123456 mysql:8.0`
Run: `bash ci/run-example.sh`
Expected: `mvn test -pl example -am` BUILD SUCCESS（这是 M1 网能抓真绿的第一个证据点）

- [ ] **Step 4: 重写 `pr-validation-main-test.yml` 到 JDK 21 + services**

关键点写进 workflow：`actions/setup-java@v4`（temurin 21）；`services:` 两个 mysql:8.0（ports 3306:3306、3307:3306，env 设 `MYSQL_ROOT_PASSWORD: 123456` + `MARIADB_ROOT_PASSWORD: 123456`）；`steps` 走 `mvn package -DskipTests -pl server -am` → `bash ci/run-example.sh`。删除原 `java-version: 1.8` 与 `mycat2/target/...` 路径。

- [ ] **Step 5: 处置两个空壳 workflow**

`pr-validation-dev-test.yml`、`pr-validation-main-native-test.yml`：`steps:` 为空 → 各自 `git rm`（native 变体若暂不需要），避免"永远绿的假网"。

- [ ] **Step 6: 触发一次 PR/push 验证 CI 真跑且绿**

Run: `gh workflow run "Java CI - Mycat2 Main"` 或推分支开 PR
Expected: Actions 该 job 全步骤执行、`mvn test` 绿、无 "steps 为空" 告警

- [ ] **Step 7: 提交**

```bash
git add .github/workflows ci/run-example.sh
git commit -m "ci(M1/I-CI-1): 修 JDK21 + server jar 路径 + example 就绪轮询；删空壳 workflow"
```

---

### Task 2: 参数化为 MySQL/MariaDB 版本矩阵（I-CI-2）

**Files:**
- Modify: `.github/workflows/pr-validation-main-test.yml`（加 `strategy.matrix`）

> 注：矩阵所有版本的服务端口固定映射为 `3306/3307`（与 `prototypeDs.datasource.json`、`MycatTest` 默认值一致），因此 `ci/run-example.sh` **无需改动**——换版本只换容器镜像、不动 Mycat 配置。这是本设计"配置零改"的关键，故 Task 2 不引入对脚本的无谓修改。

**Interfaces:**
- Consumes: Task 1 的 `ci/run-example.sh`（原样复用）、workflow 结构
- Produces: 逐版本 pass/fail 的 matrix 报告（Task 3 在其上挂 harness）

- [ ] **Step 1: 加 matrix**

```yaml
strategy:
  fail-fast: false
  matrix:
    db: [mysql:5.7, mysql:8.0, mysql:8.4, mysql:9.0,
         mariadb:10.6, mariadb:10.11, mariadb:11.4]
    include:
      - db: mysql:9.0
        continue-on-error: true   # 尚未支持的版本：红但不阻塞（M2/M3 撤）
services:
  mysql-a: { image: ${{ matrix.db }}, ports: ["3306:3306"], env: {MYSQL_ROOT_PASSWORD: 123456, MARIADB_ROOT_PASSWORD: 123456} }
  mysql-b: { image: ${{ matrix.db }}, ports: ["3307:3306"], env: {MYSQL_ROOT_PASSWORD: 123456, MARIADB_ROOT_PASSWORD: 123456} }
```

- [ ] **Step 2: 核对每个 tag 在 Docker Hub 真实存在**

Run: 逐个 `docker manifest inspect mysql:8.4` 等
Expected: 全部可解析；不存在的（如某些 9.x 小版本）先从矩阵移除，矩阵保持"tag 有效"这一网自身正确性前提

- [ ] **Step 3: 触发 matrix，产出逐版本结果表**

Run: `gh workflow run` → Actions
Expected: 7 个 db 各一条 job，产出 pass/fail（此时非全绿属正常）；截图/URL 作为 I-CI-2 Done 证据

- [ ] **Step 4: 提交**

```bash
git add .github/workflows/pr-validation-main-test.yml
git commit -m "ci(M1/I-CI-2): MySQL 5.7-9.0 + MariaDB 10.6-11.4 版本矩阵，逐版本 pass/fail"
```

---

### Task 3: 兼容性冒烟 harness 接入矩阵（I-CI-3）

**Files:**
- Modify: `ci/run-example.sh`（跑完 example 后追加一段"连接+认证+基础 CRUD"冒烟）
- Create: `example/src/test/java/io/mycat/compat/ConnectionSmokeTest.java`

**Interfaces:**
- Consumes: Task 2 的 matrix 服务与端口注入
- Produces: 可判"该版本 L1 连接是否达标"的最小自动化断言（M2 的度量基线）

- [ ] **Step 1: 写失败冒烟测试 `ConnectionSmokeTest`**

```java
package io.mycat.compat;
// 用 MycatTest 的 DB_MYCAT/DB1/DB2；断言：连上 Mycat(8066)、SHOW DATABASES 非空、
// 建表-插-查-删 往返成功。版本从 System.getProperty("compat.db") 记录进报告行。
class ConnectionSmokeTest { /* @Test: connect → create table → insert → select → drop */ }
```

- [ ] **Step 2: 本地对着一个 MariaDB 后端跑，确认能抓到真实红/绿**

Run: 起 mariadb:11.4 两实例 → `mvn -B test -pl example -am -Dtest=ConnectionSmokeTest`
Expected: 依当前后端兼容现状，MariaDB 可能红（暴露真实缺口）——这正是网的目的，属预期产出，非网 bug

- [ ] **Step 3: 把冒烟并入 run-example.sh 的测试集，触发 matrix**

Run: `bash ci/run-example.sh`（含 ConnectionSmokeTest）
Expected: 矩阵每个版本对 Mycat 的 L1 连接冒烟产出明确 pass/fail 行

- [ ] **Step 4: 提交 + 回写看板**

```bash
git add ci/run-example.sh example/src/test/java/io/mycat/compat/ConnectionSmokeTest.java
git commit -m "ci(M1/I-CI-3): 连接/认证/CRUD 冒烟 harness，L1 度量基线"
```
回写 `2026-09-30-mysql-mariadb-compat-PROGRESS.md`：`I-CI-1/2/3` → 证据（Actions run URL）+ 各版本 pass/fail 快照；更新"当前快照"下一步为 M2。

---

## M1 完成定义（DoD）

- 一条 PR 上 matrix 触发，7 个 db 版本各产出 pass/fail 报告（`I-CI-1/2/3` Evidenced-Done，附 Actions run URL）。
- 无空壳 workflow；就绪用轮询。
- PROGRESS 看板"当前快照"下一步指向 M2，版本矩阵表首列"连接认证"按实测打勾。
- **不要求全绿**：M1 只交付"网存在且逐版本可证"，绿的比例是 M2/M3 的事。
