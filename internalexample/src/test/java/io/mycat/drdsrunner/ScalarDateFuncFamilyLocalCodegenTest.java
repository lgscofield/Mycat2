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
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标量函数同族批量接入·离线落码锁（I-DEBT-32 后续专轮）。
 *
 * <p>与 {@link ScalarStdFuncLocalCodegenTest} 同 harness（mode=local，i_s 走 Calcite parser）。
 * 逐个探 Mycat 注册的日期族标量函数投影到 i_s VARCHAR 列（{@code information_schema.columns.DATA_TYPE}）
 * 能否本地落码。旧行为：函数落码形参为 {@code LocalDate/LocalDateTime} → operand family=DATE，
 * 经 I-DEBT-32 方案 E（Mycat 独占领名）后标准孪生不再追加，Mycat 算子接不住 VARCHAR →
 * {@code SqlUnresolvedFunction} / {@code Unable to implement MycatProject}（同 DAYOFWEEK 修复前）。
 *
 * <p>本锁用软收集：一次跑列出全 RED 函数，据此定批量接入清单；逐个把落码形参对齐 String 后转 GREEN。
 */
public class ScalarDateFuncFamilyLocalCodegenTest implements MycatTest {

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

    /** 单参日期族：投影到 i_s VARCHAR 列，逐个须能本地落码（已接入集）。 */
    @Test
    public void singleArgDateFamilyCodegensOverVarchar() {
        Map<String, String> probes = new LinkedHashMap<>();
        for (String fn : new String[]{"YEAR", "MONTH", "DAYOFMONTH", "DAYOFYEAR", "DAYOFWEEK",
                "HOUR", "MINUTE", "SECOND", "MICROSECOND", "QUARTER", "WEEKDAY",
                "MONTHNAME", "DAYNAME", "LAST_DAY", "TO_DAYS"}) {
            probes.put(fn, "select " + fn + "(DATA_TYPE) from information_schema.columns");
        }
        List<String> red = new ArrayList<>();
        for (Map.Entry<String, String> e : probes.entrySet()) {
            try {
                planAndCodegen(e.getValue());
            } catch (Throwable t) {
                red.add(e.getKey() + " => " + t.getClass().getSimpleName() + ": " + rootMsg(t));
            }
        }
        System.out.println("[FAMILY-PROBE] single-arg RED=" + red.size() + "/" + probes.size());
        red.forEach(s -> System.out.println("  RED " + s));
        Assert.assertTrue("以下日期族标量投影到 VARCHAR 未能本地落码：\n" + String.join("\n", red), red.isEmpty());
    }

    /** 双参日期族（tranche 2 挂账：DATEDIFF 双 String 形参尚未接入）。 */
    @Ignore("tranche 2: DATEDIFF/WEEK 双参形态待接入, 见 DEBT-QUEUE 标量函数同族批量接入专轮")
    @Test
    public void twoArgDateFamilyCodegensOverVarchar() {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("DATEDIFF", "select DATEDIFF(DATA_TYPE, DATA_TYPE) from information_schema.columns");
        List<String> red = new ArrayList<>();
        for (Map.Entry<String, String> e : probes.entrySet()) {
            try {
                planAndCodegen(e.getValue());
            } catch (Throwable t) {
                red.add(e.getKey() + " => " + t.getClass().getSimpleName() + ": " + rootMsg(t));
            }
        }
        System.out.println("[FAMILY-PROBE] two-arg RED=" + red.size() + "/" + probes.size());
        red.forEach(s -> System.out.println("  RED " + s));
        Assert.assertTrue("以下双参日期族标量投影到 VARCHAR 未能本地落码：\n" + String.join("\n", red), red.isEmpty());
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? "" : m.replaceAll("[\r\n]+", " ");
    }
}
