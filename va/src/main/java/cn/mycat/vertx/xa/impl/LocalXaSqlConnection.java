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
    /**
     * MYCAT CHANGE (I-DEBT-113): 首借登记先行——首借 Future 在 resolve 前的占位。
     * 与 localSqlConnection 相同可见性（包内测试可观测）；由 {@link #firstBorrowLock} 守护读写，
     * onComplete（在 map 赋值之后注册，执行顺序靠后）清空占位。
     */
    private volatile Future<NewMycatConnection> pendingFirstBorrow = null;
    private final Object firstBorrowLock = new Object();

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
            // I-DEBT-84 Phase2 阶段2（spec §8.6 决定点归位）：本地协调 ds1 的 commit; 是真实原子决定点，收进
            // beforeCommit，先于参与者意向持久化（writeCommitLog）与 XA COMMIT。REPLACE xa_log + commit; 原子提交
            // 业务数据与协调者意向；成功后置 commitDecided。外层仅余删 xa_log（commit; 已收进 beforeCommit）。
            Future<Void> xaCommit = super.commitXa((coordinatorLog) -> curLocalSqlConnection.update(
                            "REPLACE INTO mycat.xa_log (xid) VALUES('" + curXid + "');")
                    .compose(unused -> curLocalSqlConnection.update("commit;"))
                    .compose(unused -> {
                        commitDecided = true;
                        return Future.<Void>succeededFuture();
                    }))
                    .compose((Function<Void, Future<Void>>) o -> curLocalSqlConnection.update(
                            "delete from mycat.xa_log where xid = '" + curXid + "'").mapEmpty())
                    .mapEmpty();
            return xaCommit.transform(o -> {
                if (o.failed()) {
                    LOGGER.error("xa commit occurs exception", o.cause());
                    Throwable cause = o.cause();
                    if (commitDecided || commitTimedOut) {
                        // 决定已做出（ds1 已 commit）：不回滚已决提交（prepared 参与者交 reaper 按 ds1.xa_log 意向
                        // 向前收敛），弃用本地连接、清会话态后如实报错。杜绝 rollback→kill→close→rollback 递归。
                        // 或 commit 腿已超时（commitTimedOut，force-close 已弃用全部参与者）：此时 XA ROLLBACK 必失败，
                        // 进入 rollback 会触发 rollback→kill→rollback 无限递归（I-DEBT-84 C1）。未决超时 likewise 弃用
                        // 本地连接、清会话态、如实报错，未决收敛交 reaper（无意向 durable ⇒ reaper XA ROLLBACK 参与者）。
                        curLocalSqlConnection.abandonConnection();
                        inTranscation = false;
                        xid = null;
                        localSqlConnection = null;
                        return Future.failedFuture(cause);
                    }
                    // 未 decided（ds1 未 commit）：把已 PREPARE 参与者 XA ROLLBACK、本地 rollback; 后弃用，如实上报（I-DEBT-72）。
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
            // MYCAT CHANGE (I-DEBT-113): 修复事务首借 check-then-act——原实现 `localSqlConnection == null`
            // 判空后跨异步借用再赋值，真异步池借出 Future 未 resolve 期间后到的 getConnection 也走首借分支，
            // 两并发首借各 begin，后完成者覆写 localSqlConnection，先借出者成孤儿（带 open begin 写静默丢失）。
            // 现首借改为登记先行：synchronized 首借块内在借出 Future resolve 前先占位（pendingFirstBorrow），
            // 后到者等首借落位后按既有 local/map 路径分发。行为变化仅在"首借未落位"的并发窗口，常规路径零变化。
            synchronized (firstBorrowLock) {
                if (localSqlConnection == null && pendingFirstBorrow == null) {
                    Future<NewMycatConnection> firstBorrow = mySQLManager.getConnection(targetName)
                            .map(sqlConnection -> {
                                LocalXaSqlConnection.this.localSqlConnection = sqlConnection;
                                return sqlConnection;
                            })
                            .compose(sqlConnection -> sqlConnection
                                    .update(getTransactionIsolation().getCmd())
                                    .flatMap(unused -> sqlConnection.update("begin;").map(sqlConnection)));
                    pendingFirstBorrow = firstBorrow;
                    // onComplete 在 map 赋值之后注册（链上靠后），清占位不会先于赋值执行；
                    // 首借失败同样清占位，允许后续借出重试。
                    firstBorrow.onComplete(unused -> {
                        synchronized (firstBorrowLock) {
                            pendingFirstBorrow = null;
                        }
                    });
                    return firstBorrow;
                }
                Future<NewMycatConnection> pending = pendingFirstBorrow;
                if (pending != null) {
                    // 后到者：先等首借落位，再按既有路径分发（不并发借出）
                    return pending.compose(unused -> getConnectionAfterFirstBorrow(targetName, mySQLManager));
                }
            }
            return getConnectionAfterFirstBorrow(targetName, mySQLManager);
        }
        return mySQLManager.getConnection(targetName).map(connection -> {
            extraConnections.add(connection);
            return connection;
        });
    }

    /** 首借已落位（localSqlConnection 可用）后的分发：同目标复用本地连接，异目标走参与者 map 路径。 */
    private Future<NewMycatConnection> getConnectionAfterFirstBorrow(String targetName, MySQLManager mySQLManager) {
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

    @Override
    public Future<Void> rollback() {
        if (rollingBack) {
            // 重入回滚（Base.rollback 失败 → kill → Local.kill → rollback）：XA ROLLBACK 已失败，再发只会
            // rollback→kill→rollback 无限递归（I-DEBT-84 C1）。强制弃用连接 + 复位会话态，不再重发 XA ROLLBACK。
            return forceAbandonAndReset();
        }
        rollingBack = true;
        return rollbackInternal().onComplete(ar -> rollingBack = false);
    }

    /** 重入回滚时的终止路径：弃用本地协调 + 参与者连接、复位会话态。inTranscation 先置 false 使 super.kill() 不再触发 rollback。 */
    private Future<Void> forceAbandonAndReset() {
        NewMycatConnection cur = localSqlConnection;
        if (cur != null) {
            cur.abandonConnection();
        }
        inTranscation = false;
        xid = null;
        localSqlConnection = null;
        return super.kill();
    }

    private Future<Void> rollbackInternal() {
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
                return unused.failed()
                        ? Future.failedFuture(unused.cause())
                        : Future.succeededFuture();
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
            return u.failed() ? Future.failedFuture(u.cause()) : Future.succeededFuture();
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
        return rollback().transform(event -> {
            if (localSqlConnection != null) {
                localSqlConnection.abandonConnection();
                localSqlConnection = null;
            }
            Future<Void> cleanup = super.kill();
            return event.failed()
                    ? cleanup.transform(unused -> Future.failedFuture(event.cause()))
                    : cleanup;
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
