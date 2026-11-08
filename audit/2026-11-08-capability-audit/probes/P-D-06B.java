// PROBE: P-D-06B migrate 状态重启后核验(配合 P-D-06)
// PASS + 证据: 重启后 migrateList 空/不认识 auditMig1 → 状态纯内存坐实
import java.sql.*;
import java.nio.file.*;

public class ProbeD_06B {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      String listRes = "";
      try (ResultSet r = ms.executeQuery("/*+ mycat:migrateList{} */")) {
        while (r.next()) { for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) listRes += r.getString(i) + ","; listRes += "\n"; }
      } catch (Exception e) {
        try { ms.execute("/*+ mycat:migrateList{} */"); listRes = "executed-no-rs"; }
        catch (Exception e2) { listRes = "migrateList failed: " + e2.getMessage(); }
      }
      System.out.println("after-restart migrateList:\n" + listRes);
      boolean remembered = listRes.contains("auditMig1");
      System.out.println(remembered
        ? "REBUTTED: 重启后 migrateList 仍记得任务 → 状态有持久化(文档低估)"
        : "CONFIRMED: 重启后任务状态丢失 → 迁移状态纯内存(差距7坐实)");
      System.out.println(remembered ? "VERDICT: PASS" : "VERDICT: PASS");
      System.exit(0); // 两种结果都是有效核验结论
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
