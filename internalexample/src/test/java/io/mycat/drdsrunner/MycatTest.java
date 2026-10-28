package io.mycat.drdsrunner;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MYCAT CHANGE (I-DEBT-32-FAMILY 工程卫生·基类归位): 本接口原仅存于非法的**单级带点目录**
 * {@code src/test/java/io.mycat.drdsrunner/}, 其物理路径与声明包 {@code io.mycat.drdsrunner}
 * 不符, javac 按包路径扫描根本拾不到 → 该目录及依赖它的旧测试全部无法编译。
 *
 * <p>I-DEBT-② 处理时曾把 {@code internalexample} 的 {@code testIncludes} 收窄到只剩护栏测试
 * 以绕过此坏目录, 副作用是连同合法目录 {@code io/mycat/drdsrunner/} 里的 i_s 标量函数本地
 * codegen 回归锁(GSI/Procedure/Rx/ScalarStd/ScalarDate 族)一并被禁编译。本类归位到合法包路径
 * 后, 合法目录测试即可恢复编译运行; 坏点号目录原样保留, 其整体重组另立专轮(见 DEBT-QUEUE)。
 */
public interface MycatTest {

    String DB_MYCAT = System.getProperty("db_mycat", "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
    String DB1 = System.getProperty("db1", "jdbc:mysql://localhost:3306/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
    String DB2 = System.getProperty("db2", "jdbc:mysql://localhost:3307/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
    String DB_MYCAT_PSTMT = System.getProperty("db_mycat", "jdbc:mysql://localhost:8066/mysql?username=root&password=123456&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useServerPrepStmts=true");

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

                dataSource.setLoginTimeout(100);
                dataSource.setCheckExecuteTime(true);
                dataSource.setQueryTimeout(100);
                dataSource.setMaxWait(TimeUnit.SECONDS.toMillis(10));
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
        JdbcUtils.execute(mySQLConnection, sql);
    }

    public default List<Map<String, Object>> executeQuery(Connection mySQLConnection, String sql) throws Exception {
        LOGGER.info(sql);
        return JdbcUtils.executeQuery(mySQLConnection, sql, Collections.emptyList());
    }
    public default String executeQueryAsText(Connection mySQLConnection, String sql) throws Exception {
        LOGGER.info(sql);
        return JdbcUtils.executeQuery(mySQLConnection, sql, Collections.emptyList()).toString();
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
}
