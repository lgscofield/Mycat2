# I-DEBT-31 + I-DEBT-32：动态参 defeat Calcite 校验期 coercion 的系统性根收口

> 状态：**设计已获用户批准（方案 A·同轮），待 writing-plans 拆解实施**。
> 来源：Phase3 逐族分诊挂账的 I-DEBT-31（`VARBINARY=VARCHAR` 比较，提交 9a753fc67）与 I-DEBT-32（`DAYOFWEEK(<VARCHAR>)` 函数 operand，提交 fb56a172d）。本轮经用户拍板，将两族（+泛化 23a 认知）合并为一个"动态参 defeat coercion"系统性根 spec 轮，一次收口、共享回归门。

> **实施订正（2026-10-08，凭 TDD RED 实测栈，覆盖本 spec 下文“两修点”叙述）**：下文 §3 原设“两处分修点”，实施时实测证伪——`DAYOFWEEK(<VARCHAR>)` 校验期解析为 Calcite **内置** `SqlDatePartFunction`→`FamilyOperandTypeChecker:168`→`TypeCoercionImpl.builtinFunctionCoercion:777`→`AbstractTypeCoercion.coerceOperandType`，**并非**走 Mycat 自定义函数的 `mycatCoerceOperands`（修点② 打偏）。故 I-DEBT-31（比较面经 `binaryComparisonCoercion`）与 I-DEBT-32（内置函数面经 `builtinFunctionCoercion`）**汇同一闸门** `coerceOperandType:108`⇒**归一为单修点①**（覆写 `coerceOperandType`），修点② `mycatCoerceOperands` 收窄已 git 回退（无效且带 23a 承重守卫 blast）。详见账本 `Ruling` 与看板变更日志。
>
> **残留正交挂账**：收口后 `testComplexQuery` :391 blob 比较转绿，但方法整体推进至 :403 `EXISTS` 子查询崩 `NoSuchMethodError RelMdUtil.isRelDefinitelyNotEmpty`——系 I-DEBT-3 hbt PARK 影子签名漂移（非本 spec coercion 面），不扩面修。

## 0. 一句话结论

Mycat 把 SQL 字面量参数化成 `?`（`SqlDynamicParam`），而上游 Calcite 校验器**一刀切地"永不对动态参施加隐式 CAST"**；这个约定在 Calcite 原生场景成立（校验期动态参类型未知），但在 Mycat 里**不成立**——Mycat 校验器已能派生出每个 `?` 的实际 SQL 类型。二者错位导致所有"跨族比较 / 跨族函数入参"在真正需要包 CAST 时被静默拒绝，抛出 `Cannot apply '=' to '<VARBINARY> = <VARCHAR>'`、`Cannot apply 'DAYOFWEEK' to '<VARCHAR>'` 之类错误。正解是**让"类型可知的动态参"重新参与 coercion**，且只在 Mycat 侧改、不动上游逻辑文件。

## 1. 现象（凭当前实测签名）

| 症状 | 位置 | 真实错误签名 |
|---|---|---|
| VARBINARY 列 = 字符串字面量 | `example` `SqlFunctionTest#testComplexQuery:391`（`... WHERE blob='张三'`） | 校验期 `Cannot apply '=' to '<VARBINARY> = <VARCHAR>'` |
| 日期函数吃字符串字面量 | `example` `PushDownFunctionShardingTableTest#testTimeFunction:272`（`DAYOFWEEK('2018-01-01')` 类） | 校验期 `Cannot apply 'DAYOFWEEK' to '<VARCHAR>'`（严格族门拒） |

两症状共享同一认知根，但落在**两条独立代码路径**（内置比较运算符 vs Mycat 自定义函数 operand），故需**两处分修点**。

## 2. 根因（逐行坐实）

### 2.1 唯一的"拒动态参"闸门（比较面）

`calcite-core/.../sql/validate/implicit/AbstractTypeCoercion.java`：

- `coerceOperandType(scope, call, index, targetType)` **L96-129**：
  ```java
  SqlNode operand = call.getOperandList().get(index);
  if (operand instanceof SqlDynamicParam) {
    // Do not support implicit type coercion for dynamic param.
    return false;                 // ← L108-111 系统性拒点
  }
  ...
  RelDataType operandType = validator.deriveType(scope, operand);
  if (!needToCast(scope, operand, targetType, lenient)) return false;
  RelDataType targetType1 = syncAttributes(operandType, targetType);
  SqlNode desired = castTo(operand, targetType1);
  call.setOperand(index, desired);
  updateInferredType(desired, targetType1);
  return true;
  ```
- 比较链路：`ComparableOperandTypeChecker:70` → `TypeCoercionImpl.binaryComparisonCoercion` **L242-247**：算出 `commonTypeForBinaryComparison`（Mycat 覆写版 `MycatCalciteSupport:226-227` 对 binary 任返 VARBINARY，共型可算）→ `coerceOperandsType` **L138-147** → 逐 operand `coerceOperandType`。
- 因 `'张三'` 已被 Mycat 参数化为 `?`（`SqlDynamicParam`），`coerceOperandType` 在 L108 直接 `return false` → **从不包 CAST** → `<VARBINARY>=<VARCHAR>` 原样进严格校验并 throw。

> 注：L182-185 的同类拒动态参分支属 `coerceColumnType`（UNION/INSERT 列对齐面），非本族症状路径，本轮不动。

### 2.2 Mycat 校验期其实已知动态参类型

`hbt/.../calcite/DrdsRunnerHelper.java` 的 `getSqlValidator` 匿名 `SqlValidatorImpl` 子类：

- `resolveDynamicParam(expr)` **L250-286**：对 `SqlDynamicParam` 按三级回退派生 SQL 型——① `drdsSql.getTypeNames()`（suggested）；② `ParamHolder.getTypes()`；③ 参数值 Java 型（`String→VARCHAR` / `Number→DECIMAL` / `Float/Double→…`）；全未知才落 `ANY`（L283）。
- `deriveType` **L233-240** 与 `getValidatedNodeType` **L288-296** 都经 `resolveDynamicParam` 短路。

**∴ Calcite"校验期动态参类型未知"的前提在 Mycat 被证伪**——这正是上游约定不适用的根。

### 2.3 函数 operand 面：跳 CAST 守卫过宽

`calcite-core/.../mycat/MycatSqlDefinedFunction.java`（**Mycat 自研类、非上游 Calcite**）：

- `checkOperandTypes` **L152-161**：`super` 失败 → `mycatCoerceOperands` 就地按 Java 参数型包 CAST → 重检。
- `mycatCoerceOperands` **L186-190** 的跳过条件：
  ```java
  if (operandType == null
      || SqlTypeUtil.equalSansNullability(typeFactory, operandType, targetType)
      || SqlTypeUtil.canCastFrom(targetType, operandType, false)) {
    continue;                 // ← 问题：canCastFrom=true 即跳，不包 CAST
  }
  ```
  `DAYOFWEEK` 目标 DATE、实参 `?`(VARCHAR)：`canCastFrom(DATE, VARCHAR)=true` → **跳过包 CAST** → 交回 `super.checkOperandTypes` 时 operand 仍是 VARCHAR、严格族门拒。

> 对照实测（见看板 P3 映射）：`DAYOFMONTH` 因 visitor 将其重写为 `EXTRACT`（`MycatCalciteMySqlNodeVisitor:1822-1831`）而绕开此路径、不红；`DAYOFWEEK` 直调 `DayOfWeekFunction.createCall` 故暴露。

## 3. 设计（两处分修点，Mycat 侧，零上游逻辑改动）

### 修点①：Mycat 的 TypeCoercion 子类覆写 `coerceOperandType`（解 31）

扩展点已存在：`MycatCalciteSupport` **L187-251** 经 `withTypeCoercionFactory` 装配匿名 `TypeCoercionImpl` 子类（现仅覆写 `implicitCast` / `commonTypeForBinaryComparison`）。**新增覆写 `coerceOperandType`**：

```java
@Override
protected boolean coerceOperandType(
    @Nullable SqlValidatorScope scope, SqlCall call, int index, RelDataType targetType) {
  final SqlNode operand = call.getOperandList().get(index);
  // MYCAT CHANGE (I-DEBT-31): Mycat 校验期已知动态参类型，上游"永不 cast 动态参"不适用
  if (operand instanceof SqlDynamicParam && scope != null) {
    final RelDataType operandType = validator.deriveType(scope, operand);
    if (operandType == null || operandType.getSqlTypeName() == SqlTypeName.ANY) {
      return false;                       // 未知类型仍保守拒 cast（不扩面）
    }
    if (RelDataTypeFactoryImpl.isJavaType(targetType)) {
      targetType = ((JavaTypeFactory) factory).toSql(targetType);
    }
    if (!needToCast(scope, operand, targetType, SqlTypeCoercionRule.lenientInstance())) {
      return false;
    }
    final RelDataType target1 = syncAttributes(operandType, targetType);
    final SqlNode desired = castTo(operand, target1);
    call.setOperand(index, desired);
    updateInferredType(desired, target1);
    return true;
  }
  return super.coerceOperandType(scope, call, index, targetType);
}
```

要点：复刻 super 主体、仅去掉"见动态参即早退"这一条；`castTo`/`needToCast`/`syncAttributes`/`updateInferredType` 均为 `AbstractTypeCoercion` 可见成员。非动态参 / ANY 未知 → 行为与原实现逐字节一致。

### 修点②：`mycatCoerceOperands` 跳 CAST 守卫收窄（解 32）

将 L186-190 条件由"相等 **或 canCastFrom** 即跳"**收窄为"仅相等即跳"**：

```java
if (operandType == null
    || SqlTypeUtil.equalSansNullability(typeFactory, operandType, targetType)) {
  continue;
}
// canCastFrom=true 但族不符（如 VARCHAR→DATE）不再跳过：包 CAST(? AS DATE)
```

于是 VARCHAR→DATE 这类"可 cast 但跨族"的 operand 也被包 `CAST(? AS DATE)`，`super.checkOperandTypes` 重检即过。

## 4. 边界、幂等、安全阀

- **ANY 仍拒 cast**：动态参派生为 `ANY`（未知）时两修点均维持原拒行为，不猜测、不扩面。
- **幂等**：修点②收窄后，本已等于目标型的 operand 走"相等即跳"不重复包；若二次进 `mycatCoerceOperands`，`equalSansNullability` 已命中→跳过。
- **23a 绿函数零波及（待回归证伪）**：`bin/CONV/CRC32` 在 `super.checkOperandTypes`（L154）首轮即过，理论上根本不入 `mycatCoerceOperands`；本轮以全函数族回归坐实。
- **可整体 revert**：两改动单调、无 schema/持久化/外部副作用，均在 hbt + Mycat 自研类内。

## 5. 数据流（收口后）

```
SQL 字面量 '张三'/'2018-01-01'
  → druid 参数化为 ?（SqlDynamicParam）
  → Mycat 校验器 resolveDynamicParam 派生 VARCHAR/…（已知型）
  → [比较面] binaryComparisonCoercion → coerceOperandsType → coerceOperandType（修点① 现在对已知型动态参包 CAST）
  → [函数面] MycatSqlDefinedFunction.checkOperandTypes → mycatCoerceOperands（修点② 现在对跨族动态参包 CAST）
  → 严格族门通过 → codegen（CAST(? AS T) 按索引取运行值、类型驱动转换，23b/23c/I-DEBT-8 已证端到端可行）
```

## 6. 错误处理

- 修点仅改变"是否包 CAST"，不改异常类型/文案；包 CAST 后仍不兼容的（如 CHAR→结构化类型）由 `needToCast`/`super` 自然回退原拒路径，错误签名不变。
- 运行时若某跨族 CAST 的实际 Java 值无法转换（边界外），沿用既有 codegen 异常面（I-DEBT-8 读边界已补 byte[]↔ByteString 一类），不在本 spec 扩面。

## 7. 测试计划（Evidenced-Done 判据）

1. **新单元锁** `hbt/.../DynamicParamCoercionTest`：
   - 正例：`blob(VARBINARY) = ?(VARCHAR)` 经 TypeCoercion 后 operand 被包 `CAST(... AS VARBINARY)`、比较校验通过。
   - 正例：`DAYOFWEEK(?·VARCHAR)` 经 `mycatCoerceOperands` 后包 `CAST(? AS DATE)`、族门通过。
   - 负例：动态参派生 `ANY`（未知）时不包 CAST、维持原拒（守"不扩面"）。
2. **e2e 靶转绿**：`SqlFunctionTest#testComplexQuery:391`（31）、`PushDownFunctionShardingTableTest#testTimeFunction:272`（32，含 DAYOFWEEK）。
3. **护栏零回归**：`mvn -o test -pl hbt`（基线 58/0/0 不劣化，+新锁）、router 15/0/0、全日期函数族 + WHERE 比较面 e2e 无新红。

## 8. 交付物清单（待 plan 细化）

- 改：`hbt/.../MycatCalciteSupport.java`（修点① 覆写 `coerceOperandType`）。
- 改：`calcite-core/.../mycat/MycatSqlDefinedFunction.java`（修点② 守卫收窄，MYCAT CHANGE 注记）。
- 新测：`hbt/.../DynamicParamCoercionTest.java`。
- 看板 `2026-09-30-mysql-mariadb-compat-PROGRESS.md`：I-DEBT-31/32 收口行 + P3 映射更新。

## 9. 非目标（明确不做）

- 不改 `AbstractTypeCoercion`/`TypeCoercionImpl` 上游文件（避免 fork 污染与 UNION/INSERT 面 blast）。
- 不动 L182-185 `coerceColumnType` 的动态参分支（UNION/INSERT 列对齐，异根另论）。
- 不触碰运行时 codegen / 结果物化面（若收口后暴露新层真 bug，另立债项，不冒进扩面）。
