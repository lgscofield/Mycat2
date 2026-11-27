package io.mycat.vertx;

import io.mycat.MycatDataContextEnum;
import io.mycat.MycatUser;
import io.mycat.PreparedStatement;
import io.mycat.TransactionSession;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.config.UserConfig;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.mycat.runtime.MycatDataContextImpl;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VertxSessionImplResetTest {

    static class Ctx extends MycatDataContextImpl {
        @Override
        public void switchTransaction(TransactionType transactionType) {
        }
    }

    private VertxSessionImpl session(Ctx context) {
        return new VertxSessionImpl(context, mock(NetSocket.class), null);
    }

    @Test
    void resetRollsBackAndRestoresSessionStateWhilePreservingConnectionFacts() {
        Ctx context = new Ctx();
        TransactionSession transaction = mock(TransactionSession.class);
        when(transaction.rollback()).thenReturn(Future.succeededFuture());
        when(transaction.closeStatementState()).thenReturn(Future.succeededFuture());
        when(transaction.transactionType()).thenReturn(TransactionType.JDBC_TRANSACTION_TYPE);
        context.setTransactionSession(transaction);

        UserConfig userConfig = new UserConfig();
        userConfig.setIsolation(2);
        MycatUser user = MycatUser.builder().userName("alice").userConfig(userConfig).build();
        context.setUser(user);
        context.setServerCapabilities(123);
        context.setCharset(33, "utf8", StandardCharsets.UTF_8);
        context.setClientConnectAttrs(Map.of("_client_name", "probe"));
        context.setSslSessionInfo("cipher", "version");

        context.setDefaultSchema("app");
        context.setSessionSqlMode("STRICT_ALL_TABLES");
        context.setSessionMaxErrorCount(7);
        context.setSelectLimit(12);
        context.setNetWriteTimeout(34);
        context.setCharsetSetResult("utf8mb4");
        context.setReadOnly(true);
        context.setVariable(MycatDataContextEnum.IS_MULTI_STATEMENT_SUPPORT, 1);
        context.setVariable(MycatDataContextEnum.IS_LOCAL_IN_FILE_REQUEST_STATE, 1);
        context.setVariable(MycatDataContextEnum.IS_IN_TRANSCATION, true);
        context.setDebug(true);
        context.setVector(true);
        context.setHolder(new Object());
        context.setReadyToCloseSQL("select 1");
        context.setAffectedRows(9);
        context.setLastInsertId(10);
        context.setLastErrorCode(1064);
        context.setLastMessage("error");
        context.addWarning(new WarningMessage("Error", 1064, "error"));
        context.setPreviousWarningCount(4);
        context.setPreviousErrorCount(2);
        context.getCancelFlag().set(true);
        context.getPrepareInfo().put(1L, mock(PreparedStatement.class));
        Map<String, Object> processState = new HashMap<>();
        processState.put("SCHEMA", "stale");
        context.putProcessStateMap(processState);

        Future<Void> result = session(context).resetSession();

        assertTrue(result.succeeded());
        verify(transaction).rollback();
        verify(transaction).closeStatementState();
        verify(transaction).setAutocommit(true);
        verify(transaction).setTransactionIsolation(MySQLIsolation.READ_COMMITTED);
        verify(transaction).setReadOnly(false);
        assertNull(context.getDefaultSchema());
        assertNull(context.getSessionSqlMode());
        assertNull(context.getSessionMaxErrorCount());
        assertEquals(-1, context.getSelectLimit());
        assertEquals(-1, context.getNetWriteTimeout());
        assertNull(context.getCharsetSetResult());
        assertFalse(context.isReadOnly());
        assertEquals(0, context.getVariable(MycatDataContextEnum.IS_MULTI_STATEMENT_SUPPORT));
        assertEquals(0, context.getVariable(MycatDataContextEnum.IS_LOCAL_IN_FILE_REQUEST_STATE));
        assertFalse(context.isInTransaction());
        assertFalse(context.isDebug());
        assertFalse(context.isVector());
        assertNull(context.getHolder());
        assertNull(context.getReadyToCloseSQL());
        assertEquals(0, context.getAffectedRows());
        assertEquals(0, context.getLastInsertId());
        assertEquals(0, context.getLastErrorCode());
        assertNull(context.getLastMessage());
        assertEquals(0, context.getWarningCount());
        assertEquals(0, context.getPreviousWarningCount());
        assertEquals(0, context.getPreviousErrorCount());
        assertFalse(context.getCancelFlag().get());
        assertTrue(context.getPrepareInfo().isEmpty());
        assertTrue(context.getProcessStateMap().isEmpty());

        assertSame(user, context.getUser());
        assertEquals(123, context.getServerCapabilities());
        assertEquals(33, context.getCharsetIndex());
        assertEquals(Map.of("_client_name", "probe"), context.getClientConnectAttrs());
        assertEquals("cipher", context.getSslCipher());
        assertEquals("version", context.getSslVersion());
        assertSame(transaction, context.getTransactionSession());
    }

    @Test
    void statementStateFailurePreventsSocketWrite() {
        Ctx context = new Ctx();
        TransactionSession transaction = mock(TransactionSession.class);
        RuntimeException failure = new RuntimeException("statement cleanup failed");
        when(transaction.closeStatementState()).thenReturn(Future.failedFuture(failure));
        context.setTransactionSession(transaction);
        NetSocket socket = mock(NetSocket.class);
        when(socket.write(any(Buffer.class))).thenReturn(Future.succeededFuture());
        VertxMycatServer.MycatSessionManager manager = mock(VertxMycatServer.MycatSessionManager.class);
        VertxSessionImpl session = new VertxSessionImpl(context, socket, manager);

        Future<Void> result = session.writeBytes(new byte[]{1}, true);

        assertTrue(result.failed());
        assertSame(failure, result.cause());
        verify(socket, never()).write(any(Buffer.class));
    }

    @Test
    void rollbackFailureLeavesSessionStateUntouched() {
        Ctx context = new Ctx();
        TransactionSession transaction = mock(TransactionSession.class);
        Promise<Void> rollback = Promise.promise();
        when(transaction.rollback()).thenReturn(rollback.future());
        context.setTransactionSession(transaction);
        context.setDefaultSchema("app");

        Future<Void> result = session(context).resetSession();

        assertFalse(result.isComplete());
        assertEquals("app", context.getDefaultSchema());
        RuntimeException failure = new RuntimeException("rollback failed");
        rollback.fail(failure);
        assertTrue(result.failed());
        assertSame(failure, result.cause());
        assertEquals("app", context.getDefaultSchema());
        verify(transaction, never()).closeStatementState();
    }
}
