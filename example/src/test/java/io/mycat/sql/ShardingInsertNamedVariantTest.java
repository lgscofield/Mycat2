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

/**
 * I-DEBT-48 回归锁：向分片表 INSERT 一个"具名 variant"（MySQL 用户变量 {@code @var}）
 * 作为非分片列的值时，Mycat 分片写路径不得硬崩。
 *
 * <p>根因：{@code io.mycat.vertx.VertxExecuter.compute} 对 {@code SQLVariantRefExpr}
 * 直取 {@code params.get(index)}，而 {@code getIndex()} 对具名/无位置 variant 返回 -1
 * （无 {@code index<0} 守卫）→ {@code IndexOutOfBoundsException: Index -1}。
 * Connector/NET 把可空列参数化为 {@code values (4,@NULL)} 时命中此崩点。
 *
 * <p>期望（对齐真 MySQL 语义）：{@code @NULL} 为未定义用户变量，引用它取到 NULL；
 * 分片键 id=100 为字面量可解析，行应成功落库且 user_id 为 NULL。
 */
@NotThreadSafe
@net.jcip.annotations.NotThreadSafe
public class ShardingInsertNamedVariantTest implements MycatTest {

    @Test
    public void insertNamedUserVariableOnNonShardingColumnShouldNotCrash() throws Exception {
        initShardingTable();
        try (Connection mycat = getMySQLConnection(DB_MYCAT)) {
            // 具名 variant（用户变量 @NULL）落在非分片列 user_id 上；分片键 id 为字面量。
            // 修前：execute 抛 SQLException（服务端 IndexOutOfBoundsException: Index -1）。
            execute(mycat, "insert into db1.sharding (id, user_id) values (100, @NULL)");

            List<Map<String, Object>> rows =
                    executeQuery(mycat, "select id, user_id from db1.sharding where id = 100");
            Assert.assertEquals(1, rows.size());
            Assert.assertEquals(100L, ((Number) rows.get(0).get("id")).longValue());
            Assert.assertNull("未定义用户变量 @NULL 应写入 NULL（MySQL 语义）",
                    rows.get(0).get("user_id"));
        }
    }

    private void initShardingTable() throws Exception {
        Connection mycatConnection = getMySQLConnection(DB_MYCAT);
        Connection db1 = getMySQLConnection(DB1);
        Connection db2 = getMySQLConnection(DB2);
        execute(db1, "DROP TABLE if exists db1_0.sharding_0");
        execute(db1, "DROP TABLE if exists db1_0.sharding_1");
        execute(db2, "DROP TABLE if exists db1_1.sharding_2");
        execute(db2, "DROP TABLE if exists db1_1.sharding_3");

        execute(mycatConnection, RESET_CONFIG);
        execute(mycatConnection, "DROP DATABASE db1");
        execute(mycatConnection, "CREATE DATABASE db1");
        execute(mycatConnection, CreateDataSourceHint.create("ds0", DB1));
        execute(mycatConnection, CreateDataSourceHint.create("ds1", DB2));
        execute(mycatConnection, CreateClusterHint.create("c0", Arrays.asList("ds0"), Collections.emptyList()));
        execute(mycatConnection, CreateClusterHint.create("c1", Arrays.asList("ds1"), Collections.emptyList()));
        execute(mycatConnection, "USE `db1`;");
        execute(mycatConnection, "CREATE TABLE db1.`sharding` (\n" +
                "  `id` bigint NOT NULL AUTO_INCREMENT,\n" +
                "  `user_id` varchar(100) DEFAULT NULL,\n" +
                "  `traveldate` date DEFAULT NULL,\n" +
                "  `fee` decimal(10,0) DEFAULT NULL,\n" +
                "  `days` int DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`),\n" +
                "  KEY `id` (`id`)\n" +
                ") ENGINE=InnoDB  DEFAULT CHARSET=utf8"
                + " dbpartition by mod_hash(id) tbpartition by mod_hash(id) tbpartitions 2 dbpartitions 2;");
        execute(mycatConnection, "delete from db1.sharding");

        mycatConnection.close();
        db1.close();
        db2.close();
    }
}
