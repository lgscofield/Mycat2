# I-DEBT-77 跨片 length(binary) 被下推成 length(CAST(.. AS CHAR))→NULL——取证与修法（Spec 先行）

状态：**✅ 全绿收口（候选 A 已落·离线双锁 GREEN + 8066 活体 shape3 转绿 + 全量护栏 549/0F/0E/37skip BUILD SUCCESS）**（2026-11-02·承 I-DEBT-69 收口护栏拓出）
分类：C（引擎/codegen·跨片下推 deparse·系 I-DEBT-76 反向回归）
对齐北极星：M2 无缝 SELECT + §7 真库行为一致性（**值保真**：binary 列经字节族函数消费须按字节数返回，不得因隐式 charset coercion 降级为 NULL）

## 1. 症状（活体铁证：I-DEBT-69 收口全量护栏 run3 拓出·新 jar 8066·persona=MARIADB_10_11·后端 MySQL 8.0）
- `BinaryRepresentationFidelityDifferentialTest#shape3_localComputedOverBinary`（L208-213）：
  跨片全扫描 `select length(b), length(vb), length(blb) from tb2 order by 1`（`tb2` = `dbpartition by mod_hash(id) dbpartitions 2`）。
- 期望侧（直连真 MySQL 8.0，非分片等价表）首列 = `Long:4`（BINARY(4) 字节数）；实际侧（经 Mycat）首列 = **`null`**（vb/blb 列同族受影响）。
- 定性：**非 I-DEBT-69（告警接线）本刀引入**，系 I-DEBT-76 收口后新 jar 首次让活体 e2e 跑到 CAST 保留下推才暴露（I-DEBT-76 收口时护栏跑在陈旧 jar、活体面不可取证，故此前未咬）。

## 2. 根因链（当前代码 file:line 坐实）
1. **隐式 coercion 注入**：`LengthFunction.length(String)`（`hbt/.../calcite/sqlfunction/stringfunction/LengthFunction.java:36`）声明 CHARACTER 族实参。对 BINARY 列 `length(b)`，`super.checkOperandTypes` 因族不匹配失败 → `MycatSqlDefinedFunction.mycatCoerceOperands`（`calcite-core/.../org/apache/calcite/mycat/MycatSqlDefinedFunction.java:163-197`，I-DEBT-23 宽松 coercion）在 L191 就地包 `CAST(b AS <toSql(String)>)`，目标 VARCHAR 带默认 utf8 charset。rel 树 = `length(CAST($1):VARCHAR CHARACTER SET "UTF-8")`。
2. **deparse 保留该 CAST 下推**：I-DEBT-76 删除 `hbt/.../io/mycat/calcite/SqlImplementor.java` CAST 分支 (b)(c)(d) 三跨族早退（现 L850-864）后，跨族列引用 CAST 落到 L864 `dialect.getCastSpec(call.getType())` 保留进 fragment。`MycatSqlDialect.getCastSpec`（L48-63）仅整数族→SIGNED，CHARACTER 目标走 `super` → 渲成 `CAST(b AS CHAR)`。
3. **后端 NULL 语义**：跨片 fragment 出码 `length(CAST(b AS CHAR))`，MySQL `LENGTH()` 对 `CAST(含 0x00 的 binary AS CHAR CHARACTER SET utf8)`（0x00 截断 / 0xFF·0xAA 非法 utf8）→ **NULL**，背离直连裸 `length(b)` 的字节数 4。
4. **单片不受影响**：单目标走原文本直通、交后端按 `length(b)` 原生求值（BINARY 列 LENGTH 返字节数，正确）。与 I-DEBT-75/76「单片原文下推、跨片 rel→sql 重渲染」同源。

## 3. 难点：隐式 coercion CAST 与用户显式 CAST 在 Rex 层不可区分
`mycatCoerceOperands` 注入的 `CAST` 与用户手写的 `CAST(b AS CHAR)` 经 `SqlStdOperatorTable.CAST.createCall` 产出，validate→SqlToRel→`RexCall(CAST)` 后**同形**，deparse 侧无「此 CAST 系 coercion 插入」标记。故修法只能在**类型族方向**上做手术，不能靠结构区分。

## 4. 修法候选与裁定
### 候选 A（推荐·Narrow 定向 deparse 剥离）
在 `SqlImplementor` CAST 分支（现 L850-864 同族丢弃之后、`getCastSpec` 之前）加一条**只针对「源族=BINARY、目标族=CHARACTER」方向**的剥离：即撤销这枚「字节→字符串」的有损 coercion，`return nodeList.get(0)` 下推裸 binary，交后端按字节算 LENGTH。
- **不回退 I-DEBT-76**：76 要保的 `cast(varchar AS signed)` 方向是「源=CHARACTER、目标=INTEGER」，与本剥离方向（源=BINARY、目标=CHARACTER）**天然不相交**，靠目标/源类型族即可分派，非靠结构。
- 效果：跨片与单片统一（都按 BINARY 原生字节语义），值保真回归真库。
- blast：仅 deparse 出口、且限一个族方向；同族丢弃、整数族 SIGNED 渲染均不动。

### 候选 B（次选·免 LENGTH coercion）
让 `length` 的 operand 校验直接接受 BINARY 族（不注入 CAST）。代价：改 `MycatSqlDefinedFunction` 类型校验/推断泛化到全 Mycat 字符串函数的 CHARACTER↔BINARY 兼容，blast 面远超本症所需；且本地 codegen `length(String)` 需补 BINARY 重载。否决。

### 候选 C（不可行）
`getCastSpec` 字节族专项渲染。getCastSpec 只决定 CAST 目标型名（渲成什么），不决定该 CAST 是否下推；本症要的是「别把这枚 coercion 下推」，C 无法表达。否决。

## 5. 残留边界（诚实挂账·不伪绿）
- 候选 A 会**连带**剥离用户对 BINARY 列显式写的、且恰好落在跨片计算投影 deparse 腿上的 `CAST(b AS CHAR)`（如跨片全扫描 `select cast(b as char) from tb2`）→ 该形态交后端按裸 binary 返回字节，而非 utf8 字符串转换结果。判定：单片显式 cast 走原文直通不受影响；跨片显式 `CAST(binary AS CHAR)` 罕见、且裸 binary 交后端更接近 MySQL「binary 即字节串」心智（LENGTH/CONCAT/SUBSTRING 对 binary 均按字节），当前无活体测覆盖此形。**登记为残留边界**，如后续活体拓出显式跨片 binary→char cast 保真需求再专票收紧（需引入 coercion 标记方可精确区分，属更大改动）。
- shape3 的 vb(varbinary)/blb(blob) 列：同族 coercion（VARBINARY/BLOB→VARCHAR）方向一致，候选 A 一并覆盖（源族=BINARY 含 VARBINARY；BLOB 目标族需实测确认，GREEN 活体逐列核对）。

## 6. 计划（RED→GREEN→活体→护栏）
- **RED**：离线落码锁 `internalexample/.../drdsrunner/CrossShardBinaryLengthPushdownFidelityTest.java`（mode=local，镜像 `CrossShardCastPushdownFidelityTest` 姿势，binary(4) 分片表 `shgb`），断言 `select id, length(b) from shgb order by id` 下推 fragment **不含 `CAST(`**（当前含 → RED）。
  - ✅ **RED 实串（离线新鲜取证·`scratch/i77-pushdown.txt`）**：rel 树 `length(CAST($1):VARCHAR CHARACTER SET "UTF-8")`；每片 fragment `SELECT \`shgb\`.\`id\`, length(CAST(\`shgb\`.\`b\` AS CHAR)) AS \`len\` FROM db1_0.shgb_0 …` → 断言红（Tests run: 1, Failures: 1）。
- **GREEN**：候选 A 落 `SqlImplementor.java:852` 之后（同族丢弃之后、整数族注释之前）加一条 `srcFamily==BINARY && dstFamily==CHARACTER → return nodeList.get(0)` + MYCAT CHANGE 注释钉档。
  - ✅ **GREEN 实串**：修复后同锁 fragment 出码 `SELECT \`shgb\`.\`id\`, length(\`shgb\`.\`b\`) AS \`len\` FROM db1_0.shgb_0 …`（coercion CAST 已剥离、裸 binary 下推）。
  - ✅ **不回退 I-DEBT-76**：`-Dtest=CrossShardBinaryLengthPushdownFidelityTest,CrossShardCastPushdownFidelityTest` 同跑 `MVN_EXIT=0`（77 length 锁转绿 + 76 cast 锁仍绿，二者族方向天然不相交得实证）。
- **活体对拍**：重打 fat jar（15:21·`server -am package -DskipTests` BUILD SUCCESS）+ 重启 8066（pid 14912·persona=MARIADB_10_11），跑 `BinaryRepresentationFidelityDifferentialTest` → **4/0/0**，shape3 首列 `null`→**`Long:4`**、与直连 MySQL 8.0 oracle 多重集逐格全等。
- **全量护栏**：`mvn -o -pl example -am test`（cmd 原生重定向姿势·不带 compat.db·独立日志 `i77-guardrail.log`）→ **549/0F/0E/37skip BUILD SUCCESS**（shape3 那枚 1F 转绿·`CrossShardWarningRelayTest` 2/0/0 仍绿·`MetadataGuiMatrixTest` 6 skip NO_DATA·相对 I-DEBT-69 基线 549/1F→**549/0F 零回归**）。
- **账本对齐**：spec + DEBT-QUEUE（I-DEBT-77 转正 / I-DEBT-76 交叉）+ PROGRESS/progress + commit（不 push）。

## 7. 北极星对齐
M2 无缝 SELECT 的**值保真**子项：跨片计算投影不得因隐式 charset coercion 把 BINARY 列的字节语义降级为 NULL。本刀与 I-DEBT-76 一体两面——76 让承重 CAST 下推保 varchar→signed 值保真，77 防同一改动把 coercion CAST 也下推而毁 binary 字节保真，靠**类型族方向**精确分际，不留伪绿。
