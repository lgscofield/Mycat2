# I-DEBT-39·tranche①（宽）— Mycat 本地除法 `/` 语义对齐 MySQL 设计

- **日期**：2026-10-20
- **债务**：I-DEBT-39·残（除法语义），本票只做 **tranche①**，采**宽口径**（含除零→NULL）
- **北极星对齐**：客户端眼中无缝替代 MySQL——`SELECT a/b` 是最高频的算术表达式，当前 Mycat 本地计算**静默返错**（整除截断），属最高客户可见度的正确性破面。层级 **A**（招牌硬门本体：CRUD/SQL 正确性）。
- **裁定**：架构级（跨校验期类型派生 + 本地 codegen/求值 + 对拍 oracle），走 Spec→Plan→实施。

---

## 1. 问题（当前实证，非记录臆测）

活库 8066 探针（`Probe39Live`，information_schema，persona MYSQL_5_7）实测：

| SQL | Mycat 现状 | 真 MySQL | 定性 |
|---|---|---|---|
| `select 10/3` | `3`（Integer） | `3.3333`（DECIMAL） | **静默返错**（截断） |
| `select 7/2` | `3`（Integer） | `3.5000` | **静默返错** |
| `select 10 DIV 3` | `3` | `3` | 正确（整除本就是 DIV 语义） |
| `select 10.0/4` | `2.5`（BigDecimal） | `2.5000` | 值对（scale 显示差，见 §4） |
| `select 1/0` | 抛 `ArithmeticException: / by zero` | `NULL` | 报错 vs 返空（本票宽口径一并治） |

### 1.1 根因链（代码证据）
- Mycat 把 MySQL `/` 映射到 Calcite 标准 `SqlStdOperatorTable.DIVIDE`（`MycatCalciteMySqlNodeVisitor:1158`），`DIV`→`DIVIDE_INTEGER`（L1162）。
- `DIVIDE` 返回类型推断 `ReturnTypes.QUOTIENT_NULLABLE = DECIMAL_QUOTIENT_NULLABLE .orElse(ARG0_INTERVAL_NULLABLE) .orElse(LEAST_RESTRICTIVE)`（`SqlStdOperatorTable:300`）。
- `DECIMAL_QUOTIENT` 经 `typeFactory.getTypeSystem().deriveDecimalDivideType(t1,t2)`（`ReturnTypes:980`）；对 **INTEGER×INTEGER**，默认类型系统返回 null（该法仅处理"至少一个是 DECIMAL"）→ 落 `LEAST_RESTRICTIVE(INTEGER,INTEGER)=INTEGER`。
- 类型一旦 INTEGER，codegen 选 `SqlFunctions.divide(long/Integer…)` 整数除法（`SqlFunctions:2892` `b0.longValue()/b1.longValue()`）→ 得 3。
- **根因=类型派生层，不在算术本身。**

### 1.2 现成接缝（前人起头、已装配、只是没接电）
`MycatCalciteSupport` 有两处 **已 wire 生效、仅 DIVIDE 体被注释** 的接缝：
- `MycatTypeSystem.deriveDecimalDivideType`（L672-684，注释体本意"numeric→浮点"）；该类经 `configBuilder.typeSystem(TypeSystem)`（L318）装配，兄弟 `deriveAvgAggType`（L664）启用态即证其生效。
- `MycatStandardConvertletTable` DIVIDE 分支（L693-707）；经 `convertletTable(...)`（L564）装配。

---

## 2. 目标语义（本票要落成的样子）

对齐 MySQL 查询投影里的 `/`：

1. **精确数值 ÷ 精确数值**（整型/DECIMAL 参与）：结果 **DECIMAL**，**不整除截断**，值与真 MySQL 逐位相符。
   - scale 采用 MySQL 公式：`结果 scale = 被除数 scale + div_precision_increment`。本票把 `div_precision_increment` **当常量 4**（MySQL 默认），即整型÷整型→scale 4（`10/3`=`3.3333`），`DECIMAL(5,1)÷整型`→scale 5。
   - 舍入：MySQL 除法用**四舍五入(half-up)** 到目标 scale。
2. **含近似型（FLOAT/REAL/DOUBLE）操作数**：结果 DOUBLE（与后端一致；此路径现状已由 `LEAST_RESTRICTIVE` 落 DOUBLE，本票**不改**，仅确保归一时不被误扳 DECIMAL）。
3. **除零**：本地求值时**返回 NULL**（不再抛异常），与 MySQL 查询语义一致。NULL 语义来源=§4 的除零守卫，**非**代理短路绕过下推（见 §6）。
4. **NULL 传播**：任一操作数 NULL→结果 NULL（现状已对，回归守住）。
5. **`DIV`（`DIVIDE_INTEGER`）不动**：仍整除截断。

## 3. 明确不在本票范围（诚实挂账，防伪绿）

- **warning 子系统**：MySQL `1/0` 伴生 `Division by 0` warning，本票**不发**（tranche③）。
- **精度溢出→NULL**：结果超 DECIMAL 上限（65 位）时 MySQL 返 NULL+warning；本票只在典型规模内保证对拍相符，溢出边角列为残留（tranche③/④）。
- **DML 语境 `ERROR_FOR_DIVISION_BY_ZERO`**：作用于 INSERT/UPDATE 除零，与 SELECT 投影正交（tranche④）。
- **非"完全一致"声明**：本票达成"主干值 + 除零值 逐位一致，仅差 warning 通道"，不宣称绝对全等。

## 4. 方案（approach）与关键取舍

### 4.1 修复落点=类型派生钩子（否决 convertlet-on-SqlKind）
- **选** `MycatTypeSystem.deriveDecimalDivideType`：`DIVIDE_INTEGER` 用独立推断 `INTEGER_QUOTIENT_NULLABLE`，**不经** `deriveDecimalDivideType`，故在此覆写**天然不误伤 `DIV`**。
- **否决**旧注释的 convertlet 路子：`DIVIDE` 与 `DIVIDE_INTEGER` **同属 `SqlKind.DIVIDE`**（`SqlStdOperatorTable` L294/L353），按 SqlKind 判会连 `DIV` 一起改浮点→破 §2.5。若用 convertlet 必须按算子实例判，复杂度高于类型钩子，不取。
- 覆写体：两操作数皆整型时，返回 `DECIMAL(p, 4)`（p 取足够大的派生精度，实施期定值并以对拍校准）；其余沿用 `super.deriveDecimalDivideType`（含"至少一 DECIMAL"的既有正路）。近似型不进此分支（保持 DOUBLE）。

### 4.2 本地求值/出码=十进制除法 + 除零→NULL
仅有 DECIMAL 声明型不足：codegen 按**操作数运行时类型**分派，整型操作数仍会撞整数 `divide`。故须二选一（实施期定，spec 记原则）：
- (a) 让整型操作数在出码前**提升为 BigDecimal**（借 `ReduceDecimalsRule` 十进制展开或等价路径），十进制 `divide(BigDecimal,BigDecimal, scale, HALF_UP)` 出值；或
- (b) Mycat 侧提供**自定义除法实现/映射**（类比既有 cast 矩阵），签名收数值、内部 `BigDecimal` 除法 + scale 规则 + 除零 catch→null。
- **除零→NULL 落在此处**：`SqlFunctions.divide(BigDecimal,BigDecimal)`（L2899-2903 `b0.divide(b1, DECIMAL64)`）在 b1=0 时抛 `ArithmeticException`——本票要在选定的求值路径上把"除数为零"catch/预判为**返回 NULL**。常量折叠路径（`RexExecutor`/`RexInterpreter` 亦走 DIVIDE）须与出码路径**同语义**（同一除法实现），否则 `select 1/0` 与 `select col/0` 行为分叉。

### 4.3 scale/精度与"逐位一致"以真库对拍为唯一裁判
不靠纸面推 MySQL 公式自证；§5 的**不可下推对拍 oracle** 逐格锤定，偏差回 §4.1/§4.2 调参（scale 常量、舍入模式、精度上限）至全绿。

## 5. 验收（判据，硬门）

1. **静态/离线**：新增 JUnit 锁直调选定除法实现——`10/3`→`3.3333`、`7/2`→`3.5000`、`1/0`→`null`、`10 DIV 3`→`3`、`double/整`→DOUBLE 且等值、NULL 传播。
2. **回归（blast 兜底）**：hbt 全量绿；**plan 串断言类测试**（如 `DistinctTest` `avg(f)=[CAST($0):DOUBLE]`）与 AVG/STDDEV/VARIANCE/REGR 相关测试实测校正（§1/§6 中间态扰动）；internalexample codegen 锁全绿。
3. **活库 e2e 对拍（关键·防下推遮蔽假绿）**：构造**不可下推形态**（i_s 虚表投影 / 常量折叠 / 强制本地算的包裹），Mycat 结果与真 MySQL(5.7 docker my57-3306) **逐格、含类型/scale** 全等，除零两侧皆 NULL。verdict=PASS。
4. 探针/临时件跑完即删，产品码零残留。

## 6. 连带面与风险登记

- **内部复用 DIVIDE**（AVG/统计聚合 `AggregateReduceFunctionsRule` L452/613/817/883/907、`AggregatePushContext:157`）：外层多会 CAST 回声明型，方向与 §2 一致（这些本应浮点/十进制），但仍以 §5.2 实测为准，禁臆断无扰。
- **下推路不动**：可下推到真 MySQL 的 `/` 交后端算（本就对）。本票只治本地求值路。除零→NULL 是"本地把 `1/0` 算成和后端一样的 NULL"，**不是**代理侦测除零而不发下游——是否下推由既有规划器决定，本票不新增短路。
- **JDBC/列类型元数据**：结果列声明 DECIMAL→对外列型与后端一致（无缝的一环），对拍含列型比对。
- **`div_precision_increment` 当常量**：暂不接会话变量（可变该量属边角，tranche③/④ 随 warning/会话参数一起收）。

## 7. 交付边界（本票关闭判据）

`/` 精确数值不截断且与真 MySQL 逐位相符 + 除零→NULL（值对齐）+ `DIV` 与近似型路不受损 + §5 三重验证绿。**warning/溢出/DML 除零**明确留后续 tranche，登记于 DEBT-QUEUE I-DEBT-39·残行不伪关。
