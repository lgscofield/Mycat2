package io.mycat.config;

/**
 * R4 前端 TLS 模式（对齐 MySQL/MariaDB 的 ssl 语义）。
 * <ul>
 *   <li>{@code DISABLED}：不广告 CLIENT_SSL，仅明文（缺省，零回归）。</li>
 *   <li>{@code PREFERRED}：广告 CLIENT_SSL，客户端可选升级；不升级则明文继续。</li>
 *   <li>{@code REQUIRED}：广告 CLIENT_SSL 且强制升级（等价 {@code require_secure_transport}）。</li>
 * </ul>
 */
public enum TlsMode {
    DISABLED,
    PREFERRED,
    REQUIRED;

    public boolean advertisesClientSsl() {
        return this != DISABLED;
    }
}
