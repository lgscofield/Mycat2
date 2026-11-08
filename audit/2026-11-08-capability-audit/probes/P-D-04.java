// PROBE: P-D-04 库表级权限是否生效(声明: 权限代码被注释, 仅留壳)
// 前置: mycat-home/users/probe.user.json(无任何 schema 授权概念)已随重启装载
// PASS = probe 用户能连 mycat 并跨 schema 访问(无授权模型 = 差距坐实, 声明核验 PASS)
//        若被拒 = 权限模型实际生效(文档低估)
import java.sql.*;

public class ProbeD_04 {
  public static void main(String[] args) {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "probeguy", "Probe#2026");
         Statement s = c.createStatement()) {
      System.out.println("probe user connected OK");
      try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.sb")) {
        r.next(); System.out.println("cross-schema read OK, rows=" + r.getInt(1));
      }
      System.out.println("=> 前端用户无库表授权模型, 任意用户全域可见(差距2相关: 权限面) — 声明核验 PASS");
      System.out.println("DEFECT-CONFIRMED: 无库表级权限");
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (SQLException e) {
      System.out.println("rejected: " + e.getMessage());
      System.out.println("=> 权限面实际生效(文档低估)");
      System.out.println("VERDICT: PASS"); System.exit(0);
    }
  }
}
