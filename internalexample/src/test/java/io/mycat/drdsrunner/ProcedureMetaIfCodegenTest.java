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
 * I-DEBT-50 回归锁：Connector/J {@code DatabaseMetaData.getProcedureColumns} 下发的
 * {@code information_schema.parameters} 元数据投影大量使用 MySQL 三目函数
 * {@code IF(cond, then, else)}。旧实现未把 IF 映射到 Calcite 算子，落
 * {@code MycatCalciteMySqlNodeVisitor} 的 default 分支被建成
 * {@code SqlUnresolvedFunction("IF")}，codegen 期无实现者 →
 * {@code RuntimeException: cannot translate call IF(...)}（包装成
 * {@code IllegalStateException: Unable to implement MycatProject(...)}），
 * 令 {@code prepareCall} 直接崩。正解=IF 在 AST 层等价改写为 searched CASE
 * （{@code CASE WHEN cond THEN then ELSE else END}），复用已可落码的 CASE 路径。
 */
public class ProcedureMetaIfCodegenTest implements MycatTest {

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

    /** 规划 + 落码（触发 codegen），返回关系树文本；落码失败会抛异常。 */
    private static String planAndCodegen(String sql) {
        init();
        DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sql, "db1");
        OptimizationContext optimizationContext = new OptimizationContext();
        MycatRel dispatch = drds.dispatch(optimizationContext, drdsSqlWithParams);
        Plan plan = new PlanImpl(dispatch, DrdsExecutorCompiler.getCodeExecuterContext(
                optimizationContext.relNodeContext.getConstantMap(), dispatch, false), drdsSqlWithParams.getAliasList());
        return plan.dumpPlan().replaceAll("\r", " ").replaceAll("\n", " ").trim();
    }

    /** 核心：元数据投影里的 IF 必须能被落码（旧行为=codegen 崩）。 */
    @Test
    public void ifProjectionInMetadataQueryCodegens() {
        String dumped = planAndCodegen(
                "select if(upper(DATA_TYPE)='int', 'x', 'y') from information_schema.parameters");
        // IF 被等价改写为 searched CASE，树中不应再出现悬空 IF( 调用
        Assert.assertFalse("IF 应被改写为 CASE，不应残留未映射的 IF 调用，实际计划：" + dumped,
                dumped.contains("IF("));
    }

    /** 复刻真实形态：IF 嵌套进多分支 CASE + 排序过滤的整段投影不得崩。 */
    @Test
    public void nestedIfInFullProjectionCodegens() {
        String dumped = planAndCodegen("select SPECIFIC_SCHEMA, SPECIFIC_NAME, "
                + "case when upper(DATA_TYPE)='DATE' then if(locate('unsigned', upper(DTD_IDENTIFIER))=1, 'a', 'b') "
                + "else concat(DATA_TYPE,'z') end "
                + "from information_schema.parameters "
                + "where ROUTINE_TYPE='PROCEDURE' and SPECIFIC_NAME like 'delete%' "
                + "order by SPECIFIC_SCHEMA, SPECIFIC_NAME, ROUTINE_TYPE, ORDINAL_POSITION");
        Assert.assertFalse("整段元数据投影不得残留未映射 IF，实际计划：" + dumped,
                dumped.contains("IF("));
    }

    /** 守卫：IF 非三参属非法调用，应显式拒绝而非静默错改。 */
    @Test
    public void ifWithWrongArityIsRejected() {
        try {
            planAndCodegen("select if(upper(DATA_TYPE)='int', 'x') from information_schema.parameters");
            Assert.fail("IF 非三参应抛异常");
        } catch (UnsupportedOperationException expected) {
            Assert.assertTrue("异常应说明 IF 需要 3 参，实际：" + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("IF"));
        }
    }
}

