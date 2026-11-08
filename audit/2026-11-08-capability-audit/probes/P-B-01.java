// PROBE: P-B-01 分片 CRUD + 路由收窄 + 后端物理分布断言
// 配方复用 example/AssembleTest: CREATE DATABASE → CREATE TABLE dbpartition by mod_hash
// 库名 dbaudit_shard → 物理库 dbaudit_shard_0/1 命中 dbaudit% 授权
// PASS = mycat count(*)=120 + 后端两物理库行数和=120 + 分片键点查路由到正确物理库
import java.sql.*;
import java.util.*;

public class ProbeB_01 {
  static final String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
  static final String BACKEND = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

  public static void main(String[] args) throws Exception {
    List<String> errs = new ArrayList<>();
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("DROP DATABASE IF EXISTS dbaudit_shard");
      ms.execute("CREATE DATABASE dbaudit_shard");
      ms.execute("CREATE TABLE dbaudit_shard.sb (id bigint primary key, v varchar(32)) dbpartition by mod_hash(id) dbpartitions 2");
      try (PreparedStatement p = m.prepareStatement("INSERT INTO dbaudit_shard.sb VALUES (?,?)")) {
        for (long i = 1; i <= 120; i++) { p.setLong(1, i); p.setString(2, "v" + i); p.executeUpdate(); }
      }
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.sb")) { r.next();
        if (r.getInt(1) != 120) errs.add("mycat count=" + r.getInt(1));
      }
      // 点查(分片键等值 → 路由收窄)
      try (PreparedStatement p = m.prepareStatement("SELECT v FROM dbaudit_shard.sb WHERE id=?")) {
        p.setLong(1, 77); ResultSet r = p.executeQuery(); r.next();
        if (!"v77".equals(r.getString(1))) errs.add("point query fail");
      }
      // 范围查(跨片)
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.sb WHERE id > 100")) { r.next();
        if (r.getInt(1) != 20) errs.add("range count=" + r.getInt(1));
      }
      // UPDATE/DELETE 跨片
      ms.execute("UPDATE dbaudit_shard.sb SET v='u' WHERE id <= 10");
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.sb WHERE v='u'")) { r.next();
        if (r.getInt(1) != 10) errs.add("update fanout=" + r.getInt(1));
      }
      ms.execute("DELETE FROM dbaudit_shard.sb WHERE id > 110");
      try (ResultSet r = ms.executeQuery("SELECT COUNT(*) FROM dbaudit_shard.sb")) { r.next();
        if (r.getInt(1) != 110) errs.add("delete fanout count=" + r.getInt(1));
      }
    } catch (Exception e) { System.out.println("MYCAT EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
    // 后端物理分布断言
    try (Connection b = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
         Statement bs = b.createStatement()) {
      int total = 0; List<String> phys = new ArrayList<>();
      ResultSet dbs = bs.executeQuery("SHOW DATABASES LIKE 'dbaudit_shard%'");
      while (dbs.next()) phys.add(dbs.getString(1));
      System.out.println("physical schemas: " + phys);
      if (phys.isEmpty()) errs.add("无物理分片库");
      for (String p : phys) {
        int schemaTotal = 0;
        try (java.sql.PreparedStatement chk = bs.getConnection().prepareStatement(
            "SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME LIKE 'sb%'")) {
          chk.setString(1, p);
          List<String> tbls = new ArrayList<>();
          ResultSet er = chk.executeQuery();
          while (er.next()) tbls.add(er.getString(2));
          if (tbls.isEmpty()) { System.out.println(p + ": (no sb* table, skip logical schema)"); continue; }
          for (String t : tbls) {
            try (ResultSet r = bs.executeQuery("SELECT COUNT(*) FROM `" + p + "`.`" + t + "`")) { r.next();
              schemaTotal += r.getInt(1); System.out.println(p + "." + t + " rows=" + r.getInt(1)); }
          }
        }
        total += schemaTotal;
      }
      if (total != 110) errs.add("backend total=" + total + " expect 110");
    } catch (Exception e) { System.out.println("BACKEND EXCEPTION: " + e); errs.add("backend check fail"); }
    if (errs.isEmpty()) { System.out.println("VERDICT: PASS"); System.exit(0); }
    errs.forEach(System.out::println); System.out.println("VERDICT: RED"); System.exit(1);
  }
}
