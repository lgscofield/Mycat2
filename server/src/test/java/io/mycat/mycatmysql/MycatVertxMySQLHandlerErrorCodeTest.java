package io.mycat.mycatmysql;

import io.mycat.MycatDataContext;
import io.mycat.protocol.mysql.type.MySQLErrorCode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MycatVertxMySQLHandlerErrorCodeTest {

    private MycatVertxMySQLHandler newHandler(MycatVertxMysqlSession session) {
        return new MycatVertxMySQLHandler(session);
    }

    private MycatVertxMysqlSession newSession() {
        MycatVertxMysqlSession session = mock(MycatVertxMysqlSession.class);
        MycatDataContext dataContext = mock(MycatDataContext.class);
        when(session.getDataContext()).thenReturn(dataContext);
        when(session.getSocket()).thenReturn(mock(NetSocket.class));
        when(session.writeErrorEndPacketBySyncInProcessError(anyInt()))
                .thenReturn(Future.succeededFuture());
        return session;
    }

    @Test
    void unknownThrowableUsesMysqlUnknownErrorCode() {
        MycatVertxMysqlSession session = newSession();

        newHandler(session).handle0(0, Buffer.buffer(new byte[0]));

        verify(session).writeErrorEndPacketBySyncInProcessError(MySQLErrorCode.ER_UNKNOWN_ERROR);
    }

    @Test
    void unsupportedCommandDoesNotCollideWithGisErrorCode() {
        MycatVertxMysqlSession session = newSession();

        newHandler(session).handle0(1, Buffer.buffer(new byte[]{(byte) 0x7f}));

        ArgumentCaptor<Integer> code = ArgumentCaptor.forClass(Integer.class);
        verify(session).writeErrorEndPacketBySyncInProcessError(code.capture());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(session.getDataContext()).setLastMessage(message.capture());
        assertEquals(MySQLErrorCode.ER_NOT_SUPPORTED_YET, code.getValue(), message.getValue());
    }
}
