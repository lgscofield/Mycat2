// PROBE: P-C-01 本地事务 + SAVEPOINT(proxy 策略)
// PASS = commit 持久 / rollback 回滚 / rollback-to-savepoint 只回滚部分
import java.sql.*;

public class ProbeC_01 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("set transaction_policy = proxy");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.tx1");
      ms.execute("CREATE TABLE dbaudit_shard.tx1 (id bigint primary key, v varchar(10)) dbpartition by mod_hash(id) dbpartitions 2");
      // commit 路径
      m.setAutoCommit(false);
      ms.execute("INSERT INTO dbaudit_shard.tx1 VALUES (1,'a')");
      m.commit();
      // rollback 路径
      ms.execute("INSERT INTO dbaudit_shard.tx1 VALUES (2,'b')");
      m.rollback();
      // savepoint 路径
      ms.execute("INSERT INTO dbaudit_shard.tx1 VALUES (3,'c')");
      Savepoint sp = m.setSavepoint();
      ms.execute("INSERT INTO dbaudit_shard.tx1 VALUES (4,'d')");
      m.rollback(sp);
      m.commit();
      m.setAutoCommit(true);
      int c = 0; boolean has4 = false;
      try (ResultSet r = ms.executeQuery("SELECT id FROM dbaudit_shard.tx1")) {
        while (r.next()) { c++; if (r.getLong(1) == 4) has4 = true; }
      }
      System.out.println("final rows=" + c + " (expect 2: id1,id3) has_id4(应为false)=" + has4);
      ms.execute("DROP TABLE dbaudit_shard.tx1");
      boolean ok = c == 2 && !has4;
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
