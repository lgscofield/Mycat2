package io.mycat.sqlhandler.dql;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * I-DEBT-70 离线锁共用 JDBC 假件（Proxy 动态代理，不连真库）。
 *
 * <p>职责：记录本票关心的语句调用序（{@link #sqls}），并按 {@link #previousOldMode} /
 * {@link #showRows} 脚本应答 {@code SELECT @@old_mode} 与 {@code SHOW COLLATION}；
 * 可在指定步骤注入异常以验证 finally 复原。同时支持 Druid {@code JdbcUtils.executeQuery}
 * 的 {@code prepareStatement + 无参 executeQuery()} 走法，好在测试里跑通
 * {@code ShowCollationSQLHandler} 的 prototype 试跑腿。
 */
class FakeBackendJdbc {

    static final String SHOW = "SHOW COLLATION";

    final List<String> sqls = new ArrayList<>();
    String previousOldMode = "UTF8_IS_UTF8MB3";
    List<Object[]> showRows = new ArrayList<>();
    /**
     * 活体实测（Connector/J 9.3.0 连 mariadb:10.11，scratch/JdbcProductProbe.java）：驱动把产品名
     * 硬编成 "MySQL"，与后端真实身份无关 ⇒ 后端门不得采信它，只在版本串上判定。
     */
    String product = "MySQL";
    /** 驱动如实回传握手版本串：MariaDB 对老客户端带 "5.5.5-" 兼容前缀（SELECT VERSION() 才无前缀）。 */
    String version = "5.5.5-10.11.19-MariaDB-ubu2204";
    SQLException failOnRead;
    SQLException failOnShow;
    /** 只命中加法式种位语句（{@code SET old_mode=CONCAT_WS…}），不误伤复原语句。 */
    SQLException failOnSeal;
    /** 第几次 SHOW COLLATION 触发 {@link #failOnShow}（1 基）：handler 会先做 prototype 试跑，取数才是第 2 次。 */
    int failOnShowAt = 1;
    private int showSeen;

    private final Connection connection;

    FakeBackendJdbc() {
        this.connection = (Connection) Proxy.newProxyInstance(FakeBackendJdbc.class.getClassLoader(),
                new Class[]{Connection.class}, (p, m, a) -> {
                    String name = m.getName();
                    if ("createStatement".equals(name)) {
                        return statement(null);
                    }
                    if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
                        return statement((a != null && a.length > 0 && a[0] instanceof String) ? (String) a[0] : null);
                    }
                    if ("getMetaData".equals(name)) {
                        return metaData();
                    }
                    return defaults(m, a);
                });
    }

    Connection connection() {
        return connection;
    }

    /** 多身份循环里重置“第 N 次 SHOW”计数，避免注入点漂移到上一轮。 */
    void resetScriptCounters() {
        showSeen = 0;
    }

    private DatabaseMetaData metaData() {
        return (DatabaseMetaData) Proxy.newProxyInstance(FakeBackendJdbc.class.getClassLoader(),
                new Class[]{DatabaseMetaData.class}, (p, m, a) -> {
                    if ("getDatabaseProductName".equals(m.getName())) {
                        return product;
                    }
                    if ("getDatabaseProductVersion".equals(m.getName())) {
                        return version;
                    }
                    if ("getDriverVersion".equals(m.getName())) {
                        return version;
                    }
                    return defaults(m, a);
                });
    }

    private Statement statement(String boundSql) {
        final String[] current = {boundSql};
        // Connection.prepareStatement 的返回类型是 PreparedStatement，代理必须同时实现两接口（否则 CCE）。
        return (Statement) Proxy.newProxyInstance(FakeBackendJdbc.class.getClassLoader(),
                new Class[]{Statement.class, java.sql.PreparedStatement.class}, (p, m, a) -> {
                    String name = m.getName();
                    String sql = (a != null && a.length > 0 && a[0] instanceof String) ? (String) a[0] : null;
                    if (sql != null) {
                        current[0] = sql;
                    }
                    if ("executeQuery".equals(name) || "executeUpdate".equals(name) || "execute".equals(name)) {
                        sqls.add(current[0]);
                        if (CollationMaterialization.READ_OLD_MODE.equals(current[0]) && failOnRead != null) {
                            throw failOnRead;
                        }
                        if (current[0] != null && current[0].startsWith("SET old_mode=CONCAT_WS") && failOnSeal != null) {
                            throw failOnSeal;
                        }
                        if (current[0] != null && current[0].startsWith(SHOW) && failOnShow != null
                                && ++showSeen == failOnShowAt) {
                            throw failOnShow;
                        }
                    }
                    if ("executeQuery".equals(name)) {
                        return answer(current[0]);
                    }
                    if ("getResultSet".equals(name)) {
                        return answer(current[0]);
                    }
                    if ("getUpdateCount".equals(name)) {
                        return -1;
                    }
                    if ("execute".equals(name)) {
                        return Boolean.TRUE;
                    }
                    if ("getMoreResults".equals(name)) {
                        return Boolean.FALSE;
                    }
                    return defaults(m, a);
                });
    }

    private ResultSet answer(String sql) {
        if (CollationMaterialization.READ_OLD_MODE.equals(sql)) {
            return rows(Collections.singletonList(new Object[]{previousOldMode}), columns("old_mode"));
        }
        if (sql != null && sql.startsWith(SHOW)) {
            return rows(showRows, columns("Collation", "Charset", "Id", "Default", "Compiled", "Sortlen"));
        }
        return rows(Collections.emptyList(), columns("x"));
    }

    private static List<CollationMaterialization.ColumnSpec> columns(String... names) {
        List<CollationMaterialization.ColumnSpec> list = new ArrayList<>();
        for (String n : names) {
            boolean numeric = "Id".equals(n) || "Sortlen".equals(n);
            // 照 Connector/J 实况把两个名分开：getColumnName() 给 information_schema.COLLATIONS 的
            // 物理列名（大写），getColumnLabel() 给 SHOW COLLATION 的显示名。活体 A/B 实测（dotnet 身份
            // 直连 mariadb:10.11）wire name=Collation / org_name=COLLATION_NAME。合成一个值会让本锁
            // 对「上线取错名」完全失明——I-DEBT-70 活体第一轮正是这样漏过去的。
            list.add(new CollationMaterialization.ColumnSpec("information_schema", "COLLATIONS",
                    orgNameOf(n),
                    numeric ? java.sql.JDBCType.BIGINT.getVendorTypeNumber()
                            : java.sql.JDBCType.VARCHAR.getVendorTypeNumber(),
                    numeric ? 20 : 44, 0, n, false, false, true, numeric, numeric ? 20 : 44));
        }
        return list;
    }

    /** SHOW COLLATION 显示名 → information_schema.COLLATIONS 物理列名（活体 A/B 实测的 org_name）。 */
    private static String orgNameOf(String displayName) {
        switch (displayName) {
            case "Collation":
                return "COLLATION_NAME";
            case "Charset":
                return "CHARACTER_SET_NAME";
            case "Id":
                return "ID";
            case "Default":
                return "IS_DEFAULT";
            case "Compiled":
                return "IS_COMPILED";
            case "Sortlen":
                return "SORTLEN";
            default:
                return displayName;
        }
    }

    private static ResultSet rows(List<Object[]> data, List<CollationMaterialization.ColumnSpec> cols) {
        final int[] cursor = {-1};
        final boolean[] lastWasNull = {false};
        ResultSetMetaData rsmeta = (ResultSetMetaData) Proxy.newProxyInstance(
                FakeBackendJdbc.class.getClassLoader(), new Class[]{ResultSetMetaData.class}, (p, m, a) -> {
                    int i = (a != null && a.length > 0) ? ((int) a[0]) - 1 : 0;
                    CollationMaterialization.ColumnSpec c = cols.get(Math.max(0, Math.min(i, cols.size() - 1)));
                    switch (m.getName()) {
                        case "getColumnCount":
                            return cols.size();
                        case "getColumnName":
                            return c.columnName;
                        case "getColumnLabel":
                        case "getColumnDisplayName":
                            return c.columnLabel;
                        case "getColumnTypeName":
                            return c.columnType == java.sql.JDBCType.BIGINT.getVendorTypeNumber() ? "BIGINT" : "VARCHAR";
                        case "getColumnType":
                            return c.columnType;
                        case "getPrecision":
                            return c.precision;
                        case "getDisplaySize":
                            return c.displaySize;
                        case "getScale":
                            return c.scale;
                        case "isNullable":
                            return c.nullable ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
                        case "isSigned":
                            return c.signed;
                        case "isCaseSensitive":
                            return c.caseSensitive;
                        case "isAutoIncrement":
                            return c.autoIncrement;
                        case "getTableName":
                            return c.tableName;
                        case "getSchemaName":
                            return c.schemaName;
                        case "isWrapperFor":
                            return Boolean.FALSE;
                        default:
                            return defaults(m, a);
                    }
                });
        return (ResultSet) Proxy.newProxyInstance(FakeBackendJdbc.class.getClassLoader(),
                new Class[]{ResultSet.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "next":
                            cursor[0]++;
                            return cursor[0] < data.size();
                        case "getObject": {
                            Object v = data.get(cursor[0])[(int) a[0] - 1];
                            lastWasNull[0] = v == null;
                            return v;
                        }
                        case "wasNull":
                            return lastWasNull[0];
                        case "getString": {
                            Object s = data.get(cursor[0])[(int) a[0] - 1];
                            lastWasNull[0] = s == null;
                            return s == null ? null : String.valueOf(s);
                        }
                        case "getInt": {
                            Object i = data.get(cursor[0])[(int) a[0] - 1];
                            lastWasNull[0] = i == null;
                            return i == null ? 0 : ((Number) i).intValue();
                        }
                        case "getMetaData":
                            return rsmeta;
                        default:
                            return defaults(m, a);
                    }
                });
    }

    static Object defaults(java.lang.reflect.Method m, Object[] args) {
        Class<?> rt = m.getReturnType();
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == int.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        return null;
    }
}

