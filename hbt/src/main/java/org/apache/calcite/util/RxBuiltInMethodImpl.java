package org.apache.calcite.util;


import com.google.common.collect.Iterables;
import hu.akarnokd.rxjava3.operators.Flowables;
import io.mycat.MycatRxJavaUtl;
import io.mycat.serializable.MaterializedRecordSetFactory;
import io.mycat.serializable.OffHeapObjectList;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.function.Predicate1;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class RxBuiltInMethodImpl {

    /**
     * 投影算子：对输入流每个元素应用 {@code map}。
     *
     * <p>必须是泛型 {@code <T, R>}：单列标量投影（如 {@code SHOW DATABASES} 改写为
     * {@code select SCHEMA_NAME ... }）的 codegen 会传入返回裸标量（如 String）的
     * {@code Function1}。若把签名收窄为 {@code Function1<Object[], Object[]>}，javac
     * 会在 {@code map.apply(..)} 的返回值处烘焙 {@code (Object[])} 强转，标量投影即抛
     * {@code ClassCastException: String -> Object[]}。元素是否为 {@code Object[]} 由
     * 计划自述的 {@code getElementType()} 决定，再由 {@code MycatBindable.asObjectArray}
     * 统一升箱；此处不应假定行必为 {@code Object[]}。
     *
     * <p><b>同族已收口（I-DEBT-8）</b>：本文件 {@code filter}/{@code topN}/{@code sort}/
     * {@code limit}/{@code offset}/{@code unionAll}/{@code toEnumerable}/{@code toObservable}/{@code toObservableCache}
     * 均已同 select 一样泛型化为 {@code <T>} 签名，不再烘焙 {@code (Object[])} 输入强转；
     * {@code RxBuiltInMethod} 枚举中 {@code Types.lookupMethod(..., Observable.class, ...)} 依旧命中
     * （erased 方法签名未变），既有 ARRAY 行 codegen 路径零回归。{@code matierial}/{@code batch}/
     * {@code asObservable}/{@code asGather} 保留 {@code Object[]} 声明（内部 {@code OffHeapObjectList.addObjects(Object[])}
     * 依赖行形态），上游须先经 {@code MycatBindable.asObjectArray} 升箱。</p>
     */
    public static <T, R> Observable<R> select(Observable<T> input, org.apache.calcite.linq4j.function.Function1<T, R> map) {
        return input.map(objects -> map.apply(objects));
    }

    // MYCAT CHANGE (I-DEBT-8): 同 select 泛型化，避免 janino 烘焙 (Object[]) 输入强转。
    public static <T> Observable<T> filter(Observable<T> input, Predicate1<T> filter) {
        return input.filter(objects -> filter.apply(objects));
    }


    // MYCAT CHANGE (I-DEBT-8): 泛型化 unionAll，与 select/filter 同族。
    public static <T> Observable<T> unionAll(List<Observable<T>> inputs) {
        return Observable.merge(inputs);
    }

    public static <T> Observable<T> unionAll(Observable<T> left, Observable<T> right) {
        return Observable.merge(left, right);
    }

    public static <T> Observable<T> topN(Observable<T> input, Comparator<T> sortFunction, long skip, long limit) {
        return input.sorted(sortFunction).skip(skip).take(limit);
    }

    public static <T> Observable<T> sort(Observable<T> input, Comparator<T> sortFunction) {
        return input.sorted(sortFunction);
    }

    public static <T> Observable<T> limit(Observable<T> input, long limit) {
        return input.take(limit);
    }

    public static <T> Observable<T> offset(Observable<T> input, long offset) {
        return input.skip(offset);
    }


    // MYCAT CHANGE (I-DEBT-8): toEnumerable 内部将 Observable cast 为原始型，
    // erased 返回类型仍为 Enumerable，反射命中不变；无 unchecked 警告因为本行已
    // 在 Observable 分支里对 Iterator<T> 作自然传递（iterable 元素类型由调用方推断）。
    @SuppressWarnings("unchecked")
    public static <T> Enumerable<T> toEnumerable(Object input) {
        if (input instanceof Observable) {
            Observable<T> observable = (Observable<T>) input;
            return new AbstractEnumerable<T>() {
                @Override
                public Enumerator<T> enumerator() {
                    return Linq4j.iterableEnumerator(new Iterable<T>() {
                        @NotNull
                        @Override
                        public Iterator<T> iterator() {
                            Iterator<T> iterator = MycatRxJavaUtl.blockingIterable(observable).iterator();
                            class Iter implements AutoCloseable, Iterator<T> {
                                Observable<T> observable;

                                public Iter(Observable<T> observable) {
                                    this.observable = observable;
                                }

                                @Override
                                public void close() throws Exception {
                                    //如果数据源不能在下次查询或者在mycat返回结果集结束时候返回是否后端结果集
                                    // 自动释放结果集,再此处帮助释放
//                                    while (iterator.hasNext()) {
//                                        iterator.next();
//                                    }
                                }

                                @Override
                                public boolean hasNext() {
                                    return iterator.hasNext();
                                }

                                @Override
                                public T next() {
                                    return iterator.next();
                                }
                            }
                            return new Iter(observable);
                        }
                    });
                }
            };
        }
        return (Enumerable<T>) input;
    }


    @SuppressWarnings("unchecked")
    public static <T> Observable<T> toObservable(Object input) {
        if (input instanceof Observable) {
            return (Observable<T>) input;
        }
        return Observable.fromIterable((Enumerable<T>) input);
    }

    @SuppressWarnings("unchecked")
    public static <T> Observable<T> toObservableCache(Object input) {
        return ((Observable<T>) toObservable(input)).cache();
    }

    public static Enumerable<List<Object[]>> batch(Enumerable<Object[]> input) {
        return Linq4j.asEnumerable(Iterables.partition(input, 300));
    }

    public static Observable<List<Object[]>> batch(Observable<Object[]> input) {
        Observable observable = input;
        return observable.buffer(300, 50);
    }

    public static Observable<List<Object[]>> batch(Object input) {
        if (input instanceof Observable) {
            Observable<Object[]> observable = (Observable) input;
            return observable.buffer(300, 50);
        }
        if (input instanceof Iterable) {//Enumerable
            Iterable enumerable = (Iterable) input;
            return Observable.fromIterable(Iterables.partition(enumerable, 300));
        }
        throw new UnsupportedOperationException();
    }

    public static <T> Observable<T> mergeSort(List<Observable<T>> inputs,
                                              Comparator<T> sortFunction,
                                              long skip, long limit) {
        Observable<T> observable = mergeSort(inputs, sortFunction);
        if (skip > 0) {
            observable = observable.skip(skip);
        }
        if (limit > 0) {
            observable = observable.take(limit);
        }
        return observable;
    }

    public static <T> Observable<T> mergeSort(List<Observable<T>> inputs, Comparator<T> sortFunction) {
        Flowable<T> flowable = Flowables.orderedMerge(inputs.stream().map(i -> i.toFlowable(BackpressureStrategy.BUFFER)).collect(Collectors.toList()),
                sortFunction);
        return flowable.toObservable();
    }

    public static Enumerable<Object[]> matierial(Enumerable<Object[]> input) {
        return new AbstractEnumerable<Object[]>() {

            @Override
            public Enumerator<Object[]> enumerator() {
                OffHeapObjectList recordSet = MaterializedRecordSetFactory.DEFAULT_FACTORY.createRecordSet();
                Enumerator<Object[]> enumerator = input.enumerator();
                while (enumerator.moveNext()) {
                    recordSet.addObjects(enumerator.current());
                }
                recordSet.finish();
                enumerator.close();
                return new Enumerator<Object[]>() {
                    Enumerator<Object[]> iterator = Linq4j.iterableEnumerator(recordSet);

                    @Override
                    public Object[] current() {
                        return iterator.current();
                    }

                    @Override
                    public boolean moveNext() {
                        return iterator.moveNext();
                    }

                    @Override
                    public void reset() {
                        iterator = Linq4j.iterableEnumerator(recordSet);
                    }

                    @Override
                    public void close() {
                        recordSet.close();
                    }
                };
            }
        };
    }


    public static Observable<Object[]> asObservable(Object[][] input) {
        return Observable.fromArray(input);
    }

    public static Observable<Object[]> asObservable(Object[] input) {
        return Observable.fromIterable(() -> Arrays.stream(input).map(i -> (Object[]) i).iterator());
    }

    public static Enumerable<Object[]> asGather(Object input) {
        if (input instanceof Enumerable) {
            Enumerable input1 = (Enumerable) input;
            return Linq4j.asEnumerable(() -> StreamSupport.stream(input1.spliterator(), true).iterator());
        } else {
            return asGather(toEnumerable(input));
        }
    }

    public static List<Object[]> asList(Object input) {
        if (input instanceof Observable) {
            return (List) ((Observable<?>) input).toList();
        } else if (input instanceof Enumerable) {
            return (List) ((Enumerable<?>) input).toList();
        } else if (input instanceof List) {
            return (List) input;
        } else if (input instanceof Collection) {
            return new ArrayList<>((Collection) input);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Enumerable<Object[]> enumerableOrObservableConcat(Object left, Object right) {

        if (left instanceof Enumerable && right instanceof Enumerable) {
            Enumerable enumerable = ((Enumerable<?>) left).concat((Enumerable) right);
            return enumerable;
        }
        return ((Enumerable<Object[]>) (Enumerable<?>) toEnumerable(left)).concat((Enumerable<Object[]>) (Enumerable<?>) toEnumerable(right));
    }
}
