package io.mycat.calcite.localrel;

import com.google.common.collect.ImmutableList;
import io.mycat.HintTools;
import io.mycat.calcite.MycatCalciteSupport;
import io.mycat.calcite.MycatConvention;
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.rewriter.SQLRBORewriter;
import io.mycat.calcite.table.AbstractMycatTable;
import io.mycat.calcite.table.ShardingTable;
import org.apache.calcite.plan.*;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.*;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.logical.ToLogicalConverter;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.immutables.value.Value;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static io.mycat.calcite.rewriter.SQLRBORewriter.view;

@Value.Enclosing
public class LocalRules {
    public static final RelBuilderFactory LOCAL_BUILDER =
            RelBuilder.proto(
                    Contexts.of(LocalAggregate.AGGREGATE_FACTORY,
                            LocalJoin.JOIN_FACTORY,
                            LocalSort.SORT_FACTORY,
                            LocalAggregate.AGGREGATE_FACTORY,
                            LocalUnion.SET_OP_FACTORY,
                            LocalValues.VALUES_FACTORY,
                            LocalTableScan.TABLE_SCAN_FACTORY));

    public static final List<RelOptRule> RBO_RULES = ImmutableList.of(
            LocalRules.ToLocalTableScanRule.DEFAULT_CONFIG.toRule(),
            LocalRules.ToViewTableScanRule.DEFAULT_CONFIG.toRule(),
            LocalRules.PrimaryShardingTableFilterViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.ProjectViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.AggViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.SortViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.JoinViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.CalcViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.UnionAllViewRule.DEFAULT_CONFIG.toRule()
    );

    public static final List<RelOptRule> CBO_RULES = ImmutableList.of(
            LocalRules.ToLocalTableScanRule.DEFAULT_CONFIG.toRule(),
            LocalRules.ToViewTableScanRule.DEFAULT_CONFIG.toRule(),
            UniversalFilterViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.ProjectViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.AggViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.SortViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.JoinViewRule.DEFAULT_CONFIG.toRule(),
            LocalRules.CalcViewRule.DEFAULT_CONFIG.toRule()
    );

    public static RelNode normalize(RelNode relNode) {
//        ToLogicalConverter toLogicalConverter = getToLogicalConverter(relNode);
//        RelNode res = relNode.accept(toLogicalConverter);
//        RelOptCluster cluster = res.getCluster();
//        return cluster.getPlanner().changeTraits(res, cluster.traitSetOf(Convention.NONE));
        return relNode;
    }

    public static class ToLocalTableScanRule extends ConverterRule {


        protected ToLocalTableScanRule(ConverterRule.Config config) {
            super(config);
        }

        @Override
        public RelNode convert(RelNode rel) {
            return LocalTableScan.create((LogicalTableScan) rel);
        }

        public static final Config DEFAULT_CONFIG = Config.INSTANCE
                .withConversion(LogicalTableScan.class, Convention.NONE,
                        LocalConvention.INSTANCE, "ToLocalTableScanRule")
                .as(ToLocalTableScanRule.Config.class)
                .withRuleFactory(ToLocalTableScanRule::new);
    }

    public static class ToViewTableScanRule extends ConverterRule {


        protected ToViewTableScanRule(ConverterRule.Config config) {
            super(config);
        }

        @Override
        public RelNode convert(RelNode rel) {
            final LocalTableScan logicalTableScan = (LocalTableScan) rel;
            AbstractMycatTable abstractMycatTable = logicalTableScan.getTable().unwrap(AbstractMycatTable.class);
            return MycatView.ofBottom(logicalTableScan, abstractMycatTable.createDistribution());
        }

        public static final Config DEFAULT_CONFIG = Config.INSTANCE
                .withConversion(LocalTableScan.class, LocalConvention.INSTANCE,
                        MycatConvention.INSTANCE, "ToViewTableScanRule")
                .as(ToViewTableScanRule.Config.class)
                .withRuleFactory(ToViewTableScanRule::new);
    }

    public static class UniversalFilterViewRule extends RelRule<UniversalFilterViewRule.UniversalFilterViewRuleConfig> {

        public UniversalFilterViewRule(UniversalFilterViewRuleConfig config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Filter filter = call.rel(0);
            MycatView view = call.rel(1);
            view(view, LocalFilter.create(filter, view)).ifPresent(new Consumer<RelNode>() {
                @Override
                public void accept(RelNode res) {
                    call.transformTo(normalize(res));
                }
            });
        }

        public static final UniversalFilterViewRuleConfig DEFAULT_CONFIG = ImmutableLocalRules.UniversalFilterViewRuleConfig.builder().build().withOperandFor();

        @Value.Immutable
        public interface UniversalFilterViewRuleConfig extends RelRule.Config {
            @Override
            default UniversalFilterViewRule toRule() {
                return new UniversalFilterViewRule(this);
            }


            default UniversalFilterViewRuleConfig withOperandFor() {
                return withOperandSupplier(b0 ->
                        b0.operand(Filter.class).oneInput(b1 -> b1.operand(MycatView.class)
                                .predicate(m -> m.allowPushdown())
                                .noInputs()))
                        .withDescription("UniversalFilterViewRule")
                        .as(UniversalFilterViewRuleConfig.class);
            }
        }
    }

    public static class PrimaryShardingTableFilterViewRule extends RelRule<PrimaryShardingTableFilterViewRule.PrimaryShardingFilterConfig> {

        public PrimaryShardingTableFilterViewRule(PrimaryShardingFilterConfig config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Filter filter = call.rel(0);
            MycatView view = call.rel(1);
            view(view, LocalFilter.create(filter, view)).ifPresent(new Consumer<RelNode>() {
                @Override
                public void accept(RelNode res) {
                    switch (view.getDistribution().type()) {
                        case BROADCAST:
                        case PHY: {
                            call.transformTo(normalize(res));
                            break;
                        }
                        case SHARDING: {
                            ShardingTable shardingTable = view.getDistribution().getShardingTables().get(0);
                            if (shardingTable.getIndexTables().isEmpty()) {
                                call.transformTo(normalize(res));
                                return;
                            } else {
                                RelNode relNode = view.getRelNode();
                                if (relNode instanceof TableScan) {
                                    TableScan tableScan = (TableScan) relNode;
                                    RelHint lastIndexHint = HintTools.getLastIndexHint(tableScan.getHints());
                                    if (lastIndexHint != null) {
                                        String indexName = lastIndexHint.listOptions.get(0);
                                        List<RelNode> relNodes = MycatView.produceIndexViews(tableScan, filter.getCondition(), tableScan.identity(), view.getRowType(),
                                                indexName);
                                        if (!relNodes.isEmpty()) {
                                            call.transformTo(normalize(relNodes.get(0)));
                                        }
                                        return;
                                    }
                                }

                            }
                            break;
                        }
                    }
                }
            });
        }

        public static final PrimaryShardingFilterConfig DEFAULT_CONFIG = ImmutableLocalRules.PrimaryShardingFilterConfig.builder().build().withOperandFor();

        @Value.Immutable
        public interface PrimaryShardingFilterConfig extends RelRule.Config {
            @Override
            default PrimaryShardingTableFilterViewRule toRule() {
                return new PrimaryShardingTableFilterViewRule(this);
            }


            default PrimaryShardingFilterConfig withOperandFor() {
                return withOperandSupplier(b0 ->
                        b0.operand(Filter.class)
                                .oneInput(b1 -> b1.operand(MycatView.class)
                                        .predicate(m -> m.allowPushdown())
                                        .noInputs()))
                        .withDescription("PrimaryShardingTableFilterViewRule")
                        .as(PrimaryShardingFilterConfig.class);
            }
        }
    }

    public static class ProjectViewRule extends RelRule<ProjectViewRule.ProjectViewRuleConfig> {

        public static final ProjectViewRuleConfig DEFAULT_CONFIG = ImmutableLocalRules.ProjectViewRuleConfig.builder().build().withOperandFor();

        public ProjectViewRule(ProjectViewRuleConfig config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Project project = call.rel(0);
            MycatView view = call.rel(1);
            view(view, LocalProject.create(project, view)).ifPresent(res -> {
                call.transformTo(normalize(res));
            });
        }

        @Value.Immutable
        public interface ProjectViewRuleConfig extends RelRule.Config {
            @Override
            default ProjectViewRule toRule() {
                return new ProjectViewRule(this);
            }


            default ProjectViewRuleConfig withOperandFor() {
                return withOperandSupplier(b0 ->
                        b0.operand(Project.class)
                                .oneInput(b1 -> b1.operand(MycatView.class)
//                                        .predicate(m -> !m.isMergeSort())
                                        .noInputs()))
                        .withDescription("ProjectViewRule")
                        .as(ProjectViewRuleConfig.class);
            }
        }
    }

    public static class JoinViewRule extends RelRule<JoinViewRule.JoinViewRuleConfig> {

        public static final JoinViewRuleConfig DEFAULT_CONFIG = ImmutableLocalRules.JoinViewRuleConfig.builder().build().withOperandFor();

        public JoinViewRule(JoinViewRuleConfig config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Join join = call.rel(0);
            MycatView left = call.rel(1);
            MycatView right = call.rel(2);
            Optional<RelNode> relNodeOptional = SQLRBORewriter.rboJoinRewrite(left, right, LocalJoin.create(join, left, right));
            relNodeOptional.ifPresent(new Consumer<RelNode>() {
                @Override
                public void accept(RelNode rel) {
                    call.transformTo(normalize(rel));
                }
            });
        }

        @Value.Immutable
        public interface JoinViewRuleConfig extends RelRule.Config {
            @Override
            default JoinViewRule toRule() {
                return new JoinViewRule(this);
            }

            default JoinViewRuleConfig withOperandFor() {
                return withOperandSupplier(b0 ->
                        b0.operand(Join.class)
                                .inputs(b1 -> b1.operand(MycatView.class).predicate(m -> m.allowPushdown()).noInputs(),
                                        b2 -> b2.operand(MycatView.class).predicate(m -> m.allowPushdown()).noInputs()))
                        .withDescription("JoinViewRule")
                        .as(JoinViewRuleConfig.class);
            }
        }
    }

    public static class AggViewRule extends RelRule<AggViewRule.AggViewRuleConfig> {

        public static final AggViewRuleConfig DEFAULT_CONFIG = ImmutableLocalRules.AggViewRuleConfig.builder().build().withOperandFor();

        public AggViewRule(AggViewRuleConfig config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Aggregate aggregate = call.rel(0);
            MycatView input = call.rel(1);
            SQLRBORewriter.aggregate(input, LocalAggregate.create(aggregate, input)).ifPresent(new Consumer<RelNode>() {
                @Override
                public void accept(RelNode res) {
                    call.transformTo(normalize(res));
                }
            });
        }

        @Value.Immutable
        public interface AggViewRuleConfig extends RelRule.Config {
            @Override
            default AggViewRule toRule() {
                return new AggViewRule(this);
            }

            default AggViewRuleConfig withOperandFor() {
                return withOperandSupplier(b0 ->
                        b0.operand(Aggregate.class)
                                .oneInput(b1 -> b1.operand(MycatView.class)
                                        .predicate(m -> m.allowPushdown())
                                        .noInputs()))
                        .withDescription("AggViewRule")
                        .as(AggViewRuleConfig.class);
            }
        }
    }


    public static class SortViewRule extends RelRule<SortViewRule.SortViewRuleConfig> {

        public static final SortViewRuleConfig DEFAULT_CONFIG = ImmutableLocalRules.SortViewRuleConfig.builder().build().withOperandFor();

        public SortViewRule(SortViewRuleConfig config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Sort sort = call.rel(0);
            MycatView input = call.rel(1);
            SQLRBORewriter.sort(input, LocalSort.create(sort, input)).ifPresent(new Consumer<RelNode>() {
                @Override
                public void accept(RelNode res) {
                    call.transformTo(normalize(res));
                }
            });
        }

        @Value.Immutable
        public interface SortViewRuleConfig extends RelRule.Config {
            @Override
            default SortViewRule toRule() {
                return new SortViewRule(this);
            }

            default SortViewRuleConfig withOperandFor() {
                return withOperandSupplier(b0 ->
                        b0.operand(Sort.class)
                                .oneInput(b1 -> b1.operand(MycatView.class)
                                        .predicate(m -> m.allowPushdown())
                                        .noInputs()))
                        .withDescription("SortViewRule")
                        .as(SortViewRuleConfig.class);
            }
        }
    }

    public static class CalcViewRule extends RelRule<CalcViewRule.CalcViewRuleConfig> {

        public static final CalcViewRuleConfig DEFAULT_CONFIG = ImmutableLocalRules.CalcViewRuleConfig.builder().build().withOperandFor();

        public CalcViewRule(CalcViewRuleConfig config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Calc calc = call.rel(0);
            MycatView input = call.rel(1);
            view(input, LocalCalc.create(calc, input)).ifPresent(res -> {
                call.transformTo(normalize(res));
            });
        }

        @Value.Immutable
        public interface CalcViewRuleConfig extends RelRule.Config {
            @Override
            default CalcViewRule toRule() {
                return new CalcViewRule(this);
            }

            default CalcViewRuleConfig withOperandFor() {
                return withOperandSupplier(b0 ->
                        b0.operand(Calc.class)
                                .oneInput(b1 -> b1.operand(MycatView.class)
                                        .predicate(m -> m.allowPushdown()).noInputs()))
                        .withDescription("CalcViewRule")
                        .as(CalcViewRuleConfig.class);
            }
        }
    }

    public static class UnionAllViewRule extends RelRule<UnionAllViewRule.UnionAllViewRuleConfig> {

        public static final UnionAllViewRuleConfig DEFAULT_CONFIG = ImmutableLocalRules.UnionAllViewRuleConfig.builder().build().withOperandFor();

        public UnionAllViewRule(UnionAllViewRuleConfig config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            Union union = call.rel(0);
            MycatView left = call.rel(1);
            MycatView right = call.rel(2);
            List<RelNode> inputs = ImmutableList.of(left, right);
            Optional<RelNode> viewOptional = view(inputs, LogicalUnion.create(inputs, union.all));
            viewOptional.ifPresent(relNode -> call.transformTo(relNode));
        }

        @Value.Immutable
        public interface UnionAllViewRuleConfig extends RelRule.Config {
            @Override
            default UnionAllViewRule toRule() {
                return new UnionAllViewRule(this);
            }

            default UnionAllViewRuleConfig withOperandFor() {
                return withOperandSupplier(b0 ->
                        b0.operand(Union.class).predicate(union -> {
                                    return union.all;
                                })
                                .inputs(b1 -> b1.operand(MycatView.class).predicate(m -> m.allowPushdown()).noInputs(),
                                        b1 -> b1.operand(MycatView.class).predicate(m -> m.allowPushdown()).noInputs()))
                        .withDescription("UnionAllViewRule")
                        .as(UnionAllViewRuleConfig.class);
            }
        }
    }

    @NotNull
    private static ToLogicalConverter getToLogicalConverter(RelNode res) {
        ToLogicalConverter toLogicalConverter = new ToLogicalConverter(MycatCalciteSupport.relBuilderFactory.create(res.getCluster(), null)) {
            @Override
            public RelNode visit(RelNode relNode) {
                if (relNode instanceof MycatView) {
                    return relNode;
                }
                return super.visit(relNode);
            }
        };
        return toLogicalConverter;
    }
}
