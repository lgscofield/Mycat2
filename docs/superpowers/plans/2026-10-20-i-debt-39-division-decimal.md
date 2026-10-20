# I-DEBT-39·tranche①（宽）— `/` 除法语义对齐 MySQL 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Mycat 本地计算的 MySQL `/` 输出与真 MySQL 逐位一致的十进制商（不整除截断），并把本地除零 `1/0` 返 NULL；`DIV`、近似型路、下推路不受影响。

**Architecture:** 两接缝协作，均复用既有已装配机制——① `MycatTypeSystem.deriveDecimalDivideType`（校验期把"纯精确数值"派生成 `DECIMAL(p, 被除数scale+4)`，决定列头类型）；② `MycatCalciteRuntimeHooks.remapOperator` 把 `op==SqlStdOperatorTable.DIVIDE` 在出码期重映射成新建的 `MysqlDivideFunction`（`MycatSqlDefinedFunction` 子类，自带 `Object,Object` 求值实现：十进制商 + 除零→null + 近似→double），走 `RexToLixTranslator.visitCall:1512` 单一既有 fork 接缝。`<=>`→`StrictEqualFunction` 是本模式已在产的先例。

**Tech Stack:** Java 21（LibericaJDK-21-Full）、Calcite fork（calcite-core, `org.apache.calcite.mycat`）、hbt、internalexample 测试、Janino codegen、Docker MySQL/MariaDB。

**Spec:** `docs/superpowers/specs/2026-10-20-i-debt-39-division-decimal-design.md`

## Global Constraints

- 构建/测试须用 JDK 21：`D:\Program Files\BellSoft\LibericaJDK-21-Full`；Maven `D:\Program Files\apache-maven-3.9.9\bin\mvn.cmd`。
- 单模块跑测：`mvn -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`（`-am` 带上游；**勿**用 `-DfailIfNoTests=false`，上游 config 模块会报 no-tests）。`-q` 抑制 surefire 摘要，读 `target/surefire-reports/*.txt` 判绿。
- 权威后端 = 真实 MySQL **5.7.44** Docker 容器 `t57`（宿主 `0.0.0.0:3357`，root/123456，persona MYSQL_5_7）。注：`my57-3306` 因宿主 3306 被 PID 50440 占用起不来，本计划一律用 `t57`。
- 不 push、不开 PR、不动他人环境；每任务末本地 commit。
- 宁挂账不伪绿：warning / DECIMAL 精度溢出→NULL / DML `ERROR_FOR_DIVISION_BY_ZERO` **不在本票**（spec §3），e2e 不因这几处未对齐而谎报全绿。
- 探针/临时件跑完即删，产品码零残留。

## Review Focus

（spec 隐含但单任务测试未必覆盖、最易翻车的输入类；每条在对应任务钉一个测试）
1. **近似型操作数须保持 DOUBLE**：`10.0e0/4`、`double/整` 不能被误扳成 DECIMAL（值与列头都要 DOUBLE）——Task 1 方法级 + Task 4 e2e 列头。
2. **常量折叠 ≡ 逐行**：`select 10/3`（字面量，plan 期折叠）与 `select col/3`（逐行）必须同结果同类型，否则 §4.2 fold 分叉——Task 3 codegen + Task 4 e2e 两形态。
3. **`DIV` 与 `%`/MOD 不受损**：`10 DIV 3` 仍整数 3；MOD 未波及——Task 1/Task 3。
4. **列头类型/scale 元数据**：结果列对外声明须为 DECIMAL 且 scale=被除数scale+4（客户端无缝的一环，非仅值对）——Task 2 类型断言 + Task 4 列头比对。
5. **NULL 传播与除零分家**：任一操作数 NULL→NULL；除零→NULL；两者别混（除零不吞真 NULL 语义）——Task 1。

---

## File Structure

- Modify `hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java`（内类 `MycatTypeSystem.deriveDecimalDivideType`，约 L672-684）— 校验期 MySQL 十进制派生型。
- Create `hbt/src/main/java/io/mycat/calcite/sqlfunction/arith/MysqlDivideFunction.java` — `MycatSqlDefinedFunction` 子类 + 静态 `mysqlDivide(Object,Object)` 求值实现（本票唯一新增值逻辑载体）。
- Modify `hbt/src/main/java/io/mycat/calcite/MycatCalciteRuntimeHooks.java`（`remapOperator`，L37-40）— 追加 `op==DIVIDE → MysqlDivideFunction.INSTANCE`。
- Test `hbt/src/test/java/io/mycat/calcite/sqlfunction/arith/MysqlDivideFunctionTest.java`（新建）— 值/scale/类型/除零/近似/NULL/DIV 方法级与类型级锁。
- Test `internalexample/src/test/java/io/mycat/drdsrunner/ScalarStdFuncLocalCodegenTest.java`（扩展，已存在）— `planAndCodegen` codegen 锁（含常量折叠形态）。
- Test（探针）`.superpowers/p60/Probe39Live.java` 复用/扩展 — 活库 e2e 对拍（Task 4）。

---

## Task 1: MysqlDivideFunction 值语义（纯方法，无管线依赖）

**Files:**
- Create: `hbt/src/main/java/io/mycat/calcite/sqlfunction/arith/MysqlDivideFunction.java`
- Test: `hbt/src/test/java/io/mycat/calcite/sqlfunction/arith/MysqlDivideFunctionTest.java`

**Interfaces:**
- Produces: `public static final MysqlDivideFunction INSTANCE`；`public static Object mysqlDivide(Object num, Object den)`；`public static final int DIV_PRECISION_INCREMENT = 4`。Task 2 复用该常量；Task 3 复用 `INSTANCE`。
- 求值契约（据 spec §2 + t57 实测）：
  - `num==null || den==null` → `null`
  - 任一侧 `Double/Float` → `((Number)num).doubleValue()/((Number)den).doubleValue()`，除零(`den==0.0`)→`null`（近似路，值 DOUBLE）
  - 否则按 `BigDecimal`：`bden.signum()==0` → `null`；`bnum.divide(bden, bnum.scale()+DIV_PRECISION_INCREMENT, RoundingMode.HALF_UP)`（返回 BigDecimal，scale=被除数scale+4）

- [ ] **Step 1: 写失败测试** — `MysqlDivideFunctionTest`

```java
@Test public void decimalQuotientMatchesMySQL() {
    assertEquals(new BigDecimal("3.3333"), MysqlDivideFunction.mysqlDivide(10L, 3L));     // int/int scale0→4
    assertEquals(new BigDecimal("3.5000"), MysqlDivideFunction.mysqlDivide(7L, 2L));
    assertEquals(new BigDecimal("2.50000"), MysqlDivideFunction.mysqlDivide(new BigDecimal("10.0"), 4L)); // scale1→5
    assertEquals(new BigDecimal("3.33333"), MysqlDivideFunction.mysqlDivide(new BigDecimal("10.0"), 3L));
}
@Test public void divideByZeroYieldsNull() {
    assertNull(MysqlDivideFunction.mysqlDivide(1L, 0L));
    assertNull(MysqlDivideFunction.mysqlDivide(new BigDecimal("1.0"), BigDecimal.ZERO));
}
@Test public void nullOperandPropagates() {
    assertNull(MysqlDivideFunction.mysqlDivide(null, 3L));
    assertNull(MysqlDivideFunction.mysqlDivide(10L, null));
}
@Test public void approximateStaysDouble() {
    assertEquals(2.5d, (Double) MysqlDivideFunction.mysqlDivide(10.0d, 4.0d), 1e-9);
    assertNull(MysqlDivideFunction.mysqlDivide(5.0d, 0.0d)); // 近似除零也 null
}
```
注意：整型入参此处直接传 `Long`（对齐 I-DEBT-60 整数组恒 boxed Long 表征）；`mysqlDivide` 用 `instanceof BigDecimal/Number` 归一，勿依赖具体整型包装类。

- [ ] **Step 2: 跑测试确认失败** — Run: `mvn -pl hbt -am test -Dtest=MysqlDivideFunctionTest -Dsurefire.failIfNoSpecifiedTests=false`；Expected: 编译失败/找不到符号 `MysqlDivideFunction`。

- [ ] **Step 3: 实现 `MysqlDivideFunction.java`** — 仿 `StrictEqualFunction`（同包族 `cmpfunction` 的写法）：
  - `class MysqlDivideFunction extends MycatSqlDefinedFunction`，`public static final int DIV_PRECISION_INCREMENT = 4;`
  - `public static Object mysqlDivide(Object num, Object den)` 按上方求值契约实现（`toBigDecimal(Object)` 私有辅助：`Number` → `BigDecimal`，整型走 `BigDecimal.valueOf(((Number)x).longValue())` 使 scale=0）。
  - `ScalarFunction scalarFunction = MycatScalarFunction.create(MysqlDivideFunction.class, "mysqlDivide", 2);`
  - `INSTANCE` 构造：`returnTypeInference` 用 opBinding 判近似→DOUBLE、否则 DECIMAL(scale=t0.getScale()+DIV_PRECISION_INCREMENT)（与 Task 2 对齐；此路在 codegen 生效即可，校验期以 Task 2 为准，两处取值须一致）；`deriveType` 覆写返回同上类型且 nullable；`unparse` 用 `SqlUtil.unparseBinarySyntax(this, call, writer, leftPrec, rightPrec)` 吐 `/`。
  - 构造传参参照 `StrictEqualFunction` 的 `MycatSqlDefinedFunction.GetOperandTypes` 用法（Object/Object 参数使族校验跳过 CAST 强加，见 `MycatSqlDefinedFunction.mycatCoerceOperands` 对 `ANY` 的 continue 分支）。

- [ ] **Step 4: 跑测试确认通过** — 同 Step 2 命令；Expected: `MysqlDivideFunctionTest` 全绿。

- [ ] **Step 5: Commit** — `git add hbt/src/main/java/io/mycat/calcite/sqlfunction/arith/MysqlDivideFunction.java hbt/src/test/java/io/mycat/calcite/sqlfunction/arith/MysqlDivideFunctionTest.java && git commit -m "feat(hbt): MysqlDivideFunction value semantics (MySQL decimal quotient scale=n+4 HALF_UP, div-zero->null, approx->double, null propagate) for I-DEBT-39"`

---

## Task 2: 校验期 DECIMAL 派生型（deriveDecimalDivideType）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatCalciteSupport.java`（`MycatTypeSystem.deriveDecimalDivideType`，L672-684）
- Test: `hbt/src/test/java/io/mycat/calcite/MysqlTypeSystemDivideTest.java`（新建）

**Interfaces:**
- Consumes: `MysqlDivideFunction.DIV_PRECISION_INCREMENT`（Task 1）。
- Produces: `MycatTypeSystem.deriveDecimalDivideType(tf, t1, t2)` 对纯精确数值返回 `DECIMAL(p, t1.scale+4)` nullable；近似/其它交回 `super`。

- [ ] **Step 1: 写失败测试** — `MysqlTypeSystemDivideTest`

```java
@Test public void intIntDerivesDecimalScale4() {
    RelDataType i = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
    RelDataType out = new MycatCalciteSupport.MycatTypeSystem()
        .deriveDecimalDivideType(MycatCalciteSupport.TypeFactory, i, i);
    assertEquals(SqlTypeName.DECIMAL, out.getSqlTypeName());
    assertEquals(4, out.getScale());
}
@Test public void decimalScale1DerivesScale5() {
    RelDataType d = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.DECIMAL, 10, 1);
    RelDataType i = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
    RelDataType out = new MycatCalciteSupport.MycatTypeSystem()
        .deriveDecimalDivideType(MycatCalciteSupport.TypeFactory, d, i);
    assertEquals(SqlTypeName.DECIMAL, out.getSqlTypeName());
    assertEquals(5, out.getScale());
}
@Test public void approximateDerivesNonDecimal() {
    RelDataType dbl = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.DOUBLE);
    RelDataType i = MycatCalciteSupport.TypeFactory.createSqlType(SqlTypeName.INTEGER);
    RelDataType out = new MycatCalciteSupport.MycatTypeSystem()
        .deriveDecimalDivideType(MycatCalciteSupport.TypeFactory, dbl, i);
    assertTrue(out == null || out.getSqlTypeName() != SqlTypeName.DECIMAL); // 近似不扳 DECIMAL，交回 super→DOUBLE
}
```
（若 `MycatTypeSystem` 非静态可见，用 `MycatCalciteSupport.TypeSystem` 单例调 `deriveDecimalDivideType`。）

- [ ] **Step 2: 跑测试确认失败** — Run: `mvn -pl hbt -am test -Dtest=MysqlTypeSystemDivideTest -Dsurefire.failIfNoSpecifiedTests=false`；Expected: int/int 现落 super 返 null（或 DECIMAL 判定不符）→ FAIL。

- [ ] **Step 3: 实现** — 覆写 `deriveDecimalDivideType`：
  - 取 `t1.getSqlTypeName()`/`t2`；若**两侧皆精确数值族**（`SqlTypeFamily.EXACT_NUMERIC`，含 INTEGER/BIGINT/DECIMAL/SMALLINT/TINYINT）→ `tf.createTypeWithNullability(tf.createSqlType(SqlTypeName.DECIMAL, <precision>, t1.getScale()+DIV_PRECISION_INCREMENT), t1.isNullable()||t2.isNullable())`。precision 取足够覆盖 MySQL `/` 结果的最大位数（先用 `Integer.MAX_VALUE` 会被 Calcite 拒，须用类型系统 DECIMAL 上限；实现期取 `tf.getTypeSystem().getMaxPrecision(SqlTypeName.DECIMAL)`（Mycat 里=65）并 `Math.min(65, ...)`，若与 t57 列头精度不符由 Task 4 校准）。
  - 否则（含任一侧近似）→ `return super.deriveDecimalDivideType(tf, t1, t2);`（保持近似路落 DOUBLE 不动）。

- [ ] **Step 4: 跑测试确认通过** — 同 Step 2；Expected: 三条全绿。

- [ ] **Step 5: Commit** — `git commit -am "feat(hbt): deriveDecimalDivideType returns MySQL-faithful DECIMAL(scale=n+div_precision_increment) for exact-numeric operands, approx untouched (I-DEBT-39)"`

---

## Task 3: 出码/常量折叠接入 MysqlDivideFunction（remapOperator 接缝）

**Files:**
- Modify: `hbt/src/main/java/io/mycat/calcite/MycatCalciteRuntimeHooks.java`（`remapOperator` L37-40）
- Test: `internalexample/src/test/java/io/mycat/drdsrunner/ScalarStdFuncLocalCodegenTest.java`（扩展 `divisionLocalCodegens`）

**Interfaces:**
- Consumes: `MysqlDivideFunction.INSTANCE`（Task 1）、`SqlStdOperatorTable.DIVIDE`。
- Produces: 出码期 `op==DIVIDE` → MysqlDivideFunction；`DIVIDE_INTEGER` 与 `<=>` 行为不变。

- [ ] **Step 1: 写失败测试** — 在 `ScalarStdFuncLocalCodegenTest` 加

```java
@Test public void divisionLocalCodegens() {
    // 常量折叠形态（无表）与可逐行形态都不得崩，且断言 plan 里除法走 Mycat 自定义实现
    Assert.assertNotNull(planAndCodegen("select 10/3"));
    Assert.assertNotNull(planAndCodegen("select 1/0"));
    Assert.assertNotNull(planAndCodegen("select 10 DIV 3")); // DIV 仍整除，不被 remap
}
```
（值/类型对拍的权威断言在 Task 4 e2e；本任务 codegen 级锁"不崩 + 不被整除截断路径选中"。若 harness 可 dump plan 串，追加断言 `select 10/3` 计划含 `MYSQLDIVIDE`/`MysqlDivideFunction`，`10 DIV 3` 含 `DIVIDE_INTEGER`。）

- [ ] **Step 2: 跑测试确认失败** — Run: `mvn -pl internalexample -am test -Dtest=ScalarStdFuncLocalCodegenTest -Dsurefire.failIfNoSpecifiedTests=false`；Expected: `select 1/0` codegen 现崩 `ArithmeticException / by zero`（或 plan 仍选内置整除 implementor）→ FAIL。

- [ ] **Step 3: 实现** — 改 `remapOperator`：

```java
@Override public SqlOperator remapOperator(SqlOperator op) {
    if (op == SqlStdOperatorTable.DIVIDE) {
        return MysqlDivideFunction.INSTANCE;   // 仅 "/"，不含 DIVIDE_INTEGER
    }
    return "<=>".equalsIgnoreCase(op.getName()) ? StrictEqualFunction.INSTANCE : op;
}
```
按算子实例判（非 SqlKind，避免 `SqlKind.DIVIDE` 撞 `DIVIDE_INTEGER`，见 spec §4.1/§1）。`DIVIDE_INTEGER` 不进此分支 → `DIV` 原样。

- [ ] **Step 4: 跑测试确认通过** — 同 Step 2；Expected: 三条全绿。若 `select 1/0` 常量折叠仍崩（说明折叠路径未走 `visitCall:1512` 的 remap），在 `MycatRexExecutor`/常量折叠处同样把 `DIVIDE` 归到 `MysqlDivideFunction`（保持与出码同一 `implement`），直到该用例经**同一条**求值路径变绿——不得靠 try/catch 吞异常伪绿。

- [ ] **Step 5: hbt 全量回归** — Run: `mvn -pl hbt -am test`（读 surefire 汇总）；Expected: 全绿（重点看 AVG/STDDEV/VARIANCE/REGR 与 plan 串断言测试，如 `DistinctTest` `avg(f)=[CAST($0):DOUBLE]` 是否因类型/计划扰动变红；若红，据实修正断言或机制，记录于本步）。

- [ ] **Step 6: Commit** — `git commit -am "feat(hbt): remap DIVIDE->MysqlDivideFunction at codegen seam (constant-fold==row parity), DIV untouched (I-DEBT-39)"`

---

## Task 4: 活库 e2e 对拍真 MySQL（值+列头/scale 逐位一致 + blast 兜底）

**Files:**
- Test/探针: `.superpowers/p60/Probe39Live.java`（扩展为对拍）、`internalexample` 相关断言（若收编则改测试，否则探针跑完即删）

**Interfaces:**
- Consumes: 完整 Mycat 服务端（8066）+ 真实 MySQL `t57`（3357）。

- [ ] **Step 1: 起服务端** — 用 `.superpowers/start-server.ps1`（persona MYSQL_5_7，fat-jar `server-1.22-release-jar-with-dependencies.jar`）后台起 Mycat，settle 等待初始化完成（避免 vertx FailedFuture 假错），核 8066 监听。

- [ ] **Step 2: 写对拍探针** — `Probe39Live` 对 Mycat(8066) 与 t57 真库(3357) 各跑同一组表达式，逐格比 **值 + `ResultSetMetaData` 列类型/scale**：
  `10/3`、`7/2`、`10.0/4`、`10.0/3`、`1/0`、`10 DIV 3`、`<doublecol>/2`（近似）、`NULL/2`；**构造不可下推形态**（i_s 虚表投影 / 无表 `select`）确保走本地计算（防下推遮蔽假绿）。两侧一致才 PASS。

- [ ] **Step 3: 编译运行对拍** — Run: `.superpowers/run-probe39live.ps1`（classpath 外挂 `mysql-connector-j`）；Expected: verdict=PASS；`1/0` 两侧均 NULL、`10/3`=3.3333 且列型 DECIMAL scale4、近似路两侧 DOUBLE、`10 DIV 3`=3。

- [ ] **Step 4: 全量回归网** — `mvn -pl hbt -am test` 与 `mvn -pl internalexample -am test` 全绿（含 Task 3 Step 5 关注的聚合/plan 串测试）。任何红据实修，不挂"应该没问题"。

- [ ] **Step 5: 记录 + 清理 + 更新 DEBT-QUEUE** — 探针结论写入 `DEBT-QUEUE.md` I-DEBT-39 行（tranche①-宽 落地：主干+除零逐位一致；warning/溢出/DML 留 ②③④，**不伪关**）；删 `.superpowers` 临时探针/脚本（`.superpowers` 已 gitignore）；确认无产品码残留。

- [ ] **Step 6: Commit** — `git add docs/superpowers/specs/DEBT-QUEUE.md <测试改动> && git commit -m "test+docs(e2e): I-DEBT-39 tranche-1(wide) live diff vs MySQL5.7 PASS (10/3=3.3333 DECIMAL s4, 1/0=NULL, DIV/approx intact); DEBT-QUEUE updated, residual warning/overflow/DML tracked"`

---

## Self-Review（写完后自查，已在成文时内联执行）

- **Spec coverage**：§2.1 十进制不截断→Task1+2；§2.2 近似→Task1/2 分支；§2.3 除零→Task1+3+4；§2.5 DIV→Task3 判实例+4 对拍；§4.1 类型钩子=Task2；§4.2 fold==codegen=Task3 Step4 守卫；§5 验收=Task3(锁)+Task4(e2e)；§3/§7 残面=Task4 Step5 DEBT-QUEUE。无遗漏。
- **Step scan**：每步单一可执行物；无 "TBD/handle edge"。精度 p 取值给了明确规则 + Task4 校准兜底（非空占位）。
- **Type consistency**：`mysqlDivide`/`DIV_PRECISION_INCREMENT`/`INSTANCE` 跨任务命名一致；`deriveDecimalDivideType` 与函数 `returnTypeInference` scale 口径统一为 `scale+4`。
- **Proportion**：计划体量与 spec 相当，机制以先例（`<=>`）为据，未堆砌整段实现体。
