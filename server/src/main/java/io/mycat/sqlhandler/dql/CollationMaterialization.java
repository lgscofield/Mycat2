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
package io.mycat.sqlhandler.dql;

import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.config.ServerPersona;
import io.mycat.util.MySQLDatabaseMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * I-DEBT-70：MariaDB uca1400「物化视图」保真——身份门控 + 真库自渲染快照。
 *
 * <p>背景（spec 2026-11-03-i-debt-70-uca1400-materialization-design.md §1-§2，全部实测）：
 * MariaDB ≥10.11 的 {@code SHOW COLLATION} 按会话 {@code old_mode} 的
 * {@code NO_NULL_COLLATION_IDS} 位分两档渲染：模板档 506 行（184 行 {@code uca1400_*} 的
 * Charset/Id 为 NULL）、物化档 1242 行（同 184 名 × 5 字符集展开，Id 2048…3271，无 NULL）。
 * 服务端种位的唯一判定特征是连接属性 {@code _client_name == "mysql-connector-net"}（大小写敏感、全等）。
 * Mycat 后端池是 Connector-J，永不被种 ⇒ 透传回来的恒为模板档 ⇒ MySql.Data 在
 * {@code Convert.ToInt32(reader["id"])} 处撞 DBNull 崩。
 *
 * <p>本类只做两件事：判定该不该给这次会话出物化档（{@link #shouldMaterialize}），
 * 以及**如何让真库自己把物化档渲染出来**（{@link #fetchSnapshot}）——零自造数据。
 */
public final class CollationMaterialization {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollationMaterialization.class);

    /** 前端 HandshakeResponse41 里携带客户端名的属性键。 */
    public static final String CLIENT_NAME_ATTR = "_client_name";
    /** 真库种位的唯一判定值（E8：大小写敏感、全等，带版本后缀/前后缀/空串一律不种）。 */
    public static final String CONNECTOR_NET = "mysql-connector-net";
    /** MariaDB old_mode 里控制 NULL collation id 渲染的那一位。 */
    public static final String NO_NULL_COLLATION_IDS = "NO_NULL_COLLATION_IDS";
    /** 读会话当前 old_mode。 */
    public static final String READ_OLD_MODE = "SELECT @@old_mode";

    private CollationMaterialization() {
    }

    /**
     * 身份门：只有「对面确是 Connector/NET」且「persona 是会分档的 MariaDB ≥10.11」时才出物化档。
     * 其余任何客户端维持今日透传（模板档），与它们直连真库所见一致。
     *
     * <p>判定式即真库自身的判定式（E5 二分矩阵 + E8 形状矩阵）：键 {@code _client_name}、值全等且区分大小写。
     */
    public static boolean shouldMaterialize(Map<String, String> clientConnectAttrs, ServerPersona persona) {
        if (clientConnectAttrs == null || clientConnectAttrs.isEmpty()) {
            return false;
        }
        // 只有引入 uca1400 且按 old_mode 分档渲染的两档才需要物化（10.6 无 uca1400，MySQL 各档无此机制）。
        if (persona != ServerPersona.MARIADB_10_11 && persona != ServerPersona.MARIADB_11_4) {
            return false;
        }
        return CONNECTOR_NET.equals(clientConnectAttrs.get(CLIENT_NAME_ATTR));
    }

    /**
     * 后端门：借出的物理连接真得是 MariaDB ≥10.11（串档实况下不得乱设会话变量）。
     *
     * <p><b>只认版本串，不认 {@code getDatabaseProductName()}</b>——活体实测（Connector/J 9.3.0 连
     * mariadb:10.11）：驱动把产品名硬编成 {@code "MySQL"}、把 {@code getDatabaseMajor/MinorVersion()}
     * 报成 5/5，唯有 {@code getDatabaseProductVersion()} 如实返回握手里的
     * {@code "5.5.5-10.11.19-MariaDB-ubu2204"}（{@code SELECT VERSION()} 才是无前缀的）。
     * 前缀跳过与 MariaDB 判定复用本仓既有 {@link MySQLDatabaseMetadata#parse(String)}，不另起炉灶。
     */
    public static boolean supportsOldModeSeal(Connection connection) {
        try {
            String version = connection.getMetaData().getDatabaseProductVersion();
            if (version == null || !version.contains("MariaDB")) {
                return false;
            }
            MySQLDatabaseMetadata metaData = MySQLDatabaseMetadata.parse(version);
            int major = metaData.majorVersion();
            int minor = metaData.minorVersion();
            return major > 10 || (major == 10 && minor >= 11);
        } catch (Throwable throwable) {
            // 元数据读不到就不物化：宁可维持今日透传。
            return false;
        }
    }

    /** 种位语句文本：加法式（E6 实测裸 SET 会冲掉既有 {@code UTF8_IS_UTF8MB3}），逐字钉死在锁里。 */
    public static String sealSql() {
        return "SET old_mode=CONCAT_WS(',',NULLIF(@@old_mode,''),'" + NO_NULL_COLLATION_IDS + "')";
    }

    /** 复原语句文本：把前值原样写回（空值 → {@code SET old_mode=''}，不留本位）。 */
    public static String restoreSql(String previousOldMode) {
        String value = previousOldMode == null ? "" : previousOldMode.replace("'", "''");
        return "SET old_mode='" + value + "'";
    }

    /**
     * 取物化档快照：同一连接内四步——读前值 → 加法式种位 → 跑原 SHOW COLLATION → 复原前值。
     * 任一步抛错都尽力复原（{@code old_mode} 是物理会话态，池连接跨会话共享，泄漏即污染他人）。
     *
     * <p>读前值失败时**不动**会话（无从复原）；种位一旦“尝试过”就必发复原，哪怕它自己报错。
     *
     * @return 后端自渲染的列元数据 + 行集；{@code null} 表示取数不可用，调用方须回落到今日透传路径。
     */
    public static Snapshot fetchSnapshot(Connection connection, String showCollationSql) {
        String previous;
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(READ_OLD_MODE)) {
            if (!rs.next()) {
                return null;
            }
            Object value = rs.getObject(1);
            previous = value == null ? "" : String.valueOf(value);
        } catch (Throwable throwable) {
            return null;
        }
        boolean sealAttempted = false;
        try (Statement statement = connection.createStatement()) {
            sealAttempted = true;
            statement.executeUpdate(sealSql());
            try (ResultSet rs = statement.executeQuery(showCollationSql)) {
                return snapshotOf(rs);
            }
        } catch (Throwable throwable) {
            return null;
        } finally {
            if (sealAttempted) {
                try (Statement restore = connection.createStatement()) {
                    restore.executeUpdate(restoreSql(previous));
                } catch (Throwable throwable) {
                    LOGGER.warn("restore session old_mode failed, backend connection may keep {}",
                            NO_NULL_COLLATION_IDS, throwable);
                }
            }
        }
    }

    /** 把后端结果集的列元数据与行值原样搬进快照（列原列原序、类型照搬、NULL 保真）。 */
    private static Snapshot snapshotOf(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<ColumnSpec> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new ColumnSpec(nullToEmpty(metaData.getSchemaName(i)), nullToEmpty(metaData.getTableName(i)),
                    nullToEmpty(metaData.getColumnName(i)), metaData.getColumnType(i), metaData.getPrecision(i),
                    metaData.getScale(i), nullToEmpty(metaData.getColumnLabel(i)), metaData.isAutoIncrement(i),
                    metaData.isCaseSensitive(i), metaData.isNullable(i) != ResultSetMetaData.columnNoNulls,
                    metaData.isSigned(i), metaData.getColumnDisplaySize(i)));
        }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row[i - 1] = rs.wasNull() ? null : value;
            }
            rows.add(row);
        }
        return new Snapshot(columns, rows);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 后端自渲染结果的一次性快照（列原列原序、值原样搬运）。 */
    public static final class Snapshot {
        private final List<ColumnSpec> columns;
        private final List<Object[]> rows;

        public Snapshot(List<ColumnSpec> columns, List<Object[]> rows) {
            this.columns = Collections.unmodifiableList(columns);
            this.rows = Collections.unmodifiableList(rows);
        }

        public List<ColumnSpec> getColumns() {
            return columns;
        }

        public List<Object[]> getRows() {
            return rows;
        }

        /** 以真库自报的列元数据合成回前端的行迭代器。 */
        public ResultSetBuilder toBuilder() {
            ResultSetBuilder builder = ResultSetBuilder.create();
            for (ColumnSpec spec : columns) {
                // 上线列名必须取 JDBC 的 **getColumnLabel**，不是 getColumnName：
                // Connector/J 把 getColumnName() 定义为“表里的原始列名”(org_name)，对 SHOW COLLATION
                // 即 information_schema.COLLATIONS 的物理名 COLLATION_NAME/CHARACTER_SET_NAME/ID/…；
                // 真库 wire 上的 name 是显示名 Collation/Charset/Id/…（活体 A/B：dotnet 身份直连 3308
                // name=Collation/org_name=COLLATION_NAME）。而 Mycat 重建轨
                // ColumnDefPacketImpl#L80 把 metaData.getColumnName(idx) 直接写进 wire name 位，
                // 故此处喂进去的必须是显示名，否则 MySql.Data 的 reader["charset"] 找不到列。
                // label 缺失（个别驱动返回 null）时退回原始名，至少保住列数与可读性。
                String wireName = nullToEmpty(spec.columnLabel).isEmpty() ? spec.columnName : spec.columnLabel;
                builder.addColumnInfo(spec.schemaName, spec.tableName, wireName, spec.columnType,
                        spec.precision, spec.scale, wireName, spec.autoIncrement, spec.caseSensitive,
                        spec.nullable, spec.signed, spec.displaySize);
            }
            for (Object[] row : rows) {
                builder.addObjectRowPayload(row);
            }
            return builder;
        }
    }

    /** 列元数据副本（来自后端 ResultSetMetaData，逐字段照搬）。 */
    public static final class ColumnSpec {
        final String schemaName;
        final String tableName;
        final String columnName;
        final int columnType;
        final int precision;
        final int scale;
        final String columnLabel;
        final boolean autoIncrement;
        final boolean caseSensitive;
        final boolean nullable;
        final boolean signed;
        final int displaySize;

        public ColumnSpec(String schemaName, String tableName, String columnName, int columnType, int precision,
                          int scale, String columnLabel, boolean autoIncrement, boolean caseSensitive,
                          boolean nullable, boolean signed, int displaySize) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.columnName = columnName;
            this.columnType = columnType;
            this.precision = precision;
            this.scale = scale;
            this.columnLabel = columnLabel;
            this.autoIncrement = autoIncrement;
            this.caseSensitive = caseSensitive;
            this.nullable = nullable;
            this.signed = signed;
            this.displaySize = displaySize;
        }
    }
}

