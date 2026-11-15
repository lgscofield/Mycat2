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

import cn.mycat.vertx.xa.*;
import com.alibaba.druid.util.JdbcUtils;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.SqlResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;


public class BaseXaSqlConnection extends AbstractXaSqlConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaseXaSqlConnection.class);
    protected final ConcurrentHashMap<String, NewMycatConnection> map = new ConcurrentHashMap<>();
    protected final Map<NewMycatConnection, State> connectionState = Collections.synchronizedMap(new IdentityHashMap<>());
    protected final List<NewMycatConnection> extraConnections = new CopyOnWriteArrayList<>();
    protected final List<Future<Void>> closeList = new CopyOnWriteArrayList<>();
    private final Supplier<MySQLManager> mySQLManagerSupplier;
    protected volatile String xid;
    /**
     * I-DEBT-84 Phase2 阶段2 R1：提交决定已做出。混合路径在本地协调 commit; 成功后置位（LocalXaSqlConnection），
     * 纯 XA 在 writeCommitLog 持久化成功后置位（commitXa）。决定后失败只向前收敛，不回滚已决提交。
     */
    protected volatile boolean commitDecided;
    /** I-DEBT-84 Phase2 阶段2 R2：commit 腿语句超时已触发，retryCommit 据此让位 reaper。 */
    protected volatile boolean commitTimedOut;
    /**
     * I-DEBT-84 C1 终止守卫：rollback 进行中标志。Base.rollback 失败分支调 kill()，LocalXaSqlConnection.kill()
     * 又调 rollback()，若 XA ROLLBACK 持续失败则 rollback→kill→rollback 无限递归。LocalXaSqlConnection.rollback()
     * 据此切断重入（重入时强制弃用连接而非再发 XA ROLLBACK）。
     */
    protected volatile boolean rollingBack;


    public BaseXaSqlConnection(MySQLIsolation isolation, Supplier<MySQLManager> mySQLManagerSupplier, XaLog xaLog) {
        super(isolation,xaLog);
        this.mySQLManagerSupplier = mySQLManagerSupplier;
    }

    protected MySQLManager mySQLManager() {
        return mySQLManagerSupplier.get();
    }


    private String getDatasourceName(NewMycatConnection connection) {
        return map.entrySet().stream().filter(p -> p.getValue() == connection).map(e -> e.getKey())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown connection " + connection));
    }

    /**
     * 1.not allow Nested transaction,double begin.
     * 2.alloc unique xid
     */
    @Override
    public Future<Void> begin() {
        if (inTranscation) {
            LOGGER.warn("xa transaction occur nested transaction,xid:" + xid);
            return Future.succeededFuture();
        }
        inTranscation = true;
        xid = log.nextXid();
        log.beginXa(xid);
        return Future.succeededFuture();
    }


    public Future<NewMycatConnection> getConnection(String targetName) {
//        for (Map.Entry<String, SqlConnection> stringSqlConnectionEntry : map.entrySet()) {
//            AbstractMySqlConnectionImpl value = (AbstractMySqlConnectionImpl) stringSqlConnectionEntry.getValue();
//            value
//        }

        MySQLManager mySQLManager = mySQLManager();
        if (inTranscation) {
            if (map.containsKey(targetName)) {
                return Future.succeededFuture(map.get(targetName));
            } else {
                Future<NewMycatConnection> sqlConnectionFuture = mySQLManager.getConnection(targetName);
                return sqlConnectionFuture.compose(connection -> {
                    map.put(targetName, connection);
                    changeTo(connection, State.XA_INITED);
                    Future<SqlResult> execute = connection.update(String.format(XA_START, xid));
                    return execute.map(r -> changeTo(connection, State.XA_STARTED));
                });
            }
        }
        Future<NewMycatConnection> connection = mySQLManager.getConnection(targetName);
        return connection.map(connection1 -> {
            if (!map.containsKey(targetName)) {
                map.put(targetName, connection1);
            } else {
                extraConnections.add(connection1);
            }
            return connection1;
        });
    }

    @Override
    public List<NewMycatConnection> getExistedTranscationConnections() {
        return new ArrayList<>(map.values());
    }

    /**
     * <p>
     * XA_START to XA_END to XA_ROLLBACK
     * XA_ENDED to XA_ROLLBACK
     * XA_PREPARED to XA_ROLLBACK
     * <p>
     * client blocks until rollback successfully
     */
    @Override
    public Future<Void> rollback() {
        return Future.future((Promise<Void> promise) -> {
            logParticipants();
            Function<NewMycatConnection, Future<Void>> function = c -> {
                Future<Void> future = Future.succeededFuture();
                if (connectionState.isEmpty()) {
                    return Future.succeededFuture();
                }
                State state = connectionState.get(c);
                if (state == null) {
                    return Future.succeededFuture();
                }
                switch (state) {
                    case XA_INITED:
                        return future;
                    case XA_STARTED:
                        future = future.flatMap(unused -> {
                            return c.update(String.format(XA_END, xid))
                                    .map(u -> changeTo(c, State.XA_ENDED)).mapEmpty();
                        });
                    case XA_ENDED:
                    case XA_PREPARED:
                        future = future.flatMap(unuse -> c.update(String.format(XA_ROLLBACK, xid))
                                .map(i -> changeTo(c, State.XA_ROLLBACKED))).mapEmpty();
                }
                return future;
            };
            Future<Void> future = executeTranscationConnection(function);
            future.onComplete(event -> {
//                Throwable cause = event.cause();
//                if (cause instanceof SQLException) {
//                    SQLException sqlException = (SQLException) cause;
//                    if (sqlException.getErrorCode() == 1397 && "XAER_NOTA: Unknown XID".equalsIgnoreCase(sqlException.getMessage())) {
//                        event = Future.succeededFuture();
//                    }
//                }
                log.logRollback(xid, event.succeeded());
                if (event.succeeded()) {
                    inTranscation = false;
                    clearConnections().onComplete(promise);
                } else {
                    Set<String> targets = new HashSet<>(map.keySet());
                    Future<Void> killFuture = kill();
                    killFuture.flatMap((Function<Void, Future<Void>>) unused -> {
                        try {
                            if (tryRecovery(targets)) {
                                return Future.succeededFuture();
                            }
                            String message = "xid:" + getXid() + " recovery fail";
                            LOGGER.info(message);
                            LOGGER.error(message);
                            //@todo 注册调度中心,定时恢复
                            return Future.failedFuture(message);
                        } catch (Exception e) {
                            LOGGER.error("", e);
                            return Future.failedFuture(e);
                        }
                    }).onComplete(promise);
                }
            });
        });
    }

    private boolean tryRecovery(Set<String> targets) throws InterruptedException {
        for (int tryCount = 0; tryCount < 3; tryCount++) {
            HashMap<String, Connection> map = new HashMap<>();
            try {
                for (String target : targets) {
                    Connection writeableConnection = mySQLManager().getWriteableConnection(target);
                    map.put(target, writeableConnection);
                }
                log.readXARecoveryLog(map);
                return true;
            } catch (Exception e) {
                LOGGER.error("", e);
            } finally {
                map.values().forEach(c -> {
                    if (c != null) {
                        JdbcUtils.close(c);
                    }
                });
            }
            TimeUnit.SECONDS.sleep(1);
        }
        return false;
    }

    /**
     * retry has a delay time for datasource need duration to recover
     */
    private Future<Void> retryRollback(Function<NewMycatConnection, Future<Void>> function) {
        return Future.future(promise -> {
            List<Future<Void>> collect = computePrepareRollbackTargets().stream().map(c -> mySQLManager().getConnection(c).flatMap(function)).collect(Collectors.toList());
            CompositeFuture.join((List) collect)
                    .onComplete(event -> {
                        log.logRollback(xid, event.succeeded());
                        if (event.failed()) {
                            mySQLManager().setTimer(log.retryDelay(),
                                    () -> retryRollback(function).onComplete(promise));
                            return;
                        }
                        inTranscation = false;
                        clearConnections().onComplete(promise);
                    });
        });

    }

    /**
     * before XA_PREPARE,should log the participants
     */
    private void logParticipants() {
        ImmutableParticipantLog[] participantLogs = new ImmutableParticipantLog[map.size()];
        int index = 0;
        for (Map.Entry<String, NewMycatConnection> e : map.entrySet()) {
            participantLogs[index] = new ImmutableParticipantLog(e.getKey(),
                    log.getExpires(),
                    connectionState.get(e.getValue()));
            index++;
        }
        log.log(xid, participantLogs);
    }

    protected NewMycatConnection changeTo(NewMycatConnection c, State state) {
        connectionState.put(c, state);
        log.log(xid, getDatasourceName(c), state);
        return c;
    }

    protected void changeTo(String c, State state) {
        connectionState.put(map.get(c), state);
        log.log(xid, c, state);
    }


    @Override
    public Future<Void> commit() {
        return commitXa((unused) -> Future.succeededFuture());
    }

    /**
     * @param beforeCommit for the native connection commit or some exception test
     */
    public Future<Void> commitXa(Function<ImmutableCoordinatorLog, Future<Void>> beforeCommit) {
        return Future.future((Promise<Void> promsie) -> {
            logParticipants();
            Future<Void> xaEnd = executeTranscationConnection(connection -> {
                Future<Void> future = Future.succeededFuture();
                switch (connectionState.get(connection)) {
                    case XA_INITED:
                        future = future
                                .flatMap(unuse -> connection.update(String.format(XA_START, xid)))
                                .map(u -> changeTo(connection, State.XA_STARTED)).mapEmpty();
                    case XA_STARTED:
                        future = future
                                .flatMap(unuse -> connection.update(String.format(XA_END, xid)))
                                .map(u -> changeTo(connection, State.XA_ENDED)).mapEmpty();
                    case XA_ENDED:
                    default:
                }
                return future.mapEmpty();
            });
            xaEnd.onFailure(throwable -> promsie.tryFail(throwable));
            xaEnd.onSuccess(event -> {
                executeTranscationConnection(connection -> {
                    if (connectionState.get(connection) != State.XA_PREPARED) {
                        return connection.update(String.format(XA_PREPARE, xid))
                                .map(c -> changeTo(connection, State.XA_PREPARED)).mapEmpty();
                    }
                    return Future.succeededFuture();
                })
                        .onFailure(throwable -> {
                            log.logPrepare(xid, false);
                            //客户端触发回滚
                            promsie.tryFail(throwable);
                        })
                        .onSuccess(compositeFuture -> {
                            log.logPrepare(xid, true);
                            // I-DEBT-84 Phase2 阶段2（spec §8.6 决定点归位）：本地协调决定（beforeCommit，混合=ds1
                            // REPLACE xa_log + commit;）必须先于参与者意向持久化（writeCommitLog）与 XA COMMIT，使真实
                            // 原子决定点落在参与者意向 durable 之前——否则 ds1 提交前被 force-close 时参与者意向已 durable，
                            // reaper 误提交参与者造成跨库不一致（PROBE-A 坐实）。beforeCommit 的 coordinatorLog 参数各调用方
                            // 均忽略（LocalSqlConnection.commitXa 亦 apply(null)），重排后传 null。
                            Future<Void> before = beforeCommit.apply(null);
                            before.onFailure((Handler<Throwable>) throwable -> {
                                // 决定未做出（混合 ds1 未 commit）：取消意向 + 复位 decided，交客户端 rollback。
                                log.logCancelCommitBeforeXaCommit(xid);
                                commitDecided = false;
                                promsie.fail(throwable);
                            });
                            before.onSuccess(event16 -> {
                                try {
                                    // 参与者意向持久化（阶段0）。混合路径决定已在 beforeCommit 做出（commitDecided 已 true）；
                                    // 纯 XA 此处才是决定点。writeCommitLog 失败抛 IllegalStateException。
                                    this.log.logCommitBeforeXaCommit(xid);
                                    commitDecided = true;
                                } catch (Throwable throwable) {
                                    // 混合 decided=true：ds1 已 commit，决定不可撤销 ⇒ 不 cancel/不回滚，保留 ds1.xa_log
                                    // 意向交 reaper 向前收敛。纯 XA decided=false：未 durable=未决定 ⇒ cancel + 交客户端 rollback。
                                    if (!commitDecided) {
                                        log.logCancelCommitBeforeXaCommit(xid);
                                    }
                                    promsie.fail(throwable);
                                    return;
                                }
                                executeTranscationConnection(connection -> {
                                    return connection.update(String.format(XA_COMMIT, xid))
                                            .map(c -> changeTo(connection, State.XA_COMMITED)).mapEmpty();
                                })
                                        .onFailure(ignored -> {
                                            log.logCommit(xid, false);
                                            //retry
                                            retryCommit().onComplete(promsie);
                                        })
                                        .onSuccess(ignored -> {
                                            inTranscation = false;

                                            log.logCommit(xid, true);

                                            clearConnections().onComplete(promsie);
                                        });
                            });
                        });
            });
        });

    }


    /**
     * use new connection to retry the connection.
     */
    private Future<Void> retryCommit() {
        return Future.future((Promise<Void> promise) -> {
            // I-DEBT-84 Phase2 阶段2 R2：commit 腿已超时（客户端已拿 3013 走人）。retryCommit 只在 XA COMMIT
            // 失败后进入（此时 writeCommitLog 已成功 ⇒ commitDecided=true，意向在 xa_log），收敛交 reaper 向前
            // 提交即可；若继续 setTimer 抢新连接无限重试，会与 reaper 双主 churn。故立即让位、有界失败。
            if (commitTimedOut) {
                promise.tryFail(new IllegalStateException("commit timed out; retryCommit yields to reaper, xid:" + xid));
                return;
            }
            CompositeFuture all = CompositeFuture.join(computePrepareCommittedTargets().stream()
                    .map(s -> mySQLManager().getConnection(s)
                            .compose(c -> {
                                return c.update(String.format(XA_COMMIT, xid))
                                        .compose(rows -> {
                                            changeTo(s, State.XA_COMMITED);
                                            return c.close();
                                        }, throwable -> {
                                            return c.close();
                                        });
                            })).collect(Collectors.toList()));
            all.onSuccess(event -> {
                inTranscation = false;
                log.logCommit(xid, true);
                clearConnections().onComplete(promise);
            });
            all.onFailure(event -> {
                // 重试途中超时标志被置位（onTimeout 异步触发）：不再重排，让位 reaper。
                if (commitTimedOut) {
                    promise.tryFail(new IllegalStateException("commit timed out; retryCommit yields to reaper, xid:" + xid));
                    return;
                }
                mySQLManager().setTimer(log.retryDelay(), () -> retryCommit().onComplete(promise));
            });
        });
    }

    private List<String> computePrepareCommittedTargets() {
        List<String> collect = connectionState.entrySet().stream()
                .filter(i -> i.getValue() != State.XA_COMMITED)
                .map(i -> i.getKey())
                .map(k -> getDatasourceName(k)).collect(Collectors.toList());
        return collect;
    }

    private List<String> computePrepareRollbackTargets() {
        List<String> collect = connectionState.entrySet().stream()
                .filter(i -> i.getValue() != State.XA_ROLLBACKED)
                .map(i -> i.getKey())
                .map(k -> getDatasourceName(k)).collect(Collectors.toList());
        return collect;
    }

    public Future<Void> executeTranscationConnection(Function<NewMycatConnection, Future<Void>> connectionFutureFunction) {
        if (map.isEmpty()) {
            return Future.succeededFuture();
        }
        List<Future> futures = map.values().stream().map(connectionFutureFunction).collect(Collectors.toList());
        return CompositeFuture.join(futures).mapEmpty();
    }

    /**
     *
     */
    public Future<Void> close() {
        Function<NewMycatConnection, Future<Void>> consumer = newMycatConnection -> newMycatConnection.close();
        return close(consumer);
    }

    private Future<Void> close(Function<NewMycatConnection, Future<Void>> consumer) {
        Future<Void> allFuture = CompositeFuture.join((List) closeList).mapEmpty();
        closeList.clear();
        if (inTranscation) {
            allFuture = CompositeFuture.join(allFuture, rollback()).mapEmpty();
        }
        return allFuture.flatMap(unused -> {
            Future<Void> future = Future.succeededFuture();
            for (NewMycatConnection extraConnection : extraConnections) {
                future = future.compose(unused2 -> consumer.apply(extraConnection));
            }
            future = future.onComplete(event -> extraConnections.clear());
            return future.onComplete(u -> executeTranscationConnection(c -> {
                return consumer.apply(c);
            }).onComplete(c -> {
                map.clear();
                connectionState.clear();
            }));
        });
    }

    @Override
    public Future<Void> kill() {
        Function<NewMycatConnection, Future<Void>> consumer = newMycatConnection -> {
            newMycatConnection.abandonConnection();
            return Future.succeededFuture();
        };
        return close(consumer);
    }


    @Override
    public Future<Void> closeStatementState() {
        List<Future> stopResultSet = getAllConnections().stream().map(i -> i.abandonQuery()).collect(Collectors.toList());
        Future<Void> future = CompositeFuture.join(stopResultSet).mapEmpty();
        future = future.flatMap(unused -> CompositeFuture.join((List) closeList).mapEmpty());
        closeList.clear();
        // I-DEBT-81：排空腿必须被等待。旧写法是
        // return future.onComplete(event -> clearConnections().onComplete(...))，
        // 把 clearConnections() 的 Future 丢在 onComplete 处理器里（处理器的返回值不参与 Future 链），
        // 于是 SELECT 的 EOF 在后端连接还没归还时就发给了客户端，extraConnections 跨语句单调累积，
        // 最终撞后端 max_connections（I-DEBT-80 六格取证的判决主因）。
        // 语义向 LocalSqlConnection.closeStatementState 看齐：结果仍是 future 的结果，只是完成时刻推到排空之后。
        Promise<Void> drained = Promise.promise();
        future.onComplete(event -> clearConnections().onComplete(unused -> {
            if (!inTranscation) {
                xid = null;
            }
            if (event.failed()) {
                drained.tryFail(event.cause());
            } else {
                drained.tryComplete();
            }
        }));
        return drained.future();
    }

    @Override
    public String getXid() {
        return xid;
    }

    @Override
    public void markCommitDecided() {
        commitDecided = true;
    }

    @Override
    public boolean isCommitDecided() {
        return commitDecided;
    }

    @Override
    public void markCommitTimedOut() {
        commitTimedOut = true;
    }

    @Override
    public boolean isCommitOutcomeUnknown() {
        return commitTimedOut && commitDecided;
    }

    @Override
    public void addCloseFuture(Future<Void> future) {
        closeList.add(future);
    }

    @Override
    public List<NewMycatConnection> getAllConnections() {
        ArrayList<NewMycatConnection> resList = new ArrayList<>();
        resList.addAll(map.values());
        resList.addAll(extraConnections);
        return resList;
    }

    /**
     * before clear connections,it should check not be in transaction
     */
    public Future<Void> clearConnections() {
        Future<Void> closeListFuture = CompositeFuture.join((List) closeList).mapEmpty();
        closeList.clear();
        // I-DEBT-81：先快照再并行归还。旧写法把 close() 逐条 compose 成串行链（队头阻塞），
        // 且 extraConnections.clear() 挂在链尾，链没走完计数就永不下降。
        // 这里改成 removeAll(snapshot)，排空期间新借的连接不会被误抹掉。
        List<NewMycatConnection> draining = new ArrayList<>(extraConnections);
        Future<Void> extraFuture = joinCloses(draining)
                .onComplete(event -> extraConnections.removeAll(draining));
        if (inTranscation) {
            return joinVoidFutures(closeListFuture, extraFuture);
        }
        List<NewMycatConnection> transactionConnections = new ArrayList<>(map.values());
        Future<Void> mapFuture = joinCloses(transactionConnections)
                .onComplete(event -> {
                    map.clear();
                    connectionState.clear();
                });
        return joinVoidFutures(closeListFuture, extraFuture, mapFuture);
    }

    /**
     * 并行归还一组连接：单条失败只记日志、不中断其余归还，也不把整条腿变成 N 段串行延迟。
     */
    private Future<Void> joinCloses(List<NewMycatConnection> connections) {
        if (connections.isEmpty()) {
            return Future.succeededFuture();
        }
        List<Future> futures = new ArrayList<>(connections.size());
        for (NewMycatConnection connection : connections) {
            futures.add(connection.close().recover(cause -> {
                LOGGER.warn("return xa connection to pool failed,target:"
                        + connection.getTargetName(), cause);
                return Future.succeededFuture();
            }));
        }
        return CompositeFuture.join(futures).mapEmpty();
    }

    @SafeVarargs
    private final Future<Void> joinVoidFutures(Future<Void>... futures) {
        List<Future> joined = new ArrayList<>(Arrays.asList(futures));
        return CompositeFuture.join(joined).mapEmpty();
    }
}

