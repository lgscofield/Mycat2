# I-DEBT-31 + 32 动态参 defeat Calcite coercion 收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Mycat 校验期"类型可知的动态参 `?`"重新参与隐式 CAST coercion，一次收口 I-DEBT-31（`VARBINARY=VARCHAR` 比较）与 I-DEBT-32（`DAYOFWEEK(<VARCHAR>)` 函数入参）。

**Architecture:** 两处单调改动，均在 Mycat 侧、零上游 Calcite 逻辑文件改动——① `MycatSqlDefinedFunction.mycatCoerceOperands` 跳 CAST 守卫收窄（函数 operand 面）；② Mycat 在 `MycatCalciteSupport` 装配的匿名 `TypeCoercionImpl` 子类**覆写 `coerceOperandType`**（比较面），对已知型动态参复刻 super 主体、去掉"见动态参即早退"这一条。

**Tech Stack:** JDK21、Calcite 1.42 fork（`calcite-core`）、Mycat `hbt`、JUnit5、Maven（离线 `-o`）。

**Spec:** `docs/superpowers/specs/2026-10-08-i-debt-31-32-dynamic-param-coercion-design.md`

## Global Constraints

- 铁律：Evidenced-Done 才提交、不假修不冒进、保护 ~28 绿基线；重编前停 8066；`mvn` 带 `JAVA_HOME`（JDK21，本机 `D:\Program Files\BellSoft\LibericaJDK-21-Full`）；跳测试用 `'-Dmaven.test.skip=true'`；PowerShell 无 grep/tail，用 `Select-String`/`Select-Object`/`Get-Content -Tail`。
- 未知动态参（派生为 `SqlTypeName.ANY`）两修点均维持原拒 cast 行为——不扩面。
- `castTo`（`AbstractTypeCoercion:318`，`private static`）与 `syncAttributes`（`:234`，包级私有）**跨包不可见**，Mycat 子类须用 public/protected API 内联其效果，禁止直接调用。
- 可整体 revert：无 schema/持久化/外部副作用。

## Review Focus

- **未知型动态参仍被拒**：当 `?` 无法派生类型（`ANY`）时，比较/函数 operand 必须维持原 `Cannot apply` 报错——不得因放行"已知型"而连带放开未知型。
- **幂等**：同一 operand 二次进入 coercion 时，已等于目标型的不得被重复包 CAST。
- **23a 绿函数零波及**：`bin('12')`/`CONV(16,..)`/`CRC32(123)` 在 `super.checkOperandTypes` 首轮即过，守卫收窄后不得新红。
- **CHAR↔VARBINARY 比较的 charset**：`VARBINARY = ?(VARCHAR)` 收口后包 `CAST(? AS VARBINARY)`，可空性须随 operand 传播（NULL 参数仍合法）。
- **比较面 blast 上界**：放行仅对"动态参 + 已知型 + `needToCast` 成立"生效；字面量列、非比较运算符路径行为逐字节不变。

---

### Task 1: 修点② — `mycatCoerceOperands` 跳 CAST 守卫收窄（函数 operand 面，解 I-DEBT-32）

**Files:**
- Modify: `calcite-core/src/main/java/org/apache/calcite/mycat/MycatSqlDefinedFunction.java:186-190`
- Test: `hbt/src/test/java/io/mycat/calcite/DynamicParamCoercionTest.java`（新建，函数用例）

**Interfaces:**
- Consumes: 无（自包含）。
- Produces: `MycatSqlDefinedFunction.checkOperandTypes` 对"目标族不符但可 cast（如 DATE←VARCHAR）"的动态参 operand 就地包 `CAST(? AS <target>)` 后重检通过。

- [ ] **Step 1: 写失败测试（函数 operand 面）**

新建 `hbt/src/test/java/io/mycat/calcite/DynamicParamCoercionTest.java`，复用 `CalciteValidatorGoldenTest` 的校验脚手架（`DrdsRunnerHelper.preParse(sql, "mycat")` → `getSqlValidator(drdsSql, CATALOG_READER)` → `MycatCalciteMySqlNodeVisitor` → `validator.validate(node)`，见 `CalciteValidatorGoldenTest:214-222`）。本任务先只放函数用例：

```java
@Test
public void dayOfWeekOnStringLiteralValidates() {
    // '2018-01-01' 经 preParse 参数化为 ?(VARCHAR)，DAYOFWEEK 目标 DATE，跨族须被包 CAST 后通过
    Assertions.assertDoesNotThrow(() -> validateOk("SELECT DAYOFWEEK('2018-01-01')"));
}
```

`validateOk(sql)` 为本类私有 helper：跑完校验返回 `validator.getValidatedNodeType(validated)`，**若校验抛 `CalciteException`（`Cannot apply ...`）则测试失败**。fixture schema 直接用空表集即可（`DAYOFWEEK` 不引用列），沿 `rootSchemaWithFixtureTables()` 简化为不含表的 `mycat` 子 schema。

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -o test -pl hbt -Dtest=DynamicParamCoercionTest#dayOfWeekOnStringLiteralValidates`（先 `mvn -o install -Dmaven.test.skip=true -pl calcite-core` 确保 hbt 链到最新 fork）
Expected: FAIL，异常消息含 `Cannot apply 'DAYOFWEEK' to`。

- [ ] **Step 3: 收窄守卫**

`MycatSqlDefinedFunction.java` L186-190，把 `canCastFrom` 那一项从"跳过"条件里删掉，只留"相等/为空即跳"：

```java
final RelDataType operandType = callBinding.getOperandType(i);
// MYCAT CHANGE (I-DEBT-32): 原以 canCastFrom=true 即跳，致 VARCHAR→DATE 等跨族动态参不包 CAST、严格族门仍拒；
// 收窄为"仅已相等即跳"，可 cast 跨族 operand 也进下方包 CAST 分支。
if (operandType == null
        || SqlTypeUtil.equalSansNullability(typeFactory, operandType, targetType)) {
    continue;
}
```

下方 L191-194 包 CAST 分支不动。更新方法 Javadoc（L143-151）里"相等或可直接赋值"措辞为"相等"。

- [ ] **Step 4: 重编 fork + 跑测试确认通过**

Run: `mvn -o install -Dmaven.test.skip=true -pl calcite-core` 然后 `mvn -o test -pl hbt -Dtest=DynamicParamCoercionTest`
Expected: PASS（1 通过）。

- [ ] **Step 5: 23a 绿函数回归（证伪零波及）**

Run: `mvn -o test -pl hbt`
Expected: 原基线（23e 后 58/0/0，本任务 +1 → 59/0/0）不劣化、无新红。

- [ ] **Step 6: Commit**

```bash
git add calcite-core/src/main/java/org/apache/calcite/mycat/MycatSqlDefinedFunction.java hbt/src/test/java/io/mycat/calcite/DynamicParamCoercionTest.java
git commit -m "fix(i-dbt-32): mycatCoerceOperands 跳 CAST 守卫收窄为仅相等，放行跨族动态参 CAST"
```

---

### Task 2: 修点① — Mycat TypeCoercion 子类覆写 `coerceOperandType`（比较面，解 I-DEBT-31）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java:191-249`（匿名 `TypeCoercionImpl` 子类体内新增覆写）
- Test: `hbt/src/test/java/io/mycat/calcite/DynamicParamCoercionTest.java`（追加比较用例 + VARBINARY fixture 表）

**Interfaces:**
- Consumes: `AbstractTypeCoercion` 的 `protected` 成员 `validator`（`:80`）、`factory`（`:81`）、`needToCast(scope,node,toType,mappingRule)`（`:266`）、`updateInferredType(node,type)`（`:326`）。
- Produces: `coerceOperandType(scope, call, index, targetType)` 对"已知型（非 ANY）动态参 operand"包 `CAST(? AS commonType)`，使 `binaryComparisonCoercion`→`coerceOperandsType` 走通。

- [ ] **Step 1: 写失败测试（比较面 + 未知型负例）**

在 `DynamicParamCoercionTest` 追加。fixture schema 增一张含 VARBINARY 列的表 `tbin(id INT, blobcol VARBINARY(255))`（用 `MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.VARBINARY, 255)` 建列，沿 `CalciteValidatorGoldenTest:96-106` 的 builder 写法）：

```java
@Test
public void varBinaryEqualsStringLiteralCoerces() {
    // blobcol(VARBINARY) = '张三' → '张三' 参数化为 ?(VARCHAR)，收口后包 CAST(? AS VARBINARY) 通过
    Assertions.assertDoesNotThrow(() -> validateOk("SELECT id FROM tbin WHERE blobcol = '张三'"));
}

@Test
public void anyTypedDynamicParamStillRejected() {
    // 负例：未知型动态参（派生 ANY）不得被放行 cast——保持原 Cannot apply 报错（Review Focus）
    // 构造方式：直接对 ANY 型列做跨族比较或断言 coerce 不改动，见 Step 说明
    Assertions.assertThrows(Exception.class, () -> validateOk("<见 Step 2 备注的 ANY 触发 SQL>"));
}
```

ANY 负例触发说明：本 fixture 无天然 ANY 型来源，故 ANY 负例以**读证 + 一个不产生 ANY 的对照断言**降级处理——保留 `varBinaryEqualsStringLiteralCoerces` 与已有绿比较用例（如 `WHERE id = 1`）不受影响作为回归锁；ANY 保守性由 Step 3 代码里 `== SqlTypeName.ANY return false` 分支承载，本任务以代码走查记录、不强断言（诚实口径，不虚构可触发路径）。

- [ ] **Step 2: 跑测试确认比较用例失败**

Run: `mvn -o test -pl hbt -Dtest=DynamicParamCoercionTest#varBinaryEqualsStringLiteralCoerces`
Expected: FAIL，异常含 `Cannot apply '=' to`。

- [ ] **Step 3: 在匿名子类新增 `coerceOperandType` 覆写**

`MycatCalciteSupport.java` L191 `new TypeCoercionImpl(typeFactory, validator) { ... }` 体内（与现有 `implicitCast`/`commonTypeForBinaryComparison` 并列）追加：

```java
@Override
protected boolean coerceOperandType(
    @org.checkerframework.checker.nullness.qual.Nullable org.apache.calcite.sql.validate.SqlValidatorScope scope,
    SqlCall call, int index, RelDataType targetType) {
  final SqlNode operand = call.getOperandList().get(index);
  // MYCAT CHANGE (I-DEBT-31): Mycat 校验期已知动态参类型，上游"永不 cast 动态参"(AbstractTypeCoercion:108)不适用
  if (operand instanceof SqlDynamicParam && scope != null) {
    final RelDataType operandType = validator.deriveType(scope, operand);
    if (operandType == null || operandType.getSqlTypeName() == SqlTypeName.ANY) {
      return false;                       // 未知型保守拒 cast（不扩面）
    }
    RelDataType tt = targetType;
    if (org.apache.calcite.rel.type.RelDataTypeFactoryImpl.isJavaType(tt)) {
      tt = ((org.apache.calcite.rel.type.JavaTypeFactory) factory).toSql(tt);
    }
    if (!needToCast(scope, operand, tt, SqlTypeCoercionRule.lenientInstance())) {
      return false;
    }
    // castTo/syncAttributes 跨包不可见 → 用 public/protected API 内联其效果（仅传可空性）
    final RelDataType synced = factory.createTypeWithNullability(tt, operandType.isNullable());
    final SqlNode desired = SqlStdOperatorTable.CAST.createCall(
        operand.getParserPosition(), operand,
        SqlTypeUtil.convertTypeToSpec(synced).withNullable(synced.isNullable()));
    call.setOperand(index, desired);
    updateInferredType(desired, synced);
    return true;
  }
  return super.coerceOperandType(scope, call, index, targetType);
}
```

补 import：`SqlDynamicParam`（`org.apache.calcite.sql.*` 已覆盖）、`SqlValidatorScope`（`org.apache.calcite.sql.validate`，若未导入则全限定或补 import）、`SqlTypeCoercionRule`（`org.apache.calcite.sql.validate`）。`RelDataTypeFactoryImpl`/`JavaTypeFactory` 用全限定即可。`@Nullable` 用 `org.checkerframework...Nullable`（若类路径无 checker-qual，则改用项目既有 Nullable 注解或省略——保持与 `AbstractTypeCoercion.coerceOperandType` 签名兼容即可，Java 允许去掉 @Nullable）。

- [ ] **Step 4: 重编 hbt + 跑测试确认通过**

Run: `mvn -o test -pl hbt -Dtest=DynamicParamCoercionTest`
Expected: PASS（比较用例转绿，其余用例不回归）。

- [ ] **Step 5: 全 hbt 回归**

Run: `mvn -o test -pl hbt`
Expected: 无新红（在 Task1 后计数上 +比较用例）。

- [ ] **Step 6: Commit**

```bash
git add hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java hbt/src/test/java/io/mycat/calcite/DynamicParamCoercionTest.java
git commit -m "fix(i-dbt-31): Mycat TypeCoercion 覆写 coerceOperandType 放行已知型动态参 CAST"
```

---

### Task 3: 端到端验收 + 护栏 + 看板收口

**Files:**
- Modify: `docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md`（I-DEBT-31/32 收口行 + P3 映射 + 变更日志）

**Interfaces:**
- Consumes: Task1+Task2 已合入的产品码。
- Produces: Evidenced-Done 证据链。

- [ ] **Step 1: 停 8066，重编全链 fat jar**

Run: 停现有 proxy 进程 → `mvn -o install -Dmaven.test.skip=true -pl calcite-core,hbt,server -am`（或按既有分段：先 calcite-core/hbt install，再单独 `-pl server` 触发 shade 重打）
Expected: BUILD SUCCESS，`server/target` fat jar 时间戳刷新。

- [ ] **Step 2: 起 8066（JDK21 + `--add-opens`），跑 e2e 靶**

用 JDK21 绝对路径 `java --add-opens=java.base/java.nio=ALL-UNNAMED -jar server fat jar` 起 8066，确认 Docker `m1-mysql-a/b` 在跑且 `max_connections>=1000`。

Run: `mvn -o test -pl example -Dtest=SqlFunctionTest#testComplexQuery,PushDownFunctionShardingTableTest#testTimeFunction`
Expected: `testComplexQuery`（:391 的 `VARBINARY=VARCHAR`）不再抛 `Cannot apply '='`；`testTimeFunction`（:272 `DAYOFWEEK`）推进/转绿；记录剩余红是否均异根他族。

- [ ] **Step 3: 护栏回归**

Run: `mvn -o test -pl hbt`（全量）、`mvn -o test -pl router`
Expected: hbt 全绿、router 15/0/0、无新红。

- [ ] **Step 4: 回写看板并提交**

`PROGRESS.md`：I-DEBT-31/32 行状态改 Evidenced-Done（凭 e2e 实测签名，非记忆）、P3 映射更新、变更日志追加一行（附本计划/spec/证据路径）。

```bash
git add docs/superpowers/specs/2026-09-30-mysql-mariadb-compat-PROGRESS.md
git commit -m "docs(compat): I-DEBT-31/32 动态参 coercion 收口 Evidenced-Done"
```

若 Step2 实测暴露收口后新层真 bug（如运行时 CAST 值转换），按铁律**不冒进扩面**——如实挂账新债项，本 spec 仅收口校验期 coercion 面。

## Self-Review 记录

- **Spec 覆盖**：§2.1/修点①→Task2；§2.3/修点②→Task1；§7 测试计划→Task1/2 单元锁 + Task3 e2e；§4 ANY 保守→Task2 代码分支 + Review Focus 首条 + Task2 Step1 诚实降级说明；§9 非目标→计划未触碰上游文件/coerceColumnType。
- **可见性修正**：spec 伪码直用 `castTo`/`syncAttributes`（跨包不可见），Task2 Step3 已改为 public API 内联。
- **类型一致**：`coerceOperandType` 签名与 `AbstractTypeCoercion:96-100` 逐参对齐；`validateOk` helper 三处引用同名。
- **比例**：改动两文件 + 一测，计划体量与 spec 相称。
