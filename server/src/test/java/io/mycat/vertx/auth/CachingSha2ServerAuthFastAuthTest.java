package io.mycat.vertx.auth;

import io.mycat.protocol.mysql.auth.SecurityUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3-T3：caching_sha2 fast-auth 纯校验（无 socket）。服务端持明文口令 + 当连 seed，
 * 算期望 scramble 与客户端回送值常量时间比对。锁正/误口令与空口令边界（Review Focus #2）。
 */
public class CachingSha2ServerAuthFastAuthTest {

    @Test
    public void correctPasswordMatchesClientScramble() throws Exception {
        byte[] seed = "0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII); // 20 字节示例
        byte[] client = SecurityUtil.scrambleCachingSha2("s3cret".getBytes(StandardCharsets.UTF_8), seed);
        assertTrue(CachingSha2ServerAuth.fastAuthOk("s3cret", seed, client));
        assertFalse(CachingSha2ServerAuth.fastAuthOk("wrongpw", seed, client));
    }

    @Test
    public void emptyPasswordMatchesEmptyScramble() {
        // 合法空口令用户：rightPassword==""（非 null）才允许空 scramble
        assertTrue(CachingSha2ServerAuth.fastAuthOk("", "seed".getBytes(StandardCharsets.US_ASCII), new byte[0]));
        assertFalse(CachingSha2ServerAuth.fastAuthOk("", "seed".getBytes(StandardCharsets.US_ASCII), new byte[]{1, 2, 3}));
    }

    /** 评审修复 #1：rightPassword==null 表不存在/被禁 IP 用户，须拒绝，不与“合法空口令”混同（否则握手挂起+污染 warmed 缓存）。 */
    @Test
    public void unknownUserNullRightPasswordRejected() {
        assertFalse(CachingSha2ServerAuth.fastAuthOk(null, "seed".getBytes(StandardCharsets.US_ASCII), new byte[0]));
        assertFalse(CachingSha2ServerAuth.fastAuthOk(null, "seed".getBytes(StandardCharsets.US_ASCII), new byte[]{1, 2, 3}));
    }
}
