package io.mycat.client;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.util.JdbcUtils;
import com.zaxxer.hikari.HikariDataSource;
import io.mycat.DrdsSqlWithParams;
import io.mycat.assemble.MycatTest;
import io.mycat.calcite.DrdsRunnerHelper;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLPool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ClientTest implements MycatTest {
    @Test
    public void testDruid() throws Exception {
        try (DruidDataSource ds = new DruidDataSource()) {
            ds.setUrl(DB_MYCAT);
            ds.setUsername("root");
            ds.setPassword("123456");

            List<Map<String, Object>> maps = JdbcUtils.executeQuery(ds, "select 1");
            Connection connection = ds.getConnection();
            int transactionIsolation = connection.getTransactionIsolation();
            System.out.println();
        }
    }

    /**
     * I-DEBT-29 离线面（不需 Docker/8066）：钉死 {@code @@global.x} reprint 后仍可再解析。
     * 与 hbt {@code VariantReprintFidelityTest} 同根，此处锁 example 侧原 {@code testDBeaver}
     * 崩溃点——修复前离线断言即抛 {@code ParserException: token VARIANT}，从未走到活体序列。
     */
    @Test
    public void testDBeaverMetadataReprintFidelity() throws Exception {
        DrdsSqlWithParams drdsSqlWithParams =
                DrdsRunnerHelper.preParse("SELECT @@global.character_set_server, @@global.collation_server", null);
        // 主断言：reprint 出的参数化文本必须能二次解析（原崩溃的那一步）。
        drdsSqlWithParams.getParameterizedStatement();
        // 字面锁：分隔逗号必须逐字符还原。
        Assert.assertEquals("SELECT @@global.character_set_server, @@global.collation_server",
                drdsSqlWithParams.getParameterizedSQL());
    }

    /**
     * I-DEBT-29 活体面（需 8066 persona=MYSQL_5_7）：跑 DBeaver 元数据 12 条真实序列。
     * 对招牌那条 {@code SELECT @@GLOBAL.character_set_server,@@GLOBAL.collation_server}
     * 由哑断言升级为值断言——必须 1 行 2 列且两列非空（卖出"确实取回元数据"这门灯）。
     */
    @Test
    public void testDBeaverMetadataSequenceLive() throws Exception {
        try (Connection mySQLConnection = getMySQLConnection(DB_MYCAT)) {
            execute(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Main */ SET autocommit=1");
            executeQuery(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SELECT DATABASE()");
            executeQuery(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW ENGINES");
            executeQuery(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW CHARSET");
            executeQuery(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW COLLATION");
            // 招牌查询：由哑断言补成值断言。
            List<Map<String, Object>> charsetCollation = executeQuery(mySQLConnection,
                    "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SELECT @@GLOBAL.character_set_server,@@GLOBAL.collation_server");
            Assert.assertEquals("全局 charset/collation 查询应恰好 1 行", 1, charsetCollation.size());
            Map<String, Object> row = charsetCollation.get(0);
            Assert.assertEquals("应恰好 2 列", 2, row.size());
            List<Object> values = new ArrayList<>(row.values());
            Assert.assertNotNull("第 1 列（character_set_server）不得为 null", values.get(0));
            Assert.assertNotNull("第 2 列（collation_server）不得为 null", values.get(1));
            executeQuery(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW PLUGINS");
            executeQuery(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SHOW VARIABLES LIKE 'lower_case_table_names'");
            executeQuery(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ show databases");
            executeQuery(mySQLConnection, "/* ApplicationName=DBeaver 21.2.5 - Metadata */ SELECT * FROM information_schema.TABLES t\n" +
                    "WHERE\n" +
                    "\tt.TABLE_SCHEMA = 'information_schema'\n" +
                    "\tAND t.TABLE_NAME = 'CHECK_CONSTRAINTS'");
        }
    }

    @Test
    public void tesVertx() throws Exception {
        MySQLConnectOptions connectOptions = new MySQLConnectOptions()
                .setPort(8066)
                .setHost("localhost")
                .setDatabase("mysql")
                .setUser("root")
                .setPassword("123456");

// Pool options
        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(1);

// Create the client pool
        MySQLPool client = MySQLPool.pool(connectOptions, poolOptions);

// A simple query
        client
                .query("SELECT  1").execute().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        client.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    public void tesHikariCP() throws Exception {
        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl(DB_MYCAT);
            ds.setUsername("root");
            ds.setPassword("123456");

            List<Map<String, Object>> maps = JdbcUtils.executeQuery(ds, "select 1");
            Connection connection = ds.getConnection();
            int transactionIsolation = connection.getTransactionIsolation();
            System.out.println();
        }

    }

    @Test
    @Ignore
    public void tesSLB() throws Exception {
        Vertx vertx = Vertx.vertx();
        NetClientOptions netClientOptions = new NetClientOptions();
        List<Future> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TimeUnit.SECONDS.sleep(1);
            vertx.createNetClient(netClientOptions).connect(8066, "127.0.0.1", event -> {
                if (event.succeeded()) {
                    NetSocket netSocket = event.result();
                    netSocket.close();
                    list.add(Future.succeededFuture());
                }else {
                    list.add(Future.failedFuture(event.cause()));
                }
            });
        }
        CompositeFuture.join(list).toCompletionStage().toCompletableFuture().get(11,TimeUnit.SECONDS);
    }
}
