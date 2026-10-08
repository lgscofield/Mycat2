/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import io.mycat.MycatDataContext;
import io.mycat.TransactionSession;
import io.mycat.api.collector.MySQLColumnDef;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.proxy.session.MySQLServerSession;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.core.Future;
import io.vertx.core.Promise;

/**
 * I-DEBT-30 面①：流式结果集错误终结契约（回归锁）。
 *
 * 驱动真实 {@link ReceiverImpl.MysqlPayloadObjectObserver}，用 mock 前端会话观测其终态动作：
 * - 列头未上线遇错 → 不关连接，promise 失败（交顶层 recover 发合法 ERR，会话存活）；
 * - 列头已上线遇错 → 关闭前端连接（对齐真 MySQL "Lost connection during query"），不再补裸 ERR；
 * - onComplete 成功终结路径不变。
 */
class MysqlPayloadObjectObserverErrorTest {

    /** 与生产同款元数据（走 generateAllColumnDefPayload 的 else 分支，同 BaseRowObservable 路径）。 */
    private static MycatRowMetaData oneIntColumnMeta() {
        return ResultSetBuilder.create()
                .addColumnInfo("t", "id", Types.INTEGER, 0, 0)
                .build().getMetaData();
    }

    private static ReceiverImpl.MysqlPayloadObjectObserver newObserver(Promise<Void> promise,
            MySQLServerSession session) {
        ReceiverImpl.MysqlPayloadObjectObserver observer =
                new ReceiverImpl.MysqlPayloadObjectObserver(promise, false, false, session);
        observer.onSubscribe(mock(Disposable.class));
        return observer;
    }

    @Test
    void onErrorBeforeColumnDefDoesNotCloseConnection() {
        MySQLServerSession session = mock(MySQLServerSession.class);
        Promise<Void> promise = Promise.promise();
        ReceiverImpl.MysqlPayloadObjectObserver observer = newObserver(promise, session);

        observer.onError(new RuntimeException("boom"));

        verify(session, never()).close(anyBoolean(), any());
        assertTrue(promise.future().failed());
    }

    @Test
    void onErrorAfterColumnDefClosesConnection() {
        MySQLServerSession session = mock(MySQLServerSession.class);
        Promise<Void> promise = Promise.promise();
        ReceiverImpl.MysqlPayloadObjectObserver observer = newObserver(promise, session);

        observer.onNext(MySQLColumnDef.of(oneIntColumnMeta()));
        observer.onError(new RuntimeException("mid-stream"));

        // 列头已上线后一次关闭；若 onNext 内部因 fixture 抛错自触 onError，会变 times(2) 从而暴露。
        verify(session, times(1)).close(eq(false), any());
    }

    @Test
    void onCompleteStillTerminatesNormally() {
        MySQLServerSession session = mock(MySQLServerSession.class);
        MycatDataContext dataContext = mock(MycatDataContext.class);
        TransactionSession transactionSession = mock(TransactionSession.class);
        when(session.getDataContext()).thenReturn(dataContext);
        when(dataContext.getTransactionSession()).thenReturn(transactionSession);
        when(transactionSession.closeStatementState()).thenReturn(Future.succeededFuture());
        when(session.writeRowEndPacket(anyBoolean(), anyBoolean())).thenReturn(Future.succeededFuture());

        Promise<Void> promise = Promise.promise();
        ReceiverImpl.MysqlPayloadObjectObserver observer = newObserver(promise, session);

        observer.onNext(MySQLColumnDef.of(oneIntColumnMeta()));
        observer.onComplete();

        verify(session, never()).close(anyBoolean(), any());
        verify(session, times(1)).writeRowEndPacket(anyBoolean(), anyBoolean());
        assertFalse(promise.future().failed());
    }
}
