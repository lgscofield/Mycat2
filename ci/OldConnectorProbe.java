import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 旧 Java Connector 线冒烟探针（I-DEBT-9 旧线 NO_DATA 治具，B 层仪器）。
 *
 * 为什么单独有这一个文件（不复用 {@code ci/L1Probe.java}）：L1Probe 只跑 {@code SELECT 1}，
 * 量的是"最新 Connector/J 能不能连上"。旧线要回答的是**另一个问题**——
 * Connector/J 5.1.x（旧类名 {@code com.mysql.jdbc.Driver}，早于 caching_sha2_password）与
 * 8.0.x（新类名 {@code com.mysql.cj.jdbc.Driver}）这些**旧客户端**过 Mycat 前端认证握手、
 * 会话初始化、以及一条基本 CRUD 时，是否仍然兼容。spec §3 尺子把 M2 定义为"全 connector
 * 跑基本 CRUD"，旧线此前无仪器覆盖（NO_DATA），本探针即为点亮该格而生。
 *
 * 关键约束（多 Connector 隔离）：不能把旧 jar 挂进 reactor 与主 connector 9.x 同 classpath
 * （类遮蔽），故用 source-file 模式、每次只喂一个 connector jar：
 *   java -cp &lt;old-connector.jar&gt; ci/OldConnectorProbe.java 127.0.0.1 8066 root 123456 mysql
 *
 * 输出契约：逐段打印 {@code STAGE <name> OK/FAIL <detail>}，末尾打印
 * {@code OLDLINE RESULT driver=<类名> version=<驱动版本> verdict=PASS|FAIL}；
 * 全段 OK 退出码 0，任一段 FAIL 退出码 1（宁挂账不伪绿）。
 */
public class OldConnectorProbe {

    public static void main(String[] args) {
        String host = arg(args, 0, "127.0.0.1");
        String port = arg(args, 1, "8066");
        String user = arg(args, 2, "root");
        String password = arg(args, 3, "123456");
        String schema = arg(args, 4, "mysql");

        // 加载态自证：哪个 jar 真正在起作用（旧线 5.1 vs 8.x 类名不同，DriverManager 按 classpath 选）。
        String driverClass;
        String driverVersion;
        try {
            java.util.Enumeration<java.sql.Driver> drivers = DriverManager.getDrivers();
            java.sql.Driver picked = null;
            while (drivers.hasMoreElements()) {
                java.sql.Driver d = drivers.nextElement();
                String c = d.getClass().getName();
                if (c.startsWith("com.mysql.jdbc.Driver") || c.startsWith("com.mysql.cj.jdbc.Driver")) {
                    picked = d;
                }
            }
            driverClass = picked == null ? "<none>" : picked.getClass().getName();
            driverVersion = picked == null ? "<n/a>" : (picked.getMajorVersion() + "." + picked.getMinorVersion());
        } catch (Throwable t) {
            driverClass = "<probe-error:" + t + ">";
            driverVersion = "<n/a>";
        }

        // allowPublicKeyRetrieval：MySQL 8 默认 caching_sha2_password，公钥拉取须放行，
        // 否则握手在认证阶段即失败（5.1.x 不支持 caching_sha2，这正是要量的旧线兼容点）。
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&connectTimeout=15000&socketTimeout=30000";

        boolean pass = true;
        Connection connection = null;

        // STAGE 1: auth + 会话初始化
        try {
            connection = DriverManager.getConnection(url, user, password);
            pass &= report("auth", true, "connected via " + driverClass);
        } catch (SQLException e) {
            pass &= report("auth", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            finish(driverClass, driverVersion, pass);
            return;
        }

        try (Statement st = connection.createStatement()) {
            // STAGE 2: SELECT 1（L1 可达面）
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                int rows = 0;
                while (rs.next()) {
                    rs.getObject(1);
                    rows++;
                }
                pass &= report("select1", rows == 1, "rows=" + rows);
            } catch (SQLException e) {
                pass &= report("select1", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // STAGE 3: 版本回读（会话元数据面）
            try (ResultSet rs = st.executeQuery("SELECT VERSION()")) {
                String ver = rs.next() ? String.valueOf(rs.getObject(1)) : "<empty>";
                pass &= report("version", ver != null && !ver.isEmpty(), "server version=" + ver);
            } catch (SQLException e) {
                pass &= report("version", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // STAGE 4: 基本 CRUD 往返（经 Mycat 前端，getObject 逐值回读；M2 尺子"基本 CRUD"面）
            // 走 Mycat 正道：建逻辑库 → use → 带 dbpartition 分片建表 → INSERT → 跨分片归并 SELECT。
            // （往 schema mysql 裸建表会撞 Mycat 管理命令 MycatdbCommand，与 connector 无关，非旧线信号）
            // 用唯一库名避免撞既有库；建库/建表/删库全走代理，量旧线 DDL+DML+SELECT 全链。
            String db = "dboldprobe_" + System.currentTimeMillis();
            try {
                st.execute("DROP DATABASE IF EXISTS " + db);
                st.execute("CREATE DATABASE " + db);
                st.execute("USE " + db);
                st.execute("CREATE TABLE t1 (id INT PRIMARY KEY, name VARCHAR(32), amount DECIMAL(10,2))"
                        + " dbpartition by mod_hash(`id`) dbpartitions 2");
                st.execute("INSERT INTO t1 (id, name, amount) VALUES (1, '探针', 12.34), (2, 'abc', 99.99)");
                try (ResultSet rs = st.executeQuery("SELECT id, name, amount FROM t1 ORDER BY id")) {
                    boolean ok = false;
                    String detail = "no row";
                    if (rs.next()) {
                        Object id = rs.getObject(1);
                        Object name = rs.getObject(2);
                        Object amount = rs.getObject(3);
                        boolean second = rs.next();
                        Object id2 = second ? rs.getObject(1) : null;
                        ok = "1".equals(String.valueOf(id)) && "探针".equals(String.valueOf(name))
                                && String.valueOf(amount).startsWith("12.34")
                                && second && "2".equals(String.valueOf(id2));
                        detail = "r1=[" + id + "," + name + "," + amount + "] r2=" + id2
                                + " types=" + (id == null ? "null" : id.getClass().getSimpleName()) + "/"
                                + (name == null ? "null" : name.getClass().getSimpleName()) + "/"
                                + (amount == null ? "null" : amount.getClass().getSimpleName());
                    }
                    pass &= report("crud", ok, detail);
                }
            } catch (SQLException e) {
                pass &= report("crud", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                try {
                    st.execute("DROP DATABASE IF EXISTS " + db);
                } catch (SQLException ignore) {
                    // 清理尽力而为（含后端物理库不随逻辑 drop 消失的残留，属测试环境范畴），不影响判定
                }
            }
        } catch (SQLException e) {
            pass &= report("statement", false, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                if (connection != null) {
                    DatabaseMetaData md = connection.getMetaData();
                    // 客户端驱动自证（旧 jar 的 getDriverVersion 随线不同）
                    driverVersion = safeDriverVersion(md, driverVersion);
                    connection.close();
                }
            } catch (SQLException ignore) {
                // 关闭异常不改判定
            }
        }

        finish(driverClass, driverVersion, pass);
    }

    private static String safeDriverVersion(DatabaseMetaData md, String fallback) {
        try {
            return md.getDriverVersion();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean report(String stage, boolean ok, String detail) {
        System.out.println("STAGE " + stage + " " + (ok ? "OK" : "FAIL") + " " + detail);
        return ok;
    }

    private static void finish(String driverClass, String driverVersion, boolean pass) {
        System.out.println("OLDLINE RESULT driver=" + driverClass + " version=" + driverVersion
                + " verdict=" + (pass ? "PASS" : "FAIL"));
        if (!pass) {
            System.exit(1);
        }
    }

    private static String arg(String[] args, int index, String defaultValue) {
        return args != null && args.length > index ? args[index] : defaultValue;
    }
}
