package io.mycat.router.mycat1xfunction;

import io.mycat.MycatException;
import io.mycat.router.ShardingTableHandler;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * I-DEBT-28 回归锁：PartitionByRangeMod 的经典 MyCat PartRangeMod 契约。
 *
 * <p>{@code ranges} map 的 value 语义是"该值区间跨越的节点数(groupSize)"，<b>不是</b>目标节点下标
 * （GroupSizeRange.getGroupSizeRange 里局部变量名 {@code nodeId} 具误导性，实际落入 groupSize 字段）。
 * 计算结果 = 命中区间之前各区间 groupSize 的累加值 + (列值 % 命中区间 groupSize)。
 * 因此一张分片表所需 dataNode 数 = Σ groupSize；当 Σ groupSize 大于实际配置节点数时，
 * {@link io.mycat.router.Mycat1xSingleValueRuleFunction#calculate(String)} 越界抛
 * {@link MycatException}——这是对自相矛盾配置的<b>正确拒绝</b>，而非算法缺陷。
 *
 * <p>本类同时锁定越界异常须回显真实分片值（{@code Mycat1xSingleValueRuleFunction} 中
 * MessageFormat 占位符由错位的 {@code {4}} 纠正为 {@code {2}}，此前越界信息从不打印分片值）。
 */
public class PartitionByRangeModContractTest {

    private static Map<String, Object> props() {
        Map<String, Object> prot = new HashMap<>();
        prot.put("defaultNode", -1);
        prot.put("columnName", "id");
        return prot;
    }

    /** ΣgroupSize=1+1=2 → 适配两节点，id=1 落 node0、id=2/3 落 node1，calculate 命中真实节点不抛。 */
    @Test
    public void twoNodeConfigRoutesWithinBounds() {
        PartitionByRangeMod m = new PartitionByRangeMod();
        ShardingTableHandler table2 = TableHandlerMocks.mockTableHandlerWithDataNodes(2);
        Map<String, Object> ranges = new LinkedHashMap<>();
        ranges.put("0-1", 1);
        ranges.put("1-300", 1);
        m.callInit(table2, props(), ranges);

        Assert.assertEquals(0, m.calculateIndex("1"));
        Assert.assertEquals(1, m.calculateIndex("2"));
        Assert.assertEquals(1, m.calculateIndex("3"));
        Assert.assertEquals("0", m.calculate("1").getTable());
        Assert.assertEquals("1", m.calculate("2").getTable());
        Assert.assertEquals("1", m.calculate("3").getTable());
    }

    /** 钉死 groupSize 累加+取模语义：前序区间 groupSize 之和作为节点基址。 */
    @Test
    public void groupSizeSemanticsIsCumulativeModPartition() {
        PartitionByRangeMod m = new PartitionByRangeMod();
        ShardingTableHandler table5 = TableHandlerMocks.mockTableHandlerWithDataNodes(5);
        Map<String, Object> ranges = new LinkedHashMap<>();
        ranges.put("1-10", 2);   // 跨 node0,1
        ranges.put("11-20", 3);  // 跨 node2,3,4
        m.callInit(table5, props(), ranges);

        Assert.assertEquals(1, m.calculateIndex("5"));   // 0 + 5%2
        Assert.assertEquals(0, m.calculateIndex("6"));   // 0 + 6%2
        Assert.assertEquals(2, m.calculateIndex("15"));  // 2 + 15%3
        Assert.assertEquals(3, m.calculateIndex("16"));  // 2 + 16%3
        // 超出所有区间且 defaultNode<0 → -1（上层转全表扫描，非越界）
        Assert.assertEquals(-1, m.calculateIndex("21"));
        Assert.assertNull(m.calculate("21"));
    }

    /**
     * 复现原 testSharding 缺陷：声明 ΣgroupSize=1+2=3 却只配 2 节点 → id=3 算得 node2 越界。
     * 同配置给足 3 节点则不越界，证明根因是"配置矛盾"而非"算法 bug"。并锁定越界信息回显分片值。
     */
    @Test
    public void underProvisionedNodesThrowsWithShardingValue() {
        Map<String, Object> ranges = new LinkedHashMap<>();
        ranges.put("0-1", 1);
        ranges.put("1-300", 2);

        PartitionByRangeMod m = new PartitionByRangeMod();
        ShardingTableHandler table2 = TableHandlerMocks.mockTableHandlerWithDataNodes(2);
        m.callInit(table2, props(), ranges);
        Assert.assertEquals(0, m.calculateIndex("1"));
        Assert.assertEquals(1, m.calculateIndex("2"));
        Assert.assertEquals(2, m.calculateIndex("3")); // 需第 3 个节点
        try {
            m.calculate("3");
            Assert.fail("越界配置应抛 MycatException");
        } catch (MycatException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("分片算法越界"));
            // MessageFormat 修复验证：必须回显真实分片值 3（曾因 {4} 占位符错位从不打印）
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("分片值:3"));
        }

        PartitionByRangeMod m3 = new PartitionByRangeMod();
        ShardingTableHandler table3 = TableHandlerMocks.mockTableHandlerWithDataNodes(3);
        m3.callInit(table3, props(), new LinkedHashMap<>(ranges));
        Assert.assertEquals("2", m3.calculate("3").getTable());
    }
}
