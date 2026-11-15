/**
 * Copyright (C) <2022>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.mysqlclient;

import io.mycat.commands.AbstractMycatDatasourcePool;
import io.mycat.commands.JdbcDatasourcePoolImpl;
import io.mycat.monitor.DatabaseInstanceEntry;
import io.mycat.monitor.InstanceMonitor;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.RemoveAbandonedTimeoutConnectionImpl;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.Future;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MycatNativeDatasourcePool extends AbstractMycatDatasourcePool {
    private static final Logger LOGGER = LoggerFactory.getLogger(MycatNativeDatasourcePool.class);

    VertxPoolConnectionImpl vertxPoolConnection;

    public MycatNativeDatasourcePool(VertxPoolConnectionImpl vertxConnectionPool, String targetName) {
        super(targetName);
        this.vertxPoolConnection = vertxConnectionPool;
    }

    @Override
    public Future<NewMycatConnection> getConnection() {
        return vertxPoolConnection.getConnection().map(connection -> {
            DatabaseInstanceEntry stat = DatabaseInstanceEntry.stat(targetName);
            stat.plusCon();
            stat.plusQps();
            return new RemoveAbandonedTimeoutConnectionImpl(new VertxMycatConnectionPool(targetName,connection, vertxPoolConnection){
                long start;

                @Override
                public void onSend() {
                    start = System.currentTimeMillis();
                    onActiveTimestamp(start);
                }

                @Override
                public void onRev() {
                    long end = System.currentTimeMillis();
                    onActiveTimestamp(end);
                    InstanceMonitor.plusPrt(end - start);
                }

                @Override
                public Future<Void> close() {
                    stat.decCon();
                    return super.close();
                }

                @Override
                public Observable<VectorSchemaRoot> prepareQuery(String sql, List<Object> params, BufferAllocator allocator) {
                    // MYCAT CHANGE (I-DEBT-131)：回退腿抽为静态纯组合（借出的 jdbc 连接生命周期集中管理）
                    return prepareQueryFallback(new JdbcDatasourcePoolImpl(targetName).getConnection(), sql, params, allocator);
                }

                @Override
                public Future<List<Object>> call(String sql) {
                    // MYCAT CHANGE (I-DEBT-131)：回退腿抽为静态纯组合（借出的 jdbc 连接生命周期集中管理）
                    return callFallback(new JdbcDatasourcePoolImpl(targetName).getConnection(), sql);
                }
            });
        });
    }

    /**
     * MYCAT CHANGE (I-DEBT-131)：native→JDBC 回退腿 prepareQuery 的连接生命周期管理。
     * 借出的 {@link NewMycatConnection} 必须在 Observable 终结（complete/error/dispose）时归还
     * （close），对齐 {@code VertxMycatConnectionPool.call} 的正确先例；连接借出失败则直接透传错误。
     */
    static Observable<VectorSchemaRoot> prepareQueryFallback(Future<NewMycatConnection> connectionFuture,
                                                             String sql, List<Object> params, BufferAllocator allocator) {
        // I-DEBT-131：借出的 jdbc 连接必须恰好归还一次，覆盖四条终结路径（对齐
        // VertxMycatConnectionPool.call:239-242 的归还先例）：
        //   1) 内层 Observable 正常完结 2) 内层出错 3) 下游提前 dispose（取消） 4) 借出完成前已取消。
        // 注意 ObservableCreate 的外层 dispose 不会自动传递到 create 内部新建的内层订阅——
        // 必须经 emitter.setCancellable 显式联动（dispose 内层订阅 + 归还连接）。
        return Observable.create(emitter -> {
            java.util.concurrent.atomic.AtomicReference<io.reactivex.rxjava3.disposables.Disposable>
                    innerSubscription = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<NewMycatConnection> borrowed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // 归还协约：borrowed CAS 置空保证恰好归还一次（终态后 emitter 自身 dispose 会再触发 cancellable）
            Runnable returnConnection = () -> {
                NewMycatConnection c = borrowed.getAndSet(null);
                if (c != null) {
                    closeQuietly(c);
                }
            };
            emitter.setCancellable(() -> {
                io.reactivex.rxjava3.disposables.Disposable d = innerSubscription.get();
                if (d != null) {
                    d.dispose();
                }
                returnConnection.run();
            });
            connectionFuture.onSuccess(event -> {
                if (emitter.isDisposed()) {
                    // 订阅方在连接借出完成前已取消：直接归还，不得再把查询挂上去
                    closeQuietly(event);
                    return;
                }
                borrowed.set(event);
                io.reactivex.rxjava3.disposables.Disposable d = event.prepareQuery(sql, params, allocator)
                        .subscribe(vectorSchemaRoot -> emitter.onNext(vectorSchemaRoot),
                                throwable -> emitter.onError(throwable),
                                emitter::onComplete);
                innerSubscription.set(d);
                if (emitter.isDisposed()) {
                    // 借出与订阅之间的竞态窗口：补 dispose（cancellable 归还路径随后生效）
                    d.dispose();
                }
            });
            connectionFuture.onFailure(event -> emitter.onError(event));
        });
    }

    /**
     * MYCAT CHANGE (I-DEBT-131)：native→JDBC 回退腿 call 的连接生命周期管理——call 终结
     * （成功/失败）后归还借出的 {@link NewMycatConnection}（onComplete 收口，对齐
     * {@code VertxMycatConnectionPool.call} 先例；原实现 flatMap 后连接被遮蔽遗忘=每调用泄漏一条）。
     */
    static Future<List<Object>> callFallback(Future<NewMycatConnection> connectionFuture, String sql) {
        return connectionFuture.flatMap(connection ->
                connection.call(sql).onComplete(event -> closeQuietly(connection)));
    }

    /**
     * MYCAT CHANGE (I-DEBT-131)：归还连接的静默收口——close 失败仅记 debug（归还腿故障不得
     * 把查询结果/错误炸掉），错误本体已由调用路径透传。
     */
    private static void closeQuietly(NewMycatConnection connection) {
        try {
            connection.close();
        } catch (Throwable e) {
            LOGGER.debug("I-DEBT-131: close fallback jdbc connection failed (degrade silently)", e);
        }
    }

    @Override
    public int getAvailableNumber() {
        return vertxPoolConnection.getAvailableNumber();
    }

    @Override
    public int getUsedNumber() {
        return vertxPoolConnection.getUsedNumber();
    }

    @Override
    public void close() {
        vertxPoolConnection.close();
    }
}
