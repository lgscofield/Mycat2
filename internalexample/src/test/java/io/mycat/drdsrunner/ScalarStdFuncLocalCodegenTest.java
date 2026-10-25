package io.mycat.drdsrunner;

import io.mycat.*;
import io.mycat.calcite.DrdsRunnerHelper;
import io.mycat.calcite.MycatRel;
import io.mycat.calcite.rewriter.OptimizationContext;
import io.mycat.calcite.spm.Plan;
import io.mycat.calcite.spm.PlanImpl;
import io.mycat.config.*;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import io.mycat.sqlhandler.ConfigUpdater;
import io.mycat.util.JsonUtil;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/**
 * I-DEBT-32 回归锁：{@code information_schema} 投影里的 MySQL 专有标量函数必须能本地落码。
 *
 * <p>旧行为：i_s 查询走 Calcite parser，{@code DAYOFWEEK} 解析到标准
 * {@code SqlStdOperatorTable.DAYOFWEEK}（{@code SqlDatePartFunction}），本 fork 的
 * {@code RexImpTable} 无其 codegen 实现者 → {@code cannot translate call} →
 * {@code Unable to implement MycatProject}。正解=经 {@code MycatCalciteSupport.operatorTable}
 * 的 {@code lookupOperatorOverloads}（I-DEBT-32 方案 E）让 Mycat 拥有该名（不再追加标准同名孪生），
 * 并把 {@code DayOfWeekFunction} 落码形参对齐兄弟惯例改为 {@code String}（CHARACTER family，
 * 直配 i_s 的 VARCHAR 列），使校验/折算/落码全链同源可落码（spec
 * {@code 2026-10-19-scalar-function-local-codegen-onboarding-design.md}）。
 */
public class ScalarStdFuncLocalCodegenTest implements MycatTest {

    static DrdsSqlCompiler drds;

    static synchronized void init() {
        if (drds != null) {
            return;
        }
        System.setProperty("mode", "local");
        System.setProperty("testhbt", Boolean.TRUE.toString());
        MycatCore mycatCore = new MycatCore();
        MycatRouterConfig mycatRouterConfig = new MycatRouterConfig();
        LogicSchemaConfig logicSchemaConfig = new LogicSchemaConfig();
        mycatRouterConfig.getSchemas().add(logicSchemaConfig);
        logicSchemaConfig.setSchemaName("db1");

        ShardingTableConfig main = new ShardingTableConfig();
        main.setCreateTableSQL("CREATE TABLE db1.`travelrecord` (\n" +
                "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                "  `user_id` varchar(100) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`)\n" +
                ") ENGINE = InnoDB CHARSET = utf8\n" +
                "DBPARTITION BY mod_hash(id) DBPARTITIONS 2");
        main.setFunction(ShardingFunction.builder().properties(JsonUtil.from("{\n" +
                "\"dbNum\":\"2\",\n" +
                "\"mappingFormat\":\"c${targetIndex}/db1_${dbIndex}/travelrecord_${tableIndex}\",\n" +
                "\"tableNum\":\"1\",\n" +
                "\"storeNum\":2,\n" +
                "\"dbMethod\":\"mod_hash(id)\"\n" +
                "}", Map.class)).build());
        logicSchemaConfig.getShardingTables().put("travelrecord", main);

        mycatRouterConfig.getClusters().add(CreateClusterHint.createConfig("c0", Arrays.asList("ds0"), Collections.emptyList()));
        mycatRouterConfig.getDatasources().add(CreateDataSourceHint.createConfig("ds0", DB1));
        mycatRouterConfig.getDatasources().add(CreateDataSourceHint.createConfig("prototype", DB1));
        ConfigUpdater.load(mycatRouterConfig);
        drds = MetaClusterCurrent.wrapper(DrdsSqlCompiler.class);
    }

    /** 规划 + 落码（触发 codegen）；落码失败会抛异常。 */
    private static String planAndCodegen(String sql) {
        return codegenOf(sql).dumpPlan().replaceAll("\r", " ").replaceAll("\n", " ").trim();
    }

    /** 规划 + 落码，返回生成的 janino 码源串（remap 在出码期生效，只有码源能看到函数名）。 */
    private static String codegenSource(String sql) {
        return codegenOf(sql).getCodeExecuterContext().getCodeContext().getCode();
    }

    private static Plan codegenOf(String sql) {
        init();
        DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sql, "db1");
        OptimizationContext optimizationContext = new OptimizationContext();
        MycatRel dispatch = drds.dispatch(optimizationContext, drdsSqlWithParams);
        return new PlanImpl(dispatch, DrdsExecutorCompiler.getCodeExecuterContext(
                optimizationContext.relNodeContext.getConstantMap(), dispatch, false), drdsSqlWithParams.getAliasList());
    }

    /** 核心：i_s 投影里的 DAYOFWEEK 必须能被本地落码（旧行为=codegen 崩）。 */
    @Test
    public void dayOfWeekOverVisualTableCodegens() {
        String dumped = planAndCodegen("select DAYOFWEEK(DATA_TYPE) from information_schema.parameters");
        Assert.assertNotNull("DAYOFWEEK 本地 codegen 应成功出码，实际计划：" + dumped, dumped);
    }

    /**
     * I-DEBT-60 补全回归锁：整数组（TINYINT/SMALLINT/INTEGER）源 {@code CAST} 必须能本地落码。
     *
     * <p>旧（回归）行为：I-DEBT-60 把 {@code getJavaClass(TINYINT/SMALLINT/INTEGER)} 从 {@code Integer}
     * 扳齐为 {@code Long}（对齐物理行 wire-truth），但 {@code MycatCastOverride} 的 {@code X_TO_Y} 矩阵
     * 里整数组源方法（{@code integerToDouble(Integer)} 等）形参未跟进 → {@code Long} operand 派发不到
     * {@code Integer} 参数 → codegen 抛 {@code "No applicable constructor/method found for actual
     * parameters \"long\""}。正解=源侧整数组归一到 BIGINT（{@code bigintToY(Long)}，方法体等价）。
     * 整型字面量 {@code 10} 即 INTEGER 源，故用字面量即可锁住该派发面。
     */
    @Test
    public void castOnIntegerFamilyCodegens() {
        Assert.assertNotNull(planAndCodegen("select cast(10 as double)"));
        Assert.assertNotNull(planAndCodegen("select cast(10 as float)"));
        Assert.assertNotNull(planAndCodegen("select cast(10 as decimal(10,2))"));
        Assert.assertNotNull(planAndCodegen("select cast(10 as char)"));
    }

    /**
     * I-DEBT-64 回归锁：本地求值路径 CAST 的目标类型表征必须与 MySQL/MariaDB 语义一致。
     *
     * <p>旧行为（{@code MycatCalciteMySqlNodeVisitor.visit(SQLCastExpr)} 把 {@code arguments[0]}
     * 误灌 scale、{@code [1]} 灌 precision）：{@code CHAR(n)}/{@code VARCHAR(n)} 的长度 n 落进 scale、
     * precision=-1 → 目标塌成 Calcite {@code CHAR(1)} → {@code checkExpressionPadTruncate} 按精度 1 把串
     * 截成 1 字符（活库证 {@code cast(10 as char)="1"}）；{@code DECIMAL(M,D)} 则 M/D 全反
     * （证 {@code cast(10 as decimal(10,2))} 目标 {@code DECIMAL(2,10)}）。正解=长度/第一参数进 precision、
     * 第二参数进 scale，且 MySQL {@code CAST AS CHAR} 语义（截 n 不右填充、无长度不限长）= Calcite VARCHAR，
     * 故 cast 字符串目标归一 VARCHAR。本锁钉住**机制面**（目标类型表征），活库差分锁钉**结果面**（逐值）。
     */
    @Test
    public void castTargetTypeMatchesMysqlSemantics() {
        // CHAR(n)/VARCHAR(n) 长度须进 precision，且按不填充的 VARCHAR 表征（非塌成 CHAR(1)）。
        Assert.assertTrue("cast(1234 as char(10)) 目标应为 VARCHAR(10)（截 10 不 pad），实际计划："
                        + planAndCodegen("select cast(1234 as char(10))"),
                planAndCodegen("select cast(1234 as char(10))").contains("VARCHAR(10)"));
        Assert.assertTrue("cast('abcdefghij' as char(3)) 目标应为 VARCHAR(3)，实际计划："
                        + planAndCodegen("select cast('abcdefghij' as char(3))"),
                planAndCodegen("select cast('abcdefghij' as char(3))").contains("VARCHAR(3)"));
        // 无长度 CHAR：不限长、不截 1，须表征为 VARCHAR 而非 CHAR(1)。
        String noLen = planAndCodegen("select cast('hello' as char)");
        Assert.assertTrue("cast('hello' as char) 目标应为 VARCHAR（不限长），实际计划：" + noLen,
                noLen.contains("VARCHAR"));
        Assert.assertFalse("cast('hello' as char) 目标不得塌成 CHAR(1)（旧 bug 根因），实际计划：" + noLen,
                noLen.contains("CHAR(1)"));
        // DECIMAL(M,D)：precision/scale 不得颠倒。
        Assert.assertTrue("cast(10 as decimal(10,2)) 目标应为 DECIMAL(10, 2)，实际计划："
                        + planAndCodegen("select cast(10 as decimal(10,2))"),
                planAndCodegen("select cast(10 as decimal(10,2))").contains("DECIMAL(10, 2)"));
    }

    /**
     * I-DEBT-66 B1（让路面）回归锁：目标为 DECIMAL 的 CAST 必须走 fork 原生带舍入的转换腿
     * （{@code Primitive.decimalDecimalCast}），不得再命中 Mycat 宽松矩阵的裸 identity
     * {@code decimalToDecimal}（原样返回、从不施加目标 scale → {@code cast(1.2345 as decimal(10,2))}
     * 得 {@code 1.2345}，活库证真 MySQL 应缩到 scale2）。
     *
     * <p>正解：{@code MycatCastOverride.translate} 对 DECIMAL 目标返回 {@code null} 让位，交回
     * {@code RexToLixTranslator.getConvertExpression} 的 DECIMAL 腿（逐源型产 {@code *_DECIMAL_CAST_ROUNDING_MODE}
     * 带 precision/scale/舍入模式调用）。Fix-Meta（{@code CalciteConvertors.getRelDataType} 透传列精度标度）
     * 已解除聚合归并腿的伪降 scale CAST，使本让路不再伤 C11（spec §2.8 定谳）。
     */
    @Test
    public void castDecimalTargetUsesForkRoundingLeg() {
        String code = codegenSource("select cast(1.2345 as decimal(10,2))");
        Assert.assertTrue("DECIMAL 目标 CAST 应出码走 fork decimalDecimalCast（带 p/s/舍入模式），实际码：" + code,
                code.contains("decimalDecimalCast"));
        Assert.assertFalse("DECIMAL 目标 CAST 不得再走裸 identity decimalToDecimal（不缩 scale 的旧 seam），实际码：" + code,
                code.contains("decimalToDecimal"));
    }

    /**
     * I-DEBT-66 R1（舍入模式面）回归锁：烘进 DECIMAL 目标 CAST 出码的舍入模式必须是 MySQL/MariaDB 的
     * HALF_UP（四舍五入），而非 {@code RelDataTypeSystem.DEFAULT} 的 DOWN（截断）。
     *
     * <p>活库铁证（spec §2.5/§2.6）：{@code cast(1.2355 as decimal(10,2))} DOWN 得 {@code 1.23}，
     * 真库（MySQL/MariaDB）为 {@code 1.24}。{@code MycatTypeSystem} 覆盖 {@code roundingMode()} 后，
     * fork 出码期经 {@code typeFactory.getTypeSystem().roundingMode()} 烘进常量。
     */
    @Test
    public void castDecimalRoundingModeBakedIsHalfUp() {
        String code = codegenSource("select cast(1.2345 as decimal(10,2))");
        Assert.assertTrue("DECIMAL 目标 CAST 出码须烘入 HALF_UP 舍入模式（MySQL 四舍五入），实际码：" + code,
                code.contains("HALF_UP"));
    }

    /**
     * I-DEBT-39·tranche①（宽）回归锁：MySQL {@code /} 除法必须能本地落码，且出码/常量折叠走 Mycat 自定义实现。
     *
     * <p>旧行为：{@code select 1/0} 走内置 {@code SqlFunctions.divide} 整除路 → 常量折叠/落码抛
     * {@code ArithmeticException / by zero}；{@code select 10/3} 被整除截断为 3（非 MySQL 的 3.3333）。
     * 正解：{@code MycatCalciteRuntimeHooks.remapOperator} 把 {@code SqlStdOperatorTable.DIVIDE} 重映射到
     * {@code MysqlDivideFunction}（经 {@code RexToLixTranslator.visitCall} 单一接缝），除零→NULL、精确→十进制商。
     * {@code DIV}（{@code DIVIDE_INTEGER}）非本算子，仍整除，不得被波及。
     */
    @Test
    public void divisionLocalCodegens() {
        // 常量折叠形态（无表）与可逐行形态都不得崩。
        Assert.assertNotNull(planAndCodegen("select 10/3"));
        Assert.assertNotNull(planAndCodegen("select 1/0"));
        // 出码期 "/" 重映射到 MysqlDivideFunction：生成码源串含 mysqlDivide（plan 树不反映 remap，故断码不断树）。
        String slashCode = codegenSource("select 10/3");
        Assert.assertTrue("`10/3` 应经 MysqlDivideFunction.mysqlDivide 落码，实际码：" + slashCode,
                slashCode.contains("mysqlDivide"));
        // DIV（DIVIDE_INTEGER）非本算子，仍整除，不得被 remap 波及。
        String divCode = codegenSource("select 10 DIV 3");
        Assert.assertNotNull(divCode);
        Assert.assertFalse("`10 DIV 3` 不得走 mysqlDivide（应仍 DIVIDE_INTEGER），实际码：" + divCode,
                divCode.contains("mysqlDivide"));
    }
}
