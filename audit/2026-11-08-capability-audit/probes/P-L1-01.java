// PROBE: P-L1-01 native认证 + SELECT 1 + version() posture
// 验证: L1 连接层声明(M2 连接里程碑)。mycat 前端 root/123456 @8066。
// PASS = 连接成功 + SELECT 1 返回 1 + version() 可读
import java.sql.*;

public class ProbeL1_01 {
  static final String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";

  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(MYCAT, "root", "123456")) {
      try (Statement s = c.createStatement()) {
        ResultSet r = s.executeQuery("SELECT 1");
        r.next();
        if (r.getInt(1) != 1) { System.out.println("SELECT 1 returned " + r.getInt(1)); fail(); }
        r = s.executeQuery("SELECT version()");
        r.next();
        String ver = r.getString(1);
        System.out.println("frontend version() = " + ver);
        System.out.println("product = " + c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
        System.out.println("VERDICT: PASS");
        System.exit(0);
      }
    } catch (Exception e) {
      System.out.println("EXCEPTION: " + e);
      fail();
    }
  }
  static void fail() { System.out.println("VERDICT: RED"); System.exit(1); }
}
