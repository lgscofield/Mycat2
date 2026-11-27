package io.mycat.mycatmysql;

import io.mycat.MycatDataContext;
import io.mycat.protocol.mysql.type.MySQLCommandType;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MycatVertxMySQLHandlerResetTest {

    @Test
    void okPacketWaitsForSessionReset() {
        MycatVertxMysqlSession session = mock(MycatVertxMysqlSession.class);
        when(session.getDataContext()).thenReturn(mock(MycatDataContext.class));
        when(session.getSocket()).thenReturn(mock(NetSocket.class));
        Promise<Void> reset = Promise.promise();
        when(session.resetSession()).thenReturn(reset.future());
        when(session.writeOkEndPacket()).thenReturn(Future.succeededFuture());

        Future<Void> result = new MycatVertxMySQLHandler(session).handleResetConnection(session);

        assertFalse(result.isComplete());
        verify(session, never()).writeOkEndPacket();
        reset.complete();
        assertTrue(result.isComplete());
        verify(session).writeOkEndPacket();
    }

    @Test
    void resetFailureDoesNotSendOkPacket() {
        MycatVertxMysqlSession session = mock(MycatVertxMysqlSession.class);
        when(session.getDataContext()).thenReturn(mock(MycatDataContext.class));
        when(session.getSocket()).thenReturn(mock(NetSocket.class));
        RuntimeException failure = new RuntimeException("rollback failed");
        when(session.resetSession()).thenReturn(Future.failedFuture(failure));
        when(session.close(false, "session reset failed")).thenReturn(Future.succeededFuture());

        Future<Void> result = new MycatVertxMySQLHandler(session).handleResetConnection(session);

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        verify(session, never()).writeOkEndPacket();
        verify(session).close(false, "session reset failed");
    }

    @Test
    void pipelinedCommandWaitsForSuccessfulReset() {
        MycatVertxMysqlSession session = session();
        Promise<Void> reset = Promise.promise();
        when(session.resetSession()).thenReturn(reset.future());
        when(session.writeOkEndPacket()).thenReturn(Future.succeededFuture());
        MycatVertxMySQLHandler handler = new MycatVertxMySQLHandler(session);

        handler.handle(1, command(MySQLCommandType.COM_RESET_CONNECTION), session.getSocket());
        handler.handle(2, command(MySQLCommandType.COM_PING), session.getSocket());

        verify(session, never()).writeOkEndPacket();
        reset.complete();
        verify(session, times(2)).writeOkEndPacket();
    }

    @Test
    void failedResetPreventsPipelinedCommand() {
        MycatVertxMysqlSession session = session();
        RuntimeException failure = new RuntimeException("rollback failed");
        when(session.resetSession()).thenReturn(Future.failedFuture(failure));
        when(session.close(false, "session reset failed")).thenReturn(Future.succeededFuture());
        MycatVertxMySQLHandler handler = new MycatVertxMySQLHandler(session);

        handler.handle(1, command(MySQLCommandType.COM_RESET_CONNECTION), session.getSocket());
        handler.handle(2, command(MySQLCommandType.COM_PING), session.getSocket());

        verify(session, never()).writeOkEndPacket();
        verify(session).close(false, "session reset failed");
    }

    @Test
    void closedSessionDoesNotExecuteCommand() {
        MycatVertxMysqlSession session = session();
        when(session.isClosed()).thenReturn(true);

        new MycatVertxMySQLHandler(session).handle(
                1, command(MySQLCommandType.COM_PING), session.getSocket());

        verify(session, never()).writeOkEndPacket();
    }

    private static MycatVertxMysqlSession session() {
        MycatVertxMysqlSession session = mock(MycatVertxMysqlSession.class);
        when(session.getDataContext()).thenReturn(mock(MycatDataContext.class));
        when(session.getSocket()).thenReturn(mock(NetSocket.class));
        when(session.writeErrorEndPacketBySyncInProcessError(anyInt()))
                .thenReturn(Future.succeededFuture());
        return session;
    }

    private static Buffer command(int command) {
        return Buffer.buffer(new byte[]{(byte) command});
    }
}
