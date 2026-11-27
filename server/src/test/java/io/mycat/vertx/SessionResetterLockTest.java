package io.mycat.vertx;

import com.sun.net.httpserver.HttpServer;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatUser;
import io.mycat.TransactionSession;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.config.MycatServerConfig;
import io.mycat.config.UserConfig;
import io.mycat.runtime.MycatDataContextImpl;
import io.vertx.core.Future;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionResetterLockTest {

    private Map<Class, Object> previousContext;

    static class Ctx extends MycatDataContextImpl {
        @Override
        public void switchTransaction(TransactionType transactionType) {
        }
    }

    @BeforeEach
    void saveContext() {
        previousContext = MetaClusterCurrent.copyContext();
    }

    @AfterEach
    void restoreContext() {
        MetaClusterCurrent.register(previousContext);
    }

    @Test
    void resetWaitsUntilAllLocksAreReleased() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch resetCompleted = new CountDownLatch(1);
        HttpServer lockServer = startLockServer(requestReceived, releaseResponse, "1");
        Ctx context = context(lockServer);

        try {
            Future<Void> result = session(context).resetSession();

            assertTrue(requestReceived.await(5, TimeUnit.SECONDS));
            assertFalse(result.isComplete());
            result.onComplete(event -> resetCompleted.countDown());
            releaseResponse.countDown();
            assertTrue(resetCompleted.await(5, TimeUnit.SECONDS));
            assertTrue(result.succeeded(), () -> String.valueOf(result.cause()));
            assertTrue(usedLocks(context).isEmpty());
        } finally {
            releaseResponse.countDown();
            lockServer.stop(0);
        }
    }

    @Test
    void lockReleaseFailureFailsResetAndKeepsUnreleasedLock() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch resetCompleted = new CountDownLatch(1);
        HttpServer lockServer = startLockServer(requestReceived, new CountDownLatch(0), "0");
        Ctx context = context(lockServer);

        try {
            Future<Void> result = session(context).resetSession();
            result.onComplete(event -> resetCompleted.countDown());

            assertTrue(requestReceived.await(5, TimeUnit.SECONDS));
            assertTrue(resetCompleted.await(5, TimeUnit.SECONDS));
            assertTrue(result.failed());
            assertTrue(usedLocks(context).contains("debt127_lock"));
        } finally {
            lockServer.stop(0);
        }
    }

    private static Ctx context(HttpServer lockServer) {
        MycatServerConfig config = new MycatServerConfig();
        config.getProperties().put("lock_service_address",
                "http://127.0.0.1:" + lockServer.getAddress().getPort() + "/lockserivce");
        MetaClusterCurrent.register(MycatServerConfig.class, config);

        Ctx context = new Ctx();
        TransactionSession transaction = mock(TransactionSession.class);
        when(transaction.rollback()).thenReturn(Future.succeededFuture());
        when(transaction.closeStatementState()).thenReturn(Future.succeededFuture());
        context.setTransactionSession(transaction);
        context.setUser(MycatUser.builder().userName("root")
                .userConfig(new UserConfig()).build());
        context.getUsedlocks().add("debt127_lock");
        return context;
    }

    private static VertxSessionImpl session(Ctx context) {
        return new VertxSessionImpl(context, mock(NetSocket.class), null);
    }

    private static Set<?> usedLocks(Ctx context) throws ReflectiveOperationException {
        Field field = MycatDataContextImpl.class.getDeclaredField("usedlocks");
        field.setAccessible(true);
        return (Set<?>) field.get(context);
    }

    private static HttpServer startLockServer(CountDownLatch requestReceived,
                                               CountDownLatch releaseResponse,
                                               String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lockserivce", exchange -> {
            requestReceived.countDown();
            try {
                if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
