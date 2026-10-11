package io.mycat.config;

/**
 * I-VE-1 VersionProfile：Mycat 对客户端伪装成哪个 MySQL/MariaDB 版本的画像枚举。
 *
 * <p>枚举定义全部 7 个目标 LTS 版本，但首版（"仅抽象+接线"边界）只坐实
 * {@link #MYSQL_5_7} 的成套 bundle 为零回归锚点；其余 persona 仅提供默认版本串，
 * 能力位/字符集差异（如 8.0 的 caching_sha2 默认、CLIENT_SESSION_TRACK）留 T-AUTH 落地后再填，
 * 见 spec {@code 2026-10-11-version-profile-design.md} §1 决策。
 */
public enum ServerPersona {
    MYSQL_5_7("5.7.33-mycat-2.0"),
    MYSQL_8_0("8.0.34"),
    MYSQL_8_4("8.4.0"),
    MYSQL_9_X("9.0.1"),
    MARIADB_10_6("10.6.14-MariaDB"),
    MARIADB_10_11("10.11.8-MariaDB"),
    MARIADB_11_4("11.4.2-MariaDB");

    private final String defaultVersionString;

    ServerPersona(String defaultVersionString) {
        this.defaultVersionString = defaultVersionString;
    }

    public String defaultVersionString() {
        return defaultVersionString;
    }
}
