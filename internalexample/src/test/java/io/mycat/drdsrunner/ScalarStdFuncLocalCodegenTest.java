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
