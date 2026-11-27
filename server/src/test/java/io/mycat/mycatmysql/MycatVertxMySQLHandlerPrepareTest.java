package io.mycat.mycatmysql;

import io.mycat.MySQLPacketUtil;
import io.mycat.MycatDataContext;
import io.mycat.MetaClusterCurrent;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.prototypeserver.mysql.PrototypeService;
import io.mycat.util.MySQLSqlTextCodec;
import io.vertx.core.Future;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MycatVertxMySQLHandlerPrepareTest {

    private Map<Class, Object> previousContext;
    private MycatDataContext dataContext;
    private MycatVertxMysqlSession session;
    private MycatVertxMySQLHandler handler;
    private final List<byte[]> payloads = new ArrayList<>();
    private final List<Boolean> endFlags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        previousContext = MetaClusterCurrent.copyContext();
        PrototypeService prototypeService = mock(PrototypeService.class);
        ResultSetBuilder fields = ResultSetBuilder.create();
        fields.addColumnInfo("value", JDBCType.INTEGER);
        when(prototypeService.getMycatRowMetaDataForPrepareStatement(anyString(), anyString()))
                .thenReturn(Optional.of(fields.build().getMetaData()));
        MetaClusterCurrent.register(PrototypeService.class, prototypeService);

        dataContext = mock(MycatDataContext.class);
        when(dataContext.getDefaultSchema()).thenReturn("mycat");
        when(dataContext.nextPrepareStatementId()).thenReturn(7L);
        when(dataContext.getPrepareInfo()).thenReturn(new HashMap<>());
        session = mock(MycatVertxMysqlSession.class);
        when(session.getDataContext()).thenReturn(dataContext);
        when(session.getSocket()).thenReturn(mock(NetSocket.class));
        when(session.getWarningCount()).thenReturn(0);
        when(session.getServerStatusValue()).thenReturn(2);
        when(session.writeColumnEndPacket(false)).thenAnswer(invocation -> {
            payloads.add(MySQLPacketUtil.generateEof(0, 2));
            endFlags.add(false);
            return Future.succeededFuture();
        });
        when(session.writeBytes(any(byte[].class), anyBoolean())).thenAnswer(invocation -> {
            payloads.add(invocation.getArgument(0));
            endFlags.add(invocation.getArgument(1));
            return Future.succeededFuture();
        });
        handler = new MycatVertxMySQLHandler(session);
    }

    @AfterEach
    void restoreContext() {
        MetaClusterCurrent.register(previousContext);
    }

    @Test
    void intermediateWriteFailureStopsPrepareAndReturnsOriginalCause() throws Exception {
        when(session.isDeprecateEOF()).thenReturn(true);
        RuntimeException failure = new RuntimeException("socket write failed");
        when(session.writeBytes(any(byte[].class), anyBoolean())).thenAnswer(invocation -> {
            payloads.add(invocation.getArgument(0));
            endFlags.add(invocation.getArgument(1));
            return payloads.size() == 2 ? Future.failedFuture(failure) : Future.succeededFuture();
        });

        Future<Void> result = prepare("update t set v=? where id=?");

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        assertEquals(2, payloads.size());
    }

    @Test
    void parameterAndColumnDefinitionsUseBothEofPacketsInTraditionalMode() throws Exception {
        Future<Void> result = prepare("select ?");

        assertTrue(result.succeeded(), () -> String.valueOf(result.cause()));
        assertEquals(5, payloads.size());
        assertArrayEquals(MySQLPacketUtil.generateEof(0, 2), payloads.get(2));
        assertEquals(List.of(false, false, false, false, true), endFlags);
        verify(session).writeColumnEndPacket(false);
    }

    @Test
    void parameterAndColumnDefinitionsSkipEofPacketsWhenDeprecated() throws Exception {
        when(session.isDeprecateEOF()).thenReturn(true);

        Future<Void> result = prepare("select ?");

        assertTrue(result.succeeded(), () -> String.valueOf(result.cause()));
        assertEquals(3, payloads.size());
        assertEquals(List.of(false, false, true), endFlags);
        verify(session, never()).writeColumnEndPacket(false);
    }

    @SuppressWarnings("unchecked")
    private Future<Void> prepare(String sql) throws Exception {
        Method method = MycatVertxMySQLHandler.class.getDeclaredMethod(
                "handlePrepareStatement", byte[].class, MycatVertxMysqlSession.class);
        method.setAccessible(true);
        return (Future<Void>) method.invoke(handler, MySQLSqlTextCodec.encode(sql), session);
    }
}
