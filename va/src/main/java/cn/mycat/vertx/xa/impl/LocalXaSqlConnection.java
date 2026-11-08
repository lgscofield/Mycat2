/**
 * Copyright [2021] [chen junwen]
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaLog;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.mycat.newquery.NewMycatConnection;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class LocalXaSqlConnection extends BaseXaSqlConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalXaSqlConnection.class);
    volatile NewMycatConnection localSqlConnection = null;

    public LocalXaSqlConnection(MySQLIsolation isolation, Supplier<MySQLManager> mySQLManagerSupplier,
                                XaLog xaLog) {
        super(isolation, mySQLManagerSupplier, xaLog);
    }

    @Override
    public Future<Void> begin() {
        if (inTranscation) {
            LOGGER.debug("local xa transaction occur nested transaction,xid:" + getXid());
            return Future.succeededFuture();
        }
        inTranscation = true;
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> commit() {
        /////////////////////////check///////////////////////
        if (!isInTransaction()) {
            if (localSqlConnection == null && map.isEmpty()) {
                //ok
            } else {
                LOGGER.error("meet commit in transaction bug");
            }
        }
        /////////////////////////check///////////////////////
        String curXid = xid;
        if (localSqlConnection == null && map.isEmpty()) {

            inTranscation = false;
            xid = null;
            localSqlConnection = null;
            return (Future.succeededFuture());
        }
        if (localSqlConnection != null && map.isEmpty()) {
            NewMycatConnection curLocalSqlConnection = localSqlConnection;
            // I-DEBT-86 审计 F11/G5 显式化：单连接提交 = XA 语义静默降级为本地 commit（无 2PC/无意向）。
            // 同物理实例多分片时原子性由后端自身保证、无数据风险，但运维必须知道"这不是分布式事务"。
            LOGGER.warn("XA policy commit degraded to single-connection local commit (no 2PC/no xa_log intent). " +
                    "Safe only because all involved datanodes resolve to one physical connection; " +
                    "for real cross-instance XA ensure datanodes span distinct physical backends (G5).");
            return curLocalSqlConnection.update("commit;").transform(event -> {
                if (event.failed()) {
                    // 提交失败不能静默还池：Druid 只对 underlyingAutoCommit=false 的连接做 recycle 回滚，
                    // 而本事务是用 SQL begin; 开的，直接 close 会把未回滚的事务（含行锁）送回池（I-DEBT-72）。
                    LOGGER.error("local commit occurs exception", event.cause());
                    Throwable cause = event.cause();
                    return rollback().transform(unused -> Future.failedFuture(cause));
                }
                Future<Void> closeFuture = curLocalSqlConnection.close();

                inTranscation = false;
                xid = null;
                localSqlConnection = null;

                return closeFuture;
            }).mapEmpty();
        }
        if (inTranscation && localSqlConnection != null) {
            NewMycatConnection curLocalSqlConnection = localSqlConnection;
            Future<Void> xaCommit = super.commitXa((coordinatorLog) -> curLocalSqlConnection.update(
                            "REPLACE INTO mycat.xa_log (xid) VALUES('" + curXid + "');").mapEmpty())
                    .compose((Function<Void, Future<Void>>) o -> {
                        return curLocalSqlConnection.update("commit;").compose(unused -> {
                            return curLocalSqlConnection.update("delete from mycat.xa_log where xid = '" + curXid + "'").mapEmpty();
                        });
                    }).mapEmpty();
            return xaCommit.transform(o -> {
                if (o.failed()) {
                    // 失败即回滚：把已 PREPARE 的参与者 XA ROLLBACK 掉、本地连接 rollback; 后再弃用，
                    // 并把原始失败如实上报——既不报成功，也不把持锁连接还池（I-DEBT-72）。
                    LOGGER.error("xa commit occurs exception", o.cause());
                    Throwable cause = o.cause();
                    return rollback().transform(unused -> Future.failedFuture(cause));
                }
                Future<Void> closeFuture = curLocalSqlConnection.close();

                inTranscation = false;
                xid = null;
                localSqlConnection = null;
                return closeFuture;
            }).mapEmpty();
        } else {
            throw new AssertionError();
        }
    }

    @Override
    public Future<NewMycatConnection> getConnection(String targetName) {
        MySQLManager mySQLManager = mySQLManager();
        if (inTranscation) {
            if (localSqlConnection == null) {
                Future<NewMycatConnection> sqlConnectionFuture = mySQLManager.getConnection(targetName);
                return sqlConnectionFuture.map(sqlConnection -> {
                    LocalXaSqlConnection.this.localSqlConnection = sqlConnection;
                    return sqlConnection;
                }).compose(sqlConnection -> sqlConnection
                        .update(getTransactionIsolation().getCmd())
                        .flatMap(unused -> sqlConnection.update("begin;").map(sqlConnection)));
            }
            if (this.localSqlConnection != null && this.localSqlConnection.getTargetName().equals(targetName)) {
                if (this.localSqlConnection.isClosed()) {
                    LOGGER.error("localSqlConnection is closed ,in transaction");
                }
                return Future.succeededFuture(localSqlConnection);
            }
            if (xid == null) {
                xid = log.nextXid();
                log.beginXa(xid);
            }
            return super.getConnection(targetName);
        }
        return mySQLManager.getConnection(targetName).map(connection -> {
            extraConnections.add(connection);
            return connection;
        });
    }

    @Override
    public Future<Void> rollback() {
        if (localSqlConnection == null && map.isEmpty()) {

            inTranscation = false;
            xid = null;
            localSqlConnection = null;
            return Future.succeededFuture();
        }
        if (localSqlConnection != null && map.isEmpty()) {
            return localSqlConnection.update("rollback;").transform(unused -> {
                if (unused.failed()) {
                    LOGGER.error("local rollback occurs exception", unused.cause());
                }
                localSqlConnection.abandonConnection();

                inTranscation = false;
                xid = null;
                localSqlConnection = null;
                return Future.succeededFuture();
            }).mapEmpty();
        }
        String curXid = this.xid;
        NewMycatConnection curLocalSqlConnection = this.localSqlConnection;
        return super.rollback().compose(unused -> curLocalSqlConnection.update("rollback;").flatMap(unused1 -> {
            return curLocalSqlConnection.update("delete from mycat.xa_log where xid = '" + curXid + "'");
        })).transform(u -> {
            if (u.failed()) {
                LOGGER.error("", u.cause());
                if (curLocalSqlConnection != null) {
                    curLocalSqlConnection.abandonConnection();
                }
            } else {
                if (curLocalSqlConnection != null) {
                    curLocalSqlConnection.close();
                }
            }
            inTranscation = false;
            xid = null;
            localSqlConnection = null;
            return Future.succeededFuture();
        }).mapEmpty();
    }

    @Override
    public Future<Void> closeStatementState() {
        ///////////////////////////////check////////////////////////////////
        if (this.localSqlConnection != null) {
            if (inTranscation && this.localSqlConnection.isClosed()) {
                LOGGER.error("localSqlConnection is closed,in transaction");
            }
            if (inTranscation && !this.localSqlConnection.isClosed()) {
                //ok
            }
            if (!inTranscation && this.localSqlConnection.isClosed()) {
                LOGGER.error("localSqlConnection is closed,not in transaction");
                //fix
                this.localSqlConnection = null;
            }
            if (!inTranscation && !this.localSqlConnection.isClosed()) {
                LOGGER.error("localSqlConnection is not closed,not in transaction");
                //fix
                this.localSqlConnection.close();
                this.localSqlConnection = null;
            }
        }
        if (!map.isEmpty()) {
            if (inTranscation) {
                //ok
            }
            if (!inTranscation) {
                LOGGER.error("xa connection map is not empty ,in transaction");
            }
        }
        ///////////////////////////////check////////////////////////////////
        Future<Void> future = Future.succeededFuture();
        if (localSqlConnection != null) {
            future = localSqlConnection.abandonQuery();
        }
        return CompositeFuture.join(future, super.closeStatementState()
                .flatMap(event -> {
                    Future<Void> closeFuture = Future.succeededFuture();
                    if (!isInTransaction()) {
                        closeFuture = Optional.ofNullable(this.localSqlConnection)
                                .map(newMycatConnection -> localSqlConnection.close())
                                .orElse(Future.succeededFuture());
                        this.localSqlConnection = null;
                    }
                    return closeFuture;
                })).mapEmpty();
    }

    @Override
    public Future<Void> close() {
        return rollback();
    }

    @Override
    public List<NewMycatConnection> getExistedTranscationConnections() {
        if (localSqlConnection == null) {
            List<NewMycatConnection> existedTranscationConnections = super.getExistedTranscationConnections();
            if (!existedTranscationConnections.isEmpty()) {
                LOGGER.error("localSqlConnection is null but existed transcation connections is not empty");
            }
            return existedTranscationConnections;
        }
        ArrayList<NewMycatConnection> newMycatConnections = new ArrayList<>();
        newMycatConnections.add(localSqlConnection);
        newMycatConnections.addAll(super.getExistedTranscationConnections());
        return newMycatConnections;
    }

    @Override
    public Future<Void> kill() {
        Future<Void> future = rollback();
        return future.transform(unused -> {
            if (localSqlConnection != null) {
                localSqlConnection.abandonConnection();
                localSqlConnection = null;
            }
            return super.kill();
        });
    }

    @Override
    public List<NewMycatConnection> getAllConnections() {
        List<NewMycatConnection> allConnections = super.getAllConnections();
        ArrayList<NewMycatConnection> resList = new ArrayList<>(allConnections.size() + 1);
        if (localSqlConnection != null) {
            resList.add(localSqlConnection);
        }
        // I-DEBT-81 M9：旧码算出 allConnections 却从未并入返回值（连容量都按 +1 预留了，漏并是笔误），
        // 于是 XA 轨语句结束时 BaseXaSqlConnection.closeStatementState():446 对 map/extraConnections
        // 一条 abandonQuery() 都不发（后端结果集不被放弃），且 show 的事务快照
        // VertxMycatServer:327 也少掉全部参与者。语义向 LocalSqlConnection.getAllConnections():176 看齐。
        resList.addAll(allConnections);
        return resList;
    }
}
