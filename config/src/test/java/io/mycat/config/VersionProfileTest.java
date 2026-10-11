package io.mycat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        // 能力位必须等于当前 getDefaultServerCapabilities() 返回值（零回归锚点）
        assertEquals(MySQLServerCapabilityFlags.getDefaultServerCapabilities(), p.capabilityMask());
    }

    @Test
    public void explicitVersionOverridesPersonaDefault() {
        VersionProfile p = VersionProfileFactory.of(ServerPersona.MYSQL_5_7, "8.0.40-mycat");
        assertEquals("8.0.40-mycat", p.serverVersionString());
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
