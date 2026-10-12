package io.mycat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R3-T7：persona 启动覆盖助手。
 *
 * <p>版本画像 {@link ServerPersona} 默认由 config.xml 的 {@code server.persona} 决定（缺省
 * {@link ServerPersona#MYSQL_5_7}，零回归锚点）。兼容性测试矩阵需在不起/不改配置的前提下，
 * 用 JVM 系统属性 {@code -Dmycat.persona=MYSQL_8_0} 临时覆盖生效画像，以便同二进制模拟不同后端版本。
 *
 * <p>约束：值 {@code null}/空 → 不动；非法枚举名 → 记 WARN、不抛（绝不因启动参数拼错崩掉服务）；
 * 合法枚举名 → 覆盖。必须在 {@code VersionProfileFactory.from(...)} 烘画像之前调用。
 */
public final class PersonaOverride {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonaOverride.class);

    private PersonaOverride() {
    }

    /**
     * 按系统属性值覆盖 cfg 的 persona。非法/空值保持原样且不抛异常。
     *
     * @param sysPropValue {@code System.getProperty("mycat.persona")} 的原始值，可能为 null
     * @param cfg          待覆盖的 {@link ServerConfig}
     */
    public static void apply(String sysPropValue, ServerConfig cfg) {
        if (sysPropValue == null || sysPropValue.isEmpty()) {
            return;
        }
        if (cfg == null) {
            return;
        }
        try {
            ServerPersona persona = ServerPersona.valueOf(sysPropValue);
            cfg.setPersona(persona);
            // 清空显式版本串，令 VersionProfileFactory.from 回落 persona 默认版本串：
            // 否则 ServerConfig 缺省的 "5.7.33-mycat-2.0" 会压制 persona，导致宣称 8.0 认证却报 5.7 版本。
            cfg.setServerVersion("");
            LOGGER.info("persona overridden by -Dmycat.persona to {}", persona);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("ignoring invalid -Dmycat.persona='{}'; keeping persona {}", sysPropValue,
                    cfg.getPersona());
        }
    }
}
