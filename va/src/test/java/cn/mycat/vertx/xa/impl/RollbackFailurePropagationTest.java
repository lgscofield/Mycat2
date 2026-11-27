package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaLog;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.SqlResult;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.vertx.core.Future;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RollbackFailurePropagationTest {

    @Test
    public void localRollbackFailureIsPropagatedAfterCleanup() throws Exception {
        MySQLManager manager = mock(MySQLManager.class);
        NewMycatConnection connection = connection("ds1");
        when(manager.getConnection("ds1")).thenReturn(Future.succeededFuture(connection));
        RuntimeException failure = new RuntimeException("local rollback failed");
        when(connection.update("rollback")).thenReturn(Future.failedFuture(failure));

        LocalSqlConnection session = new LocalSqlConnection(
                MySQLIsolation.DEFAULT, () -> manager, mock(XaLog.class));
        await(session.begin());
        await(session.getConnection("ds1"));

        assertSame(failure, awaitFailure(session.rollback()));
        assertFalse(session.isInTransaction());
        assertTrue(session.getAllConnections().isEmpty());
    }

    @Test
    public void localClosePropagatesRollbackFailureAfterConnectionCleanup() throws Exception {
        MySQLManager manager = mock(MySQLManager.class);
        NewMycatConnection connection = connection("ds1");
        when(manager.getConnection("ds1")).thenReturn(Future.succeededFuture(connection));
        RuntimeException failure = new RuntimeException("local close rollback failed");
        when(connection.update("rollback")).thenReturn(Future.failedFuture(failure));

        LocalSqlConnection session = new LocalSqlConnection(
                MySQLIsolation.DEFAULT, () -> manager, mock(XaLog.class));
        await(session.begin());
        await(session.getConnection("ds1"));

        assertSame(failure, awaitFailure(session.close()));
        assertFalse(session.isInTransaction());
        assertTrue(session.getAllConnections().isEmpty());
    }

    @Test
    public void localKillPropagatesRollbackFailureAfterConnectionCleanup() throws Exception {
        MySQLManager manager = mock(MySQLManager.class);
        NewMycatConnection connection = connection("ds1");
        when(manager.getConnection("ds1")).thenReturn(Future.succeededFuture(connection));
        RuntimeException failure = new RuntimeException("local kill rollback failed");
        when(connection.update("rollback")).thenReturn(Future.failedFuture(failure));

        LocalSqlConnection session = new LocalSqlConnection(
                MySQLIsolation.DEFAULT, () -> manager, mock(XaLog.class));
        await(session.begin());
        await(session.getConnection("ds1"));

        assertSame(failure, awaitFailure(session.kill()));
        assertFalse(session.isInTransaction());
        assertTrue(session.getAllConnections().isEmpty());
    }

    @Test
    public void localXaSingleConnectionRollbackFailureIsPropagatedAfterAbandon() throws Exception {
        MySQLManager manager = mock(MySQLManager.class);
        NewMycatConnection connection = connection("ds1");
        when(manager.getConnection("ds1")).thenReturn(Future.succeededFuture(connection));
        RuntimeException failure = new RuntimeException("local XA rollback failed");
        when(connection.update("rollback;")).thenReturn(Future.failedFuture(failure));

        LocalXaSqlConnection session = new LocalXaSqlConnection(
                MySQLIsolation.DEFAULT, () -> manager, mock(XaLog.class));
        await(session.begin());
        await(session.getConnection("ds1"));

        assertSame(failure, awaitFailure(session.rollback()));
        assertFalse(session.isInTransaction());
        assertTrue(session.getAllConnections().isEmpty());
    }

    @Test
    public void localXaKillPropagatesRollbackFailureAfterAbandon() throws Exception {
        MySQLManager manager = mock(MySQLManager.class);
        NewMycatConnection connection = connection("ds1");
        when(manager.getConnection("ds1")).thenReturn(Future.succeededFuture(connection));
        RuntimeException failure = new RuntimeException("local XA kill rollback failed");
        when(connection.update("rollback;")).thenReturn(Future.failedFuture(failure));

        LocalXaSqlConnection session = new LocalXaSqlConnection(
                MySQLIsolation.DEFAULT, () -> manager, mock(XaLog.class));
        await(session.begin());
        await(session.getConnection("ds1"));

        assertSame(failure, awaitFailure(session.kill()));
        assertFalse(session.isInTransaction());
        assertTrue(session.getAllConnections().isEmpty());
    }

    @Test
    public void mixedXaCoordinatorRollbackFailureIsPropagatedAfterCleanup() throws Exception {
        MySQLManager manager = mock(MySQLManager.class);
        XaLog xaLog = mock(XaLog.class);
        when(xaLog.nextXid()).thenReturn("rollback-failure-xid");
        NewMycatConnection coordinator = connection("ds1");
        NewMycatConnection participant = connection("ds2");
        when(manager.getConnection("ds1")).thenReturn(Future.succeededFuture(coordinator));
        when(manager.getConnection("ds2")).thenReturn(Future.succeededFuture(participant));
        RuntimeException failure = new RuntimeException("coordinator rollback failed");
        when(coordinator.update("rollback;")).thenReturn(Future.failedFuture(failure));

        LocalXaSqlConnection session = new LocalXaSqlConnection(
                MySQLIsolation.DEFAULT, () -> manager, xaLog);
        await(session.begin());
        await(session.getConnection("ds1"));
        await(session.getConnection("ds2"));

        assertSame(failure, awaitFailure(session.rollback()));
        assertFalse(session.isInTransaction());
        assertTrue(session.getAllConnections().isEmpty());
    }

    private static NewMycatConnection connection(String target) {
        NewMycatConnection connection = mock(NewMycatConnection.class);
        when(connection.getTargetName()).thenReturn(target);
        when(connection.isClosed()).thenReturn(false);
        when(connection.update(anyString())).thenReturn(Future.<SqlResult>succeededFuture(null));
        when(connection.abandonQuery()).thenReturn(Future.succeededFuture());
        when(connection.close()).thenReturn(Future.succeededFuture());
        return connection;
    }

    private static <T> T await(Future<T> future) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        future.onComplete(event -> {
            if (event.succeeded()) {
                result.set(event.result());
            } else {
                failure.set(event.cause());
            }
            latch.countDown();
        });
        assertTrue("future did not complete", latch.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError("unexpected failure", failure.get());
        }
        @SuppressWarnings("unchecked")
        T value = (T) result.get();
        return value;
    }

    private static Throwable awaitFailure(Future<?> future) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        future.onComplete(event -> {
            if (event.failed()) {
                failure.set(event.cause());
            }
            latch.countDown();
        });
        assertTrue("future did not complete", latch.await(20, TimeUnit.SECONDS));
        assertTrue("rollback failure was swallowed", failure.get() != null);
        return failure.get();
    }
}
