package io.mycat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R4/Task4 配套：tlsMode 启动覆盖助手（镜像 PersonaOverride 契约）。
 *
 * <p>兼容性 TLS 矩阵需同二进制以 -Dmycat.tls.mode=REQUIRED 起服务端（免写 server.json
 * 污染后续所有跑）：空值不动（零回归缺省 DISABLED）、非法枚举名记 WARN 不抛（绝不因启动参数
 * 拼错崩掉服务）、合法枚举名覆盖。
 */
public class TlsModeOverrideTest {

    @Test
    public void validValueOverridesTlsMode() {
        ServerConfig cfg = new ServerConfig();
        assertEquals(TlsMode.DISABLED, cfg.getTlsMode(), "缺省必须 DISABLED（零回归锚点）");
        TlsModeOverride.apply("REQUIRED", cfg);
        assertEquals(TlsMode.REQUIRED, cfg.getTlsMode());
        TlsModeOverride.apply("PREFERRED", cfg);
        assertEquals(TlsMode.PREFERRED, cfg.getTlsMode());
    }

    @Test
    public void nullOrEmptyKeepsDefaultWithoutThrowing() {
        ServerConfig cfg = new ServerConfig();
        TlsModeOverride.apply(null, cfg);
        TlsModeOverride.apply("", cfg);
        assertEquals(TlsMode.DISABLED, cfg.getTlsMode(), "无该属性/空值时保持缺省（零回归）");
    }

    @Test
    public void invalidValueKeepsCurrentAndDoesNotThrow() {
        ServerConfig cfg = new ServerConfig();
        TlsModeOverride.apply("REQUIRED", cfg);
        TlsModeOverride.apply("bogus", cfg);
        assertEquals(TlsMode.REQUIRED, cfg.getTlsMode(), "非法值不得改变已生效模式、不得抛出");
    }
}
