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
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite.rewriter;

import io.mycat.DrdsSqlCompiler;
import io.mycat.LogicTableType;
import io.mycat.Partition;
import io.mycat.RangeVariable;
import io.mycat.ShardingTableType;
import io.mycat.SimpleColumnInfo;
import io.mycat.calcite.MycatCalciteSupport;
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.table.LogicTable;
import io.mycat.calcite.table.MycatLogicTable;
import io.mycat.calcite.table.NormalTable;
import io.mycat.calcite.table.ShardingTable;
import io.mycat.config.NormalTableConfig;
import io.mycat.config.ShardingTableConfig;
import io.mycat.router.CustomRuleFunction;
import io.mycat.router.ShardingTableHandler;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelColumnOrigin;
import org.apache.calcite.tools.RelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.JDBCType;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * I-DEBT-117：{@code SQLRBORewriter.isSameTargetPartitionGlobalOrNormalJoin} 传参错误锁。
 *
 * <p>背景：normal/global 在左、sharding 在右的等值对分支（现 :1009）调
 * {@code isSameTargetPartitionJoin(right, leftColumnOrigin, rightRelNode, rightRelNode)}——
 * 助手（:1079-1105）以第 3 参为 sharding 表、第 4 参为 normal/global 对端表：
 * <ol>
 *   <li>第 4 参误传 {@code rightRelNode}（= 第 3 参同一 sharding 表）→ 助手内
 *       {@code rightTableType} 恒 SHARDING → NORMAL/GLOBAL 两分支恒死，方法恒 false；
 *       （若有人只改第 4 参不第 2 参，{@code leftColumnOrigin}（normal 表列序）会被拿去索引
 *       sharding 表列 → 列序号越界 IOOBE 或错列判定。）</li>
 *   <li>第 2 参应为 sharding 侧列起源 {@code rightColumnOrigin}（列必须取自分片表）。</li>
 * </ol>
 * 正确形态（镜像 SHARDING 在左的正确参考 :1005）：
 * {@code isSameTargetPartitionJoin(right, rightColumnOrigin, rightRelNode, leftRelNode)}。
 *
 * <p>测试策略（复用 {@code SQLRBORewriterJoinPushdownMatrixTest} 的 fake 惯例）：反射直调
 * {@code isSameTargetPartitionGlobalOrNormalJoin}（private static），构造 normal×sharding 与
 * global×sharding 等值对，断言命中（返回 true）且不抛 IOOBE。RED 先行：现恒 false。
 * 严判格：normal 侧 join 列序(0) ≠ sharding 侧分片键列序(1)——只修第 4 参不修第 2 参仍 false。
 */
public class JoinPushdownParamOrderTest {

    private boolean savedRboPartitionKeyJoin;

    @BeforeEach
    void setUp() {
        savedRboPartitionKeyJoin = DrdsSqlCompiler.RBO_PARTITION_KEY_JOIN;
        DrdsSqlCompiler.RBO_PARTITION_KEY_JOIN = true;
    }

    @AfterEach
    void tearDown() {
        DrdsSqlCompiler.RBO_PARTITION_KEY_JOIN = savedRboPartitionKeyJoin;
    }

    // ------------------------------------------------------------------
    // fake 后端构件（同 SQLRBORewriterJoinPushdownMatrixTest 惯例）
    // ------------------------------------------------------------------

    /**
     * 最小分片函数 fake：SINGLE_INSTANCE_SHARDING_TABLE 型——使 {@code isShardingPartitionKey}
     * 走 tableKeys、{@code isAllPartitionInTargetName} 可真判（SHARDING_INSTANCE_SHARDING_TABLE
     * 在上游对 isAllPartitionInTargetName 恒 false，无法命中 NORMAL 分支）。
     */
    static class FakeSingleShardingFn extends CustomRuleFunction {
        final Set<String> tableKeys;
        final Set<String> dbKeys;
        final Set<String> targetKeys;

        FakeSingleShardingFn(Set<String> dbKeys, Set<String> tableKeys, Set<String> targetKeys) {
            this.dbKeys = dbKeys;
            this.tableKeys = tableKeys;
            this.targetKeys = targetKeys;
        }

        @Override
        public String name() {
            return "fakeSingleShardingFn";
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
            return "single";
        }

        @Override
        public ShardingTableType getShardingTableType() {
            return ShardingTableType.SINGLE_INSTANCE_SHARDING_TABLE;
        }

        @Override
        public int requireShardingKeyCount() {
            return 1;
        }

        @Override
        public boolean requireShardingKeys(Set<String> shardingKeys) {
            return true; // 空谓词视图（TableScan）下 isInPartitionKey 兜底判定为真
        }

        @Override
        public boolean isSameDistribution(CustomRuleFunction customRuleFunction) {
            return customRuleFunction instanceof FakeSingleShardingFn;
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

    /** 分片表：列名列表 + 分片键列名。 */
    private static MycatLogicTable shardingTable(String schemaName, String tableName,
                                                 List<String> columnNames, String shardingKeyName) {
        java.util.List<SimpleColumnInfo> columns = new java.util.ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            SimpleColumnInfo c = col(columnNames.get(i), i);
            if (c.getColumnName().equalsIgnoreCase(shardingKeyName)) {
                c.setShardingKey(true);
            }
            columns.add(c);
        }
        LogicTable logicTable = new LogicTable(LogicTableType.SHARDING, schemaName, tableName,
                columns, Collections.emptyMap(),
                "CREATE TABLE `" + tableName + "` (`c0` int, `c1` int)");
        FakeSingleShardingFn fn = new FakeSingleShardingFn(
                Collections.emptySet(),
                Collections.singleton(shardingKeyName),
                Collections.singleton(shardingKeyName));
        ShardingTable shardingTable = new ShardingTable(logicTable,
                Collections.singletonList(fakePartition()), fn,
                Collections.emptyList(), new ShardingTableConfig());
        fn.callInit(shardingTable, Collections.emptyMap(), Collections.emptyMap());
        return new MycatLogicTable(shardingTable);
    }

    private static MycatLogicTable normalTable(String schemaName, String tableName) {
        LogicTable logicTable = new LogicTable(LogicTableType.NORMAL, schemaName, tableName,
                Arrays.asList(col("c0", 0), col("c1", 1)), Collections.emptyMap(),
                "CREATE TABLE `" + tableName + "` (`c0` int, `c1` int)");
        NormalTable normalTable = new NormalTable(logicTable, fakePartition(), new NormalTableConfig());
        return new MycatLogicTable(normalTable);
    }

    private static MycatLogicTable singleColumnNormalTable(String schemaName, String tableName) {
        LogicTable logicTable = new LogicTable(LogicTableType.NORMAL, schemaName, tableName,
                Collections.singletonList(col("c0", 0)), Collections.emptyMap(),
                "CREATE TABLE `" + tableName + "` (`c0` int)");
        NormalTable normalTable = new NormalTable(logicTable, fakePartition(), new NormalTableConfig());
        return new MycatLogicTable(normalTable);
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

    private static MycatView scanView(RelBuilder rb, String tableName) {
        RelNode scan = rb.scan(tableName).build();
        MycatLogicTable logicTable = scan.getTable().unwrap(MycatLogicTable.class);
        Assertions.assertNotNull(logicTable, "scan 应能 unwrap 出 MycatLogicTable：" + scan);
        return MycatView.ofBottom(scan, logicTable.createDistribution());
    }

    private static boolean invokeIsSameTargetPartitionGlobalOrNormalJoin(
            MycatView left, MycatView right,
            IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals) throws Exception {
        Method m = SQLRBORewriter.class.getDeclaredMethod("isSameTargetPartitionGlobalOrNormalJoin",
                MycatView.class, MycatView.class, IdentityHashMap.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, left, right, equals);
    }

    private static RelNode scanOf(RelBuilder rb, String tableName) {
        return rb.scan(tableName).build();
    }

    // ------------------------------------------------------------------
    // 矩阵：左侧表类型 × 右侧表类型 × 列对齐形态
    // ------------------------------------------------------------------

    @Test
    void normalLeft_shardingRight_hitsNormalBranch_noIOOBE() throws Exception {
        RelBuilder rb = newRelBuilder(
                normalTable("ns", "ntb"),
                shardingTable("ns", "stb", Arrays.asList("c0", "c1"), "c0"));
        MycatView left = scanView(rb, "ntb");
        MycatView right = scanView(rb, "stb");

        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(scanOf(rb, "ntb").getTable(), 0, false),
                new RelColumnOrigin(scanOf(rb, "stb").getTable(), 0, false));

        Assertions.assertTrue(invokeIsSameTargetPartitionGlobalOrNormalJoin(left, right, equals),
                "normal(左)×sharding(右) 等值对必须命中 NORMAL 分支（现恒 false=分支恒死，I-DEBT-117）");
    }

    @Test
    void globalLeft_shardingRight_hitsGlobalBranch_noIOOBE() throws Exception {
        RelBuilder rb2 = newRelBuilder(
                globalTable("ns", "gtb"),
                shardingTable("ns", "stb", Arrays.asList("c0", "c1"), "c0"));
        MycatView left = scanView(rb2, "gtb");
        MycatView right = scanView(rb2, "stb");

        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(scanOf(rb2, "gtb").getTable(), 0, false),
                new RelColumnOrigin(scanOf(rb2, "stb").getTable(), 0, false));

        Assertions.assertTrue(invokeIsSameTargetPartitionGlobalOrNormalJoin(left, right, equals),
                "global(左)×sharding(右) 等值对必须命中 GLOBAL 分支（现恒 false=分支恒死，I-DEBT-117）");
    }

    /**
     * 严判格：normal 侧 join 列序 0，sharding 侧分片键是列序 1（c1）。
     * 只修第 4 参（保留 leftColumnOrigin）时，助手拿 normal 表列序 0 去 sharding 表取列 →
     * 取到 c0（非分片键）→ false；正确修复（第 2 参=rightColumnOrigin）→ 取到 c1 → true。
     */
    @Test
    void columnOriginMustComeFromShardingSide() throws Exception {
        RelBuilder rb = newRelBuilder(
                singleColumnNormalTable("ns", "ntb1"),
                shardingTable("ns", "stb", Arrays.asList("c0", "c1"), "c1"));
        MycatView left = scanView(rb, "ntb1");
        MycatView right = scanView(rb, "stb");

        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(scanOf(rb, "ntb1").getTable(), 0, false),
                new RelColumnOrigin(scanOf(rb, "stb").getTable(), 1, false));

        Assertions.assertTrue(invokeIsSameTargetPartitionGlobalOrNormalJoin(left, right, equals),
                "列起源必须取自 sharding 侧（rightColumnOrigin）：sharding 分片键=c1(序1)，"
                        + "误用 leftColumnOrigin(序0)=c0 非分片键将恒 false");
    }

    @Test
    void shardingLeft_normalRight_sanityStillTrue() throws Exception {
        // SHARDING 在左的正确参考路径（:1005）不回归
        RelBuilder rb = newRelBuilder(
                shardingTable("ns", "stb", Arrays.asList("c0", "c1"), "c0"),
                normalTable("ns", "ntb"));
        MycatView left = scanView(rb, "stb");
        MycatView right = scanView(rb, "ntb");

        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(scanOf(rb, "stb").getTable(), 0, false),
                new RelColumnOrigin(scanOf(rb, "ntb").getTable(), 0, false));

        Assertions.assertTrue(invokeIsSameTargetPartitionGlobalOrNormalJoin(left, right, equals),
                "sharding(左)×normal(右) 为既有正确路径，必须保持命中");
    }

    @Test
    void shardingBothSides_false_sanity() throws Exception {
        // 双 sharding 不归本方法管（isSameTargetPartitionJoin/isErJoin 负责本分支），恒 false
        RelBuilder rb = newRelBuilder(
                shardingTable("ns", "stb1", Arrays.asList("c0", "c1"), "c0"),
                shardingTable("ns", "stb2", Arrays.asList("c0", "c1"), "c0"));
        MycatView left = scanView(rb, "stb1");
        MycatView right = scanView(rb, "stb2");

        IdentityHashMap<RelColumnOrigin, RelColumnOrigin> equals = new IdentityHashMap<>();
        equals.put(new RelColumnOrigin(scanOf(rb, "stb1").getTable(), 0, false),
                new RelColumnOrigin(scanOf(rb, "stb2").getTable(), 0, false));

        Assertions.assertFalse(invokeIsSameTargetPartitionGlobalOrNormalJoin(left, right, equals),
                "双 sharding 形态不进 NORMAL/GLOBAL 分支");
    }

    private static MycatLogicTable globalTable(String schemaName, String tableName) {
        LogicTable logicTable = new LogicTable(LogicTableType.GLOBAL, schemaName, tableName,
                Arrays.asList(col("c0", 0), col("c1", 1)), Collections.emptyMap(),
                "CREATE TABLE `" + tableName + "` (`c0` int, `c1` int)");
        io.mycat.calcite.table.GlobalTable globalTable = new io.mycat.calcite.table.GlobalTable(
                logicTable, Collections.singletonList(fakePartition()),
                new io.mycat.config.GlobalTableConfig());
        return new MycatLogicTable(globalTable);
    }
}
