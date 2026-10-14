package io.mycat.calcite;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.core.Window;

import java.util.List;

/**
 * I-DEBT-41 形状谓词：执行计划顶层是否存在<b>多源 Union 合并</b>（MycatUnion/QueryView/LocalUnion 等
 * {@code Union} 族节点且输入 &gt; 1）。命中时单分支后端轨不是顶层列头的合法权威，两缝须一致退计划轨
 * （列类型唯一权威决策，发首行前在计划装配点一次定死，避免等异步运行态）。
 *
 * <p>为何不比对输入列类型：探针实测（2026-10-14 e2e 取证）bit(8)∪bigint 在 Calcite rowType 已全部
 * 坍缩为 BIGINT，{@code SqlTypeName} 层看不见 wire 族差异；而崩溃真因是 Union 分支拼接处的 codegen
 * 裸转 `(Long) row[0]`（忠实轨 byte[] 必炸）——存在多源 Union 即有 typed consumer 风险，按结构判最保守。
 *
 * <p>同构 identity 分片归并不在风暴面：其形态为单 {@code MycatView}（内部 mergeSort，键取用
 * {@code SqlFunctions.toLong} 安转，无 Union 节点）→ 不命中，carrier 忠实轨仍是权威，I-DEBT-34 零回归。
 * 同构多源 union（各分支 wire 类型全同）会被本谓词保守降级为计划轨（列头失忠实、值为数值渲染），
 * 属切片2 ① 后端为准收复的 persona 差距，由测试矩阵终裁并记入对拍差异表。
 */
public final class MycatMergeShape {

    private MycatMergeShape() {
    }

    /** 递归遍历 rel 树；存在输入 &gt; 1 的 Union 族节点即置位。null 根（直通计划）→ false。 */
    public static boolean hasHeterogeneousUnion(RelNode root) {
        if (root == null) {
            return false;
        }
        if (root instanceof Union && root.getInputs().size() > 1) {
            return true;
        }
        for (RelNode input : root.getInputs()) {
            if (hasHeterogeneousUnion(input)) {
                return true;
            }
        }
        return false;
    }

    /**
     * I-DEBT-41 Review Focus#2（lookup/join 形态，Task 6 活靶实证）：计划内存在<b>内存重消费</b>算子
     * （Join/Aggregate/Window/Correlate/SetOp 族）时，扫描行将喂给 codegen 生成的 typed consumer
     * （distinct/比较键按 Calcite 坍缩类型裸转 (Long) 等），后端忠实轨的 bit byte[] 必炸——解码缝
     * 无条件退计划轨。与 Union 合并同源一条规则：“内部重消费→计划轨”；纯扫描直通（无任何此类算子，
     * 含同构分片单 MycatView 归并）不命中，I-DEBT-34 忠实轨零回归。
     */
    public static boolean hasInternalReconsumingOperator(RelNode root) {
        if (root == null) {
            return false;
        }
        if (root instanceof Join || root instanceof Aggregate || root instanceof Window
                || root instanceof Correlate || root instanceof SetOp) {
            return true;
        }
        for (RelNode input : root.getInputs()) {
            if (hasInternalReconsumingOperator(input)) {
                return true;
            }
        }
        return false;
    }

    /**
     * I-DEBT-41 切片2 ①：顶层 Union 分支数（describe 探测完整性门槛）。仅接受两种保守形态：
     * 根即 Union，或根单一子节点为 Union（MemSort 等顶层包装不改列形状）。其余形态返回 -1
     * → 不探测回 ③（嵌套 Union 等复杂树分支计数不可靠，宁保守）。
     */
    public static int topLevelUnionBranchCount(RelNode root) {
        if (root == null) {
            return -1;
        }
        if (root instanceof Union && root.getInputs().size() > 1) {
            return root.getInputs().size();
        }
        if (root.getInputs().size() == 1) {
            RelNode child = root.getInputs().get(0);
            if (child instanceof Union && child.getInputs().size() > 1) {
                return child.getInputs().size();
            }
        }
        return -1;
    }
}
