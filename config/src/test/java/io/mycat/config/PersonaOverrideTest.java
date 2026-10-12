package io.mycat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R3-T7：persona 启动覆盖助手。系统属性 {@code -Dmycat.persona} 提供值时覆盖 ServerConfig 缺省画像，
 * 非法值/空值一律不改、不抛（保护启动流程）。锁覆盖/非法/空三分支。
 */
public class PersonaOverrideTest {

    @Test
    public void appliesValidPersonaOverride() {
        ServerConfig cfg = new ServerConfig(); // 默认 MYSQL_5_7
        PersonaOverride.apply("MYSQL_8_0", cfg);
        assertEquals(ServerPersona.MYSQL_8_0, cfg.getPersona());
    }

    @Test
    public void ignoresInvalidValueWithoutThrowing() {
        ServerConfig cfg = new ServerConfig();
        PersonaOverride.apply("BOGUS", cfg);
        assertEquals(ServerPersona.MYSQL_5_7, cfg.getPersona()); // 不变
    }

    @Test
    public void nullOrEmptyIsNoop() {
        ServerConfig cfg = new ServerConfig();
        cfg.setPersona(ServerPersona.MYSQL_9_X);
        PersonaOverride.apply(null, cfg);
        PersonaOverride.apply("", cfg);
        assertEquals(ServerPersona.MYSQL_9_X, cfg.getPersona()); // 保持既有覆盖值
    }

    @Test
    public void overrideClearsExplicitServerVersionSoProfileFollowsPersona() {
        ServerConfig cfg = new ServerConfig();
        // 缺省 serverVersion 为非空串，VersionProfileFactory.from 会优先用它 → persona 版本被压制。
        // 覆盖 persona 时必须清空显式版本串，令生效版本随 persona 默认串（否则宣称 caching_sha2 却报 5.7）。
        PersonaOverride.apply("MYSQL_8_0", cfg);
        assertEquals(ServerPersona.MYSQL_8_0, cfg.getPersona());
        assertEquals("", cfg.getServerVersion());
        // 经工厂构造后版本串应回落 persona 默认串
        assertEquals("8.0.34", VersionProfileFactory.from(cfg).serverVersionString());
    }
}
