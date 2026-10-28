package io.mycat.vector;

import com.alibaba.druid.util.JdbcUtils;
import io.mycat.assemble.MycatTest;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@NotThreadSafe
@net.jcip.annotations.NotThreadSafe
public class VectorTest implements MycatTest {
    /**
     * I-DEBT-67 第二刀残留子项验收锁（不能仅凭全表精确 avg=5.0000 就宣布向量腿 DECIMAL 全绿）：
     * <ul>
     *   <li><b>非终止小数</b>：{@code 7/3=2.3333…} 在向量态落到 scale=4 十进制 {@code 2.3333}，
     *       而非泄漏到 double（{@code 2.3333333…}）。</li>
     *   <li><b>group by 带键十进制均值写回</b>：{@code GroupByKeyWithAggPlan} 逐组经本地除产出十进制。</li>
     * </ul>
     * 防下推假绿：表按 {@code mod_hash(id)} 跨 2 dbpartition 分片，全局/逐组 avg 无法单条下推
     * （avg-of-avg 错），explain 实定计划为 {@code MycatProject(CAST(/(CAST(SUM):DECIMAL(19,4),COUNT)):DECIMAL(19,4))}，
     * 后端仅回 {@code SUM(id),COUNT(*)}——除法确在本地向量 project。该 DECIMAL(19,4) project 形态仅第二刀后存在
     * （砍前同形态投影 DOUBLE，值会带长小数尾），故本锁无第二刀必红。
     */
    @Test
    public void testVectorAvgDecimalResiduals() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT)) {
            execute(mycatConnection, RESET_CONFIG);
            execute(mycatConnection, "CREATE DATABASE dbvecdec");
            dropTableIfExists(mycatConnection, "dbvecdec", "vt");
            execute(mycatConnection, "CREATE TABLE dbvecdec.`vt` (\n" +
                    "  `id` bigint NOT NULL,\n" +
                    "  `gid` int DEFAULT NULL,\n" +
                    "  PRIMARY KEY (`id`)\n" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8"
                    + " dbpartition by mod_hash(id) tbpartition by mod_hash(id) tbpartitions 2 dbpartitions 2;");
            deleteData(mycatConnection, "dbvecdec", "vt");
            // gid=1 → {1,2,4} sum=7 count=3 avg=2.3333…(非终止)；gid=2 → {10,31} sum=41 count=2 avg=20.5(终止)。
            // 全表 sum=48 count=5 avg=9.6(终止)。
            execute(mycatConnection, "insert dbvecdec.vt (id,gid) values(1,1)");
            execute(mycatConnection, "insert dbvecdec.vt (id,gid) values(2,1)");
            execute(mycatConnection, "insert dbvecdec.vt (id,gid) values(4,1)");
            execute(mycatConnection, "insert dbvecdec.vt (id,gid) values(10,2)");
            execute(mycatConnection, "insert dbvecdec.vt (id,gid) values(31,2)");

            execute(mycatConnection, "/*+mycat:setVector{1}*/");
            try {
                // 全表：终止小数验证 scale 补零
                List<Map<String, Object>> full = executeQuery(mycatConnection,
                        "SELECT avg(id) FROM `dbvecdec`.`vt` LIMIT 0, 1000; ");
                Assert.assertEquals("[{avg(id)=9.6000}]", full.toString());

                // 带键写回：逐组十进制均值（含非终止 2.3333）；hash 序不定，按 gid 排序后断言
                List<Map<String, Object>> grp = executeQuery(mycatConnection,
                        "SELECT gid, avg(id) FROM `dbvecdec`.`vt` GROUP BY gid LIMIT 0, 1000; ");
                grp.sort((a, b) -> Long.compare(((Number) a.get("gid")).longValue(),
                        ((Number) b.get("gid")).longValue()));
                Assert.assertEquals("[{gid=1, avg(id)=2.3333}, {gid=2, avg(id)=20.5000}]", grp.toString());
            } finally {
                execute(mycatConnection, "/*+mycat:setVector{0}*/");
            }
        }
    }

    @Test
    public void testBase() throws Exception {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT)) {
            execute(mycatConnection, RESET_CONFIG);
            execute(mycatConnection, "CREATE DATABASE db1");

            execute(mycatConnection, "CREATE TABLE db1.`vector` (\n" +
                    "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                    "  `user_id` varchar(100) DEFAULT NULL,\n" +
                    "  `traveldate` date DEFAULT NULL,\n" +
                    "  `fee` decimal(10,0) DEFAULT NULL,\n" +
                    "  `days` int DEFAULT NULL,\n" +
                    "  `blob` longblob,\n" +
                    "  PRIMARY KEY (`id`),\n" +
                    "  KEY `id` (`id`)\n" +
                    ") ENGINE=InnoDB  DEFAULT CHARSET=utf8"
                    + " dbpartition by mod_hash(id) tbpartition by mod_hash(id) tbpartitions 2 dbpartitions 2;");

            deleteData(mycatConnection, "db1", "vector");
            for (int i = 1; i < 10; i++) {
                execute(mycatConnection, "insert db1.vector (id,user_id) values(" + i + "," + i%4+")");
            }
            //test hint
            List<Map<String, Object>> maps = executeQuery(mycatConnection, "/*+mycat:vector() */SELECT id FROM `db1`.`vector` LIMIT 0, 1000; ");

            Assert.assertEquals("[{id=4}, {id=8}, {id=1}, {id=5}, {id=9}, {id=2}, {id=6}, {id=3}, {id=7}]",maps.toString());
            try {
                execute(mycatConnection, "/*+mycat:setVector{1}*/");

                maps = executeQuery(mycatConnection, "SELECT count(*) FROM `db1`.`vector` LIMIT 0, 1000; ");

                Assert.assertEquals("[{count(*)=9}]",maps.toString());

                maps = executeQuery(mycatConnection, "SELECT max(id) FROM `db1`.`vector` LIMIT 0, 1000; ");

                Assert.assertEquals("[{max(id)=9}]",maps.toString());

                maps = executeQuery(mycatConnection, "SELECT min(id) FROM `db1`.`vector` LIMIT 0, 1000; ");

                Assert.assertEquals("[{min(id)=1}]",maps.toString());

                maps = executeQuery(mycatConnection, "SELECT sum(id) FROM `db1`.`vector` LIMIT 0, 1000; ");

                Assert.assertEquals("[{sum(id)=45}]",maps.toString());

                maps = executeQuery(mycatConnection, "SELECT avg(id) FROM `db1`.`vector` LIMIT 0, 1000; ");

                // MYCAT CHANGE (I-DEBT-39·tranche②): deriveAvgAggType 精确数值改判 DECIMAL，AVG(bigint)
                // 对齐 MySQL = DECIMAL(scale=div_precision_increment=4)，id 1..9 avg=5 → 5.0000。
                // ⚠ 本行处于 `/*+mycat:setVector{1}*/` 态，走 ordinate 向量化执行器（非 calcite codegen 腿）：
                // AvgAggregateFunction 系 DoubleAccumulator（executor/.../aggregate/avg/AvgAggregateFunction.java:27,65-70,85-97，
                // cairo MapValue 仅 LONG/DOUBLE 槽，无 DECIMAL 累加器 infra），sum=45.0/count=9 → 现抛出 "5.0"。
                // 真库口径=DECIMAL scale4 应 5.0000，此为**未修的产品缺口红锁**（宁挂账不伪绿，绝不降级断言迁就现状）。
                // DECIMAL 保真属 executor 表征链专轮 I-DEBT-67（待接）；引擎双轨现状锤钉于 AvgDecimalFidelityGapTest（knownGap 锚锁）。
                Assert.assertEquals("[{avg(id)=5.0000}]",maps.toString());

                maps = executeQuery(mycatConnection, "SELECT user_id FROM `db1`.`vector` group by user_id LIMIT 0, 1000; ");

                Assert.assertEquals("[{user_id=0}, {user_id=1}, {user_id=2}, {user_id=3}]",maps.toString());

                maps = executeQuery(mycatConnection, "SELECT max(id) FROM `db1`.`vector` group by user_id LIMIT 0, 1000; ");

                Assert.assertEquals("[{max(id)=8}, {max(id)=9}, {max(id)=6}, {max(id)=7}]",maps.toString());

                System.out.println();
            } finally {
                execute(mycatConnection, "/*+mycat:setVector{0}*/");
            }
        }
    }

}
