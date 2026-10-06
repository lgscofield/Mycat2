package io.mycat.sql;

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
public class TableAnalyseTest implements MycatTest {

    @Test
    public void testSameShardingRule() throws Exception {
        try(Connection mycatConnection = getMySQLConnection(DB_MYCAT)){
            execute(mycatConnection, RESET_CONFIG);
            Connection mysql3306 = getMySQLConnection(DB1);

            execute(mycatConnection, "DROP DATABASE db1");


            execute(mycatConnection, "CREATE DATABASE db1");
            execute(mycatConnection, "CREATE DATABASE db1");


            execute(mycatConnection, CreateDataSourceHint
                    .create("ds0",
                            DB1));
            execute(mycatConnection, CreateDataSourceHint
                    .create("ds1",
                            DB2));

            execute(mycatConnection,
                    CreateClusterHint.create("c0",
                            Arrays.asList("ds0"), Collections.emptyList()));
            execute(mycatConnection,
                    CreateClusterHint.create("c1",
                            Arrays.asList("ds1"), Collections.emptyList()));

            execute(mycatConnection, "USE `db1`;");
            execute(mysql3306, "USE `db1`;");

            execute(mycatConnection, "CREATE TABLE db1.`travelrecord` (\n" +
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
            execute(mycatConnection, "CREATE TABLE db1.`travelrecord2` (\n" +
                    "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                    "  `user_id` varchar(100) DEFAULT NULL,\n" +
                    "  `traveldate` date DEFAULT NULL,\n" +
                    "  `fee` decimal(10,0) DEFAULT NULL,\n" +
                    "  `days` int DEFAULT NULL,\n" +
                    "  `blob` longblob,\n" +
                    "  PRIMARY KEY (`id`),\n" +
                    "  KEY `id` (`id`)\n" +
                    ") ENGINE=InnoDB  DEFAULT CHARSET=utf8"
                    + " dbpartition by mod_hash(user_id) tbpartition by mod_hash(user_id) tbpartitions 2 dbpartitions 2;");

            // 走 helper 绕开 Connector/J 对纯 hint 串的 executeQuery 方向镜像守卫（同 I-DEBT-7A-2）；
            // 该单类运行受 Root B 池耗尽阻塞、本轮 sweep 排除，运行时全绿证据待 I-DEBT-7B 重基线。
            List<Map<String, Object>> maps = executeQuery(mycatConnection, "/*+ mycat:showErGroup{}*/");
            Assert.assertEquals(2,maps.size() );
            Assert.assertEquals("[{groupId=0, schemaName=db1, tableName=travelrecord}, {groupId=0, schemaName=db1, tableName=travelrecord2}]",maps.toString() );
        }
    }

}
