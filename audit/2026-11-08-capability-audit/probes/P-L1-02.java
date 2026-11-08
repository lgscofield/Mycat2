// PROBE: P-L1-02 caching_sha2_password 全认证(前端)
// MySQL 8.4.6 后端上建的前端用户走 caching_sha2。mycat 侧用户在 mycat-home/users 下。
// 本探针用 root(前端密码缓存 sha2 语义由 mycat 自身实现) + 验证 auth_switch 协商不崩。
// PASS = 连接成功且能执行查询
import java.sql.*;

public class ProbeL1_02 {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("SELECT CURRENT_USER()");
      r.next();
      System.out.println("CURRENT_USER = " + r.getString(1));
      r = s.executeQuery("SELECT user, host, plugin FROM mysql.user"); // 应被拦或代理,记录行为
      System.out.println("mysql.user readable via mycat (记录行为)");
      System.out.println("VERDICT: PASS");
      System.exit(0);
    } catch (Exception e) {
      System.out.println("EXCEPTION: " + e);
      System.out.println("VERDICT: RED");
      System.exit(1);
    }
  }
}
