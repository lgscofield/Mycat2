/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.mysqlclient;

import io.mycat.newquery.NewMycatConnection;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.observers.TestObserver;
import io.vertx.core.Future;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * I-DEBT-131：native→JDBC 回退腿连接泄漏锁。
 *
 * <p>背景：{@code MycatNativeDatasourcePool} 的匿名 {@code VertxMycatConnectionPool} 覆写里，
 * prepareQuery/call 两条回退腿各自 {@code new JdbcDatasourcePoolImpl(targetName).getConnection()}
 * 借出一条 jdbc 连接，但 Observable 终结/Future 完成后<b>从不 close</b>——每条回退查询泄漏一条
 * 连接（mock 断言口径：close 调用次数 = 借出次数）。正确先例：
 * {@code VertxMycatConnectionPool.call:239-242}（{@code call.onComplete(event -> connection.close())}），
 * 该先例恰被本类的匿名覆写遮蔽。
 *
 * <p>锁策略：回退腿已抽为静态纯组合 {@code prepareQueryFallback}/{@code callFallback}
 * （注入 {@code Future<NewMycatConnection>}），用 Mockito 假连接断言 close 次数——
 * 矩阵：{正常完结 / 上游出错 / 订阅方提前 dispose} × close 恰一次；借出失败不 close、错误透传。
 * 完全离线（无真池）。
 */
public class NativeFallbackCloseTest {

    private static final List<Object> PARAMS = Collections.singletonList(1);

    private static Future<NewMycatConnection> borrowed(NewMycatConnection connection) {
        return Future.succeededFuture(connection);
    }

    private static NewMycatConnection connection(Observable<VectorSchemaRoot> observable, AtomicInteger closeCounter) {
        NewMycatConnection connection = Mockito.mock(NewMycatConnection.class);
        Mockito.when(connection.prepareQuery(Mockito.anyString(), Mockito.anyList(),
                        Mockito.<org.apache.arrow.memory.BufferAllocator>any()))
                .thenReturn(observable);
        Mockito.when(connection.close()).thenAnswer(inv -> {
            closeCounter.incrementAndGet();
            return Future.succeededFuture();
        });
        Mockito.when(connection.call(Mockito.anyString()))
                .thenReturn(Future.succeededFuture(Collections.singletonList("v")));
        return connection;
    }

    private static AtomicInteger newCounter() {
        return new AtomicInteger();
    }

    @Test
    void prepareQuery_completes_closesBorrowedConnectionOnce() {
        AtomicInteger closeCount = newCounter();
        NewMycatConnection connection = connection(Observable.empty(), closeCount);
        TestObserver<VectorSchemaRoot> observer = MycatNativeDatasourcePool
                .prepareQueryFallback(borrowed(connection), "sql", PARAMS, null).test();
        observer.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
        observer.assertComplete();

        Assertions.assertEquals(1, closeCount.get(),
                "回退腿 Observable 正常完结必须归还借出连接（close=1）——现码不归还=泄漏（I-DEBT-131）");
    }

    @Test
    void prepareQuery_upstreamError_closesBorrowedConnectionOnceAndPropagates() {
        AtomicInteger closeCount = newCounter();
        RuntimeException boom = new RuntimeException("boom");
        NewMycatConnection connection = connection(Observable.error(boom), closeCount);
        TestObserver<VectorSchemaRoot> observer = MycatNativeDatasourcePool
                .prepareQueryFallback(borrowed(connection), "sql", PARAMS, null).test();
        observer.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
        observer.assertError(boom);

        Assertions.assertEquals(1, closeCount.get(),
                "回退腿出错同样必须归还借出连接（close=1），不得错误路径漏还");
    }

    @Test
    void prepareQuery_disposedEarly_closesBorrowedConnectionOnce() {
        AtomicInteger closeCount = newCounter();
        NewMycatConnection connection = connection(Observable.never(), closeCount);
        TestObserver<VectorSchemaRoot> observer = MycatNativeDatasourcePool
                .prepareQueryFallback(borrowed(connection), "sql", PARAMS, null).test();
        observer.dispose();

        Assertions.assertEquals(1, closeCount.get(),
                "订阅方提前 dispose（下游取消）必须归还借出连接——现码此路必泄漏");
    }

    @Test
    void prepareQuery_borrowFails_propagatesWithoutClose() {
        AtomicInteger closeCount = newCounter();
        RuntimeException boom = new RuntimeException("pool exhausted");
        NewMycatConnection connection = connection(Observable.empty(), closeCount); // 不该被触达
        TestObserver<VectorSchemaRoot> observer = MycatNativeDatasourcePool
                .prepareQueryFallback(Future.failedFuture(boom), "sql", PARAMS, null).test();
        observer.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
        observer.assertError(boom);
        Assertions.assertEquals(0, closeCount.get(), "借出失败无连接可还，不得误 close");
    }

    @Test
    void call_success_closesBorrowedConnectionOnce() {
        AtomicInteger closeCount = newCounter();
        NewMycatConnection connection = connection(Observable.empty(), closeCount);
        MycatNativeDatasourcePool.callFallback(borrowed(connection), "sql")
                .onSuccess(v -> { });
        Assertions.assertEquals(1, closeCount.get(),
                "call 回退腿成功终结必须归还借出连接（对齐 VertxMycatConnectionPool.call 先例）");
    }

    @Test
    void call_failure_stillClosesBorrowedConnectionOnce() {
        AtomicInteger closeCount = newCounter();
        NewMycatConnection connection = connection(Observable.empty(), closeCount);
        RuntimeException boom = new RuntimeException("call failed");
        Mockito.when(connection.call(Mockito.anyString())).thenReturn(Future.failedFuture(boom));

        MycatNativeDatasourcePool.callFallback(borrowed(connection), "sql")
                .onFailure(v -> { });
        Assertions.assertEquals(1, closeCount.get(),
                "call 回退腿失败终结同样必须归还借出连接");
    }
}
