package io.mycat.proxy.session;

import io.mycat.MycatDataContext;
import io.mycat.MycatDataContextEnum;
import io.mycat.TransactionSession;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.mycat.runtime.MycatDataContextImpl;
import io.vertx.core.Future;

import java.util.HashMap;

public final class SessionResetter {

    private SessionResetter() {
    }

    public static Future<Void> reset(MycatDataContext context) {
        try {
            TransactionSession transaction = context.getTransactionSession();
            return transaction.rollback()
                    .compose(v -> transaction.closeStatementState())
                    .compose(v -> releaseAllLocks(context))
                    .compose(v -> {
                        resetState(context, transaction);
                        return Future.succeededFuture();
                    });
        } catch (Throwable throwable) {
            return Future.failedFuture(throwable);
        }
    }

    private static Future<Void> releaseAllLocks(MycatDataContext context) {
        if (context instanceof MycatDataContextImpl) {
            return Future.fromCompletionStage(((MycatDataContextImpl) context).releaseAllLocks());
        }
        return Future.succeededFuture();
    }

    private static void resetState(MycatDataContext context, TransactionSession transaction) {
        int isolationIndex = context.getUser().getUserConfig().getIsolation();
        MySQLIsolation isolation = MySQLIsolation.values()[isolationIndex - 1];
        transaction.setReadOnly(false);

        context.setAutoCommit(true);
        context.setIsolation(isolation);
        context.setVariable(MycatDataContextEnum.IS_READ_ONLY, false);
        context.setInTransaction(false);
        context.setVariable(MycatDataContextEnum.DEFAULT_SCHEMA, null);
        context.setSessionSqlMode(null);
        context.setSessionMaxErrorCount(null);
        context.setVariable(MycatDataContextEnum.SELECT_LIMIT, -1L);
        context.setVariable(MycatDataContextEnum.NET_WRITE_TIMEOUT, -1L);
        context.setVariable(MycatDataContextEnum.CHARSET_SET_RESULT, null);
        context.setVariable(MycatDataContextEnum.IS_MULTI_STATEMENT_SUPPORT, 0);
        context.setVariable(MycatDataContextEnum.IS_LOCAL_IN_FILE_REQUEST_STATE, 0);
        context.setDebug(false);
        context.setVector(false);
        context.setHolder(null);
        context.setReadyToCloseSQL(null);
        context.setAffectedRows(0);
        context.setLastInsertId(0);
        context.setLastErrorCode(0);
        context.setLastMessage((String) null);
        context.clearWarnings();
        context.getCancelFlag().set(false);
        context.getPrepareInfo().clear();
        context.putProcessStateMap(new HashMap<>());

        if (context instanceof MycatDataContextImpl) {
            MycatDataContextImpl impl = (MycatDataContextImpl) context;
            impl.setPreviousWarningCount(0);
            impl.setPreviousErrorCount(0);
            impl.setSqlState("HY000");
            impl.setEmitter(null);
            impl.setObservable(null);
        }
    }
}
