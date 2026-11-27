package io.mycat.proxy.session;

import io.mycat.command.CommandDispatcher;
import io.mycat.config.ServerConfig;
import io.mycat.config.TimerConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.mockito.Mockito.*;

class MycatSessionManagerIdleTest {

    @Test
    void idleSessionIsClosedAfterConfiguredTimeout() throws Exception {
        ServerConfig config = config(0, 3600, 1);
        MycatSessionManager manager = manager(config);
        MycatSession session = sessionAt(System.currentTimeMillis() - 2000);
        sessions(manager).add(session);

        manager.check();

        verify(session).close(false, "frontend idle timeout");
    }

    @Test
    void activeSessionIsNotClosed() throws Exception {
        MycatSessionManager manager = manager(config(0, 3600, 1));
        MycatSession session = activeSession();
        sessions(manager).add(session);

        manager.check();

        verify(session, never()).close(anyBoolean(), anyString());
    }

    @Test
    void sessionWithoutActivityTimestampIsNotClosed() throws Exception {
        MycatSessionManager manager = manager(config(0, 3600, 1));
        MycatSession session = mock(MycatSession.class);
        sessions(manager).add(session);

        manager.check();

        verify(session, never()).close(anyBoolean(), anyString());
    }

    @Test
    void nonPositiveTimeoutDisablesIdleClose() throws Exception {
        MycatSessionManager manager = manager(config(0, 3600, 0));
        MycatSession session = sessionAt(1);
        sessions(manager).add(session);

        manager.check();

        verify(session, never()).close(anyBoolean(), anyString());
    }

    @Test
    void nonPositivePeriodDisablesScan() throws Exception {
        MycatSessionManager manager = manager(config(0, 0, 1));
        MycatSession session = sessionAt(1);
        sessions(manager).add(session);

        manager.check();

        verify(session, never()).close(anyBoolean(), anyString());
    }

    @Test
    void scanRunsOnlyAfterConfiguredPeriod() throws Exception {
        MycatSessionManager manager = manager(config(0, 3600, 1));
        MycatSession session = activeSession();
        sessions(manager).add(session);
        manager.check();

        when(session.getLastActiveTime()).thenReturn(1L);
        manager.check();

        verify(session, never()).close(anyBoolean(), anyString());
    }

    @Test
    void initialDelayPostponesFirstScan() throws Exception {
        MycatSessionManager manager = manager(config(3600, 1, 1));
        MycatSession session = sessionAt(1);
        sessions(manager).add(session);

        manager.check();

        verify(session, never()).close(anyBoolean(), anyString());
    }

    private static ServerConfig config(long initialDelay, long period, long idleTimeout) {
        ServerConfig config = new ServerConfig();
        config.setIdleTimer(new TimerConfig(initialDelay, period, TimeUnit.SECONDS.name()));
        config.setIdleTimeout(idleTimeout);
        return config;
    }

    private static MycatSessionManager manager(ServerConfig config) {
        Function<MycatSession, CommandDispatcher> dispatcher = session -> null;
        return new MycatSessionManager(dispatcher, config);
    }

    private static MycatSession activeSession() {
        MycatSession session = sessionAt(System.currentTimeMillis());
        when(session.getLastActiveTime()).thenAnswer(invocation -> System.currentTimeMillis());
        return session;
    }

    private static MycatSession sessionAt(long lastActiveTime) {
        MycatSession session = mock(MycatSession.class);
        when(session.getLastActiveTime()).thenReturn(lastActiveTime);
        when(session.getProcessState()).thenReturn(ProcessState.READY);
        when(session.writeQueue()).thenReturn(new ConcurrentLinkedQueue<>());
        return session;
    }

    @Test
    void busySessionIsNotClosedByLastIoTimeout() throws Exception {
        MycatSessionManager manager = manager(config(0, 3600, 1));
        MycatSession session = sessionAt(System.currentTimeMillis() - 2000);
        when(session.getProcessState()).thenReturn(ProcessState.DOING);
        sessions(manager).add(session);

        manager.check();

        verify(session, never()).close(anyBoolean(), anyString());
    }

    @Test
    void queuedDoneSessionIsNotClosedUntilWriteQueueDrains() throws Exception {
        MycatSessionManager manager = manager(config(0, 3600, 1));
        MycatSession session = sessionAt(System.currentTimeMillis() - 2000);
        when(session.getProcessState()).thenReturn(ProcessState.DONE);
        when(session.writeQueue()).thenReturn(new ConcurrentLinkedQueue<>(java.util.List.of(java.nio.ByteBuffer.allocate(1))));
        sessions(manager).add(session);

        manager.check();

        verify(session, never()).close(anyBoolean(), anyString());
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedDeque<MycatSession> sessions(MycatSessionManager manager) throws Exception {
        Field field = MycatSessionManager.class.getDeclaredField("mycatSessions");
        field.setAccessible(true);
        return (ConcurrentLinkedDeque<MycatSession>) field.get(manager);
    }
}
