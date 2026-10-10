package io.mycat.mysqlclient.decoder;

import io.vertx.core.buffer.Buffer;
import io.vertx.mysqlclient.impl.datatype.DataType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * I-DEBT-38 回归锁：NATIVE 数据源 TEXT 行解码器 {@link ObjectArrayDecoder} 对整型列的
 * 类型解析与值解码。
 *
 * <p>历史缺陷（同 I-DEBT-33 Bug B 家族）：{@link ObjectArrayDecoder#addColumn} 调
 * {@code DataType.parseDataType(type, flags, charset)}，而 vertx 真实签名是
 * {@code parseDataType(short type, int charsetIndex, int flags)}——第 3 参才是 flags。把
 * flags 与 charset 传反后，数字列的 charset(63=0x3F) 落进 flags 槽，其 0x20 位被误读为
 * "无符号"，有符号 bigint id 遂被解析成 {@code U_INT64}。而 {@code convert} 的 switch 当时
 * 只有有符号 INT8/16/24/32/64 分支、无 U_INT* 亦无 default，于是该列静默解码成 null；跨分片
 * {@code ORDER BY id} 合并时 {@code SqlFunctions.toLong(null)} 抛 CalciteException 打挂会话。
 *
 * <p>MySQL FIELD_TYPE 常量（协议字节值）：LONGLONG=8。charset 63=binary。
 */
class ObjectArrayDecoderDataTypeTest {

    private static final int FIELD_TYPE_LONGLONG = 8;
    private static final int CHARSET_BINARY = 63;

    /**
     * 构造一个列定义包 payload，字段布局严格对应
     * {@link ObjectArrayDecoder#decodeColumnDefinitionPacketPayload} 的读取顺序。
     */
    private static Buffer columnDefBuffer(int fieldType, int charset, int flags) {
        Buffer b = Buffer.buffer();
        // catalog / schema / table / orgTable：lenenc 空串
        b.appendByte((byte) 0x00);
        b.appendByte((byte) 0x00);
        b.appendByte((byte) 0x00);
        b.appendByte((byte) 0x00);
        // name / orgName：lenenc "id"
        b.appendByte((byte) 0x02).appendString("id");
        b.appendByte((byte) 0x02).appendString("id");
        // length of fixed-length fields（lenenc，恒为 10 = 0x0a）
        b.appendByte((byte) 0x0a);
        // character set (fixint 2, LE)
        b.appendByte((byte) (charset & 0xff)).appendByte((byte) ((charset >> 8) & 0xff));
        // column length (fixint 4, LE)
        b.appendIntLE(8);
        // type (1 byte)
        b.appendByte((byte) fieldType);
        // flags (fixint 2, LE)
        b.appendByte((byte) (flags & 0xff)).appendByte((byte) ((flags >> 8) & 0xff));
        // decimals (1 byte)
        b.appendByte((byte) 0x00);
        return b;
    }

    /** 把若干列值拼成一行 TEXT payload：每列 lenenc 前缀 + ASCII 串。 */
    private static Buffer rowBuffer(String... values) {
        Buffer b = Buffer.buffer();
        for (String v : values) {
            b.appendByte((byte) v.length()).appendString(v);
        }
        return b;
    }

    /**
     * 根因锁：signed BIGINT（type=LONGLONG、charset=63、flags=0）必须解析为有符号 INT64。
     * 若 addColumn 的 parseDataType 实参顺序被改回 (type, flags, charset)，则 charset(63) 落入
     * flags 槽、其 0x20 位被当成 UNSIGNED → 返回 U_INT64，本断言即红。
     */
    @Test
    void signedLonglongColumnResolvesToSignedInt64() {
        ObjectArrayDecoder decoder = new ObjectArrayDecoder();
        decoder.initColumnCount(1);
        decoder.addColumn(0, columnDefBuffer(FIELD_TYPE_LONGLONG, CHARSET_BINARY, 0));
        assertEquals(DataType.INT64, decoder.getDataTypes()[0],
                "signed BIGINT 必须是 INT64；被误判成 U_INT64 即为 parseDataType 实参顺序回归");
    }

    /**
     * 崩溃面锁：真实的 unsigned BIGINT（U_INT64）列必须解码成非 null 的 BigInteger，
     * 而不是静默 null（修复前 switch 无 U_INT64 分支→null→跨分片 ORDER BY 合并 toLong(null) 崩）。
     */
    @Test
    void unsignedInt64ColumnDecodesToBigIntegerNotNull() {
        ObjectArrayDecoder decoder = new ObjectArrayDecoder();
        decoder.initColumnCount(1);
        decoder.getDataTypes()[0] = DataType.U_INT64;
        decoder.getSigneds()[0] = false;
        Buffer payload = rowBuffer("18446744073709551615"); // 2^64-1，超 Long.MAX_VALUE
        Object[] row = decoder.convert(payload);
        assertNotNull(row[0], "U_INT64 列不得静默解码为 null");
        assertEquals(new BigInteger("18446744073709551615"), row[0]);
    }

    /** 崩溃面锁：U_INT32 列解码为 long（无符号 int 可超 Integer.MAX_VALUE）。 */
    @Test
    void unsignedInt32ColumnDecodesToLong() {
        ObjectArrayDecoder decoder = new ObjectArrayDecoder();
        decoder.initColumnCount(1);
        decoder.getDataTypes()[0] = DataType.U_INT32;
        decoder.getSigneds()[0] = false;
        Object[] row = decoder.convert(rowBuffer("4294967295")); // 2^32-1
        assertNotNull(row[0]);
        assertEquals(4294967295L, row[0]);
    }

    /** 崩溃面锁：U_INT8 列解码为 int。 */
    @Test
    void unsignedInt8ColumnDecodesToInt() {
        ObjectArrayDecoder decoder = new ObjectArrayDecoder();
        decoder.initColumnCount(1);
        decoder.getDataTypes()[0] = DataType.U_INT8;
        decoder.getSigneds()[0] = false;
        Object[] row = decoder.convert(rowBuffer("255"));
        assertNotNull(row[0]);
        assertEquals(255, row[0]);
    }

    /** 守绿：有符号 INT64 列仍正常解码为 long。 */
    @Test
    void signedInt64ColumnDecodesToLong() {
        ObjectArrayDecoder decoder = new ObjectArrayDecoder();
        decoder.initColumnCount(1);
        decoder.getDataTypes()[0] = DataType.INT64;
        decoder.getSigneds()[0] = true;
        Object[] row = decoder.convert(rowBuffer("1"));
        assertEquals(1L, row[0]);
    }
}
