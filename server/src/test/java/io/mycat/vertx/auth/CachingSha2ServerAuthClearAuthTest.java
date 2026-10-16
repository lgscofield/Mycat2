package io.mycat.vertx.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-51（caching_sha2 over TLS full-auth）：安全连接下 full-auth 不做 RSA 公钥交换，
 * 客户端直接以 {@code 0x00} 结尾明文回传口令，服务端 {@link CachingSha2ServerAuth#clearAuthOk}
 * 剥尾 0x00 后与明文口令常量时间比对。钉死匹配/错口令/null 用户/尾终止符/空口令各支。
 */
public class CachingSha2ServerAuthClearAuthTest {

    @Test
    public void matchingClearPasswordWithNullTerminatorOk() {
        byte[] wire = "s3cret\0".getBytes(StandardCharsets.UTF_8);
        assertTrue(CachingSha2ServerAuth.clearAuthOk("s3cret", wire));
    }

    @Test
    public void wrongClearPasswordRejected() {
        byte[] wire = "wrong\0".getBytes(StandardCharsets.UTF_8);
        assertFalse(CachingSha2ServerAuth.clearAuthOk("s3cret", wire));
    }

    @Test
    public void nullRightPasswordRejectedNeverEmptyPassBypass() {
        // 不存在/被禁 IP 用户：rightPassword==null，绝不因客户端回空而放行。
        assertFalse(CachingSha2ServerAuth.clearAuthOk(null, new byte[] { 0 }));
        assertFalse(CachingSha2ServerAuth.clearAuthOk(null, "anything\0".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void emptyPasswordUserAcceptsOnlyEmptyClear() {
        assertTrue(CachingSha2ServerAuth.clearAuthOk("", new byte[] { 0 }));
        assertFalse(CachingSha2ServerAuth.clearAuthOk("", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void stripsTrailingNullTerminators() {
        byte[] wire = "pa\0\0ss\0".getBytes(StandardCharsets.UTF_8);
        // 尾部连续 0x00 应被剥净后比对，中间的 \0 属口令内容不予剥离→与 "pa\0\0ss" 不匹配明文 "password"。
        assertFalse(CachingSha2ServerAuth.clearAuthOk("password", wire));
        assertTrue(CachingSha2ServerAuth.clearAuthOk("pa\0\0ss", wire));
    }
}
