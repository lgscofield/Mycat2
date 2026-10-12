package io.mycat.vertx.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * R3-T2：服务端 caching_sha2 full-auth 所需的进程级 RSA 密钥对提供器。
 *
 * <p>握手 full-auth（非加密连接冷态）时，服务端需向客户端下发一把 RSA 公钥（PEM），
 * 客户端用其加密 {@code XOR(口令, seed)} 回送，服务端以配对私钥解密。本类懒生成 2048-bit
 * RSA 密钥对、以 MySQL 生态可解析的 PEM 形式暴露公钥。私钥仅驻内存，绝不落日志/配置/序列化。
 */
public final class RsaKeyPairProvider {

    private static final RsaKeyPairProvider INSTANCE = new RsaKeyPairProvider();

    private static final String PEM_BEGIN = "-----BEGIN PUBLIC KEY-----\n";
    private static final String PEM_END = "-----END PUBLIC KEY-----\n";
    private static final int PEM_LINE_LEN = 64;

    private volatile KeyPair keyPair;

    private RsaKeyPairProvider() {
    }

    public static RsaKeyPairProvider getInstance() {
        return INSTANCE;
    }

    private KeyPair keyPair() {
        KeyPair kp = keyPair;
        if (kp == null) {
            synchronized (this) {
                kp = keyPair;
                if (kp == null) {
                    try {
                        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                        gen.initialize(2048, new SecureRandom());
                        kp = gen.generateKeyPair();
                        keyPair = kp;
                    } catch (Exception e) {
                        throw new IllegalStateException("failed to generate server RSA keypair", e);
                    }
                }
            }
        }
        return kp;
    }

    public PublicKey publicKey() {
        return keyPair().getPublic();
    }

    public PrivateKey privateKey() {
        return keyPair().getPrivate();
    }

    /** MySQL 格式 PEM 公钥：标准头尾 + 64 字符折行的 X.509 SPKI Base64 主体。 */
    public String publicKeyPem() {
        byte[] der = publicKey().getEncoded();
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder(PEM_BEGIN);
        for (int i = 0; i < b64.length(); i += PEM_LINE_LEN) {
            sb.append(b64, i, Math.min(i + PEM_LINE_LEN, b64.length())).append('\n');
        }
        sb.append(PEM_END);
        return sb.toString();
    }
}
