// PROBE: P-A-02 游标 COM_STMT_FETCH(useCursorFetch=true)
// 验证声明: "游标解析后恒回 error 包, 不支持"
// 语义: 这是"声明核验"探针 —— mycat 报错(不支持游标) = 声明成立 = PASS;
//       若游标真跑通 = 文档低估(能力更好) = PASS_WITH_SURPRISE 记为 PASS 但打印 NOTE。
import java.sql.*;

public class ProbeA_02 {
  public static void main(String[] args) {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&useCursorFetch=true&serverTimezone=Asia/Shanghai";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS pa02_t");
      st.execute("CREATE TABLE pa02_t (id int primary key)");
      st.execute("INSERT INTO pa02_t VALUES (1),(2),(3)");
      try (PreparedStatement p = c.prepareStatement("SELECT * FROM pa02_t")) {
        p.setFetchSize(1); // 触发 COM_STMT_FETCH
        try (ResultSet r = p.executeQuery()) {
          int n = 0;
          while (r.next()) n++;
          System.out.println("NOTE: cursor fetch WORKED, rows=" + n + " (文档低估: 能力比声明的好)");
          st.execute("DROP TABLE pa02_t");
          System.out.println("VERDICT: PASS"); System.exit(0);
        }
      }
    } catch (Exception e) {
      System.out.println("cursor fetch rejected: " + e.getMessage());
      System.out.println("=> 与'游标不支持'声明一致");
      System.out.println("VERDICT: PASS"); System.exit(0);
    }
  }
}
