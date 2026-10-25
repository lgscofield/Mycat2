# I-DEBT-66 窄修复设计提案（CAST(x AS DECIMAL(p,s)) 超采样值舍入 · 值腿）

> 状态：**🟡 Fix-Meta 已落地（TDD RED→GREEN、hbt 242/242、CrudResultMatrix 12/12活库零回归），B1+R1 待另起一轮（2026-10-25）**。根因不在「架构级」规划器/归并规则，而在 `CalciteConvertors.getRelDataType()` (`hbt/.../util/CalciteConvertors.java:369`) 对 DECIMAL 列不传 precision/scale → Calcite 默认 `DECIMAL(19,0)` → `deriveAvgAggType` 得错误 `DECIMAL(19,4)` → `reduceAvg` 的 CAST 降 scale。Fix-Meta 为 B1 安全落地的必要前置（解除归并腿降 scale CAST）；详见 §2.8 定谳与 §2.9 落地实证。
> 前置：I-DEBT-64（arg-swap，目标类型 precision/scale 表征已修正）、I-DEBT-65（编码层「仅补零·绝不降 scale」已 Evidenced-Done）。本债是与「补零」正交的「舍入」残腿。

---

## 1. 现象（活库铁证，2026-10-24 · DEBT-QUEUE §2 I-DEBT-66 行）

Mycat（本地求值路径）对**超采样**（值 scale > 目标 scale）的 DECIMAL CAST 不按目标 scale 舍入：

| SQL | Mycat 实得 | 真 MariaDB:10.11 | 判定 |
|---|---|---|---|
| `cast(1.2345 as decimal(10,2))` | `1.2345`（值 scale=4、元数据 scale=2） | `1.23` | ✗ 未 round |
| `cast(1.2355 as decimal(10,2))` | `1.2355` | `1.24` | ✗ 未 round |

> 数值补零面（`cast(10 as decimal(10,2))`→`10.00`）已由 I-DEBT-65 编码层收口；本债专攻**降 scale 有损舍入**——不能在编码层强裁（会复现 avg 腐蚀，见 I-DEBT-65 教训），必须在**值生成腿**把 CAST 结果 scale 天然做成目标 s。

---

## 2. 根因（运行时定谳 · 凭实跑证据独立复核，含对本 Spec 前版的推翻）

> 本节为 §5 步骤1「塌型点定谳」的**结论**。**订正声明**：本 Spec 初版（§2.2/§2.3）曾把根因推断为「RexSimplify 剥 CAST + 未注册 CALC_REDUCE_DECIMALS」，并一度追向「RelNodeConvertor HBT 往返丢 precision/scale」——二者**均被本轮运行时探针证伪**（详见 §2.4）。以下为凭实跑证据的最终定性。

### 2.1 铁证一：CAST 未下推后端，是 Mycat **本地**求值

临时探针 dump `select cast(id as decimal(10,2)) from db1.travelrecord` 的 dispatch 物理树，得后端 SQL：

```sql
SELECT `travelrecord`.`id` AS `CAST(idASdecimal(10,2))` FROM db1.travelrecord AS `travelrecord`
```

后端**只取原始 `id`（BIGINT）**，`CAST(idASdecimal(10,2))` 只是内部列名字符串、**不含 CAST 表达式**。即舍入本应由 Mycat 本地完成。物理树里 MycatView.relNode = `LocalProject`，其投影 RexNode 的**目标类型完整存活**：

```
PROJ[0] kind=CAST  target=DECIMAL[p=10,s=2] :: CAST($0):DECIMAL(10, 2) NOT NULL
INPUT: id = BIGINT[p=19,s=0]
```

→ **证伪「RexSimplify/normalize 剥 CAST」**：`RexSimplify.simplifyCast`（`RexSimplify.java:2476-2477`）明确「DECIMAL casts are never no-ops」，列引用场景原样返回；物理 RelNode 到 codegen 边界 CAST 完好。

### 2.2 铁证二（真根因）：Mycat 宽松 CAST seam 对 DECIMAL 目标命中不带 scale 的裸 `X_TO_DECIMAL`，短路 fork 舍入分支

本地求值走 `RexToLixTranslator.translateCast`（`RexToLixTranslator.java:307-324`），第一步经中立 seam `MycatCalciteRuntime.get().translateCastOverride(sourceType, targetType, operand)`（L317-318）；**命中即短路**，不再进 `getConvertExpression` 的 DECIMAL 舍入分支（L576-631，`DECIMAL_DECIMAL_CAST_ROUNDING_MODE`→`Primitive.decimalDecimalCast` 等带 precision/scale/roundingMode）。`MycatCastOverride.translate`（`MycatCastOverride.java:87-92`）对命中项仅 `Expressions.call(method.method, operand)`——**只传 operand，不传 target 的 precision/scale**。

**用真症状（超采样字面量）在生产态出码 harness 直接取 ground truth**（临时探针 dump，跑后即删）：

| 探针查询 | 计划 | 生产出码（本地 current()） | 判定 |
|---|---|---|---|
| `cast(1.2345 as decimal(10,2))` | `MycatProject(CAST(?0):DECIMAL(10, 2))` over `MycatValues` | `SqlFunctions.toBigDecimal((Number)root.get("?0"))` → **`MycatBuiltInMethodImpl.decimalToDecimal(bd)`** | 命中 `DECIMAL_TO_DECIMAL` |
| `cast(1.2355 as decimal(10,2))` | 同上 | 同上 `decimalToDecimal(bd)` | 命中 `DECIMAL_TO_DECIMAL` |
| `cast(10 as decimal(10,2))` | 同上 | `bigintToDecimal(((Number)root.get("?0")).longValue())` | 命中 `BIGINT_TO_DECIMAL` |

命中的方法体（`MycatBuiltInMethodImpl.java`，已核源码）：

```java
public static BigDecimal decimalToDecimal(BigDecimal b) { if (b==null) return null; return b; }   // L295-298 纯 identity，scale 原样 → 1.2345 不舍入
public static BigDecimal bigintToDecimal(Long b)        { if (b==null) return null; return BigDecimal.valueOf(b); } // L290-293 scale=0
```

`decimalToDecimal` **直接原样返回**，目标 scale 从不施加 → `cast(1.2345 as decimal(10,2))` 得 `1.2345`、`cast(1.2355…)` 得 `1.2355`。**症状逐值精确复现。** 随后 `scaleValue`（L322 调用，定义 `RexToLixTranslator.java:1298-1327`）明确**不** scale DECIMAL（L1309 排除 DECIMAL 目标），故整条本地腿无任何一处缩到目标 scale。

> 注：`cast(10 as decimal(10,2))` 整数源出 `BigDecimal.valueOf(10)`（scale 0）值本身正确（补零面由 I-DEBT-65 编码层做 `10.00`），**整数→DECIMAL 不需舍入**，非本债症状——真正的降 scale 有损舍入只在**超采样**源（字面量 `1.2345`、或高精度 DECIMAL 列/表达式）上暴露。

### 2.3 定性（一句话）

> I-DEBT-66 = Mycat 为「宽松（MySQL 语义）CAST」而装的 `translateCastOverride` seam（`MycatCastOverride`），对**目标为 DECIMAL** 的 CAST 命中矩阵项 `DECIMAL_TO_DECIMAL`/`BIGINT_TO_DECIMAL`/…，用**只带 operand、不带 precision/scale/rounding 的裸 `X_TO_DECIMAL`**（尤以 `decimalToDecimal` 纯 identity）短路了 fork 原生的 DECIMAL rounding 出码；且 `scaleValue` 对 DECIMAL 不做二次缩放——两条合起来使目标 scale 舍入彻底缺位。这是**值生成腿**的窄缝缺陷，非编码层、非归约层、非后端 unparse、**非 MycatView 架构级**。

### 2.4 诚实订正（推翻既有记录，含对本会话中段一次「架构级」恐慌的撤回）

依「不信既有记录、凭实跑证据独立复核」原则，逐条记账：

1. **推翻本 Spec 初版 §2.2「RexSimplify 剥 CAST + 未注册 CALC_REDUCE_DECIMALS」**：探针证物理 RelNode 的 CAST 完好（`DECIMAL[p=10,s=2]`），`simplifyCast`（`RexSimplify.java:2476-2477`）亦保护 DECIMAL。归约非塌型点。
2. **推翻会话中段「RelNodeConvertor HBT 往返丢 precision/scale」推断**：`RelNodeConvertor.convertRelNode`（`RelNodeConvertor.java:63-140`）方法体**整段注释、L139 无条件 `throw UnsupportedOperationException`**，唯一调用方 `convertToHBTText`（`MycatCalciteSupport.java:646-656`）catch 后返回「hbt无法生成」——**完全不在执行路径**。
3. **撤回本会话一度提出的「MycatView 下推/本地分解丢 CAST · 架构级」恐慌（前版结论，凭本轮真症状实测证伪）**：该恐慌源自**测错了对象**——用了整数列 `select cast(id as decimal(10,2)) from db1.travelrecord`，其 ground truth 是裸 `SqlFunctions.toBigDecimal(row[0])`（因 MycatView 对分片扫描的行是 **ANY/Object 源**，seam L65-67 透传→`EnumUtils.convert`；而整数→decimal 本就无需舍入、补零由 I-65 兜，结果 `12345.00` 恰为**正确值**，把「正确行为」误读成「CAST 被丢」）。**真症状（超采样字面量 `cast(1.2345…)`）实测走的是 DECIMAL→DECIMAL seam 命中 `decimalToDecimal` identity**——即窄缝，非架构级。§2.2 以真症状为准。
4. **订正前版 §2.2/§2.4 headline 方法名**：本 Spec 前一版把真根因示例写作 `bigintToDecimal`（因用整数错查询取样）；真症状的确切末段是 **`decimalToDecimal`（纯 identity）**，`bigintToDecimal` 只是同一 seam 矩阵的整数旁支。定性（seam 短路 fork rounding）不变，示例钉死为 `decimalToDecimal`。

### 2.5 B1 落地后实测残留：舍入**模式** DOWN ≠ MySQL/MariaDB HALF_UP（超范围岔路，待裁定）

B1 已落（`MycatCastOverride` DECIMAL 目标让路）+ 机制锁 `castDecimalTargetUsesForkRoundingNotBareSeam` 本地绿，离线回归零破（`MysqlTypeSystemAvg`4/`Divide`3/`CalciteValidatorGolden`1/`DruidOutputGolden`3/`DecimalColumnScaleEncode`7/`ScalarStdFunc`5 全 0 fail）。出码现走 `Primitive.decimalDecimalCast(v, 10, 2, RoundingMode.DOWN)`。

**但**：`MycatTypeSystem`（`MycatCalciteSupport.java:663-704`）仅覆盖 `deriveAvgAggType`/`deriveDecimalDivideType`，**未覆盖 `roundingMode()`** → 委托 `RelDataTypeSystem.DEFAULT` = **`RoundingMode.DOWN`（截断）**。而 MySQL/MariaDB `CAST(… AS DECIMAL)` 是 **half-up（四舍五入）**（DEBT-QUEUE I-65 亦证编码层用 `setScale(…, HALF_UP)`）。故 B1 后逐值：

| 查询 | B1 出码(DOWN) | MariaDB:10.11 | 判定 |
|---|---|---|---|
| `cast(1.2345 as decimal(10,2))` | `1.23` | `1.23` | ✓ 对齐 |
| `cast(1.2355 as decimal(10,2))` | `1.23` | `1.24` | ✗ **tie 仍偏** |

→ **B1 消除了「完全不缩 scale」的主缺陷，但 tie 舍入方向仍与真库不符**。全收口需让 DECIMAL 目标舍入用 HALF_UP。两条候选（均超出「单守卫窄缝」前提、须活库逐值 + 全量离线套背书，本会话无活库不能自证绿）：

- **R1（推荐 · 全局对齐 MySQL）**：`MycatTypeSystem` 加 `@Override RoundingMode roundingMode(){ return HALF_UP; }`。爆炸半径=**所有**经 type-system roundingMode 的 DECIMAL cast + `ReduceDecimalsRule` 算术降 scale 路；须跑全量 hbt/golden/结果套 + 活库四档差分防 avg/divide 回归。
- **R2（窄 · 回退 B1 改走带参）**：撤销让路，转 §3-B2——`MycatCastOverride` 对 DECIMAL 目标产**带 p/s/`HALF_UP` 的 `setScale` 调用**（新增 `*_TO_DECIMAL` 带参重载），把 rounding 模式钉在 cast 腿局部、不动全局 type system。爆炸半径小，但重引 Mycat 自管矩阵、偏离 fork 单一版本方向。

**守宁挂账不伪绿**：I-DEBT-66 不因 B1 就标 Evidenced-Done；tie 残面须 R1/R2 择一 + 活库差分定标，据实挂账待用户裁定路线。

### 2.6 【决定性】R1 落地 + 活库逐值差分：cast 全绿、但 C11 avg 回归红 → 回退

用户选 **R1**（`MycatTypeSystem.roundingMode()`→`HALF_UP`），与 B1 一并落地，重 build `server-1.22-release-jar-with-dependencies.jar`、重启 8066 Mycat，对 **MariaDB:10.11 后端（`m1-mysql-a`）活库逐值差分**（读前端返回文本）：

| 查询 | Mycat(B1+R1) | MariaDB:10.11 | 判定 |
|---|---|---|---|
| `cast(1.2345 as decimal(10,2))` | 1.23 | 1.23 | ✅ |
| `cast(1.2355 as decimal(10,2))` | **1.24** | 1.24 | ✅ HALF_UP 生效 |
| `cast(-1.2355 as decimal(10,2))` | -1.24 | -1.24 | ✅ 远离零 |
| `cast(10 as decimal(10,2))` | 10.00 | 10.00 | ✅ 补零不回退 |
| `cast(1.2345 as decimal(10,4))` | 1.2345 | 1.2345 | ✅ 不缩 |
| 除法/round/乘（`10/3`、`2/3`、`cast(1 as decimal(10,4))/3`…） | 逐值 | 逐值 | ✅ 全等 |

**cast 轴完全达标。但 `CrudResultMatrixTest` 12 格中 `crossShardAvgFidelity`（C11）回归红**（HEAD 基线本为绿，实测复验 `1/0/0`）：

```
C11：跨分片 AVG(decimal(20,4))  实得 mycat=3.6667  oracle=3.66666667  expected 0 but was 1
```

**根因（这才是真障碍）**：跨片 `avg(d)` 走本地 `AGGREGATE_REDUCE`，其商 scale=8（`deriveAvgAggType` 已正确定 4+4）；归并后对结果施加了一个 **DECIMAL→DECIMAL(20,4) 降 scale 强制**（源 scale8 > 目标 scale4）。旧 `decimalToDecimal` **identity 恰好无害地保住了 scale-8**（等于「该降 scale 的强制本不该生效」被 identity 掩盖）；B1 让 fork `decimalDecimalCast` 真正施加目标 scale → avg 塌到 scale-4（R1 再令 DOWN→HALF_UP，得 3.6667；即便 DOWN 也是 3.6666，同样 ≠ 3.66666667）。

**结论：B1 的「窄缝」前提被活库证伪**——`DECIMAL→DECIMAL` seam 被**两类语义共用**：① 用户 `CAST(x AS DECIMAL(p,s))`（应降 scale 舍入）；② 聚合归并/算术链的内部类型强制（须保源 scale）。seam 拿不到「这是用户 cast 还是内部 coercion」的区分信息，`scale 方向`也不能辨（两者皆源 scale > 目标 scale）。**故无「单守卫」窄修法**。

**处置（守宁挂账不伪绿）**：`git checkout` 回退 `MycatCastOverride`/`MycatCalciteSupport`/`ScalarStdFuncLocalCodegenTest` 三处至 HEAD，重 build 基线 jar、重启 Mycat，复验 C11 `1/0/0` 绿 + cast 症状逐值复现（`1.2345`/`1.2355` 不 round）。**I-DEBT-66 重升架构级**，正解见 §2.7。

### 2.7 架构级正解方向（待专轮重诊，本轮不投机）

- **(i) 分离两类 DECIMAL→DECIMAL 语义**：让用户 `CAST` 与内部投影/归并 coercion 在 RexPlan 层可辨（如仅对显式 `SqlKind.CAST` 且非聚合归并上下文让路 rounding，内部类型对齐走 identity/preserve）。侵入规划/归并规则，须防 I-DEBT-63 归约塌型族 + C11/C12 回归。
- **(ii) 先修 avg 归并腿那个「不该存在的 DECIMAL(20,4) 降 scale 强制」**（疑为 avg 输出列型/投影对齐缺陷），使其不再经 DECIMAL→DECIMAL 降 scale；修后 B1 对 cast 的安全性需活库重验。
- **(iii) 维持现状**：本债可见度低-中（仅超采样字面量/表达式 `CAST AS DECIMAL(p,s)` 直采文本才偏离），且与已 Evidenced-Done 的补零面正交。可据北极星优先级把 I-DEBT-66 继续挂 C 层 backlog，不强行开轮。

### 2.8 【路线 (ii) 实证定谳 2026-10-25】伪下采根因 = `CalciteConvertors.getRelDataType` 列元数据丢 scale（非 reduceAvg 本身）

用户选 (ii)「先诊 avg 归并腿伪下采」。经四枚活库探针实证（已回退），结论：

**伪下采不在 `AggregateReduceFunctionsRule.reduceAvg` L459——而在 `CalciteConvertors.getRelDataType()` (`hbt/.../util/CalciteConvertors.java:369`)：**

```java
// L369 当前代码（对所有非 VARCHAR/LONGVARBINARY 列）：
type = factory.createSqlType(sqlTypeName);  // ← 不传 precision/scale！
```

`SimpleColumnInfo` 携带 `precision=20, scale=4`（L43-44 字段），但此函数完全忽略它们。Calcite 对 `createSqlType(DECIMAL)` 给默认 `DECIMAL(19, 0)`（precision=`MAX_NUMERIC_PRECISION=19`, scale=0）。

**完整因果链（实证探针铁证 2026-10-25）：**

1. 列 `decimal(20,4)` → 验证器接收类型 `DECIMAL(19, 0)` （scale 丢失）
2. `deriveAvgAggType(DECIMAL(19,0))` → `scale = max(0+4, 0) = 4` → `DECIMAL(19, 4)`（错误，应 = max(4+4,0)=8 → DECIMAL(19,8)）
3. `reduceAvg`：`SUM(DECIMAL(20,4))` → `ensureType(DECIMAL(19,4))` coerce → `DIVIDE(DECIMAL(19,4), BIGINT)` → `deriveDecimalDivideType(DECIMAL(19,4), BIGINT)` → `scale=max(4+4,0)=8` → `DECIMAL(19,8)`
4. `makeCast(pos, DECIMAL(19,4), divideRef[DECIMAL(19,8)])` → 降 scale CAST
5. 基线下 `MycatCastOverride.decimalToDecimal` identity 使此 CAST 运行时无操作 → avg 仍出 scale 8（正确结果，歪打正着）
6. B1 令此 CAST 真执行 → avg 塌成 scale 4（回归 C11）

**正解判定：若修 `CalciteConvertors.getRelDataType` 传 precision/scale，则：**
- `decimal(20,4)` → Calcite `DECIMAL(20,4)` → `deriveAvgAggType` → `DECIMAL(19,8)` → `oldCall.getType()=DECIMAL(19,8)`
- `reduceAvg` 的 CAST 目标=源=`DECIMAL(19,8)` → identity，B1 不伤 C11

**撤回「架构级」定性：** 根因不在规划器/归并规则/CAST seam 交互的架构层面，而在**列元数据传递**的窄缝缺失。I-DEBT-66 的正解组合为：
- **Fix-Meta**：`CalciteConvertors.getRelDataType()` 对 DECIMAL/NUMERIC 传 `columnInfo.getPrecision()/getScale()`
- **B1+R1**（已验证）：`MycatCastOverride` DECIMAL 目标 return null + `roundingMode()=HALF_UP`
- 二者正交，Fix-Meta 解除 B1 对聚合归并的副作用。可见度从「架构级」降为「窄修二连」。

**风险评估（Fix-Meta 爆炸半径）：** 修改 `getRelDataType` 影响**全部表**的 Calcite row type——precision/scale 进入类型系统后波及 validator、type coercion、codegen。须全量回归（至少 `MysqlTypeSystemAvg`/`C3 decimalScaleFidelity`/`C11 crossShardAvgFidelity`/`CrudResultMatrix` 全组/`hbt` 离线 239）。建议专轮 TDD 先行落此 fix。

### 2.9 【Fix-Meta 落地 2026-10-25】TDD RED→GREEN 闭环 + 全量零回归实证

用户选先 1 = 仅落 Fix-Meta，B1+R1 另起一轮。本轮交付：

**机制锁（RED→GREEN）**：新增 [`CalciteConvertorsRelDataTypeTest`](file:///d:/Workspaces/IdeaProjects/Mycat2/hbt/src/test/java/io/mycat/util/CalciteConvertorsRelDataTypeTest.java)，3 个测试：
- `decimalColumnPreservesPrecisionAndScale`：`decimal(20,4)` 列→断言 scale=4（关键锈）+ precision≥ 20 或 clamp 到 19
- `numericColumnPreservesPrecisionAndScale`：NUMERIC 同机制（经 `SimpleColumnInfo` 构造器归一为 DECIMAL）
- `nonDecimalColumnsAreNotAffected`：BIGINT 反向护栏，防 fix 过度泛化

RED 目视失败行：`expected:<4> but was:<0>`（decimal）、`expected:<6> but was:<0>`（numeric）——与 Spec §2.8 定谳因果链逐字对齐。

**产品码（GREEN）**：[`CalciteConvertors.getRelDataType`](file:///d:/Workspaces/IdeaProjects/Mycat2/hbt/src/main/java/io/mycat/util/CalciteConvertors.java#L364-L384) 对 `DECIMAL && precision>0 && scale>=0` 分支传 `createSqlType(sqlTypeName, precision, scale)`，precision clamp 到 `typeSystem.getMaxPrecision(DECIMAL)`=19（当前 Mycat fork 未 override，默认），scale clamp 到 `min(scale, precision)` 防越界。非 DECIMAL 分支保留，爆炸半径受控。

**全量回归实证**（`-DargLine=-Dnet.bytebuddy.experimental=true` 绕开 JDK 25 与 Byte Buddy 不兼容的环境问题，非产品回归）：
- **hbt 242/242 全绿**（含本轮新增 3 锁），零回归
- **CrudResultMatrix 12/12 全绿**（活库），关键目视：`C11：avg(int)=3.6667` + `C11：avg(decimal(20,4))=3.66666667` 均 mycat=oracle 逐字对拍。当前基线 identity 下不暴露 B1 回归，Fix-Meta 为 B1 下阶段安全落地的必要前置。
- **example 全量 A/B 定位**：基线 148T/1F/55E 与 Fix-Meta 148T/1F/55E **数字完全一致**，确认 55E 全为存量红（`[B cannot be cast to String` 与 `XAER_NOTA: Unknown XID`，属 I-DEBT-41/59/46 族），本轮零新增回归。

**遗留项**（归后续专轮）：
- **B1+R1**：`MycatCastOverride` DECIMAL 目标 `return null` + `MycatTypeSystem.roundingMode()=HALF_UP`——前序会话已验证离线出码锁+活库逐值差分（`cast(1.2345 as decimal(10,2))=1.23` 五档全绿），本轮不回revert；待另轮提 PR
- **环境工程卫生**（非本债）：JDK 25 + Byte Buddy 不兼容导致 Mockito inline 测试需实验旗；属工程卫生，建议新开窄账或归 I-DEBT-56 同类
- **server/internalexample 套本轮未跑**：server 模块自身在 HEAD 编译不过（`getConnectionId()` 未定义、`URISyntaxException` 未报），属 pre-existing infra，不属本 fix 引入。待修后补跑。（后继轮实测推翻：`mvn -pl server compile` RC=0，旧记录系 JDK 环境变量问题，见 §2.10 环境发现。）

### 2.10 【B1+R1+Fix-Meta-2 落地 2026-10-25】三者合龙，cast 舍入面活库达标

后继专轮（TDD）落地 §2.9 遗留项 B1+R1，过程中活库暴露并钉死 §2.8 因果链的**最后缺口**，追加 Fix-Meta-2：

**B1+R1（RED→GREEN）**：
- RED 目视：`MycatTypeSystemRoundingModeTest` `expected: <HALF_UP> but was: <DOWN>`；`ScalarStdFuncLocalCodegenTest` 新增 2 出码锁（`castDecimalTargetUsesForkRoundingLeg` 断 `decimalDecimalCast` 在场且 `decimalToDecimal` 缺席；`castDecimalRoundingModeBakedIsHalfUp` 断出码烘入 HALF_UP）失败。
- GREEN：`MycatCastOverride.translate` 于 ANY 透传后加 `tgtName==DECIMAL → return null`（B1 让路 fork 带舍入转换腿）；`MycatTypeSystem.roundingMode()` 覆盖返 HALF_UP（R1）。新锁 6/6、hbt 全量 243/243 零回归。

**活库复验暴 C11 回归红（§2.6 症状精确重现：mycat=3.6667 oracle=3.66666667）**——jar 内三 class hash 与本地编译一致，排除补丁未生效，坐实为真因果链缺口：

**根因 = `common/.../util/MycatRowMetaDataImpl.getPrecision/getScale` 硬编码 0**。分片表列元数据有两条供给腿：① JDBC 腿 `convertfromDatabaseMetaData`（读 COLUMN_SIZE/DECIMAL_DIGITS，有精度标度）；② **DDL 解析腿**（schema 配置含 createTableSQL 文本时，`PrototypeService.getColumnInfo→SQL2ResultSetUtil.getMycatRowMetaData(MySqlCreateTableStatement)→MycatRowMetaDataImpl`），此腿 precision/scale 恒 0 → `SimpleColumnInfo(0,0)` → Fix-Meta 的 `precision>0` 守卫不触发 → DECIMAL 列仍塌 (19,0) → §2.8 链条复活 → C11 红。即 §2.8 「`SimpleColumnInfo` 携带 precision=20, scale=4」的前提**只对 JDBC 腿成立**。

**Fix-Meta-2（RED→GREEN）**：`common` 新锁 `MycatRowMetaDataImplPrecisionScaleTest`（RED 目视 `expected:<20> but was:<0>` 等 2F + datetime 无参护栏绿）；GREEN 实现从列定义 `SQLDataType.getArguments()` 读整数字面量参数（首参=precision、次参=scale，无参/非整数表达式保守返 0 维持现状语义）。

**修后活库实证全闭环**：
- `CrudResultMatrixTest` **12/12 绿**，C11 `avg(d)=3.66666667 = oracle` 逐字对拍；
- 差分探针 TEXT 16/17 + BINARY 6/6 全绿（唯一 FAIL=version() persona 预期差异）：tie 舍入 `cast(1.2355 as decimal(10,2))=1.24`、负数 `-1.24`、`cast(0.5 as decimal(10,0))=1`（HALF_UP 远离零）、补零防回退 `10→10.00`、`(10,4)→1.2345` 不缩、`10/3=3.3333`、metaScale 列头同步一致；
- example 回归轴 A/B：compat 50T/2F（均 I-DEBT-41 存量红 bit 归并族）、sql 79T/1F/5E（any_value/WEEK 存量族）与基线**数字完全一致**，零新增红；common 43/43、hbt 243/243。
- example 全量套（533T/8F/159E）：8F 与今晨基线全量（补丁前 jar）的 F 类名单逐名逐数一致；其中 HbtTest（`avg(id)` 组内显 `1.0000` 而断言整数态）与 VectorTest（断言 `5.0000` 实得 `5.0`）两红经 **git stash 产品码→重建基线 fat jar→重启 Mycat 复跑 A/B 实证均为存量红**（症状逐字符相同，与 B1/R1/FM2 无关；两者同属 I-DEBT-39·tranche② avg(bigint) DECIMAL(scale4) 口径族：真 MySQL `avg(bigint)`=DECIMAL scale4，HbtTest 断言应更新为 `1.0000`，VectorTest 实得 `5.0` 为另一独立缺口——待另票处置，本轮不扩域）。159E 签名全为 XA 池态/lock-wait/DUPID 环境族（本轮两次重启 Mycat + 多轮套跑致后端 XID 残留，板面 §I-DEBT-41 分诊行已定性），非产品新增回归。

**环境发现（非本债，钉死为陷阱）**：新终端 `JAVA_HOME` 漂到 LibericaJDK-25 时，JDK 23+ **默认禁用隐式注解处理**，lombok 全面失效 → common 全量重编炸出 20+ 「找不到符号 getXxx()」假象（曾被误读为存量编译坏）。规矩：本仓一切 mvn 前必坐实 `JAVA_HOME=LibericaJDK-21-Full`。

**裁定**：本债三改点组合 = Fix-Meta（已提交 bc60e5ab4）+ Fix-Meta-2 + B1+R1，I-DEBT-66 本地闭环；剩末次 CodeReview 门禁 + push + CI compat 矩阵全档绿后升 Evidenced-Done。

---

## 3. 修法取舍（据 §2 定谳**更新**，2026-10-25）

根因既已钉在「`MycatCastOverride` 对 DECIMAL 目标返裸 `X_TO_DECIMAL`、短路 fork 舍入」，修法应**就地**处置该 seam，而非再碰归约/后端。

| 方案 | 落点 | 优点 | 代价 / 风险 | 建议 |
|---|---|---|---|---|
| **B1. `MycatCastOverride` 对 DECIMAL 目标让路（`tgtName==DECIMAL` 时 `return null`）** | `hbt/.../MycatCastOverride.java:81`（`tgt==DECIMAL` 分支前） | **最窄**：DECIMAL 目标 CAST 交回 fork `getConvertExpression` 的 `*_DECIMAL_CAST_ROUNDING_MODE`（`RexToLixTranslator.java:576-631`，带 precision/scale/roundingMode）；算术链、其它目标（DATE/TIMESTAMP/…）零改动 | 需核 MySQL 宽松度：现 `stringToDecimal=new BigDecimal(b)` 对畸形串**本就抛**（并非更宽松），故让路未必引入 leniency 落差；但要验 fork 的 int→decimal 溢出/越界检查与 MySQL 语义是否一致（TDD 步骤3/4 兜） | ✓ **推荐首选** |
| **B2. 让 DECIMAL 目标 `X_TO_DECIMAL` 方法带 precision/scale/roundingMode 并在值腿 `setScale`** | `MycatBuiltInMethod` 增带参重载 + `MycatBuiltInMethodImpl.*ToDecimal` 补舍入 + `MycatCastOverride` 传 target p/s | 保留「Mycat 矩阵自管宽松 CAST」架构一致性，rounding 施加在**正确的值生成腿** | 改枚举/方法签名/矩阵全 DECIMAL 条目，改动面大于 B1；`MycatCastOverride` 现只传 operand、不传 target 元数据，需扩 seam 签名 | 备选（B1 遇 leniency 落差点时针对性启用） |
| **A. 注册 `CALC_REDUCE_DECIMALS`** | `MycatRules.rules()` | Calcite 官方 reduce pass | §2.1 已证归约**非塌型点**（CAST 完好到 codegen），注册它既治不了本债、又**爆炸半径最大**（重写所有 DECIMAL 算术/比较/聚合，动摇 I-63 归约族/`avg` scale 链/golden） | ✗ 不取（且与根因无关） |
| **C. coercion 期对 `BigDecimal` 强 `setScale`** | `EnumUtils.convert`/出码 coercion | — | 误伤算术/比较强制链、难区分「CAST 目标 scale」与「源列天然 scale」，重演 I-65 avg 腐蚀禁忌 | ✗ 不取 |

**推荐 = B1**：`MycatCastOverride.translate` 在目标为 DECIMAL 时返回 null，令本地投影落回 fork 原生 `translateCast` 的 DECIMAL rounding 分支（该分支已正确、无需改）。若 TDD 暴露 MySQL leniency 落差（如特定串/溢出容错），对**该源类型**局部启用 B2 补带参舍入。通用算术链零改动，爆炸半径最小。

---

## 4. 落点（据 §2 定谳**确认**，非预判）

- **唯一改点**：`hbt/.../calcite/MycatCastOverride.java`——在 `lookup(...)`（L87）**之前**加守卫 `if (tgtName == SqlTypeName.DECIMAL) return null;`，让**命中矩阵**的 DECIMAL 目标 CAST 交回 fork rounding 分支。守卫置于 ANY 透传（L65-67）**之后**，故 ANY 源列（如分片扫描行）行为不变。
- **正确机制已在位、无需改**：`calcite-core/.../RexToLixTranslator.java:576-631`（fork DECIMAL rounding 分支），B1 让它重新被命中即可。
- **经探针**明确排除**的伪落点**（勿再改）：
  - `MycatCalc.java:160-166`（`RexSimplify` normalize）——CAST 完好，非删除点；
  - `RelNodeConvertor.java`（HBT 往返）——死代码，`convertRelNode` 抛 UnsupportedOperation；
  - 后端 SQL unparse——CAST 根本未下推；
  - `scaleValue`（L1298-1327）——对 DECIMAL 不缩放是 by-design，非缺陷点；
  - **MycatView 下推/投影分解**——上一版「架构级丢 CAST」恐慌已被真症状实测**撤回**（§2.4 第3条）；MycatView 非本债改点。

> 结论：塌型点已从「推断」升格为「真症状实跑定谳」（§2.2：DECIMAL 目标命中裸 `decimalToDecimal`/`bigintToDecimal`，`MycatCastOverride.java:87-92` 只传 operand）。改动集中于 `MycatCastOverride` 单文件一处守卫（B1），B1/B2 混合比例待 TDD 逐值差分裁定。

---

## 5. TDD 验证计划（专轮执行，本 Spec 不落）

1. **步骤1 · 取证定谳删除点**：**本会话已完成**（临时探针，已删）。结论见 §2：CAST 不被归约剥除、不下推后端；塌型在 `MycatCastOverride` 对 DECIMAL 目标返裸 `X_TO_DECIMAL` 短路 fork rounding + `scaleValue` 不缩放 DECIMAL。回写 §2/§4。
2. **步骤2 · 离线出码锁（RED→GREEN）**：**须在生产态（装配 `MycatCalciteRuntimeHooks`）下取出码**，非未装钩子的 DEFAULT harness——
   - 现状（RED）：`cast(1.2345 as decimal(10,2))` 本地出码命中 `MycatBuiltInMethodImpl.decimalToDecimal(…)`（纯 identity，无 scale 舍入；整数源则 `bigintToDecimal`）；
   - 目标（GREEN）：B1 后应改由 fork DECIMAL rounding 分支出码（`Primitive.decimalDecimalCast`/`integerDecimalCast` 等带 precision/scale/roundingMode 形态），不再出现裸 `decimalToDecimal`/`XToDecimal`；
   - 护栏：`cast(id as double)`、`cast(x as char)` 等非 DECIMAL 目标出码零变化。
3. **步骤3 · 活库逐值差分**：persona MYSQL_8_0/ MariaDB:10.11 对拍
   `cast(1.2345 as decimal(10,2))=1.23`、`cast(1.2355 as decimal(10,2))=1.24`、`cast(1.2345 as decimal(10,4))=1.2345`（不缩）、`cast(10 as decimal(10,2))=10.00`（I-65 补零不回退）。
4. **步骤4 · 零回归护栏**：`hbt` 全量套、`DecimalColumnScaleEncodeTest`（7）、`MysqlTypeSystemAvgTest`/`MysqlTypeSystemDivideTest`、`CalciteValidatorGoldenTest`/`DruidOutputGolden`；four-suite `CrudResultMatrixTest.crossShardAvgFidelity`（C11）不得复红（防 avg 腐蚀回潮）。
5. **步骤5 · 末次 CodeReview 门禁** + commit + push + CI compat 矩阵全档绿，DEBT-QUEUE I-DEBT-66 升 `本地✅/CI⏳`→`Evidenced-Done`。

---

## 6. 可见度与优先级（贴北极星）

- **可见度：低-中**。仅「`SELECT` 直采超采样字面量/表达式 `CAST(... AS DECIMAL(p,s))` 并取文本/导出」场景偏离；多数驱动取 DECIMAL 走数值语义（`BigDecimal` 比较忽略 scale）无感。补零面（更常见）已随 I-65 收口。
- **层级：C（引擎地基 · codegen/结果值表征）**，不在 spec §3 关键路径 `M1→M2→M3` 上；A 层 M2 硬门全✅、推荐序（I-64/65）已尽，本债属纯本地 C 层地基 backlog（`DEBT-QUEUE §6` 第3步族）。
- **裁定：架构级 · 待专轮**。值腿 rounding 非编码缝可安全施加，须走 route(a)/方案 B 并防 I-DEBT-63 归约塌型族 + golden 回归。
