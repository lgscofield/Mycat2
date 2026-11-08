// PROBE: P-E-05 长稳冒烟: 1000 笔混合 CRUD + 后端连接数前后对比(泄漏指纹)
// PASS = 0 错误 + 60s 观察窗后后端 dbaudit% 连接数回落到接近基线
import java.sql.*;

public class ProbeE_05 {
  public static void main(String[] args) throws Exception {
    String mycat = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String backend = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection ob = DriverManager.getConnection(backend, "audit", "Audit#2026");
         Statement os = ob.createStatement()) {
      int base = conns(os);
      System.out.println("backend conns baseline=" + base);
      int errors = 0;
      try (Connection m = DriverManager.getConnection(mycat, "root", "123456");
           Statement ms = m.createStatement()) {
        ms.execute("DROP TABLE IF EXISTS dbaudit_shard.soak");
        ms.execute("CREATE TABLE dbaudit_shard.soak (id bigint primary key, v int) dbpartition by mod_hash(id) dbpartitions 2");
        try (PreparedStatement ins = m.prepareStatement("INSERT INTO dbaudit_shard.soak VALUES (?,?)");
             PreparedStatement sel = m.prepareStatement("SELECT v FROM dbaudit_shard.soak WHERE id=?");
             PreparedStatement upd = m.prepareStatement("UPDATE dbaudit_shard.soak SET v=v+1 WHERE id=?");
             PreparedStatement del = m.prepareStatement("DELETE FROM dbaudit_shard.soak WHERE id=?")) {
          for (int i = 1; i <= 1000; i++) {
            long id = (i % 50) + 1;
            try {
              switch (i % 4) {
                case 0: del.setLong(1, id + 1000); del.executeUpdate(); ins.setLong(1, id + 1000); ins.setInt(2, i); ins.executeUpdate(); break;
                case 1: sel.setLong(1, id); try (ResultSet r = sel.executeQuery()) { r.next(); } break;
                case 2: upd.setLong(1, id); upd.executeUpdate(); break;
                case 3: sel.setLong(1, id + 1000); try (ResultSet r = sel.executeQuery()) { r.next(); } break;
              }
            } catch (SQLException e) { errors++; if (errors < 3) System.out.println("err@" + i + ": " + e.getMessage()); }
          }
        }
      }
      System.out.println("1000 mixed ops done, errors=" + errors);
      int rightAfter = conns(os);
      System.out.println("backend conns right after=" + rightAfter);
      for (int w = 0; w < 6; w++) { Thread.sleep(10000); int now = conns(os); System.out.println("t+" + ((w + 1) * 10) + "s conns=" + now); }
      int finalC = conns(os);
      try (Connection m = DriverManager.getConnection(mycat, "root", "123456"); Statement ms = m.createStatement()) { ms.execute("DROP TABLE dbaudit_shard.soak"); } catch (Exception ignore) {}
      boolean noLeak = finalC <= base + 5; // 允许池保活少量常驻
      System.out.println("final=" + finalC + " baseline=" + base + " noLeak=" + noLeak + " errors=" + errors);
      boolean ok = errors == 0 && noLeak;
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
    }
  }
  static int conns(Statement s) throws SQLException {
    try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB LIKE 'dbaudit%'")) { r.next(); return r.getInt(1); }
  }
}
