// PROBE: P-C-02 XA 双分片 commit / rollback
// PASS = xa 策略下跨分片 commit 全持久 + rollback 全回滚 + 会话关闭无残留
import java.sql.*;

public class ProbeC_02 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("set transaction_policy = xa");
      System.out.println("transaction_policy switched to xa");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.xa1");
      ms.execute("CREATE TABLE dbaudit_shard.xa1 (id bigint primary key, v varchar(10)) dbpartition by mod_hash(id) dbpartitions 2");
      // XA commit: 两个 id 打到不同分片(mod_hash 散布, 1..4 至少跨两片)
      m.setAutoCommit(false);
      ms.execute("INSERT INTO dbaudit_shard.xa1 VALUES (1,'a')");
      ms.execute("INSERT INTO dbaudit_shard.xa1 VALUES (999999999999,'z')");
      m.commit();
      m.setAutoCommit(true);
      // XA rollback
      m.setAutoCommit(false);
      ms.execute("INSERT INTO dbaudit_shard.xa1 VALUES (5,'del1')");
      ms.execute("INSERT INTO dbaudit_shard.xa1 VALUES (6,'del2')");
      m.rollback();
      m.setAutoCommit(true);
      int c = 0; boolean delFound = false;
      try (ResultSet r = ms.executeQuery("SELECT id, v FROM dbaudit_shard.xa1")) {
        while (r.next()) { c++; long id = r.getLong(1); if (id == 5 || id == 6) delFound = true; }
      }
      System.out.println("final rows=" + c + " (expect 2) rollbackRowsLeaked=" + delFound);
      ms.execute("DROP TABLE dbaudit_shard.xa1");
      boolean ok = c == 2 && !delFound;
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
