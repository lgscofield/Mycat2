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
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite.metadata;

import com.google.common.collect.ImmutableList;
import io.mycat.calcite.logical.MycatView;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.metadata.BuiltInMetadata;
import org.apache.calcite.rel.metadata.ChainedRelMetadataProvider;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.MetadataHandler;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

import java.util.Arrays;

/**
 * I-DEBT-3 续：把原 hbt 影子 {@code RelMdCollation} 里对 {@link MycatView} 的 collation 供出规则，
 * 归位到 fork 已有的元数据扩展点（provider 链），从而删除影子、让 fork 1.42 版本胜出而不丢 Mycat 语义。
 *
 * <p>
 * fork 的 {@code RelMdCollation} 不认识 {@link MycatView}（其 catch-all 对任意未知 RelNode 返回
 * {@code null}），因此影子删除后必须在此 provider 补回 MycatView 的有序性供出，否则 merge-sort 视图
 * 会退化为"未知 collation"。规则逐字复刻影子：
 * <ol>
 *   <li>merge-sort 视图：取底层 {@link Sort} 的 collation；</li>
 *   <li>否则：取视图 traitSet 上的 collation；</li>
 *   <li>都没有：返回空列表（表示"确定无序"，而非 {@code null} 的"未知"）。</li>
 * </ol>
 *
 * <p>
 * 该 handler 只声明 {@code collations(MycatView, mq)}，{@link ReflectiveRelMetadataProvider} 沿类继承链
 * 上溯查找时，对非 {@link MycatView} 的 RelNode 一律返回 {@code null}，故挂到 {@link DefaultRelMetadataProvider}
 * 之前不会干扰 fork 对其它节点的既有行为。
 */
public final class MycatRelMetadataProvider {

    private MycatRelMetadataProvider() {
    }

    /** MycatView 的 collation 供出 handler（Task 6）。 */
    public static final RelMetadataProvider COLLATION_SOURCE =
            ReflectiveRelMetadataProvider.reflectiveSource(
                    new MycatViewCollationHandler(), BuiltInMetadata.Collation.Handler.class);

    /** 本包所有 Mycat 自有元数据源的链（Task 7 会继续追加 UniqueKeys 源）。 */
    public static final RelMetadataProvider INSTANCE =
            ChainedRelMetadataProvider.of(ImmutableList.of(COLLATION_SOURCE));

    /**
     * Mycat 源置于 fork 默认链之前的完整 provider。接线处（{@code DrdsSqlCompiler} 的 newCluster /
     * registerMetadataProviders / Programs.of）用它替换裸 {@link DefaultRelMetadataProvider#INSTANCE}，
     * 即可在不动 fork 源码的前提下让 MycatView 元数据经 provider 派发供出。
     */
    public static final RelMetadataProvider WITH_DEFAULT =
            ChainedRelMetadataProvider.of(Arrays.asList(INSTANCE, DefaultRelMetadataProvider.INSTANCE));

    /**
     * {@link MycatView} collation 供出规则的执行体，经反射按 RelNode 运行时类型派发。
     */
    public static class MycatViewCollationHandler
            implements MetadataHandler<BuiltInMetadata.Collation> {

        @Override
        public org.apache.calcite.rel.metadata.MetadataDef<BuiltInMetadata.Collation> getDef() {
            return BuiltInMetadata.Collation.DEF;
        }

        /**
         * 复刻影子 {@code RelMdCollation.collations(RelNode, mq)} 的 MycatView 分支。
         */
        public ImmutableList<RelCollation> collations(MycatView view, RelMetadataQuery mq) {
            RelCollation collation;
            if (view.isMergeSort()) {
                Sort sort = (Sort) view.getRelNode();
                collation = sort.getCollation();
                if (collation != null) {
                    return ImmutableList.of(collation);
                }
            }
            collation = view.getTraitSet().getCollation();
            if (collation != null) {
                return ImmutableList.of(collation);
            }
            return ImmutableList.of();
        }
    }
}
