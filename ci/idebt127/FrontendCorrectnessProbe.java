import com.mysql.cj.jdbc.ConnectionImpl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Properties;

public final class FrontendCorrectnessProbe {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        probeAttributes(host, port);
        probePrepare(host, port, false);
        probePrepare(host, port, true);
        probeReset(host, port);
        System.out.println("verdict\tPASS");
    }

    private static Connection connect(String host, int port, Properties overrides) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", "root");
        properties.setProperty("password", System.getenv("MYSQL_PWD"));
        properties.setProperty("useSSL", "false");
        properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "10000");
        properties.putAll(overrides);
        return DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/mysql", properties);
    }

    private static void probeAttributes(String host, int port) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("connectionAttributes", "probe.中文:中文值,probe.long:" + "x".repeat(260));
        try (Connection connection = connect(host, port, properties);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new AssertionError("connection attributes handshake lost synchronization");
            }
        }
        System.out.println("connect_attrs\tPASS\tnon_ascii_and_260_byte_value");
    }

    private static void probePrepare(String host, int port, boolean deprecateEof) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("useDeprecateEOF", Boolean.toString(deprecateEof));
        try (Connection connection = connect(host, port, properties)) {
            assertPrepare(connection, "SELECT ? AS `值`", 1, 1);
            assertPrepare(connection, "SELECT 1 AS value, 'x' AS name", 2, 0);
            assertPrepare(connection, "SELECT 1 AS `中文`", 1, 0);
        }
        System.out.println("prepare\tPASS\tdeprecateEOF=" + deprecateEof);
    }

    private static void assertPrepare(Connection connection, String sql, int columns, int parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            ResultSetMetaData metadata = statement.getMetaData();
            if (metadata == null || metadata.getColumnCount() != columns) {
                int actual = metadata == null ? -1 : metadata.getColumnCount();
                throw new AssertionError("prepare column count for " + sql + ": expected " + columns + ", got " + actual);
            }
            if (statement.getParameterMetaData().getParameterCount() != parameters) {
                throw new AssertionError("prepare parameter count for " + sql);
            }
            if (parameters > 0) {
                statement.setInt(1, 7);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("prepared query returned no row: " + sql);
                }
            }
        }
    }

    private static void probeReset(String host, int port) throws Exception {
        try (ConnectionImpl connection = (ConnectionImpl) connect(host, port, new Properties())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE");
                statement.execute("SET SESSION sql_mode = 'ANSI'");
            }
            connection.resetServerState();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT @@SESSION.autocommit, @@SESSION.transaction_isolation, @@SESSION.sql_mode")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new AssertionError("reset did not restore server autocommit");
                }
                if (!result.getString(2).equalsIgnoreCase("REPEATABLE-READ")) {
                    throw new AssertionError("reset did not restore configured server isolation");
                }
                if (result.getString(3).toUpperCase().contains("ANSI")) {
                    throw new AssertionError("reset did not restore server sql_mode");
                }
            }
        }
        System.out.println("reset\tPASS\trollback_state_cleared_ok_received");
    }
}
