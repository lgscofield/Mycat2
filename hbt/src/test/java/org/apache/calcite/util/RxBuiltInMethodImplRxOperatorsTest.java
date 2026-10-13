package org.apache.calcite.util;

import io.reactivex.rxjava3.core.Observable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-8 契约回归：Rx 算子 {@code filter}/{@code sort}/{@code topN}/{@code limit}/
 * {@code offset}/{@code unionAll}/{@code toEnumerable}/{@code toObservable} 不得假定行必为
 * {@code Object[]}。
 *
 * <p>与 {@link RxBuiltInMethodImplSelectTest}（I-DEBT-6 select 泛型化）同族：codegen
 * 通过 {@code RxBuiltInMethod.OBSERVABLE_*.method} 反射查方法并以 janino 烘焙 lambda
 * 桥接；若签名收窄为 {@code Observable<Object[]>}/{@code Predicate1<Object[]>}/
 * {@code Comparator<Object[]>}，janino 在桥接体首行即插入 {@code (Object[])} 输入强转，
 * 一旦上游 select 已泛型化并产出标量行（如 {@code Observable<String>}）流经本族算子，
 * 运行期抛同名 {@code ClassCastException: String -> Object[]}。
 *
 * <p>本文件所有用例均以 {@code Observable<String>}/{@code Observable<Integer>} 作输入，
 * 泛型化前<b>无法通过编译</b>（RED），泛型化后各算子行为与 Object[] 输入完全等价
 * （通过 erasure 保持二进制兼容），且 {@code RxBuiltInMethod} 枚举里 {@code Types.lookupMethod}
 * 的原始参数类型 {@code Observable.class}/{@code Predicate1.class}/{@code Comparator.class} 不变，
 * 反射查表仍命中——因此对既有 ARRAY 行 codegen 路径零回归。
 */
class RxBuiltInMethodImplRxOperatorsTest {

    @Test
    void filterAcceptsScalarObservableWithoutObjectArrayCast() {
        Observable<String> input = Observable.just("aa", "b", "ccc", "d");
        Observable<String> out = RxBuiltInMethodImpl.filter(input, s -> s.length() > 1);
        assertEquals(List.of("aa", "ccc"), out.toList().blockingGet());
    }

    @Test
    void sortAcceptsScalarObservableAndScalarComparator() {
        Observable<String> input = Observable.just("banana", "apple", "cherry");
        Observable<String> out = RxBuiltInMethodImpl.sort(input, Comparator.naturalOrder());
        assertEquals(List.of("apple", "banana", "cherry"), out.toList().blockingGet());
    }

    @Test
    void topNAcceptsScalarObservableComparatorSkipAndLimit() {
        Observable<Integer> input = Observable.just(3, 1, 4, 1, 5, 9, 2, 6);
        Observable<Integer> out = RxBuiltInMethodImpl.topN(input, Comparator.naturalOrder(), 1, 3);
        // sorted=[1,1,2,3,4,5,6,9]; skip 1=[1,2,3,4,5,6,9]; take 3=[1,2,3]
        assertEquals(List.of(1, 2, 3), out.toList().blockingGet());
    }

    @Test
    void limitAndOffsetAcceptScalarObservable() {
        Observable<String> input = Observable.just("a", "b", "c", "d", "e");
        Observable<String> limited = RxBuiltInMethodImpl.limit(input, 2);
        assertEquals(List.of("a", "b"), limited.toList().blockingGet());
        Observable<String> offseted = RxBuiltInMethodImpl.offset(input, 3);
        assertEquals(List.of("d", "e"), offseted.toList().blockingGet());
    }

    @Test
    void unionAllAcceptsScalarObservablesPairAndList() {
        Observable<String> left = Observable.just("x", "y");
        Observable<String> right = Observable.just("z");
        Observable<String> pairOut = RxBuiltInMethodImpl.unionAll(left, right);
        assertEquals(List.of("x", "y", "z"), pairOut.toList().blockingGet());

        List<Observable<String>> inputs = new ArrayList<>();
        inputs.add(left);
        inputs.add(right);
        Observable<String> listOut = RxBuiltInMethodImpl.unionAll(inputs);
        assertEquals(List.of("x", "y", "z"), listOut.toList().blockingGet());
    }

    @Test
    void toEnumerableAcceptsScalarObservableAndPreservesElementType() {
        Observable<String> input = Observable.just("a", "b", "c");
        // toEnumerable 形参 Object，T 无法从入参推导，需先取 Enumerable<?> 再窄化
        @SuppressWarnings("unchecked")
        Enumerable<String> out = (Enumerable<String>) (Enumerable<?>) RxBuiltInMethodImpl.toEnumerable(input);
        List<String> collected = new ArrayList<>();
        out.forEach(collected::add);
        assertEquals(List.of("a", "b", "c"), collected);
    }

    @Test
    void toEnumerableAcceptsEnumerableDirectlyWithoutWrapping() {
        // 非 Observable 输入 → 原样返回（旧行为），泛型化不应改动此路径
        Enumerable<Integer> input = Linq4j.asEnumerable(new Integer[] {1, 2, 3});
        @SuppressWarnings("unchecked")
        Enumerable<Integer> out = (Enumerable<Integer>) (Enumerable<?>) RxBuiltInMethodImpl.toEnumerable(input);
        assertTrue(out == input, "Enumerable 输入应透传，不做二次包装");
    }

    @Test
    void multiColumnObjectArrayFlowStillWorksAfterGenericization() {
        // 反向护栏：Object[] 行为与 select 泛型化前一致，无回归
        Observable<Object[]> input = Observable.just(
                new Object[] {"alice", 30},
                new Object[] {"bob", 25},
                new Object[] {"carol", 35});
        Observable<Object[]> filtered = RxBuiltInMethodImpl.filter(input, r -> ((Integer) r[1]) > 28);
        List<Object[]> rows = filtered.toList().blockingGet();
        assertEquals(2, rows.size());
        assertEquals("alice", rows.get(0)[0]);
        assertEquals("carol", rows.get(1)[0]);

        Observable<Object[]> sorted = RxBuiltInMethodImpl.sort(filtered,
                Comparator.comparingInt(r -> (Integer) r[1]));
        List<Object[]> sortedRows = sorted.toList().blockingGet();
        assertEquals("alice", sortedRows.get(0)[0]);
        assertEquals("carol", sortedRows.get(1)[0]);
        assertFalse(sortedRows.isEmpty());
    }
}
