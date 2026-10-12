package io.mycat.config;

import io.mycat.util.MySQLDatabaseMetadata;

/**
 * I-VE-1 VersionProfile 工厂：由 persona + 可选覆盖串构造画像。
 *
 * <p>能力位中 {@code CLIENT_DEPRECATE_EOF} 由版本档案驱动（I-VE-2）：
 * 按生效版本串（覆盖串优先，否则 persona 默认串）经 {@link MySQLDatabaseMetadata#isClientDeprecateEof()}
 * 推导——不再生成后冻进启动期全局可变 static、连接期读。其余 persona 差异（字符集/插件）仍留后续。
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
        // I-VE-2：能力位 = 基础掩码 + 由版本档案推导的 CLIENT_DEPRECATE_EOF
        int capabilityMask = MySQLServerCapabilityFlags.getDefaultServerCapabilities();
        if (MySQLDatabaseMetadata.parse(versionString).isClientDeprecateEof()) {
            capabilityMask |= MySQLServerCapabilityFlags.CLIENT_DEPRECATE_EOF;
        }
        return new VersionProfile(
                versionString,
                capabilityMask,
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
