# I-DEBT-45 列头 raw 透传保真 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 后端忠实轨（FaithfulTypeView 守卫通过的按位对齐列）的前端列头改为 raw column-def 包透传，消灭 `MySQLFieldsType.fromJdbcType` 的有损折叠（VARBINARY(20)→LONGBLOB(255)）。

**Architecture:** 方案B主干+方案C兜底（2026-10-14 已批技术路线，零 calcite-core 改动）：`MycatRowMetaData` 增 default 钩子 `rawColumnDefPacket(int)`（null=无真相源，回重建）；`MycatMySQLRowMetaData` 返回原始包；`RowMetaDataFidelity.FaithfulTypeView` 仅在列名对齐守卫通过时透传后端包；`MySQLPacketUtil` 发射缝逐列优先 raw 包、否则原重建。异构多源（`isBinaryFamilyAuthority` 拒绝轨）行为不变。

**Tech Stack:** Java 21, Maven（common/server/hbt）, JUnit5, docker mysql:5.7 真后端。

**Spec:** 债务本体=DEBT-QUEUE 校准结论 item3 + `.superpowers/sdd/2026-10-14-cross-shard-column-type-authority/progress.md` Task 6 Ruling（jshell 实证：后端三形态全 VARBINARY(20)，客户端见 LONGBLOB(255)）。

## Global Constraints

- 严禁修改 fork 的 calcite-core 内部枚举（既定禁忌）。
- 现有 `instanceof MycatMySQLRowMetaData` raw 分支行为零变化。
- 异名列/多源异构一律维持重建轨（I-DEBT-42 守卫同源）。
- 构建 JDK21：`$env:JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"`；PS 下 `-D` 参数加引号。
- 每 Task commit，默认不 push。

## Review Focus

1. raw 包携带后端物理表名/org 名——直通/单源时与直连一致（目标行为）；多源异构不得进透传分支。
2. 尾部下推排序键（backend 列数>plan）：透传循环以 plan 列数为准，多余尾列忽略（与现有 effective 一致）。
3. binary 协议（预编译）轨共用 `generateAllColumnDefPayload`，透传须同样生效。
4. 名对齐守卫的大小写：equalsIgnoreCase 通过但后端原始包保留后端大小写——与直连行为一致。

---

### Task 1: common 发射缝逐列 raw 钩子（TDD）

**Files:**
- Modify: `common/src/main/java/io/mycat/beans/mycat/MycatRowMetaData.java`（+default 钩子）
- Modify: `common/src/main/java/io/mycat/beans/mycat/MycatMySQLRowMetaData.java`（覆写钩子）
- Modify: `common/src/main/java/io/mycat/beans/mycat/RowMetaDataFidelity.java`（FaithfulTypeView 透传钩子，守卫同款）
- Modify: `common/src/main/java/io/mycat/MySQLPacketUtil.java`（`generateAllColumnDefPayload`/`generateColumnDefPayload` 逐列优先 raw）
- Test: `common/src/test/java/io/mycat/ColumnDefRawPassthroughTest.java`

**Interfaces:**
- Produces: `MycatRowMetaData.rawColumnDefPacket(int column) -> ColumnDefPacket|null`（default null）
- Consumes: `RowMetaDataFidelity.effective(backend, plan)`（既有）

- [ ] **Step 1: 写失败测试**（3 例）
  - `alignedColumnEmitsBackendRawPacket`：后端包 {name=v, type=VAR_STRING 形态使 getColumnType==Types.VARBINARY, length=20, charset=63}；计划 meta 同名列但 type=LONGVARBINARY；`effective()` 包装后 `generateAllColumnDefPayload` 生成字节流中该列 field_type 字节==后端包字节（非 251/LONG_BLOB、length==20）。先断言现状复现失真（RED：输出 251/255）。
  - `misalignedColumnFallsBackToRebuild`：异名守卫 → 维持重建（现状字节）。
  - `plainBackendMetaUnchanged`：裸 MycatMySQLRowMetaData 分支输出与改前逐字节一致。
- [ ] **Step 2: 跑测试确认 RED**（`mvn -B -q test -pl common "-Dtest=ColumnDefRawPassthroughTest"`）
- [ ] **Step 3: 实现钩子+发射缝改优先级**（签名如上 Architecture）
- [ ] **Step 4: 跑测试 GREEN + common 全量**（`mvn -B -q test -pl common`）
- [ ] **Step 5: hbt+server 全量回归**（`mvn -B -q test -pl hbt,server`）
- [ ] **Step 6: Commit**（feat(common): I-DEBT-45 列头 raw 透传钩子…）

### Task 2: 真机 5.7 后端金标准 + 驾驶舱回写

- [ ] **Step 1:** mysql:5.7 容器起新 ds（3308），建 varbinary(20)/varchar/bit 表种数据
- [ ] **Step 2:** 打含修复 fat-jar 起 Mycat，`mysql --column-type-info` 双路对拍（直连 5.7 vs 经 Mycat，单片直通+跨片/合并形态），Type/Length 逐列一致（RED 基线：经 Mycat 见 BLOB/LONGBLOB 255）
- [ ] **Step 3:** DEBT-QUEUE 回写（I-DEBT-45 行/评级 5.7 项/校准结论 item3）+ commit
