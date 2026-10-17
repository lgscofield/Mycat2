package io.mycat.calcite;

import io.mycat.AsyncMycatDataContextImpl;
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
        });
        return rowObservable;
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
        Iterable<Object[]> bind = asObservableObjectArray(newMycatDataContext);
        Iterator<Object[]> iterator = bind.iterator();
        ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
        while (iterator.hasNext()){
            Object[] row = iterator.next();
            resultSetBuilder.addObjectRowPayload(row);
        }
        return resultSetBuilder.build(mycatRowMetaData);
    }
}
