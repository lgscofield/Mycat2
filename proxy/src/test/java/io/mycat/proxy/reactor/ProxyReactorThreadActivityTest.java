package io.mycat.proxy.reactor;

import io.mycat.buffer.ReactorBufferPool;
import io.mycat.proxy.handler.NIOHandler;
import io.mycat.proxy.session.MycatSession;
import io.mycat.proxy.session.SessionManager.FrontSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyReactorThreadActivityTest {

    private final MycatReactorThread reactor;

    ProxyReactorThreadActivityTest() throws IOException {
        reactor = new MycatReactorThread(mock(ReactorBufferPool.class),
                mock(FrontSessionManager.class));
    }

    @AfterEach
    void closeSelector() throws IOException {
        reactor.getSelector().close();
    }

    @Test
    void handledReadRefreshesActivityBeforeInvokingHandler() throws IOException {
        MycatSession session = mock(MycatSession.class);
        NIOHandler handler = mock(NIOHandler.class);
        when(session.getCurNIOHandler()).thenReturn(handler);
        SelectionKey key = key(session);

        reactor.processReadKey(key);

        InOrder order = inOrder(session, handler);
        order.verify(session).updateLastActiveTime();
        order.verify(handler).onSocketRead(session);
    }

    @Test
    void directReadRefreshesActivityWhenBytesArrive() throws IOException {
        MycatSession session = mock(MycatSession.class);
        SocketChannel channel = mock(SocketChannel.class);
        when(session.channel()).thenReturn(channel);
        when(channel.read(any(ByteBuffer.class))).thenReturn(1);

        reactor.processReadKey(key(session));

        verify(session).updateLastActiveTime();
    }

    @Test
    void emptyDirectReadDoesNotRefreshActivity() throws IOException {
        MycatSession session = mock(MycatSession.class);
        SocketChannel channel = mock(SocketChannel.class);
        when(session.channel()).thenReturn(channel);
        when(channel.read(any(ByteBuffer.class))).thenReturn(0);

        reactor.processReadKey(key(session));

        verify(session, never()).updateLastActiveTime();
    }

    private static SelectionKey key(MycatSession session) {
        SelectionKey key = mock(SelectionKey.class);
        when(key.attachment()).thenReturn(session);
        when(key.channel()).thenReturn(mock(SocketChannel.class));
        return key;
    }
}
