// PROBE: P-B-04 跨分片 JOIN(分片表×全局表 + 分片表×分片表)
// PASS = 两种 JOIN 行数与直连 UNION ALL 基准一致
import java.sql.*;

public class ProbeB_04 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.j1");
      ms.execute("DROP TABLE IF EXISTS dbaudit_shard.j2");
      ms.execute("CREATE TABLE dbaudit_shard.j1 (id bigint primary key, g int) dbpartition by mod_hash(id) dbpartitions 2");
      // 全局表(broadcast): 无分片子句 → global
      ms.execute("CREATE TABLE dbaudit_shard.j2 (g int primary key, name varchar(32)) broadcast");
      try (PreparedStatement p = m.prepareStatement("INSERT INTO dbaudit_shard.j1 VALUES (?,?)")) {
        for (long i = 1; i <= 30; i++) { p.setLong(1, i); p.setInt(2, (int)(i % 5)); p.executeUpdate(); }
      }
      try (PreparedStatement p = m.prepareStatement("INSERT INTO dbaudit_shard.j2 VALUES (?,?)")) {
        for (int g = 0; g < 5; g++) { p.setInt(1, g); p.setString(2, "grp" + g); p.executeUpdate(); }
      }
      // 分片×全局 JOIN
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.j1 a JOIN dbaudit_shard.j2 b ON a.g = b.g")) {
        r.next(); int c1 = r.getInt(1);
        System.out.println("shard×global join rows=" + c1 + " (expect 30)");
        if (c1 != 30) { System.out.println("VERDICT: RED"); System.exit(1); }
      }
      // 分片×分片 JOIN(自连接)
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.j1 a JOIN dbaudit_shard.j1 b ON a.id = b.id - 1")) {
        r.next(); int c2 = r.getInt(1);
        System.out.println("shard×shard selfjoin rows=" + c2 + " (expect 29)");
        if (c2 != 29) { System.out.println("VERDICT: RED"); System.exit(1); }
      }
      // JOIN + 聚合混合
      try (ResultSet r = ms.executeQuery("SELECT b.name, COUNT(*) FROM dbaudit_shard.j1 a JOIN dbaudit_shard.j2 b ON a.g=b.g GROUP BY b.name ORDER BY b.name")) {
        int groups = 0;
        while (r.next()) groups++;
        System.out.println("join+groupby groups=" + groups + " (expect 5)");
        if (groups != 5) { System.out.println("VERDICT: RED"); System.exit(1); }
      }
      ms.execute("DROP TABLE dbaudit_shard.j1"); ms.execute("DROP TABLE dbaudit_shard.j2");
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
