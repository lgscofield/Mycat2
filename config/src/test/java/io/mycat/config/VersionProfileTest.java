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
}
