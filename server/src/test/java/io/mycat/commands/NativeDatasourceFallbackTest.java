package io.mycat.commands;

import io.mycat.config.DatasourceConfig;
import io.mycat.newquery.NewMycatConnection;
import io.vertx.core.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NativeDatasourceFallbackTest {

    private static DatasourceConfig nativeConfig() {
        DatasourceConfig config = new DatasourceConfig();
        config.setName("native_ds");
        config.setType(DatasourceConfig.DatasourceType.NATIVE.name());
        return config;
    }

    @Test
    void failedProbeClosesNativePoolBeforeUsingJdbcFallback() {
        MycatDatasourcePool nativePool = mock(MycatDatasourcePool.class);
        MycatDatasourcePool jdbcPool = mock(MycatDatasourcePool.class);
        when(nativePool.getConnection()).thenReturn(Future.failedFuture("probe failed"));
        when(jdbcPool.getTargetName()).thenReturn("native_ds");
        NewMycatConnection fallback = mock(NewMycatConnection.class);
        when(jdbcPool.getConnection()).thenReturn(Future.succeededFuture(fallback));

        try (MockedStatic<MycatMySQLManagerImpl> factory = mockStatic(MycatMySQLManagerImpl.class)) {
            factory.when(() -> MycatMySQLManagerImpl.createNativeDatasourcePool(any(), eq("native_ds")))
                    .thenReturn(nativePool);
            factory.when(() -> MycatMySQLManagerImpl.createJdbcDatasourcePool("native_ds"))
                    .thenReturn(jdbcPool);

            MycatMySQLManagerImpl manager = new MycatMySQLManagerImpl(
                    Collections.singletonList(nativeConfig()));

            verify(nativePool).close();
            verify(jdbcPool, never()).close();
            Assertions.assertSame(fallback, manager.getConnection("native_ds").result());
            InOrder order = inOrder(nativePool, jdbcPool);
            order.verify(nativePool).close();
            order.verify(jdbcPool).getConnection();
        }
    }

    @Test
    void nativeCloseFailureDoesNotPreventJdbcFallback() {
        MycatDatasourcePool nativePool = mock(MycatDatasourcePool.class);
        MycatDatasourcePool jdbcPool = mock(MycatDatasourcePool.class);
        when(nativePool.getConnection()).thenReturn(Future.failedFuture("probe failed"));
        when(jdbcPool.getTargetName()).thenReturn("native_ds");
        NewMycatConnection fallback = mock(NewMycatConnection.class);
        when(jdbcPool.getConnection()).thenReturn(Future.succeededFuture(fallback));
        doThrow(new IllegalStateException("close failed")).when(nativePool).close();

        try (MockedStatic<MycatMySQLManagerImpl> factory = mockStatic(MycatMySQLManagerImpl.class)) {
            factory.when(() -> MycatMySQLManagerImpl.createNativeDatasourcePool(any(), eq("native_ds")))
                    .thenReturn(nativePool);
            factory.when(() -> MycatMySQLManagerImpl.createJdbcDatasourcePool("native_ds"))
                    .thenReturn(jdbcPool);

            MycatMySQLManagerImpl manager = new MycatMySQLManagerImpl(
                    Collections.singletonList(nativeConfig()));

            verify(nativePool).close();
            Assertions.assertSame(fallback, manager.getConnection("native_ds").result());
        }
    }

    @Test
    void successfulProbeKeepsNativePoolOpen() {
        MycatDatasourcePool nativePool = mock(MycatDatasourcePool.class);
        NewMycatConnection probe = mock(NewMycatConnection.class);
        when(nativePool.getConnection()).thenReturn(Future.succeededFuture(probe));
        when(probe.close()).thenReturn(Future.succeededFuture());
        when(nativePool.getTargetName()).thenReturn("native_ds");

        try (MockedStatic<MycatMySQLManagerImpl> factory = mockStatic(MycatMySQLManagerImpl.class)) {
            factory.when(() -> MycatMySQLManagerImpl.createNativeDatasourcePool(any(), eq("native_ds")))
                    .thenReturn(nativePool);

            new MycatMySQLManagerImpl(Collections.singletonList(nativeConfig()));

            verify(probe).close();
            verify(nativePool, never()).close();
        }
    }
}
