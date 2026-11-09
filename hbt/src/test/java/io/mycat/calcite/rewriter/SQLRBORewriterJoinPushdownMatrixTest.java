/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 */
package io.mycat.calcite.rewriter;

import com.google.common.collect.ImmutableList;
import io.mycat.DrdsSqlCompiler;
import io.mycat.LogicTableType;
import io.mycat.Partition;
import io.mycat.RangeVariable;
import io.mycat.ShardingTableType;
import io.mycat.SimpleColumnInfo;
import io.mycat.TableHandler;
import io.mycat.calcite.MycatCalciteSupport;
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.table.GlobalTable;
import io.mycat.calcite.table.LogicTable;
import io.mycat.calcite.table.MycatLogicTable;
import io.mycat.calcite.table.ShardingTable;
import io.mycat.config.GlobalTableConfig;
import io.mycat.config.ShardingTableConfig;
import io.mycat.router.CustomRuleFunction;
import io.mycat.router.ShardingTableHandler;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.metadata.RelColumnOrigin;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * I-DEBT-93：join 下推判定矩阵化（离线锁）。
 *
 * <p>背景：I-DEBT-92 修复了 {@code SQLRBORewriter.pushDownERTable} 中
 * “join 条件全为派生表达式 → equals 空 → 空流 allMatch/isErJoin 恒真 → 误判 ER 下推”
 * 的空等值对形态（活体基线：{@code SELECT COUNT(*) FROM 分片表 a JOIN 分片表 b
 * ON a.id = b.id - 1} 修复前 0 行、修复后 29 行）。但该修复只覆盖“空等值对”一种形态，
 * 本测试把判定流矩阵化锁死：
 *
 * <pre>
 * 矩阵（pushDownERTable，单位级，反射直调）：
 *   equals 形态 \ join 类型     INNER        LEFT         SEMI
 *   ------------------------+------------+------------+-----------
 *   空（全派生表达式）        | empty      | empty      | empty     ← 回退内存 JOIN（92 语义）
 *   全纯列等值（同分布）      | 下推 View  | 下推 View  | 下推 View ← 既有行为
 *   部分派生+部分纯列        | 纯列对驱动 | 同左       | 同左      ← 既有行为（派生对不阻断）
 *   （附）FULL               | empty（switch 直接拒绝，红线）
 * </pre>
 *
 * <p>{@code isErJoin} 单元格（结构性锁）：
 * <ol>
 *   <li>空 equals map：加固前空流 {@code allMatch} 恒真（vacuous truth），必须显式 false
 *       ——空 = 不可证明同分片 = 不构成 ER join；</li>
 *   <li>同分布（同 erUniqueID）+ 同列标记剖面 → true；</li>
 *   <li>sharding × global → false；</li>
 *   <li>同分布但列标记剖面不等（键列 vs 非键列）→ false；</li>
 *   <li>同实现不同 erUniqueID（不同分片函数）→ false。</li>
 * </ol>
 *
 * <p>测试策略：不解析 SQL，不跑规划器——直接构造最小 RelNode 树（真 TableScan/Project/Join +
 * 真 MycatView，表后端用 {@link FakeShardingFn} 假分片函数），经反射直调
 * {@code pushDownERTable} / {@code isErJoin}（private static，hbt 单模块内稳定签名）。
 * 派生列经真实 {@code RelMetadataQuery.getColumnOrigin} 语义产生：
 * Project 内列引用直传 = 纯（isDerived=false），表达式（如 $0+0）= 派生（isDerived=true），
 * 与生产 SQL 中 {@code a.id = b.id - 1} 经下推投影后的起源形态同构。
 */
public class SQLRBORewriterJoinPushdownMatrixTest {

    // ------------------------------------------------------------------
    // fake 后端构件（参考既有惯例：hbt 测试内直接 new 真 MycatLogicTable，函数用最小 fake）
    // ------------------------------------------------------------------

    /** 最小分片函数 fake：以 erUniqueID 判同分布（模仿 AutoFunction.isSameDistribution 语义）。 */
    static class FakeShardingFn extends CustomRuleFunction {
        final String erUniqueID;
        final Set<String> dbKeys;
        final Set<String> tableKeys;
        final Set<String> targetKeys;

        FakeShardingFn(String erUniqueID, Set<String> dbKeys, Set<String> tableKeys, Set<String> targetKeys) {
            this.erUniqueID = erUniqueID;
            this.dbKeys = dbKeys;
            this.tableKeys = tableKeys;
            this.targetKeys = targetKeys;
        }

        @Override
        public String name() {
            return "fakeShardingFn_" + erUniqueID;
        }

        @Override
        public List<Partition> calculate(Map<String, RangeVariable> values) {
            return Collections.singletonList(fakePartition());
        }

        @Override
        protected void init(ShardingTableHandler tableHandler, Map<String, Object> properties, Map<String, Object> ranges) {
            // no-op
        }

        @Override
        public boolean isShardingDbKey(String name) {
            return dbKeys.contains(name);
        }

        @Override
        public boolean isShardingTableKey(String name) {
            return tableKeys.contains(name);
        }

        @Override
        public boolean isShardingTargetKey(String name) {
            return targetKeys.contains(name);
        }

        @Override
        public String getErUniqueID() {
            return erUniqueID;
        }

        @Override
        public ShardingTableType getShardingTableType() {
            return ShardingTableType.SHARDING_INSTANCE_SHARDING_TABLE;
        }

        @Override
        public int requireShardingKeyCount() {
            return 1;
        }

        @Override
        public boolean requireShardingKeys(Set<String> shardingKeys) {
            java.util.HashSet<String> all = new java.util.HashSet<>();
            all.addAll(dbKeys);
            all.addAll(tableKeys);
            all.addAll(targetKeys);
            return all.containsAll(shardingKeys);
        }

        @Override
        public boolean isSameDistribution(CustomRuleFunction customRuleFunction) {
            return customRuleFunction instanceof FakeShardingFn
                    && Objects.equals(this.erUniqueID, ((FakeShardingFn) customRuleFunction).erUniqueID);
        }
    }

    private static Partition fakePartition() {
        return new Partition() {
            @Override
            public String getTargetName() {
                return "target0";
            }

            @Override
            public String getSchema() {
                return "db0";
            }

            @Override
            public String getTable() {
                return "tb0";
            }

            @Override
            public Integer getDbIndex() {
                return 0;
            }

            @Override
            public Integer getTableIndex() {
                return 0;
            }

            @Override
            public Integer getIndex() {
                return 0;
            }
        };
    }

    private static SimpleColumnInfo col(String name, int id) {
        return new SimpleColumnInfo(name, 0, 0, JDBCType.INTEGER, true, false, false, false, id, true);
    }

    /** 两列(c0,c1)分片表；fn 决定键列剖面。c0 为分片键（db+table+target key）。 */
    private static MycatLogicTable shardingTable(String schemaName, String tableName, FakeShardingFn fn) {
        SimpleColumnInfo c0 = col("c0", 0);
        SimpleColumnInfo c1 = col("c1", 1);
        c0.setShardingKey(true);
        LogicTable logicTable = new LogicTable(LogicTableType.SHARDING, schemaName, tableName,
                Arrays.asList(c0, c1), Collections.emptyMap(),
                "CREATE TABLE `" + tableName + "` (\n  `c0` int,\n  `c1` int\n)");
        ShardingTable shardingTable = new ShardingTable(logicTable,
                Collections.singletonList(fakePartition()), fn,
                Collections.emptyList(), new ShardingTableConfig());
        fn.callInit(shardingTable, Collections.emptyMap(), Collections.emptyMap());
        return new MycatLogicTable(shardingTable);
    }

    private static MycatLogicTable globalTable(String schemaName, String tableName) {
        LogicTable logicTable = new LogicTable(LogicTableType.GLOBAL, schemaName, tableName,
                Arrays.asList(col("c0", 0), col("c1", 1)), Collections.emptyMap(),
                "CREATE TABLE `" + tableName + "` (\n  `c0` int,\n  `c1` int\n)");
        GlobalTable globalTable = new GlobalTable(logicTable,
                Collections.singletonList(fakePartition()), new GlobalTableConfig());
        return new MycatLogicTable(globalTable);
    }

    private static RelBuilder newRelBuilder(MycatLogicTable... tables) {
        RelOptCluster cluster = DrdsSqlCompiler.newCluster();
        CalciteSchema rootSchema = CalciteSchema.createRootSchema(false, false);
        for (MycatLogicTable table : tables) {
            rootSchema.add(table.getTable().getTableName(), table);
        }
        RelOptSchema schema = new CalciteCatalogReader(
                rootSchema,
                Collections.emptyList(),
                MycatCalciteSupport.TypeFactory,
                MycatCalciteSupport.calciteConnectionConfig);
        return MycatCalciteSupport.relBuilderFactory.create(cluster, schema);
    }

    private static io.mycat.calcite.rewriter.Distribution distributionOf(RelNode scan) {
        MycatLogicTable logicTable = scan.getTable().unwrap(MycatLogicTable.class);
        Assertions.assertNotNull(logicTable, "scan 应能 unwrap 出 MycatLogicTable：" + scan);
        return logicTable.createDistribution();
    }

    /** 纯列视图：inner = TableScan（列起源 isDerived=false，与生产底表视图同构）。 */
    private static MycatView scanView(RelBuilder rb, String tableName) {
        RelNode scan = rb.scan(tableName).build();
        return MycatView.ofBottom(scan, distributionOf(scan));
    }

    /** 派生列视图：inner = Project(c0 = $0 + 0)，join 键列起源 isDerived=true（模拟 a.id = b.id - 1 的下推投影形态）。 */
    private static MycatView derivedProjectView(RelBuilder rb, String tableName) {
        RelNode scan = rb.scan(tableName).build();
        RexBuilder rexBuilder = rb.getRexBuilder();
        RelDataType fieldType = scan.getRowType().getFieldList().get(0).getType();
        List<RexNode> projects = Collections.singletonList(rexBuilder.makeCall(
                SqlStdOperatorTable.PLUS,
                rexBuilder.makeInputRef(fieldType, 0),
                rexBuilder.makeExactLiteral(BigDecimal.ZERO, fieldType)));
        RelNode project = LogicalProject.create(scan, ImmutableList.of(), projects, (List<String>) null);
        return MycatView.ofBottom(project, distributionOf(scan));
    }

    /** 混合列视图：inner = Project(c0 = $0 直传[纯], c1 = $1 + 0 [派生])。 */
    private static MycatView mixedProjectView(RelBuilder rb, String tableName) {
        RelNode scan = rb.scan(tableName).build();
        RexBuilder rexBuilder = rb.getRexBuilder();
        RelDataType t0 = scan.getRowType().getFieldList().get(0).getType();
        RelDataType t1 = scan.getRowType().getFieldList().get(1).getType();
        List<RexNode> projects = Arrays.asList(
                rexBuilder.makeInputRef(t0, 0),
                rexBuilder.makeCall(SqlStdOperatorTable.PLUS,
                        rexBuilder.makeInputRef(t1, 1),
                        rexBuilder.makeExactLiteral(BigDecimal.ZERO, t1)));
        RelNode project = LogicalProject.create(scan, ImmutableList.of(), projects, (List<String>) null);
        return MycatView.ofBottom(project, distributionOf(scan));
    }

    /**
     * 构造 LogicalJoin(left,right, condition)，condition 为 leftFields 与 rightFields 的
     * 等值对（偶数位=左侧列序，奇数位=右侧列序）之 AND。
     */
    private static Join buildJoin(RelBuilder rb, JoinRelType joinType, RelNode left, RelNode right,
                                  int... leftRightFieldPairs) {
        RexBuilder rexBuilder = rb.getRexBuilder();
        int leftFields = left.getRowType().getFieldCount();
        List<RexNode> operands = new ArrayList<>();
        for (int i = 0; i < leftRightFieldPairs.length; i += 2) {
            int l = leftRightFieldPairs[i];
            int r = leftRightFieldPairs[i + 1];
            RelDataType lType = left.getRowType().getFieldList().get(l).getType();
            RelDataType rType = right.getRowType().getFieldList().get(r).getType();
            operands.add(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
                    rexBuilder.makeInputRef(lType, l),
                    rexBuilder.makeInputRef(rType, leftFields + r)));
        }
        RexNode condition = operands.size() == 1 ? operands.get(0)
                : rexBuilder.makeCall(SqlStdOperatorTable.AND, operands);
        return LogicalJoin.create(left, right, ImmutableList.of(), condition,
                Collections.emptySet(), joinType);
    }

    // ------------------------------------------------------------------
    // 反射入口（private static，签名在 hbt 单模块内稳定）
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Optional<RelNode> invokePushDownERTable(Join join, MycatView left, MycatView right) throws Exception {
        Method m = SQLRBORewriter.class.getDeclaredMethod("pushDownERTable", Join.class, MycatView.class, MycatView.class);
        m.setAccessible(true);
        return (Optional<RelNode>) m.invoke(null, join, left, right);
    }

    private static boolean invokeIsErJoin(IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals) throws Exception {
        Method m = SQLRBORewriter.class.getDeclaredMethod("isErJoin", IdentityHashMap.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, equals);
    }

    private static final List<JoinRelType> MATRIX_JOIN_TYPES =
            Arrays.asList(JoinRelType.INNER, JoinRelType.LEFT, JoinRelType.SEMI);

    // ------------------------------------------------------------------
    // 矩阵行 1：equals 空（join 条件全为派生表达式）→ 一律 empty，回退内存 JOIN
    // ------------------------------------------------------------------

    @Test
    void matrix_emptyEquals_returnsEmpty_forInnerLeftSemi() throws Exception {
        for (JoinRelType joinType : MATRIX_JOIN_TYPES) {
            RelBuilder rb = newRelBuilder(
                    shardingTable("ns", "tb1", new FakeShardingFn("ER1",
                            Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))),
                    shardingTable("ns", "tb2", new FakeShardingFn("ER1",
                            Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))));
            MycatView left = derivedProjectView(rb, "tb1");
            MycatView right = derivedProjectView(rb, "tb2");
            Join join = buildJoin(rb, joinType, left, right, 0, 0);

            Optional<RelNode> out = invokePushDownERTable(join, left, right);
            Assertions.assertFalse(out.isPresent(),
                    "矩阵[空 equals × " + joinType + "]：无任何纯列等值对即无从证明同分片，必须回退内存 JOIN（I-DEBT-92 语义），实际=" + out);
        }
    }

    /** 附：FULL 在 join 类型开关处直接拒绝（红线，不进入等值对计算）。 */
    @Test
    void matrix_fullJoin_rejectedBeforeEqualsComputation() throws Exception {
        RelBuilder rb = newRelBuilder(
                shardingTable("ns", "tb1", new FakeShardingFn("ER1",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))),
                shardingTable("ns", "tb2", new FakeShardingFn("ER1",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))));
        MycatView left = scanView(rb, "tb1");
        MycatView right = scanView(rb, "tb2");
        Join join = buildJoin(rb, JoinRelType.FULL, left, right, 0, 0);
        Optional<RelNode> out = invokePushDownERTable(join, left, right);
        Assertions.assertFalse(out.isPresent(), "FULL join 不做 ER 下推，实际=" + out);
    }

    // ------------------------------------------------------------------
    // 矩阵行 2：equals 非空且全纯列等值（同分布 sharding×sharding）→ 保持既有行为（ER 下推）
    // ------------------------------------------------------------------

    @Test
    void matrix_pureColumnEquiSameDistribution_pushdown_forInnerLeftSemi() throws Exception {
        for (JoinRelType joinType : MATRIX_JOIN_TYPES) {
            RelBuilder rb = newRelBuilder(
                    shardingTable("ns", "tb1", new FakeShardingFn("ER1",
                            Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))),
                    shardingTable("ns", "tb2", new FakeShardingFn("ER1",
                            Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))));
            MycatView left = scanView(rb, "tb1");
            MycatView right = scanView(rb, "tb2");
            Join join = buildJoin(rb, joinType, left, right, 0, 0);

            Optional<RelNode> out = invokePushDownERTable(join, left, right);
            Assertions.assertTrue(out.isPresent(),
                    "矩阵[全纯列等值同分布 × " + joinType + "]：既有行为为 ER 下推，实际=" + out);
            Assertions.assertTrue(out.get() instanceof MycatView,
                    "矩阵[全纯列等值同分布 × " + joinType + "]：下推结果应为 MycatView，实际=" + out.get().getClass().getSimpleName());
            Assertions.assertTrue(((MycatView) out.get()).getRelNode() instanceof Join,
                    "矩阵[全纯列等值同分布 × " + joinType + "]：下推视图应包裹原 JOIN，实际=" + ((MycatView) out.get()).getRelNode());
            Assertions.assertEquals(2, ((MycatView) out.get()).getDistribution().getShardingTables().size(),
                    "矩阵[全纯列等值同分布 × " + joinType + "]：合并分片应含两侧两张表");
        }
    }

    // ------------------------------------------------------------------
    // 矩阵行 3：部分派生 + 部分纯列 → 派生对被 isDerived 跳过，纯列对驱动判定（既有行为）
    // ------------------------------------------------------------------

    @Test
    void matrix_partialDerivedPartialPure_purePairDrives_forInnerLeftSemi() throws Exception {
        for (JoinRelType joinType : MATRIX_JOIN_TYPES) {
            RelBuilder rb = newRelBuilder(
                    shardingTable("ns", "tb1", new FakeShardingFn("ER1",
                            Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))),
                    shardingTable("ns", "tb2", new FakeShardingFn("ER1",
                            Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))));
            MycatView left = mixedProjectView(rb, "tb1");   // c0 纯，c1 派生
            MycatView right = scanView(rb, "tb2");          // 全纯
            Join join = buildJoin(rb, joinType, left, right, 0, 0, 1, 1);

            Optional<RelNode> out = invokePushDownERTable(join, left, right);
            Assertions.assertTrue(out.isPresent(),
                    "矩阵[部分派生部分纯列 × " + joinType + "]：纯列对应驱动判定（派生对不阻断既有 ER 下推），实际=" + out);
            Assertions.assertTrue(out.get() instanceof MycatView,
                    "矩阵[部分派生部分纯列 × " + joinType + "]：下推结果应为 MycatView，实际=" + out.get().getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // isErJoin 结构性锁
    // ------------------------------------------------------------------

    /**
     * 结构锁：空 equals map 不得因空流 allMatch 恒真而判为 ER join。
     * 加固前该断言 RED（vacuous truth 恒 true）；加固后 false——
     * 语义与 I-DEBT-92 一致：空 = 不可证明同分片 = 不构成 ER join。
     */
    @Test
    void isErJoin_emptyEqualsMap_mustNotBeVacuouslyTrue() throws Exception {
        boolean result = invokeIsErJoin(new IdentityHashMap<>());
        Assertions.assertFalse(result,
                "空 equals map 在 allMatch 上恒真（vacuous truth），必须显式判 false（空=不可证明同分片）");
    }

    @Test
    void isErJoin_sameDistributionAndSameColumnFlags_true() throws Exception {
        RelBuilder rb = newRelBuilder(
                shardingTable("ns", "tb1", new FakeShardingFn("ER1",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))),
                shardingTable("ns", "tb2", new FakeShardingFn("ER1",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))));
        RelNode s1 = rb.scan("tb1").build();
        RelNode s2 = rb.scan("tb2").build();
        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(s1.getTable(), 0, false), new RelColumnOrigin(s2.getTable(), 0, false));
        Assertions.assertTrue(invokeIsErJoin(equals), "同分布 + 同列标记剖面应判 ER join");
    }

    @Test
    void isErJoin_shardingAgainstGlobal_false() throws Exception {
        RelBuilder rb = newRelBuilder(
                shardingTable("ns", "tb1", new FakeShardingFn("ER1",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))),
                globalTable("ns", "gtb"));
        RelNode s1 = rb.scan("tb1").build();
        RelNode s2 = rb.scan("gtb").build();
        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(s1.getTable(), 0, false), new RelColumnOrigin(s2.getTable(), 0, false));
        Assertions.assertFalse(invokeIsErJoin(equals), "sharding × global 不构成 ER join");
    }

    /** 同分布但 join 列标记剖面不等（左侧非键列 c1 vs 右侧键列 c0）→ 不构成 ER join。 */
    @Test
    void isErJoin_sameDistributionButDifferentColumnFlagProfile_false() throws Exception {
        RelBuilder rb = newRelBuilder(
                shardingTable("ns", "tb1", new FakeShardingFn("ER1",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))),
                shardingTable("ns", "tb2", new FakeShardingFn("ER1",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))));
        RelNode s1 = rb.scan("tb1").build();
        RelNode s2 = rb.scan("tb2").build();
        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(s1.getTable(), 1, false), new RelColumnOrigin(s2.getTable(), 0, false));
        Assertions.assertFalse(invokeIsErJoin(equals), "同分布但列标记剖面不等（键列 vs 非键列）不应判 ER join");
    }

    /** 同实现不同 erUniqueID（不同分片函数，跨分片分布）→ 不构成 ER join。 */
    @Test
    void isErJoin_differentDistribution_sameClass_false() throws Exception {
        RelBuilder rb = newRelBuilder(
                shardingTable("ns", "tb1", new FakeShardingFn("ER1",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))),
                shardingTable("ns", "tb2", new FakeShardingFn("ER2",
                        Collections.singleton("c0"), Collections.singleton("c0"), Collections.singleton("c0"))));
        RelNode s1 = rb.scan("tb1").build();
        RelNode s2 = rb.scan("tb2").build();
        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(s1.getTable(), 0, false), new RelColumnOrigin(s2.getTable(), 0, false));
        Assertions.assertFalse(invokeIsErJoin(equals), "不同分片函数（不同 erUniqueID）不应判 ER join");
    }
}
