# Calcite Fork 策略分析与渐进清理设计

## 背景

Mycat2 项目正在进行 JDK 21 升级（Phase 0），将 Calcite 从 1.26.0 升级到 1.42.0。编译基线检查发现 42 个文件、约 149 个编译错误，主要是 Calcite 1.42 内部 API 变更导致的兼容性问题。

在进行盲目修复之前，我们基于第一性原理重新审视了 Calcite fork 策略。

## Druid 与 Calcite 在 Mycat2 中的角色

### 架构管线

```
SQL 文本 → [Druid 解析] → Druid AST → [AST 转换] → Calcite SqlNode
    → [Calcite 验证/优化] → RelNode → [RBO/CBO] → MycatRel
    → [RelToSqlConverter] → SQL 文本 → 下发到后端 MySQL
```

### Druid 角色：MySQL 兼容的 SQL 前端

- **SQL 解析**：`SQLUtils.parseSingleMysqlStatement()` 解析所有入口 SQL
- **MySQL 方言 AST**：完整支持 MySQL 特有语法（LIMIT、反引号、GROUP_CONCAT、Hint 等）
- **SQL 序列化输出**：`MycatOutputVisitor`（166.7KB）将 AST 转回 SQL 文本
- **常量表达式求值**：`MycatSQLEvalVisitorUtils` 做常量折叠
- **DML 类型分发**：根据 INSERT/UPDATE/DELETE 类型走不同编译路径

**为什么不用 Calcite parser？** Calcite 的 SQL parser 是标准 SQL 方言，不支持 MySQL 特有语法。

### Calcite 角色：查询优化中台

- **SQL 验证**：`SqlValidator` 校验语义正确性
- **SQL → RelNode**：`SqlToRelConverter` 将 SQL AST 转为关系代数
- **RBO 优化**：`HepPlanner` 做谓词下推、投影裁剪、Join 重排等
- **CBO 优化**：`VolcanoPlanner` + `MycatConvention` 做物理计划选择
- **37 个物理算子**：MycatProject, MycatFilter, MycatHashJoin 等
- **150+ MySQL 函数**：通过自定义 `SqlOperatorTable` 注册
- **RelNode → SQL**：`RelToSqlConverter` + `MycatImplementor` 反向生成 SQL
- **类型系统**：`MycatTypeSystem` 适配 MySQL 类型语义
- **SPM 计划管理**：计划缓存、基线管理

### 边界总结

- **Druid 是"前端"**：负责 MySQL 协议兼容的 SQL 解析和 SQL 输出
- **Calcite 是"中端"**：负责语义验证、关系代数优化、物理计划生成、RelNode → SQL 反向生成
- 两者通过 `MycatCalciteMySqlNodeVisitor`（Druid AST → Calcite AST）和 `MycatImplementor`（RelNode → Calcite SQL AST）连接

## Calcite Fork 现状分析

### 层面 1：calcite-core 模块（~1651 文件完整 fork）

**实际 Mycat 修改仅 5 处**：

| 文件 | 修改 | 是否不可避免 |
|------|------|-------------|
| `EnumerableRelImplementor.java` | 2 处 `private → protected` | 是 — bridge 子类需要访问 |
| `RexImpTable.java` | 添加 `MycatSqlDefinedFunction` 分发 | 是 — 无 SPI 替代 |
| `CalciteCatalogReader.java` | `private → public` | 否 — 可通过继承替代 |
| `SqlLiteral.java` | 添加 `LocalTime/LocalDateTime` 转换 | 否 — 可通过自定义类型工厂 |
| `SqlTypeName.java` | 添加 7 个 MySQL INTERVAL 类型 | 否 — 可放在 bridge 中处理 |

**3 个新增文件**（`org.apache.calcite.mycat` 包，不属于 fork 修改）：
- `MycatBuiltInMethod.java` — Mycat 类型转换方法枚举
- `MycatBuiltInMethodImpl.java` — MySQL 兼容时间/日期函数实现
- `MycatSqlDefinedFunction.java` — Mycat 自定义 SQL 函数基类

### 层面 2：hbt bridge 文件（~16 个 org.apache.calcite 包名覆盖）

| 文件 | 行数 | Mycat 实际定制 | 能否用 SPI/扩展替代 |
|------|------|---------------|-------------------|
| `SqlValidatorImpl.java` | 6635 | **3 行**（处理 `<=>` 运算符类型推导） | **是 — `SqlFunction.deriveType()`** |
| `RexToLixTranslator.java` | 2996 | ~20 行（`<=>` 运算符代码生成） | 部分可能 — 自定义 RexVisitor |
| `RelMd*.java`（6 个） | ~2000 | ~50 行（MycatView/ParamHolder 元数据） | **是 — `RelMetadataProvider` SPI** |
| `DataContext.java` | ~100 | ~30 行（Mycat 会话上下文） | **是 — 接口实现** |
| `RelJson/RelJsonWriter` | ~500 | ~20 行（MycatView 序列化） | 是 — 外部处理 |
| `RexLiteral.java` | ~800 | ~10 行（参数化查询 ParamHolder） | 是 — 包装类 |

**核心发现**：16 个 bridge 覆盖文件总计 ~14000 行，但 Mycat 实际定制仅 ~130 行（< 1%）。

### 层面 3：io.mycat.calcite.* 扩展（~393 文件）

这是纯扩展层，设计正确，不需要 fork：
- 37 个物理算子、150+ MySQL 函数、30 个 RBO 重写器、36 个 CBO 规则
- SPM 计划管理、表抽象等

## 编译错误分析

42 个文件的 ~149 个编译错误分布：

| 类别 | 文件数 | 错误数 | 说明 |
|------|--------|--------|------|
| io.mycat.calcite.* 扩展文件 | 40 | ~137 | Mycat 扩展代码使用了 Calcite 1.26 内部 API |
| org.apache.calcite.* bridge 覆盖 | 2 | ~12 | SqlValidatorImpl(10) + RexToLixTranslator(2) |

**关键结论**：无论是否 fork，这 137 个扩展文件错误都需要修复。它们是"使用 Calcite"的成本，不是"fork Calcite"的成本。

### 错误类型分布

| 错误类型 | 数量 | 代表文件 |
|----------|------|---------|
| `Config.EMPTY` 移除 | ~13 | LocalRules, MycatRules, MycatFilterJoinRule |
| `getHints()` API 变更 | ~6 | MycatCalc, LocalTableScan |
| `NO_PARAMS`/`NO_EXPRS` 常量移除 | ~5 | MycatCalciteSupport, MycatHint |
| 抽象方法未实现 | ~8 | MycatWindow, LocalWindow, SqlValidatorImpl |
| 构造器签名变更 | ~5 | MycatRepeatUnion, MycatCalciteMySqlNodeVisitor |
| 方法签名变更 | ~10 | MycatSQLTableLookup, MycatAggregateExpandDistinctAggregatesRule |
| 类型变更 (List<Integer>→List<RexNode>) | ~15 | MycatAggregateExpandDistinctAggregatesRule |
| Druid API 变更 | ~5 | MycatOutputVisitor, MycatCalciteMySqlNodeVisitor |
| 其他分散错误 | ~82 | 30+ 个文件 |

## 设计方案：渐进清理（方案 B）

### 原则

1. **能用 Calcite 原来的代码最好** — 逐步消除不必要的覆盖
2. **需要改写就用 fork** — calcite-core 的 5 处不可避免的修改保留
3. **fork 不行再用桥接** — 最小化 bridge 覆盖层

### Step 1：修复 149 个编译错误（最高优先级）

逐一修复 42 个文件的编译错误，使项目全量编译通过。按错误类型分批处理：

- **批次 1**：Config.EMPTY → ImmutableBuilder 模式（13 errors, 8 files）
- **批次 2**：getHints()/NO_PARAMS/NO_EXPRS 常量适配（11 errors, 6 files）
- **批次 3**：抽象方法实现（8 errors, 5 files）
- **批次 4**：构造器/方法签名适配（15 errors, 8 files）
- **批次 5**：类型变更适配（15 errors, 3 files）
- **批次 6**：Druid API 适配（5 errors, 2 files）
- **批次 7**：其余分散错误（82 errors, 30 files）

每批修复后验证编译。

### Step 2：消除 SqlValidatorImpl 6635 行覆盖

**现状**：6635 行覆盖文件只为 3 行定制

```java
// hbt SqlValidatorImpl.java line 5769-5771
if (operator == StrictEqualFunction.INSTANCE) {
    return MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.BOOLEAN);
}
```

**改法**：
1. 让 `StrictEqualFunction` 重写 `SqlFunction.deriveType()` 方法，自行返回 BOOLEAN 类型
2. 删除 `hbt/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java`（6635 行）
3. Calcite 1.42 原版 SqlValidatorImpl 生效
4. 验证编译和运行时行为

### Step 3：RelMd* 元数据覆盖改为 SPI

**现状**：6 个 `org.apache.calcite.rel.metadata.RelMd*` 覆盖文件

**改法**：
1. 创建 `MycatRelMetadataProvider implements RelMetadataProvider`
2. 将 MycatView/ParamHolder 的元数据逻辑注册到 Provider 中
3. 在 `MycatCalciteSupport` 初始化时注册 Provider
4. 删除 6 个 bridge 覆盖文件
5. 验证编译和运行时行为

### Step 4：DataContext 覆盖改为接口实现

**现状**：`org.apache.calcite.DataContext` 覆盖

**改法**：
1. 创建 `MycatDataContext implements DataContext`（如果尚未存在）
2. 确保 Calcite 代码中通过 DataContext 接口引用，不依赖具体实现类
3. 删除 bridge 覆盖文件

### 优先级与时间线

| Step | 优先级 | 预计时间 | 阻塞关系 |
|------|--------|---------|---------|
| Step 1 | P0 — 阻塞 JDK 21 升级 | 3-5 天 | 无 |
| Step 2 | P1 — 消除最大覆盖 | 0.5 天 | 依赖 Step 1 |
| Step 3 | P1 — 消除元数据覆盖 | 1-2 天 | 依赖 Step 1 |
| Step 4 | P2 — 清理 DataContext | 0.5 天 | 依赖 Step 1 |

## 不变的部分

以下设计决策保持不变：

1. **calcite-core fork 模块保留**：5 处不可避免的修改 + 3 个新增文件
2. **Druid fork 保留**：MycatOutputVisitor 等是必要的 MySQL 方言适配
3. **io.mycat.calcite.* 扩展层保留**：设计正确，不需要变更
4. **双引擎架构保留**：Druid 做前端解析 + Calcite 做中端优化

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| Step 1 修复可能引入运行时错误 | 每批修复后运行测试套件 |
| Step 2 SqlValidatorImpl 删除可能遗漏隐藏定制 | diff 对比确认只有 3 行定制 |
| Step 3 RelMetadataProvider SPI 可能与 Calcite 1.42 不完全兼容 | 先做原型验证 |
| 渐进清理可能在 Step 2-4 被搁置 | 每个 Step 独立可交付 |
