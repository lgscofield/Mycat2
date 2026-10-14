package io.mycat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-41 切片1 · per-query 列类型权威标志（in-process 契约锁）。
 *
 * <p>钉 {@link AsyncMycatDataContextImpl} 上的异构多源权威标志：默认 false（直通/同构单源，
 * carrier 后端忠实轨仍是合法权威，行为零回归）；置位后两缝（列头/解码）须退计划轨一致取列类型。
 * 标志本身只锁"决策一次、两缝同读"的载体语义，形状谓词的置位接线在 Task 2/3 的缝内测试覆盖。
 */
public class ColumnTypeAuthorityTest {

    @Test
    public void defaultIsNotHeterogeneous() {
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl ctx =
                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(null, null, null);
        Assertions.assertFalse(ctx.isMergedHeterogeneousOutput(),
                "新查询默认非异构合并：直通/同构路径不得被权威决策改变（零回归前提）");
    }

    @Test
    public void markThenRead() {
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl ctx =
                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(null, null, null);
        ctx.markMergedHeterogeneousOutput();
        Assertions.assertTrue(ctx.isMergedHeterogeneousOutput(),
                "置位后两缝须能读到同一异构决策");
    }
}
