// PROBE: P-B-11 ORDER BY + LIMIT 跨片 TopN 归并正确性
// PASS = mycat ORDER BY val DESC LIMIT 5 与后端 UNION ALL 基线一致
import java.sql.*;
import java.util.*;

public class ProbeB_11 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String BACKEND = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement();
         Connection b = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
         Statement bs = b.createStatement()) {
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.ob");
      ms.execute("CREATE TABLE dbaudit_shard.ob (id bigint primary key, val int) dbpartition by mod_hash(id) dbpartitions 2");
      List<Integer> vals = new ArrayList<>(new Random(7).ints(80, 0, 10000).boxed().toList());
      try (PreparedStatement p = m.prepareStatement("INSERT INTO dbaudit_shard.ob VALUES (?,?)")) {
        for (int i = 0; i < 80; i++) { p.setLong(1, i + 1); p.setInt(2, vals.get(i)); p.executeUpdate(); }
      }
      StringBuilder mine = new StringBuilder();
      try (ResultSet r = ms.executeQuery("SELECT id, val FROM dbaudit_shard.ob ORDER BY val DESC LIMIT 5")) {
        while (r.next()) mine.append(r.getLong(1)).append(":").append(r.getInt(2)).append(",");
      }
      StringBuilder want = new StringBuilder();
      try (ResultSet r = bs.executeQuery("SELECT id, val FROM (SELECT * FROM `dbaudit_shard_0`.`ob_0` UNION ALL SELECT * FROM `dbaudit_shard_1`.`ob_0`) t ORDER BY val DESC LIMIT 5")) {
        while (r.next()) want.append(r.getLong(1)).append(":").append(r.getInt(2)).append(",");
      }
      System.out.println("mycat   top5: " + mine);
      System.out.println("backend top5: " + want);
      // 再验升序 + 大 LIMIT(全量归并)
      int cntM = 0; try (ResultSet r = ms.executeQuery("SELECT id FROM dbaudit_shard.ob ORDER BY val ASC")) { while (r.next()) cntM++; }
      System.out.println("full sort count=" + cntM + " (expect 80)");
      ms.execute("DROP TABLE dbaudit_shard.ob");
      boolean ok = mine.toString().contentEquals(want) && cntM == 80;
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
