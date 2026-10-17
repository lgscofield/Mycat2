package io.mycat.calcite;

import io.mycat.DrdsSqlWithParams;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * I-DEBT-54/D3 回归锁：写路径参数化对带 charset introducer 的字符串字面量
 * （mysqldump 的 {@code _binary '原始字节'} 形态）必须<b>不参数化</b>，并在模板中
 * 输出纯 ASCII 的 {@code _binary x'HEX'} 形态——字节保真且不依赖任何后端连接器的编码行为。
 *
 * <p>根因：{@code MySqlExportParameterVisitor} 默认把 {@code MySqlCharExpr} 折叠为
 * {@code ?}+String 参数，restore 时 introducer 丢失，非法 UTF-8 载体经连接器 utf8 再编码
 * 变 U+FFFD（EF BF BD），bit(8) 列写入即 1406/沉默损坏。
 *
 * <p>入参字符串模拟前端 {@code MySQLSqlTextCodec.decode} 的产物：0xAA 字节 → U+00AA 载体字符。
 */
public class BinaryIntroducerParameterizeTest {

    private static DrdsSqlWithParams preParse(String sql) {
        return DrdsRunnerHelper.preParse(sql, null);
    }

    @Test
    public void binaryIntroducerIllegalByteCarrierEmitsUppercaseHexAndStaysOutOfParams() {
        // 前端 decode 后 0xAA 字节载体为 U+00AA；必须输出 _binary x'AA' 且不进 params。
        // unicode 转义写载体，免受源文件编码影响：SQL 文本 = insert into t values (2,_binary 'ª','aa')
        DrdsSqlWithParams parsed = preParse("insert into t values (2,_binary '\u00AA','aa')");
        String sql = parsed.getParameterizedSQL();
        Assertions.assertTrue(sql.contains("_binary x'AA'") || sql.contains("binary x'AA'"),
                "introducer 字面量须 hex 化为纯 ASCII: " + sql);
        List<Object> params = parsed.getParams();
        Assertions.assertEquals(2, params.size(), "载体 U+00AA 不得进 params,应为 [2, \"aa\"]: " + params);
        Assertions.assertEquals("aa", params.get(1));
    }

    @Test
    public void binaryIntroducerChineseLiteralEmitsUtf8Hex() {
        // '中' 是真 Unicode 字符，encode 走标准 UTF-8 → x'E4B8AD'
        DrdsSqlWithParams parsed = preParse("insert into t values (2,_binary '\u4E2D')");
        String sql = parsed.getParameterizedSQL();
        Assertions.assertTrue(sql.contains("x'E4B8AD'"), "中文 introducer 字面量须输出 UTF-8 hex: " + sql);
        Assertions.assertEquals(1, parsed.getParams().size(), "introducer 不进 params: " + parsed.getParams());
    }

    @Test
    public void binaryIntroducerNullByteCarrier() {
        // NUL 载体（前端 decode 0x00 → '\u0000'）：hex 化后无需转义，纯 ASCII 保真
        DrdsSqlWithParams parsed = preParse("insert into t values (2,_binary '\u0000')");
        String sql = parsed.getParameterizedSQL();
        Assertions.assertTrue(sql.contains("x'00'"), "NUL 载体须 hex 为 x'00': " + sql);
    }

    @Test
    public void plainLiteralStillParameterized() {
        // 零回归红线：无 introducer 的字面量照常参数化为 ?
        DrdsSqlWithParams parsed = preParse("insert into t values (2,'aa')");
        String sql = parsed.getParameterizedSQL();
        List<Object> params = parsed.getParams();
        Assertions.assertTrue(sql.contains("?"), "普通字面量仍须参数化: " + sql);
        Assertions.assertEquals(2, params.size(), "params 应为 [2, \"aa\"]: " + params);
        Assertions.assertEquals("aa", params.get(1));
    }

    @Test
    public void bitVectorLiteralUntouched() {
        // I-DEBT-44 面回归红线：b'...' 字面量仍原样留在模板、不参数化
        DrdsSqlWithParams parsed = preParse("insert into t (`id`,`c`) values (2, b'10101010')");
        String sql = parsed.getParameterizedSQL();
        Assertions.assertTrue(sql.contains("b'10101010'"), "bit 字面量须原样在模板: " + sql);
    }
}
