package io.mycat.calcite;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;

import java.util.List;

/**
 * I-DEBT-41 形状谓词：执行计划里是否存在"异构多源合并"——某个 Union 节点（含 MycatUnion/QueryView/
 * LocalUnion 等子类）各输入的列类型按位不一致。异构时单分支后端轨不是顶层列头的合法权威，两缝须一致
 * 退计划轨（列类型唯一权威决策，发首行前在计划装配点一次定死，避免等异步运行态）。
 *
 * <p>同构合并（identity 各片同型，I-DEBT-34 场景）输入逐位同型 → 不命中，carrier 忠实轨仍是权威，
 * 行为零回归。比较按 {@code SqlTypeName}（类型名级）：够区分 BIT/int 族与 BIGINT 等后端线型族差异；
 * 同族内更细差异（如长度）不构成两缝分叉（两缝读同一轨即一致），不在本谓词职责内。
 */
public final class MycatMergeShape {

    private MycatMergeShape() {
    }

    /** 递归遍历 rel 树；任一 Union 节点输入列类型按位不一致即判异构。null 根（直通计划）→ false。 */
    public static boolean hasHeterogeneousUnion(RelNode root) {
        if (root == null) {
            return false;
        }
        if (root instanceof Union) {
            List<RelNode> inputs = root.getInputs();
            for (int i = 1; i < inputs.size(); i++) {
                if (!sameFieldTypes(inputs.get(0).getRowType(), inputs.get(i).getRowType())) {
                    return true;
                }
            }
        }
        for (RelNode input : root.getInputs()) {
            if (hasHeterogeneousUnion(input)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameFieldTypes(RelDataType a, RelDataType b) {
        List<RelDataTypeField> fa = a.getFieldList();
        List<RelDataTypeField> fb = b.getFieldList();
        if (fa.size() != fb.size()) {
            return false;
        }
        for (int i = 0; i < fa.size(); i++) {
            if (fa.get(i).getType().getSqlTypeName() != fb.get(i).getType().getSqlTypeName()) {
                return false;
            }
        }
        return true;
    }
}
