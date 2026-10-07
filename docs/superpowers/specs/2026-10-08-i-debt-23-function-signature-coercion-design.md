# I-DEBT-23 函数签名族（bin / CONV / DATE_ADD）MySQL 宽松跨族 coercion

> 状态：**23a+23b 已实现并验证 Evidenced-Done（bin/CONV/CRC32 转绿、hbt47/router15 零回归）；23c DATE_ADD codegen 续办**。源自 I-DEBT-7C 第二轮全量重基线 sweep（2026-10-07）根族分诊 ⑥
> `sql.PushDownFunctionShardingTableTest ×3E`。本 spec 逐行坐实根因并给出最小 blast 正解。

## 1. 现象（凭 surefire + 服务端实测签名）

`example` e2e（连外部 8066 proxy + Docker `m1-mysql-a/b` 后端）中三个函数用字面量调用即崩：

| SQL（节选） | 失败层 | 真实签名 |
|---|---|---|
| `SELECT BIN('12')`（testCharFunction:79） | 校验期 | `No match found for function signature bin(<CHARACTER>)` @`SqlFunction.java:341` |
| `SELECT HEX(255), CONV(HEX(255),16,10)`（testCharFunction:94） | 校验期 | `convt` 首参 CHARACTER 期望 mismatch |
| `SELECT CONV(16,10,16)`（testMathFunction:229） | 校验期 | 同上（NUMERIC→CHARACTER 首参） |
| `SELECT DATE_ADD('2018-05-01',INTERVAL 1 DAY)`（testTimeFunction:265） | **codegen 期** | `implementSafe` 返 `null` → "while converting DATE_ADD(...)" |

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

### 2.2 codegen 期（DATE_ADD）——implementSafe 形态不足

`DATE_ADD` 走 `MycatCalciteMySqlNodeVisitor` 的 `case "DATE_ADD"` 直接 `createCall`（operand 校验用
`OperandTypes.VARIADIC` 恒过），失败在 `DateAddFunction.implementSafe`：只处理
`(String,Duration)` / `(Duration,String)` 两形态，其余 **`return null`**（→ 上层 "while converting"）。
兄弟类 `DateSubFunction.implementSafe` 已多处理 `Period` / `LocalDateTime` 组合且末尾抛
`UnsupportedOperationException`。差异即 DATE_ADD 缺 `DAY`/`YEAR` 等 unit → 对应 `Duration`/`Period` 的落地形态。

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
