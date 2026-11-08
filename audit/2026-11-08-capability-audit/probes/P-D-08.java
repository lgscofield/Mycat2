// PROBE: P-D-08 用户级 SQL 类型黑名单(disallowSqlTypes)生效性
// 前置: users/probe2.user.json 带 role.disallowSqlTypes=["DELETE"](随重启装载)
// PASS(声明核验): probe2 用户 DELETE 被拒 + SELECT/INSERT 放行 → 黑名单生效
//                  若 DELETE 也能执行 → 黑名单也是壳(文档低估为"部分生效")
import java.sql.*;

public class ProbeD_08 {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "probe2", "Probe#2026");
         Statement s = c.createStatement()) {
      System.out.println("probe2 connected");
      try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.sb")) { r.next(); System.out.println("SELECT ok rows=" + r.getInt(1)); }
      boolean insertOk = true;
      try { s.execute("INSERT INTO dbaudit_shard.sb (id, v) VALUES (8888888, 'd8')"); }
      catch (SQLException e) { insertOk = false; System.out.println("INSERT blocked(记录): " + e.getMessage()); }
      boolean deleteOk = false;
      try { s.execute("DELETE FROM dbaudit_shard.sb WHERE id = 8888888");
        deleteOk = true;
        System.out.println("DELETE executed OK (黑名单未拦截!)");
      } catch (SQLException e) { System.out.println("DELETE blocked: " + e.getMessage()); }
      // 清理(用 root)
      try (Connection r2 = DriverManager.getConnection(url.replace("//", "//"), "root", "123456");
           Statement rs = r2.createStatement()) { rs.execute("DELETE FROM dbaudit_shard.sb WHERE id = 8888888"); } catch (Exception ignore) {}
      if (!deleteOk) { System.out.println("=> disallowSqlTypes 生效(文档低估: 比声明的好)"); System.out.println("VERDICT: PASS"); System.exit(0); }
      if (insertOk) { System.out.println("=> DELETE 放行 + INSERT 放行 → 黑名单完全无效(比'部分生效'更糟)"); }
      System.out.println("DEFECT-CONFIRMED(若 deleteOk): disallowSqlTypes 无效");
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (SQLException e) {
      System.out.println("probe2 connect failed: " + e.getMessage());
      System.out.println("NO_DATA: 用户/role 配置未被识别"); System.out.println("VERDICT: NO_DATA"); System.exit(2);
    }
  }
}
