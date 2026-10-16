package io.mycat.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * I-DEBT-51（caching_sha2 over TLS full-auth）路由锁：安全连接（TLS 已升级）下的 caching_sha2
 * full-auth 走明文回传校验（{@link VertxMySQLAuthHandler.AuthStage#SHA_FULL_TLS_CLEARPW}），
 * 非安全连接才走 RSA 公钥舞蹈（{@link VertxMySQLAuthHandler.AuthStage#SHA_FULL_ASK_PUBKEY}）。
 * 这是端到端 {@code Unknown result for fast auth: 45} 的根因面——两情混用会把 PEM 公钥包首字节
 * {@code '-'}(45) 当成状态字节。拆成纯静态路由以便单测钉死、防回归。
 */
class CachingSha2TlsFullAuthRoutingTest {

    @Test
    void secureConnectionRoutesToCleartextStage() {
        assertEquals(VertxMySQLAuthHandler.AuthStage.SHA_FULL_TLS_CLEARPW,
                VertxMySQLAuthHandler.fullAuthStageFor(true));
    }

    @Test
    void insecureConnectionRoutesToPubkeyStage() {
        assertEquals(VertxMySQLAuthHandler.AuthStage.SHA_FULL_ASK_PUBKEY,
                VertxMySQLAuthHandler.fullAuthStageFor(false));
    }
}
