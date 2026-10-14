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

    /**
     * I-DEBT-7C·测试隔离前置：后端 MySQL 容器持久例，上一例／另一例遗留同名表会撞
     * “Unknown column ...”（CREATE 经 Mycat 代理可能被吞或写向另一 ds，INSERT 则命中旧 schema）。
     * 与 {@link #deleteData(Connection, String, String)} 同层语义：deleteData 只清行，本方法
     * 清“旧 schema 形状”本身——建表前先 {@code DROP TABLE IF EXISTS}，给同例新 schema 腾位。
     * 与 {@code RESET_CONFIG} 不同：RESET_CONFIG 只重置 Mycat 运行期配置，不动后端物理 schema。
     *
     * <p><b>历史与现态</b>：初版实现曾绕过 Mycat 直连 DB1/DB2 兑底（当时代理层 {@code DROP TABLE IF EXISTS}
     * 对 Mycat 元数据未登记的物理表不会下沉，属“假 OK”）。该问题已由 I-DEBT-43 修复
     * 的 {@code DropTableSQLHandler}（补上 {@code onPhysics} 兑底分支）解决，回归 MySQL 语义：
     * 后端物理表存在即删。本方法重新走 Mycat 代理（与同族 {@link #deleteData}/{@link #execute} 一致），
     * 不再绕过。若将来重新发现代理 DDL 路由问题，需先另票修 Mycat，而非本层绕过。
     */
    public default void dropTableIfExists(Connection mycatConnection, String db, String table) throws Exception {
        // 经 Mycat 代理下发，与同族测试 helper 一致（I-DEBT-43 修复后代理 DDL 路由已同 MySQL 语义）。
        execute(mycatConnection, String.format("DROP TABLE IF EXISTS %s.%s", db, table));
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

    // 带参版与无参版同族：druid JdbcUtils.execute/executeQuery 内部强制 PreparedStatement
    // 的 executeUpdate()/executeQuery()，二者都对 SQL 做 Connector/J 发送前纯客户端静态分类，
    // 对 hint/多语句串误判即抛（同 execute()/executeQuery() 无参版根因）。此处改用
    // PreparedStatement.execute()（不做该预分类，返回 boolean）并逐位 setObject 绑定，
    // 参数绑定语义与 druid setParameters 逐字一致；结果转换复刻 druid ResultSetToMap
    // （LinkedHashMap + getColumnLabel + getObject），保证既有断言形态不变。
    public default void execute(Connection mySQLConnection, String sql, List<Object> parameters) throws Exception {
        LOGGER.info(sql);
        try (PreparedStatement statement = mySQLConnection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            statement.execute();
        }
    }

    public default List<Map<String, Object>> executeQuery(Connection mySQLConnection, String sql,
                                                          List<Object> parameters) throws Exception {
        LOGGER.info(sql);
        try (PreparedStatement statement = mySQLConnection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            boolean hasResultSet = statement.execute();
            List<Map<String, Object>> list = new ArrayList<>();
            if (!hasResultSet) {
                return list;
            }
            try (ResultSet rs = statement.getResultSet()) {
                collectRows(rs, list);
            }
            return list;
        }
    }

    static void setParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0, size = parameters.size(); i < size; ++i) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    static void collectRows(ResultSet rs, List<Map<String, Object>> rows) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columns = metaData.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(columns);
            for (int i = 1; i <= columns; ++i) {
                row.put(metaData.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
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
