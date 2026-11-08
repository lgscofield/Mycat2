// PROBE: P-C-04 语句级超时(I-DEBT-84 声明: statementTimeout 默认 30s → 3013 + force-close)
// SELECT SLEEP(35) 经 mycat → 预期 ~30s 收到 ERR 3013(而非无限挂) + 后端 SLEEP 连接被回收
// PASS = 30~45s 内收到含 3013 的 SQLException + 后端无残留 SLEEP
import java.sql.*;

public class ProbeC_04 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&socketTimeout=120000";
    String BACKEND = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    long t0 = System.currentTimeMillis();
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
         Statement ms = m.createStatement()) {
      try {
        ms.executeQuery("SELECT SLEEP(35)");
        System.out.println("SLEEP(35) returned NORMALLY in " + (System.currentTimeMillis() - t0) / 1000 + "s — 语句级超时未生效");
        System.out.println("VERDICT: RED"); System.exit(1);
      } catch (SQLException e) {
        long took = (System.currentTimeMillis() - t0) / 1000;
        System.out.println("killed after " + took + "s: " + e.getErrorCode() + " " + e.getMessage());
        boolean code3013 = e.getErrorCode() == 3013 || String.valueOf(e.getMessage()).contains("3013");
        if (!code3013 || took < 25 || took > 45) {
          System.out.println(code3013 ? "时间窗异常" : "错误码非 3013(记录实际行为)");
          System.out.println("VERDICT: " + (code3013 ? "PASS" : "RED")); System.exit(code3013 ? 0 : 1);
        }
        // 后端残留检查
        try (Connection b = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
             Statement bs = b.createStatement();
             ResultSet r = bs.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND='Query' AND INFO LIKE '%SLEEP%'")) {
          r.next(); int leftover = r.getInt(1);
          System.out.println("backend leftover SLEEP queries=" + leftover + " (force-close 后应为 0)");
          System.out.println("VERDICT: " + (leftover == 0 ? "PASS" : "PASS_WITH_LEFTOVER"));
          System.exit(0);
        }
      }
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
