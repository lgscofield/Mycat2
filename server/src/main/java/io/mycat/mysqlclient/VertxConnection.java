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

import io.mycat.beans.mycat.WarningMessage;
import io.mycat.protocol.mysql.type.MySQLServerStatusFlags;
import io.mycat.mysqlclient.command.OkCommand;
import io.mycat.mysqlclient.command.QueryCommand;
import io.mycat.mysqlclient.command.ResponseBufferCommand;
import io.mycat.mysqlclient.decoder.ByteArrayDecoder;
import io.mycat.mysqlclient.decoder.StringArrayDecoder;
import io.mycat.newquery.NewMycatConnectionConfig;
import io.mycat.newquery.SqlResult;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;


@Getter
public class VertxConnection {
    final static Logger logger = LoggerFactory.getLogger(VertxConnection.class);
    final NetSocket netSocket;
    private long connectionId;
    private VertxPoolConnectionImpl.Config config;
    private VertxConnectionPool vertxConnectionPool;
    private int serverstatus;
    Future<Void> future = Future.succeededFuture();


    public VertxConnection(NetSocket netSocket, long connectionId, VertxPoolConnectionImpl.Config config, VertxConnectionPool vertxConnectionPool) {
        this.netSocket = netSocket;
        this.connectionId = connectionId;
        this.config = config;
        this.vertxConnectionPool = vertxConnectionPool;
        checkException();
    }

    private void checkException() {
        this.netSocket.exceptionHandler(event -> {
            logger.error("", event);
            VertxConnection.this.vertxConnectionPool.kill(VertxConnection.this);
        });
    }

    public Future<SqlResult> update(String sql) {
        Promise<SqlResult> caller = Promise.promise();
        synchronized (VertxConnection.this) {
            if (future.isComplete()) {
                future = Future.succeededFuture();
            }
            Promise<SqlResult> inner = Promise.promise();
            // I-DEBT-69 Blade G（刀2）：DML 主句先入队；队尾 future 只等主句（使明细往返能接力）。
            Future<SqlResult> dml = future.transform(unused -> {
                try {
                    checkException();
                    OkCommand queryCommand = new OkCommand(sql, netSocket, inner);
                    inner.future().onComplete(event -> {
                        VertxConnection.this.serverstatus = queryCommand.serverstatus;
                    });
                    netSocket.handler(queryCommand);
                    queryCommand.write();
                    return inner.future();
                } catch (Throwable throwable) {
                    inner.tryFail(throwable);
                    return Future.failedFuture(throwable);
                }
            });
            future = dml.mapEmpty();
            // 告警明细回折：仅 warnings>0 路径，作为同一串行队列的下一条命令接力（生产
            // 已验证的链式模式，避免在 OkCommand.handle0 同步栈内重入换装 handler）。
            // caller 等 DML+明细，保证连接归还前明细已落入 SqlResult；取不到明细如实空列表。
            dml.onComplete(main -> {
                if (main.failed()) {
                    caller.tryFail(main.cause());
                    return;
                }
                SqlResult result = main.result();
                if (result == null || result.getBackendWarningCount() <= 0) {
                    caller.tryComplete(result);
                    return;
                }
                queryAsObjectArray("SHOW WARNINGS").toList().subscribe(rows -> {
                    result.setWarnings(convertNativeWarningRows(rows));
                    caller.tryComplete(result);
                }, error -> {
                    logger.warn("NATIVE 池 SHOW WARNINGS 明细往返失败，告警明细如实为空", error);
                    caller.tryComplete(result);
                });
            });
        }
        return caller.future();
    }

    /**
     * SHOW WARNINGS 往返行（Level/Code/Message 三列文本）→ WarningMessage。
     *
     * <p>行不得被静默吞掉：null Level 退 "Warning"，非数字/缺失码退化 0，
     * 缺失消息退化空串；行数与 OK 包计数不一致时如实保留行数，不合成假条目。
     */
    static List<WarningMessage> convertNativeWarningRows(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<WarningMessage> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String level = cellAt(row, 0) != null ? cellText(cellAt(row, 0)) : "Warning";
            int code = 0;
            String codeText = cellAt(row, 1) != null ? cellText(cellAt(row, 1)).trim() : null;
            if (codeText != null && !codeText.isEmpty()) {
                try {
                    code = Integer.parseInt(codeText);
                } catch (NumberFormatException ignored) {
                    // 非数字码退化 0，不得抛
                }
            }
            String message = cellAt(row, 2) != null ? cellText(cellAt(row, 2)) : "";
            result.add(new WarningMessage(level, code, message));
        }
        return result;
    }

    private static Object cellAt(Object[] row, int idx) {
        return row != null && row.length > idx ? row[idx] : null;
    }

    /**
     * 单元格文本还原：真库 SHOW WARNINGS 的 Code/Level 等数值或 binary 列（charset 63），
     * {@code StringArrayDecoder} 返回装箱 {@code Byte[]}（primitive 路径为 {@code byte[]}），
     * 其内容即文本协议下的 ASCII 值字节，须按 UTF-8 还原；其余类型走 String.valueOf。
     */
    private static String cellText(Object cell) {
        if (cell instanceof Byte[]) {
            Byte[] boxed = (Byte[]) cell;
            byte[] raw = new byte[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                raw[i] = boxed[i] == null ? 0 : boxed[i];
            }
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (cell instanceof byte[]) {
            return new String((byte[]) cell, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(cell);
    }


    public <T> Observable<Object[]> queryAsObjectArray(String sql) {
        return query(sql, new StringArrayDecoder());
    }

    public <T> Observable<byte[][]> queryAsByteArray(String sql) {
        return query(sql, new ByteArrayDecoder());
    }

    public synchronized <T> Observable<T> query(String sql, Decoder<T> decoder) {
        logger.debug("VertxConnection{}:netSocket{}", this.hashCode(), netSocket.hashCode());
        return Observable.create(emitter -> {
            synchronized (VertxConnection.this) {
                future = future.transform(new Function<AsyncResult<Void>, Future<Void>>() {
                    @Override
                    public Future<Void> apply(AsyncResult<Void> voidAsyncResult) {
                        return Future.future(new Handler<Promise<Void>>() {
                            @Override
                            public void handle(Promise<Void> promise) {
                                checkException();
                                QueryCommand<T> queryCommand = new QueryCommand(netSocket, sql, config, decoder, emitter) {
                                    @Override
                                    public void onEnd() {
                                        super.onEnd();
                                        promise.tryComplete();
                                    }
                                };
                                netSocket.handler(queryCommand);
                                queryCommand.write();
                                promise.future().onSuccess(event -> VertxConnection.this.serverstatus = queryCommand.getServerstatus());
                            }
                        });
                    }
                });
            }
        });
    }


    public Observable<Buffer> query(String sql) {
        Observable<Buffer> objectObservable = Observable.create(emitter -> {
            synchronized (VertxConnection.this) {
                future = future.flatMap(new Function<Void, Future<Void>>() {
                    @Override
                    public Future<Void> apply(Void unused) {
                        return Future.future((promise) -> {
                            checkException();
                            ResponseBufferCommand bufferedResponseHandler = new ResponseBufferCommand(netSocket, sql, config, !NewMycatConnectionConfig.PASS_HALF_PACKET, emitter) {
                                @Override
                                public void onEnd() {
                                    super.onEnd();
                                    promise.tryComplete();
                                }
                            };
                            netSocket.handler(bufferedResponseHandler);
                            bufferedResponseHandler.write();
                        });
                    }
                });
            }
        });
        return objectObservable;
    }

    Future<Void> close() {
        return netSocket.close();
    }

    public boolean checkVaildForRecycle() {
        boolean autocommit = MySQLServerStatusFlags.statusCheck(serverstatus, MySQLServerStatusFlags.AUTO_COMMIT);
        return autocommit;
    }
}
