package io.mycat.vertx.auth;

import io.mycat.util.CachingSha2PasswordPlugin;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3-T4：caching_sha2 full-auth RSA 往返锁（无 socket）。用同树"客户端向"加密器
 * {@link CachingSha2PasswordPlugin#encryptPassword} 产出 XOR(口令,seed)→RSA 密文，
 * 服务端 {@code fullAuthOk} 以配对私钥解密、去 XOR、剥尾 0x00 后比对口令。
 * 锁 OAEP/PKCS1 两种变换与口令错则拒（Review Focus #3/#4）。
 */
public class CachingSha2ServerAuthFullAuthTest {

    @Test
    public void serverDecryptsClientRsaFullAuthOaep() throws Exception {
        RsaKeyPairProvider kp = RsaKeyPairProvider.getInstance();
        byte[] seed = "0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII);
        byte[] cipher = CachingSha2PasswordPlugin.encryptPassword(
                "RSA/ECB/OAEPWithSHA-1AndMGF1Padding", kp.publicKeyPem(), "s3cret",
                new String(seed, StandardCharsets.US_ASCII), "UTF-8");
        assertTrue(CachingSha2ServerAuth.fullAuthOk("s3cret", seed, cipher, kp.privateKey(), true));
        assertFalse(CachingSha2ServerAuth.fullAuthOk("nope", seed, cipher, kp.privateKey(), true));
    }

    @Test
    public void serverDecryptsClientRsaFullAuthPkcs1() throws Exception {
        RsaKeyPairProvider kp = RsaKeyPairProvider.getInstance();
        byte[] seed = "0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII);
        byte[] cipher = CachingSha2PasswordPlugin.encryptPassword(
                "RSA/ECB/PKCS1Padding", kp.publicKeyPem(), "s3cret",
                new String(seed, StandardCharsets.US_ASCII), "UTF-8");
        assertTrue(CachingSha2ServerAuth.fullAuthOk("s3cret", seed, cipher, kp.privateKey(), false));
        assertFalse(CachingSha2ServerAuth.fullAuthOk("nope", seed, cipher, kp.privateKey(), false));
    }
}
