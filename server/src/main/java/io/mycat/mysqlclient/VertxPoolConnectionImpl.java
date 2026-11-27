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

import io.mycat.MycatCore;
import io.mycat.mysqlclient.command.ConnectHandler;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import lombok.Data;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

public class VertxPoolConnectionImpl implements VertxConnectionPool {
    final static Logger logger = LoggerFactory.getLogger(MycatCore.class);
    Config config;
    ConcurrentLinkedQueue<VertxConnection> connections = new ConcurrentLinkedQueue<>();
    ConcurrentHashMap<Long,VertxConnection> usedConnections = new ConcurrentHashMap<>();
    private final Set<NetClient> pendingClients = ConcurrentHashMap.newKeySet();
    private final Set<NetSocket> pendingSockets = ConcurrentHashMap.newKeySet();
    private final Set<Promise<VertxConnection>> pendingConnections = ConcurrentHashMap.newKeySet();
    private volatile Consumer<VertxConnection> borrowListener;
    private volatile Consumer<VertxConnection> releaseListener;
    Vertx vertx;
    volatile boolean closed;
    private int creatingConnections;
    private final long timerId;

    long lastActiveTime = System.currentTimeMillis();

    @Data
    public static class Config {
        int port = 3306;
        String host = "127.0.0.1";
        String username = "root";
        String password = "123456";
        String database = "mysql";
        boolean clientDeprecateEof = true;

        int maxCon = 1000;
        int minCon = 1;
        long timer = TimeUnit.SECONDS.toMillis(30);
        int retry = 3;

        int removeAbandonedTimeoutSecond=180;//秒
    }

    @SneakyThrows
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        VertxPoolConnectionImpl vertxConnectionPool = new VertxPoolConnectionImpl(new Config(), vertx);

        long start = System.currentTimeMillis();
        List<Future> list = new ArrayList<>();
        long count = 60000;
        long batch = 30;
        for (int i = 0; i < batch; i++) {
            Future<Void> future = Future.future(promise -> {
                Handler<AsyncResult<VertxConnection>> handler = new Handler<AsyncResult<VertxConnection>>() {
                    Future<Void> future = Future.succeededFuture();
                    long start = System.currentTimeMillis();


                    @Override
                    public void handle(AsyncResult<VertxConnection> res) {
                        if (res.succeeded()) {
                            start = System.currentTimeMillis();
                            VertxConnection mySQLConnection = res.result();
                            for (int i1 = 0; i1 < count; i1++) {
                                future = future.compose(unused -> {
                                    return Future.future(promise1 -> {
                                        Observable<Buffer> query = mySQLConnection.query("select * from db1.travelrecord");
                                        query.doOnComplete(() -> promise1.tryComplete()).doOnError(throwable -> promise1.tryFail(throwable)).subscribe();
                                    });

                                });
                            }
                            future = future.onSuccess(event -> {
                                long end = System.currentTimeMillis();
                                double during = end - start;
                                System.out.println(during);
                                System.out.println("qps:" + count / during * 1000);
                            });
                            future = future.onFailure(event -> System.out.println(event));
                            future= future.onSuccess(event -> mySQLConnection.close());
                            future=future.onComplete(event -> promise.tryComplete());
                        } else {
                            System.out.println("Could not connect " + res.cause());
                        }
                    }
                };
                Future<VertxConnection> connection = vertxConnectionPool.getConnection();
                connection.onComplete(handler);
            });
            list.add(future);
        }
        CompositeFuture.join(list).toCompletionStage().toCompletableFuture().get();
        long end = System.currentTimeMillis();
        double during = end - start;
        System.out.println(during);
        System.out.println("qps:" + count* batch/ during * 1000);
        System.exit(0);
    }


    public VertxPoolConnectionImpl(Config config, Vertx vertx) {
        if (config.getMaxCon() < 1) {
            throw new IllegalArgumentException("maxCon must be at least 1");
        }
        if (config.getMinCon() < 0) {
            throw new IllegalArgumentException("minCon must be at least 0");
        }
        if (config.getMinCon() > config.getMaxCon()) {
            throw new IllegalArgumentException("minCon must not exceed maxCon");
        }
        this.config = config;
        this.vertx = vertx;
        this.timerId = this.vertx.setPeriodic(config.timer, event -> {
            if (closed) {
                return;
            }
            long dur = System.currentTimeMillis() - lastActiveTime;
            if (dur > TimeUnit.MINUTES.toMillis(1)) {
                synchronized (VertxPoolConnectionImpl.this) {
                    if (closed) {
                        return;
                    }
                    Integer availableNumber = getAvailableNumber();
                    int minCon = config.getMinCon();
                    int distance = availableNumber - minCon;

                    //async
                    List<Future<VertxConnection>> idleConnections = new ArrayList<>();
                    for (int i = 0; i < distance; i++) {
                        idleConnections.add(getConnectionWithMaxCountLimit());
                    }
                    CompositeFuture compositeFuture = CompositeFuture.join((List) idleConnections).onSuccess(event12 -> {
                        for (Future<VertxConnection> idleConnection : idleConnections) {
                            idleConnection.onSuccess(event1 -> kill(event1));
                        }
                    });
                    compositeFuture.onComplete(event13 -> {
                        for (int i = 0; i < config.getMinCon(); i++) {
                            Future<VertxConnection> connectionFuture = getConnectionWithMaxCountLimit();
                            connectionFuture.onSuccess(vertxConnection -> {
                                Observable<Object[]> observable = vertxConnection.queryAsObjectArray("select 1");
                                AtomicBoolean success = new AtomicBoolean(false);
                                observable.subscribe(objects -> success.set(true), throwable -> {
                                    logger.error("",throwable);
                                    success.set(false);
                                }, () -> {
                                    if (success.get()) {
                                        recycle(vertxConnection);
                                    } else {
                                        kill(vertxConnection);
                                    }
                                });
                            }).onFailure(event14 -> logger.error("", event14));
                        }
                    });
                }
            }
        });
    }

    private Future<VertxConnection> innerCreateConnection() {
        return Future.future(promise -> {
            NetClient netClient = vertx.createNetClient();
            synchronized (VertxPoolConnectionImpl.this) {
                if (closed) {
                    closeClient(netClient, "failed to close native client after pool close");
                    promise.tryFail("pool has closed");
                    return;
                }
                pendingClients.add(netClient);
                pendingConnections.add(promise);
            }
            Future<NetSocket> netSocketFuture;
            try {
                netSocketFuture = netClient.connect(config.getPort(), config.getHost());
            } catch (Throwable throwable) {
                pendingClients.remove(netClient);
                pendingConnections.remove(promise);
                closeClient(netClient, "failed to close native client after connect failure");
                promise.tryFail(throwable);
                return;
            }
            netSocketFuture.onSuccess(netSocket -> {
                synchronized (VertxPoolConnectionImpl.this) {
                    pendingClients.remove(netClient);
                    if (closed) {
                        closeSocket(netSocket, "failed to close native socket after pool close");
                        promise.tryFail("pool has closed");
                        return;
                    }
                    pendingSockets.add(netSocket);
                }
                netSocket.exceptionHandler(event -> {
                    logger.error("", event);
                    promise.tryFail(event);
                });
                ConnectHandler connectHandler = new ConnectHandler(netSocket, config, VertxPoolConnectionImpl.this);
                Future<VertxConnection> vertxConnectionFuture = connectHandler.handleInitialHandshake();
                vertxConnectionFuture.onComplete(event -> {
                    synchronized (VertxPoolConnectionImpl.this) {
                        pendingSockets.remove(netSocket);
                        pendingConnections.remove(promise);
                    }
                    if (event.succeeded()) {
                        VertxConnection connection = event.result();
                        if (closed) {
                            closeConnection(connection, "failed to close late native connection");
                            promise.tryFail("pool has closed");
                        } else {
                            promise.tryComplete(connection);
                        }
                    } else {
                        closeSocket(netSocket, "failed to close native socket after handshake failure");
                        promise.tryFail(event.cause());
                    }
                });
            });
            netSocketFuture.onFailure(event -> {
                pendingClients.remove(netClient);
                pendingConnections.remove(promise);
                promise.tryFail(event);
            });
        });
    }

    private void closeClient(NetClient client, String message) {
        try {
            observeCloseFailure(client.close(), message);
        } catch (Throwable throwable) {
            logger.warn(message, throwable);
        }
    }

    private void closeSocket(NetSocket socket, String message) {
        try {
            observeCloseFailure(socket.close(), message);
        } catch (Throwable throwable) {
            logger.warn(message, throwable);
        }
    }

    private void closeConnection(VertxConnection connection, String message) {
        try {
            observeCloseFailure(connection.close(), message);
        } catch (Throwable throwable) {
            logger.warn(message, throwable);
        }
    }

    private void observeCloseFailure(Future<Void> closeFuture, String message) {
        if (closeFuture != null) {
            closeFuture.onFailure(throwable -> logger.warn(message, throwable));
        }
    }


    void setConnectionLifecycleListeners(Consumer<VertxConnection> borrowListener,
                                         Consumer<VertxConnection> releaseListener) {
        this.borrowListener = borrowListener;
        this.releaseListener = releaseListener;
    }

    private void notifyBorrowed(VertxConnection connection) {
        Consumer<VertxConnection> listener = borrowListener;
        if (listener != null) {
            listener.accept(connection);
        }
    }

    private void releaseUsedConnection(VertxConnection connection) {
        if (!usedConnections.remove(connection.getConnectionId(), connection)) {
            return;
        }
        Consumer<VertxConnection> listener = releaseListener;
        if (listener != null) {
            try {
                listener.accept(connection);
            } catch (Throwable throwable) {
                logger.warn("failed to release native connection metric", throwable);
            }
        }
    }

    @Override
    public Future<VertxConnection> getConnection() {
        lastActiveTime = System.currentTimeMillis();
        Future<VertxConnection> tryGetFuture = getConnectionWithMaxCountLimit();
        return tryGetFuture.recover(throwable -> {
            logger.error("try get connection fail", throwable);
            Future<VertxConnection> future = Future.failedFuture(throwable);
            for (int i = 0; i < config.retry; i++) {
                int count = i;
                future = future.recover(throwable1 -> {
                    logger.error("try get connection fail try count:{}", count, throwable);
                    return getConnectionWithMaxCountLimit();
                });
            }
            return future;
        });
    }

    private Future<VertxConnection> getConnectionWithMaxCountLimit() {
        synchronized (VertxPoolConnectionImpl.this) {
            if (closed) {
                return Future.failedFuture("pool has closed");
            }
            VertxConnection connection = connections.poll();
            if (connection != null) {
                return registerBorrowedConnection(connection);
            }
            if (getUsedNumber() + creatingConnections >= config.getMaxCon()) {
                return Future.failedFuture("pool capacity exhausted");
            }
            creatingConnections++;
        }
        Future<VertxConnection> created;
        try {
            created = innerCreateConnection();
        } catch (Throwable throwable) {
            releaseCreatingConnection();
            return Future.failedFuture(throwable);
        }
        return created.transform(event -> {
            if (event.failed()) {
                return Future.failedFuture(event.cause());
            }
            return registerBorrowedConnection(event.result());
        }).onComplete(event -> releaseCreatingConnection());
    }

    private Future<VertxConnection> registerBorrowedConnection(VertxConnection connection) {
        synchronized (VertxPoolConnectionImpl.this) {
            if (closed) {
                kill(connection);
                return Future.failedFuture("pool has closed");
            }
            usedConnections.put(connection.getConnectionId(), connection);
            try {
                notifyBorrowed(connection);
            } catch (Throwable throwable) {
                kill(connection);
                return Future.failedFuture(throwable);
            }
            return Future.succeededFuture(connection);
        }
    }

    private void releaseCreatingConnection() {
        synchronized (VertxPoolConnectionImpl.this) {
            creatingConnections--;
        }
    }

    @Override
    public void close() {
        List<Promise<VertxConnection>> pendingPromises;
        List<NetSocket> sockets;
        List<NetClient> clients;
        List<VertxConnection> idleConnections;
        List<VertxConnection> borrowedConnections;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                vertx.cancelTimer(timerId);
            } catch (Throwable throwable) {
                logger.warn("failed to cancel native pool timer", throwable);
            }
            pendingPromises = new ArrayList<>(pendingConnections);
            sockets = new ArrayList<>(pendingSockets);
            clients = new ArrayList<>(pendingClients);
            idleConnections = new ArrayList<>(connections);
            borrowedConnections = new ArrayList<>(usedConnections.values());
            pendingConnections.clear();
            pendingSockets.clear();
            pendingClients.clear();
            connections.clear();
        }
        for (Promise<VertxConnection> promise : pendingPromises) {
            try {
                promise.tryFail("pool has closed");
            } catch (Throwable throwable) {
                logger.warn("failed to fail pending native borrow during pool close", throwable);
            }
        }
        for (NetSocket socket : sockets) {
            closeSocket(socket, "failed to close pending native socket");
        }
        for (NetClient client : clients) {
            closeClient(client, "failed to close pending native client");
        }
        for (VertxConnection connection : idleConnections) {
            closeConnection(connection, "failed to close idle native connection");
        }
        for (VertxConnection connection : borrowedConnections) {
            try {
                closeConnection(connection, "failed to close used native connection");
            } finally {
                releaseUsedConnection(connection);
            }
        }
        usedConnections.clear();
    }

    @Override
    public void recycle(VertxConnection connection) {
        synchronized (this) {
            if (closed) {
                kill(connection);
                return;
            }
            if(connection.checkVaildForRecycle()){
                connections.offer(connection);
                releaseUsedConnection(connection);
            }else {
                logger.info("because connectionId:{} maybe have transcation,so kill it in recycling connection",connection.getConnectionId());
                kill(connection);
            }
        }
    }

    @Override
    public void kill(VertxConnection connection) {
        synchronized (this) {
            releaseUsedConnection(connection);
            connections.remove(connection);
            closeConnection(connection, "failed to close killed native connection");
        }
    }

    public Integer getAvailableNumber() {
        return connections.size();
    }

    public Integer getUsedNumber() {
        return usedConnections.size();
    }
}
