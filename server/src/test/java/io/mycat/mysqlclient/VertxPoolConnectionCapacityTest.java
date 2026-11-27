package io.mycat.mysqlclient;

import io.mycat.mysqlclient.command.ConnectHandler;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VertxPoolConnectionCapacityTest {

    private static VertxPoolConnectionImpl.Config config(int maxCon, int minCon) {
        VertxPoolConnectionImpl.Config config = new VertxPoolConnectionImpl.Config();
        config.setMaxCon(maxCon);
        config.setMinCon(minCon);
        config.setRetry(0);
        return config;
    }

    private static Vertx vertx() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(42L);
        return vertx;
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,-1", "1,2"})
    void rejectsInvalidCapacity(int maxCon, int minCon) {
        Vertx vertx = vertx();

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new VertxPoolConnectionImpl(config(maxCon, minCon), vertx));
    }

    @Test
    void pendingHandshakeReservesCapacityUntilBorrowCompletes() {
        Vertx vertx = vertx();
        NetClient firstClient = mock(NetClient.class);
        NetClient secondClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        Promise<NetSocket> secondSocketPromise = Promise.promise();
        Promise<VertxConnection> handshakePromise = Promise.promise();
        when(vertx.createNetClient()).thenReturn(firstClient, secondClient);
        when(firstClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        when(secondClient.connect(3306, "127.0.0.1")).thenReturn(secondSocketPromise.future());
        when(socket.close()).thenReturn(Future.succeededFuture());
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(config(1, 0), vertx);

        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()))) {
            Future<VertxConnection> first = pool.getConnection();
            Future<VertxConnection> rejected = pool.getConnection();

            Assertions.assertTrue(rejected.failed(), "a pending handshake must consume pool capacity");
            Assertions.assertEquals("pool capacity exhausted", rejected.cause().getMessage());
            verify(vertx, times(1)).createNetClient();

            socketPromise.complete(socket);
            handshakePromise.fail("authentication failed");
            Assertions.assertEquals("authentication failed", first.cause().getMessage());

            Future<VertxConnection> retry = pool.getConnection();
            Assertions.assertFalse(retry.failed(), "a failed creation must release its reservation");
            verify(vertx, times(2)).createNetClient();
            pool.close();
        }
    }

    @Test
    void successfulBorrowRegistersUsedConnectionAndReleasesReservation() {
        Vertx vertx = vertx();
        NetClient firstClient = mock(NetClient.class);
        NetClient secondClient = mock(NetClient.class);
        NetSocket socket = mock(NetSocket.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        Promise<VertxConnection> handshakePromise = Promise.promise();
        Promise<NetSocket> secondSocketPromise = Promise.promise();
        when(vertx.createNetClient()).thenReturn(firstClient, secondClient);
        when(firstClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        when(secondClient.connect(3306, "127.0.0.1")).thenReturn(secondSocketPromise.future());
        when(socket.close()).thenReturn(Future.succeededFuture());
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(config(1, 0), vertx);
        VertxConnection firstConnection = mock(VertxConnection.class);
        when(firstConnection.getConnectionId()).thenReturn(1L);
        when(firstConnection.close()).thenReturn(Future.succeededFuture());

        try (MockedConstruction<ConnectHandler> handlers = mockConstruction(ConnectHandler.class,
                (handler, context) -> when(handler.handleInitialHandshake()).thenReturn(handshakePromise.future()))) {
            Future<VertxConnection> first = pool.getConnection();
            socketPromise.complete(socket);
            handshakePromise.complete(firstConnection);

            Assertions.assertSame(firstConnection, first.result());
            Assertions.assertEquals(1, pool.getUsedNumber());
            pool.kill(firstConnection);
            Assertions.assertEquals(0, pool.getUsedNumber());

            Future<VertxConnection> second = pool.getConnection();
            Assertions.assertFalse(second.failed(), "successful creation must release its reservation");
            verify(vertx, times(2)).createNetClient();
            pool.close();
        }
    }

    @Test
    void idleConnectionCheckoutCannotRacePastCapacity() throws Exception {
        Vertx vertx = vertx();
        NetClient netClient = mock(NetClient.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        CountDownLatch idlePolled = new CountDownLatch(1);
        CountDownLatch finishIdlePoll = new CountDownLatch(1);
        CountDownLatch competingCreation = new CountDownLatch(1);
        AtomicInteger polls = new AtomicInteger();
        when(vertx.createNetClient()).thenAnswer(invocation -> {
            competingCreation.countDown();
            return netClient;
        });
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        when(netClient.close()).thenReturn(Future.succeededFuture());
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(config(1, 0), vertx);
        VertxConnection idle = mock(VertxConnection.class);
        when(idle.getConnectionId()).thenReturn(1L);
        when(idle.close()).thenReturn(Future.succeededFuture());
        pool.connections = new ConcurrentLinkedQueue<VertxConnection>() {
            @Override
            public VertxConnection poll() {
                VertxConnection connection = super.poll();
                if (connection != null && polls.getAndIncrement() == 0) {
                    idlePolled.countDown();
                    try {
                        Assertions.assertTrue(finishIdlePoll.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return connection;
            }
        };
        pool.connections.offer(idle);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Thread pollReleaser = new Thread(() -> {
            try {
                competingCreation.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finishIdlePoll.countDown();
            }
        });

        try {
            java.util.concurrent.Future<Future<VertxConnection>> firstCall =
                    executor.submit(() -> pool.getConnection());
            Assertions.assertTrue(idlePolled.await(5, TimeUnit.SECONDS));
            pollReleaser.start();
            java.util.concurrent.Future<Future<VertxConnection>> secondCall =
                    executor.submit(() -> pool.getConnection());
            Future<VertxConnection> first = firstCall.get(5, TimeUnit.SECONDS);
            Future<VertxConnection> second = secondCall.get(5, TimeUnit.SECONDS);

            Assertions.assertTrue(first.succeeded());
            Assertions.assertSame(idle, first.result());
            Assertions.assertTrue(second.failed(), "idle checkout and capacity reservation must be atomic");
            Assertions.assertEquals("pool capacity exhausted", second.cause().getMessage());
            verify(vertx, times(0)).createNetClient();
        } finally {
            finishIdlePoll.countDown();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            pool.close();
        }
    }

    @Test
    void concurrentBorrowsCannotBothPassAnEmptyPoolCapacityCheck() throws Exception {
        Vertx vertx = vertx();
        NetClient netClient = mock(NetClient.class);
        Promise<NetSocket> socketPromise = Promise.promise();
        CountDownLatch firstCreationEntered = new CountDownLatch(1);
        CountDownLatch allowFirstCreation = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        when(vertx.createNetClient()).thenAnswer(invocation -> {
            if (creations.getAndIncrement() == 0) {
                firstCreationEntered.countDown();
                Assertions.assertTrue(allowFirstCreation.await(5, TimeUnit.SECONDS));
            }
            return netClient;
        });
        when(netClient.connect(3306, "127.0.0.1")).thenReturn(socketPromise.future());
        when(netClient.close()).thenReturn(Future.succeededFuture());
        VertxPoolConnectionImpl pool = new VertxPoolConnectionImpl(config(1, 0), vertx);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> pool.getConnection());
            Assertions.assertTrue(firstCreationEntered.await(5, TimeUnit.SECONDS));

            Future<VertxConnection> second = pool.getConnection();

            Assertions.assertTrue(second.failed(), "capacity check and reservation must be atomic");
            Assertions.assertEquals("pool capacity exhausted", second.cause().getMessage());
            verify(vertx, times(1)).createNetClient();
        } finally {
            allowFirstCreation.countDown();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            pool.close();
        }
    }
}
