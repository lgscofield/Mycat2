package io.mycat.util;

/**
 * MySQL 式 SQL 文本可逆编解码（I-DEBT-54/D3）。
 *
 * <p>真 MySQL 服务器对客户端字符集的非法字节采用"保真映射"（每个非法字节映射为同码位值的
 * latin 字符，保证可逆），而非 JDK {@code new String(bytes, UTF_8)} 的 U+FFFD 替换
 * （多个不同非法字节坍缩为同一个 U+FFFD，信息不可逆丢失——mysqldump 的
 * {@code _binary '原始字节'} 回灌在此处死锁，报 1406/沉默数据损坏）。
 *
 * <p>本类是 MySQL 兜底语义在 SQL 文本通道上的实现：{@link #decode(byte[])} 对合法 UTF-8
 * 序列的行为与标准解码逐字符一致（中文等零回归），非法字节恒等映射为 U+0000–U+00FF 载体字符；
 * {@link #encode(String)} 是其逆映射（载体字符回原字节，真实 Unicode 字符按标准 UTF-8 输出）。
 *
 * <p>已知歧义窗（挂账 I-DEBT-54/D3b）：真实 U+00xx 字符（合法序列 c2xx 解码所得）与非法字节
 * 载体在 String 层不可区分，encode 均产出单字节——与真 MySQL 的 latin1 兜底同型。写路径的
 * binary 字面量最终在参数化层 hex 化为纯 ASCII（{@code _binary x'..'}），不依赖 encode 的字节级保真，
 * 除上述窗口外无实际影响。
 */
public final class MySQLSqlTextCodec {

    private MySQLSqlTextCodec() {
    }

    /**
     * 可逆解码：合法 UTF-8 序列按标准解码；每个非法字节恒等映射为同码位字符（U+0000–U+00FF），前进一字节。
     */
    public static String decode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            int len;
            int cp;
            if (b <= 0x7F) {
                sb.append((char) b);
                i++;
                continue;
            } else if (b >= 0xC2 && b <= 0xDF) {
                len = 2;
                cp = (b & 0x1F) << 6;
            } else if (b >= 0xE0 && b <= 0xEF) {
                len = 3;
                cp = (b & 0x0F) << 12;
            } else if (b >= 0xF0 && b <= 0xF4) {
                len = 4;
                cp = (b & 0x07) << 18;
            } else {
                // 0x80-0xC1（孤立延续字节/overlong 前导）与 0xF5-0xFF（超范围）一律载体映射
                sb.append((char) b);
                i++;
                continue;
            }
            if (i + len > bytes.length || !continuationsValid(bytes, i, len, b)) {
                sb.append((char) b);
                i++;
                continue;
            }
            for (int j = 1; j < len; j++) {
                // 前导掩码值已按最高位对齐,后续只 OR 延续字节的低 6 位(按剩余延续组数移位)
                cp |= (bytes[i + j] & 0x3F) << (6 * (len - 1 - j));
            }
            sb.appendCodePoint(cp);
            i += len;
        }
        return sb.toString();
    }

    /** 延续字节形状 + 各前导类的下界校验（overlong / 代理区 / 超范围一律判非法）。 */
    private static boolean continuationsValid(byte[] bytes, int start, int len, int lead) {
        for (int j = 1; j < len; j++) {
            int c = bytes[start + j] & 0xFF;
            if (c < 0x80 || c > 0xBF) {
                return false;
            }
        }
        int firstCont = bytes[start + 1] & 0xFF;
        if (lead == 0xE0 && firstCont < 0xA0) {
            return false; // overlong 3 字节
        }
        if (lead == 0xED && firstCont > 0x9F) {
            return false; // UTF-16 代理区
        }
        if (lead == 0xF0 && firstCont < 0x90) {
            return false; // overlong 4 字节
        }
        if (lead == 0xF4 && firstCont > 0x8F) {
            return false; // 超出 U+10FFFF
        }
        return true;
    }

    /**
     * decode 的逆映射：码位 ≤0xFF 的字节载体字符输出单字节原值；其余（真实 Unicode 字符）按标准 UTF-8 编码。
     */
    public static byte[] encode(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp <= 0xFF) {
                out.write(cp);
            } else {
                byte[] utf8 = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
            }
        }
        return out.toByteArray();
    }
}
