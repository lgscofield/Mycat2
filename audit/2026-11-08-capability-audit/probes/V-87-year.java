// V-87 活体验收(修正判据): YEAR() 裸投影 —— 与真库行为逐法对拍 + 会话存活(I-DEBT-87)
// 修复前: ERROR 2013 连接被杀。修复后: 会话必须存活, 且三种读取法与 3306 直连语义一致
// (getObject 在真库上本就因 yearIsDateType 返回 Date "2026-01-01" —— 保真即目标)
import java.sql.*;

public class V87Year {
  public static void main(String[] args) throws Exception {
    String mycatUrl = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String backUrl = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String SQL = "SELECT YEAR('2026-11-08')";
    try (Connection m = DriverManager.getConnection(mycatUrl, "root", "123456");
         Statement ms = m.createStatement();
         Connection b = DriverManager.getConnection(backUrl, "audit", "Audit#2026");
         Statement bs = b.createStatement()) {
      Object mv = null, bv = null; String mStr = null, bStr = null; int mInt = -999, bInt = -999;
      try (ResultSet r = ms.executeQuery(SQL)) { r.next(); mv = r.getObject(1); mStr = r.getString(1); mInt = r.getInt(1); }
      try (ResultSet r = bs.executeQuery(SQL)) { r.next(); bv = r.getObject(1); bStr = r.getString(1); bInt = r.getInt(1); }
      System.out.println("mycat:   getObject=" + mv + "(" + (mv == null ? "null" : mv.getClass().getSimpleName()) + ") getString=" + mStr + " getInt=" + mInt);
      System.out.println("backend: getObject=" + bv + "(" + (bv == null ? "null" : bv.getClass().getSimpleName()) + ") getString=" + bStr + " getInt=" + bInt);
      boolean objectParity = String.valueOf(mv).equals(String.valueOf(bv));
      boolean classParity = (mv == null) == (bv == null) && (mv == null || mv.getClass().equals(bv.getClass()));
      boolean strParity = String.valueOf(mStr).equals(String.valueOf(bStr));
      boolean intParity = mInt == bInt;
      System.out.println("parity: object=" + objectParity + " class=" + classParity + " string=" + strParity + " int=" + intParity);
      // 会话存活断言(修复前: 连接已被杀, 此查询必败)
      try (ResultSet r = ms.executeQuery("SELECT 'still-alive'")) { r.next(); }
      System.out.println("session survived = true");
      // 既有绿面回归守卫
      try (ResultSet r = ms.executeQuery("SELECT MONTH('2026-11-08'), QUARTER('2026-11-08')")) {
        r.next();
        boolean guard = r.getInt(1) == 11 && r.getInt(2) == 4;
        System.out.println("MONTH/QUARTER guard = " + guard);
      }
      boolean ok = objectParity && classParity && strParity && intParity;
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED"); System.exit(ok ? 0 : 1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
