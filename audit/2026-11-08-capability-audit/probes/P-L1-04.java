// PROBE: P-L1-04 认证边界: 错误密码必须被拒
// PASS = 错误密码连接抛 SQLException(含 Access denied 语义); RED = 错误密码能连上(重大安全缺陷)
import java.sql.*;

public class ProbeL1_04 {
  public static void main(String[] args) {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "root", "WRONG_PASSWORD_XYZ")) {
      System.out.println("SECURITY DEFECT: wrong password accepted!");
      try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT 1")) { r.next(); }
      System.out.println("VERDICT: RED");
      System.exit(1);
    } catch (SQLException e) {
      System.out.println("rejected as expected: " + e.getMessage());
      System.out.println("VERDICT: PASS");
      System.exit(0);
    }
  }
}
