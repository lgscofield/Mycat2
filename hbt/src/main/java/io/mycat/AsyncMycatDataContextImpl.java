package io.mycat;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaSqlConnection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import hu.akarnokd.rxjava3.operators.Flowables;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.calcite.*;
import io.mycat.calcite.executor.MycatPreparedStatementUtil;
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.rewriter.Distribution;
import io.mycat.calcite.rewriter.ValueIndexCondition;
import io.mycat.calcite.rewriter.ValuePredicateAnalyzer;
import io.mycat.calcite.spm.ParamHolder;
import io.mycat.calcite.table.GlobalTable;
import io.mycat.calcite.table.MycatTransientSQLTableScan;
import io.mycat.calcite.table.NormalTable;
import io.mycat.calcite.table.ShardingTable;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.prototypeserver.mysql.VisualTableHandler;
import io.mycat.querycondition.QueryType;
import io.mycat.router.CustomRuleFunction;
import io.mycat.util.VertxUtil;
import io.mycat.vertx.VertxExecuter;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.impl.future.PromiseInternal;
import lombok.Getter;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.util.SqlString;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Getter
public abstract class AsyncMycatDataContextImpl extends NewMycatDataContextImpl {
    protected final static Logger LOGGER = LoggerFactory.getLogger(AsyncMycatDataContextImpl.class);
    protected final static Logger FULL_TABLE_SCAN_LOGGER = LoggerFactory.getLogger("FULL_TABLE_SCAN_LOGGER");
    public static int FULL_TABLE_SCAN_LIMIT = 1024;
    public static boolean FULL_TABLE_SCAN_EXCEPTION;
    final Map<String, Future<NewMycatConnection>> transactionConnnectionMap = new HashMap<>();// int transaction
    final Map<String, LimitQueue> connectionlimitMap = new HashMap<>();//not int transaction
    final List<NewMycatConnection> connectionlList = Collections.synchronizedList(new ArrayList<>());//not int transaction
    final Map<String, List<Observable<Object[]>>> shareObservable = new HashMap<>();

    /**
     * I-DEBT-34 两缝同移侧信道：跨片首分片后端忠实轨 {@code MycatMySQLRowMetaData}（{@code onColumnDef} 于
     * 首行之前回包）经此上抛至列头装配点 {@code PrepareExecutor}，供其用 {@code RowMetaDataFidelity.effective}
     * 以后端物理类型为准重建列头。identity 结果集各片同构，取首写者胜即可（{@code @Getter} 暴露供列头缝惰性读）。
     */
    private final AtomicReference<MycatRowMetaData> backendRowMeta = new AtomicReference<>();

    /** 首写者胜发布后端忠实轨 meta；null 忽略（非分片源无回包列头轨）。 */
    public void publishBackendRowMeta(MycatRowMetaData meta) {
        if (meta != null) {
            backendRowMeta.compareAndSet(null, meta);
        }
    }

    /**
     * I-DEBT-41 列类型唯一权威·异构决策位：顶层输出为异构多源合并（union/join/lookup 分支列类型
     * 不同）时，单分支 carrier 不再是合法权威——列头缝与解码缝须同读本位退计划轨一致取列类型，
     * 消除“列头来自首写者分支、行值按各自分支解码”的两缝分叉窗口。默认 false：直通/同构单源
     * 沿用 carrier 忠实轨（行为零回归）。决策一次、两缝同读（发首行前置位）。
     */
    private volatile boolean mergedHeterogeneousOutput = false;

    /** 计划构建期识别到异构合并时置位；置位后两缝均退计划轨取列类型。 */
    public void markMergedHeterogeneousOutput() {
        this.mergedHeterogeneousOutput = true;
    }

    /**
     * I-DEBT-41 切片2 ①：置位并登记顶层 Union 分支数（后端 describe 探测的完整性门槛：
     * 收集到的分支下推 SQL 数必须恰等于该值才允许探测，防半途分支缺席导致归并形状错判）。
     */
    public void markMergedHeterogeneousOutput(int unionBranchCount) {
        this.mergedHeterogeneousOutput = true;
        this.mergedExpectedBranches = unionBranchCount;
    }

    /**
     * 切片2 ①：bind 期登记分支下推 SQL（仅异构置位后收集）。混合 ds、带参(?)SQL 一律标不可探测，
     * 探测时回 ③ 计划轨（诚实保守面）。
     */
    public void registerProbeBranch(String dsKey, String sql) {
        if (!mergedHeterogeneousOutput) {
            return;
        }
        if (sql == null || sql.indexOf('?') >= 0) {
            mergedProbeUnavailable = true;
            return;
        }
        // 顶层拼接探测要求分支是纯查询块：自带 order by/limit 的分支拼接后语义漂移（limit 绑错
        // 操作数），保守弃探回 ③。
        String lower = sql.toLowerCase();
        if (lower.contains("order by") || lower.contains("limit")) {
            mergedProbeUnavailable = true;
            return;
        }
        if (mergedBranchDsKey == null) {
            mergedBranchDsKey = dsKey;
        } else if (!mergedBranchDsKey.equals(dsKey)) {
            mergedProbeUnavailable = true;
            return;
        }
        mergedBranchSqls.add(sql);
    }

    /**
     * 切片2 ①：异构合并列类型权威 = 单后端对该查询实际产出的归并列元数据（describe）。
     * 门槛全过才探测：分支数吻合、同 ds、无参；取不到（跨 ds/超时/报错）返回 null →
     * 列头缝照旧回 ③ 计划轨。一次查询只探一次，结果缓存。
     */
    public synchronized MycatRowMetaData probeMergedBackendMeta() {
        if (mergedProbeDone) {
            return mergedProbeMeta;
        }
        mergedProbeDone = true;
        if (!mergedHeterogeneousOutput || mergedProbeUnavailable
                || mergedExpectedBranches < 2 || mergedBranchSqls.size() != mergedExpectedBranches) {
            return null;
        }
        MySQLManager mySQLManager = MetaClusterCurrent.wrapper(MySQLManager.class);
        mergedProbeMeta = io.mycat.calcite.BackendMergedMetaProbe.probe(mySQLManager, mergedBranchDsKey,
                new ArrayList<>(mergedBranchSqls), 2000);
        return mergedProbeMeta;
    }

    /** 两缝（列头/解码）共读的异构权威决策。 */
    public boolean isMergedHeterogeneousOutput() {
        return mergedHeterogeneousOutput;
    }

    /**
     * I-DEBT-41 Review Focus#2（Task 6 活靶实证）：计划内含内存重消费算子（Join/Aggregate/Window/
     * Correlate/SetOp 族）时，扫描行喂给 codegen typed consumer（键值按 Calcite 坍缩类型裸转），
     * 后端忠实轨 bit byte[] 必炸——解码缝必须退计划轨。计划装配点一次置位，两缝同读：
     * 列头缝同退计划轨（多源先后发布 carrier 互踩，单分支不是合法权威；Task 6 活靶实证
     * lookup 列头 BIT vs 直连 BIGINT）；异构 Union 仍走 ① describe 探测优先。
     */
    private volatile boolean internalReconsumeDecode = false;

    public void markInternalReconsumeDecode() {
        this.internalReconsumeDecode = true;
    }

    /** 列头缝读取：内部重消费时单分支 carrier 非法权威（多源先后发布互踩），同解码缝退计划轨。 */
    public boolean isInternalReconsumeDecode() {
        return internalReconsumeDecode;
    }

    /** 解码缝单一入口：异构合并（旧锚点）或内部重消费（泛化谓词）任一命中即取计划轨。 */
    public boolean isPlanTrackDecode() {
        return mergedHeterogeneousOutput || internalReconsumeDecode;
    }

    // I-DEBT-41 切片2 ①：后端 describe 归并列元数据探测的 per-query 状态（bind 期登记，列头发送前一次探测）。
    private volatile int mergedExpectedBranches = -1;
    private volatile String mergedBranchDsKey;
    private volatile boolean mergedProbeUnavailable;
    private volatile boolean mergedProbeDone;
    private volatile MycatRowMetaData mergedProbeMeta;
    private final java.util.concurrent.CopyOnWriteArrayList<String> mergedBranchSqls =
            new java.util.concurrent.CopyOnWriteArrayList<>();


    public AsyncMycatDataContextImpl(MycatDataContext dataContext,
                                     CodeExecuterContext context,
                                     DrdsSqlWithParams drdsSqlWithParams) {
        super(dataContext, context, drdsSqlWithParams);
    }

    static class LimitQueue {
        ArrayList<LimitQueueItem> queue = new ArrayList<>();
        int index = 0;

        int tmpIndex;
    }

    static class LimitQueueItem {
        Future<NewMycatConnection> future;
        AtomicInteger countDown = new AtomicInteger();
    }

    public synchronized Future<NewMycatConnection> getConnection(String key) {
        XaSqlConnection transactionSession = (XaSqlConnection) context.getTransactionSession();
        if (context.isInTransaction()) {
            return transactionConnnectionMap
                    .computeIfAbsent(key, s -> transactionSession.getConnection(key));
        }
        MySQLManager mySQLManager = MetaClusterCurrent.wrapper(MySQLManager.class);
        int limit = Math.min(AsyncMycatDataContextImpl.FULL_TABLE_SCAN_LIMIT, mySQLManager.getAvailableNumber(key));
        if (limit < 1) {
            limit = 1;
        }
        LimitQueue limitQueue = connectionlimitMap.computeIfAbsent(key, s -> {
            return new LimitQueue();
        });

        int index = limitQueue.queue.size();
        Future<NewMycatConnection> connectionFuture;
        if (index < limit) {
            connectionFuture = mySQLManager.getConnection(key).flatMap(newMycatConnection -> {
                connectionlList.add(newMycatConnection);
                return Future.succeededFuture( newMycatConnection);
            });
            LimitQueueItem limitQueueItem = new LimitQueueItem();
            limitQueueItem.future = connectionFuture;
            limitQueueItem.countDown.incrementAndGet();
            limitQueue.queue.add(index, limitQueueItem);
        } else {
            limitQueue.tmpIndex = (limitQueue.index++) % limitQueue.queue.size();
            LimitQueueItem limitQueueItem = limitQueue.queue.get(limitQueue.tmpIndex);
            return limitQueueItem.future;
        }
        return connectionFuture;
    }

    public synchronized void recycleConnection(String key, Future<NewMycatConnection> connectionFuture) {
        XaSqlConnection transactionSession = (XaSqlConnection) context.getTransactionSession();
        if (context.isInTransaction()) {
            transactionConnnectionMap.put(key, connectionFuture);
            return;
        }
        LimitQueue limitQueue = connectionlimitMap.get(key);
        LimitQueueItem limitQueueItem = limitQueue.queue.get(limitQueue.tmpIndex);
        limitQueueItem.future = connectionFuture.onComplete(newMycatConnectionAsyncResult -> {
            if (newMycatConnectionAsyncResult.succeeded()) {
                NewMycatConnection mycatConnection = newMycatConnectionAsyncResult.result();
                if (limitQueueItem.countDown.decrementAndGet() < 1) {
                    mycatConnection.close();
                }
            }
        });
    }

    public static interface Queryer<T> {

        Observable<T> runQuery(Future<NewMycatConnection> sessionConnection, String sql, List<Object> extractParams, MycatRowMetaData calciteRowMetaData);
    }

    @NotNull
    public synchronized <T> List<Observable<T>> getObservables(ImmutableMultimap<String, SqlString> expand, MycatRowMetaData calciteRowMetaData, Queryer<T> queryer) {
        LinkedList<Observable<T>> observables = new LinkedList<>();
        for (Map.Entry<String, SqlString> entry : expand.entries()) {
            String key = context.resolveDatasourceTargetName(entry.getKey());
            SqlString sqlString = entry.getValue();
            // I-DEBT-41 切片2 ①：bind 期（订阅前同步）登记分支下推 SQL，供列头缝 describe 探测合成文本
            registerProbeBranch(key, sqlString.getSql());
            Observable<T> observable = Observable.create(emitter -> {
                Future<NewMycatConnection> sessionConnection = getConnection(key);
                PromiseInternal<NewMycatConnection> promise = VertxUtil.newPromise();
                Observable<T> innerObservable = Objects.requireNonNull(queryer.runQuery(sessionConnection,
                        sqlString.getSql(),
                        MycatPreparedStatementUtil.extractParams(drdsSqlWithParams.getParams(), sqlString.getDynamicParameters()), calciteRowMetaData));
                innerObservable.subscribe(objects -> {
                            emitter.onNext((objects));
                        },
                        throwable -> {
                            sessionConnection.onSuccess(c -> {
                                        //close connection?
                                        emitter.onError(throwable);
                                        promise.tryFail(throwable);
                                    })
                                    .onFailure(t -> {
                                        emitter.onError(throwable);
                                        promise.tryFail(t);
                                    });
                        }, () -> {
                            sessionConnection.onSuccess(c -> {
                                promise.tryComplete(c);
                            }).onFailure(t -> {
                                emitter.onError(t);
                                promise.tryFail(t);
                            });
                            ;
                        });
                recycleConnection(key,
                        promise.future()
                                .onSuccess(c -> {
                                    emitter.onComplete();
                                })
                                .onFailure(t -> {
                                    emitter.onError(t);
                                }));
            });
            observables.add(observable);
        }
        return observables;
    }

    public synchronized CompositeFuture endFuture() {
        return CompositeFuture.join((List) ImmutableList.builder()
                .addAll(transactionConnnectionMap.values())
                .addAll(connectionlList.stream().map(i->i.close()).collect(Collectors.toList())).build());
    }

    public abstract List<Observable<Object[]>> getObservableList(String node);

    public static final class HbtMycatDataContextImpl extends AsyncMycatDataContextImpl {

        public HbtMycatDataContextImpl(MycatDataContext dataContext, CodeExecuterContext context) {
            super(dataContext, context, DrdsRunnerHelper.preParse("select 1", null));
        }

        @Override
        public List<Observable<Object[]>> getObservableList(String node) {
            MycatRelDatasourceSourceInfo mycatRelDatasourceSourceInfo = codeExecuterContext.getRelContext().get(node);
            MycatTransientSQLTableScan relNode = (MycatTransientSQLTableScan) mycatRelDatasourceSourceInfo.getRelNode();
            ImmutableMultimap<String, SqlString> multimap = ImmutableMultimap.of(relNode.getTargetName(), new SqlString(MycatSqlDialect.DEFAULT, relNode.getSql()));
            return getObservables(multimap, mycatRelDatasourceSourceInfo.getColumnInfo(),
                    (sessionConnection, sql, extractParams, calciteRowMetaData) -> VertxExecuter.runQuery(sessionConnection, sql, extractParams, calciteRowMetaData, this::publishBackendRowMeta, this::isPlanTrackDecode)
            );
        }

        @Override
        public Observable<Object[]> getObservable(String node, Function1 function1, Comparator comparator, int offset, int fetch) {
            return null;
        }
    }


    @Getter
    public static final class SqlMycatDataContextImpl extends AsyncMycatDataContextImpl {

        private DrdsSqlWithParams drdsSqlWithParams;
        private ConcurrentMap<String, List<PartitionGroup>> cache = new ConcurrentHashMap<>();
        MycatDataContext dataContext;

        public SqlMycatDataContextImpl(MycatDataContext dataContext, CodeExecuterContext context, DrdsSqlWithParams drdsSqlWithParams) {
            super(dataContext, context, drdsSqlWithParams);
            this.drdsSqlWithParams = drdsSqlWithParams;
            this.dataContext = dataContext;
        }

        public List<Observable<Object[]>> getObservableList(String node) {
            if (shareObservable.containsKey(node)) {
                return (shareObservable.get(node));
            }
            MycatRelDatasourceSourceInfo mycatRelDatasourceSourceInfo = this.codeExecuterContext.getRelContext().get(node);
            MycatView view = mycatRelDatasourceSourceInfo.getRelNode();
            List<PartitionGroup> sqlMap = getPartition(node).get();
            if ((sqlMap.size() > FULL_TABLE_SCAN_LIMIT) && FULL_TABLE_SCAN_LOGGER.isInfoEnabled()) {
                FULL_TABLE_SCAN_LOGGER.info(" warning sql:{},partition count:{},limit:{},it may be a full table scan.",
                        drdsSqlWithParams.toString(), sqlMap.size(), FULL_TABLE_SCAN_LIMIT);
                if (FULL_TABLE_SCAN_EXCEPTION) {
                    throw new MycatException("FULL_TABLE_SCAN_EXCEPTION:{}", drdsSqlWithParams.toString());
                }
            }
            boolean share = mycatRelDatasourceSourceInfo.refCount > 0;
            List<Observable<Object[]>> observables = getObservables((view
                            .apply(dataContext.getMergeUnionSize(), mycatRelDatasourceSourceInfo.getSqlTemplate(), sqlMap, drdsSqlWithParams.getParams())), mycatRelDatasourceSourceInfo.getColumnInfo(),
                    (sessionConnection, sql, extractParams, calciteRowMetaData) -> VertxExecuter.runQuery(sessionConnection, sql, extractParams, calciteRowMetaData, this::publishBackendRowMeta, this::isPlanTrackDecode)
            );
            if (share) {
                observables = observables.stream().map(i -> i.share()).collect(Collectors.toList());
                shareObservable.put(node, observables);
            }
            return observables;
        }

        public Optional<List<PartitionGroup>> getPartition(String node) {
            MycatRelDatasourceSourceInfo mycatRelDatasourceSourceInfo = this.codeExecuterContext.getRelContext().get(node);
            if (mycatRelDatasourceSourceInfo == null) return Optional.empty();
            MycatView view = mycatRelDatasourceSourceInfo.getRelNode();
            return Optional.ofNullable(cache.computeIfAbsent(node, s -> getSqlMap(codeExecuterContext.getConstantMap(), view, drdsSqlWithParams, drdsSqlWithParams.getHintDataNodeFilter())));
        }


        @Override
        public Observable<Object[]> getObservable(String node, Function1 function1, Comparator comparator, int offset, int fetch) {
            List<Observable<Object[]>> observableList = getObservableList(node).stream().map(i -> i.subscribeOn(Schedulers.computation())).collect(Collectors.toList());

            Iterable<Flowable<Object[]>> collect = observableList.stream().map(s -> Flowable.fromObservable(s, BackpressureStrategy.BUFFER)).collect(Collectors.toList());
            Flowable<Object[]> flowable = Flowables.orderedMerge(collect, (o1, o2) -> {
                Object left = function1.apply(o1);
                Object right = function1.apply(o2);
                return comparator.compare(left, right);
            });
            if (offset > 0) {
                flowable = flowable.skip(offset);
            }
            if (fetch > 0 && fetch != Integer.MAX_VALUE) {
                flowable = flowable.take(fetch);
            }
            return flowable.toObservable();
        }

    }


    @Override
    public Observable<Object[]> getObservable(String node) {
        return Observable.merge((getObservableList(node)));

    }

    public static List<PartitionGroup> getSqlMap(Map<RexNode, RexNode> constantMap,
                                                 MycatView view,
                                                 DrdsSqlWithParams drdsSqlWithParams,
                                                 Optional<List<PartitionGroup>> hintDataMapping) {
        Distribution distribution = view.getDistribution();

        Distribution.Type type = distribution.type();
        switch (type) {
            case BROADCAST: {
                Map<String, Partition> builder = new HashMap<>();
                String targetName = null;
                for (GlobalTable globalTable : distribution.getGlobalTables()) {
                    if (targetName == null) {
                        int i = ThreadLocalRandom.current().nextInt(0, globalTable.getGlobalDataNode().size());
                        Partition partition = globalTable.getGlobalDataNode().get(i);
                        targetName = partition.getTargetName();
                    }
                    builder.put(globalTable.getUniqueName(), globalTable.getDataNode());
                }
                return Collections.singletonList(new PartitionGroup(targetName, builder));
            }
            case PHY:
                Map<String, Partition> builder = new HashMap<>();
                String targetName = null;
                for (GlobalTable globalTable : distribution.getGlobalTables()) {
                    builder.put(globalTable.getUniqueName(), globalTable.getDataNode());
                }
                for (NormalTable normalTable : distribution.getNormalTables()) {
                    if (targetName == null) {
                        targetName = normalTable.getDataNode().getTargetName();
                    }
                    builder.put(normalTable.getUniqueName(), normalTable.getDataNode());
                }
                return Collections.singletonList(new PartitionGroup(targetName, builder));
            case SHARDING:
                if (hintDataMapping.isPresent()) {
                    return hintDataMapping.get();
                }

                ShardingTable shardingTable = distribution.getShardingTables().get(0);
                RexBuilder rexBuilder = MycatCalciteSupport.RexBuilder;
                RexNode condition = view.getCondition().orElse(MycatCalciteSupport.RexBuilder.makeLiteral(true));
                List<RexNode> inputConditions = new ArrayList<>(constantMap.size() + 1);

                inputConditions.add(condition);
                for (Map.Entry<RexNode, RexNode> rexNodeRexNodeEntry : constantMap.entrySet()) {
                    inputConditions.add(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, rexNodeRexNodeEntry.getKey(), rexNodeRexNodeEntry.getValue()));
                }
                ParamHolder paramHolder = ParamHolder.CURRENT_THREAD_LOCAL.get();
                paramHolder.setData(drdsSqlWithParams.getParams(), drdsSqlWithParams.getTypeNames());
                try {
                    ArrayList<RexNode> res = new ArrayList<>(inputConditions.size());
                    MycatRexExecutor.INSTANCE.reduce(rexBuilder, inputConditions, res);
                    condition = res.get(0);
                    ValuePredicateAnalyzer predicateAnalyzer = new ValuePredicateAnalyzer(shardingTable.keyMetas(true), shardingTable.getColumns().stream().map(i -> i.getColumnName()).collect(Collectors.toList()));
                    Map<QueryType, List<ValueIndexCondition>> indexConditionMap = predicateAnalyzer.translateMatch(condition);

                    List<Partition> partitions = ValueIndexCondition.getPartitions(shardingTable, shardingTable.getShardingFuntion(), indexConditionMap, drdsSqlWithParams.getParams());
                    return mapSharding(view, partitions);
                } finally {
                    paramHolder.clear();
                }
            default:
                throw new IllegalStateException("Unexpected value: " + distribution.type());
        }
    }

    public static List<PartitionGroup> mapSharding(MycatView view, List<Partition> partitionList) {
        Distribution distribution = view.getDistribution();
        List<ShardingTable> shardingTableList = distribution.getShardingTables();
        ShardingTable primaryShardingTable = shardingTableList.get(0);
        CustomRuleFunction primaryShardingFunction = primaryShardingTable.getShardingFuntion();
        HashMap<String, Partition> groupTemplate = new HashMap<>();
        for (NormalTable normalTable : distribution.getNormalTables()) {//可能存在错误的数据分布,但是错误的数据分布访问不到
            groupTemplate.put(normalTable.getUniqueName(), normalTable.getDataNode());
        }
        for (GlobalTable globalTable : distribution.getGlobalTables()) {
            groupTemplate.put(globalTable.getUniqueName(), globalTable.getDataNode());
        }
        if (distribution.getShardingTables().size() == 1) {
            List<PartitionGroup> res = new ArrayList<>(partitionList.size());
            for (Partition partition : partitionList) {
                HashMap<String, Partition> map = new HashMap<>(groupTemplate);
                map.put(primaryShardingTable.getUniqueName(), partition);
                res.add(new PartitionGroup(partition.getTargetName(), map));
            }
            return res;
        } else {
            List<ShardingTable> joinShardingTables = shardingTableList.subList(1, shardingTableList.size());
            List<PartitionGroup> res = new ArrayList<>(partitionList.size());
            for (Partition primaryPartition : partitionList) {
                HashMap<String, Partition> map = new HashMap<>(groupTemplate);
                map.put(primaryShardingTable.getUniqueName(), primaryPartition);
                for (ShardingTable joinShardingTable : joinShardingTables) {
                    CustomRuleFunction joinFunction = joinShardingTable.function();
                    if (primaryShardingFunction.isSameDistribution(joinFunction)) {
                        Partition joinPartition = joinFunction.getPartition(primaryShardingFunction.indexOf(primaryPartition));
                        map.put(joinShardingTable.getUniqueName(), joinPartition);
                    } else if (primaryShardingFunction.isSameTargetFunctionDistribution(joinFunction)) {
                        List<Partition> joinPartitions = joinShardingTable.getPartitionsByTargetName(primaryPartition.getTargetName());
                        if (joinPartitions.size() != 1) {
                            throw new IllegalArgumentException("wrong partition " + joinPartitions + " in " + view);
                        }
                        map.put(joinShardingTable.getUniqueName(), joinPartitions.get(0));
                    }
                }
                res.add(new PartitionGroup(primaryPartition.getTargetName(), map));
            }
            return res;
        }
    }


    @Override
    public Observable<Object[]> getTableObservable(String schemaName, String tableName) {
        MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);
        TableHandler tableHandler = metadataManager.getTable(schemaName, tableName);
        VisualTableHandler visualTableHandler = (VisualTableHandler) tableHandler;
        return visualTableHandler.scanAll();
    }
}
