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
import io.mycat.config.DatasourceConfig;
import io.mycat.config.LogicSchemaConfig;
import io.mycat.config.MycatRouterConfig;
import io.mycat.config.ShardingFunction;
import io.mycat.config.ShardingTableConfig;
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
 * 跨片下推把整数除法 DIV 渲染成非法 MySQL 语法的离线落码锁（I-DEBT-75·架构级方言债专轮）。
 *
 * <p>根因（活体 8066 已坐实，见 scratch/proxy-t75-out.txt）：druid 的 {@code DIV} 经
 * {@code MycatCalciteMySqlNodeVisitor} 映射到 {@link org.apache.calcite.sql.fun.SqlStdOperatorTable#DIVIDE_INTEGER}
 * （算子名 {@code "/INT"}）。单分片时原 SQL 文本原样下推、交后端 MySQL 原生执行（绿）；跨片 UNION fan-out 走
 * Calcite rel→sql 重渲染，{@code SqlBinaryOperator} 按算子名逐字 emit 成 {@code a /INT b}，MySQL 把 {@code /} 后的
 * {@code INT} 当函数/关键字解析 → ERROR 1064（near {@code INT (`shg`.`id` - 1)) AS `q`}）。
 *
 * <p>正解：下推出口把 DIVIDE_INTEGER 渲染成 MySQL 原生中缀 {@code DIV}（向零截断、除零返 NULL，与用户原写语义全等），
 * 绝非 {@code FLOOR(a/b)}（负数 {@code FLOOR(-3/2)=-2} ≠ MySQL {@code -3 DIV 2=-1}，语义不等价）。
 *
 * <p>本锁直指标针：断言下推 SQL 文本<b>不含</b> {@code /INT}、且 DIV 投影以中缀 {@code DIV} 呈现。
 * 修复前必红（活体已证含 {@code /INT}），{@code MycatSqlDialect.unparseCall} 落地 DIV 分支后转绿。
 * mode=local，纯配置、不连库，无需真 MySQL（同 {@link PushDownOptionalDefaultGuardrailTest} 姿势）。
 */
public class PushDownDivUnparseFidelityTest {

    /** 等价于 io.mycat.drdsrunner.MycatTest.DB1，本地内联以避免依赖同包基类。 */
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

    /** 构建计划并汇出下推（rel→sql deparse）后的具体后端 SQL 文本。 */
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

    /** 跨片 UNION ALL 两分支各含 DIV 投影：下推 SQL 不得出现非法 {@code /INT}，须以中缀 DIV 呈现。 */
    @Test
    public void crossShardUnionDivMustNotEmitSlashInt() {
        String pushdown = pushdownSql(
                "select id, 1 div (id-1) as q from shg where id=1 "
                + "union all select id, 1 div (id-2) as q from shg where id=2");
        Assert.assertFalse("跨片 DIV 下推 SQL 不得把 DIVIDE_INTEGER 渲染成非法的 /INT（MySQL→1064）；实际: " + pushdown,
                pushdown.contains("/INT"));
    }

    /** 单表 DIV 投影（强制走 rel→sql 重渲染）：同样不得出现 /INT，须以中缀 DIV 呈现。 */
    @Test
    public void singleShardDivProjectionMustRenderNativeDiv() {
        String pushdown = pushdownSql("select id, 1 div (id-1) as q from shg where id=1");
        Assert.assertFalse("DIV 投影下推 SQL 不得出现 /INT；实际: " + pushdown, pushdown.contains("/INT"));
        Assert.assertTrue("DIV 投影下推 SQL 应以 MySQL 原生中缀 DIV 呈现；实际: " + pushdown,
                pushdown.toUpperCase().contains(" DIV "));
    }
}
