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
package io.mycat.commands;

import cn.mycat.vertx.xa.XaSqlConnection;
import io.mycat.*;
import io.mycat.api.collector.*;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.WarningMessage;
import io.mycat.beans.resultset.ResultSetWriter;
import io.mycat.beans.resultset.SimpleBinaryWriterImpl;
import io.mycat.beans.resultset.SimpleTextWriterImpl;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.RowSet;
import io.mycat.newquery.SqlResult;
import io.mycat.config.DatasourceConfig;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.vertx.StatementDeadline;
import io.mycat.proxy.session.MySQLServerSession;
import io.mycat.util.VertxUtil;
import io.mycat.vertx.ResultSetMapping;
import io.mycat.vertx.VertxExecuter;
import io.ordinate.engine.builder.SchemaBuilder;
import io.ordinate.engine.schema.InnerType;
import io.ordinate.engine.util.ResultWriterUtil;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableSource;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.mycat.ExecuteType.*;


public class ReceiverImpl implements Response {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReceiverImpl.class);

    protected final MySQLServerSession session;
    protected final MycatDataContext dataContext;
    protected final XaSqlConnection transactionSession;
    protected int stmtSize;
    protected final boolean binary;
    protected int count = 0;

    public ReceiverImpl(MySQLServerSession session, int stmtSize, boolean binary) {
        this.stmtSize = stmtSize;
        this.binary = binary;
        this.session = session;
        this.dataContext = this.session.getDataContext();
        this.transactionSession = (XaSqlConnection) this.dataContext.getTransactionSession();
    }

    @Override
    public int getResultSetCounter() {
        return this.count;
    }

    @Override
    public void resetResultSetSize(int size) {
        this.stmtSize = size;
    }

    @Override
    public Future<Void> sendError(Throwable e) {
        MycatDataContext dataContext = session.getDataContext();
        dataContext.setLastMessage(e);
        return VertxUtil.newFailPromise(new RuntimeException(e));
    }

    @Override
    public Future<Void> proxySelect(List<String> targets, String statement, List<Object> params) {
        return execute(ExplainDetail.create(QUERY, targets, statement, null, params));
    }

    @Override
    public Future<Void> proxyInsert(List<String> targets, String proxyUpdate, List<Object> params) {
        return execute(ExplainDetail.create(INSERT, targets, proxyUpdate, null, params));
    }


    @Override
    public Future<Void> proxyUpdate(List<String> targets, String sql, List<Object> params) {
        return execute(ExplainDetail.create(UPDATE, Objects.requireNonNull(targets), sql, null, params));
    }

    @Override
    public Future<Void> proxyUpdateToPrototype(String proxyUpdate, List<Object> params) {
        MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);
        return proxyUpdate(Collections.singletonList(Objects.requireNonNull(metadataManager.getPrototype())), proxyUpdate, params);
    }

    @Override
    public Future<Void> proxySelectToPrototype(String statement, List<Object> params) {
        MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);
        return execute(ExplainDetail.create(QUERY_MASTER, Collections.singletonList(Objects.requireNonNull(metadataManager.getPrototype())), statement, null, params));
    }


    @Override
    public Future<Void> sendError(String errorMessage, int errorCode) {
        return VertxUtil.newFailPromise(new MycatException(errorCode, errorMessage));
    }

    @Override
    public Future<Void> sendResultSet(Observable<MysqlPayloadObject> mysqlPacketObservable) {
        count++;
        boolean hasMoreResult = hasMoreResultSet();
        IOExecutor ioExecutor = MetaClusterCurrent.wrapper(IOExecutor.class);

        return ioExecutor.executeBlocking(promise1 -> mysqlPacketObservable.subscribe(
                new MysqlPayloadObjectObserver(promise1, hasMoreResult, binary, session)));

    }

    @Override
    public Future<Void> rollback() {
        count++;
        boolean hasMoreResultSet = hasMoreResultSet();
        return transactionSession.rollback()
                .eventually((u) -> transactionSession.closeStatementState())
                .flatMap(u -> session.writeOk(hasMoreResultSet));
    }

    @Override
    public Future<Void> begin() {
        count++;
        boolean hasMoreResultSet = hasMoreResultSet();
        return transactionSession.begin()
                .eventually((u) -> transactionSession.closeStatementState())
                .flatMap(u -> session.writeOk(hasMoreResultSet));
    }

    @Override
    public Future<Void> commit() {
        count++;
        boolean moreResultSet = hasMoreResultSet();
        return transactionSession.commit().eventually((u) -> transactionSession.closeStatementState())
                .flatMap(u -> session.writeOk(moreResultSet));
    }

    @Override
    public Future<Void> rollbackSavepoint(String name) {
        count++;
        boolean hasMoreResultSet = hasMoreResultSet();
        return transactionSession.rollbackSavepoint(name)
                .eventually((u) -> transactionSession.closeStatementState())
                .flatMap(u -> session.writeOk(hasMoreResultSet));
    }

    @Override
    public Future<Void> setSavepoint(String name) {
        count++;
        boolean hasMoreResultSet = hasMoreResultSet();
        return transactionSession.createSavepoint(name)
                .eventually((u) -> transactionSession.closeStatementState())
                .flatMap(u -> session.writeOk(hasMoreResultSet));
    }

    @Override
    public Future<Void> releaseSavepoint(String name) {
        count++;
        boolean hasMoreResultSet = hasMoreResultSet();
        return transactionSession.releaseSavepoint(name)
                .eventually((u) -> transactionSession.closeStatementState())
                .flatMap(u -> session.writeOk(hasMoreResultSet));
    }

    @Override
    public Future<Void> execute(ExplainDetail detail) {
        boolean directPacket = false;
        boolean master = dataContext.isInTransaction() || !dataContext.isAutocommit() || detail.getExecuteType().isMaster();
        Set<String> targets = new HashSet<>();
        for (String target : detail.getTargets()) {
            String datasource = session.getDataContext().resolveDatasourceTargetName(target, master);
            targets.add(datasource);
        }

        String sql = detail.getSql();
        ArrayList<String> targetOrderList = new ArrayList<>(targets);
        switch (detail.getExecuteType()) {
            case QUERY:
            case QUERY_MASTER: {
                List<Observable<MysqlPayloadObject>> outputs = new LinkedList<>();
                for (int i = 0; i < targetOrderList.size(); i++) {
                    String datasource = targetOrderList.get(i);
                    Future<NewMycatConnection> connectionFuture = transactionSession.getConnection(datasource);
                    if (i == 0) {
                        // I-DEBT-69 Blade D 余项：SELECT 的后端诊断条件同样物化到会话缓冲（比 EOF/OK 早）。
                        outputs.add(VertxExecuter.runQueryOutputAsMysqlPayloadObject(connectionFuture, sql.toString(), detail.getParams(),
                                warnings -> warnings.forEach(dataContext::addWarning)));
                    } else {
                        outputs.add(VertxExecuter.runQuery(connectionFuture, sql.toString(), Collections.emptyList(), null,
                                        meta -> {
                                        }, () -> false,
                                        warnings -> warnings.forEach(dataContext::addWarning))
                                .map(row -> new MysqlObjectArrayRow(row)));
                    }
                }
                return sendResultSet(Observable.concat(outputs));
            }
            case UPDATE:
            case INSERT: {
                List<Future<SqlResult>> updateInfoList = new ArrayList<>(targetOrderList.size());
                // I-DEBT-84 (D-B')：登记本条命令在途的物理连接，供语句超时 deadline 触发时 force-close，
                // 解困阻塞在 socket read 上的 IOExecutor worker 并作废连接不入池（否则=客户端解挂却泄漏线程/连接）。
                List<NewMycatConnection> inFlight = Collections.synchronizedList(new ArrayList<>());
                for (int i = 0; i < targetOrderList.size(); i++) {
                    String datasource = targetOrderList.get(i);
                    Future<NewMycatConnection> connectionFuture = transactionSession.getConnection(datasource);
                    if (detail.getExecuteType() == INSERT) {
                        updateInfoList.add(connectionFuture
                                .map(c -> {
                                    inFlight.add(c);
                                    return c;
                                })
                                .flatMap(c -> c.insert(sql.toString(), detail.getParams())));
                    } else {
                        updateInfoList.add(connectionFuture
                                .map(c -> {
                                    inFlight.add(c);
                                    return c;
                                })
                                .flatMap(c -> c.update(sql.toString(), detail.getParams())));
                    }
                }
                CompositeFuture all = CompositeFuture.join((List) updateInfoList)
                        .onSuccess(unused -> dataContext.getTransactionSession().closeStatementState());
                Future<Void> statementLeg = all.<List<SqlResult>>map(u -> all.list()).flatMap(list -> {
                    long affectedRow = 0;
                    long lastInsertId = 0;
                    for (SqlResult sqlResult : list) {
                        affectedRow += sqlResult.getAffectRows();
                        lastInsertId += sqlResult.getLastInsertId();
                    }
                    // I-DEBT-69 Blade D：后端诊断条件物化到前端会话缓冲，比 writeOk 早。
                    for (WarningMessage warning : collectBackendWarnings(list)) {
                        dataContext.addWarning(warning);
                    }
                    return sendOk(affectedRow, lastInsertId);
                });
                // I-DEBT-84：UPDATE/INSERT 在 sendOk 前不向客户端写任何字节 ⇒ 超时以 3013 走 recover 发合法 ERR，协议安全。
                // （SELECT 流式腿须与 observer 的 resultStarted 纪律协同，另列后续子改动，见 spec §D / DEBT-QUEUE。）
                return StatementDeadline.withDeadline(statementLeg, statementDeadlineMillis(targetOrderList), () -> {
                    forceCloseInFlight(inFlight);
                    return Future.succeededFuture();
                });
            }
            default:
                throw new IllegalStateException("Unexpected value: " + detail.getExecuteType());
        }
    }

    @Override
    public Future<Void> sendOk() {
        count++;
        boolean hasMoreResultSet = hasMoreResultSet();
        return transactionSession.closeStatementState().flatMap(u -> session.writeOk(hasMoreResultSet));
    }

    @Override
    public Future<Void> sendOk(long affectedRow) {
        dataContext.setAffectedRows(affectedRow);
        return sendOk();
    }

    @Override
    public Future<Void> sendOk(long affectedRow, long lastInsertId) {
        dataContext.setLastInsertId(lastInsertId);
        dataContext.setAffectedRows(affectedRow);
        return sendOk();
    }

    @Override
    public <T> T unWrapper(Class<T> clazz) {
        return null;
    }

    @Override
    public Future<Void> swapBuffer(Observable<Buffer> sender) {
        return Future.future(new Handler<Promise<Void>>() {
            Future<Void> future = Future.succeededFuture();

            @Override
            public void handle(Promise<Void> promise) {
                session.directWriteStart();
                sender.subscribe(buffer -> future = session.directWrite(buffer),
                        throwable -> promise.tryFail(throwable),
                        () -> future.flatMap((Function<Void, Future<Void>>) unused -> session.directWriteEnd()).onComplete(event -> {
                            if (event.succeeded()) {
                                promise.tryComplete();
                            } else {
                                promise.tryFail(event.cause());
                            }
                        }));
            }
        });
    }


    protected boolean hasMoreResultSet() {
        return count < this.stmtSize;
    }

    /**
     * I-DEBT-69 Blade D：多个后端结果各自的诊断条件按处理顺序拼接。
     * 前端一条 DML 可能拆到多个数据源，任一后端产生的告警都属于本语句。
     */
    static List<WarningMessage> collectBackendWarnings(List<SqlResult> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<WarningMessage> warnings = new ArrayList<>();
        for (SqlResult result : results) {
            if (result != null) {
                warnings.addAll(result.getWarnings());
            }
        }
        return warnings;
    }

    /**
     * I-DEBT-84 (D-B')：语句超时 deadline 的全局兜底默认值（datasource 未配到时的保守界）。
     * 与 {@code DatasourceConfig.statementTimeout} 出厂默认一致。
     */
    private static final long DEFAULT_STATEMENT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);

    /**
     * 解析本条命令的语句级 deadline（毫秒）：取所涉 datasource {@code statementTimeout} 的最小值（多 DS 取最紧）。
     * <ul>
     *   <li>命中的 config 全为 {@code <=0}（显式禁用）⇒ 返回 {@code 0}，即不套 deadline；</li>
     *   <li>无任何命中 config（目标名不在数据源表）⇒ 返回保守默认 30s（保证“默认可挂”被兜住）。</li>
     * </ul>
     */
    private long statementDeadlineMillis(List<String> targets) {
        long deadline = Long.MAX_VALUE;
        boolean sawConfig = false;
        try {
            JdbcConnectionManager jdbcConnectionManager = MetaClusterCurrent.wrapper(JdbcConnectionManager.class);
            Map<String, DatasourceConfig> configMap = jdbcConnectionManager.getConfigAsMap();
            for (String target : targets) {
                DatasourceConfig cfg = configMap.get(target);
                if (cfg != null) {
                    sawConfig = true;
                    long v = cfg.getStatementTimeout();
                    if (v > 0) {
                        deadline = Math.min(deadline, v);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("resolve statement deadline failed, fall back to default", t);
        }
        if (!sawConfig) {
            return DEFAULT_STATEMENT_TIMEOUT_MILLIS;
        }
        // 命中的 config 全部显式禁用（<=0）⇒ 不界；否则取启用值的最小值。
        return deadline == Long.MAX_VALUE ? 0L : deadline;
    }

    /**
     * I-DEBT-84：对本条命令在途的物理连接逐个 force-close（弃用不入池），使阻塞在 socket read
     * 上的 IOExecutor worker 抛错解困。单个失败只记日志，不影响其余回收与向客户端报错。
     */
    private void forceCloseInFlight(List<NewMycatConnection> inFlight) {
        for (NewMycatConnection c : inFlight) {
            try {
                c.abandonConnection();
            } catch (Throwable t) {
                LOGGER.warn("statement deadline force-close connection failed", t);
            }
        }
    }

    public static class MysqlPayloadObjectObserver implements Observer<MysqlPayloadObject> {
        private final Promise<Void> promise;
        private final boolean moreResultSet;
        private boolean binary;
        private MySQLServerSession session;
        private Disposable disposable;
        Function<Object[], byte[]> convertor;
        private final AtomicLong rowCount = new AtomicLong(0);
        /** 列头是否已刷给客户端：一旦刷出，连接进入读行态，后续错误不能再补合法 ERR 包，只能关连接。 */
        private boolean resultStarted = false;

        public MysqlPayloadObjectObserver(Promise<Void> promise,
                                          boolean moreResultSet, boolean binary, MySQLServerSession session) {
            this.promise = promise;
            this.moreResultSet = moreResultSet;
            this.binary = binary;
            this.session = session;
        }

        @Override
        public void onSubscribe(@NonNull Disposable d) {
            this.disposable = d;
        }

        @Override
        public void onNext(@NonNull MysqlPayloadObject next) {
            try {
                if (next instanceof MysqlObjectArrayRow) {
                    rowCount.getAndIncrement();
                    session.writeBytes(this.convertor.apply(((MysqlObjectArrayRow) next).getRow()), false);
                } else if (next instanceof MysqlByteArrayPayloadRow) {
                    rowCount.getAndIncrement();
                    session.writeBytes(((MysqlByteArrayPayloadRow) next).getBytes(), false);
                } else if (next instanceof MySQLColumnDef) {
                    MycatRowMetaData rowMetaData = ((MySQLColumnDef) next).getMetaData();
                    // 列数包即客户端进入读行态的提交点：先置位，此后任何错误都只能关连接而非补 ERR。
                    this.resultStarted = true;
                    session.writeColumnCount(rowMetaData.getColumnCount());
                    if (!binary) {
                        convertor = ResultSetMapping.concertToDirectTextResultSet(rowMetaData);
                    } else {
                        convertor = ResultSetMapping.concertToDirectBinaryResultSet(rowMetaData);
                    }
                    Iterator<byte[]> columnIterator = MySQLPacketUtil.generateAllColumnDefPayload(rowMetaData).iterator();
                    while (columnIterator.hasNext()) {
                        session.writeBytes(columnIterator.next(), false);
                    }
                    session.writeColumnEndPacket(moreResultSet);
                }
            } catch (Throwable e) {
                onError(e);
            }
        }

        @Override
        public void onError(@NonNull Throwable throwable) {
            disposable.dispose();
            if (resultStarted) {
                // 列头已上线：连接进入读行态，此时补发裸 0xFF ERR 包属协议非法（该状态只认 行/EOF/OK），
                // 客户端会把错误包当数据行继续等终结包而永久挂死。对齐真 MySQL，强制关闭前端连接，
                // 客户端得 "Lost connection during query"；顶层 recover 因 channel 已断自动变 no-op。
                String msg = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                session.close(false, msg);
            }
            promise.tryFail(throwable);
        }

        @Override
        public void onComplete() {
            // session.getDataContext().setAffectedRows(rowCount.get());
            disposable.dispose();
            session.getDataContext().getTransactionSession().closeStatementState()
                    .onComplete(event -> {
                        session.writeRowEndPacket(moreResultSet, false)
                                .onComplete((Handler<AsyncResult>) event1 -> promise.tryComplete());
                    });
        }
    }

    @Override
    public Future<Void> sendVectorResultSet(MycatRowMetaData mycatRowMetaData,
                                            Observable<VectorSchemaRoot> rootObservable) {
        class Writer implements io.reactivex.rxjava3.functions.Function<VectorSchemaRoot, ObservableSource<? extends MysqlPayloadObject>> {
            InnerType[] types = null;

            @Override
            public ObservableSource<? extends MysqlPayloadObject> apply(VectorSchemaRoot vectorRowBatch) throws Throwable {
                int rowCount = vectorRowBatch.getRowCount();
                ArrayList<MysqlPayloadObject> objects = new ArrayList<>(rowCount);
                if (types == null) {
                    types = SchemaBuilder.getInnerTypes(vectorRowBatch);
                }
                for (int rowId = 0; rowId < rowCount; rowId++) {
                    ResultSetWriter newWriter = binary ? new SimpleBinaryWriterImpl() : new SimpleTextWriterImpl();
                    ResultWriterUtil.vectorRowBatchToResultSetWriter(vectorRowBatch, newWriter, types, rowId);
                    objects.add(MysqlByteArrayPayloadRow.of(newWriter.build()));
                }
                return Observable.fromIterable(objects);
            }
        }
        ;
        Writer writer = new Writer();
        Observable<MySQLColumnDef> mySQLColumnDefObservable = Observable.fromArray(MySQLColumnDef.of(mycatRowMetaData));
        Observable<MysqlPayloadObject> mysqlPayloadObjectObservable = rootObservable.flatMap(writer);
        Observable<MysqlPayloadObject> mysqlPacketObservable = Observable.concat(mySQLColumnDefObservable, mysqlPayloadObjectObservable);
        return sendResultSet(mysqlPacketObservable);
    }


    @Override
    public Future<Void> proxyProcedure(String sql, String targetName) {
        targetName = dataContext.resolveDatasourceTargetName(targetName, true);
        XaSqlConnection transactionSession = (XaSqlConnection) dataContext.getTransactionSession();
        Future<NewMycatConnection> mySQLManagerConnection = transactionSession.getConnection(targetName);
        Future<List<Object>> objectFuture = mySQLManagerConnection.flatMap(newMycatConnection -> {
            Future<List<Object>> call = newMycatConnection.call(sql);
            return (Future) call;
        });
        Future<List<Object>> rowBaseIteratorFuture = objectFuture.map(objects -> objects.stream().map(o -> {
            if (o instanceof long[]) return o;
            if (o instanceof SqlResult) return ((SqlResult) o).toLongs();
            if (o instanceof RowSet) return ((RowSet) o).toRowBaseIterator();
            throw new UnsupportedOperationException();
        }).collect(Collectors.toList()));
        return rowBaseIteratorFuture.flatMap(objectList -> {
            if (objectList instanceof List) {
                List list = (List) objectList;
                resetResultSetSize(list.size());
                Future<Void> future = Future.succeededFuture();
                for (Object o : list) {
                    if (o instanceof long[]) {
                        long[] r = (long[]) o;
                        future = future.flatMap(unused -> sendOk(r[0], r[1]));
                    } else if (o instanceof RowBaseIterator) {
                        RowBaseIterator rs = (RowBaseIterator) o;
                        future = future.flatMap(unused -> sendResultSet(rs));
                    }
                }
                return future;
            }
            throw new UnsupportedOperationException();
        });
    }

}