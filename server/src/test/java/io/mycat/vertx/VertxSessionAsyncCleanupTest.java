package io.mycat.vertx;

import io.mycat.MycatDataContext;
import io.mycat.TransactionSession;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.runtime.MycatDataContextImpl;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VertxSessionAsyncCleanupTest {

    @Test
    void normalCloseWaitsForTransactionCleanup() {
        Promise<Void> transactionClose = Promise.promise();
        TransactionSession transaction = transaction(transactionClose.future(), null);
        CleanupContext context = new CleanupContext(transaction);
        NetSocket socket = socket();
        VertxSessionImpl session = new VertxSessionImpl(context, socket, null);

        Future<Void> result = session.close();

        assertFalse(result.isComplete());
        assertFalse(session.isClosed());
        verify(socket, never()).close();
        transactionClose.complete();
        assertTrue(result.succeeded());
        assertTrue(session.isClosed());
        verify(socket).close();
    }

    @Test
    void failedKillCleanupRemainsRetryableAndReturnsOriginalCause() {
        Promise<Void> transactionKill = Promise.promise();
        TransactionSession transaction = transaction(null, transactionKill.future());
        CleanupContext context = new CleanupContext(transaction);
        VertxSessionImpl session = new VertxSessionImpl(context, socket(), null);
        RuntimeException failure = new RuntimeException("transaction kill failed");

        Future<Void> result = session.close(false, "frontend closed");
        transactionKill.fail(failure);

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        assertFalse(session.isClosed());
    }

    @Test
    void failedTransactionCleanupStillReleasesLocksAndPreservesTransactionCause() {
        Promise<Void> transactionClose = Promise.promise();
        TransactionSession transaction = transaction(transactionClose.future(), null);
        RuntimeException transactionFailure = new RuntimeException("transaction close failed");
        CompletableFuture<Void> lockCleanup = new CompletableFuture<>();
        boolean[] lockCleanupAttempted = {false};
        CleanupContext context = new CleanupContext(transaction) {
            @Override
            public CompletableFuture<Void> releaseAllLocks() {
                lockCleanupAttempted[0] = true;
                return lockCleanup;
            }
        };
        VertxSessionImpl session = new VertxSessionImpl(context, socket(), null);

        Future<Void> result = session.close();
        transactionClose.fail(transactionFailure);

        assertTrue(lockCleanupAttempted[0]);
        assertFalse(result.isComplete());
        lockCleanup.complete(null);
        assertTrue(result.failed());
        assertSame(transactionFailure, result.cause());
        assertFalse(session.isClosed());
    }

    @Test
    void synchronousTransactionCleanupFailureStillReleasesLocks() {
        TransactionSession transaction = mock(TransactionSession.class);
        RuntimeException failure = new RuntimeException("transaction close threw");
        when(transaction.close()).thenThrow(failure);
        boolean[] lockCleanupAttempted = {false};
        CleanupContext context = new CleanupContext(transaction) {
            @Override
            public CompletableFuture<Void> releaseAllLocks() {
                lockCleanupAttempted[0] = true;
                return CompletableFuture.completedFuture(null);
            }
        };
        VertxSessionImpl session = new VertxSessionImpl(context, socket(), null);

        Future<Void> result = session.close();

        assertTrue(lockCleanupAttempted[0]);
        assertTrue(result.failed());
        assertSame(failure, result.cause());
        assertFalse(session.isClosed());
    }

    @Test
    void failedLockCleanupClosesSocketAndRemainsRetryable() {
        TransactionSession transaction = transaction(Future.succeededFuture(), null);
        RuntimeException failure = new RuntimeException("lock cleanup failed");
        CleanupContext context = new CleanupContext(transaction) {
            @Override
            public CompletableFuture<Void> releaseAllLocks() {
                return CompletableFuture.failedFuture(failure);
            }
        };
        NetSocket socket = socket();
        VertxSessionImpl session = new VertxSessionImpl(context, socket, null);

        Future<Void> result = session.close();

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        assertFalse(session.isClosed());
        verify(socket).close();
    }

    @Test
    void closeWaitsForLockCleanupBeforeClosingSocket() {
        TransactionSession transaction = transaction(Future.succeededFuture(), null);
        CompletableFuture<Void> lockCleanup = new CompletableFuture<>();
        CleanupContext context = new CleanupContext(transaction) {
            @Override
            public CompletableFuture<Void> releaseAllLocks() {
                return lockCleanup;
            }
        };
        NetSocket socket = socket();
        VertxSessionImpl session = new VertxSessionImpl(context, socket, null);

        Future<Void> result = session.close();

        assertFalse(result.isComplete());
        assertFalse(session.isClosed());
        verify(socket, never()).close();
        lockCleanup.complete(null);
        assertTrue(result.succeeded());
        assertTrue(session.isClosed());
        verify(socket).close();
    }

    @Test
    void failedKillCleanupCanBeRetried() {
        Promise<Void> firstKill = Promise.promise();
        Promise<Void> secondKill = Promise.promise();
        TransactionSession transaction = transaction(null, firstKill.future());
        when(transaction.kill()).thenReturn(firstKill.future(), secondKill.future());
        CleanupContext context = new CleanupContext(transaction);
        VertxSessionImpl session = new VertxSessionImpl(context, socket(), null);
        RuntimeException failure = new RuntimeException("first kill failed");

        Future<Void> firstResult = session.close(false, "frontend closed");
        firstKill.fail(failure);
        Future<Void> retry = session.close(false, "frontend cleanup retry");

        assertTrue(firstResult.failed());
        assertSame(failure, firstResult.cause());
        assertFalse(retry.isComplete());
        assertFalse(session.isClosed());
        secondKill.complete();
        assertTrue(retry.succeeded());
        assertTrue(session.isClosed());
    }

    @Test
    void synchronousCleanupFailureStillClosesSocket() {
        MycatDataContext context = mock(MycatDataContext.class);
        RuntimeException failure = new RuntimeException("synchronous cleanup failed");
        doThrow(failure).when(context).close();
        NetSocket socket = socket();
        VertxSessionImpl session = new VertxSessionImpl(context, socket, null);

        assertSame(failure, assertThrows(RuntimeException.class, () -> session.close()));
        verify(socket).close();
    }

    private static TransactionSession transaction(Future<Void> close, Future<Void> kill) {
        TransactionSession transaction = mock(TransactionSession.class);
        when(transaction.close()).thenReturn(close);
        when(transaction.kill()).thenReturn(kill);
        return transaction;
    }

    private static NetSocket socket() {
        NetSocket socket = mock(NetSocket.class);
        when(socket.close()).thenReturn(Future.succeededFuture());
        return socket;
    }

    private static class CleanupContext extends MycatDataContextImpl {
        private CleanupContext(TransactionSession transaction) {
            setTransactionSession(transaction);
        }

        @Override
        public void switchTransaction(TransactionType transactionType) {
        }
    }
}
