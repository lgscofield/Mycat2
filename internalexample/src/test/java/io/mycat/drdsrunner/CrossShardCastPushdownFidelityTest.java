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
 * 跨片计算投影（CAST）下推保真的离线落码锁（I-DEBT-76·引擎/codegen 跨片下推保真专轮）。
 *
 * <p>根因（读侧取证钉死，见 spec §6.2/§6.3）：Mycat 自有 deparse 出口
 * {@code hbt/.../io/mycat/calcite/SqlImplementor.Context.toSql} 的 CAST 分支相对上游 Calcite
 * （{@code calcite-core/.../rel/rel2sql/SqlImplementor.java:907-935} 除 ignoreCast/UNKNOWN 外一律保留 CAST）
 * 多出三个「操作数是列引用/动态参数/局部引用就直接返回操作数、丢弃 CAST」的早退。因同族冗余 cast 已在块首被
 * 丢弃，这三个早退<b>只在跨族时触发</b>——即专门丢弃 {@code cast(varchar AS signed)} 这类承重 CAST，把它降级成裸列引用。
 * 单分片走原 SQL 文本直通、交后端 MySQL 求值（绿）；跨片 UNION fan-out 走 rel→sql 重渲染，CAST 静默丢失（红）。
 *
 * <p>正解（Narrow-A）：删除这三个跨族早退、回归上游，让跨族列引用 CAST 落到 {@code dialect.getCastSpec} 渲进
 * fragment（后端逐片求值 → 值/类型/诊断保真回归真库，并顺带解锁 I-DEBT-69 多目标 SELECT 告警拼接活体格）。
 *
 * <p>本锁直指标针：断言跨片 {@code cast(note as signed)} 投影的下推 fragment <b>保留 CAST</b>
 * （含 {@code CAST(}）。修复前必红（当前只剩 {@code shg.note AS n}），删分歧后转绿。
 * mode=local，纯配置、不连库，无需真 MySQL（同 {@link PushDownDivUnparseFidelityTest} 姿势）。
 */
public class CrossShardCastPushdownFidelityTest {

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

    /**
     * 跨片全扫描 {@code cast(note as signed)} 投影：下推 fragment 必须保留 CAST（交后端逐片求值），
     * 不得把承重 CAST 降级成裸列引用 {@code shg.note AS n}。修复前必红。
     */
    @Test
    public void crossShardCastProjectionMustPushdownCast() {
        String pushdown = pushdownSql("select id, cast(note as signed) as n from shg order by id");
        String upper = pushdown.toUpperCase();
        Assert.assertTrue("跨片 cast(note as signed) 下推 fragment 必须保留 CAST（后端逐片求值→值/类型保真）；实际: " + pushdown,
                upper.contains("CAST("));
        // 断言目标型渲染为 MySQL/MariaDB CAST 合法整型 SIGNED（非裸 INTEGER/BIGINT，否则后端→1064）。
        Assert.assertTrue("跨片 cast 下推 fragment 整数目标型须渲染为合法的 SIGNED；实际: " + pushdown,
                upper.contains("SIGNED"));
    }
}
