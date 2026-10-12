package io.mycat.vertx.auth;

import io.mycat.protocol.mysql.auth.SecurityUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
    static boolean fastAuthOk(String rightPassword, byte[] seed, byte[] clientScramble) {
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
}
