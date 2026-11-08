// PROBE: P-B-03 跨分片聚合两段式(count/sum/avg/max/min + GROUP BY)
// PASS = mycat 聚合结果与后端 UNION ALL 直连聚合一致
import java.sql.*;

public class ProbeB_03 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String BACKEND = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement();
         Connection b = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
         Statement bs = b.createStatement()) {
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.agg");
      ms.execute("CREATE TABLE dbaudit_shard.agg (id bigint primary key, g int, val decimal(10,2)) dbpartition by mod_hash(id) dbpartitions 2");
      try (PreparedStatement p = m.prepareStatement("INSERT INTO dbaudit_shard.agg VALUES (?,?,?)")) {
        for (long i = 1; i <= 60; i++) { p.setLong(1, i); p.setInt(2, (int)(i % 3)); p.setBigDecimal(3, new java.math.BigDecimal(i * 10)); p.executeUpdate(); }
      }
      String[][] probes = {
        {"COUNT(*)", "SELECT COUNT(*) FROM dbaudit_shard.agg", "SELECT COUNT(*) FROM `dbaudit_shard_0`.`agg_0` + UNION"},
        {"SUM(val)", "SELECT SUM(val) FROM dbaudit_shard.agg", null},
        {"AVG(val)", "SELECT AVG(val) FROM dbaudit_shard.agg", null},
        {"MAX(val)", "SELECT MAX(val) FROM dbaudit_shard.agg", null},
        {"MIN(val)", "SELECT MIN(val) FROM dbaudit_shard.agg", null},
      };
      boolean allOk = true;
      for (String[] pr : probes) {
        String expectSql = "SELECT " + pr[0].replaceAll("\\((\\w+)\\)", "($1)") + " FROM (SELECT * FROM `dbaudit_shard_0`.`agg_0` UNION ALL SELECT * FROM `dbaudit_shard_1`.`agg_0`) t";
        String mine = scalar(ms, pr[1]);
        String want = scalar(bs, wantSql(pr[0]));
        System.out.println(pr[0] + ": mycat=" + mine + " backend_union=" + want);
        if (!cmp(mine, want)) { allOk = false; System.out.println("  MISMATCH"); }
      }
      // GROUP BY 跨片
      java.util.TreeSet<String> mineSet = new java.util.TreeSet<>(), wantSet = new java.util.TreeSet<>();
      try (ResultSet r = ms.executeQuery("SELECT g, COUNT(*) c, SUM(val) s FROM dbaudit_shard.agg GROUP BY g")) {
        while (r.next()) mineSet.add(r.getInt(1) + ":" + r.getInt(2) + ":" + r.getBigDecimal(3).stripTrailingZeros().toPlainString());
      }
      try (ResultSet r = bs.executeQuery("SELECT g, COUNT(*) c, SUM(val) s FROM (SELECT * FROM `dbaudit_shard_0`.`agg_0` UNION ALL SELECT * FROM `dbaudit_shard_1`.`agg_0`) t GROUP BY g")) {
        while (r.next()) wantSet.add(r.getInt(1) + ":" + r.getInt(2) + ":" + r.getBigDecimal(3).stripTrailingZeros().toPlainString());
      }
      System.out.println("GROUP BY: mycat=" + mineSet + " backend=" + wantSet);
      if (!mineSet.equals(wantSet)) { allOk = false; System.out.println("  MISMATCH"); }
      ms.execute("DROP TABLE dbaudit_shard.agg");
      System.out.println(allOk ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(allOk ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
  static String wantSql(String agg) {
    return "SELECT " + agg + " FROM (SELECT * FROM `dbaudit_shard_0`.`agg_0` UNION ALL SELECT * FROM `dbaudit_shard_1`.`agg_0`) t";
  }
  static String scalar(Statement s, String sql) throws SQLException {
    try (ResultSet r = s.executeQuery(sql)) { r.next(); return String.valueOf(r.getObject(1)); }
  }
  static boolean cmp(String a, String b) {
    try { return Double.parseDouble(a) == Double.parseDouble(b); } catch (Exception ignore) {}
    return a.equals(b);
  }
}
