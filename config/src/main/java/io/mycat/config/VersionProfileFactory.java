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

    private static final String AUTH_PLUGIN_NATIVE = "mysql_native_password";
    private static final String AUTH_PLUGIN_CACHING_SHA2 = "caching_sha2_password";
    private static final int DEFAULT_CHARACTER_SET = 8;
    private static final int DEFAULT_STATUS_FLAGS = 2;

    private VersionProfileFactory() {
    }

    /**
     * @param persona               画像枚举，决定默认版本串
     * @param overrideVersionString 显式覆盖串；为 null/空则回落 persona 默认串
     */
    public static VersionProfile of(ServerPersona persona, String overrideVersionString) {
        return of(persona, overrideVersionString, TlsMode.DISABLED);
    }

    /**
     * @param persona               画像枚举，决定默认版本串
     * @param overrideVersionString 显式覆盖串；为 null/空则回落 persona 默认串
     * @param tlsMode               R4 前端 TLS 模式；非 DISABLED 时广告 CLIENT_SSL
     */
    public static VersionProfile of(ServerPersona persona, String overrideVersionString, TlsMode tlsMode) {
        String versionString = (overrideVersionString != null && !overrideVersionString.isEmpty())
                ? overrideVersionString
                : persona.defaultVersionString();
        // I-VE-2：能力位 = 基础掩码 + 由版本档案推导的 CLIENT_DEPRECATE_EOF
        int capabilityMask = MySQLServerCapabilityFlags.getDefaultServerCapabilities();
        if (MySQLDatabaseMetadata.parse(versionString).isClientDeprecateEof()) {
            capabilityMask |= MySQLServerCapabilityFlags.CLIENT_DEPRECATE_EOF;
        }
        // R4：非 DISABLED 的 TLS 模式在握手广告 CLIENT_SSL（对齐 MySQL Connection Phase：
        // 服务端 Handshake 先宣称 CLIENT_SSL=2048，客户端方可回 SSLRequest 走同端口 STARTTLS 升级）。
        if (tlsMode != null && tlsMode.advertisesClientSsl()) {
            capabilityMask |= MySQLServerCapabilityFlags.CLIENT_SSL;
        }
        // I-AU-5：握手宣称的默认认证插件按 persona 驱动——8.0/8.4/9.x → caching_sha2_password，
        // 其余（5.7 / MariaDB 各档，后端无该插件）→ mysql_native_password。前端握手须同时支持两插件。
        String authPlugin = switch (persona) {
            case MYSQL_8_0, MYSQL_8_4, MYSQL_9_X -> AUTH_PLUGIN_CACHING_SHA2;
            default -> AUTH_PLUGIN_NATIVE;
        };
        return new VersionProfile(
                versionString,
                capabilityMask,
                authPlugin,
                DEFAULT_CHARACTER_SET,
                DEFAULT_STATUS_FLAGS);
    }

    /** 零回归默认画像 = MYSQL_5_7（DI 未注册时的回落，供 VERSION() 等消费方兜底）。 */
    public static VersionProfile defaultProfile() {
        return of(ServerPersona.MYSQL_5_7, null);
    }

    /** 由 ServerConfig 构造画像：显式 serverVersion 覆盖串优先，否则回落 persona 默认串。 */
    public static VersionProfile from(ServerConfig serverConfig) {
        return of(serverConfig.getPersona(), serverConfig.getServerVersion(), serverConfig.getTlsMode());
    }
}
