package io.mycat.resultset;

import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.vertx.ResultSetMapping;
import org.apache.calcite.avatica.util.ByteString;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-27 活库机制锁（钉真实二进制编码路径·BLOB 值保真）。
 * <p>Mycat 前端结果集统一经 {@code ReceiverImpl} 的 Observable 观察器落
 * {@link ResultSetMapping#concertToDirectTextResultSet} / {@link ResultSetMapping#concertToDirectBinaryResultSet}。
 * 按 I-DEBT-8 不变式，BLOB/VARBINARY/BINARY 列的内部规范表示为 {@link ByteString}
 * （见 {@code BaseRowObservable.getObjects} 的 BLOB 分支 {@code value = new ByteString(...)}）。
 * <p>缺陷靶心：文本腿显式处理 {@code o instanceof ByteString → getBytes()} 原样透传（正确）；
 * 二进制腿把 object 交给 {@code BinaryResultSetResponse.convertString}，其只认 {@code byte[]}，
 * {@link ByteString} 落 {@code object.toString().getBytes()}——而 {@code ByteString.toString()}
 * 返回**十六进制 ASCII 串**（如原始 {@code [127,-1,...]} 变 {@code "7fffffffffffffff"}），
 * 沉默地把二进制结果集的 BLOB 列腐蚀成 hex 串（UserCaseTest#case14 经 useServerPrepStmts 实跑复现）。
 * <p>断言口径=客户端可见 wire 字节：二进制行必须含原始字节序列、不得含 hex ASCII 串。
 */
class BinaryBlobByteStringEncodeTest {

    /** 8 字节大端 Long.MAX_VALUE，与 UserCaseTest#case14 写入值一致。 */
    private static final byte[] RAW = new byte[]{127, -1, -1, -1, -1, -1, -1, -1};

    private static MycatRowMetaData blobMeta(int type) {
        return ResultSetBuilder.create()
                .addColumnInfo("t", "data", type, 65535, 0)
                .build().getMetaData();
    }

    private static byte[] binaryRow(int type, Object value) {
        Function<Object[], byte[]> fn = ResultSetMapping.concertToDirectBinaryResultSet(blobMeta(type));
        return fn.apply(new Object[]{value});
    }

    private static byte[] textRow(int type, Object value) {
        Function<Object[], byte[]> fn = ResultSetMapping.concertToDirectTextResultSet(blobMeta(type));
        return fn.apply(new Object[]{value});
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** 二进制腿喂 ByteString 时，hex ASCII 串 "7fffffffffffffff" 不得出现在 wire 里。 */
    private static void assertNoHexLeak(byte[] row) {
        String asString = new String(row, StandardCharsets.ISO_8859_1);
        assertFalse(asString.contains("7fffffffffffffff"),
                "二进制 wire 不得含 ByteString.toString() 的 hex 串，实际行字节=" + java.util.Arrays.toString(row));
    }

    @Test
    void binaryPreservesRawBytesForLongVarbinaryByteString() {
        byte[] row = binaryRow(Types.LONGVARBINARY, new ByteString(RAW));
        assertTrue(contains(row, RAW),
                "binary LONGVARBINARY 必须透传原始字节，实际=" + java.util.Arrays.toString(row));
        assertNoHexLeak(row);
    }

    @Test
    void binaryPreservesRawBytesForBlobByteString() {
        byte[] row = binaryRow(Types.BLOB, new ByteString(RAW));
        assertTrue(contains(row, RAW),
                "binary BLOB 必须透传原始字节，实际=" + java.util.Arrays.toString(row));
        assertNoHexLeak(row);
    }

    @Test
    void binaryPreservesRawBytesForVarbinaryByteString() {
        byte[] row = binaryRow(Types.VARBINARY, new ByteString(RAW));
        assertTrue(contains(row, RAW),
                "binary VARBINARY 必须透传原始字节，实际=" + java.util.Arrays.toString(row));
        assertNoHexLeak(row);
    }

    @Test
    void binaryStillPreservesRawBytesForPlainByteArray() {
        // 回归护栏：修前裸 byte[] 本就走 convertString 的 byte[] 分支正确，修后不得破坏
        byte[] row = binaryRow(Types.LONGVARBINARY, RAW);
        assertTrue(contains(row, RAW),
                "binary 裸 byte[] 应继续透传，实际=" + java.util.Arrays.toString(row));
    }

    @Test
    void textAlreadyPreservesRawBytesForByteString() {
        // 对照锁：文本腿显式处理 ByteString → 原始字节（修前即正确），钉死两腿一致口径
        byte[] row = textRow(Types.LONGVARBINARY, new ByteString(RAW));
        assertTrue(contains(row, RAW),
                "text LONGVARBINARY ByteString 应透传原始字节，实际=" + java.util.Arrays.toString(row));
        assertNoHexLeak(row);
    }
}
