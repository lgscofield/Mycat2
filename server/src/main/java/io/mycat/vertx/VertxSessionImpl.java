/**
 * Copyright (C) <2021>  <chen junwen>
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
package io.mycat.vertx;

import io.mycat.MySQLPacketUtil;
import io.mycat.MycatDataContext;
import io.mycat.TransactionSession;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.proxy.session.ProcessState;
import io.mycat.runtime.MycatDataContextImpl;
import io.mycat.util.VertxUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.nio.charset.Charset;

public class VertxSessionImpl implements VertxSession {
    private MycatDataContext mycatDataContext;
    private NetSocket socket;
    private VertxMycatServer.MycatSessionManager mycatSessionManager;
    int packetId = 0;
    private ProcessState processState;
    private volatile boolean close = false;
    private volatile boolean resourcesClosed = false;
    private volatile Future<Void> cleanupFuture;
    public boolean pause = false;

    public VertxSessionImpl(MycatDataContext mycatDataContext, NetSocket socket, VertxMycatServer.MycatSessionManager mycatSessionManager) {
        this.mycatDataContext = mycatDataContext;
        this.socket = socket;
        this.mycatSessionManager = mycatSessionManager;
    }

    @Override
    public void setPacketId(int packet) {
        this.packetId = packet;
    }

    @Override
    public byte getNextPacketId() {
        return (byte) (++packetId);
    }

    @Override
    public String getLastMessage() {
        return mycatDataContext.getLastMessage();
    }

    @Override
    public long affectedRows() {
        return mycatDataContext.getAffectedRows();
    }

    @Override
    public int getServerStatusValue() {
        return mycatDataContext.serverStatus();
    }

    @Override
    public int getWarningCount() {
        return mycatDataContext.getWarningCount();
    }

    @Override
    public boolean isDeprecateEOF() {
        return MySQLServerCapabilityFlags.isDeprecateEOF(this.mycatDataContext.getServerCapabilities());
    }

    @Override
    public long getLastInsertId() {
        return mycatDataContext.getLastInsertId();
    }

    @Override
    public int getLastErrorCode() {
        return mycatDataContext.getLastErrorCode();
    }

    @Override
    public void setLastErrorCode(int errorCode) {
        mycatDataContext.setLastErrorCode(errorCode);
    }

    @Override
    public int getCapabilities() {
        return this.mycatDataContext.getServerCapabilities();
    }

    @Override
    public boolean isResponseFinished() {
        return processState == ProcessState.DONE;
    }

    @Override
    public int charsetIndex() {
        return this.mycatDataContext.getCharsetIndex();
    }

    @Override
    public void setResponseFinished(ProcessState b) {
        this.processState = b;
    }

    @Override
    public Future<Void> resetSession() {
        return io.mycat.proxy.session.SessionResetter.reset(mycatDataContext);
    }

    @Override
    public Charset charset() {
        return this.mycatDataContext.getCharset();
    }

    @Override
    public Future<Void> writeBytes(byte[] payload, boolean end) {
        if (!close) {
            if (end) {
                if (mycatDataContext != null) {
                    TransactionSession transactionSession = mycatDataContext.getTransactionSession();
                    if (transactionSession != null) {
                        //加速回收资源
                        return transactionSession.closeStatementState()
                                .compose(voidAsyncResult ->
                                        socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(getNextPacketId(), payload))))
                                .onComplete(voidAsyncResult -> {
                                    if (!transactionSession.isInTransaction() && mycatSessionManager.isPause()) {
                                        socket.pause();
                                        VertxSessionImpl.this.pause = true;
                                    }
                                });
                    }
                }
            }
            return socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(getNextPacketId(), payload)));
        } else {
            return VertxUtil.castPromise(Future.failedFuture("session is closed"));
        }
    }

    @Override
    public Future<Void> writeErrorEndPacketBySyncInProcessError() {
        int errorCode = mycatDataContext.getLastErrorCode();
        String message = mycatDataContext.getLastMessage();
        // I-DEBT-69 Blade F：凡回客户端 ERR_Packet 的语句级失败必进会话诊断区（Error 级，
        // 活体 C1 wc/ec 计入）；写入不受下方 socket 写守卫影响，真库诊断与投递成败无关。
        mycatDataContext.recordStatementError(errorCode, message);
        return writeBytes(MySQLPacketUtil.generateError(
                errorCode,
                message,
                getCapabilities()
        ), true);
    }

    @Override
    public Future<Void> writeErrorEndPacketBySyncInProcessError(int errorCode) {
        // 同上：诊断写入先于投递，与 ERR 包同源同码。
        mycatDataContext.recordStatementError(errorCode, mycatDataContext.getLastMessage());
        return writeBytes(MySQLPacketUtil.generateError(
                errorCode,
                mycatDataContext.getLastMessage(),
                getCapabilities()
        ), true);
    }

    @Override
    public MycatDataContext getDataContext() {
        return mycatDataContext;
    }

    @Override
    public synchronized Future<Void> close(boolean normal, String quit) {
        this.close = true;
        Future<Void> cleanup;
        try {
            cleanup = cleanupResources(normal);
        } catch (RuntimeException | Error throwable) {
            closeSocketAfterCleanup();
            throw throwable;
        }
        if (!normal) {
            return cleanup.onComplete(event -> closeSocketAfterCleanup());
        }
        return cleanup.onComplete(event -> {
            if (event.failed()) {
                closeSocketAfterCleanup();
            }
        }).compose(v -> socket.close());
    }

    @Override
    public Future<Void> directWrite(Buffer buffer) {
        return socket.write(buffer);
    }

    @Override
    public Future<Void> directWriteEnd() {
        if (mycatDataContext != null) {
            TransactionSession transactionSession = mycatDataContext.getTransactionSession();
            if (transactionSession != null) {
                return transactionSession.closeStatementState();
            }
        }
        return Future.succeededFuture();
    }

    @Override
    public void directWriteStart() {

    }

    @Override
    public synchronized Future<Void> close() {
        return close(true, null);
    }

    private Future<Void> cleanupResources(boolean normal) {
        if (resourcesClosed) {
            return Future.succeededFuture();
        }
        if (cleanupFuture != null && !cleanupFuture.isComplete()) {
            return cleanupFuture;
        }
        if (!(mycatDataContext instanceof MycatDataContextImpl)) {
            if (normal) {
                mycatDataContext.close();
            } else {
                mycatDataContext.kill();
            }
            resourcesClosed = true;
            return Future.succeededFuture();
        }
        MycatDataContextImpl context = (MycatDataContextImpl) mycatDataContext;
        Future<Void> cleanup = Future.fromCompletionStage(
                normal ? context.closeAsync() : context.killAsync());
        cleanupFuture = cleanup;
        cleanup.onSuccess(v -> {
            resourcesClosed = true;
            if (cleanupFuture == cleanup) {
                cleanupFuture = null;
            }
        });
        cleanup.onFailure(cause -> {
            if (cleanupFuture == cleanup) {
                cleanupFuture = null;
            }
        });
        return cleanup;
    }

    private void closeSocketAfterCleanup() {
        try {
            socket.close();
        } catch (RuntimeException | Error ignored) {
        }
    }

    @Override
    public NetSocket getSocket() {
        return socket;
    }

    @Override
    public boolean isPause() {
        return this.pause;
    }

    @Override
    public boolean isClosed() {
        return resourcesClosed;
    }
}
