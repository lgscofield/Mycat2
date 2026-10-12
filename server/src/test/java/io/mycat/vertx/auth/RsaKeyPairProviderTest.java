package io.mycat.vertx.auth;

import io.mycat.util.CachingSha2PasswordPlugin;
import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3-T2：服务端 RSA 密钥对提供器。握手 full-auth 需向客户端下发 MySQL 生态可解析的 PEM 公钥，
 * 并以对应私钥解密客户端回送的密文。本测锁：PEM 头/尾格式正确 + 可被同树客户端解码器解回同钥。
 */
public class RsaKeyPairProviderTest {

    @Test
    public void pemRoundTripsThroughConnectorDecoder() throws Exception {
        RsaKeyPairProvider p = RsaKeyPairProvider.getInstance();
        String pem = p.publicKeyPem();
        assertNotNull(p.privateKey());
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n"), "PEM 须以标准头起始");
        assertTrue(pem.contains("-----END PUBLIC KEY-----"), "PEM 须以标准尾结束");
        // 用同树“客户端向”解码器证 PEM 能被 MySQL 生态解析
        RSAPublicKey pub = CachingSha2PasswordPlugin.decodeRSAPublicKey(pem);
        // 逐字节比密钥编码（两次独立构造的 Key 对象 equals 不可靠）
        assertArrayEquals(pub.getEncoded(), p.publicKey().getEncoded());
    }

    @Test
    public void singletonHoldsStableKeyAcrossCalls() {
        RsaKeyPairProvider a = RsaKeyPairProvider.getInstance();
        RsaKeyPairProvider b = RsaKeyPairProvider.getInstance();
        assertArrayEquals(a.publicKeyPem().getBytes(), b.publicKeyPem().getBytes(),
                "进程级单例：同一次运行内公钥 PEM 稳定");
    }
}
