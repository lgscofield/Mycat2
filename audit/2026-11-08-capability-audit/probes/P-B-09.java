// PROBE: P-B-09 I-DEBT-85 复现②: SET sql_mode='' 后同型 getTables + 对照 getTables(null)
// 声明: sql_mode='' 放宽后卡实现层 Unable to implement MycatFilter; getTables(null) 可见(列库列表本体不堵)
// 判定: 声明核验语义 —— 崩=文档诚实 PASS; 全通=文档低估 NOTE; getTables(null) 崩=超出声明(RED)
import java.sql.*;

public class ProbeB_09 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456")) {
      DatabaseMetaData md = m.getMetaData();
      // 对照组: getTables(null) —— 声明说这个能跑(列库面不堵)
      boolean nullOk = true;
      try (ResultSet r = md.getTables(null, null, "%", null)) {
        int n = 0; while (r.next()) n++;
        System.out.println("getTables(null): OK rows=" + n);
      } catch (SQLException e) {
        nullOk = false;
        System.out.println("getTables(null) CRASHED(超出声明): " + e.getMessage());
      }
      // 实验组: sql_mode='' + 非空 typeList
      try (Statement s = m.createStatement()) { s.execute("SET sql_mode=''"); } catch (SQLException se) { System.out.println("SET sql_mode err: " + se.getMessage()); }
      boolean relaxCrash = false;
      try (ResultSet r = md.getTables("dbaudit_shard", null, "%", new String[]{"TABLE", "VIEW"})) {
        int n = 0; while (r.next()) n++;
        System.out.println("sql_mode='' + typeList: WORKED rows=" + n + " (文档低估)");
      } catch (SQLException e) {
        relaxCrash = true;
        System.out.println("sql_mode='' + typeList CRASH: " + e.getMessage());
      }
      System.out.println("summary: getTables(null)Ok=" + nullOk + " relaxedStillCrash=" + relaxCrash);
      System.out.println(nullOk ? "VERDICT: PASS" : "VERDICT: RED");
      System.exit(nullOk ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
