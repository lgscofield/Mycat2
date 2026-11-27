package io.mycat.command;

import io.mycat.beans.mysql.packet.MySQLPacket;
import io.mycat.beans.mycat.MycatErrorCode;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.proxy.session.MycatSession;
import io.mycat.proxy.session.ProcessState;
import io.mycat.protocol.mysql.type.MySQLCommandType;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandResolverActivityTest {

    @Test
    void commandStaysDoingUntilItsResponseCompletes() {
        MycatSession session = mock(MycatSession.class);
        MySQLPacket packet = packet(MySQLCommandType.COM_QUERY);
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        Promise<Void> response = Promise.promise();
        when(dispatcher.handleQuery(any(byte[].class), any())).thenReturn(response.future());

        CommandResolver.handle(session, packet, dispatcher);

        verify(session).setResponseFinished(ProcessState.DOING);
        verify(session, never()).onHandlerFinishedClear();
        response.complete();
        verify(session, never()).onHandlerFinishedClear();
    }

    @Test
    void responseFreeCommandReturnsToReadyAfterCompletion() {
        MycatSession session = mock(MycatSession.class);
        MySQLPacket packet = packet(MySQLCommandType.COM_STMT_CLOSE);
        when(packet.readFixInt(4)).thenReturn(7L);
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.handlePrepareStatementClose(7L, session)).thenReturn(Future.succeededFuture());

        CommandResolver.handle(session, packet, dispatcher);

        verify(session).setResponseFinished(ProcessState.DOING);
        verify(session).onHandlerFinishedClear();
    }

    @Test
    void longDataCommandReturnsToReadyAfterCompletion() {
        MycatSession session = mock(MycatSession.class);
        MySQLPacket packet = packet(MySQLCommandType.COM_STMT_SEND_LONG_DATA);
        when(packet.readFixInt(4)).thenReturn(7L);
        when(packet.readFixInt(2)).thenReturn(0L);
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        Promise<Void> response = Promise.promise();
        when(dispatcher.handlePrepareStatementLongdata(7L, 0, new byte[]{'1'}, session))
                .thenReturn(response.future());

        CommandResolver.handle(session, packet, dispatcher);

        verify(session).setResponseFinished(ProcessState.DOING);
        verify(session, never()).onHandlerFinishedClear();
        response.complete();
        verify(session).onHandlerFinishedClear();
    }

    @Test
    void changeUserAuthenticationLengthIsUnsigned() {
        MycatSession session = mock(MycatSession.class);
        when(session.getCapabilities()).thenReturn(MySQLServerCapabilityFlags.CLIENT_SECURE_CONNECTION);
        MySQLPacket packet = packet(MySQLCommandType.COM_CHANGE_USER);
        when(packet.readFinished()).thenReturn(false);
        when(packet.readFixInt(2)).thenReturn(0L);
        when(packet.readable()).thenReturn(300);
        when(packet.readByte()).thenReturn((byte) MySQLCommandType.COM_CHANGE_USER, (byte) 255);
        when(packet.readNULString()).thenReturn("u", "");
        when(packet.readFixString(anyInt())).thenAnswer(invocation -> {
            int length = invocation.<Integer>getArgument(0);
            if (length < 0) {
                throw new IndexOutOfBoundsException("negative authentication length");
            }
            return "a".repeat(length);
        });
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.handleChangeUser(any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(Future.succeededFuture());
        String authentication = "a".repeat(255);

        CommandResolver.handle(session, packet, dispatcher);

        verify(dispatcher).handleChangeUser("u", authentication, "", 0, null, Map.of(), session);
    }

    @Test
    void truncatedChangeUserAuthenticationIsRejectedBeforeReading() {
        MycatSession session = mock(MycatSession.class);
        when(session.getCapabilities()).thenReturn(MySQLServerCapabilityFlags.CLIENT_SECURE_CONNECTION);
        MySQLPacket packet = packet(MySQLCommandType.COM_CHANGE_USER);
        when(packet.readFinished()).thenReturn(false, true);
        when(packet.readable()).thenReturn(0);
        when(packet.readByte()).thenReturn((byte) MySQLCommandType.COM_CHANGE_USER, (byte) 1);
        when(packet.readNULString()).thenReturn("u", "");
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        Promise<Void> errorResponse = Promise.promise();
        errorResponse.complete();
        doReturn(errorResponse).when(session).writeErrorEndPacketBySyncInProcessError();

        CommandResolver.handle(session, packet, dispatcher);

        verify(dispatcher, never()).handleChangeUser(any(), any(), any(), anyInt(), any(), any(), any());
        verify(session).writeErrorEndPacketBySyncInProcessError();
    }

    @Test
    void unknownCommandWritesUnsupportedError() {
        MycatSession session = mock(MycatSession.class);
        MySQLPacket packet = packet(0x7F);
        Promise<Void> errorResponse = Promise.promise();
        errorResponse.complete();
        doReturn(errorResponse).when(session)
                .writeErrorEndPacketBySyncInProcessError(MycatErrorCode.ERR_NOT_SUPPORT);

        CommandResolver.handle(session, packet, mock(CommandDispatcher.class));

        verify(session).setLastMessage("无法识别的MYSQL数据包");
        verify(session).writeErrorEndPacketBySyncInProcessError(MycatErrorCode.ERR_NOT_SUPPORT);
    }

    private static MySQLPacket packet(int command) {
        MySQLPacket packet = mock(MySQLPacket.class);
        when(packet.getByte(any(int.class))).thenReturn((byte) command);
        when(packet.readFinished()).thenReturn(false, false);
        when(packet.readByte()).thenReturn((byte) command);
        when(packet.readEOFStringBytes()).thenReturn(new byte[]{'1'});
        when(packet.readFixInt(any(int.class))).thenReturn(0L);
        return packet;
    }
}
