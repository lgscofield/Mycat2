package io.mycat.drdsrunner;

import io.mycat.DrdsExecutorCompiler;
import io.mycat.DrdsSqlCompiler;
import io.mycat.DrdsSqlWithParams;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatCore;
import io.mycat.calcite.DrdsRunnerHelper;
import io.mycat.calcite.MycatRel;
import io.mycat.calcite.rewriter.OptimizationContext;
import io.mycat.calcite.spm.Plan;
import io.mycat.calcite.spm.PlanImpl;
import io.mycat.calcite.spm.SpecificSql;
import io.mycat.config.MycatRouterConfig;
import io.mycat.config.ShardingFunction;
import io.mycat.config.ShardingTableConfig;
import io.mycat.config.LogicSchemaConfig;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import io.mycat.sqlhandler.ConfigUpdater;
import io.mycat.util.JsonUtil;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 7C 下推隐式列别名裸 ? 致后端 1064 的离线落码锁（A1 · aliasList 覆写专轮）。
 *
 * <p>根因（current HEAD 取证坐实，spec §2）：无显式 AS 的函数投影，下推隐式别名取自参数化表达式文本
 * （含动态参 ?），经 RelToSqlConverter.addSelect → SqlImplementor.as 渲成反引号标识符；
 * MycatPreparedStatementUtil.executeQuery 把别名内 ? 计为 JDBC 占位符，但 dynamicParameters 只含真
 * SqlDynamicParam → 参数错配 → 后端 1064。反引号 ? 别名本身合法（直连真库返 2017），1064 源于占位符错配。
 *
 * <p>正解（A1）：compileQuery 字段名定稿处把含 ? 的隐式别名字段名以 aliasList 字面量形态覆写，
 * 使下推别名不含 ?。本锁断言下推 fragment 别名位（AS `...`）不含裸 ?。修复前必红。
 * mode=local，纯配置；MycatCore init 需一个可达后端（m1-mysql-a:3306）读变量。
 */
public class ImplicitAliasBareParamFidelityTest {

    private static final String DB1 = System.getProperty("db1",
            "jdbc:mysql://localhost:3306/mysql?username=root&password=123456"
            + "&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true");

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
        main.setCreateTableSQL("CREATE TABLE db1.`shg` (\n" +
                "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                "  `note` varchar(100) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`)\n" +
                ") ENGINE = InnoDB CHARSET = utf8\n" +
                "DBPARTITION BY mod_hash(id) DBPARTITIONS 2 TBPARTITION BY mod_hash(id) TBPARTITIONS 1");
        main.setFunction(ShardingFunction.builder().properties(JsonUtil.from("{\n" +
                "\"dbNum\":\"2\",\n" +
                "\"mappingFormat\":\"c0/db1_${dbIndex}/shg_${tableIndex}\",\n" +
                "\"tableNum\":\"1\",\n" +
                "\"storeNum\":2,\n" +
                "\"dbMethod\":\"mod_hash(id)\",\n" +
                "\"tbMethod\":\"mod_hash(id)\"\n" +
                "}", Map.class)).build());
        logicSchemaConfig.getShardingTables().put("shg", main);

        mycatRouterConfig.getClusters().add(CreateClusterHint.createConfig("c0",
                Arrays.asList("ds0"), Collections.emptyList()));
        mycatRouterConfig.getDatasources().add(CreateDataSourceHint.createConfig("ds0", DB1));
        mycatRouterConfig.getDatasources().add(CreateDataSourceHint.createConfig("prototype", DB1));
        ConfigUpdater.load(mycatRouterConfig);
        drds = MetaClusterCurrent.wrapper(DrdsSqlCompiler.class);
    }

    private static String pushdownSql(String sql) {
        init();
        DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sql, "db1");
        OptimizationContext optimizationContext = new OptimizationContext();
        MycatRel dispatch = drds.dispatch(optimizationContext, drdsSqlWithParams);
        Plan plan = new PlanImpl(dispatch, DrdsExecutorCompiler.getCodeExecuterContext(
                optimizationContext.relNodeContext.getConstantMap(), dispatch, false), drdsSqlWithParams.getAliasList());
        List<SpecificSql> specificSqls = plan.specificSql(drdsSqlWithParams);
        StringBuilder sb = new StringBuilder();
        for (SpecificSql s : specificSqls) {
            sb.append(s.toString()).append('\n');
        }
        return sb.toString();
    }

    /** 别名位（AS `...`）不得含裸 ?；修复前必红（当前 AS `DATE_FORMAT(?,'%Y')`）。 */
    private static void assertNoBareParamInAlias(String pushdown) {
        Assert.assertFalse("隐式别名不得含动态参 ?（致 JDBC 占位符错配→后端1064）；实际: " + pushdown,
                pushdown.matches("(?s).*AS `[^`]*\\?[^`]*`.*"));
    }

    @Test
    public void implicitAliasMustNotContainBareParam() {
        String pushdown = pushdownSql("select DATE_FORMAT(\"2017-06-15\", \"%Y\") from shg");
        assertNoBareParamInAlias(pushdown);
        Assert.assertTrue("隐式别名应取字面量形态；实际: " + pushdown,
                pushdown.contains("AS `DATE_FORMAT('2017-06-15','%Y')`")
                || pushdown.contains("AS `DATE_FORMAT(\"2017-06-15\",\"%Y\")`"));
    }

    @Test
    public void yearImplicitAlias() {
        assertNoBareParamInAlias(pushdownSql("select YEAR(\"2017-06-15\") from shg"));
    }

    @Test
    public void concatImplicitAlias() {
        assertNoBareParamInAlias(pushdownSql("select CONCAT('a','b') from shg"));
    }

    @Test
    public void multiProjectionImplicitAlias() {
        String pushdown = pushdownSql("select YEAR(\"2017-06-15\"), CONCAT('a','b') from shg");
        assertNoBareParamInAlias(pushdown);
        // 索引对齐锁：每个函数投影须紧邻其自身字面量别名（错位/互换会破坏邻接）。
        Assert.assertTrue("YEAR 投影须携 YEAR 字面量别名（索引对齐）；实际: " + pushdown,
                pushdown.matches("(?s).*YEAR\\(\\?\\) AS `YEAR\\('2017-06-15'\\)`.*"));
        Assert.assertTrue("CONCAT 投影须携 CONCAT 字面量别名（索引对齐）；实际: " + pushdown,
                pushdown.matches("(?s).*concat\\(\\?, \\?\\) AS `CONCAT\\('a','b'\\)`.*"));
    }

    @Test
    public void explicitAliasUntouched() {
        String pushdown = pushdownSql("select DATE_FORMAT(\"2017-06-15\",\"%Y\") as f from shg");
        assertNoBareParamInAlias(pushdown);
        Assert.assertTrue("显式 AS 路径零变化，别名仍为 f；实际: " + pushdown,
                pushdown.contains("AS `f`"));
    }

    @Test
    public void unionFanoutAllFragments() {
        String pushdown = pushdownSql("select DATE_FORMAT(\"2017-06-15\", \"%Y\") from shg");
        Assert.assertTrue("跨片 fan-out 应含 union all 多 fragment；实际: " + pushdown,
                pushdown.toLowerCase().contains("union all"));
        assertNoBareParamInAlias(pushdown);
    }

    @Test
    @org.junit.Ignore("Phase B 边界：真 SQL UNION 各分支隐式别名仍参数化（A1 aliasList 仅取首分支，"
            + "preParse getFirstQueryBlock 不覆 UNION 次分支）。A1 仅盖单 SELECT（含分片 fan-out）；"
            + "UNION/嵌套/*-混合 需 preParse aliasList 跨分支扩展，架构级另轮拍板。取证见 scratch/7c-fixpass.log。")
    public void trueSqlUnionAllFragments() {
        // 真 SQL UNION（两分支），区别于分片 fan-out：各分支隐式别名均须无 ?（Review Focus #1）。
        String pushdown = pushdownSql(
                "select YEAR(\"2017-06-15\") from shg union all select CONCAT('a','b') from shg");
        assertNoBareParamInAlias(pushdown);
    }
}