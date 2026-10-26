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
 * I-DEBT-59 回归锁：Observable（stream 腿）上的单列投影必须保持容器行形态。
 *
 * <p>根因：RxJava3 {@code Observable} 契约禁止 null item，而
 * {@code RxBuiltInMethodImpl.select} 内部就是 {@code input.map(...)}。{@code MycatCalc.implementStream}
 * 的 physType 走 {@code PhysTypeImpl.of(..., optimize=true)}，fork 的 {@code JavaRowFormat.optimize}
 * 对 {@code getFieldCount()==1} 无条件把 ARRAY 降成 SCALAR，于是投影 lambda 直出裸标量——值为 null 时
 * {@code .map} 返回 null，运行期抛 {@code NullPointerException: The mapper function returned a null
 * value}（活库取证：i_s 虚表上纯投影 {@code select DAYOFWEEK(DATA_TYPE) ...} 即崩，非仅 distinct）。
 *
 * <p>正解（修法 A，spec
 * {@code 2026-10-26-i-debt-59-rx-nullable-scalar-container-row-design.md}）：stream 腿行形态不变式——
 * 单列不得塌 SCALAR，恒为 {@code Object[]}，null 只作行内元素；裸标量在终结处解箱。与 I-DEBT-19
 * （{@code MycatView.implementMergeSort} 强制 {@code optimize=false}）同法，且与既有绿路径「双列 distinct
 * 生成 {@code new Object[]{...}} + {@code Functions.arrayComparer()}」同形。
 *
 * <p>与函数无关：{@code DAYOFWEEK} 与常量 {@code IF(1=2,'a',NULL)} 同形，故本锁只钉形态不钉函数。
 */
public class RxNullableScalarProjectionContainerRowTest implements MycatTest {

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

    private static Plan codegenOf(String sql) {
        init();
        DrdsSqlWithParams drdsSqlWithParams = DrdsRunnerHelper.preParse(sql, "db1");
        OptimizationContext optimizationContext = new OptimizationContext();
        MycatRel dispatch = drds.dispatch(optimizationContext, drdsSqlWithParams);
        return new PlanImpl(dispatch, DrdsExecutorCompiler.getCodeExecuterContext(
                optimizationContext.relNodeContext.getConstantMap(), dispatch, false), drdsSqlWithParams.getAliasList());
    }

    /** 规划 + 落码，返回生成码源串与计划树文本（失败消息里要能同时看到两者）。 */
    private static String codeOf(String sql) {
        Plan plan = codegenOf(sql);
        return plan.getCodeExecuterContext().getCodeContext().getCode();
    }

    private static String planOf(String sql) {
        return codegenOf(sql).dumpPlan().replaceAll("\r", " ").replaceAll("\n", " ").trim();
    }

    /**
     * 不变式本体：Rx 腿上的 select lambda 必须返回容器行，绝不直出可空裸标量。
     *
     * <p>只钉这一条即可断根——{@code RxBuiltInMethodImpl.select} 是全仓唯一向 Observable
     * 新造 item 的发射点，其余 Rx 算子皆透传。
     */
    private static void assertRxSelectCarriesContainerRow(String sql) {
        String code;
        try {
            code = codeOf(sql);
        } catch (Throwable t) {
            Assert.fail(sql + " 落码失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }
        Assert.assertTrue(sql + " 应经 Rx select 出码（否则本锁未覆盖 stream 腿），实际码：" + code,
                code.contains("RxBuiltInMethodImpl.select"));
        Assert.assertTrue(sql + " 的 Rx select lambda 必须返回 Object[]（容器行），不得直出裸标量，实际码：" + code,
                code.contains("public Object[] apply(Object[] row)"));
        Assert.assertTrue(sql + " 的 Rx select lambda 必须以 new Object[] {...} 携带行（null 只作行内元素），实际码：" + code,
                code.contains("new Object[] {"));
    }

    /**
     * 根节点为 Rx 腿（bind 直接返回 Observable）时，声明形态必须等于实发形态：
     * {@code getElementType()} 须为 {@code Object[].class}，否则终结端 {@code MycatBindable.asObjectArray}
     * 会把整个 {@code Object[]} 再包一层（嵌套数组/静默错值）。仅适用于 Rx 根，
     * 不适用以 Enumerable 节点（如聚合）为根的计划——后者根上标量形态是合法的（见 distinct 测）。
     */
    private static void assertRxRootDeclaresArrayRow(String sql) {
        String code = codeOf(sql);
        Assert.assertTrue(sql + " Rx 根的 getElementType 应为 Object[].class（声明形态须等于实发形态），实际码：" + code,
                code.contains("return java.lang.Object[].class;"));
    }

    /** 核心①：可空标量函数的纯投影（无 distinct、无聚合）也不得在 .map 上产出 null。 */
    @Test
    public void nullableScalarFunctionProjectionKeepsContainerRow() {
        String sql = "select DAYOFWEEK(DATA_TYPE) from information_schema.columns";
        assertRxSelectCarriesContainerRow(sql);
        assertRxRootDeclaresArrayRow(sql);
    }

    /** 核心②：与具体函数无关——常量 CASE 产 null 同形。 */
    @Test
    public void nullLiteralProjectionKeepsContainerRow() {
        String sql = "select IF(1=2,'a',NULL) from information_schema.columns";
        assertRxSelectCarriesContainerRow(sql);
        assertRxRootDeclaresArrayRow(sql);
    }

    /**
     * 核心③：单列 distinct（聚合退化路径）下的投影同样须保容器行。
     *
     * <p>本例根节点是 {@code MycatHashAggregate}，它先把 Rx 腿经 {@code toEnumerable(...)} 桥为
     * Enumerable，再在其上 {@code .select(o -> o[0]).distinct()}；Linq4j {@code Enumerable} 容 null
     * 且 HashSet 视 null 彼此相等（与 MySQL distinct 口径一致），故根上 {@code getElementType()}
     * 仍为标量属正当（声明==实发），本锁不钉它，只钉 Rx 段不得吃 null。
     */
    @Test
    public void singleColumnDistinctKeepsContainerRow() {
        String sql = "select distinct DAYOFWEEK(DATA_TYPE) from information_schema.columns";
        assertRxSelectCarriesContainerRow(sql);
        String code = codeOf(sql);
        // m-1 防真空：先保证两个比较对象都在场（否则 indexOf 返 -1 会使大小比较假过）
        int bridgeIdx = code.indexOf("RxBuiltInMethodImpl.toEnumerable(");
        int distinctIdx = code.indexOf(".distinct()");
        Assert.assertTrue("distinct 例须同时出现 toEnumerable 桥接与 .distinct()（否则本锁失去比较对象），实际码：" + code,
                bridgeIdx >= 0 && distinctIdx >= 0);
        Assert.assertTrue("distinct 的标量解箱必须发生在 toEnumerable 桥接之后（Enumerable 态），实际码：" + code,
                bridgeIdx < distinctIdx);
    }

    /**
     * 核心④：union all 是元素透传者，其自身 physType 必须与实际行形态一致——
     * 子投影改容器行后若 union 仍声明 SCALAR，终结端会把整个 Object[] 再包一层（静默错值）。
     */
    @Test
    public void singleColumnUnionAllKeepsContainerRow() {
        String sql = "select DAYOFWEEK(DATA_TYPE) from information_schema.columns "
                + "union all select DAYOFWEEK(DATA_TYPE) from information_schema.parameters";
        String plan = planOf(sql);
        Assert.assertTrue("单列 union all 计划应含 MycatUnion，实际计划：" + plan, plan.contains("MycatUnion"));
        assertRxSelectCarriesContainerRow(sql);
        assertRxRootDeclaresArrayRow(sql);
    }

    /**
     * 控制组①（今日即绿，防过度修复）：多列投影本就走容器行 + 内容等值比较器，形态不得漂移。
     */
    @Test
    public void multiColumnDistinctKeepsArrayComparerPath() {
        String sql = "select distinct DATA_TYPE, ORDINAL_POSITION from information_schema.columns";
        String code = codeOf(sql);
        Assert.assertTrue("多列 distinct 应继续用 Object[] 容器行，实际码：" + code,
                code.contains("public Object[] apply(Object[] row)"));
        Assert.assertTrue("多列 distinct 应继续用 arrayComparer 做内容等值（引用等值会不去重），实际码：" + code,
                code.contains("Functions.arrayComparer()"));
    }

    /**
     * 控制组②（今日即绿，防过度修复）：LIMIT 腿经 MycatMemSort（isSupportStream=false）回落 Enumerable，
     * Enumerable 容 null，本债不触碰该腿——生成码里不应出现 Rx select，也不应被强行改成容器行。
     */
    @Test
    public void limitLegStaysEnumerable() {
        String sql = "select DAYOFWEEK(DATA_TYPE) from information_schema.columns limit 3";
        String code = codeOf(sql);
        Assert.assertFalse("LIMIT 腿应维持 Enumerable 态（不得被拖进 Rx select），实际码：" + code,
                code.contains("RxBuiltInMethodImpl.select"));
    }

    /**
     * 透传者的根声明形态必须等于实发形态（活库铁证：{@code union all ... limit} 曾返回
     * {@code [Ljava.lang.Object;@...} —— 容器行被终结端再包一层）。
     *
     * <p>根 {@code getElementType()} 取自根节点 physType 的声明（
     * {@code StreamMycatEnumerableRelImplementor#implementHybridRoot}）；而
     * {@code StreamMycatEnumerableRelImplementor#visitChild} 会对 stream 子节点走
     * {@code implementStream}，所以**根即便是 Enumerable（limit 回落的 MemSort/TopN）子节点仍可能是
     * Observable 容器行**。透传者（Union/MemSort/TopN/Minus/Intersect——它们不重录行、直接转发子元素）
     * 若用 {@code PhysTypeImpl.of(..., format)} 三参重载（= optimize=true）声明，单列 ARRAY 会被
     * 塌回 SCALAR → 声明与实发不符 → 静默错值。
     */
    private static void assertRootDeclaresContainerRow(String sql, String expectRelInPlan) {
        String plan = planOf(sql);
        if (expectRelInPlan != null) {
            Assert.assertTrue(sql + " 计划应含 " + expectRelInPlan + "，实际计划：" + plan, plan.contains(expectRelInPlan));
        }
        String code = codeOf(sql);
        Assert.assertTrue(sql + " 子节点应仍为 stream 腿（否则本锁未覆盖混态），实际码：" + code,
                code.contains("RxBuiltInMethodImpl.select"));
        Assert.assertTrue(sql + " 根声明形态必须为 Object[]（须等于透传的实发容器行），实际码：" + code,
                code.contains("return java.lang.Object[].class;"));
    }

    /** 透传铁证 A：{@code union all} 上挂 limit（根回落 Enumerable MemSort）不得嵌套升箱。 */
    @Test
    public void unionAllUnderLimitDeclaresContainerRow() {
        assertRootDeclaresContainerRow("select DAYOFWEEK(DATA_TYPE) from information_schema.columns "
                + "union all select DAYOFWEEK(DATA_TYPE) from information_schema.parameters limit 3", "MycatUnion");
    }

    /** 透传铁证 B：同上再加 order by（走 TopN/MemSort 形态）。 */
    @Test
    public void unionAllUnderOrderByLimitDeclaresContainerRow() {
        assertRootDeclaresContainerRow("select DAYOFWEEK(DATA_TYPE) from information_schema.columns "
                + "union all select DAYOFWEEK(DATA_TYPE) from information_schema.parameters order by 1 limit 3",
                "MycatUnion");
    }

    /** 透传铁证 C：{@code except}（MycatMinus 不重录行，转发子元素）。 */
    @Test
    public void exceptUnderRootDeclaresContainerRow() {
        assertRootDeclaresContainerRow("select DAYOFWEEK(DATA_TYPE) from information_schema.columns "
                + "except select DAYOFWEEK(DATA_TYPE) from information_schema.parameters", "MycatMinus");
    }

    /** 透传铁证 D：{@code intersect}（MycatIntersect 同理）。 */
    @Test
    public void intersectUnderRootDeclaresContainerRow() {
        assertRootDeclaresContainerRow("select DAYOFWEEK(DATA_TYPE) from information_schema.columns "
                + "intersect select DAYOFWEEK(DATA_TYPE) from information_schema.parameters", "MycatIntersect");
    }

    /**
     * 控制组③（防过度修复／零漂移）：全程 Enumerable 的单列 limit 腿无 Rx、无 null 风险，
     * 根声明形态不得被强改成 Object[]。
     */
    @Test
    public void pureEnumerableLimitLegDoesNotDrift() {
        String sql = "select DATA_TYPE from information_schema.columns limit 3";
        String code = codeOf(sql);
        Assert.assertFalse("纯 Enumerable 腿不得出现 Rx select，实际码：" + code,
                code.contains("RxBuiltInMethodImpl.select"));
        Assert.assertTrue("纯 Enumerable 单列腿根声明应保持标量，实际码：" + code,
                code.contains("return java.lang.String.class;"));
    }

    /**
     * 透传铁证 E（分片/View 腿）：活库只有虚 schema，本条腿只有离线 codegen 能进。
     *
     * <p>铁证：{@code select user_id from db1.travelrecord union all ... order by 1} 的错误码形态为
     * 根 {@code getElementType()=java.lang.Object[].class}，而子腿实发是
     * {@code toEnumerable( Observable<Object[]> ).select(row -> row[0].toString())} 即
     * {@code Enumerable<String>}（{@code MycatView} 的 {@code toRows} 在 Enumerable 态解箱，本来合法：
     * Linq4j {@code Enumerable} 容 null）。声明容器行而实发标量 → {@code order by} 的 collation lambda
     * 对元素插 {@code (Object[]) row} 强转 → 运行期
     * {@code ClassCastException: java.lang.Long cannot be cast to [Ljava.lang.Object;}
     *（跳活库铁证：example {@code CrossShardUnionHeaderDifferentialTest}）。
     *
     * <p>本锁钉的是§2.4 透传不变式的反方向：**union 腿的声明形态必须逐字等于子节点实发形态，
     * 不得为了本债而把已是合法标量的 Enumerable 腿硬抬成容器行**（那属无收益扩面，且本身造新错配）。
     * 分片腿的正确形态与控制组③（纯 Enumerable 单列 {@code limit} 腿）同形：根声明为列类型。
     */
    @Test
    public void shardingViewLegUnderUnionKeepsItsOwnScalarShape() {
        String sql = "select user_id from db1.travelrecord union all select user_id from db1.travelrecord order by 1";
        String plan = planOf(sql);
        Assert.assertTrue("该用例应含 MycatUnion + MycatView 腿（否则本锁未覆盖分片腿），实际计划：" + plan,
                plan.contains("MycatUnion") && plan.contains("MycatView"));
        String code = codeOf(sql);
        Assert.assertTrue("分片 View 腿实发为标量 Enumerable（toRows 已解箱），union 根声明须逐字跟随子节点，"
                + "不得硬抬成容器行（实际码：" + code + ")",
                code.contains("return java.lang.String.class;"));
    }

    /**
     * M-1 锁（混合 stream union）：一条 Observable 腿（i_s 虚表）与一条 Enumerable 腿（分片 View）进
     * 入同一 {@code MycatUnion.implementStream} 的 toEnumerate 支时——Rx 腿禁 null 强制容器行，根声明
     * {@code getElementType()=Object[]}；则 Enumerable（View）腿的合法标量实发**必须被 {@code convertTo}
     * 归一成容器行**，否则 {@code enumerableOrObservableConcat} 会把 {@code Object[]} 与 {@code String}
     * 混进同一条 Observable，消费端按 {@code Object[]} 解 {@code (String)} → 运行期 ClassCastException
     *（与 else 支、与本债初起同机理）。
     *
     * <p>实测铁证（修复前 {@code zzzDumpMixedUnion} 出码）：根 {@code getElementType()=Object[]} 而
     * View 子腿实发 {@code public String apply(Object[] row){return row[0].toString();}}，未归一。
     */
    @Test
    public void mixedObservableAndEnumerableUnionLegsShareContainerElementType() {
        String sql = "select DATA_TYPE from information_schema.columns "
                + "union all select user_id from db1.travelrecord";
        String plan = planOf(sql);
        Assert.assertTrue("混合 union 计划应同时含 MycatUnion + MycatView（分片腿）+ i_s 虚表腿，实际计划：" + plan,
                plan.contains("MycatUnion") && plan.contains("MycatView") && plan.contains("information_schema"));
        String code = codeOf(sql);
        // 含 Observable 腿 → 根声明必为容器行
        Assert.assertTrue("含 Observable 腿的 stream union 根声明须为 Object[].class，实际码：" + code,
                code.contains("RxBuiltInMethodImpl.select") && code.contains("return java.lang.Object[].class;"));
        // 每条进 concat 的子腿都须实发容器行：View 标量腿必须被 convertTo 归一
        int containerEmitters = code.split("return new Object\\[\\] \\{", -1).length - 1;
        Assert.assertTrue("混合 union 的每条子腿都必须实发容器行（View 标量腿须 convertTo 归一），"
                + "期望至少 2 处 new Object[] 发射，实得 " + containerEmitters + "，实际码：" + code,
                containerEmitters >= 2);
    }
}
