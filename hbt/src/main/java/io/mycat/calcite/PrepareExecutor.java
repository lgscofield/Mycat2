package io.mycat.calcite;

import io.mycat.AsyncMycatDataContextImpl;
import io.mycat.MycatDataContext;
import io.mycat.MycatRxJavaUtl;
import io.mycat.api.collector.MySQLColumnDef;
import io.mycat.api.collector.MysqlObjectArrayRow;
import io.mycat.api.collector.MysqlPayloadObject;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.beans.mycat.RowMetaDataFidelity;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import lombok.Getter;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Getter
public class PrepareExecutor {
    private static final Logger log = LoggerFactory.getLogger(PrepareExecutor.class);
    ArrowBindable arrowBindable = null;
    MycatBindable arrayBindable = null;
    public static interface ArrowBindable {
        ArrowObservable apply(AsyncMycatDataContextImpl newMycatDataContext, MycatRowMetaData mycatRowMetaData);
    }

    @Getter
    public static class ArrowObservable {
        MycatRowMetaData mycatRowMetaData;
        Observable<VectorSchemaRoot> observable;

        public ArrowObservable(MycatRowMetaData mycatRowMetaData, Observable<VectorSchemaRoot> observable) {
            this.mycatRowMetaData = mycatRowMetaData;
            this.observable = Objects.requireNonNull(observable);
        }

        public static ArrowObservable of(MycatRowMetaData mycatRowMetaData,
                                         Observable<VectorSchemaRoot> observable) {
            return new ArrowObservable(mycatRowMetaData, observable);
        }
    }

    public PrepareExecutor( ArrowBindable arrowBindable, MycatBindable arrayBindable ) {
        this.arrowBindable = arrowBindable;
        this.arrayBindable = arrayBindable;
    }

    public static PrepareExecutor of(ArrowBindable arrowBindable, MycatBindable arrayBindable ) {
        return new PrepareExecutor(arrowBindable, arrayBindable);
    }


    public ArrowObservable asObservableVector(AsyncMycatDataContextImpl newMycatDataContext, MycatRowMetaData mycatRowMetaData) {
        return arrowBindable.apply(newMycatDataContext, mycatRowMetaData);
    }


    @NotNull
    public static Observable<MysqlPayloadObject> getMysqlPayloadObjectObservable(
            MycatBindable bindable,
            AsyncMycatDataContextImpl newMycatDataContext,
            MycatRowMetaData rowMetaData) {
        Observable<MysqlPayloadObject> rowObservable = Observable.<MysqlPayloadObject>create(emitter -> {
            //
            // I-DEBT-97/F5 取证（挂摘点选型——observable 订阅边界，非 executeBlocking 提交边界）：
            // SELECT 1/0 经 8066 的求值链 = DefaultCommandHandler:213 executeBlocking(提交#1，handler 仅
            // 注册 onComplete 即返回，不阻塞) → MycatdbCommand.execute → ObservablePlanImplementorImpl
            // .executeQuery → 本方法（懒 Observable 创建，不求值）→ response.sendResultSet →
            // ReceiverImpl:143 executeBlocking(提交#2) → observable.subscribe(...)（订阅边界）→ 本
            // create 体（lambda 在订阅时才执行）→ bindable.bind + 内层 observable.subscribe → 本地
            // Enumerable 经 toObservable 同步枚举（mysqlDivide 在此求值）。求值线程 = 订阅线程，求值
            // 严格同步于本 lambda 体执行期内，故 install/restore 包 lambda 体 = 包求值窗口。
            // 若挂提交边界：提交#1 的 finally 摘除先于内层订阅发生（外层挂了白挂）；提交#2 覆盖不到
            // SqlResultSetService:170 / HintHandler:205 等不经 ReceiverImpl 的订阅方——单点挂本订阅
            // 边界，server/proxy/hint/内部重消费四路汇点全覆盖。
            // 远程/多源路径：远程分支 Observable subscribeOn(Schedulers.computation())
            // （AsyncMycatDataContextImpl:399），行流异步产出晚于本 lambda 体返回，槽已摘 → codegen 在
            // computation 线程求值读不到槽 → 降级静默（与"无槽不抛"兼容格一致；远程除零告警由
            // I-DEBT-69 后端告警中继腿覆盖，互补不重叠）。
            // 挂/摘协议（NewMycatDataContext 注释）：install 返回 previous、finally restore
            // （previous==null 时 ThreadLocal.remove）——严格 try/finally 包住 lambda 体全部出口，
            // 防线程池 worker 串会话/残留会话引用；嵌套求值由 previous 成对恢复不互踩。
            MycatDataContext diagnosticContext = newMycatDataContext.getContext();
            MycatDataContext previousDiagnosticContext =
                    NewMycatDataContext.installDiagnosticContext(diagnosticContext);
            try {
                createMysqlPayloadObjectObservableBody(emitter, bindable, newMycatDataContext, rowMetaData);
            } finally {
                NewMycatDataContext.restoreDiagnosticContext(previousDiagnosticContext);
            }
        });
        return rowObservable;
    }

    /**
     * I-DEBT-97/F5：{@link #getMysqlPayloadObjectObservable} 原 create lambda 体（订阅时执行，
     * 求值窗口本体；诊断槽挂/摘见上层 lambda）。
     */
    private static void createMysqlPayloadObjectObservableBody(
            io.reactivex.rxjava3.core.ObservableEmitter<MysqlPayloadObject> emitter,
            MycatBindable bindable,
            AsyncMycatDataContextImpl newMycatDataContext,
            MycatRowMetaData rowMetaData) {
            // I-DEBT-39：列头惰性化。原在首行求值前急切发列头，使首行才爆发的语句级运行期错误
            // (如本地求值 select 1/0 的 ArithmeticException)落入 observer resultStarted=true 分支被迫关连接。
            // 改为列头延到首行就绪(或空集正常终结)才发：首行前遇错则不发列头 → observer 走 resultStarted=false
            // → 顶层 recover 发合法 ERR、会话存活，对齐真 MySQL「执行成功才发元数据」语义。
            //
            // I-DEBT-34 两缝同移·缝一（列头缝）：列头以 per-query carrier 里的后端忠实轨为准<em>惰性</em>重建——
            // effective(后端忠实轨, 计划有损轨)。延迟到发送点(首行/空集补发)才构造，确保后端 onColumnDef
            // 已把首分片 MycatMySQLRowMetaData 发布进 carrier。carrier 为空(非分片源/本地聚合)时 effective
            // 原样回计划轨 → 行为与改动前一致(零回归)。行解码缝(缝二)由 Task 2 一并切忠实轨达成两缝同移。
            // I-DEBT-41 列类型唯一权威·列头缝：异构多源合并（union/join/lookup 分支列类型不同）时，
            // 单分支 carrier 不是合法权威。切片2 ①：优先用单后端 describe 实测的归并列元数据作权威
            //（后端自己算合并类型，如 bit∪bigint→DECIMAL(19)）；取不到（跨 ds/超时/列名不对齐）
            // 则退 ③ 计划轨（与解码缝一致，两缝不分叉）；直通/同构仍用 carrier 忠实轨（零回归）。
            final java.util.function.Supplier<MySQLColumnDef> columnDefSupplier = () -> {
                MycatRowMetaData authorityBackend;
                boolean suppressRaw = false;
                if (newMycatDataContext.isMergedHeterogeneousOutput()) {
                    authorityBackend = newMycatDataContext.probeMergedBackendMeta();
                    // 终审 Important-1：异构探测权威合成 SQL 不得进透传分支（计划 Review Focus#1）
                    suppressRaw = true;
                } else if (newMycatDataContext.isInternalReconsumeDecode()) {
                    // I-DEBT-41 Task 6：内部重消费（join/agg/lookup）时多源先后发布 carrier 互踩，
                    // 首写者分支不是合法权威（活靶实证 lookup 列头 BIT vs 直连 BIGINT）；行值已按
                    // 计划轨解码，列头同退计划轨保持两缝不分叉（回 I-DEBT-34 前的计划轨形态）。
                    authorityBackend = null;
                } else {
                    authorityBackend = newMycatDataContext.getBackendRowMeta().get();
                }
                MycatRowMetaData effective = suppressRaw
                        ? RowMetaDataFidelity.effectiveWithSuppressedRaw(authorityBackend, rowMetaData)
                        : RowMetaDataFidelity.effective(authorityBackend, rowMetaData);
                return MySQLColumnDef.of(effective);
            };
            final boolean[] headerSent = {false};
            try {

                Object bindObservable;
                bindObservable = bindable.bind(newMycatDataContext);
                Observable<Object[]> observable;
                if (bindObservable instanceof Observable) {
                    observable = (Observable) bindObservable;
                } else {
                    Enumerable<Object[]> enumerable = (Enumerable) bindObservable;
                    observable = toObservable(newMycatDataContext, enumerable);
                }
                observable.subscribe(objects -> {
                            if (!headerSent[0]) {
                                emitter.onNext(columnDefSupplier.get());
                                headerSent[0] = true;
                            }
                            emitter.onNext(new MysqlObjectArrayRow(objects));
                        },
                        throwable -> {
                            log.error("result-set execution failed (streaming)", throwable);
                            newMycatDataContext.endFuture()
                                    .onComplete(event -> emitter.onError(throwable));
                        }, () -> {
                            CompositeFuture compositeFuture = newMycatDataContext.endFuture();
                            compositeFuture.onSuccess(event -> {
                                if (!headerSent[0]) {   // 空结果集：正常终结时补发列头，保证客户端拿到合法 metadata+EOF
                                    emitter.onNext(columnDefSupplier.get());
                                    headerSent[0] = true;
                                }
                                emitter.onComplete();
                            });
                            compositeFuture.onFailure(event -> emitter.onError(event));
                        });
            } catch (Throwable throwable) {
                log.error("result-set execution failed (bind)", throwable);
                CompositeFuture compositeFuture = newMycatDataContext.endFuture();
                compositeFuture.onComplete(event -> emitter.onError(throwable));
            }
    }

    @NotNull
    private static Observable<Object[]> toObservable(AsyncMycatDataContextImpl context, Enumerable<Object[]> enumerable) {
        Observable<Object[]> observable;
        observable = Observable.create(emitter1 -> {
            Future future;
            try (Enumerator<Object[]> enumerator = enumerable.enumerator()) {
                while (enumerator.moveNext()) {
                    emitter1.onNext(enumerator.current());
                }
                future = Future.succeededFuture();
            } catch (Throwable throwable) {
                future = Future.failedFuture(throwable);
            }
            CompositeFuture.join(future, context.endFuture())
                    .onSuccess(event -> emitter1.onComplete())
                    .onFailure(event -> emitter1.onError(event));
        });
        return observable;
    }
    public Iterable<Object[]> asObservableObjectArray( AsyncMycatDataContextImpl newMycatDataContext){
        Object bindObservable = arrayBindable.bind(newMycatDataContext);
        if (bindObservable instanceof Observable) {
            return MycatRxJavaUtl.blockingIterable( ((Observable) bindObservable));
        } else {
            Enumerable<Object[]> enumerable = (Enumerable) bindObservable;
            return enumerable;
        }
    }
    public RowBaseIterator asRowBaseIterator(AsyncMycatDataContextImpl newMycatDataContext, MycatRowMetaData mycatRowMetaData){
        // I-DEBT-97/F5：阻塞迭代求值入口（ExecutorProviderImpl 内部 SQL 等同步消费路径）。Enumerable
        // 路的 codegen 求值发生在 bind 返回的惰性迭代器被本方法 while 循环拉取的同步段内，故 install/
        // restore 包住 bind+迭代全程。严格 try/finally + previous 恢复（协议同 getMysqlPayloadObjectObservable
        // 的 I-DEBT-97 注释）：Observable 路的 blockingIterable 上游若异步产出（computation 线程），
        // 拉取线程外无槽 → 降级静默，与流式入口口径一致。
        MycatDataContext diagnosticContext = newMycatDataContext.getContext();
        MycatDataContext previousDiagnosticContext = NewMycatDataContext.installDiagnosticContext(diagnosticContext);
        try {
            Iterable<Object[]> bind = asObservableObjectArray(newMycatDataContext);
            Iterator<Object[]> iterator = bind.iterator();
            ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
            while (iterator.hasNext()){
                Object[] row = iterator.next();
                resultSetBuilder.addObjectRowPayload(row);
            }
            return resultSetBuilder.build(mycatRowMetaData);
        } finally {
            NewMycatDataContext.restoreDiagnosticContext(previousDiagnosticContext);
        }
    }
}
