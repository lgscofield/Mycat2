package io.mycat.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * I-DEBT-54/D3：SQL 文本可逆解码（MySQL 式 latin 兜底）与对称编码的向量测试。
 * 判据：合法 UTF-8 行为与标准解码完全一致（零回归红线）；非法字节恒等映射 U+00xx 且 encode 逐字节还原。
 */
class MySQLSqlTextCodecTest {

    @Test
    void asciiIsIdentity() {
        byte[] bytes = "select 1".getBytes(StandardCharsets.US_ASCII);
        Assertions.assertEquals("select 1", MySQLSqlTextCodec.decode(bytes));
        Assertions.assertArrayEquals(bytes, MySQLSqlTextCodec.encode("select 1"));
    }

    @Test
    void legalChineseDecodesSameAsStandardUtf8() {
        byte[] bytes = "中文".getBytes(StandardCharsets.UTF_8);
        // 零回归红线：合法序列解码行为必须与 new String(bytes, UTF_8) 逐字符一致
        Assertions.assertEquals(new String(bytes, StandardCharsets.UTF_8), MySQLSqlTextCodec.decode(bytes));
        Assertions.assertArrayEquals(bytes, MySQLSqlTextCodec.encode("中文"));
    }

    @Test
    void illegalBytesMapIdentityToLatinCodepoints() {
        Assertions.assertEquals("\u00AA", MySQLSqlTextCodec.decode(new byte[] { (byte) 0xAA }));
        Assertions.assertEquals("\u00FF", MySQLSqlTextCodec.decode(new byte[] { (byte) 0xFF }));
        Assertions.assertEquals("\u0000", MySQLSqlTextCodec.decode(new byte[] { 0x00 }));
    }

    @Test
    void mixedLegalAndIllegalIsByteReversible() {
        byte[] bytes = { (byte) 0xE4, (byte) 0xB8, (byte) 0xAD, (byte) 0xAA, (byte) 0xFF };
        String decoded = MySQLSqlTextCodec.decode(bytes);
        Assertions.assertEquals("中\u00AA\u00FF", decoded);
        Assertions.assertArrayEquals(bytes, MySQLSqlTextCodec.encode(decoded));
    }

    @Test
    void truncatedMultiByteSequenceBecomesCarriers() {
        // 半个“中”（E4 B8 后无延续字节）：两个非法字节各自恒等映射
        byte[] bytes = { (byte) 0xE4, (byte) 0xB8 };
        String decoded = MySQLSqlTextCodec.decode(bytes);
        Assertions.assertEquals("\u00E4\u00B8", decoded);
        Assertions.assertArrayEquals(bytes, MySQLSqlTextCodec.encode(decoded));
    }

    @Test
    void roundTripIsolatedIllegalBytes() {
        // 高位字节全部用 ASCII 隔离：任何合法前导字节（如 0xC2）都因后继非延续字节而变非法载体，
        // 保证 0x00–0xFF 全部单字节载体 round-trip 逐字节还原。
        byte[] bytes = new byte[512];
        for (int i = 0; i < 256; i++) {
            bytes[2 * i] = (byte) i;
            bytes[2 * i + 1] = (byte) ('a' + (i % 26));
        }
        String decoded = MySQLSqlTextCodec.decode(bytes);
        Assertions.assertArrayEquals(bytes, MySQLSqlTextCodec.encode(decoded));
    }

    @Test
    void knownAmbiguityWindowDocumented() {
        // I-DEBT-54/D3b 挂账窗口：合法 utf8 序列 c2aa（真 'ª'）解码后与非法字节 aa 的载体同为 U+00AA，
        // encode 产出单字节 aa（真 MySQL 对 _binary 字面量会存原始 c2aa 两字节）。String 载体方案不可区分，
        // 已裁定挂账另票，本测试仅钉住该行为防无声漂移。
        byte[] bytes = { (byte) 0xC2, (byte) 0xAA };
        String decoded = MySQLSqlTextCodec.decode(bytes);
        Assertions.assertEquals("\u00AA", decoded);
        Assertions.assertArrayEquals(new byte[] { (byte) 0xAA }, MySQLSqlTextCodec.encode(decoded));
    }
}
