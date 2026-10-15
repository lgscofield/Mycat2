package io.mycat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R4/Task4 配套：tlsMode 启动覆盖助手（与 PersonaOverride 同型）。
 *
 * <p>前端 TLS 兼容矩阵（TlsAxisMatrixTest）需同一二进制以 TLS 开启态启动而**不写
 * server.json**（该文件落在 MYCAT_HOME 会静默改变之后所有启动的 TLS 态，污染后续矩阵）。
 * 故提供 JVM 系统属性 -Dmycat.tls.mode=REQUIRED|PREFERRED|DISABLED 的临时覆盖。
 *
 * <p>约束（镜像 PersonaOverride）：值 null/空 → 不动（缺省 DISABLED 零回归）；非法枚举名 →
 * 记 WARN、不抛（绝不因启动参数拼错崩掉服务）；合法枚举名 → 覆盖。必须在
 * VersionProfileFactory.from(...) 烘画像（CLIENT_SSL 能力位依 tlsMode 置位）之前调用。
 */
public final class TlsModeOverride {

    private static final Logger LOGGER = LoggerFactory.getLogger(TlsModeOverride.class);

    private TlsModeOverride() {
    }

    /**
     * 按系统属性值覆盖 cfg 的 tlsMode。非法/空值保持原样且不抛异常。
     *
     * @param sysPropValue System.getProperty("mycat.tls.mode") 的原始值，可能为 null
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
            TlsMode tlsMode = TlsMode.valueOf(sysPropValue);
            cfg.setTlsMode(tlsMode);
            LOGGER.info("tlsMode overridden by -Dmycat.tls.mode to {}", tlsMode);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("ignoring invalid -Dmycat.tls.mode='{}'; keeping tlsMode {}", sysPropValue,
                    cfg.getTlsMode());
        }
    }
}
