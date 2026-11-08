// PROBE: P-B-10 跨分片 UNION 列头类型保真(vs 直连)
// 验证声明: "跨片列头权威/类型表示统一"修复后 ResultSetMetaData 应与直连一致
// PASS = mycat UNION 的 getColumnType/getColumnTypeName 与直连后端一致
import java.sql.*;

public class ProbeB_10 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String BACKEND = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String SQL = "SELECT id, v, amt FROM dbaudit_shard.sb2 WHERE id <= 5 UNION ALL SELECT id, v, amt FROM dbaudit_shard.sb2 WHERE id > 90 ORDER BY id";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement();
         Connection b = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
         Statement bs = b.createStatement()) {
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.sb2");
      ms.execute("CREATE TABLE dbaudit_shard.sb2 (id bigint primary key, v varchar(20), amt decimal(20,4)) dbpartition by mod_hash(id) dbpartitions 2");
      try (PreparedStatement p = m.prepareStatement("INSERT INTO dbaudit_shard.sb2 VALUES (?,?,?)")) {
        for (long i = 1; i <= 95; i++) { p.setLong(1, i); p.setString(2, "s" + i); p.setBigDecimal(3, new java.math.BigDecimal("1.5")); p.executeUpdate(); }
      }
      ResultSetMetaData rm = null, rb = null;
      try (ResultSet r = ms.executeQuery(SQL)) { r.next(); rm = r.getMetaData(); }
      try (ResultSet r = bs.executeQuery(SQL.replace("dbaudit_shard.sb2", "`dbaudit_shard_0`.`sb2_0`").replace("ORDER BY id", "").replace("SELECT id, v, amt FROM `dbaudit_shard_0`.`sb2_0` WHERE id > 90", "SELECT id, v, amt FROM `dbaudit_shard_1`.`sb2_0` WHERE id > 90"))) { r.next(); rb = r.getMetaData(); }
      // 对比 UNION 两腿合并的 mycat 元数据 vs 单腿直连元数据(类型应同源)
      System.out.println("mycat meta:    cols=" + rm.getColumnCount() + " [1]=" + rm.getColumnTypeName(1) + " [2]=" + rm.getColumnTypeName(2) + " [3]=" + rm.getColumnTypeName(3) + " precision[3]=" + rm.getPrecision(3) + " scale[3]=" + rm.getScale(3));
      System.out.println("backend meta:  cols=" + rb.getColumnCount() + " [1]=" + rb.getColumnTypeName(1) + " [2]=" + rb.getColumnTypeName(2) + " [3]=" + rb.getColumnTypeName(3) + " precision[3]=" + rb.getPrecision(3) + " scale[3]=" + rb.getScale(3));
      boolean t1 = rm.getColumnTypeName(1).equalsIgnoreCase(rb.getColumnTypeName(1));
      boolean t2 = rm.getColumnTypeName(2).equalsIgnoreCase(rb.getColumnTypeName(2));
      boolean t3 = rm.getColumnTypeName(3).equalsIgnoreCase(rb.getColumnTypeName(3));
      boolean sc = rm.getScale(3) == rb.getScale(3);
      System.out.println("types match: " + t1 + "/" + t2 + "/" + t3 + " scale match: " + sc);
      ms.execute("DROP TABLE dbaudit_shard.sb2");
      boolean ok = t1 && t2 && t3 && sc;
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
