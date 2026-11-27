import io.mycat.MetaClusterCurrent;
import io.mycat.commands.MycatMySQLManagerImpl;
import io.mycat.config.DatasourceConfig;
import io.mycat.mysqlclient.MycatNativeDatasourcePool;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.RowSet;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.concurrent.TimeUnit;

public class NativeCapacityProbe {
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    private static void queryOne(NewMycatConnection connection, String phase) throws Exception {
        RowSet rows = await(connection.query("SELECT 1"));
        Object value = rows.iterator().next()[0];
        if (!"1".equals(String.valueOf(value))) {
            throw new AssertionError(phase + " expected 1 but got " + value);
        }
        System.out.println(phase + " PASS value=1");
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        Vertx vertx = Vertx.vertx();
        MetaClusterCurrent.register(Vertx.class, vertx);
        MycatNativeDatasourcePool pool = null;
        try {
            DatasourceConfig datasource = new DatasourceConfig();
            datasource.setName("native_capacity_live");
            datasource.setType(DatasourceConfig.DatasourceType.NATIVE.name());
            datasource.setUrl("jdbc:mysql://" + host + ":" + port + "/mysql");
            datasource.setUser("root");
            datasource.setPassword("123456");
            datasource.setMaxCon(1);
            datasource.setMinCon(0);
            datasource.setMaxRetryCount(0);
            datasource.setIdleTimeout(120000);
            pool = (MycatNativeDatasourcePool) MycatMySQLManagerImpl.createNativeDatasourcePool(
                    datasource, "native_capacity_live");
            System.out.println("CONFIG PASS maxCon=1 minCon=0 retry=0");

            NewMycatConnection first = await(pool.getConnection());
            System.out.println("BORROW_FIRST PASS");
            queryOne(first, "QUERY_FIRST");

            Future<NewMycatConnection> rejected = pool.getConnection();
            if (!rejected.failed() || !"pool capacity exhausted".equals(rejected.cause().getMessage())) {
                throw new AssertionError("second borrow must fail with pool capacity exhausted but was "
                        + (rejected.failed() ? rejected.cause() : "successful"));
            }
            System.out.println("BORROW_SECOND PASS rejection=pool capacity exhausted");

            await(first.close());
            System.out.println("RETURN_FIRST PASS");

            NewMycatConnection third = await(pool.getConnection());
            System.out.println("BORROW_THIRD PASS");
            queryOne(third, "QUERY_THIRD");
            await(third.close());
            System.out.println("CAPACITY_LIVE PASS");
        } finally {
            if (pool != null) {
                pool.close();
            }
            await(vertx.close());
        }
    }
}
