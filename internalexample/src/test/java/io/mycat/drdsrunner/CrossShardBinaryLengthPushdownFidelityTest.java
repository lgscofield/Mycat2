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
 * 跨片 {@code length(binary)} 下推的字节保真离线落码锁（I-DEBT-77·引擎/codegen 跨片下推保真专轮，
 * 系 I-DEBT-76 的反向回归）。
 *
 * <p>根因（取证钉死，见 spec）：I-DEBT-76 删除 {@code hbt/.../io/mycat/calcite/SqlImplementor.Context.toSql}
 * CAST 分支的 (b)(c)(d) 三个跨族早退、改为保留跨族列引用 CAST 下推后，凡「操作数被隐式 coercion 包成 CAST」的
 * 表达式也会被一并保留下推。{@code LengthFunction.length(String)} 声明 CHARACTER 族实参，故校验期
 * {@code MycatSqlDefinedFunction.mycatCoerceOperands} 为 {@code length(b)} 的 BINARY 列注入
 * {@code CAST(b AS VARCHAR)}（utf8 charset）。该隐式 CAST 与 I-DEBT-76 要保的 {@code cast(varchar AS signed)}
 * 在 Rex 层同形、不可区分 → 一并保留下推 → 跨片 fragment 出码 {@code length(CAST(b AS CHAR))}。
 * MySQL {@code LENGTH()} 对含 0x00 的 utf8 串返 NULL，背离直连真库裸 {@code length(b)} 的字节数（binary(4)→4）。
 *
 * <p>正解（Narrow 定向剥离）：在 deparse CAST 分支只针对「源族=BINARY、目标族=CHARACTER」这一有损字节→字符串
 * 方向剥离 coercion、下推裸 binary（交后端按字节算 LENGTH），与 I-DEBT-76 要保的「源=CHARACTER、目标=INTEGER」
 * 方向天然不相交，不回退其成果。
 *
 * <p>本锁直指标针：断言跨片 {@code length(b)} 的下推 fragment <b>不含 CAST 包裹</b>（应裸出 {@code length(`b`)}）。
 * 修复前必红（当前出码 {@code length(CAST(`b` AS CHAR))}）。mode=local，纯配置、不连库，
 * 姿势同 {@link CrossShardCastPushdownFidelityTest}。
 */
public class CrossShardBinaryLengthPushdownFidelityTest {

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
        main.setCreateTableSQL("CREATE TABLE db1.`shgb` (\n" +
                "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                "  `b` binary(4) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`)\n" +
                ") ENGINE = InnoDB CHARSET = utf8\n" +
                "DBPARTITION BY mod_hash(id) DBPARTITIONS 2 TBPARTITION BY mod_hash(id) TBPARTITIONS 1");
        main.setFunction(ShardingFunction.builder().properties(JsonUtil.from("{\n" +
                "\"dbNum\":\"2\",\n" +
                "\"mappingFormat\":\"c0/db1_${dbIndex}/shgb_${tableIndex}\",\n" +
                "\"tableNum\":\"1\",\n" +
                "\"storeNum\":2,\n" +
                "\"dbMethod\":\"mod_hash(id)\",\n" +
                "\"tbMethod\":\"mod_hash(id)\"\n" +
                "}", Map.class)).build());
        logicSchemaConfig.getShardingTables().put("shgb", main);

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
     * 跨片全扫描 {@code length(b)}（b 为 BINARY(4)）：下推 fragment 必须以裸 binary 出码
     * {@code length(`shgb`.`b`)}，不得把隐式 coercion {@code CAST(b AS CHAR)} 一并下推
     * （MySQL {@code LENGTH(CAST(含0x00的utf8串))}→NULL，背离真库字节数）。修复前必红。
     */
    @Test
    public void crossShardLengthOverBinaryMustNotPushdownCoercionCast() {
        String pushdown = pushdownSql("select id, length(b) as len from shgb order by id");
        String upper = pushdown.toUpperCase();
        System.out.println("[I-DEBT-77] length(binary) 下推 fragment 实串:\n" + pushdown);
        Assert.assertTrue("跨片 length(b) 应出码为裸 length(...)（交后端按字节算 LENGTH）；实际: " + pushdown,
                upper.contains("LENGTH("));
        Assert.assertFalse("跨片 length(binary) 下推 fragment 不得含隐式 coercion 的 CAST 包裹"
                + "（MySQL LENGTH(CAST(含0x00的utf8串))→NULL，背离真库字节数）；实际: " + pushdown,
                upper.contains("CAST("));
    }
}
