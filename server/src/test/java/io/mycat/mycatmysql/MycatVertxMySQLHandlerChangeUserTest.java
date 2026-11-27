package io.mycat.mycatmysql;

import io.mycat.MycatDataContext;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.protocol.mysql.type.MySQLCommandType;
import io.mycat.vertx.ReadView;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MycatVertxMySQLHandlerChangeUserTest {

    @Test
    void secureConnectionAuthenticationLengthIsUnsigned() {
        MycatVertxMysqlSession session = session();
        MycatVertxMySQLHandler handler = spy(new MycatVertxMySQLHandler(session));
        doReturn(Future.succeededFuture()).when(handler).handleChangeUser(
                anyString(), any(), any(), anyInt(), any(), any(), any());
        byte[] authentication = new byte[255];
        Arrays.fill(authentication, (byte) 'a');
        Buffer packet = Buffer.buffer()
                .appendByte((byte) MySQLCommandType.COM_CHANGE_USER)
                .appendString("u\0")
                .appendByte((byte) 255)
                .appendBytes(authentication)
                .appendString("\0")
                .appendBytes(new byte[2]);

        Future<Void> result = handler.handle0(1, packet);

        assertTrue(result.succeeded(), () -> String.valueOf(result.cause()));
        verify(handler).handleChangeUser("u", "a".repeat(255), "", 0, null, Map.of(), session);
    }

    @Test
    void truncatedAuthenticationIsRejectedBeforeAllocation() {
        MycatVertxMysqlSession session = session();
        MycatVertxMySQLHandler handler = spy(new MycatVertxMySQLHandler(session));
        doReturn(Future.succeededFuture()).when(handler).handleChangeUser(
                anyString(), any(), any(), anyInt(), any(), any(), any());
        when(session.writeErrorEndPacketBySyncInProcessError(1105))
                .thenReturn(Future.succeededFuture());

        try (var construction = mockConstruction(ReadView.class, (view, context) -> {
            when(view.readByte()).thenReturn(
                    (byte) MySQLCommandType.COM_CHANGE_USER, (byte) 1);
            when(view.readNULString()).thenReturn("u");
            when(view.readable()).thenReturn(0);
        })) {
            Future<Void> result = handler.handle0(1, Buffer.buffer());

            assertTrue(result.succeeded(), () -> String.valueOf(result.cause()));
            verify(construction.constructed().get(0), never()).readFixString(anyInt());
            verify(session).writeErrorEndPacketBySyncInProcessError(1105);
        }
    }

    private static MycatVertxMysqlSession session() {
        MycatVertxMysqlSession session = mock(MycatVertxMysqlSession.class);
        when(session.getDataContext()).thenReturn(mock(MycatDataContext.class));
        when(session.getSocket()).thenReturn(mock(NetSocket.class));
        when(session.getCapabilities()).thenReturn(MySQLServerCapabilityFlags.CLIENT_SECURE_CONNECTION);
        when(session.writeErrorEndPacketBySyncInProcessError(anyInt()))
                .thenReturn(Future.succeededFuture());
        return session;
    }
}
