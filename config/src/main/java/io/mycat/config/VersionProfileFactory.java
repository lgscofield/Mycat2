package io.mycat.config;

/**
 * I-VE-1 VersionProfile 工厂：由 persona + 可选覆盖串构造画像。
 *
 * <p>首版"仅抽象+接线"边界下，除版本串外的 bundle 字段对所有 persona 取当前硬编码值
 * （charset=8、statusFlags=2、plugin=mysql_native_password、caps=getDefaultServerCapabilities()），
 * 使 MYSQL_5_7 逐字节零回归；非 5.7 的能力位/字符集差异留 T-AUTH（见 spec §1）。
 */
public final class VersionProfileFactory {

    private static final String DEFAULT_AUTH_PLUGIN = "mysql_native_password";
    private static final int DEFAULT_CHARACTER_SET = 8;
    private static final int DEFAULT_STATUS_FLAGS = 2;

    private VersionProfileFactory() {
    }

    /**
     * @param persona               画像枚举，决定默认版本串
     * @param overrideVersionString 显式覆盖串；为 null/空则回落 persona 默认串
     */
    public static VersionProfile of(ServerPersona persona, String overrideVersionString) {
        String versionString = (overrideVersionString != null && !overrideVersionString.isEmpty())
                ? overrideVersionString
                : persona.defaultVersionString();
        return new VersionProfile(
                versionString,
                MySQLServerCapabilityFlags.getDefaultServerCapabilities(),
                DEFAULT_AUTH_PLUGIN,
                DEFAULT_CHARACTER_SET,
                DEFAULT_STATUS_FLAGS);
    }

    /** 零回归默认画像 = MYSQL_5_7（DI 未注册时的回落，供 VERSION() 等消费方兜底）。 */
    public static VersionProfile defaultProfile() {
        return of(ServerPersona.MYSQL_5_7, null);
    }

    /** 由 ServerConfig 构造画像：显式 serverVersion 覆盖串优先，否则回落 persona 默认串。 */
    public static VersionProfile from(ServerConfig serverConfig) {
        return of(serverConfig.getPersona(), serverConfig.getServerVersion());
    }
}
