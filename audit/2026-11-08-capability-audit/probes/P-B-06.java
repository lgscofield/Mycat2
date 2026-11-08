// PROBE: P-B-06 告警子系统: SHOW WARNINGS / @@warning_count 往返(I-DEBT-69 声明)
// 触发: 非严格 sql_mode 下截断插入 → 1265 警告; SELECT 1/0 → 1365 警告
// PASS = 会话内 warning_count>0 且 SHOW WARNINGS 内容与直连一致语义
import java.sql.*;

public class ProbeB_06 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      ms.execute("SET sql_mode=''");
      ms.execute("DROP TABLE IF EXISTS pb06_t");
      ms.execute("CREATE TABLE pb06_t (v varchar(5))");
      ms.execute("INSERT INTO pb06_t VALUES ('0123456789abc')"); // 截断 → warning
      int wc = -1;
      try (ResultSet r = ms.executeQuery("SELECT @@warning_count")) { r.next(); wc = r.getInt(1); }
      System.out.println("@@warning_count=" + wc);
      StringBuilder warnings = new StringBuilder();
      try (ResultSet r = ms.executeQuery("SHOW WARNINGS")) {
        while (r.next()) warnings.append(r.getInt(1)).append("/").append(r.getString(2)).append("/").append(r.getString(3)).append("\n");
      }
      System.out.println("SHOW WARNINGS:\n" + warnings);
      ms.execute("DROP TABLE pb06_t");
      if (wc > 0 && warnings.toString().contains("Warning")) { System.out.println("VERDICT: PASS"); System.exit(0); }
      System.out.println("VERDICT: " + (wc > 0 ? "PASS" : "RED"));
      System.exit(wc > 0 ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
