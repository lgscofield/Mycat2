# Calcite 1.42 编译错误修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 hbt 模块中 42 个文件的 ~149 个 Calcite 1.42 API 兼容编译错误，使项目全量编译通过。

**Architecture:** 按错误类型分批修复。每批修复后运行 `mvn compile` 验证。修复范围涵盖 calcite-core 可见性调整、Calcite API 适配、Druid API 适配三大类。

**Tech Stack:** Java 21, Maven, Calcite 1.42.0, Druid 1.2.28

## Global Constraints

- JDK 21: `D:\Program Files\BellSoft\LibericaJDK-21-Full`
- 编译命令: `$env:JAVA_HOME = 'D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn compile -T 4 2>&1 | Out-File .qoder/compile-fixN.txt -Encoding UTF8`
- calcite-core 模块修改需标记 `// MYCAT CHANGE:` 注释
- 不修改 io.mycat.calcite.* 扩展文件的业务逻辑，仅适配 API 变更
- PowerShell 中用 `;` 连接命令，不用 `&&`

---

## Task 1: calcite-core 可见性修复

**Files:**
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/EnumUtils.java` (6 methods)
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/PhysTypeImpl.java` (1 method)
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java` (1 field)
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/EnumerableRelImplementor.java` (TypeRegistrar constructor)

**修复内容：** Calcite 1.42 收紧了以下方法的可见性，需改回 protected/public。

- [ ] **Step 1: 修改 EnumUtils 的 6 个方法为 public**

在 `EnumUtils.java` 中，将以下方法从 package-private 改为 `public`：
- `generatePredicate(EnumerableRelImplementor, RexBuilder, RelNode, RelNode, PhysType, PhysType, RexNode)` → 添加 `public`
- `joinSelector(JoinRelType, PhysType, List<PhysType>)` → 添加 `public`
- `toLinq4jJoinType(JoinRelType)` → 添加 `public`
- `fieldRowTypes(RelDataType, List<? extends RexNode>, List<Integer>)` → 添加 `public`
- `javaClass(JavaTypeFactory, RelDataType)` → 添加 `public`
- `fieldTypes(JavaTypeFactory, List<? extends RelDataType>)` → 添加 `public`

每个方法前添加注释: `// MYCAT CHANGE: package-private → public for Mycat bridge subclass access`

- [ ] **Step 2: 修改 PhysTypeImpl.of 为 public**

在 `PhysTypeImpl.java` 中，将 `static PhysTypeImpl of(JavaTypeFactory typeFactory, Type rowType)` 从 package-private 改为 `public`。

添加注释: `// MYCAT CHANGE: package-private → public for Mycat bridge access`

- [ ] **Step 3: 修改 RexImpTable.map 为 protected**

在 `RexImpTable.java` 中，将 `private final Map<SqlOperator, CallImplementor> map` 改为 `protected`。

- [ ] **Step 4: 修改 TypeRegistrar 构造器为 public**

在 `EnumerableRelImplementor.java` 中，找到 `TypeRegistrar` 内部类的构造器，将其改为 `public`。

- [ ] **Step 5: 编译验证**

Run: `$env:JAVA_HOME = 'D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn compile -pl calcite-core -T 4`
Expected: BUILD SUCCESS for calcite-core module

- [ ] **Step 6: Commit**

```bash
git add calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/
git add calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/PhysTypeImpl.java
git commit -m "fix(calcite-core): widen visibility of EnumUtils/PhysTypeImpl/RexImpTable for Mycat bridge access"
```

---

## Task 2: Config.EMPTY → ImmutableBuilder 模式

**Files (11 个文件，13 个错误):**
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/LocalRules.java` (8 处)
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/MycatAggregateUnionTransposeRule.java`
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/rules/ToViewTableScanRule.java`
- Modify: `hbt/src/main/java/io/mycat/calcite/logical/MycatViewIndexViewRule.java`
- Modify: `hbt/src/main/java/io/mycat/calcite/rewriter/MycatAggDistinctRule.java`
- Modify: `hbt/src/main/java/io/mycat/calcite/rewriter/MycatFilterJoinRule.java`
- Modify: `hbt/src/main/java/io/mycat/calcite/rewriter/MycatAggregateExpandDistinctAggregatesRule.java` (1 处 EMPTY)

**修复模式：** Calcite 1.42 移除了 `RelRule.Config.EMPTY` 常量。每个 Config 接口需要改为使用 Immutables builder 模式。

- [ ] **Step 1: 分析 Calcite 1.42 中 Config.EMPTY 的替代方案**

参考 calcite-core 中已有的规则（如 `FilterJoinRule.java`），了解 1.42 的标准模式：

```java
// Calcite 1.26 旧模式：
interface Config extends RelRule.Config {
    Config DEFAULT = Config.EMPTY.withOperandSupplier(...)...;
}

// Calcite 1.42 新模式：
@Value.Immutable
interface Config extends RelRule.Config {
    Config DEFAULT = ImmutableXxxConfig.builder()
        .operandSupplier(...)
        ...
        .build();
}
```

查看 calcite-core 中 `FilterJoinRule` 或 `FilterProjectTransposeRule` 的 Config 定义，确认 `@Value.Immutable` 注解和 `Immutable*Config.builder()` 模式。

- [ ] **Step 2: 修复 LocalRules.java 的 8 个 Config**

对 LocalRules.java 中每个内部 Config 接口：
1. 添加 `@Value.Immutable` 注解
2. 将 `Config.EMPTY` 替换为 `Immutable<RuleName>Config.builder()...build()`
3. 确保 `operandSupplier` 和 `description` 等配置正确传递

例如 `UniversalFilterViewRule.Config`:
```java
@Value.Immutable
interface Config extends RelRule.Config {
    Config DEFAULT = ImmutableUniversalFilterViewRuleConfig.builder()
        .operandSupplier(b -> b.operand(MycatView.class).anyInputs())
        .description("UniversalFilterViewRule")
        .build();
}
```

- [ ] **Step 3: 修复其余 6 个文件的 Config**

每个文件遵循相同模式：添加 `@Value.Immutable`，替换 `Config.EMPTY` 为对应的 `Immutable*Config.builder()`。

- [ ] **Step 4: 编译验证**

Run: `$env:JAVA_HOME = 'D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn compile -pl hbt -T 4 2>&1 | Select-String "ERROR"`
Expected: Config.EMPTY 相关错误消失

- [ ] **Step 5: Commit**

```bash
git add hbt/src/main/java/io/mycat/calcite/localrel/ hbt/src/main/java/io/mycat/calcite/logical/ hbt/src/main/java/io/mycat/calcite/rewriter/
git commit -m "fix(hbt): replace Config.EMPTY with ImmutableBuilder pattern for Calcite 1.42"
```

---

## Task 3: 抽象方法实现

**Files (6 个文件，8 个错误):**
- Modify: `hbt/src/main/java/org/apache/calcite/adapter/enumerable/RexToLixTranslator.java` — 添加 `visitLambda(RexLambda)`
- Modify: `hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java` — 添加 `validateTimeFrame(SqlIntervalQualifier)`
- Modify: `hbt/src/main/java/io/mycat/calcite/physical/MycatWindow.java` — 添加 `copy(List<RexLiteral>)`
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/LocalWindow.java` — 添加 `copy(List<RexLiteral>)`
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatRelDataTypeFactory.java` — 添加 `leastRestrictive(List<RelDataType>, SqlTypeMappingRule)`
- Modify: `hbt/src/main/java/io/mycat/calcite/physical/MycatWindow.java` — WinAggContext 匿名类添加 `getExclude()`

- [ ] **Step 1: 参考 calcite-core 中对应方法的实现**

查看 calcite-core 1.42 中这些方法的标准实现：
- `RexVisitor.visitLambda(RexLambda)` — 参考 calcite-core 中其他 RexVisitor 实现
- `SqlValidator.validateTimeFrame(SqlIntervalQualifier)` — 参考 calcite-core 的 SqlValidatorImpl
- `Window.copy(List<RexLiteral>)` — 参考 calcite-core 的 EnumerableWindow
- `RelDataTypeFactory.leastRestrictive(List, SqlTypeMappingRule)` — 参考 calcite-core 的 RelDataTypeFactoryImpl
- `WinAggContext.getExclude()` — 参考 calcite-core 的 WinAggContext 接口定义

- [ ] **Step 2: 在 RexToLixTranslator 中添加 visitLambda**

```java
// MYCAT CHANGE: Calcite 1.42 added RexLambda support
@Override public Result visitLambda(RexLambda lambda) {
    throw new RuntimeException("Lambda expressions are not supported in Mycat2 enumerable implementation");
}
```

- [ ] **Step 3: 在 SqlValidatorImpl 中添加 validateTimeFrame**

查看 calcite-core 中的 SqlValidatorImpl 实现，复制 `validateTimeFrame` 方法到 hbt 的对应位置。

- [ ] **Step 4: 在 MycatWindow 和 LocalWindow 中添加 copy(List<RexLiteral>)**

参考 calcite-core 的 `EnumerableWindow.copy(List<RexLiteral>)` 实现。MycatWindow 需要返回 `super.copy(constants)` 或自定义实现。

- [ ] **Step 5: 在 MycatRelDataTypeFactory 中添加 leastRestrictive**

参考 calcite-core 的 `RelDataTypeFactoryImpl.leastRestrictive(List, SqlTypeMappingRule)` 实现，委托给 super 类或 delegate。

- [ ] **Step 6: 在 MycatWindow 的 WinAggContext 匿名类中添加 getExclude()**

查看 calcite-core 中 `WinAggContext.getExclude()` 的接口定义和默认实现。

- [ ] **Step 7: 编译验证**

- [ ] **Step 8: Commit**

```bash
git commit -m "fix(hbt): implement abstract methods required by Calcite 1.42"
```

---

## Task 4: Lambda 参数类型适配

**Files (4 个文件，4 个错误):**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatRules.java` (2 处)
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/LocalCorrelate.java`
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/LocalJoin.java`
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/LocalProject.java`

**错误类型：** Calcite 1.42 中某些 lambda/函数式接口的参数类型变更。

- [ ] **Step 1: 分析每个 lambda 类型不匹配**

查看 calcite-core 中对应接口的 1.42 签名，确定正确的 lambda 参数类型。常见模式：
- `RelRule.OperandTransform` 的 lambda 参数类型
- `Function<RelBuilder, RelNode>` vs `Function<RelBuilder, ?>` 等

- [ ] **Step 2: 逐一修复 4 个文件**

- [ ] **Step 3: 编译验证**

- [ ] **Step 4: Commit**

```bash
git commit -m "fix(hbt): adapt lambda parameter types for Calcite 1.42 API changes"
```

---

## Task 5: Druid API 变更适配

**Files (5 个文件，~12 个错误):**
- Modify: `hbt/src/main/java/io/mycat/calcite/table/LogicTable.java` — `setIfNotExiists` → `setIfNotExists` (2 处)
- Modify: `hbt/src/main/java/io/mycat/util/CreateTableUtils.java` — `setIfNotExiists` → `setIfNotExists` (1 处)
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatHint.java` — `hash_lower()` → 替代方法 (2 处)
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatCalciteMySqlNodeVisitor.java` — `getLiteral()` → 替代方法 (3 处)
- Modify: `hbt/src/main/java/io/mycat/MysqlMetadataManager.java` — `getGeneratedAlawsAs()` → 替代方法 (1 处)
- Modify: `hbt/src/main/java/com/alibaba/druid/sql/dialect/mysql/visitor/MycatOutputVisitor.java` — IOException in try-catch (1 处)

- [ ] **Step 1: 修复 setIfNotExiists 拼写修正**

Druid 1.2.28 修正了拼写：`setIfNotExiists(boolean)` → `setIfNotExists(boolean)`

在 LogicTable.java 和 CreateTableUtils.java 中，全局替换 `setIfNotExiists` → `setIfNotExists`。

- [ ] **Step 2: 修复 MycatHint 的 hash_lower()**

Druid 1.2.28 中 `Lexer.hash_lower()` 可能已重命名。检查 Druid 1.2.28 的 Lexer 类，找到替代方法（可能是 `hashLCase()` 或 `hashLower()`）。

- [ ] **Step 3: 修复 MycatCalciteMySqlNodeVisitor 的 getLiteral()**

Druid 1.2.28 中 `SQLTimestampExpr.getLiteral()`、`SQLDateExpr.getLiteral()`、`SQLTimeExpr.getLiteral()` 可能已重命名或移除。检查替代方法（可能是 `getValue()` 或 `toString()`）。

- [ ] **Step 4: 修复 MysqlMetadataManager 的 getGeneratedAlawsAs()**

检查 Druid 1.2.28 中 `SQLColumnDefinition` 的替代方法（可能是 `getGeneratedAlwaysAs()` 修正了拼写）。

- [ ] **Step 5: 修复 MycatOutputVisitor 的 IOException**

MycatOutputVisitor.java:798 — `try` 语句中不能抛出 `IOException`。检查上下文，可能是被调用的方法签名变了（不再 throws IOException），移除不必要的 catch 块。

- [ ] **Step 6: 编译验证**

- [ ] **Step 7: Commit**

```bash
git commit -m "fix(hbt): adapt Druid 1.2.28 API changes (spelling corrections, method renames)"
```

---

## Task 6: Calcite API 移除与签名变更

**Files (~15 个文件，~35 个错误):**

**6a: NO_PARAMS/NO_EXPRS/BRIDGE_METHODS 常量 (MycatCalc, 8 处)**
**6b: getHints() 方法 (MycatHashJoin, MycatNestedLoopJoin, MycatSortMergeJoin, MycatSortMergeSemiJoin, MycatHashAggregate, LocalTableScan, 6 处)**
**6c: SqlBasicCall.getOperands() (SqlValidatorImpl x2, SqlImplementor x1, MycatCalciteMySqlNodeVisitor x2, 5 处)**
**6d: createCall 签名 (MycatCalciteMySqlNodeVisitor, 1 处)**
**6e: rewriteSingleValueExpr 签名 (RelToSqlConverter, MycatImplementor, 2 处)**
**6f: SqlBasicCall 构造器 (SqlValidatorImpl, 1 处)**
**6g: WithScope 构造器 (SqlValidatorImpl, 1 处)**
**6h: SqlToRelConverter.configBuilder() (MycatCalciteSupport, 1 处)**
**6i: SqlTypeUtil.isBigint/isDouble (MycatCalciteSupport, 4 处)**
**6j: bindObservable (PrepareExecutor, 2 处)**
**6k: RepeatUnion 构造器 (MycatRepeatUnion, 1 处)**
**6l: final traitSet (MycatTableLookupValues, MycatQuery, 2 处)**

- [ ] **Step 1: 修复 NO_PARAMS/NO_EXPRS/BRIDGE_METHODS**

Calcite 1.42 移除了 `Calc.NO_PARAMS`、`Calc.NO_EXPRS`、`Calc.BRIDGE_METHODS` 常量。

查看 calcite-core 中 `EnumerableCalc` 的 1.42 实现，找到替代方案：
- `NO_PARAMS` → `ImmutableList.of()` 或 `Collections.emptyList()`
- `NO_EXPRS` → `ImmutableList.of()` 或 `Collections.emptyList()`
- `BRIDGE_METHODS` → 检查对应枚举或常量

在 MycatCalc.java 中替换所有引用。

- [ ] **Step 2: 修复 getHints() 方法**

Calcite 1.42 中 `RelInput.getHints()` 可能已移除或变更。查看 calcite-core 的 `RelInput` 接口，找到获取 hints 的替代方式（可能是 `getHints()` 返回类型变了，或需要从其他来源获取）。

修复 MycatHashJoin, MycatNestedLoopJoin, MycatSortMergeJoin, MycatSortMergeSemiJoin, MycatHashAggregate, LocalTableScan 中的 6 处引用。

- [ ] **Step 3: 修复 SqlBasicCall.getOperands()**

Calcite 1.42 中 `SqlBasicCall.getOperands()` 返回类型可能从数组变为 List，或方法被移除。查看 calcite-core 中 `SqlBasicCall` 的 1.42 API：
- 如果 `getOperands()` 移除 → 使用 `getOperandList()` 返回 `List<SqlNode>`
- 修复 SqlValidatorImpl (2处)、SqlImplementor (1处)、MycatCalciteMySqlNodeVisitor (2处)

- [ ] **Step 4: 修复 createCall 签名变更**

`SqlOperator.createCall(SqlParserPos, Object[])` 不再接受 `Object[]`，需要 `SqlNode[]` 或 `List<SqlNode>`。修复 MycatCalciteMySqlNodeVisitor 中的类型转换。

- [ ] **Step 5: 修复 rewriteSingleValueExpr 签名**

Calcite 1.42 的 `SqlDialect.rewriteSingleValueExpr()` 需要额外参数 `RelDataType`。修复 RelToSqlConverter 和 MycatImplementor。

- [ ] **Step 6: 修复 SqlBasicCall 构造器**

Calcite 1.42 的 `SqlBasicCall` 构造器签名变更（5 参数 → 4 参数或不同组合）。修复 SqlValidatorImpl 中的 1 处。

- [ ] **Step 7: 修复 WithScope 构造器**

Calcite 1.42 的 `WithScope` 新增 `WithRecursiveScope` 参数。修复 SqlValidatorImpl 中的 1 处。

- [ ] **Step 8: 修复 SqlToRelConverter.configBuilder()**

方法可能已重命名或移动。查看 calcite-core 的 `SqlToRelConverter` 1.42 API。

- [ ] **Step 9: 修复 SqlTypeUtil.isBigint/isDouble**

方法可能在 1.42 中移除。替代方案：`SqlTypeUtil.isBigint()` → `type.getSqlTypeName() == SqlTypeName.BIGINT`。

- [ ] **Step 10: 修复 bindObservable**

`ArrayBindable.bindObservable()` 在 1.42 中可能已移除。查看替代方法。

- [ ] **Step 11: 修复 RepeatUnion 构造器**

Calcite 1.42 新增 `RelOptTable` 参数。修复 MycatRepeatUnion。

- [ ] **Step 12: 修复 final traitSet**

MycatTableLookupValues 和 MycatQuery 中尝试赋值 final `traitSet` 字段。改为使用 `this.traitSet = traitSet` 只在构造器中赋值，或使用 `copy()` 方法。

- [ ] **Step 13: 编译验证**

- [ ] **Step 14: Commit**

```bash
git commit -m "fix(hbt): adapt Calcite 1.42 API removals and signature changes"
```

---

## Task 7: 类型系统变更

**Files (2 个文件，~20 个错误):**
- Modify: `hbt/src/main/java/io/mycat/calcite/rewriter/MycatAggregateExpandDistinctAggregatesRule.java` (~14 处)
- Modify: `hbt/src/main/java/io/mycat/calcite/localrel/MycatAggregateUnionTransposeRule.java` (~4 处)

**错误类型：** Calcite 1.42 中 `Aggregate.groupSet` 从 `ImmutableIntList` (List<Integer>) 变为 `ImmutableList<RexNode>` 或类似类型变更。

- [ ] **Step 1: 分析 Calcite 1.42 Aggregate API 变更**

查看 calcite-core 中 `Aggregate` 类和相关方法的 1.42 API，理解 `groupSet`/`groupSets` 的类型变更。

- [ ] **Step 2: 修复 MycatAggregateExpandDistinctAggregatesRule**

将所有 `List<Integer>` → `List<RexNode>` 的转换适配。可能需要使用 `RexInputRef` 将整数索引转换为 RexNode 引用。

- [ ] **Step 3: 修复 MycatAggregateUnionTransposeRule**

修复推断变量类型不兼容的问题。

- [ ] **Step 4: 修复 ImmutableXxxConfig.of() 方法**

`ImmutableMycatAggregateExpandDistinctAggregatesRule.Config.of()` 方法不存在，改为 `.builder().build()`。

- [ ] **Step 5: 编译验证**

- [ ] **Step 6: Commit**

```bash
git commit -m "fix(hbt): adapt Aggregate groupSet type changes for Calcite 1.42"
```

---

## Task 8: 全量编译验证与清理

- [ ] **Step 1: 运行完整编译**

Run: `$env:JAVA_HOME = 'D:\Program Files\BellSoft\LibericaJDK-21-Full'; mvn compile -T 4 2>&1 | Out-File .qoder/compile-final.txt -Encoding UTF8`
Expected: BUILD SUCCESS for all 18 modules

- [ ] **Step 2: 如果仍有错误，继续修复**

分析残余编译错误，逐一修复。

- [ ] **Step 3: 最终 Commit**

```bash
git add -A
git commit -m "fix(hbt): Calcite 1.42 compilation baseline - all modules pass"
```

---

## 附录：错误-任务映射表

| 错误文件 | 错误数 | 所属 Task |
|----------|--------|----------|
| MycatCalc.java | 16 | Task 6a |
| LocalRules.java | 16 | Task 2 |
| MycatWindow.java | 14 | Task 3 + Task 6b/6k |
| MycatAggregateExpandDistinctAggregatesRule.java | 14 | Task 7 |
| MycatCalciteMySqlNodeVisitor.java | 12 | Task 5 + Task 6c/6d |
| MycatCalciteSupport.java | 12 | Task 6h/6i + Task 1 |
| SqlValidatorImpl.java | 10 | Task 3 + Task 6c/6f/6g |
| MycatNestedLoopJoin.java | 8 | Task 6b + Task 1 |
| MycatSortMergeJoin.java | 8 | Task 6b + Task 1 |
| MycatHashJoin.java | 8 | Task 6b + Task 1 |
| LogicTable.java | 4 | Task 5 |
| MycatRules.java | 4 | Task 4 |
| MycatCorrelate.java | 4 | Task 1 + Task 6b |
| MycatSQLTableLookup.java | 4 | Task 1 |
| MycatHint.java | 4 | Task 5 |
| MycatAggregateUnionTransposeRule.java | 4 | Task 2 + Task 7 |
| MycatHashAggregate.java | 4 | Task 6b + Task 1 |
| PrepareExecutor.java | 4 | Task 6j |
| LocalWindow.java | 4 | Task 3 |
| ToViewTableScanRule.java | 2 | Task 2 |
| StreamMycatEnumerableRelImplementor.java | 2 | Task 1 |
| RelToSqlConverter.java | 2 | Task 6e |
| MycatFilterJoinRule.java | 2 | Task 2 |
| MycatAggDistinctRule.java | 2 | Task 2 |
| MycatImplementor.java | 2 | Task 6e |
| SqlImplementor.java | 2 | Task 6c |
| MycatSortAgg.java | 2 | Task 1 |
| MycatOutputVisitor.java | 2 | Task 5 |
| CreateTableUtils.java | 2 | Task 5 |
| MycatViewIndexViewRule.java | 2 | Task 2 |
| MycatTableLookupValues.java | 2 | Task 6l |
| MycatQuery.java | 2 | Task 6l |
| MycatSortMergeSemiJoin.java | 2 | Task 6b |
| LocalProject.java | 2 | Task 4 |
| MycatEnumerableRelImplementor.java | 2 | Task 1 |
| MycatRelDataTypeFactory.java | 2 | Task 3 |
| LocalJoin.java | 2 | Task 4 |
| MysqlMetadataManager.java | 2 | Task 5 |
| LocalCorrelate.java | 2 | Task 4 |
| MycatRepeatUnion.java | 2 | Task 6k |
| LocalTableScan.java | 2 | Task 6b |
| RexToLixTranslator.java | 2 | Task 3 |
