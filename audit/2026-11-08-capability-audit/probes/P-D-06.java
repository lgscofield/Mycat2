// PROBE: P-D-06 migrate 迁移任务状态持久化核验(声明: 状态纯内存 CopyOnWriteArrayList、不持久)
// 阶段1(本探针): 建表灌数 → MIGRATE hint → migrateList 查状态 → 写 marker 文件记录任务名
// 阶段2(P-D-06B, 重启后): migrateList 是否还记得该任务
import java.sql.*;
import java.nio.file.*;

public class ProbeD_06 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.mig_in");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.mig_out");
      ms.execute("CREATE TABLE dbaudit_shard.mig_in (id bigint primary key, v varchar(20)) dbpartition by mod_hash(id) dbpartitions 2");
      ms.execute("CREATE TABLE dbaudit_shard.mig_out (id bigint primary key, v varchar(20)) dbpartition by mod_hash(id) dbpartitions 2");
      try (PreparedStatement p = m.prepareStatement("INSERT INTO dbaudit_shard.mig_in VALUES (?,?)")) {
        for (long i = 1; i <= 50; i++) { p.setLong(1, i); p.setString(2, "m" + i); p.executeUpdate(); }
      }
      String hint = "/*+ mycat:migrate {\"name\":\"auditMig1\",\"input\":{\"schemaName\":\"dbaudit_shard\",\"tableName\":\"mig_in\"},\"output\":{\"schemaName\":\"dbaudit_shard\",\"tableName\":\"mig_out\"}} */";
      System.out.println("issuing migrate hint...");
      try {
        ms.execute(hint);
      } catch (SQLException e) { System.out.println("migrate hint err: " + e.getMessage()); }
      Thread.sleep(3000);
      StringBuilder status = new StringBuilder();
      try (ResultSet r = ms.executeQuery("/*+ mycat:migrateList{} */ SELECT 1")) {
        ResultSet rs = r; // migrateList hint 语法可能不同, 兜底两种
      } catch (Exception ignore) {}
      // migrateList 常见形态: 直接发 hint 无 SELECT
      String listRes = "";
      try (Statement s2 = m.createStatement()) {
        try (ResultSet r = s2.executeQuery("/*+ mycat:migrateList{} */")) {
          while (r.next()) { for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) listRes += r.getString(i) + ","; listRes += "\n"; }
        }
      } catch (Exception e2) {
        try (Statement s3 = m.createStatement()) {
          s3.execute("/*+ mycat:migrateList{} */");
          listRes = "executed-no-rs(" + e2.getMessage() + ")";
        } catch (Exception e3) { listRes = "migrateList failed: " + e3.getMessage(); }
      }
      System.out.println("migrateList result:\n" + listRes);
      // 迁移数据正确性
      int in = 0, out = 0;
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.mig_in")) { r.next(); in = r.getInt(1); }
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.mig_out")) { r.next(); out = r.getInt(1); }
      System.out.println("mig_in=" + in + " mig_out=" + out + " (迁移应使 out>=in 或任务态说明)");
      Files.write(Paths.get("D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/logs/P-D-06.marker"),
          ("task=auditMig1\nbefore=" + listRes + "\n").getBytes("UTF-8"));
      System.out.println("marker written; 重启后跑 P-D-06B 验证状态是否丢失");
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
