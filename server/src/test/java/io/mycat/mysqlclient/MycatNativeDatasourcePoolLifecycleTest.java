package io.mycat.mysqlclient;

import io.mycat.monitor.DatabaseInstanceEntry;
import io.mycat.mysqlclient.command.ConnectHandler;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.RemoveAbandonedTimeoutConnectionImpl;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MycatNativeDatasourcePoolLifecycleTest {

    private static final String TARGET = "native_metric_ds";

    @AfterEach
    void clearMetric() {
        DatabaseInstanceEntry.map.remove(TARGET);
    }

    @Test
    void poolCloseReleasesBorrowedConnectionMetricExactlyOnce() {
        Vertx vertx = mock(Vertx.class);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        Promise<VertxConnection> handshakePromise = Promise.promise();
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        when(vertx.createNetClient()).thenReturn(netClient);
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());

        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        config.setRemoveAbandonedTimeoutSecond(0);
        VertxPoolConnectionImpl rawPool = new VertxPoolConnectionImpl(config, vertx);
        MycatNativeDatasourcePool pool = new MycatNativeDatasourcePool(rawPool, TARGET);
        VertxConnection raw = mock(VertxConnection.class);
        when(raw.getConnectionId()).thenReturn(7L);
        when(raw.getConfig()).thenReturn(config);
        when(raw.close()).thenReturn(Future.succeededFuture());
        when(raw.checkVaildForRecycle()).thenReturn(true);

        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()))) {
            Future<NewMycatConnection> borrowFuture = pool.getConnection();
            socketPromise.complete(socket);
            handshakePromise.complete(raw);
            NewMycatConnection borrowed = borrowFuture.result();

            Assertions.assertEquals(1, DatabaseInstanceEntry.stat(TARGET).getCon().get());
            pool.close();
            Assertions.assertEquals(0, DatabaseInstanceEntry.stat(TARGET).getCon().get(),
                    "pool close must release metrics for raw used connections");

            borrowed.close();
            Assertions.assertEquals(0, DatabaseInstanceEntry.stat(TARGET).getCon().get(),
                    "late wrapper close must not decrement the metric twice");
        }
    }

    @Test
    void wrapperConstructionFailureKillsRawConnectionAndReleasesMetric() {
        Vertx vertx = mock(Vertx.class);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        Promise<VertxConnection> handshakePromise = Promise.promise();
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        when(vertx.createNetClient()).thenReturn(netClient);
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        when(socket.close()).thenReturn(Future.succeededFuture());

        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        config.setRemoveAbandonedTimeoutSecond(0);
        VertxPoolConnectionImpl rawPool = new VertxPoolConnectionImpl(config, vertx);
        MycatNativeDatasourcePool pool = new MycatNativeDatasourcePool(rawPool, TARGET);
        VertxConnection raw = mock(VertxConnection.class);
        when(raw.getConnectionId()).thenReturn(8L);
        when(raw.close()).thenReturn(Future.succeededFuture());

        IllegalStateException wrapperFailure = new IllegalStateException("wrapper construction failed");
        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()));
             MockedConstruction<RemoveAbandonedTimeoutConnectionImpl> wrappers = mockConstruction(
                     RemoveAbandonedTimeoutConnectionImpl.class, (wrapper, context) -> {
                         throw wrapperFailure;
                     })) {
            Future<NewMycatConnection> borrow = pool.getConnection();
            socketPromise.complete(socket);
            handshakePromise.complete(raw);

            Assertions.assertTrue(borrow.failed());
            Assertions.assertNotNull(borrow.cause());
            verify(raw).close();
            Assertions.assertEquals(0, rawPool.getUsedNumber());
            Assertions.assertEquals(0, DatabaseInstanceEntry.stat(TARGET).getCon().get());
        }
    }
}
