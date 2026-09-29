/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.protocol.mysql.type;

/**
 * MySQL/MariaDB dialect detection for protocol compatibility.
 * Detects database type from server version string and provides
 * feature flags for dialect-specific behavior.
 *
 * Usage:
 *   MySQLDialect dialect = MySQLDialect.detect("10.6.15-MariaDB");
 *   if (dialect.isMariaDB()) { ... }
 */
public enum MySQLDialect {
    /**
     * MySQL 5.7.x
     */
    MYSQL_5_7("MySQL 5.7", "5.7", false),

    /**
     * MySQL 8.0.x
     */
    MYSQL_8_0("MySQL 8.0", "8.0", false),

    /**
     * MariaDB 10.x (covers 10.0 through 10.11 LTS)
     */
    MARIADB_10_X("MariaDB 10.x", "10.x", true);

    private final String displayName;
    private final String versionPattern;
    private final boolean isMariaDB;

    MySQLDialect(String displayName, String versionPattern, boolean isMariaDB) {
        this.displayName = displayName;
        this.versionPattern = versionPattern;
        this.isMariaDB = isMariaDB;
    }

    /**
     * Detect dialect from server version string.
     *
     * @param serverVersion the version string from handshake packet
     *                      e.g., "5.7.33-log", "8.0.28", "10.6.15-MariaDB"
     * @return detected dialect, defaults to MYSQL_5_7 if uncertain
     */
    public static MySQLDialect detect(String serverVersion) {
        if (serverVersion == null || serverVersion.isEmpty()) {
            return MYSQL_5_7;
        }

        // MariaDB detection: version string contains "MariaDB"
        if (serverVersion.contains("MariaDB")) {
            return MARIADB_10_X;
        }

        // MySQL version detection
        if (serverVersion.startsWith("8.0") || serverVersion.startsWith("8.")) {
            return MYSQL_8_0;
        }

        // Default to MySQL 5.7
        return MYSQL_5_7;
    }

    /**
     * Check if this is MariaDB.
     */
    public boolean isMariaDB() {
        return isMariaDB;
    }

    /**
     * Check if this is MySQL (not MariaDB).
     */
    public boolean isMySQL() {
        return !isMariaDB;
    }

    /**
     * Check if the dialect supports uca1400 collations.
     * MariaDB 10.x supports uca1400_ai_ci, etc.
     */
    public boolean supportsUca1400Collation() {
        return isMariaDB;
    }

    /**
     * Check if the dialect supports SYSTEM VERSIONED tables.
     * Only MariaDB 10.x supports this feature.
     */
    public boolean supportsSystemVersionedTables() {
        return isMariaDB;
    }

    /**
     * Check if the dialect supports XA transactions.
     * All supported dialects support XA.
     */
    public boolean supportsXaTransactions() {
        return true;
    }

    /**
     * Get the appropriate validation query for this dialect.
     * All supported dialectes use "SELECT 1" for connection validation.
     */
    public String getValidationQuery() {
        return "SELECT 1";
    }

    /**
     * Get display name for logging purposes.
     */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
