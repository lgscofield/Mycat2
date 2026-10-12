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
        assertTrue(CachingSha2ServerAuth.fastAuthOk("", "seed".getBytes(StandardCharsets.US_ASCII), new byte[0]));
        assertTrue(CachingSha2ServerAuth.fastAuthOk(null, "seed".getBytes(StandardCharsets.US_ASCII), new byte[0]));
        assertFalse(CachingSha2ServerAuth.fastAuthOk("", "seed".getBytes(StandardCharsets.US_ASCII), new byte[]{1, 2, 3}));
    }
}
