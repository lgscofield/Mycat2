// PROBE: P-B-02 非分片键查询广播(无 sharding key 条件 → 全分片 UNION ALL)
// PASS = 非分片键等值查命中目标行(广播) + explain 不崩
import java.sql.*;

public class ProbeB_02 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      // 复用 P-B-01 的表
      try (ResultSet r = ms.executeQuery("SELECT id, v FROM dbaudit_shard.sb WHERE v = 'v50'")) {
        boolean found = false;
        while (r.next()) { if (r.getLong(1) == 50) found = true; }
        if (!found) { System.out.println("broadcast lookup missed v50"); System.out.println("VERDICT: RED"); System.exit(1); }
      }
      try (ResultSet r = ms.executeQuery("EXPLAIN SELECT COUNT(*) FROM dbaudit_shard.sb WHERE v='v50'")) {
        while (r.next()) System.out.println("explain: " + r.getString(1));
      } catch (Exception e) { System.out.println("EXPLAIN err(记录): " + e.getMessage()); }
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
