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
