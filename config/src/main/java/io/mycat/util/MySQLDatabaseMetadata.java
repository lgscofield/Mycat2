package io.mycat.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * copy from package io.vertx.mysqlclient.impl;
 */
public class MySQLDatabaseMetadata {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLDatabaseMetadata.class);

    /** Connector/J 自述串常见的历史兼容前缀；新版 MariaDB（10.x/11.x）自述串不带它。 */
    private static final String LEGACY_MARIADB_PREFIX = "5.5.5-";

    private final String fullVersion;
    private final String productName;
    private final int majorVersion;
    private final int minorVersion;
    private final int microVersion;

    private MySQLDatabaseMetadata(String fullVersion,
                                  String productName,
                                  int majorVersion,
                                  int minorVersion,
                                  int microVersion) {
        this.fullVersion = fullVersion;
        this.productName = productName;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.microVersion = microVersion;
    }

    public static MySQLDatabaseMetadata parse(String serverVersion) {
        int majorVersion = 0;
        int minorVersion = 0;
        int microVersion = 0;

        int len = serverVersion.length();
        boolean isMariaDb = serverVersion.contains("MariaDB");
        String productName = isMariaDb ? "MariaDB" : "MySQL";

        String versionToken = null;
        // 早期实现假定 MariaDB 一律带 "5.5.5-" 前缀、固定从下标 6 起切；而 ServerPersona 的
        // MariaDB 默认串形如 "10.11.8-MariaDB"，切出来只剩单段残余（4/8/空），
        // 解析抛数组越界后被 catch 吞掉、留下半截伪版本。故改为“前缀确实存在才跳过”。
        int versionTokenStartIdx = (isMariaDb && serverVersion.startsWith(LEGACY_MARIADB_PREFIX))
                ? LEGACY_MARIADB_PREFIX.length() : 0;
        int versionTokenEndIdx = versionTokenStartIdx;
        while (versionTokenEndIdx < len) {
            char c = serverVersion.charAt(versionTokenEndIdx);
            if (c == '-' || c == ' ') {
                versionToken = serverVersion.substring(versionTokenStartIdx, versionTokenEndIdx);
                break;
            }
            versionTokenEndIdx++;
        }
        if (versionToken == null) {
            // if there's no '-' char
            versionToken = serverVersion.substring(versionTokenStartIdx);
        }

        // we assume the server version tokens follows the syntax: ${major}.${minor}.${micro}
        String[] versionTokens = versionToken.split("\\.");
        try {
            majorVersion = Integer.parseInt(versionTokens[0]);
            minorVersion = Integer.parseInt(versionTokens[1]);
            microVersion = Integer.parseInt(versionTokens[2]);
        } catch (Exception ex) {
            // make sure it does fail the connection phase
            LOGGER.warn("Incorrect parsing server version tokens", ex);
        }

        return new MySQLDatabaseMetadata(serverVersion, productName, majorVersion, minorVersion, microVersion);
    }


    public String productName() {
        return productName;
    }


    public String fullVersion() {
        return fullVersion;
    }


    public int majorVersion() {
        return majorVersion;
    }


    public int minorVersion() {
        return minorVersion;
    }

    public int microVersion() {
        return microVersion;
    }

    public boolean isClientDeprecateEof() {
        MySQLDatabaseMetadata md = this;
        if (md.majorVersion() == 5 &&
                (md.minorVersion() < 7 || (md.minorVersion() == 7 && md.microVersion() < 5))) {
            // EOF_HEADER has to be enabled for older MySQL version which does not support the CLIENT_DEPRECATE_EOF flag
            return false;
        } else {
            return true;
        }
    }
}
