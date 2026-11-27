package io.mycat.proxy.session;

import io.mycat.MycatDataContext;
import io.mycat.TransactionSession;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionResetterFailureTest {

    @Test
    void missingTransactionSessionBecomesFailedFuture() {
        MycatDataContext context = mock(MycatDataContext.class);
        RuntimeException failure = new RuntimeException("transaction session unavailable");
        when(context.getTransactionSession()).thenThrow(failure);

        Future<Void> result = assertDoesNotThrow(() -> SessionResetter.reset(context));

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        verify(context, never()).setAutoCommit(true);
    }

    @Test
    void synchronousRollbackFailureBecomesFailedFuture() {
        MycatDataContext context = mock(MycatDataContext.class);
        TransactionSession transaction = mock(TransactionSession.class);
        when(context.getTransactionSession()).thenReturn(transaction);
        RuntimeException failure = new RuntimeException("rollback threw");
        when(transaction.rollback()).thenThrow(failure);

        Future<Void> result = assertDoesNotThrow(() -> SessionResetter.reset(context));

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        verify(context, never()).setAutoCommit(true);
    }
}
