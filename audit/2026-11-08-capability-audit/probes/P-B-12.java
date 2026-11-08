// PROBE: P-B-12 视图(CREATE VIEW 经 mycat)跨分片查询
// PASS = 视图创建成功 + 视图查询行数与基表一致
import java.sql.*;

public class ProbeB_12 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("DROP VIEW IF EXISTS dbaudit_shard.v_ob");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.vb");
      ms.execute("CREATE TABLE dbaudit_shard.vb (id bigint primary key, val int) dbpartition by mod_hash(id) dbpartitions 2");
      try (PreparedStatement p = m.prepareStatement("INSERT INTO dbaudit_shard.vb VALUES (?,?)")) {
        for (long i = 1; i <= 40; i++) { p.setLong(1, i); p.setInt(2, (int) i * 3); p.executeUpdate(); }
      }
      ms.execute("CREATE VIEW dbaudit_shard.v_ob AS SELECT id, val FROM dbaudit_shard.vb WHERE val > 30");
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.v_ob")) { r.next();
        int viaView = r.getInt(1);
        int direct = 0;
        try (ResultSet r2 = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.vb WHERE val > 30")) { r2.next(); direct = r2.getInt(1); }
        System.out.println("view rows=" + viaView + " direct=" + direct);
        ms.execute("DROP VIEW dbaudit_shard.v_ob"); ms.execute("DROP TABLE dbaudit_shard.vb");
        boolean ok = viaView == direct && viaView > 0;
        System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
      }
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
