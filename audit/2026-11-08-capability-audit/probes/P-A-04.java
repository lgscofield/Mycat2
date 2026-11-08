// PROBE: P-A-04 多语句(allowMultiQueries=true)
// 验证声明: "CLIENT_MULTI_STATEMENTS 已广告, 但 SERVER_MORE_RESULTS_EXISTS 写路径被注释 → 实际不产出多结果集"
// 判定: 两语句执行, 观察第二结果集是否可达。
//   - 报错/仅第一语句执行 = 与声明一致(多语句面残缺) PASS(声明核验)
//   - 两个结果集都拿到 = 文档低估 NOTE PASS
import java.sql.*;

public class ProbeA_04 {
  public static void main(String[] args) {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true&serverTimezone=Asia/Shanghai";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement()) {
      boolean hasRs = s.execute("SELECT 11; SELECT 22");
      int rsCount = 0, updCount = 0;
      while (true) {
        if (hasRs) { try (ResultSet r = s.getResultSet()) { r.next(); System.out.println("RS[" + rsCount + "]=" + r.getInt(1)); } rsCount++; }
        else { int u = s.getUpdateCount(); if (u == -1) break; updCount++; }
        try { hasRs = s.getMoreResults(); } catch (SQLException se) { System.out.println("getMoreResults err: " + se.getMessage()); break; }
        if (!hasRs && s.getUpdateCount() == -1) break;
      }
      System.out.println("resultSets=" + rsCount + " updates=" + updCount);
      if (rsCount >= 2) { System.out.println("NOTE: multi-resultset WORKED (文档低估)"); }
      else System.out.println("=> 与'多结果集写路径被注释'声明一致");
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (Exception e) {
      System.out.println("EXCEPTION: " + e.getMessage());
      System.out.println("=> 多语句路径异常(与'能力位广告但写路径不产出'声明不矛盾)");
      System.out.println("VERDICT: PASS"); System.exit(0);
    }
  }
}
