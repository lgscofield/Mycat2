package io.mycat.calcite.rules;

import io.mycat.calcite.table.MycatLogicTable;
import io.mycat.calcite.table.MycatTableScan;
import io.mycat.prototypeserver.mysql.VisualTableHandler;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.util.ImmutableBitSet;
import org.immutables.value.Value;

/**
 * I-DEBT-54/D1：恒空仿表子树折叠规则。
 * <p>
 * 当 Project 子树的全部叶子都是静态恒空 VisualTableHandler（scanAll 永远返回 empty），
 * 将整个子树折叠为空 LogicalValues（携带正确 rowType），使投影表达式（含 JSON_EXTRACT）
 * 不再进入 RexToLix codegen。
 * <p>
 * 语义忠实：真 MySQL 在无直方图统计时该查询本就返回 0 行。
 * 严格谓词：子树掺入任何非恒空叶子（真实后端表、含实数据仿表）即不折叠。
 */
@Value.Enclosing
public class EmptyVisualTableSubtreeRule extends RelRule<EmptyVisualTableSubtreeRule.Config> {

    public static final EmptyVisualTableSubtreeRule INSTANCE = Config.DEFAULT.toRule();

    protected EmptyVisualTableSubtreeRule(Config config) {
        super(config);
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
        Project project = call.rel(0);
        return allLeavesAreStaticallyEmptyVisual(project.getInput());
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Project project = call.rel(0);
        LogicalValues emptyValues = LogicalValues.create(
                project.getCluster(), project.getRowType(), com.google.common.collect.ImmutableList.of());
        call.transformTo(emptyValues);
    }

    /**
     * 递归判定：子树所有叶子都是 MycatTableScan 包装的恒空 VisualTableHandler。
     * 遇到非恒空叶子返回 false。
     * <p>
     * HepPlanner 会把每个节点包成 {@link HepRelVertex}，故先拆壳再判定。
     */
    static boolean allLeavesAreStaticallyEmptyVisual(RelNode node) {
        if (node instanceof HepRelVertex) {
            node = ((HepRelVertex) node).getCurrentRel();
        }
        if (node instanceof MycatTableScan) {
            MycatTableScan mts = (MycatTableScan) node;
            return isStaticallyEmptyTableScan(mts.getRelNode());
        }
        // 无分组聚合（grand aggregate，含 ROLLUP/CUBE/GROUPING SETS 的空分组总计行）对
        // 空输入仍恒产出 ≥1 行（COUNT→0、MIN/MAX/SUM→NULL），折叠成空 Values 会丢这 1 行。
        // 带分组键的聚合空输入→0 组→0 行，可安全继续判定。
        if (node instanceof Aggregate) {
            for (ImmutableBitSet group : ((Aggregate) node).getGroupSets()) {
                if (group.isEmpty()) {
                    return false;
                }
            }
        }
        if (node.getInputs().isEmpty()) {
            // 叶子但不是 MycatTableScan → 不是恒空仿表
            return false;
        }
        // 非叶子节点（Filter/Calc/Union 等）：所有子节点必须全叶恒空
        for (RelNode child : node.getInputs()) {
            if (!allLeavesAreStaticallyEmptyVisual(child)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStaticallyEmptyTableScan(RelNode node) {
        if (!(node instanceof TableScan)) return false;
        TableScan ts = (TableScan) node;
        MycatLogicTable mlt = ts.getTable().unwrap(MycatLogicTable.class);
        if (mlt == null) return false;
        if (!(mlt.getTable() instanceof VisualTableHandler)) return false;
        return ((VisualTableHandler) mlt.getTable()).isStaticallyEmpty();
    }

    /** Rule configuration. */
    @Value.Immutable
    public interface Config extends RelRule.Config {
        Config DEFAULT = ImmutableEmptyVisualTableSubtreeRule.Config.builder().build()
                .withOperandSupplier(b ->
                        b.operand(Project.class).anyInputs())
                .withDescription("EmptyVisualTableSubtreeRule")
                .as(Config.class);

        @Override
        default EmptyVisualTableSubtreeRule toRule() {
            return new EmptyVisualTableSubtreeRule(this);
        }
    }
}
