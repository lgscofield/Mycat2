package io.mycat.vertx;

import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.LocalDate;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * I-DEBT-42 红锁：MySQL text 协议 DATE 列的 wire 编码必须为 10 字节 ISO "yyyy-MM-dd"
 * （或 NULL 时的 0xFB 单字节标记），Connector/J {@code MysqlTextValueDecoder.decodeDate}
 * 只承认这两态。历史缺陷：{@link ResultSetMapping#concertToDirectTextResultSet} 的
 * {@code switch (columnType)} 显式列了 TIME/TIMESTAMP/BIT/...，<b>独漏 {@code Types.DATE}</b>，
 * DATE 值落 {@code default:} 分支 {@code Objects.toString(object).getBytes(UTF_8)}。
 *
 * <p>现场证据（HbtTest L84 runHBT innerJoin）：HBT Calcite 计划内部 DATE 表示是
 * {@code int} days-since-epoch（非 {@link LocalDate}），{@code Objects.toString(18262)} =
 * "18262" = 5 字节 → 客户端报 {@code Invalid length (5) for type DATE}。
 *
 * <p>本测试钉 4 种典型内部表示（{@code null} / {@link LocalDate} / {@link java.sql.Date} /
 * Calcite {@code Integer} day-count）的 wire 字节，覆盖修前后红/绿语义差。
 */
class MysqlDateWireEncodingTest {

    /** 单列 Types.DATE 的 rowMetaData，与生产 BaseRowObservable/HBT codegen 走同款构建器。 */
    private static MycatRowMetaData oneDateColumnMeta() {
        return ResultSetBuilder.create()
                .addColumnInfo("d", Types.DATE)
                .build().getMetaData();
    }

    private static byte[] encode(Object dateCellValue) {
        Function<Object[], byte[]> fn = ResultSetMapping.concertToDirectTextResultSet(oneDateColumnMeta());
        return fn.apply(new Object[]{dateCellValue});
    }

    @Test
    void nullDateEncodesAsNullMarker() {
        // MySQL text 协议 NULL 值 = 0xFB 单字节 lenenc NULL marker
        assertArrayEquals(new byte[]{(byte) 0xFB}, encode(null));
    }

    @Test
    void localDateEncodesAsTenByteIsoString() {
        // 修前后都应为绿（LocalDate.toString() 恰好 ISO "2020-01-01" = 10 字节），钉回归
        byte[] iso = "2020-01-01".getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[]{(byte) 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        System.arraycopy(iso, 0, expected, 1, 10);
        assertArrayEquals(expected, encode(LocalDate.of(2020, 1, 1)));
    }

    @Test
    void sqlDateEncodesAsTenByteIsoString() {
        // java.sql.Date.toString() 也是 ISO，本应保持绿；显式钉住不随分支变化漂移
        byte[] iso = "2020-01-01".getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[]{(byte) 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        System.arraycopy(iso, 0, expected, 1, 10);
        assertArrayEquals(expected, encode(java.sql.Date.valueOf("2020-01-01")));
    }

    @Test
    void calciteDayCountIntegerEncodesAsTenByteIsoString() {
        // I-DEBT-42 缺陷靶心：HBT Calcite codegen 直发 Object[] 时 DATE 列值 = int days-since-epoch。
        // 18262 天 = 2020-01-01。修前 default 分支 Objects.toString(18262) = "18262" 5 字节（客户端炸），
        // 修后须转 LocalDate.ofEpochDay(18262).toString() = "2020-01-01" 10 字节。
        byte[] iso = "2020-01-01".getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[]{(byte) 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        System.arraycopy(iso, 0, expected, 1, 10);
        assertArrayEquals(expected, encode(18262));
    }
}
