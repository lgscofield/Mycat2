package io.mycat.assemble;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.util.JdbcUtils;
import io.mycat.beans.mycat.CopyMycatRowMetaData;
import io.mycat.beans.mycat.JdbcRowMetaData;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import io.mycat.util.JsonUtil;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;


public interface MycatTest {

    String DB_MYCAT = System.getProperty("db_mycat", "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
    String DB1 = System.getProperty("db1", "jdbc:mysql://localhost:3306/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
    String DB2 = System.getProperty("db2", "jdbc:mysql://localhost:3307/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
    String DB_MYCAT_PSTMT = System.getProperty("db_mycat", "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useServerPrepStmts=true");

    String RESET_CONFIG = "/*+ mycat:resetConfig{} */";

    Map<String, DruidDataSource> dsMap = new ConcurrentHashMap<>();
    Logger LOGGER = LoggerFactory.getLogger(MycatTest.class);


    default Connection getMySQLConnection(String url) throws Exception {
        return dsMap.computeIfAbsent(url, new Function<String, DruidDataSource>() {
            @Override
            @SneakyThrows
            public DruidDataSource apply(String url) {
                Map<String, String> urlParameters = JsonUtil.urlSplit(url);
                String username = urlParameters.getOrDefault("username", "root");
                String password = urlParameters.getOrDefault("password", "123456");

                DruidDataSource dataSource = new DruidDataSource();
                dataSource.setUrl(url);
                dataSource.setUsername(username);
                dataSource.setPassword(password);

                dataSource.setLoginTimeout(100000);
                dataSource.setCheckExecuteTime(true);
                dataSource.setQueryTimeout(1000);
                dataSource.setMaxWait(TimeUnit.SECONDS.toMillis(100));
                dataSource.setMaxActive(8);
                return dataSource;
            }
        }).getConnection();

    }

    public default boolean existTable(Connection connection, String db, String table) throws Exception {
        return !executeQuery(connection, String.format("SHOW TABLES from %s LIKE '%s';", db, table)).isEmpty();

    }

    public default boolean hasData(Connection connection, String db, String table) throws Exception {
        return !executeQuery(connection, String.format("select * from %s.%s limit 1", db, table)).isEmpty();
    }

    public default long count(Connection connection, String db, String table) throws Exception {
        String format = String.format("select count(1) as `count` from %s.%s", db, table);
        List<Map<String, Object>> mapList = executeQuery(connection, format);
        Number count = (Number) mapList.get(0).get("count");
        return count.longValue();
    }

    public default void deleteData(Connection connection, String db, String table) throws Exception {
        execute(connection, String.format("delete  from %s.%s", db, table));
    }

    public default void execute(Connection mySQLConnection, String sql) throws Exception {
        LOGGER.info(sql);
        // 用 JDBC Statement.execute() 而非 druid JdbcUtils.execute（其内部强制 PreparedStatement.executeUpdate）。
        // Connector/J 的 executeUpdate 在“发送前”有一道纯客户端静态分类守卫：把 /*+ mycat:... */ 这类
        // 注释/hint 串（无被识别的起始关键字）判为“产出结果集”，直接抛
        // "Can not issue executeUpdate() ... with statements that produce result sets"
        // （ClientPreparedStatement:1145），根本没走到网络，与 Mycat 服务端 sendOk 无关。
        // Statement.execute() 对 OK/结果集/hint 任意形态都不抛，返回 boolean 在此忽略即可；
        // 本方法返回 void，调用方从不依赖 updateCount，故行为对既有用例保持不变。
        try (Statement statement = mySQLConnection.createStatement()) {
            statement.execute(sql);
        }
        System.out.println(sql);
    }

    public default List<Map<String, Object>> executeQuery(Connection mySQLConnection, String sql) throws Exception {
        LOGGER.info(sql);
        // 用 JDBC Statement.execute()+getResultSet() 而非 JdbcUtils→PreparedStatement.executeQuery()。
        // 与 execute() 同族的 Connector/J 发送前纯客户端静态分类守卫：把 /*+ mycat:... */ 这类
        // 无被识别起始关键字的 hint 串误判为“不产出结果集”，直接抛
        // "Statement.executeQuery() cannot issue statements that do not produce result sets"，未走网络。
        // Statement.execute() 不做该预分类（返回 boolean），再取回真正的 ResultSet。
        // 转换逐字节复刻 druid 1.2.28 JdbcUtils 内部 ResultSetToMap：LinkedHashMap + getColumnLabel + getObject，
        // 保证既有断言（如 "[{value=1}]"）的多列 toString 顺序/取值形态不变。
        try (Statement statement = mySQLConnection.createStatement()) {
            boolean hasResultSet = statement.execute(sql);
            List<Map<String, Object>> list = new ArrayList<>();
            if (!hasResultSet) {
                return list;
            }
            try (ResultSet rs = statement.getResultSet()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columns = metaData.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>(columns);
                    for (int i = 1; i <= columns; ++i) {
                        row.put(metaData.getColumnLabel(i), rs.getObject(i));
                    }
                    list.add(row);
                }
            }
            return list;
        }
    }
    public default String executeQueryAsText(Connection mySQLConnection, String sql) throws Exception {
        LOGGER.info(sql);
        return executeQuery(mySQLConnection, sql).toString();
    }

    public default String explain(Connection mySQLConnection, String sql) throws Exception {
        List<Map<String, Object>> maps = JdbcUtils.executeQuery(mySQLConnection, "explain "+sql, Collections.emptyList());
        return maps.stream().flatMap(i -> i.values().stream()).map(i -> (String) i).collect(Collectors.joining("\n"));
    }

    public default void addC0(Connection connection) throws Exception {
        execute(connection, CreateDataSourceHint
                .create("newDs",
                        DB1));
        execute(connection, CreateClusterHint.create("c0", Arrays.asList("newDs"), Collections.emptyList()));
    }


    public default MycatRowMetaData getColumns(Connection connection, String db, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select * from " + db + "." + table)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            return new CopyMycatRowMetaData(new JdbcRowMetaData(metaData));
        }
    }
    default long getUseCon(Connection connection,String dsName) throws Exception {
        List<Map<String, Object>> maps = executeQuery(connection, "/*+ mycat:showDataSources{} */");
        return maps.stream().filter(r -> dsName.equalsIgnoreCase((String) r.get("NAME"))).map(r -> (Number) r.get("USE_CON")).findFirst().get().longValue();
    }

}
