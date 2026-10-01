package org.apache.calcite.util;

import io.reactivex.rxjava3.core.Observable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * I-DEBT-6 契约回归：投影算子 {@link RxBuiltInMethodImpl#select} 不得假定行必为 {@code Object[]}。
 *
 * <p>单列标量投影（如 {@code SHOW DATABASES} 被改写为 {@code select SCHEMA_NAME as \`Database\`}
 * from information_schema.SCHEMATA）的 codegen 会传入返回裸标量的 {@code Function1}；若 {@code select}
 * 被收窄为 {@code Function1<Object[], Object[]>}，javac 在其返回值处烘焙的 {@code (Object[])} 强转即抛
 * {@code ClassCastException: String -> Object[]}（本债项的冒烟断层）。是否升箱由计划自述的
 * {@code getElementType()} 与下游 {@code MycatBindable.asObjectArray} 负责，不在 {@code select} 内。
 */
class RxBuiltInMethodImplSelectTest {

    @Test
    void scalarProjectionEmitsScalarWithoutThrowing() {
        Observable<Object[]> input = Observable.just(new Object[]{"def", "mydb", "utf8mb4", "ci", null});
        // 单列标量投影：map 返回裸 String，而非 Object[] —— 泛型化前此调用无法编译
        Observable<String> projected = RxBuiltInMethodImpl.select(input, row -> (String) row[1]);
        List<String> rows = projected.toList().blockingGet();
        assertEquals(List.of("mydb"), rows);
    }

    @Test
    void multiColumnProjectionStillYieldsObjectArray() {
        Observable<Object[]> input = Observable.just(new Object[]{"def", "mydb"});
        Observable<Object[]> projected = RxBuiltInMethodImpl.select(input, row -> new Object[]{row[1], row[0]});
        List<Object[]> rows = projected.toList().blockingGet();
        assertEquals(1, rows.size());
        assertArrayEquals(new Object[]{"mydb", "def"}, rows.get(0));
    }
}
