import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * L1 端到端探针：以 MySQL 客户端的身份连上 Mycat 前端，跑一条真查询并把结果行读回来。
 *
 * 为什么要有这个文件：M2-1 之前的 D1 缺陷（NoSuchMethodError:
 * SQLASTOutputVisitor.&lt;init&gt;(Appendable)）只在**连接期服务端构造 visitor 时**才暴露，
 * 编译与单测都量不到它。能证明"L1 通了"的只有真客户端走完认证 + 会话初始化 + 一条查询。
 *
 * 用 source-file 模式直接跑，不需要构建产物：
 *   java -cp &lt;connector.jar&gt; ci/L1Probe.java 127.0.0.1 8066 root 123456 mysql
 * 成功打印 `L1 OK rows=&lt;n&gt;` 并以 0 退出；任何 SQLException 打印 `L1 FAIL &lt;message&gt;` 并以 1 退出。
 */
public class L1Probe {

    public static void main(String[] args) {
        String host = arg(args, 0, "127.0.0.1");
        String port = arg(args, 1, "8066");
        String user = arg(args, 2, "root");
        String password = arg(args, 3, "123456");
        String schema = arg(args, 4, "mysql");

        // allowPublicKeyRetrieval 必须有：MySQL 8 默认 caching_sha2_password，
        // 首次认证要走公钥拉取，否则握手在认证阶段就失败，量不到会话初始化路径。
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&connectTimeout=15000&socketTimeout=30000";

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            int rows = 0;
            while (resultSet.next()) {
                rows++;
            }
            System.out.println("L1 OK rows=" + rows);
        } catch (SQLException e) {
            System.out.println("L1 FAIL " + e.getMessage());
            System.exit(1);
        }
    }

    private static String arg(String[] args, int index, String defaultValue) {
        return args != null && args.length > index ? args[index] : defaultValue;
    }
}
