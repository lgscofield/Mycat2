package io.mycat.vertx;

import io.mycat.MycatDataContext;
import io.mycat.config.MycatServerConfig;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VertxMycatServerIdleCleanupTest {

    @Test
    void unexpectedSocketCloseKillsSessionResources() {
        MycatDataContext context = mock(MycatDataContext.class);
        NetSocket socket = mock(NetSocket.class);
        VertxSessionImpl session = new VertxSessionImpl(context, socket, null);
        VertxMycatServer.MycatSessionManager manager = new VertxMycatServer.MycatSessionManager(
                new MycatServerConfig());
        ArgumentCaptor<Handler<Void>> closeHandler = closeHandlerCaptor();
        manager.addSession(session);
        verify(socket).closeHandler(closeHandler.capture());

        closeHandler.getValue().handle(null);

        verify(context).kill();
    }

    @Test
    void normalCloseDoesNotKillResourcesAgainWhenSocketCloseEventArrives() {
        MycatDataContext context = mock(MycatDataContext.class);
        NetSocket socket = mock(NetSocket.class);
        when(socket.close()).thenReturn(Future.succeededFuture());
        VertxSessionImpl session = new VertxSessionImpl(context, socket, null);
        VertxMycatServer.MycatSessionManager manager = new VertxMycatServer.MycatSessionManager(
                new MycatServerConfig());
        ArgumentCaptor<Handler<Void>> closeHandler = closeHandlerCaptor();
        manager.addSession(session);
        verify(socket).closeHandler(closeHandler.capture());

        session.close();
        clearInvocations(context);
        closeHandler.getValue().handle(null);

        verify(context, never()).kill();
        verify(context, never()).close();
    }

    @Test
    void contextCloseFailureStillClosesSocketAndAllowsCleanupRetry() {
        MycatDataContext context = mock(MycatDataContext.class);
        NetSocket socket = mock(NetSocket.class);
        when(socket.close()).thenReturn(Future.succeededFuture());
        RuntimeException failure = new RuntimeException("resource cleanup failed");
        doThrow(failure).when(context).close();
        VertxSessionImpl session = new VertxSessionImpl(context, socket, null);
        VertxMycatServer.MycatSessionManager manager = new VertxMycatServer.MycatSessionManager(
                new MycatServerConfig());
        ArgumentCaptor<Handler<Void>> closeHandler = closeHandlerCaptor();
        manager.addSession(session);
        verify(socket).closeHandler(closeHandler.capture());

        assertThrows(RuntimeException.class, session::close);
        verify(socket).close();
        assertFalse(session.isClosed());

        closeHandler.getValue().handle(null);

        verify(context).kill();
        assertTrue(session.isClosed());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Handler<Void>> closeHandlerCaptor() {
        return ArgumentCaptor.forClass((Class) Handler.class);
    }
}
