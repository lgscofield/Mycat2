package io.mycat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-VE-1 VersionProfile 画像零回归锚点：缺省 MYSQL_5_7 的 bundle 必须逐字段等于
 * 此前散落在握手/VERSION() 的硬编码常量，防止抽象化改造引入行为漂移。
 */
public class VersionProfileTest {

    @Test
    public void mysql57BundleEqualsCurrentHardcodedDefaults() {
        VersionProfile p = VersionProfileFactory.of(ServerPersona.MYSQL_5_7, null);
        assertEquals("5.7.33-mycat-2.0", p.serverVersionString());
        assertEquals(8, p.characterSet());
        assertEquals(2, p.statusFlags());
        assertEquals("mysql_native_password", p.authPlugin());
        // 能力位 = 基础掩码 + 由档案推导的 CLIENT_DEPRECATE_EOF（5.7.33 ≥ 5.7.5 → 含该位）。
        // 服务字节零回归：此前该位由启动期全局 static 在 VertxMycatServer 叠加，现改由档案自带，同字节。
        assertEquals(MySQLServerCapabilityFlags.getDefaultServerCapabilities()
                | MySQLServerCapabilityFlags.CLIENT_DEPRECATE_EOF, p.capabilityMask());
    }

    @Test
    public void explicitVersionOverridesPersonaDefault() {
        VersionProfile p = VersionProfileFactory.of(ServerPersona.MYSQL_5_7, "8.0.40-mycat");
        assertEquals("8.0.40-mycat", p.serverVersionString());
    }

    /**
     * I-VE-2：CLIENT_DEPRECATE_EOF 由版本档案推导，不再来自启动期全局可变 static。
     * 5.7.33（persona 默认串）支持 deprecate-eof（≥​5.7.5），故 capabilityMask 必含该位。
     * RED：当前工厂只回 getDefaultServerCapabilities()（不含 EOF 位），此断言应失败。
     */
    @Test
    public void persona57CapabilityIncludesClientDeprecateEof() {
        VersionProfile p = VersionProfileFactory.of(ServerPersona.MYSQL_5_7, null);
        assertTrue((p.capabilityMask() & MySQLServerCapabilityFlags.CLIENT_DEPRECATE_EOF) != 0,
                "5.7 persona 应广告 CLIENT_DEPRECATE_EOF");
    }

    /**
     * I-VE-2：EOF 位真“由档案推导”而非无条件置位——旧版本（<5.7.5）不应广告。
     * 合成覆盖串 "5.1.0" → isClientDeprecateEof=false → 不应含该位。
     */
    @Test
    public void oldVersionCapabilityOmitsClientDeprecateEof() {
        VersionProfile p = VersionProfileFactory.of(ServerPersona.MYSQL_5_7, "5.1.0");
        assertEquals(0, p.capabilityMask() & MySQLServerCapabilityFlags.CLIENT_DEPRECATE_EOF,
                "5.1.0 旧版本不应广告 CLIENT_DEPRECATE_EOF");
    }

    @Test
    public void fromServerConfigUsesOverrideThenPersona() {
        ServerConfig cfg = new ServerConfig();
        // 默认：persona=MYSQL_5_7、serverVersion 仍为其字段默认 "5.7.33-mycat-2.0"
        assertEquals("5.7.33-mycat-2.0", VersionProfileFactory.from(cfg).serverVersionString());
        cfg.setPersona(ServerPersona.MYSQL_8_0);
        cfg.setServerVersion(null); // 清掉覆盖串 -> 走 persona 默认
        assertEquals("8.0.34", VersionProfileFactory.from(cfg).serverVersionString());
        cfg.setServerVersion("9.9.9-custom"); // 覆盖串优先
        assertEquals("9.9.9-custom", VersionProfileFactory.from(cfg).serverVersionString());
    }
}
