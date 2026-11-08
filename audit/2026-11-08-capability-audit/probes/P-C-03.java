// PROBE: P-C-03 xa_log 提交意向持久化实证
// 声明(I-DEBT-84 阶段0+1): "XaLogSchema + writeCommitLog 提交意向持久化到各参与者后端,
// 写失败抛异常拒绝提交" —— 修的是"xa_log 生产从不 INSERT, in-doubt 会被误 ROLLBACK 丢数据"。
// PASS = XA commit 后后端物理库出现 xa_log 类表且行数增加(持久化真落地)
// RED  = XA commit 后后端无任何 xa_log 行(文档虚绿)
import java.sql.*;
import java.util.*;

public class ProbeC_03 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String BACKEND = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement();
         Connection b = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
         Statement bs = b.createStatement()) {
      int before = countXaLogRows(bs);
      System.out.println("xa_log rows BEFORE XA commit: " + (before < 0 ? "no xa_log table" : before));
      ms.execute("set transaction_policy = xa");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.xl");
      ms.execute("CREATE TABLE dbaudit_shard.xl (id bigint primary key) dbpartition by mod_hash(id) dbpartitions 2");
      m.setAutoCommit(false);
      ms.execute("INSERT INTO dbaudit_shard.xl VALUES (1)");
      ms.execute("INSERT INTO dbaudit_shard.xl VALUES (999999999999)");
      m.commit();
      m.setAutoCommit(true);
      Thread.sleep(1500);
      int after = countXaLogRows(bs);
      System.out.println("xa_log rows AFTER XA commit: " + (after < 0 ? "no xa_log table" : after));
      // 列出 backend dbaudit_shard% 里所有表名供取证
      List<String> tables = new ArrayList<>();
      try (ResultSet r = bs.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA LIKE 'dbaudit%'")) {
        while (r.next()) tables.add(r.getString(1) + "." + r.getString(2));
      }
      System.out.println("backend tables under dbaudit%: " + tables);
      ms.execute("DROP TABLE dbaudit_shard.xl");
      boolean persisted = after > 0 && after > Math.max(before, 0);
      System.out.println(persisted ? "VERDICT: PASS (提交意向持久化坐实)" : "VERDICT: RED (XA 提交后后端无 xa_log 行 → 文档虚绿)");
      System.exit(persisted ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
  static int countXaLogRows(Statement bs) {
    try {
      int total = 0; boolean found = false;
      ResultSet r = bs.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES WHERE TABLE_NAME LIKE '%xa_log%' OR TABLE_NAME LIKE 'xa%'");
      List<String> l = new ArrayList<>();
      while (r.next()) l.add("`" + r.getString(1) + "`.`" + r.getString(2) + "`");
      r.close();
      for (String t : l) {
        found = true;
        try (ResultSet rr = bs.executeQuery("SELECT COUNT(*) FROM " + t)) { rr.next(); total += rr.getInt(1); }
        System.out.println("  " + t + " counted");
      }
      return found ? total : -1;
    } catch (Exception e) { System.out.println("  xa_log probe err: " + e); return -1; }
  }
}
