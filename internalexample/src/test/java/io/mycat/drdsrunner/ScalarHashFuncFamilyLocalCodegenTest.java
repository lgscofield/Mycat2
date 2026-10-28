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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加密/哈希族标量函数·离线落码锁（I-DEBT-32-FAMILY 后续专轮）。
 *
 * <p>与 {@link ScalarDateFuncFamilyLocalCodegenTest} 同 harness（mode=local，i_s 走 Calcite parser）。
 * 取证：Mycat 算子表对 MD5/SHA1 的 {@code lookupOperatorOverloads} 返回 0 个算子（{@code UPPER} 等已注册者为 1），
 * 即 Mycat 侧完全无实现，折算期留下 {@code SqlUnresolvedFunction}，出码期在
 * {@code RexToLixTranslator.visitCall} 抛 {@code cannot translate call MD5($t7)}，
 * 被 {@code MycatEnumerableRelImplementor#implementRoot} 包成 {@code Unable to implement MycatProject}。
 *
 * <p>客户端可见偏离：对本地表/information_schema 的 VARCHAR 列投影 MD5/SHA1 直接崩，
 * 而原生 MySQL/MariaDB 返回十六进制摘要。本锁把"能本地落码"钉成回归防线。
 */
public class ScalarHashFuncFamilyLocalCodegenTest implements MycatTest {

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

    private static String planAndCodegen(String sql) {
        init();
        DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sql, "db1");
        OptimizationContext optimizationContext = new OptimizationContext();
        MycatRel dispatch = drds.dispatch(optimizationContext, drdsSqlWithParams);
        Plan plan = new PlanImpl(dispatch, DrdsExecutorCompiler.getCodeExecuterContext(
                optimizationContext.relNodeContext.getConstantMap(), dispatch, false), drdsSqlWithParams.getAliasList());
        return plan.dumpPlan().replaceAll("\r", " ").replaceAll("\n", " ").trim();
    }

    /** 哈希族：投影到 i_s VARCHAR 列，须能本地落码（MD5/SHA1 及同义词 SHA）。 */
    @Test
    public void hashFamilyCodegensOverVarchar() {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("MD5", "select MD5(DATA_TYPE) from information_schema.columns");
        probes.put("SHA1", "select SHA1(DATA_TYPE) from information_schema.columns");
        probes.put("SHA", "select SHA(DATA_TYPE) from information_schema.columns");
        probes.put("MD5(嵌套)", "select MD5(MD5(DATA_TYPE)) from information_schema.columns");
        List<String> red = new ArrayList<>();
        for (Map.Entry<String, String> e : probes.entrySet()) {
            try {
                planAndCodegen(e.getValue());
            } catch (Throwable t) {
                red.add(e.getKey() + " => " + t.getClass().getSimpleName() + ": " + rootMsg(t));
            }
        }
        System.out.println("[HASH-FAMILY-PROBE] RED=" + red.size() + "/" + probes.size());
        red.forEach(s -> System.out.println("  RED " + s));
        Assert.assertTrue("以下哈希族标量投影到 VARCHAR 未能本地落码：\n" + String.join("\n", red), red.isEmpty());
    }

    private static String rootMsg(Throwable t) {
        // MycatEnumerableRelImplementor#implementRoot 用 addSuppressed 携带真因(不是 getCause)。
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        sb.append(c.getMessage() == null ? "" : c.getMessage().replaceAll("[\r\n]+", " "));
        for (Throwable s : t.getSuppressed()) {
            Throwable sc = s;
            while (sc.getCause() != null && sc.getCause() != sc) {
                sc = sc.getCause();
            }
            sb.append(" | SUPPRESSED_ROOT(").append(sc.getClass().getName()).append(") ")
                    .append(String.valueOf(sc.getMessage()).replaceAll("[\r\n]+", " "));
        }
        return sb.toString();
    }
}
