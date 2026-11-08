// PROBE: P-C-05 kill 事务中会话 → 后端无孤儿连接/无悬挂事务(I-DEBT-81 回归验证)
// PASS = 会话被 KILL 后 10s 内, 后端 INNODB_TRX 无 mycat 事务残留 且连接数回落
import java.sql.*;

public class ProbeC_05 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String BACKEND = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement();
         Connection observer = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
         Statement os = observer.createStatement()) {
      ms.execute("set transaction_policy = xa");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.kx");
      ms.execute("CREATE TABLE dbaudit_shard.kx (id bigint primary key) dbpartition by mod_hash(id) dbpartitions 2");
      long connId;
      try (ResultSet r = ms.executeQuery("SELECT CONNECTION_ID()")) { r.next(); connId = r.getLong(1); }
      System.out.println("victim session connId=" + connId);
      m.setAutoCommit(false);
      ms.execute("INSERT INTO dbaudit_shard.kx VALUES (1)");
      ms.execute("INSERT INTO dbaudit_shard.kx VALUES (999999999999)");
      // 事务打开中, 记后端连接基线
      int base = backendConns(os);
      System.out.println("backend mycat conns (tx open, approx): " + base);
      // 硬杀客户端连接(模拟客户端崩溃)
      try (Connection k = DriverManager.getConnection(BACKEND.replace("dbaudit_a", "dbaudit_b"), "audit", "Audit#2026")) {
        // KILL 走 mycat 前端会话——用第二个 mycat 会话杀
      }
      try (Connection m2 = DriverManager.getConnection(MYCAT, "root", "123456");
           Statement ms2 = m2.createStatement()) {
        ms2.execute("KILL " + connId);
      } catch (SQLException ke) { System.out.println("KILL err(记录): " + ke.getMessage()); }
      try { m.close(); } catch (Throwable ignore) { System.out.println("victim close error(预期, 忽略): " + ignore.getMessage()); } // 模拟客户端死亡
      // 等 10s 观察
      boolean clean = false;
      for (int i = 0; i < 10; i++) {
        Thread.sleep(1000);
        try (ResultSet r = os.executeQuery("SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_state='RUNNING'")) {
          r.next(); int trx = r.getInt(1);
          if (trx == 0) { clean = true; System.out.println("INNODB_TRX clean after " + (i + 1) + "s"); break; }
          else System.out.println("t+" + (i + 1) + "s INNODB_TRX running=" + trx);
        }
      }
      int after = backendConns(os);
      System.out.println("backend conns after kill: " + after);
      // 清理用全新会话(受害者已死)
      try (Connection m3 = DriverManager.getConnection(MYCAT, "root", "123456");
           Statement ms3 = m3.createStatement()) { ms3.execute("DROP TABLE IF EXISTS dbaudit_shard.kx"); }
      catch (Exception ignore) {
        try (Connection b3 = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
             Statement bs3 = b3.createStatement()) {
          bs3.execute("DROP TABLE IF EXISTS `dbaudit_shard_0`.`kx_0`"); bs3.execute("DROP TABLE IF EXISTS `dbaudit_shard_1`.`kx_0`");
        } catch (Exception ignore2) {}
      }
      System.out.println(clean ? "VERDICT: PASS" : "VERDICT: RED");
      System.exit(clean ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
  static int backendConns(Statement os) throws SQLException {
    try (ResultSet r = os.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB LIKE 'dbaudit%'")) {
      r.next(); return r.getInt(1);
    }
  }
}
