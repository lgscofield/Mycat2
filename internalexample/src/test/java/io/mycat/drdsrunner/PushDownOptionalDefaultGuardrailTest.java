package io.mycat.drdsrunner;

import io.mycat.*;
import io.mycat.calcite.DrdsRunnerHelper;
import io.mycat.calcite.MycatRel;
import io.mycat.calcite.rewriter.OptimizationContext;
import io.mycat.calcite.spm.Plan;
import io.mycat.calcite.spm.PlanImpl;
import io.mycat.calcite.spm.SpecificSql;
import io.mycat.config.*;
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
 * 下推可选参数被补 DEFAULT 致 1064 的离线落码锁（I-DEBT-②·架构级专轮）。
 *
 * <p>根因（活体 explain + 直连真库差分已坐实，见 Spec §1bis/§3）：
 * Mycat 自定义函数带 {@code optional=true} 尾参（如 {@code DATE_FORMAT} 的 locale）时，
 * {@code SqlCallBinding.operands()} 会把被省略的可选尾参物化为 {@code DEFAULT}，经 sql→rel 逃入 RexCall，
 * 下推 rel→sql 由 {@code MycatSqlDialect.unparseCall} 逐 operand 原样 emit 成 {@code , DEFAULT)}。
 * MySQL 不接受函数实参位的 {@code DEFAULT} 关键字 → 1064。
 *
 * <p>本锁直指标针：断言下推 SQL 文本<b>不含</b> {@code DEFAULT}。修复前必红（活体已证含 {@code , DEFAULT)}），
 * 护栏（策略 A·unparseCall 截断尾部连续 DEFAULT）落地后转绿。mode=local，纯配置、不连库，无需真 MySQL。
 */
public class PushDownOptionalDefaultGuardrailTest {

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
        main.setCreateTableSQL("CREATE TABLE db1.`travelrecord` (\n" +
                "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                "  `user_id` varchar(100) DEFAULT NULL,\n" +
                "  `traveldate` date DEFAULT NULL,\n" +
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

    /** DATE_FORMAT 省略可选 locale 尾参，下推 SQL 不得物化出 DEFAULT。 */
    @Test
    public void dateFormatOmittedLocaleMustNotEmitDefault() {
        String pushdown = pushdownSql("select DATE_FORMAT('2017-06-15','%Y') from travelrecord");
        Assert.assertFalse("DATE_FORMAT 下推 SQL 不得把省略的可选 locale 尾参物化成 DEFAULT（MySQL 非法实参→1064）；实际: " + pushdown,
                pushdown.toUpperCase().contains("DEFAULT"));
    }

    /** WEEK 省略可选 mode 尾参，下推 SQL 同样不得物化出 DEFAULT（同类 blast 覆盖）。 */
    @Test
    public void weekOmittedModeMustNotEmitDefault() {
        String pushdown = pushdownSql("select WEEK('2017-06-15') from travelrecord");
        Assert.assertFalse("WEEK 下推 SQL 不得把省略的可选 mode 尾参物化成 DEFAULT；实际: " + pushdown,
                pushdown.toUpperCase().contains("DEFAULT"));
    }
}
