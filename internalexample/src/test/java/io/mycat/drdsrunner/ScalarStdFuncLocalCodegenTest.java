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
        init();
        DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sql, "db1");
        OptimizationContext optimizationContext = new OptimizationContext();
        MycatRel dispatch = drds.dispatch(optimizationContext, drdsSqlWithParams);
        Plan plan = new PlanImpl(dispatch, DrdsExecutorCompiler.getCodeExecuterContext(
                optimizationContext.relNodeContext.getConstantMap(), dispatch, false), drdsSqlWithParams.getAliasList());
        return plan.dumpPlan().replaceAll("\r", " ").replaceAll("\n", " ").trim();
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
}
