# I-DEBT-67 设计：ordinate 向量化执行器聚合链 DECIMAL 保真专轮（决策齐备·待落地）

> 状态：**⚠ 已被 2026-10-28 落地活体轮 §6 推翻·真因重定位**（原 S1/S2/S3 缝位对 avg(id) 实测为死代码/错位；本 Spec §0-§5 仅作勘察历史留档，接手以 §6 为准）。
> 原状态（已作废）：勘察收口 · 缝位全量枚举 · 决策齐备待专轮落地（2026-10-28 本会话离线勘察，全部 file:line 凭当前 HEAD 实读，非凭记录）。
> 本 Spec 不随批落产品码——勘察证实横切面广（含逐列精度/标度表征缺失这一最深缝），按既定纪律"宁挂账不伪绿、不投机半修"，专轮据本 Spec 干净接手。

## 0. 债刻画（现状锤钉）

`/*+mycat:setVector{1}*/` 态下 `SELECT avg(id)`（id BIGINT）出 `5.0`（Double 轨），真库 MySQL/MariaDB 返 DECIMAL scale4（`div_precision_increment=4`）= `5.0000`。

既有锁（本会话实测复核）：
- knownGap 锚锁 `executor/src/test/java/io/ordinate/engine/AvgDecimalFidelityGapTest.java`：GREEN 1/1（钉死现状 avg 全 DOUBLE_TYPE，实测 `mvn -o -pl executor test -Dtest=AvgDecimalFidelityGapTest` 6/0/0…即 1/1 绿）。
- 目标红锁 `example/src/test/java/io/mycat/vector/VectorTest.java` L74：断言 `[{avg(id)=5.0000}]`，活在 `setVector{1}` 段现出 `5.0` 即红（挂账态，需活代理 e2e 验证）。
- calcite codegen 腿（非向量引擎）已由 I-DEBT-39·tranche② `deriveAvgAggType` 扳对 DECIMAL scale4——本债**仅**向量执行器轨。

## 1. 缝位全量枚举（六层，由浅入深）

### S1 派发缝（入口）
`executor/src/main/java/io/ordinate/engine/builder/ExecuteCompiler.java` `avg(int index)` L519-521：无条件 `new AvgAggregateFunction(index)`（Double 轨）。对照 `sum/min/max` L505-549 均有 `input.schema()` 字段型派发。
`CalciteCompiler` L243-246 AVG case 未把 `input` 传入 `avg()`（sum/min/max 均传）。
**修法**：`avg(PhysicalPlan input, int index)` 按 `InnerType.castToAggType(field.getType())` 派发：INT32/INT64 → 新 `AvgDecimalAggregateFunction`；FLOAT/DOUBLE → 现 Double 轨保留；DECIMAL 源 → 见 S4（依赖逐列标度通道，可划二刀）。

### S2 累加器缝
`AvgAggregateFunction`（avg/AvgAggregateFunction.java L27-104）implements `DoubleAccumulator`；`computeNext` 读 `record.getDouble`；`allocContext` 申请 LONG(count)+DOUBLE(sum) 两槽。
**修法**：新增 `AvgDecimalAggregateFunction`（`function/aggregate/avg/` 下）：
- `allocContext`：LONG(count)+**LONG(sum)** 两槽（cairo MapValue 仅 primitive 槽，`addLong` 精确累加整数组，**无需造 BigDecimal 存储**——终值期折算）；
- `computeNext`：`record.getLong(columnIndex)`（派发链保证输入槽为 BigIntVector）；
- 终值：`BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), SCALE_4, RoundingMode.HALF_UP)`（见 §2 D2/D3 语义决策）；
- `getOutputType/getType` = `InnerType.DECIMAL_TYPE`；`name()`=`avg(decimal)`；NULL 语义：count==0 → `isNull=true`（AVG 空集返 NULL，对齐真库；现 Double 轨 `getDouble` count==0 返 0 且 `isNull` 已正确置标，序列化面沿用）。
接口面：`AccumulatorFunction`/`DoubleAccumulator`/`LongAccumulator` 模式各一——加 `DecimalAccumulator extends AccumulatorFunction<BigDecimal>`？不必：**引擎值读取已按 `Function.getType()` switch 分派**（`Function.getAsObject` L215-280 已有 `case DECIMAL_TYPE: res = getDecimal(rec)` L242-244），新类覆写 `default BigDecimal getDecimal(Record)`（Function.java L138-140 现抛 UnsupportedOperation）即可，零新接口。

### S3 物化缝（agg 结果写回向量）
`NoKeysAggPlan.finalToVectorSchemaRoot` L149-190：switch 仅 `INT*→BigIntVector.set(computeFinalLongValue)` 与 `DOUBLE→Float8Vector.set(computeFinalDoubleValue)` 两落地 case，**DECIMAL case 缺失**（落穿不写值=静默 null/0）。`AggregateVectorExpression` L26-27 仅 two `computeFinal*Value` 缺省。
**修法**：`AggregateVectorExpression` 加 `default BigDecimal computeFinalDecimalValue(MapValue){ return null; }`；`NoKeysAggPlan` 加 `case DECIMAL_TYPE:` → `((DecimalVector) output.getVector(columnIndex)).set(0, expr.computeFinalDecimalValue(simpleMapValue))`；`AvgDecimalAggregateFunction.toAggregateVectorExpression()` 实现之（sum/count 两 LONG 槽读回折算，与 S2 同一私有工具）。
⚠ `GroupByKeyWithAggPlan`（带 group key 态）**无** `finalToVectorSchemaRoot`（本会话 Select-String 零命中）——其结果经 `map.forEachRecord`/record-sink 通道装配，**专轮落地前须先枚举该 plan 的十进制写回路径**（遗留勘察项，见 §3 步 4）。VectorTest 现红格 `select avg(id)` 无 group by，走 NoKeysAggPlan 即达首刀目标；`group by user_id` 十进制均值属同轮第二面或拆票，落地时按实测归位。

### S4 表征缝（最深·架构本体）
`common/src/main/java/io/mycat/beans/mycat/ArrowTypes.java` L39：`DECIMAL_TYPE = new ArrowType.Decimal(0,0,64)`——**全局单常量、scale=0**。`InnerType.from(ArrowType)`（InnerType.java L81-82）把任意 `ArrowType.Decimal` 折叠为 `InnerType.DECIMAL_TYPE`，**逐列 precision/scale 在此丢失**。schema/Field 装配（InnerType L410 `case DECIMAL_TYPE`）回填时只能吃 (0,0) 常量 → DecimalVector 以 scale0 建，`5.0000` 必被 Arrow 截成 `5`。
**修法（决策 D4）**：聚合输出通道的 Field 构造改为**携带逐列 ArrowType.Decimal(precision,scale,128/64)**：
- `AccumulatorFunction` 加 `default ArrowType getOutputArrowType()`（缺省由 getOutputType 映射现常量；avg-decimal 覆写为 `Decimal(65, div_precision_increment, 64)`——DecimalVector 的字节宽由 Field 的 bitwidth 决定，Arrow `DecimalVector` 要求 128bit（`Decimal(p,s,128)`）；**落地时以 `ArrowTypes` 现行 `Decimal(0,0,64)` 走何向量类实测为准**（`ResultWriterUtil` 强转 `DecimalVector`＝128bit 系，则全局常量本身位宽声明 64 与向量类已矛盾——专轮第一步用最小活体探针钉死此事实，若读侧能正确渲染后端 decimal 即证明 64 声明未被消费/或被覆盖，据实修正本决策））；
- `CalciteCompiler` agg schema 装配点消费 `getOutputArrowType()` 建 `Field`；
- 输入侧 `column()`（ExecuteCompiler L52-93 `case DECIMAL_TYPE: break;`→ L93 `throw UnsupportedOperationException`）**本首刀不需要**（avg decimal 输入是整数组，经 cast-project 后为 INT64 槽）；DECIMAL **源**列聚合（sum/avg over decimal）留第二刀，届时补 `DecimalColumn`。

### S5 序列化缝（已存在·零改动）
`executor/src/main/java/io/ordinate/engine/util/ResultWriterUtil.java` L259-262：`case DECIMAL → ((DecimalVector) vector).getObject(rowId) → newWriter.addDecimal(...)`。BigDecimal 原样带标度上 wire（`5.0000` 保真）。**此腿已通，是本专轮边际成本最低的证据。**

### S6 读侧输入缝（背景事实）
后端结果 → Arrow 装载链不在 executor/main（全仓 `new DecimalVector` 零显式实例化，由 Arrow schema 自动建向量），本刀不改。

## 2. 语义决策（决策齐备·落地前活体钉验）

- **D1 scale 来源** = 会话 `div_precision_increment`（缺省 4）。专轮落地时经 session 变量通道取真值；首刀可先常量 4 + TODO 挂会话变量（若会话变量通道已在 hbt persona 面存在则直连，落地时查证）。
- **D2 舍入模式** = 向零截断（MySQL `/` 与 AVG 实为**舍入**：`select 2/3`→`0.6667` 排除截断）；平局方向未证——**专轮第一步活体探针**：真库跑 `avg` 使第 5 位小数恰为 5 的样本（如 sum=1,count=8 →0.125 恰表尽无第5位；取 `{1,1,1,2}/4=0.75`…需构造第5位平局如 sum=5,count=16→0.3125 恰 4 位；真平局需 5 位循环：`{1}/3=0.3333` 无平局。可行构造：`sum/count` 第5位=5 且其后全0，如 `count=32,sum=1 → 0.03125 → scale5…` 超4位舍入位，即 `1/32=0.03125` 舍入到4位：0.0312(HALF_EVEN) vs 0.0313(HALF_UP)——**用该样本活体分晓**）。本 Spec 先取 **HALF_UP**（与 MySQL DECIMAL 除法文档口径一致），活体探针后如异据实翻。
- **D3 溢出边界**：整数组源 sum 入 LONG 槽——BIGINT 极端大值累加可溢 long（真库 SUM(bigint)=DECIMAL(65,0) 无溢）。**决策**：首刀不处理、**不装绿**——在类注释+看板显式挂 knownGap（对齐本仓"现状锤钉于 knownGap 锁"惯例），新增离线锁断言溢出为**已知缺口**；根治需 MapValue 双 LONG（hi/lo）或 BINARY 槽大数累加器，属第二刀。
- **D4 逐列标度表征**：见 S4——本刀只在聚合输出 Field 构造点引入 `Decimal(p,s,bw)`，不动全局常量语义（改常量=全局爆炸半径）。
- **D5 对外列元数据**：AVG 列上报 `COLUMN_TYPE=DECIMAL`/scale=4 与真库一致；persona 面既有 `deriveAvgAggType`（I-DEBT-39·tranche②，calcite 腿）为口径锚，两腿殊途同归不互抄。

## 3. 落地任务序（TDD·专轮接手即用）

1. **活体探针钉语义**（D1/D2/S4 位宽事实）：起活库对拍 `avg` 平局样本 + 读侧 decimal scale 保真样例 → 回写本 Spec 决策终值。
2. **RED（离线·executor 模块）**：
   - `AvgDecimalAggregateFunctionTest`：直构累加器喂 sum=45/count=9 语义（经 MapValue 需 cairo 测试基建——若建值成本高，退而锁 `ExecuteCompiler.avg` 派发产物类型 + `computeFinalDecimalValue` 折算工具函数纯单测）；
   - 翻转 `AvgDecimalFidelityGapTest`（看板明令：专轮落地后删/翻此锁）→ 断言 `avg(int64)` 派发为 DECIMAL 轨；
   - `NoKeysAggPlan` 十进制物化最小离线集成锁（rootContext 造 BigIntVector 输入批 → agg → 断言输出 DecimalVector getObject=5.0000）。
3. **GREEN**：按 S1/S2/S3/S4 落码（预计 6-8 文件：ExecuteCompiler、CalciteCompiler、avg/ 新类、AggregateVectorExpression、NoKeysAggPlan、（含 GroupByKeyWithAggPlan 面则+1）、Function.getDecimal 注释面零改）。
4. **枚举 GroupByKeyWithAggPlan 写回通道**（S3 ⚠）据实补第三处 case 或拆票。
5. **回归护栏**：`mvn -o -pl executor test` 全量 + hbt 全量（正交但同仓表征敏感）+ **活代理 e2e**：VectorTest 转绿（`5.0000`）+ 摘 hint 对照（calcite 腿本就绿，证两腿独立）+ 溢出 knownGap 锁显式挂账。
6. **看板**：I-DEBT-67 行 ✅ 收口段 + DEBT-QUEUE 关闭序更新；溢出/decimal 源聚合残面据实拆新票（候选 I-DEBT-6x）。

## 4. 风险与边界

- **爆炸半径**：向量引擎 hint 门控（`setVector{1}`），不在 spec M2 关键路径主轨（主轨 calcite codegen 腿已对）；CI compat 矩阵不启向量态 → 本刀无 PR 阻断语义变更。
- **红线**（造假前科纪律）：禁字符串格式化 double 冒充 scale4（值面伪绿）；禁在 (0,0) 全局常量下 set BigDecimal 后默吞截断；溢出/decimal 源不装绿、knownGap 显式挂。
- **回滚**：纯增量（新类+case 分支+派发），git revert 单 commit 即净。

## 5. 本会话勘察勘误记录（诚实面）

- 上轮会话 Grep（ripgrep）对 DEBT-QUEUE.md 长 CJK 行假阴致"I-DEBT-64 缺行"误判，PowerShell Select-String 复核撤回；本轮全程以 Select-String/Read 双通道交叉。
- `InnerType.java` L350 `case DECIMAL` 系 ColumnType→InnerType 映射（questdb 列型面），非 arrow 面，不影响 S4 结论。

## 6. 落地活体轮勘误（2026-10-28·**决定性·推翻 §0-§5 前提**·接手以此为准）

> 本节凭当前 HEAD 活体运行取证（本地起代理 8066 classpath 直启 JDK21 + prototypeDs→docker mariadb:10.11@3306），非静态推断。§0-§5 的 S1/S2/S3 据此作废。

### 6.1 活体 RED 坐实
`mvn -o -pl example test -Dtest=VectorTest#testBase` → `Tests run: 1, Failures: 1`，`VectorTest.testBase:74 expected:<[{avg(id)=5.0[000]}]> but was:<[{avg(id)=5.0[]}]>`。前置断言（vector() 序、count=9、max=9、min=1、sum=45）**全过**，仅 avg 失败——活体 infra 健康、缺口精确即 avg DECIMAL 保真、无连带破坏。

### 6.2 真因（server 运行态计划日志取证·锤死）
`setVector{1}` 下 `SELECT avg(id)` 的**实际物理计划**：
```
MycatProject(avg(id)=[CAST(/(CAST(CASE(=($1,0), null:BIGINT, $0)):DECIMAL(19,4), $1)):DECIMAL(19,4)])
```
即 **Calcite `ReduceAggregatesRule` 把 AVG 分解为 `SUM`(=$0)+`COUNT`(=$1) 两个聚合 + 外层除法 Project**。故 ordinate 向量引擎**根本不接收 AVG 累加器**——本会话据 §0-§3 实现的 `AvgDecimalAggregateFunction` + `ExecuteCompiler.avg(input,index)` 派发 + `CalciteCompiler` 传 input 对这条查询**全是死代码**（落地后活体复跑仍 `5.0`，遂整体 git revert，产品树已净回 HEAD）。

### 6.3 真缝 = 向量腿端到端缺 DECIMAL 支持（非累加器问题）
分解出的 `CAST(sum):DECIMAL / count` 除法在向量腿塌成 double，实证四处缺位：
- **列读取**：`ExecuteCompiler.column()` L70-71 `case DECIMAL_TYPE: break;` → L94 `throw UnsupportedOperationException`——向量腿**无 DECIMAL 列读取器**。
- **除法算子**：`RexConverter` L275-276 `case DIVIDE: executeCompiler.call("/", aeOperands)`——`/` 无 DECIMAL 语义，落 double。
- **CAST-to-DECIMAL**：向量腿无 `CAST(...):DECIMAL(p,s)` 的十进制实现（计划里的 DECIMAL(19,4) cast 被降级/默吞为 double 域运算）。
- **列头 scale 保真（独立子缝）**：`getMycatRelType(Schema)`（common/.../MycatRelDataType.java L47）用 3-arg `MycatField.of(name,mycatDataType,nullable)`，**不读 Arrow Decimal 的 precision/scale** → 即便值腿修对，wire 列头 scale 仍非 4（binary 协议下 Connector/J 按列头 scale 构 BigDecimal，会二次损坏；text 协议下值串自带 scale 侥幸幸存）。
- 关联表征死根（原 S4 仍成立且更深）：全局 `ArrowTypes.DECIMAL_TYPE=Decimal(0,0,64)` scale0+非法位宽，`InnerType.from` 折叠逐列 p/s。

### 6.4 债重定范围（远超原估）
I-DEBT-67 真身 = **ordinate 向量腿整套 DECIMAL 支持栈**：十进制列读取器（`DecimalColumn` + `column()` DECIMAL case）+ `/` 的 DECIMAL 除法语义（对齐 calcite 腿 `MysqlDivideFunction`：值日志实证 codegen 腿已发 `integerDecimalCast(...,19,4,HALF_UP)` 正确 BigDecimal）+ `CAST→DECIMAL` 向量实现 + 逐列 `Decimal(p,s,128)` 表征贯通 + `getMycatRelType` 列头 scale 保真。**体量与风险远超原 §3 的 6-8 文件估计，属引擎算术子系统级改造，非窄首刀。**

### 6.5 knownGap 锁对齐提示
`AvgDecimalFidelityGapTest` 单元级钉 `AvgAggregateFunction` 全 DOUBLE——但 e2e 分解世界根本不用该类（AVG 被分解走）。此锚锁真实反映"单元类现状"却**锚错了 e2e 真路径**；专轮落地时应把锚锁与目标重定向到向量腿 decimal 除法（`/`）与列头 scale，而非 avg 累加器。`VectorTest:74` 目标红锁本身有效（活体已证其在正确位点失败）。

### 6.6 语义活体确证（原 D1/D2 落地）
`docker exec week-m80 mysql`（8.0）实测：`@@div_precision_increment=4`；`1/32=0.03125→0.0313`、`5/32=0.15625→0.1563`（HALF_EVEN 会得 0.0312/0.1562）→ **MySQL 除法舍入 = HALF_UP（远离零），scale=4**，钉死无悬念。向量腿 `/` 的 DECIMAL 实现须按此口径。

### 6.7 本会话净态
错位产品码已全部回退（`git checkout` 4 改 + `DeleteFile` 1 新），executor 已重编回 HEAD 类，探针代理已停，产品树 `git status` 干净。本轮净交付＝本 §6 活体勘误（把债从"假设有望的窄首刀"重定位为"已证实的引擎级 DECIMAL 算术子系统缺位"），避免了一次会误导后续专轮的伪绿提交。

## 7. 引擎级专轮拆刀进度（据 §6.3 四处缺位序拆，逐刀 TDD 独立可证）

> 承 §6.4 债重定，I-DEBT-67 拆为四刀有序落地，每刀独立 RED→GREEN、独立不碰下一刀。

| 刀 | 缝位 | 状态 |
|---|---|---|
| ① | 向量腿 DECIMAL **源列**读取器（`column()` + `DecimalColumn` + `VectorBatchRecord.getObject`） | ✅ **已落地转绿**（见 §7.1） |
| ② | `/`（DIVIDE）的 DECIMAL 除法语义（对齐 calcite 腿 `MysqlDivideFunction`：scale=div_precision_increment、HALF_UP） | ✅ **已落地转绿**（并入第二刀，见 §7.3） |
| ③ | `CAST(x):DECIMAL(p,s)` 的向量腿十进制实现（§6.3 计划里的 `CAST($0):DECIMAL(19,4)` 现塌 double） | ✅ **已落地转绿**（并入第二刀，见 §7.3） |
| ④ | 逐列 `Decimal(p,s,128)` 表征贯通 + `getMycatRelType` 列头 scale 保真（binary 协议下 Connector/J 按列头 scale 构值） | ✅ **已落地转绿**（经 `getOutputArrowType` 贯通，见 §7.3） |
| 残留 | 向量态非终止小数 avg scale 保真 + `group by` 带键十进制均值写回（§7.3 挂账两子项） | ✅ **已活体验证 + 锁死，本债全绿关账**（见 §7.4） |

### 7.1 第一刀① 落地实录（2026-10-28·本会话）
- **RED**（眼看失败）：新建离线锁 `executor/src/test/java/io/ordinate/engine/VectorLegDecimalColumnReaderTest`——对 `DECIMAL(19,4)` schema 调 `ExecuteCompiler.column(0, schema)`，实跑现 `java.lang.UnsupportedOperationException at ExecuteCompiler.column(ExecuteCompiler.java:93)`（十进制列读取器缺位，正是 §6.3 首条）；并断言读一个含 `1.2345` 的 `DecimalVector` 批应经 `VectorBatchRecord` 读回精确 `BigDecimal`（落地前 `getObject` 恒返 null 亦不可达）。
- **GREEN**（三处最小改动）：
  1. 新增 `function/column/DecimalColumn.java`（implements `ScalarFunction,ColumnFunction`，`getType()=InnerType.DECIMAL_TYPE`，`getDecimal(rec)` 经 `rec.getObject()` 读回 BigDecimal，NULL 走 `isNull` 置标）；
  2. `ExecuteCompiler.column()` `case DECIMAL_TYPE: break;`→`return DecimalColumn.newInstance(index);`；
  3. `VectorBatchRecord.getObject()`：`instanceof DecimalVector` 时 `((DecimalVector)vector).getObject(index)` 读回（其余类型维持原 null 行为，非回归）。
  4. **测试基建**：executor `pom.xml` 为 surefire forked JVM 常驻 `--add-opens=java.base/java.nio=ALL-UNNAMED`（Arrow netty 后端在 JDK17+ 分配 `DecimalVector` 的硬要求，使锁可复现；纯测试面，零产品语义变更）。
- **验绿**：`mvn -o -pl executor test`（JAVA_HOME=LibericaJDK-21-Full）→ `Tests run: 2, Failures: 0, Errors: 0`（新锁 + `AvgDecimalFidelityGapTest` 皆绿）+ BUILD SUCCESS。
- **诚实边界（关键）**：**本刀不让 `VectorTest` 的 `avg(id)` 目标红转绿**——`avg(id)` 源列是 BIGINT（走 `LongColumn`），其 DECIMAL 仅出现在外层 Project 的 `CAST/COUNT` 除法里，真 blocker 是 ②③④。本刀关掉的是"向量腿无法读任何 DECIMAL **源列**"这一独立真实缺口（如 `SELECT decimal_col FROM t /*+setVector{1}*/` 落地前必崩），是后续刀的承重前置。I-DEBT-67 整体**仍开放**，不假关。

### 7.2 第二刀② 勘察：真枢纽定位（2026-10-28 本会话·当前代码实证，订正 §6.3）
§6.3 旧表述称 ② 是"`RexConverter` L275 `/` 无 decimal 语义"——本轮实读当前 HEAD **推翻该定位**，真枢纽更上游、更单点：
- **`RexConverter.convertColumnType(SqlTypeName)` L354-356**：`case DOUBLE: case DECIMAL: return InnerType.DOUBLE_TYPE;`——**向量腿把 DECIMAL 系统性折叠为 DOUBLE**。
- **传导链**：`convertToFunction(RexCall)` 的 `case CAST`（L278-280）`targetType = convertColumnType(call.getType())` → 计划里的 `CAST($0):DECIMAL(19,4)` 目标被折成 **DOUBLE_TYPE** → `ExecuteCompiler.cast(operand, DOUBLE_TYPE)` 投成 double；随后 `case DIVIDE`（L275-276）`call("/")` 两端均 double → 结果 double → 序列化 `5.0`。**故 avg 塌型根在类型映射枢纽，非除法算子本体。**
- **不可盲翻（投机半修警示）**：单把 L356 改 `DECIMAL_TYPE` 会连带：①`cast()`（ExecuteCompiler L198-213）按 (源型→目标型) 查注册 "cast" 工厂，无 `cast(*:decimal)` 实现即直坠 L213 `throw`（另一种崩，非修复）；②`/` 需新增 DECIMAL 操作数语义（scale=`div_precision_increment`、HALF_UP，对齐 §6.6）；③输出列 schema 需携逐列 `Decimal(p,s)` + `getMycatRelType` 列头 scale 保真（②③④耦合）。故 ②③④ **必须作为一个 e2e 锚定的整体专轮**推进（活代理 VectorTest 现证 + hbt/golden 回归护栏），不可孤立改单点——重踩上一轮 avg 累加器"假设塌方死代码"覆辙。
- **下一轮起手式**：起活代理 infra（本会话已沉淀可复用配方 `.mycat-home-e2e\launch.ps1` + fullcp classpath 直启）→ 以 `VectorTest` 为红锺，按序落 convertColumnType(DECIMAL 保留) + 十进制 cast 工厂族 + `/` decimal 除法(scale4/HALF_UP) + 逐列 Decimal(p,s) 输出/列头，每步活体现测防回归。

### 7.3 第二刀②③④ 整体落地实录（2026-10-28·本会话·avg 端到端转绿）

按 §7.2 起手式起活体 infra（docker 三容器在跑 + `launch.ps1` fullcp 直启 8066）→ 取活体真红 `VectorTest.testBase:74 expected 5.0000 but was 5.0`（含第一刀① 的当前 main 基线）为红锺，整体落 ②③④。

**关键设计决策（订正 §7.2 起手式）**：不动全局 `convertColumnType`（爆炸半径太大），改为在 `RexConverter` 的 `case CAST`/`case DIVIDE` 内**按结果 `RelDataType.getSqlTypeName()==DECIMAL` 精确特化**，直接构造携带逐列 `precision/scale` 的十进制函数——把改动面严格锁在"向量腿十进制 CAST/除法"，非十进制路径一行不改（`getOutputArrowType()` 默认 == 旧 `getType().getArrowType()`）。

- **最深发现（运行态锤定，比 §7.2 更准）**：Project 输出列 Arrow 类型 = `function.getType().getArrowType()`（`ExecuteCompiler.project` L573），`ExprVectorExpression` 构造同源（L39），而 `InnerType` 是枚举、`DECIMAL_TYPE.getArrowType()`＝全局 scale0 常量；且 `ExprVectorExpression.eval()` 的 `switch(getType())` **根本没有 `case DECIMAL_TYPE`**（第50-290行）→ 向量腿 DECIMAL 是"类型表征 + 求值分派"双重缺位。所幸 `ExprVectorExpression` 实为"按行调用 ScalarFunction"的向量封装（L49 `generateSink`＋`setPosition`＋`getXxx`），故补一个 DECIMAL 分支即按行正确写 `DecimalVector`（非 SIMD 但值正确）。另 `ResultWriterUtil` L259-262 早已支持 DECIMAL（读 `getObject`→`addDecimal`），且列头 scale 由 L172 `MycatRelDataType.getMycatRelType(outputSchema)` 从 Field 取——**只要输出 Schema Field 带 `Decimal(19,4)`，第四刀列头 scale 自动满足**（聚合 sum 走 BIGINT，`castToAggType`→SumLong，非 decimal，无 scale0 隐患）。
- **GREEN（六处最小改动）**：
  1. `Function.java`：加 `default ArrowType getOutputArrowType(){ return getType().getArrowType(); }`（逐列 scale 载体，默认等价旧行为）。
  2. `ExecuteCompiler.project()` L573：`exprTypes[i] = function.getOutputArrowType();`（输出 schema/列头随逐列 scale）。
  3. `ExprVectorExpression`：构造改 `super(function.getOutputArrowType())`；`eval()` 补 `case DECIMAL_TYPE` → `DecimalVector.setSafe(i, function.getDecimal(rec))`（NULL 走 `setNull`）。
  4. `RexConverter`：`case CAST`/`case DIVIDE` 于结果型为 DECIMAL 时分派 `new CastToDecimalFunction(...)`/`new DivDecimalFunction(...)`（携 `call.getType()` 的 precision/scale），否则维持原 `cast()`/`call("/")`。
  5. 新增 `function/decimal/CastToDecimalFunction`（`getType=DECIMAL_TYPE`，`getDecimal` 经 `DecimalValues.toBigDecimal` 读操作数后 `setScale(scale, HALF_UP)`，覆写 `getOutputArrowType`）、`DivDecimalFunction`（BigDecimal 除法 `scale=div_precision_increment`（缺则兜底 4）/HALF_UP、除零→NULL，MySQL 语义对齐 §6.6）、`DecimalValues`（公共助手）。
  6. 新增离线锁 `VectorLegDecimalArithmeticTest`（4 测）：cast `setScale`+逐列 scale、除法 scale/HALF_UP、除零→NULL、`getArgs` 暴露操作数（活体精确整例之外的边界锁）。
- **验绿（活体＋全量回归）**：
  - 活体 `mvn -o -pl example test -Dtest=VectorTest` → `Tests run: 1, Failures: 0` / **`avg(id)=5.0000`**（红锺转绿，端到端证）。
  - `mvn -o -pl executor test` → `Tests run: 6, Failures: 0`（含新算术锁）。
  - hbt golden 护栏 `mvn -o -pl hbt -am test -Dtest=MysqlTypeSystemDivideTest,MysqlTypeSystemAvgTest,MysqlDivideFunctionTest,TypeRepresentationThreePathConsistencyTest,CalciteConvertorsRelDataTypeTest` → `Tests run: 48, Failures: 0` + BUILD SUCCESS。
- **诚实边界（残留子项，不假关）**：本轮以 `VectorTest` 精确整除例（id 1..9，`avg(id)=45/9=5.0000`）为活体红锺转绿；**非终止小数 avg（如均值落 `1/3` 类）在向量态下的 scale 保真**，其除法 scale 取自 Calcite 结果 `RelDataType`（默认已由 `div_precision_increment` 推得），逻辑经 `VectorLegDecimalArithmeticTest` 单元锁＋hbt `MysqlTypeSystemDivideTest` 语义护栏覆盖，但**尚无向量态非终止小数的活体 e2e 例**。此作 I-DEBT-67 下一条待补验子项挂账（`group by` 带键态十进制均值写回 `GroupByKeyWithAggPlan` 面亦未单独活体覆盖）。主目标（无组 avg 塌型）已真关。

### 7.4 残留子项活体验收与摘账（2026-10-28·本会话·I-DEBT-67 全绿关账）

承接 §7.3 挂账的两残留子项，本轮**起活体 infra 补验并落严格回归锁**，不砍产品码（第二刀②③④ 已覆盖，本轮为验证 + 锁定）。

- **防下推假绿（关键前置）**：临时探针 `explain` 锤定——全表 `SELECT avg(id)` 的向量化计划为
  `MycatProject(avg(id)=[CAST(/(CAST(CASE(=($1,0),null:BIGINT,$0)):DECIMAL(19,4),$1)):DECIMAL(19,4)])`，
  下推后端的仅 `SELECT SUM(id),COUNT(*)`（跨 4 物理表 union all）——**除法确在本地向量 project 做**（表按 `mod_hash(id)` 跨 2 dbpartition，全局/逐组 avg 无法单条下推，avg-of-avg 错）。该 `DECIMAL(19,4)` project 形态仅第二刀后存在（砍前同形态投影 DOUBLE），故新锁无第二刀必红。
- **活体实测（真实运行值）**：
  - 全表非终止/终止混合数据（sum=48/count=5）→ `avg(id)=9.6000`（scale4 补零 ✓）。
  - `GROUP BY gid` → `gid=1` 组 `{1,2,4}` sum7/count3 → **`avg(id)=2.3333`**（非终止，落到 scale4 十进制而非 double 长尾 ✓）；`gid=2` 组 `{10,31}` → `avg(id)=20.5000`（终止 ✓）。**带键十进制均值经 `GroupByKeyWithAggPlan` 写回正确**。
- **落盘锁**：`example/.../vector/VectorTest.testVectorAvgDecimalResiduals`（新 `@Test`）——以分片表构造非终止 + 带键两组，严格断言 `[{avg(id)=9.6000}]` 与排序后 `[{gid=1, avg(id)=2.3333}, {gid=2, avg(id)=20.5000}]`（group 输出 hash 序不定，按 `gid` 排序去 flaky）。
- **验绿**：`mvn -o -pl example test -Dtest=VectorTest` → `Tests run: 2, Failures: 0, Errors: 0`（`testBase` + 新残锁共存全绿）。
- **本轮 delta 面**：仅新增 example 一个测试方法，**零产品码改动**（第二刀已覆盖两子项，运行态实证即证据）；executor/hbt 沿用 §7.3 已落 6/0/0、48/0/0。
- **舍入口径说明**：`2.3333` 一例末位非边界（5th 位=3，HALF_UP/HALF_EVEN 同果），故本锁验的是 **scale 保真 + 非终止不泄漏 double**；**舍入方向（HALF_UP）已由 hbt `MysqlDivideFunctionTest`/`MysqlTypeSystemDivideTest`（`0.15625→0.1563` 边界）与 `VectorLegDecimalArithmeticTest` 单独锁死**，不在此构造 .xxx5 反例重复。
- **结论**：I-DEBT-67 §7.3 两残留子项均已活体验证 + 回归锁定，**本债彻底摘账**（四刀①②③④ + 两残留子项全链路 e2e 实证）。
