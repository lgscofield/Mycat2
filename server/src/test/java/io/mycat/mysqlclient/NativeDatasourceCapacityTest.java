package io.mycat.mysqlclient;

import io.mycat.MetaClusterCurrent;
import io.mycat.commands.MycatMySQLManagerImpl;
import io.mycat.config.DatasourceConfig;
import io.mycat.monitor.DatabaseInstanceEntry;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class NativeDatasourceCapacityTest {

    private static final String TARGET = "native_capacity_ds";

    @AfterEach
    void clearMetric() {
        DatabaseInstanceEntry.map.remove(TARGET);
    }

    private static DatasourceConfig datasource() {
        DatasourceConfig datasource = new DatasourceConfig();
        datasource.setName(TARGET);
        datasource.setType(DatasourceConfig.DatasourceType.NATIVE.name());
        datasource.setUrl("jdbc:mysql://127.0.0.1:3306/mysql");
        datasource.setUser("root");
        datasource.setPassword("123456");
        return datasource;
    }

    private static MycatNativeDatasourcePool createPool(Vertx vertx, DatasourceConfig datasource) {
        try (MockedStatic<MetaClusterCurrent> cluster = mockStatic(MetaClusterCurrent.class)) {
            cluster.when(() -> MetaClusterCurrent.wrapper(Vertx.class)).thenReturn(vertx);
            return (MycatNativeDatasourcePool)
                    MycatMySQLManagerImpl.createNativeDatasourcePool(datasource, TARGET);
        }
    }

    @Test
    void nativeFactoryPassesDatasourceCapacityToRawPool() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        DatasourceConfig datasource = datasource();
        datasource.setMaxCon(2);
        datasource.setMinCon(0);

        MycatNativeDatasourcePool pool = createPool(vertx, datasource);

        Assertions.assertEquals(2, pool.vertxPoolConnection.config.getMaxCon());
        Assertions.assertEquals(0, pool.vertxPoolConnection.config.getMinCon());
        pool.close();
    }

    @Test
    void nativeFactoryPreservesDefaultCapacity() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);

        MycatNativeDatasourcePool pool = createPool(vertx, datasource());

        Assertions.assertEquals(1000, pool.vertxPoolConnection.config.getMaxCon());
        Assertions.assertEquals(1, pool.vertxPoolConnection.config.getMinCon());
        pool.close();
    }
}
