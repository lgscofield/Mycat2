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
 * I-DEBT-49 回归锁：带 GLOBAL INDEX(GSI) 的分片表，等值命中索引键列时
 * 规划器必须**无需 hint** 自动把查询路由到索引表视图。
 *
 * <p>根因（探针实证 2026-10-16，订正看板原记"CAST 匹配缝漏配"签名）：
 * MycatViewIndexViewRule 在 I-DEBT-10 阶段一从 Volcano 迁入 HepPlanner 后，
 * 其 call.transformTo(mycatRel, equivMap) 的双参形态在 Hep 下被 HepRuleCall
 * 忽略 equivMap、仅以第一个参数做替换——传的是视图自身，等价于自我替换 no-op，
 * 产出的索引候选视图被整体丢弃；hint 路径（PrimaryShardingTableFilterViewRule
 * 直接 transformTo(候选)）不受影响，故症状为"GSI 只有显式 hint 才生效"。
 */
public class GsiAutoIndexPlanTest implements MycatTest {

    private static final String INDEX_TABLE = "travelrecord_g_i_user_id";

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
                "  `traveldate` date DEFAULT NULL,\n" +
                "  `fee` decimal(10, 0) DEFAULT NULL,\n" +
                "  `days` int DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`),\n" +
                "  KEY `id` (`id`)\n" +
                ") ENGINE = InnoDB CHARSET = utf8\n" +
                "DBPARTITION BY mod_hash(id) DBPARTITIONS 2\n" +
                "TBPARTITION BY mod_hash(id) TBPARTITIONS 2");
        main.setFunction(ShardingFunction.builder().properties(JsonUtil.from("{\n" +
                "\"dbNum\":\"2\",\n" +
                "\"mappingFormat\":\"c${targetIndex}/db1_${dbIndex}/travelrecord_${tableIndex}\",\n" +
                "\"tableNum\":\"2\",\n" +
                "\"tableMethod\":\"mod_hash(id)\",\n" +
                "\"storeNum\":2,\n" +
                "\"dbMethod\":\"mod_hash(id)\"\n" +
                "}", Map.class)).build());

        ShardingTableConfig gsi = new ShardingTableConfig();
        gsi.setCreateTableSQL("CREATE TABLE db1.`travelrecord_g_i_user_id` (\n" +
                "  `user_id` varchar(100) DEFAULT NULL,\n" +
                "  `fee` decimal(10, 0) DEFAULT NULL,\n" +
                "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                "  PRIMARY KEY (`id`)\n" +
                ") ENGINE = InnoDB CHARSET = utf8\n" +
                "DBPARTITION BY mod_hash(`user_id`) TBPARTITION BY mod_hash(`user_id`) TBPARTITIONS 2");
        gsi.setFunction(ShardingFunction.builder().properties(JsonUtil.from("{\n" +
                "\"dbNum\":\"2\",\n" +
                "\"mappingFormat\":\"c${targetIndex}/db1_${dbIndex}/travelrecord_g_i_user_id_${tableIndex}\",\n" +
                "\"tableNum\":\"2\",\n" +
                "\"tableMethod\":\"mod_hash(user_id)\",\n" +
                "\"storeNum\":2,\n" +
                "\"dbMethod\":\"mod_hash(user_id)\"\n" +
                "}", Map.class)).build());
        gsi.setShardingIndexTables(Collections.emptyMap());
        main.getShardingIndexTables().put(INDEX_TABLE, gsi);

        logicSchemaConfig.getShardingTables().put("travelrecord", main);

        mycatRouterConfig.getClusters().add(CreateClusterHint.createConfig("c0", Arrays.asList("ds0"), Collections.emptyList()));
        mycatRouterConfig.getClusters().add(CreateClusterHint.createConfig("c1", Arrays.asList("ds1"), Collections.emptyList()));
        mycatRouterConfig.getDatasources().add(CreateDataSourceHint.createConfig("ds0", DB1));
        mycatRouterConfig.getDatasources().add(CreateDataSourceHint.createConfig("ds1", DB2));
        mycatRouterConfig.getDatasources().add(CreateDataSourceHint.createConfig("prototype", DB1));
        ConfigUpdater.load(mycatRouterConfig);
        drds = MetaClusterCurrent.wrapper(DrdsSqlCompiler.class);
    }

    private static String plan(String sql) {
        init();
        DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sql, "db1");
        OptimizationContext optimizationContext = new OptimizationContext();
        MycatRel dispatch = drds.dispatch(optimizationContext, drdsSqlWithParams);
        Plan plan = new PlanImpl(dispatch, DrdsExecutorCompiler.getCodeExecuterContext(
                optimizationContext.relNodeContext.getConstantMap(), dispatch, false), drdsSqlWithParams.getAliasList());
        return plan.dumpPlan().replaceAll("\r", " ").replaceAll("\n", " ").trim();
    }

    /** 核心：无 hint 的 GSI 键等值查询必须自动路由到索引表。 */
    @Test
    public void autoPointQuerySelectsGsiWithoutHint() {
        String dumped = plan("select id, fee from travelrecord where user_id='a' order by id");
        Assert.assertTrue("等值命中 GSI 键列应自动选用索引表 " + INDEX_TABLE + "，实际计划：" + dumped,
                dumped.contains(INDEX_TABLE));
    }

    /** 守卫：谓词不含任何索引键列时不得误走索引表。 */
    @Test
    public void nonKeyColumnPredicateDoesNotSelectGsi() {
        String dumped = plan("select id from travelrecord where fee = 1 order by id");
        Assert.assertFalse("fee 不是 GSI 键列，不应路由到索引表，实际计划：" + dumped,
                dumped.contains(INDEX_TABLE));
    }

    /** 守卫：显式 force index 路径（原唯一可用路径）保持可用。 */
    @Test
    public void forceIndexPathStillSelectsGsi() {
        String dumped = plan("select id, fee from travelrecord force index(g_i_user_id) where user_id='a'");
        Assert.assertTrue("force index 路径应继续选中索引表，实际计划：" + dumped,
                dumped.contains(INDEX_TABLE));
    }
}
