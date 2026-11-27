package io.mycat.command;

import io.mycat.proxy.session.MySQLServerSession;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractCommandHandlerResetTest {

    private final AbstractCommandHandler handler = mock(AbstractCommandHandler.class, CALLS_REAL_METHODS);

    @Test
    void okPacketWaitsForSessionReset() {
        MySQLServerSession session = mock(MySQLServerSession.class);
        Promise<Void> reset = Promise.promise();
        when(session.resetSession()).thenReturn(reset.future());
        when(session.writeOkEndPacket()).thenReturn(Future.succeededFuture());

        Future<Void> result = handler.handleResetConnection(session);

        assertFalse(result.isComplete());
        verify(session, never()).writeOkEndPacket();
        reset.complete();
        assertTrue(result.succeeded());
        verify(session).writeOkEndPacket();
    }

    @Test
    void resetFailureDoesNotSendOkPacket() {
        MySQLServerSession session = mock(MySQLServerSession.class);
        RuntimeException failure = new RuntimeException("rollback failed");
        when(session.resetSession()).thenReturn(Future.failedFuture(failure));
        when(session.close(false, "session reset failed")).thenReturn(Future.succeededFuture());

        Future<Void> result = handler.handleResetConnection(session);

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        verify(session, never()).writeOkEndPacket();
        verify(session).close(false, "session reset failed");
    }
}
