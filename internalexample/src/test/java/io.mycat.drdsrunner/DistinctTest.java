package io.mycat.drdsrunner;


import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
@net.jcip.annotations.NotThreadSafe
public class DistinctTest extends DrdsTest {

    @BeforeClass
    public static void beforeClass() {
        DrdsTest.drdsRunner = null;
        DrdsTest.metadataManager = null;
    }


    @Test
    public void testSelectDistinctStar() throws Exception {
        Explain explain = parse("SELECT DISTINCT *  from db1.distinct_sharding");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`id`, `distinct_sharding`.`user_id`, `distinct_sharding`.`traveldate`, `distinct_sharding`.`fee`, `distinct_sharding`.`days`, `distinct_sharding`.`blob` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`, `distinct_sharding`.`user_id`, `distinct_sharding`.`traveldate`, `distinct_sharding`.`fee`, `distinct_sharding`.`days`, `distinct_sharding`.`blob` union all SELECT `distinct_sharding`.`id`, `distinct_sharding`.`user_id`, `distinct_sharding`.`traveldate`, `distinct_sharding`.`fee`, `distinct_sharding`.`days`, `distinct_sharding`.`blob` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`, `distinct_sharding`.`user_id`, `distinct_sharding`.`traveldate`, `distinct_sharding`.`fee`, `distinct_sharding`.`days`, `distinct_sharding`.`blob`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`id`, `distinct_sharding`.`user_id`, `distinct_sharding`.`traveldate`, `distinct_sharding`.`fee`, `distinct_sharding`.`days`, `distinct_sharding`.`blob` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`, `distinct_sharding`.`user_id`, `distinct_sharding`.`traveldate`, `distinct_sharding`.`fee`, `distinct_sharding`.`days`, `distinct_sharding`.`blob` union all SELECT `distinct_sharding`.`id`, `distinct_sharding`.`user_id`, `distinct_sharding`.`traveldate`, `distinct_sharding`.`fee`, `distinct_sharding`.`days`, `distinct_sharding`.`blob` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`, `distinct_sharding`.`user_id`, `distinct_sharding`.`traveldate`, `distinct_sharding`.`fee`, `distinct_sharding`.`days`, `distinct_sharding`.`blob`)]",explain.specificSql().toString());
        Assert.assertEquals("MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }
    @Test
    public void testSelectDistinctShardingKeyButNoPrimaryKey() throws Exception {
        Explain explain = parse("SELECT DISTINCT id  from db1.distinct_sharding");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`id` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id` union all SELECT `distinct_sharding`.`id` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`id` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id` union all SELECT `distinct_sharding`.`id` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`)]",explain.specificSql().toString());
        Assert.assertEquals("MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }

    @Test
    public void testSelectDistinctNoShardingKeyButNoPrimaryKey() throws Exception {
        Explain explain = parse("SELECT DISTINCT traveldate  from db1.distinct_sharding");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`traveldate` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`traveldate` union all SELECT `distinct_sharding`.`traveldate` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`traveldate`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`traveldate` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`traveldate` union all SELECT `distinct_sharding`.`traveldate` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`traveldate`)]",explain.specificSql().toString());
        Assert.assertEquals("MycatHashAggregate(group=[{0}])   MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }
    @Test
    public void testSelectDistinctPrimaryKeyButNoShardingKey() throws Exception {
        Explain explain = parse("SELECT DISTINCT id  from db1.distinct_sharding");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`id` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id` union all SELECT `distinct_sharding`.`id` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`id` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id` union all SELECT `distinct_sharding`.`id` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`)]",explain.specificSql().toString());
        Assert.assertEquals("MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }

    @Test
    public void testSelectDistinctPrimaryKeyAndShardingKey() throws Exception {
        Explain explain = parse("SELECT DISTINCT id  from db1.sharding");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `sharding`.`id` FROM db1_0.sharding_0 AS `sharding` union all SELECT `sharding`.`id` FROM db1_0.sharding_1 AS `sharding`)\n" +
                "Each(targetName=c1, sql=SELECT `sharding`.`id` FROM db1_1.sharding_0 AS `sharding` union all SELECT `sharding`.`id` FROM db1_1.sharding_1 AS `sharding`)]",explain.specificSql().toString());
        Assert.assertEquals("MycatView(distribution=[[db1.sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }

    @Test
    public void testSelectDistinctPrimaryKeyAndShardingKey2() throws Exception {
        Explain explain = parse("SELECT DISTINCT user_id  from db1.distinct_sharding");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`user_id` FROM db1_0.sharding_0 AS `distinct_sharding` union all SELECT `distinct_sharding`.`user_id` FROM db1_0.sharding_1 AS `distinct_sharding`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`user_id` FROM db1_1.sharding_0 AS `distinct_sharding` union all SELECT `distinct_sharding`.`user_id` FROM db1_1.sharding_1 AS `distinct_sharding`)]",explain.specificSql().toString());
        Assert.assertEquals("MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }

    @Test
    public void testGroupByPrimaryKey() throws Exception {
        Explain explain = parse("SELECT user_id  from db1.distinct_sharding group by user_id");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`user_id` FROM db1_0.sharding_0 AS `distinct_sharding` union all SELECT `distinct_sharding`.`user_id` FROM db1_0.sharding_1 AS `distinct_sharding`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`user_id` FROM db1_1.sharding_0 AS `distinct_sharding` union all SELECT `distinct_sharding`.`user_id` FROM db1_1.sharding_1 AS `distinct_sharding`)]",explain.specificSql().toString());
        Assert.assertEquals("MycatView(distribution=[[db1.distinct_sharding]])".trim(),explain.dumpPlan().toString());
        System.out.println();
    }
    @Test
    public void testGroupBy2Key() throws Exception {
        Explain explain = parse("SELECT user_id,id  from db1.distinct_sharding group by user_id,id");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`user_id`, `distinct_sharding`.`id` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`user_id`, `distinct_sharding`.`id` union all SELECT `distinct_sharding`.`user_id`, `distinct_sharding`.`id` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`user_id`, `distinct_sharding`.`id`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`user_id`, `distinct_sharding`.`id` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`user_id`, `distinct_sharding`.`id` union all SELECT `distinct_sharding`.`user_id`, `distinct_sharding`.`id` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`user_id`, `distinct_sharding`.`id`)]",explain.specificSql().toString());
        Assert.assertEquals("MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }
    @Test
    public void testCountNoShardingKey() throws Exception {
        Explain explain = parse("SELECT count(*)  from db1.distinct_sharding group by user_id");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`user_id`, COUNT(*) AS `count(*)` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`user_id` union all SELECT `distinct_sharding`.`user_id`, COUNT(*) AS `count(*)` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`user_id`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`user_id`, COUNT(*) AS `count(*)` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`user_id` union all SELECT `distinct_sharding`.`user_id`, COUNT(*) AS `count(*)` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`user_id`)]",explain.specificSql().toString());

        Assert.assertEquals("MycatProject(count(*)=[$1])   MycatHashAggregate(group=[{0}], count(*)=[$SUM0($1)])     MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }
    @Test
    public void testAvgNoShardingKey() throws Exception {
        Explain explain = parse("SELECT avg(fee)  from db1.distinct_sharding group by fee");

        // MYCAT CHANGE (I-DEBT-7C 再基线): 规划器演化后 group 键即分区列，SUM/COUNT 可由上层对
        // 去重组精确计算，下推串不再携带 (COALESCE(SUM..),0)/COUNT 预聚合列（旧形态属冗余投影）。
        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`fee` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`fee` union all SELECT `distinct_sharding`.`fee` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`fee`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`fee` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`fee` union all SELECT `distinct_sharding`.`fee` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`fee`)]",explain.specificSql().toString());

        // MYCAT CHANGE (I-DEBT-7C 再基线): 新计划形态——上层无聚合、后端组去重后渲染；经活库对拍
        // 探针（同组 fee 跨 ds0/ds1 分布，Mycat 恰返 2 行且值精确）实证非重复/非丢行，可安全钉新形态。
        // MYCAT CHANGE (I-DEBT-39·tranche②): deriveAvgAggType 精确数值改判 DECIMAL（对齐 MySQL AVG(decimal)=DECIMAL）。
        // fee=decimal(10,0)→scale 0+div_precision_increment(4)=4，最大 DECIMAL 精度 19（MycatTypeSystem 委托 DEFAULT）。
        // 计划外层 CAST 型随之由 DOUBLE→DECIMAL(19, 4)（与 CalciteValidatorGolden #10 avg(salary DECIMAL(10,2))→DECIMAL(19,6) 同公式）。
        Assert.assertEquals("MycatProject(avg(fee)=[CAST($0):DECIMAL(19, 4)])   MycatHashAggregate(group=[{0}])     MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }
    @Test
    public void testMaxNoShardingKey() throws Exception {
        Explain explain = parse("SELECT max(fee)  from db1.distinct_sharding group by fee");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`fee`, MAX(`distinct_sharding`.`fee`) AS `max(fee)` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`fee` union all SELECT `distinct_sharding`.`fee`, MAX(`distinct_sharding`.`fee`) AS `max(fee)` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`fee`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`fee`, MAX(`distinct_sharding`.`fee`) AS `max(fee)` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`fee` union all SELECT `distinct_sharding`.`fee`, MAX(`distinct_sharding`.`fee`) AS `max(fee)` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`fee`)]",explain.specificSql().toString());

        Assert.assertEquals("MycatProject(max(fee)=[$1])   MycatHashAggregate(group=[{0}], max(fee)=[MAX($1)])     MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }
    @Test
    public void testCountShardingKey() throws Exception {
        Explain explain = parse("SELECT count(*)  from db1.distinct_sharding group by id");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT COUNT(*) AS `count(*)` FROM db1_0.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id` union all SELECT COUNT(*) AS `count(*)` FROM db1_0.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`)\n" +
                "Each(targetName=c1, sql=SELECT COUNT(*) AS `count(*)` FROM db1_1.sharding_0 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id` union all SELECT COUNT(*) AS `count(*)` FROM db1_1.sharding_1 AS `distinct_sharding` GROUP BY `distinct_sharding`.`id`)]",explain.specificSql().toString());

        Assert.assertEquals("MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }
    @Test
    public void testDistinctUniqueKey() throws Exception {
        Explain explain = parse("SELECT days  from db1.distinct_sharding group by days");

        Assert.assertEquals("[Each(targetName=c0, sql=SELECT `distinct_sharding`.`days` FROM db1_0.sharding_0 AS `distinct_sharding` union all SELECT `distinct_sharding`.`days` FROM db1_0.sharding_1 AS `distinct_sharding`)\n" +
                "Each(targetName=c1, sql=SELECT `distinct_sharding`.`days` FROM db1_1.sharding_0 AS `distinct_sharding` union all SELECT `distinct_sharding`.`days` FROM db1_1.sharding_1 AS `distinct_sharding`)]",explain.specificSql().toString());

        Assert.assertEquals("MycatView(distribution=[[db1.distinct_sharding]])",explain.dumpPlan().toString());
        System.out.println();
    }


}
