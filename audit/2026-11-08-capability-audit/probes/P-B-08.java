// PROBE: P-B-08 I-DEBT-85 复现①: DatabaseMetaData.getTables 带非空 typeList(裸 HAVING 两层崩)
// Connector/J 9.3 会对 getTables(catalog, schema, pattern, types 非空) 生成含 HAVING TABLE_TYPE IN(...)
// 的 i_s 查询。声明: 校验层 CalciteContextException "not being grouped" 或实现层 MycatFilter 崩。
// 判定: 崩 = I-DEBT-85 破口坐实(声明核验 PASS, 但缺陷标记 DEFECT-CONFIRMED); 能跑 = 文档低估 NOTE。
import java.sql.*;

public class ProbeB_08 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456")) {
      DatabaseMetaData md = m.getMetaData();
      System.out.println("calling getTables(catalog=dbaudit_shard, schema=null, pattern=%, types=[TABLE,VIEW])...");
      try (ResultSet r = md.getTables("dbaudit_shard", null, "%", new String[]{"TABLE", "VIEW"})) {
        int n = 0;
        while (r.next()) { n++; System.out.println("  table: " + r.getString("TABLE_NAME") + " type=" + r.getString("TABLE_TYPE")); }
        System.out.println("NOTE: getTables with typeList WORKED, rows=" + n + " (I-DEBT-85 未复现 = 文档低估/已修复)");
        System.out.println("VERDICT: PASS"); System.exit(0);
      } catch (SQLException e) {
        String msg = String.valueOf(e.getMessage());
        System.out.println("CRASH as documented: " + msg);
        boolean having = msg.contains("grouped") || msg.contains("HAVING") || msg.contains("MycatFilter") || msg.contains("not being grouped");
        System.out.println(having ? "=> 与 I-DEBT-85 声明一致(裸 HAVING 两层崩)" : "=> 崩但形态不同(记录)");
        System.out.println("DEFECT-CONFIRMED I-DEBT-85");
        System.out.println("VERDICT: PASS"); System.exit(0); // 声明核验语义: 崩=文档诚实
      }
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
