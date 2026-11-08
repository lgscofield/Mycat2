// PROBE: P-B-07 information_schema 深面: SHOW TABLES / DESC / i_s.TABLES / i_s.COLUMNS
// PASS = 分片表在 SHOW TABLES 可见 + DESC 列型正确 + i_s.TABLES/COLUMNS 行数>0
import java.sql.*;

public class ProbeB_07 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.meta1");
      ms.execute("CREATE TABLE dbaudit_shard.meta1 (id bigint primary key, name varchar(50) not null, amt decimal(12,3)) dbpartition by mod_hash(id) dbpartitions 2");
      boolean shown = false;
      try (ResultSet r = ms.executeQuery("SHOW TABLES FROM dbaudit_shard")) {
        while (r.next()) { if ("meta1".equalsIgnoreCase(r.getString(1))) shown = true; }
      }
      System.out.println("SHOW TABLES sees meta1: " + shown);
      boolean descOk = false;
      try (ResultSet r = ms.executeQuery("DESC dbaudit_shard.meta1")) {
        int cols = 0;
        while (r.next()) { String f = r.getString("Field"); if (++cols == 3 && "amt".equalsIgnoreCase(f)) descOk = true; }
        System.out.println("DESC columns=" + cols + " thirdFieldOk=" + descOk);
      }
      int i_sTables = -1, i_sCols = -1;
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='dbaudit_shard'")) { r.next(); i_sTables = r.getInt(1); }
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='dbaudit_shard' AND TABLE_NAME='meta1'")) { r.next(); i_sCols = r.getInt(1); }
      System.out.println("i_s.TABLES(dbaudit_shard)=" + i_sTables + " i_s.COLUMNS(meta1)=" + i_sCols);
      ms.execute("DROP TABLE dbaudit_shard.meta1");
      boolean ok = shown && descOk && i_sTables >= 1 && i_sCols >= 3;
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
