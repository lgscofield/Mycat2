import java.sql.*;

/**
 * I-DEBT-86 净室护栏探针（source-file 模式，配 ci/verify-cleanroom.sh）。
 * 断言：净室启动（mycat-home 仅 datasources+users、无 clusters/）下 Connector/J 可连，
 * 且连接引导查询消费的 22 变量全部非 null（刀A/B 扩展后的产品承诺）。
 * 出口码：0=PASS / 1=RED / 2=NO_DATA（对齐 guard 三元语义）。
 * 参数：args[0]=端口（缺省 8067）。
 */
public class CleanroomProbe {
    // Connector/J 9.x 连接引导查询消费的变量面（与 MysqlVariableServiceImpl.CLEANROOM_SESSION_INHERIT_KEYS 对齐）
    static final String[] BOOTSTRAP_VARS = {
            "auto_increment_increment", "character_set_client", "character_set_connection",
            "character_set_results", "character_set_server", "collation_server", "collation_connection",
            "init_connect", "interactive_timeout", "license", "lower_case_table_names",
            "max_allowed_packet", "net_write_timeout", "performance_schema", "query_cache_size",
            "query_cache_type", "sql_mode", "system_time_zone", "time_zone",
            "transaction_isolation", "wait_timeout"
    };

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8067;
        String url = "jdbc:mysql://127.0.0.1:" + port + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000";
        try (Connection c = DriverManager.getConnection(url, "root", "123456");
             Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT 1")) {
                r.next();
                if (r.getInt(1) != 1) { System.out.println("RESULT=RED (SELECT 1 != 1)"); System.exit(1); }
            }
            int nullVars = 0;
            for (String v : BOOTSTRAP_VARS) {
                try (ResultSet r = s.executeQuery("SELECT @@session." + v)) {
                    r.next();
                    Object o = r.getObject(1);
                    // character_set_results 豁免：Connector/J 自身会对会话发 `SET character_set_results=NULL`
                    // （无 characterEncoding 形态下），真库直连同 URL 形态同为 null（2026-11-08 实测对拍），
                    // 非 mycat 缺口。
                    if (o == null && !"character_set_results".equals(v)) {
                        nullVars++; System.out.println("NULL-VAR: " + v);
                    }
                } catch (SQLException e) {
                    nullVars++; System.out.println("ERR-VAR: " + v + " -> " + e.getMessage());
                }
            }
            System.out.println("bootstrap vars: total=" + BOOTSTRAP_VARS.length + " null/err=" + nullVars);
            if (nullVars == 0) {
                System.out.println("RESULT=PASS (净室启动 Connector/J 连通 + 引导变量全可读)");
                System.exit(0);
            }
            System.out.println("RESULT=RED (净室可连但引导变量面有缺口)");
            System.exit(1);
        } catch (Exception e) {
            System.out.println("EXCEPTION: " + e);
            System.out.println("RESULT=RED (净室启动 JDBC 不可连)");
            System.exit(1);
        }
    }
}
