package io.mycat.vertx.auth;

import io.mycat.protocol.mysql.auth.SecurityUtil;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;

/**
 * R3：服务端 caching_sha2_password 原生认证校验原语（纯计算、无 socket）。
 *
 * <p>fast-auth：服务端持明文口令 + 当连 seed，重算期望 scramble 与客户端回送值常量时间比对；
 * full-auth（Task 4 追加）：RSA 解密客户端 {@code XOR(口令, seed)} 密文后还原比对。
 */
public final class CachingSha2ServerAuth {

    private CachingSha2ServerAuth() {
    }

    /**
     * fast-auth 校验：服务端用明文口令 + 当连 seed 重算期望 scramble，与客户端回送值常量时间比对。
     *
     * @param rightPassword  服务端持有的明文口令（来自 UserAuthenticationBean，可能为 null/空）
     * @param seed           当次连接握手下发的 20 字节 nonce
     * @param clientScramble 客户端回送的 scramble（caching_sha2 为 32 字节；空口令时为 0 字节）
     * @return 口令匹配则 true
     */
    public static boolean fastAuthOk(String rightPassword, byte[] seed, byte[] clientScramble) {
        if (rightPassword == null || rightPassword.isEmpty()) {
            // 空口令：客户端应回送空 scramble
            return clientScramble == null || clientScramble.length == 0;
        }
        if (clientScramble == null || clientScramble.length == 0) {
            return false;
        }
        byte[] expected;
        try {
            expected = SecurityUtil.scrambleCachingSha2(rightPassword.getBytes(StandardCharsets.UTF_8), seed);
        } catch (Exception e) {
            return false;
        }
        // 常量时间比对（长度不等 MessageDigest.isEqual 直接 false）
        return MessageDigest.isEqual(expected, clientScramble);
    }

    /**
     * full-auth 校验：非加密连接冷态下，客户端用服务端下发的 RSA 公钥加密 {@code XOR(口令+尾0x00, seed)}
     * 回送。服务端以配对私钥解密，逆 XOR 还原出口令字节，剥掉末尾 {@code 0x00} 后与明文口令常量时间比对。
     *
     * @param rightPassword 服务端持有的明文口令（可能为 null/空）
     * @param seed          当次连接握手下发的 nonce（与客户端 XOR 时同源）
     * @param clientCipher  客户端回送的 RSA 密文
     * @param rsaPrivate    服务端 RSA 私钥（{@link RsaKeyPairProvider#privateKey()}）
     * @param oaep          true→OAEP（≥8.0.5），false→PKCS1
     * @return 口令匹配则 true；任何解密/还原异常一律 false（不把异常抛穿握手）
     */
    public static boolean fullAuthOk(String rightPassword, byte[] seed, byte[] clientCipher,
                              PrivateKey rsaPrivate, boolean oaep) {
        byte[] xored;
        try {
            Cipher de = Cipher.getInstance(
                    oaep ? "RSA/ECB/OAEPWithSHA-1AndMGF1Padding" : "RSA/ECB/PKCS1Padding");
            de.init(Cipher.DECRYPT_MODE, rsaPrivate);
            xored = de.doFinal(clientCipher);
        } catch (Exception e) {
            return false;
        }
        // 逆 XOR 还原客户端 getBytesNullTerminated 的口令字节（含末尾 0x00）
        byte[] plain = new byte[xored.length];
        SecurityUtil.xorString(xored, plain, seed, xored.length);
        int len = plain.length;
        for (int i = 0; i < plain.length; i++) {
            if (plain[i] == 0) {
                len = i;
                break;
            }
        }
        String decoded = new String(plain, 0, len, StandardCharsets.UTF_8);
        String expected = rightPassword == null ? "" : rightPassword;
        return MessageDigest.isEqual(
                decoded.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
