# I-DEBT-23 函数签名族（bin / CONV / DATE_ADD）MySQL 宽松跨族 coercion

> 状态：**23a+23b 已实现并验证 Evidenced-Done（bin/CONV/CRC32 转绿、hbt47/router15 零回归）；23c DATE_ADD/ADDDATE/DATE_SUB INTERVAL 区间构造+codegen 已收口 Evidenced-Done（回归锁 `MycatIntervalLiteralCoercionTest` 3/0/0、hbt50/router15 零回归）；**23e `CURTIME()+0` Duration→TIME 已收口 Evidenced-Done（`MycatRelDataTypeFactory.createJavaType` 对称补 `Duration.class→TIME`、回归锁 `MycatDurationTimeMappingTest` 3/0/0、hbt58/router15 零回归）**。新开 **23f `DAYOFWEEK(<VARCHAR>)`**（testTimeFunction 修好 23e 后新暴露的异根族：内置日期函数 STRING→DATETIME 校验 coercion）。（源自 I-DEBT-7C 第二轮全量重基线 sweep（2026-10-07）根族分诊 ⑥
> `sql.PushDownFunctionShardingTableTest ×3E`。本 spec 逐行坐实根因并给出最小 blast 正解。

## 1. 现象（凭 surefire + 服务端实测签名）

`example` e2e（连外部 8066 proxy + Docker `m1-mysql-a/b` 后端）中三个函数用字面量调用即崩：

| SQL（节选） | 失败层 | 真实签名 |
|---|---|---|
| `SELECT BIN('12')`（testCharFunction:79） | 校验期 | `No match found for function signature bin(<CHARACTER>)` @`SqlFunction.java:341` |
| `SELECT HEX(255), CONV(HEX(255),16,10)`（testCharFunction:94） | 校验期 | `convt` 首参 CHARACTER 期望 mismatch |
| `SELECT CONV(16,10,16)`（testMathFunction:229） | 校验期 | 同上（NUMERIC→CHARACTER 首参） |
| `SELECT DATE_ADD('2018-05-01',INTERVAL 1 DAY)`（testTimeFunction:265） | **sql2rel 期**（非 codegen） | `RexBuilder.makeIntervalLiteral→RexLiteral.<init>:230` 抛 `IllegalArgumentException`（影子区间 Duration vs fork BigDecimal）——**已收口 23c** |

## 2. 根因（逐行坐实，非凭记忆）

### 2.1 校验期（bin / CONV）——category 门

Mycat 自定义函数经 `MycatCalciteMySqlNodeVisitor` 落到 `SqlUnresolvedFunction(category=USER_DEFINED_FUNCTION)`
进入 Calcite 校验（BIN/CONV 不在 visitor 的显式 `case` 白名单 → default 分支）。`SqlFunction.deriveType`：

1. **line 254** `SqlUtil.lookupRoutine(..., coerce=false)`：`BinFunction.bin(Long)` 经
   `MycatSqlDefinedFunction.GetOperandTypes.invoke()` 严格按 Java 参数类型派生 operand 族为
   NUMERIC/BIGINT，实参 `'12'` 是 CHARACTER，`canCastFrom(BIGINT, CHARACTER)`（严格）= false → `function=null`。
2. 进入 **line 301 `if(function==null)`** 块 → **line 302** `typeCoercionEnabled=true` →
   **line 304** `lookupRoutine(..., coerce=true)`：**命中** `BinFunction`（coercion 查找放行）。
3. **line 314 category 门**：仅当 `function.category == USER_DEFINED_FUNCTION / USER_DEFINED_TABLE_FUNCTION`
   才调 `typeCoercion.userDefinedFunctionCoercion(...)` 并 **line 317 `break validCoercionType`**。
   但 `BinFunction extends MycatStringFunction extends MycatSqlDefinedFunction`，其 category 被子类
   显式声明为 **STRING**（`MycatStringFunction` 构造传 `SqlFunctionCategory.STRING`），门不放行。
4. **line 335** `if(function==null && lenientOperatorLookup)`：因 line 304 已把 `function` 置非 null，
   `lenientOperatorLookup=true` 的兜底**不触发**（这正是"上轮试 implicitCast 无效"的真相——不在此调用链）。
5. **line 341** 无条件 `throw handleUnresolvedFunction(...)` → `validatorUnknownFunction`。

**关键旁证（决定无需动 coercion map）**：
- `coerceOperandType`（`AbstractTypeCoercion:119`）判 `needToCast(..., SqlTypeCoercionRule.lenientInstance())`
  ——用的是 **静态 LENIENT_INSTANCE**，而非校验器配置的 map。
- `SqlTypeCoercionRule` LENIENT 构造（line 374-381）：BIGINT/INTEGER/… 目标的可来源集合含
  `CHAR`/`VARCHAR`（line 367-368）。⇒ **CHARACTER→BIGINT 的 CAST 可被 `userDefinedFunctionCoercion` 施加**。
- 因 line 304 coerce=true 查找已命中（否则落 line 335 lenient 兜底、不会 throw），
  **`MycatCalciteSupport.getValidatorConfig` 的 coercion map 无需补 STRING↔NUMERIC**。

**⇒ 唯一阻塞点 = line 314 的 category 门**。

### 2.2 23c DATE_ADD——真根因修正（凭服务端完整栈实测，非 implementSafe）

**订正**：上轮将 DATE_ADD 失败归为“codegen 期 `implementSafe` 返 null”**有误**。服务端完整栈实锤失败发生在 **sql2rel 期**（远早于 codegen）：
```
RuntimeException: while converting DATE_ADD(?, INTERVAL '10' DAY)
  ReflectiveConvertletTable.lambda$registerOpTypeMethod$3:147
  StandardConvertletTable.convertFunction:982 → convertOperands:1306
  SqlToRelConverter$Blackboard.visit(SqlLiteral):6103
  SqlNodeToRexConverterImpl.convertLiteral:158
  RexBuilder.makeIntervalLiteral:1767 → makeLiteral:1422
Caused by: IllegalArgumentException @ RexLiteral.<init>:230 (Preconditions.checkArgument)
```
即 `INTERVAL '10' DAY` 这个 `SqlIntervalLiteral` 转 `RexLiteral` 时，构造器 `valueMatchesType(value, INTERVAL_DAY, true)` 不通过。

**逐行定根**：运行时加载的是 **hbt PARK 影子 `org.apache.calcite.rex.RexLiteral`**（非 fork），其 `valueMatchesType` 对
DAY-TIME 区间要求 `value instanceof Duration`（影子 line 368），而 fork `RexBuilder.makeIntervalLiteral` 一贯传 **`BigDecimal`**
（Calcite 升级后区间内部表示 Duration→BigDecimal）→ 校验失败抛错。且影子 `getValueAs` DAY-TIME 分支（line 1200）`Duration duration = (Duration) value`
也硬假设 Duration，无 `Duration.class` 目标分支——即影子整个区间表示与 fork 不兼容。**DAY-TIME 区间当前 100% 不可构造**（全经过 throw），
YEAR-MONTH 因影子要 `instanceof Number`（BigDecimal 是其子类）**已可工作**（故 DATE_SUB…YEAR 不阻）。

**第二障碍**：日期首参被 Mycat 参数化为动态参 `?`，codegen `translateLiteral` 对动态参产出的 Expression 可能非 `String`，
即使区间修好，`implementSafe` 的 `one.getType()==String.class` 仍可能不成立→返 null。属 23b 同类“动态参型推”问题。

**裁定（不冒进）**：23c 需将 load-bearing PARK 影子 `RexLiteral` 的 DAY-TIME 区间表示从 legacy `Duration` 迁至 fork 的
`BigDecimal`（改 `valueMatchesType` + `getValueAs` 的 unit→Duration 换算），叠加动态参日期型推——属多层架构迁移，
需与 I-DEBT-12/13/14 同等的专轮 instrumented 流程（先 hbt 微探针钉死区间 BigDecimal/unit 语义，再改）。
盲改有产出错日期（假修）风险，故 **曾挂 23c open**（已于 2026-10-08 专轮收口，见 §2.3）。

### 2.3 23c 收口实施（2026-10-08）+ 23e 新开

**先立 hbt 微探针（免重编 jar/免 8066、秒级）钉死区间语义**：经 `SqlLiteral.createInterval(1,"10",DAY)`→`getValueAs(BigDecimal)=864000000`（**毫秒数**）；`'1' YEAR→12`（**总月数**）；`'2' HOUR→7200000`。确认 fork 约定与探针复现的 `makeIntervalLiteral(BigDecimal,DAY)` 在影子下 `IllegalArgumentException`。

**修法（hbt PARK 影子 `org/apache/calcite/rex/RexLiteral.java` 3 处、单调零回归—DAY-TIME 区间此前 100% 不可构造→无绿依赖）**：
1. `valueMatchesType` DAY-TIME：`value instanceof Duration` → `value instanceof Duration || value instanceof Number`（对齐 fork，且与影子自身已按 Number 处理的 YEAR-MONTH 一致）。
2. `getValueAs` DAY-TIME 块顶部归一化：`Duration duration = (value instanceof Duration) ? (Duration) value : Duration.ofMillis(((Number) value).longValue());` 并新增 `if (clazz == Duration.class) return clazz.cast(duration);`（此前无 Duration.class 分支，旧值形态依赖 fast-path）。
3. `INTERVAL_YEAR getValueAs(Period)`：`Period.ofYears(月数)` → `Period.ofMonths(月数)`（探针暴露潜伏 bug：1 年会被错算成12 年）。

“第二障碍”（动态参日期 `?` 型推）经实测 **不成立**：影子 `RexToLixTranslator.translateLiteral` 对日期字面量仍产出 `String` Expression，`ADDDATE(...INTERVAL 10 DAY)` 端到端 checkValue 转绿（值正确），证明区间层修复即可走通。

**证据**：新增回归锁 `hbt/src/test/java/io/mycat/calcite/MycatIntervalLiteralCoercionTest.java` **3/0/0**（DAY→10 天 / HOUR→2 时 / YEAR→P12M）；重编 hbt→server fat jar + JDK21 8066 实跑 `PushDownFunctionShardingTableTest`：`ADDDATE(...INTERVAL 10 DAY)` checkValue 转绿（testChar/testMath 保持）；护栏 hbt **50/0/0**、router **15/0/0** 零回归。⇒ **23c Evidenced-Done**。

**新开 23e（`testTimeFunction` 现红靶，异根）**：修好后测试推进至 `SELECT (CURTIME() + 0)` → `Cannot apply '+' to '<OTHER> + '<INTEGER>'`。Mycat 已配 `TIME/DATE/TIMESTAMP→NUMERIC` 隐式转（`MycatCalciteSupport.getValidatorConfig` 的 `implicitCast:206-211`），但 `CurTimeFunction.curTime()` 返 `java.time.Duration`，其 java→SQL 反查（`MycatRelDataTypeFactory` 缺 `Duration→TIME` 逆向映射）落到 `OTHER`（`getJavaClass(OTHER)=String`，不可 coerercible）→命中不到 TIME 分支。正解=补 `Duration→TIME` java 映射（需与 I-DEBT-13 `OTHER` coercion 登记交互、blast radius 专轮评估，非 crisp 单点，不冒进）。265/266（DATE_ADD DAY / DATE_SUB YEAR）当前被 262 阻断，探针已证语义正确，待 23e 后一并端到端复绿。

**23e 收口实施（2026-10-08，凭实测推翻“非 crisp”初判）**：实测确认失败为**校验期类型派生**（非 codegen）：`SELECT CURTIME()+0` → `Cannot apply '+' to '<OTHER> + <INTEGER>'`。根因坐实为 `MycatRelDataTypeFactory` 仅覆写了 SQL→Java 方向（`getJavaClass(TIME)=Duration`，I-DEBT-12 方向 A），**Java→SQL 正向 `createJavaType(Duration.class)` 未接** → Calcite 默认落 `OTHER`。**blast radius 实测收敛（非初判的“专轮评估”）**：枚举全部 9 个 `static Duration` 返回函数（`curTime`/`utcTime`/`makeTime`/`secToTime`/`timeDiff`×3/`time`）**语义均为 TIME 值、无一为 INTERVAL** → 对称补 `Duration.class→TIME` 零误类型风险。**修法（单调、零回归）**：`createJavaType` 仅拦截 `Duration.class == aClass` 返回 `createSqlType(TIME)`，其余原样委派；与 `getJavaClass` 构成双向对称。`Duration→TIME` 后 `TIME + NUMERIC` 命中已有 `implicitCast(TIME,NUMERIC)=DOUBLE`，`+0` 通过。**证据**：新回归锁 `hbt/src/test/java/io/mycat/calcite/MycatDurationTimeMappingTest.java` **3/0/0**（正向 Duration→TIME / 双向对称 / DATE·TIMESTAMP·OTHER·Integer 不受波及）；重编 hbt→server fat jar + JDK21 8066 实跑 `PushDownFunctionShardingTableTest#testTimeFunction`：`<OTHER> + <INTEGER>` 错误消失、推进至 line 272 新暴露的 **23f `DAYOFWEEK(<VARCHAR>)`** 异根族（内置日期函数 STRING→DATETIME 校验 coercion，独立挂账、非 23e 面）；护栏 hbt **58/0/0**（+3 新锁；`MycatTemporalJavaClassTest` SQL→Java 向不回归）、router **15/0/0**。⇒ **23e Evidenced-Done**（`CURTIME()+0` 面）；testTimeFunction 整体仍红于 23f。



## 3. 正解设计（最小 blast，单调、零回归论证）

### 3.1 校验期：放宽 line 314 门放行 MycatSqlDefinedFunction

在 fork `calcite-core` 的 `SqlFunction.java` `deriveType` category 门增加对 Mycat 自有函数的放行：

```java
if (function != null) {
  TypeCoercion typeCoercion = validator.getTypeCoercion();
  if ((function.category == SqlFunctionCategory.USER_DEFINED_FUNCTION
      || function.category == SqlFunctionCategory.USER_DEFINED_TABLE_FUNCTION
      // MYCAT CHANGE (I-DEBT-23)
      || (function instanceof MycatSqlDefinedFunction
          && function.getOperandTypeChecker() instanceof SqlOperandMetadata))
      && typeCoercion.userDefinedFunctionCoercion(scope, call, function)) {
    break validCoercionType;
  }
}
```

- **`instanceof MycatSqlDefinedFunction`**：作用域严格限 Mycat 自定义函数，**不触碰任何 Calcite 内建函数**。
- **`&& getOperandTypeChecker() instanceof SqlOperandMetadata`**：防御性前置条件——
  `userDefinedFunctionCoercion`（`TypeCoercionImpl:788`）对 checker 做 `(SqlOperandMetadata)` 强转，
  对用 `OperandTypes.VARIADIC`/`OperandTypes.STRING`（非 metadata）构造的函数会 ClassCastException；
  加此守卫后这类函数维持原行为（走不进 coercion）。VARIADIC 函数本就 strict 命中、从不进本块，实为双保险。
- **单调性（零回归论证）**：改动仅在 `if(function==null)` 块内。当前**绿**的用例（strict line 254 命中）
  根本不进该块；进入本块者现状一律 throw@341，改后仅在 coercion 可行时转成功，
  `userDefinedFunctionCoercion` 返 false 则 `&&` 短路、仍落 throw。⇒ **只把"抛错"变"成功"，不可能把绿变红**。

### 3.2 codegen 期：补齐 DateAddFunction 的 interval 形态

对齐 `DateSubFunction`：补 `(String,Period)` / `(LocalDateTime,Period)` / `(LocalDateTime,Duration)` 等
分支；无法处理的形态抛 `UnsupportedOperationException`（与 DateSub 一致，不再静默 `return null`）。
按测试覆盖 `INTERVAL n DAY` / `YEAR` / `SECOND` 落地。

> 3.2 若实测发现 interval operand 的类型映射需 visitor/codegen 联动（如 DAY→Duration vs Period 的判定），
> 则以实测签名为准调整；DATE_ADD 与 bin/CONV **异层异根**，可分两个提交节奏推进。

## 4. 回归门（Evidenced-Done 判据）

- **构建**：停 8066 → `mvn -B -o install -DskipTests -pl config,calcite-core,hbt,router,server -am`
  → `restart-server.ps1`（`--add-opens=java.base/java.nio`、`-Duser.timezone=Asia/Shanghai`、`-Dfile.encoding=UTF-8`）。
- **靶（须全绿）**：`PushDownFunctionShardingTableTest#testCharFunction/testMathFunction/testTimeFunction`
  三方法；对照 `PushDownFunctionNormalTableTest` 同族不回归。
- **护栏（不得新红）**：`mvn -o -pl hbt test`（validator/影子全量）、`mvn -o -pl router test`；
  既有 ~28 绿 example 产品基线零回归。
- **破则回退**：任一护栏新红 → revert 本改动、保持 I-DEBT-23 spec-ready 归类，不假修、不毁绿基线。

## 5. 风险与边界

| 项 | 评估 |
|---|---|
| fork 上游漂移 | line 314 门是 fork 已有 "Mycat CHANGE" 区域，改动 1 处、带注释标记，可控 |
| 其它函数误伤 | `instanceof MycatSqlDefinedFunction` 精确限定；Calcite 内建函数行为零变化 |
| ClassCastException | metadata 守卫兜底 |
| DATE_ADD codegen | 独立子项，异层；若牵出 interval 类型映射深水区则单列续债，不强推 |
| 7B 连接池 | 本族靶不依赖挂点类；若 testTimeFunction 撞池耗尽（SqlFunctionTest 症状），以 PushDown 类实测为准 |

## 6. 债务归属

本 spec 收编 I-DEBT-7C 根族 ⑥；提交绿后在看板立独立行 **I-DEBT-23**（Evidenced-Done 附靶/护栏实测）。
DATE_ADD 若分次收敛，则 I-DEBT-23a（bin/CONV 校验门）+ I-DEBT-23b（DATE_ADD codegen）。
