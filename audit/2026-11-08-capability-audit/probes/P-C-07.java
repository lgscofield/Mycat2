// PROBE: P-C-07 autocommit=0 跨分片边界(两张分片表同一事务)
// PASS = 同一事务写两张分片表, commit 全持久 / rollback 全回滚
import java.sql.*;

public class ProbeC_07 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("set transaction_policy = proxy");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.mt_a");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.mt_b");
      ms.execute("CREATE TABLE dbaudit_shard.mt_a (id bigint primary key) dbpartition by mod_hash(id) dbpartitions 2");
      ms.execute("CREATE TABLE dbaudit_shard.mt_b (id bigint primary key) dbpartition by mod_hash(id) dbpartitions 2");
      m.setAutoCommit(false);
      ms.execute("INSERT INTO dbaudit_shard.mt_a VALUES (1)");
      ms.execute("INSERT INTO dbaudit_shard.mt_b VALUES (1)");
      m.rollback();
      ms.execute("INSERT INTO dbaudit_shard.mt_a VALUES (2)");
      ms.execute("INSERT INTO dbaudit_shard.mt_b VALUES (2)");
      m.commit();
      m.setAutoCommit(true);
      int ca = count(ms, "SELECT COUNT(*) FROM dbaudit_shard.mt_a");
      int cb = count(ms, "SELECT COUNT(*) FROM dbaudit_shard.mt_b");
      System.out.println("mt_a rows=" + ca + " (expect 1) mt_b rows=" + cb + " (expect 1)");
      ms.execute("DROP TABLE dbaudit_shard.mt_a"); ms.execute("DROP TABLE dbaudit_shard.mt_b");
      boolean ok = ca == 1 && cb == 1;
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
  static int count(Statement s, String q) throws SQLException {
    try (ResultSet r = s.executeQuery(q)) { r.next(); return r.getInt(1); }
  }
}
