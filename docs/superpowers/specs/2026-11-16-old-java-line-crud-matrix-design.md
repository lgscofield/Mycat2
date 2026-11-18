# 旧 Java Connector 线 full-CRUD 矩阵专轮 · 设计（spec）

> 状态：**取证完成，待用户拍板**。本文件仅立设计意图与决定点，未写实现码、未落 plan。
> 触发：M2（连接达标）"全 connector 跑基本 CRUD" 子门的**唯一真残格**（I-DEBT-9 旧 Java 线 full-CRUD 矩阵，看板 2026-11-13 据实纠正钉死）。

## 1. 招牌定位与里程碑口径

- 目标：在客户端眼中无缝替代 MySQL 5.7/8.0/8.4/9.x + 预留 MariaDB 扩展位。
- spec §3 尺子：某版本"被支持" = **M2 连接达标**（native+caching_sha2、TLS on/off、**全 connector 跑基本 CRUD**）→ M3 mysqldump → M4 迁移。关键路径 M1→M2→M3。
- M2 现状（对账代码+看板，非转述）：
  - I-AU-1 caching_sha2 前端原生协商 **已 DONE**（`server/src/main/java/io/mycat/vertx/VertxMySQLAuthHandler.java` + `CachingSha2TlsFullAuthRoutingTest`，live `FrontAuthMatrixTest` sha2 格 8.0/8.4/9.0 PASS；TLS-full-auth 残留 I-DEBT-51 2026-10-16 闭环）。
  - I-AU-2 前端 TLS **已 DONE**（Connector/J × 7 persona on/off `TlsAxisMatrixTest` 14/14）。
  - I-VE-1 版本 persona 连接级 **已 DONE**（`8ca4690e3` 等）。
  - I-DEBT-9 connector 轴：polyglot 基本 CRUD/TLS×polyglot/字符集保真/CA-Identity 链/旧线认证+STAGE5 类型深面/旧线 TLS 轴 **均已点亮**。
  - **唯一真残格 = 旧 Java 线 full-CRUD 矩阵**（本专轮靶）。

## 2. 取证结论：缺口精确定位

### 2.1 现代 connector 的 CRUD 度量网（模板）
`example/src/test/java/io/mycat/compat/CrudResultMatrixTest.java` —— 12 格，跑**主 reactor 的现代 Connector/J 9.x**，persona 参数化：

| 格 | 能力 | 老线探针是否覆盖 |
|----|------|------------------|
| C1 | int+varchar 单行往返 | ✅ STAGE4 |
| **C2** | **UPDATE + DELETE 语义**（改得动/删得掉/删后计数归零） | ❌ 缺 |
| C3 | decimal(20,4) 标度保真 | ◐ STAGE4(12.34)/STAGE5(20,10) INSERT+SELECT，非 UPDATE |
| C4 | DATE / DATETIME(6) | ◐ STAGE5 仅 SELECT 回读 |
| **C5** | **VARBINARY 逐字节保真（`?` 参数化写入）** | ❌ 缺 |
| **C6** | **BIT(1)/BIT(8)/BIT(16)/NULL wire 口径（单片路径）** | ◐ STAGE5 仅 BIT(8)/BIT(1) SELECT |
| **C7** | **跨 2 分库聚合 count/sum/order** | ❌ 缺 |
| **C8** | **跨分片 LIMIT/OFFSET 分页切片** | ❌ 缺 |
| **C9** | **TIMESTAMPDIFF 下推（6 单位 + NULL 传播）** | ❌ 缺 |
| **C10** | **除零→单行 NULL 值语义 + 会话存活传输层契约** | ❌ 缺 |
| **C11** | **跨分片 AVG 逐值 == 真 MySQL oracle** | ❌ 缺 |
| **C12** | **跨分片 STDDEV/VARIANCE 总体口径对拍（浮点容差）** | ❌ 缺 |

### 2.2 老线现有仪器
- `ci/OldConnectorProbe.java`：STAGE1 auth、STAGE2 select1、STAGE3 version、STAGE4 基本 CRUD（INT/VARCHAR/DECIMAL，**INSERT+SELECT only**）、STAGE5 逐类型深面（BIT/DATE/DATETIME/TEXT/DECIMAL/NULL，**INSERT+SELECT only**）。
- `ci/verify-old-connector.sh`：起 Mycat → 逐 coord（缺省 `mysql:mysql-connector-java:5.1.49` + `com.mysql:mysql-connector-j:8.0.33`）`java -cp <单 jar> 源码模式` 跑探针，**明文轴 + TLS 轴各一轮**，端口/影子断裂守卫，全绿退出 0。CI 单 job `old-connector`（`pr-validation-main-test.yml`）真跑本脚本。

### 2.3 缺口定性
老线**缺 CRUD 的 U/D（C2）、跨分片归并聚合（C7/C8/C11/C12）、函数下推（C9）、除零契约（C10）、字节/BIT16（C5/C6）、参数化/prepared 路径**。这正是"旧客户端拿到什么结果集表征"的真实兼容面——深债红族（I-DEBT-8/34/35/36/39/27）的暴露处，只是当前仅在现代 connector 上钉了网。

## 3. 拟设计（待拍板后落 plan）

**核心裁定：以 `OldConnectorProbe.java` 增量新增 STAGE 段承载 CRUD 矩阵，复用现有隔离/CI 接线，不进 reactor、不新建 harness 模块。**

理由：隔离约束（多 connector 版本不可同 classpath，类遮蔽）已由"源码模式单 jar"解决；新增 reactor 测试无法用 5.1.x 旧类名。老线矩阵天然属同一探针。

候选新段（命名 `STAGE6 crudmatrix`，逐格 `STAGE cm.<cell> OK/FAIL detail`，纳入既有 verdict）：
- C2 UPDATE/DELETE；C5 VARBINARY（`setBytes`/`?`）；C6 BIT 单片口径；C7/C8 跨分片聚合/分页；C9 TIMESTAMPDIFF；C10 除零+会话存活；C11/C12 跨分片 AVG/STDDEV 对拍。

## 4. 决定点（已拍板 2026-11-16）

1. **格子量级 = 全 11 格复刻**（用户拍板）：C2/C5/C6/C7/C8/C9/C10/C11/C12 全面对齐 `CrudResultMatrixTest` 口径，真正兑现 M2"全 connector 跑基本 CRUD"。代价=×2 connector×2(tls/plain)，接受。
2. **oracle 策略 = 老线用硬编码 ground truth**（用户拍板）：现代网已背书 Mycat↔真库一致，老线格只断"旧客户端拿到与网基准一致的归并值"，探针内不直连 3306、零耦合。
3. **prepared 路径 = 折进 C5 参数化写入**（Ruling：忠实复刻现代网，现代网 C5 即以 `insert … values (1, ?)` 覆盖 prepared-write；不另造 prepared 格，守 YAGNI）— 若拍板后 plan 阶段发现 5.1.x prep 有独立表征差异再据实加格。
4. **persona 轴 = 单 persona MYSQL_8_0**（用户拍板）：老线聚焦"旧客户端兼容"轴，归并/函数语义已由现代 persona 矩阵背书；run 时长可控。
5. **跨片 BIT 边界 = 继承现代网**（Ruling，非争议）：C6 只钉单片 BIT；跨片归并 BIT 列头仍归 `UserCaseTest#caseCrossShardBitColumnFidelity`/"类型表征统一"专轮，本票不重开。

**固化设计核心**：在 `ci/OldConnectorProbe.java` 增量新增 `STAGE6 crudmatrix`（逐格 `STAGE cm.<cell> OK/FAIL detail`，纳入既有 verdict），复用 `verify-old-connector.sh` 的 coord×{plain,TLS} 隔离接线与 CI `old-connector` job；不进 reactor、不新建 harness 模块、不改产品码。

## 5. 边界与非目标

- 不改产品码（纯测试仪器 B 层）；若矩阵揭出真破面，另立产品票走专轮，本票只点网+如实挂账（宁挂账不伪绿）。
- 不重开跨片 BIT/类型表征统一（决定点 5）。
- jar 前置：探针用现成 fat jar（`server/target/server-1.22-release-jar-with-dependencies.jar`），本票若不动产品码则无需重打 jar。
- 离线约束：本地 mvn -o；旧 jar 已在 `D:\Nexus\repository`（5.1.20–5.1.49/8.0.15–8.0.33）。

## 6. 验证链（拍板后）

RED：新段对当前 jar 若命中真破面应红（否则说明格无判别力，参照 C9 跨月跨年选数教训）。GREEN：判别力负控（老线明文 vs DISABLED 负控已内建）。全绿后 push → CI `old-connector` job 复现 + 回写 I-DEBT-9 销账 → M2 闭环。
