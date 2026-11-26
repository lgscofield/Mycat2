package io.mycat.mysqlclient;

import io.mycat.mysqlclient.command.ConnectHandler;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VertxPoolConnectionLifecycleTest {

    private static VertxPoolConnectionImpl pool(Vertx vertx, long timerId) {
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(timerId);
        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        config.setTimer(1000);
        return new VertxPoolConnectionImpl(config, vertx);
    }

    private static VertxConnection connection(long id) {
        VertxConnection connection = mock(VertxConnection.class);
        when(connection.getConnectionId()).thenReturn(id);
        when(connection.close()).thenReturn(Future.succeededFuture());
        return connection;
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<T> pendingSet(VertxPoolConnectionImpl pool, String fieldName) throws ReflectiveOperationException {
        Field field = VertxPoolConnectionImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Set<T>) field.get(pool);
    }

    @Test
    void closeCancelsTimerAndClosesIdleAndUsedConnections() {
        Vertx vertx = mock(Vertx.class);
        VertxPoolConnectionImpl pool = pool(vertx, 42L);
        VertxConnection idle = connection(1L);
        VertxConnection used = connection(2L);
        pool.connections.offer(idle);
        pool.usedConnections.put(2L, used);

        pool.close();

        verify(vertx).cancelTimer(42L);
        verify(idle).close();
        verify(used).close();
        if (pool.getAvailableNumber() != 0 || pool.getUsedNumber() != 0) {
            throw new AssertionError("close must clear pool accounting");
        }
    }

    @Test
    void closeIsIdempotent() {
        Vertx vertx = mock(Vertx.class);
        VertxPoolConnectionImpl pool = pool(vertx, 42L);
        VertxConnection used = connection(2L);
        pool.usedConnections.put(2L, used);

        pool.close();
        pool.close();

        verify(vertx, times(1)).cancelTimer(42L);
        verify(used, times(1)).close();
    }

    @Test
    void recycleAfterCloseKillsConnectionInsteadOfReopeningPool() {
        Vertx vertx = mock(Vertx.class);
        VertxPoolConnectionImpl pool = pool(vertx, 42L);
        VertxConnection late = connection(3L);
        when(late.checkVaildForRecycle()).thenReturn(true);

        pool.close();
        pool.recycle(late);

        verify(late).close();
        if (pool.getAvailableNumber() != 0 || pool.getUsedNumber() != 0) {
            throw new AssertionError("closed pool must not accept recycled connections");
        }
    }

    @Test
    void connectionCompletingAfterCloseIsClosedAndRejected() throws Exception {
        Vertx vertx = mock(Vertx.class);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        Promise<VertxConnection> handshakePromise = Promise.promise();
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        when(vertx.createNetClient()).thenReturn(netClient);
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(new VertxPoolConnectionImpl.Config(), vertx);
        VertxConnection late = connection(4L);

        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()))) {
            Future<VertxConnection> borrow = pool.getConnection();
            socketPromise.complete(socket);
            pool.close();
            handshakePromise.complete(late);

            try {
                borrow.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                Assertions.fail("late connection must not be handed out after close");
            } catch (ExecutionException expected) {
                Assertions.assertNotNull(expected.getCause());
            }
            verify(late).close();
            verify(socket).close();
            Assertions.assertEquals(0, pool.getUsedNumber());
        }
    }

    @Test
    void closeClosesPendingSocketAndFailsBorrowWithoutHandshakeCompletion() throws Exception {
        Vertx vertx = mock(Vertx.class);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        Promise<VertxConnection> handshakePromise = Promise.promise();
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        when(vertx.createNetClient()).thenReturn(netClient);
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(new VertxPoolConnectionImpl.Config(), vertx);

        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()))) {
            Future<VertxConnection> borrow = pool.getConnection();
            socketPromise.complete(socket);
            CountDownLatch completed = new CountDownLatch(1);
            Throwable[] failure = new Throwable[1];
            borrow.onComplete(ar -> {
                if (ar.failed()) {
                    failure[0] = ar.cause();
                }
                completed.countDown();
            });

            pool.close();

            Assertions.assertTrue(completed.await(1, TimeUnit.SECONDS), "close must fail a hanging borrow");
            Assertions.assertNotNull(failure[0]);
            verify(socket).close();
        }
    }

    @Test
    void closeClosesPendingClientBeforeSocketConnectCompletes() throws Exception {
        Vertx vertx = mock(Vertx.class);
        NetClient netClient = mock(NetClient.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        when(vertx.createNetClient()).thenReturn(netClient);
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(new VertxPoolConnectionImpl.Config(), vertx);

        Future<VertxConnection> borrow = pool.getConnection();
        pool.close();

        try {
            borrow.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            Assertions.fail("close must fail a borrow waiting for socket connect");
        } catch (ExecutionException expected) {
            Assertions.assertNotNull(expected.getCause());
        }
        verify(netClient).close();
    }

    @Test
    void failedHandshakeClosesSocket() throws Exception {
        Vertx vertx = mock(Vertx.class);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        Promise<VertxConnection> handshakePromise = Promise.promise();
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        when(vertx.createNetClient()).thenReturn(netClient);
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        config.setRetry(0);
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(config, vertx);

        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()))) {
            Future<VertxConnection> borrow = pool.getConnection();
            socketPromise.complete(socket);
            handshakePromise.fail("authentication failed");

            try {
                borrow.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                Assertions.fail("failed handshake must fail borrow");
            } catch (ExecutionException expected) {
                Assertions.assertEquals("authentication failed", expected.getCause().getMessage());
            }
            verify(socket).close();
            pool.close();
        }
    }

    @Test
    void closeContinuesAfterConnectionCloseFailure() {
        Vertx vertx = mock(Vertx.class);
        VertxPoolConnectionImpl pool = pool(vertx, 42L);
        VertxConnection idle = connection(1L);
        VertxConnection used = connection(2L);
        when(idle.close()).thenThrow(new IllegalStateException("close failed"));
        pool.connections.offer(idle);
        pool.usedConnections.put(2L, used);

        pool.close();

        verify(used).close();
        Assertions.assertEquals(0, pool.getAvailableNumber());
        Assertions.assertEquals(0, pool.getUsedNumber());
    }

    @Test
    void lateConnectionCloseFailureStillFailsBorrow() throws Exception {
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
        config.setRetry(0);
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(config, vertx);
        VertxConnection late = connection(4L);
        when(late.close()).thenThrow(new IllegalStateException("late close failed"));

        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()))) {
            Future<VertxConnection> borrow = pool.getConnection();
            socketPromise.complete(socket);
            pool.close();
            handshakePromise.complete(late);

            try {
                borrow.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                Assertions.fail("late connection close failure must not leave borrow pending");
            } catch (ExecutionException expected) {
                Assertions.assertNotNull(expected.getCause());
            }
            verify(late).close();
            verify(socket).close();
        }
    }

    @Test
    void failedHandshakeSocketCloseFailureStillFailsBorrow() throws Exception {
        Vertx vertx = mock(Vertx.class);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        Promise<VertxConnection> handshakePromise = Promise.promise();
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        when(vertx.createNetClient()).thenReturn(netClient);
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        when(socket.close()).thenThrow(new IllegalStateException("socket close failed"));
        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        config.setRetry(0);
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(config, vertx);

        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()))) {
            Future<VertxConnection> borrow = pool.getConnection();
            socketPromise.complete(socket);
            handshakePromise.fail("authentication failed");

            try {
                borrow.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                Assertions.fail("socket close failure must not leave borrow pending");
            } catch (ExecutionException expected) {
                Assertions.assertEquals("authentication failed", expected.getCause().getMessage());
            }
            verify(socket).close();
        }
    }

    @Test
    void closeContinuesAfterPendingPromiseListenerThrows() throws Exception {
        Vertx vertx = mock(Vertx.class);
        VertxPoolConnectionImpl pool = pool(vertx, 42L);
        NetClient netClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        @SuppressWarnings("unchecked")
        Promise<VertxConnection> pending = mock(Promise.class);
        when(netClient.close()).thenReturn(Future.succeededFuture());
        when(socket.close()).thenReturn(Future.succeededFuture());
        when(pending.tryFail("pool has closed")).thenThrow(new IllegalStateException("listener failed"));
        VertxConnection used = connection(2L);
        pendingSet(pool, "pendingConnections").add(pending);
        pendingSet(pool, "pendingClients").add(netClient);
        pendingSet(pool, "pendingSockets").add(socket);
        pool.usedConnections.put(2L, used);

        Assertions.assertDoesNotThrow(pool::close);

        verify(netClient).close();
        verify(socket).close();
        verify(used).close();
        Assertions.assertEquals(0, pool.getUsedNumber());
    }

    @Test
    void synchronousConnectFailureClosesClientAndClearsPendingState() throws Exception {
        Vertx vertx = mock(Vertx.class);
        NetClient netClient = mock(NetClient.class);
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        when(vertx.createNetClient()).thenReturn(netClient);
        when(netClient.connect(3306, "127.0.0.1"))
                .thenThrow(new IllegalStateException("connect failed"));
        when(netClient.close()).thenReturn(Future.succeededFuture());
        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        config.setRetry(0);
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(config, vertx);

        Future<VertxConnection> borrow = Assertions.assertDoesNotThrow(pool::getConnection);
        try {
            borrow.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            Assertions.fail("synchronous connect failure must fail borrow");
        } catch (ExecutionException expected) {
            Assertions.assertEquals("connect failed", expected.getCause().getMessage());
        }
        verify(netClient).close();
        Assertions.assertTrue(pendingSet(pool, "pendingClients").isEmpty());
        Assertions.assertTrue(pendingSet(pool, "pendingConnections").isEmpty());
    }
}
