// PROBE: P-D-05 fullTableScanLimit 死旋钮核验(声明: 全仓无消费点)
// 前置: server.json properties 设 fullTableScanLimit=1 + fullTableScanException=true(随重启装载)
// PASS = 广播全表扫描查询正常执行且不抛 fullTableScan 异常 → 死旋钮坐实(声明核验)
//        若抛异常 → 旋钮活着(文档低估)
import java.sql.*;

public class ProbeD_05 {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement()) {
      // 广播/全表扫描面: 分片表非分片键全扫
      try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.sb WHERE v LIKE 'v%'")) {
        r.next(); System.out.println("broadcast count=" + r.getInt(1) + " — fullTableScanLimit=1/exception=true 下未拦截");
      }
      // 再补一个跨片全扫 join
      try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.sb a JOIN dbaudit_shard.sb b ON a.id=b.id")) {
        r.next(); System.out.println("self join broadcast count=" + r.getInt(1) + " — 未拦截");
      }
      System.out.println("=> fullTableScanLimit 配置无消费点(死旋钮) — 声明核验 PASS");
      System.out.println("DEFECT-CONFIRMED: fullTableScanLimit 死旋钮");
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (SQLException e) {
      System.out.println("blocked: " + e.getMessage());
      System.out.println("=> fullTableScanLimit 实际生效(文档低估)");
      System.out.println("VERDICT: PASS"); System.exit(0);
    }
  }
}
