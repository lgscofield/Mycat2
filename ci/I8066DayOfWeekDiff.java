import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * I-DEBT-32 活库端到端差分对拍（B 层仪器，接真 8066 代理 + 真 3306 后端）。
 *
 * <p>回答旧离线锁答不出的问题：Mycat 的 DAYOFWEEK 过 Calcite parser 落到本地 codegen 后，
 * **在活体 MySQL 协议链路上真能执行且值与真 MySQL 一致**吗？（尤其 i_s 里入参是 VARCHAR 列，
 * 方案 E+String 形参是否真把全链跑通；周日边界 1、旧公式曾误给 8）
 *
 * <p>用法（source-file 模式，只挂一个 connector jar）：
 *   java -cp &lt;mysql-connector.jar&gt; ci/I8066DayOfWeekDiff.java
 * 期望：Mycat(8066) 与 MySQL(3306) 对同一 SQL 逐值相等 → verdict=PASS；任一格 FAIL 退出码 1（宁挂账不伪绿）。
 */
public class I8066DayOfWeekDiff {

    static String url(String host, int port, String schema) {
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&connectTimeout=15000&socketTimeout=30000";
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        boolean pass = true;

        try (Connection mycat = DriverManager.getConnection(url(host, 8066, "mysql"), "root", "123456");
             Connection mysql = DriverManager.getConnection(url(host, 3306, "mysql"), "root", "123456")) {

            // STAGE 1：i_s 元数据投影里的 DAYOFWEEK(VARCHAR 列) 端到端跑通（旧 bug=在此崩 codegen）。
            // 用投影 + limit（小样本）：活库差分定性发现“null 流满量扫描/distinct 聚合的 RxJava .map()”会 NPE，
            // 那是与 DAYOFWEEK 无关的执行层独立债（control 纯 IF(...,NULL) 亦复现，DEBT-QUEUE 另记），不应混入本锁。
            // 本段只断言：Mycat 对实有行的 i_s 列能真执行 DAYOFWEEK（不再崩）且非日期类型名→全 NULL。
            pass &= stageIRunsAllNull("i_s_dayofweek_over_columns",
                    mycat, "select DAYOFWEEK(DATA_TYPE) from information_schema.columns limit 10");
            // 与真 MySQL 对同一类型名字符串取 DAYOFWEEK 的值一致（均为 NULL）。
            pass &= diffOne("dw_varchar_typename", mycat, mysql, "select DAYOFWEEK('bigint')");

            // STAGE 2：7 天显式日期串逐值对拍（含周日边界，旧公式会让周日给 8）。
            String[] days = {"2017-06-11", "2017-06-12", "2017-06-13", "2017-06-14",
                    "2017-06-15", "2017-06-16", "2017-06-17"}; // Sun..Sat
            for (String d : days) {
                String q = "select DAYOFWEEK('" + d + "')";
                pass &= diffOne("dayofweek_" + d, mycat, mysql, q);
            }

            // STAGE 3：datetime 串 + 非法串（MySQL 宽松语义：非法→NULL，不抛）。
            pass &= diffOne("dayofweek_datetime", mycat, mysql, "select DAYOFWEEK('2017-06-15 13:45:30')");
            pass &= diffOne("dayofweek_invalid", mycat, mysql, "select DAYOFWEEK('not-a-date')");
        }

        System.out.println("DIFF RESULT verdict=" + (pass ? "PASS" : "FAIL"));
        if (!pass) {
            System.exit(1);
        }
    }

    /** i_s 投影端到端：Mycat 能真执行且返回多行（>0），且每行 DAYOFWEEK(非日期型名) 均为 NULL。 */
    static boolean stageIRunsAllNull(String stage, Connection mycat, String sql) {
        try (Statement s = mycat.createStatement()) {
            s.setQueryTimeout(60);
            int rows = 0;
            boolean allNull = true;
            try (ResultSet rs = s.executeQuery(sql)) {
                while (rs.next()) {
                    rows++;
                    if (rs.getObject(1) != null) {
                        allNull = false;
                    }
                }
            }
            boolean ok = rows > 0 && allNull;
            report(stage, ok, "rows=" + rows + " allNull=" + allNull + "（旧 bug=此处 codegen 崩）");
            return ok;
        } catch (Exception e) {
            report(stage, false, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /** 单值对拍：Mycat 与 MySQL 对同一 SQL 首行值相等。 */
    static boolean diffOne(String stage, Connection mycat, Connection mysql, String sql) {
        try (Statement s1 = mycat.createStatement(); Statement s2 = mysql.createStatement()) {
            Object mine = first(s1.executeQuery(sql));
            Object ref = first(s2.executeQuery(sql));
            boolean ok = norm(mine).equals(norm(ref));
            report(stage, ok, "Mycat=" + mine + " MySQL=" + ref);
            return ok;
        } catch (Exception e) {
            report(stage, false, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    static String first(ResultSet rs) throws Exception {
        return rs.next() ? String.valueOf(rs.getObject(1)) : "<empty>";
    }

    static String norm(Object o) {
        if (o == null) {
            return "NULL";
        }
        if (o instanceof Number) {
            return String.valueOf(((Number) o).longValue());
        }
        return o.toString();
    }

    static boolean report(String stage, boolean ok, String detail) {
        System.out.println("STAGE " + stage + " " + (ok ? "OK" : "FAIL") + " " + detail);
        return ok;
    }
}
