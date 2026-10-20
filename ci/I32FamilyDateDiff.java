import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 标量函数同族批量接入（I-DEBT-32-FAMILY）活库端到端差分对拍（B 层仪器）。
 *
 * <p>回答离线锁答不出的问题：tranche 1+2 接入 String 形参的 11 个日期族标量
 * （YEAR/MONTH/DAYOFYEAR/QUARTER/WEEKDAY/MONTHNAME/DAYNAME/LAST_DAY/TO_DAYS + WEEK/DATEDIFF），
 * 在活体 MySQL 协议链路（8066 代理）上①对 i_s VARCHAR 列真执行不再崩 codegen；②锚点值与真 MySQL 逐值一致。
 *
 * <p>硬断言集=locale/纪元无关的 10 函数；WEEK 默认模数（依赖默认 Locale）与 TO_DAYS（纪元口径存疑）
 * 记 INFO 观测——差异如实打印、不判红，为后续保真票留证（宁挂账不伪绿，也不把已知偏差钉成正确）。
 *
 * <p>用法（source-file 模式，只挂一个 connector jar）：
 *   java -cp &lt;mysql-connector.jar&gt; ci/I32FamilyDateDiff.java [host]
 * 期望：verdict=PASS；任一格 FAIL 退出码 1。
 */
public class I32FamilyDateDiff {

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

            report("backend_version", true, "MySQL=" + scalar(mysql, "select version()")
                    + " Mycat=" + scalar(mycat, "select version()"));

            // STAGE 1：i_s VARCHAR 列投影端到端——旧病=在此崩（Unable to implement MycatProject）。
            // 单列投影 + limit（避开 I-DEBT-59 聚合/distinct 满量管道 NPE，那是独立执行层债不混入本锁）。
            String[] fns = {"YEAR", "MONTH", "DAYOFYEAR", "QUARTER", "WEEKDAY", "WEEK", "MONTHNAME",
                    "DAYNAME", "LAST_DAY", "TO_DAYS"};
            for (String fn : fns) {
                pass &= stageRuns("is_proj_" + fn, mycat,
                        "select " + fn + "(DATA_TYPE) from information_schema.columns limit 10");
            }
            pass &= stageRuns("is_proj_DATEDIFF", mycat,
                    "select DATEDIFF(TABLE_NAME, COLUMN_NAME) from information_schema.columns limit 10");
            pass &= stageRuns("is_proj_WEEK_mode", mycat,
                    "select WEEK(DATA_TYPE, 1) from information_schema.columns limit 10");

            // STAGE 2：硬断言集（locale/纪元无关）逐值对拍——锚点 2017-02-03(周五) + 闰日 + datetime + 非法串。
            String[] anchors = {"2017-02-03", "2016-02-29", "2017-02-03 13:45:00", "not-a-date"};
            for (String fn : new String[]{"YEAR", "MONTH", "DAYOFYEAR", "QUARTER", "WEEKDAY",
                    "MONTHNAME", "DAYNAME", "LAST_DAY"}) {
                for (String d : anchors) {
                    pass &= diffOne(fn + "_" + d, mycat, mysql, "select " + fn + "('" + d + "')");
                }
            }
            pass &= diffOne("DATEDIFF_anchor1", mycat, mysql, "select DATEDIFF('2017-02-03', '2017-01-01')");
            pass &= diffOne("DATEDIFF_anchor2", mycat, mysql, "select DATEDIFF('2016-02-29', '2016-12-31')");
            pass &= diffOne("DATEDIFF_datetime", mycat, mysql, "select DATEDIFF('2017-02-03 10:00:00', '2017-02-03 23:00:00')");
            pass &= diffOne("DATEDIFF_invalid", mycat, mysql, "select DATEDIFF('nope', '2017-01-01')");

            // STAGE 3：INFO 观测集——差异如实打印不判红（WEEK 默认模数依 Locale、TO_DAYS 纪元口径待保真票）。
            for (String d : new String[]{"2017-02-03", "2016-12-31", "not-a-date"}) {
                info("WEEK_" + d, mycat, mysql, "select WEEK('" + d + "')");
                info("WEEK3_" + d, mycat, mysql, "select WEEK('" + d + "', 3)");
                info("TO_DAYS_" + d, mycat, mysql, "select TO_DAYS('" + d + "')");
            }
        }

        System.out.println("DIFF RESULT verdict=" + (pass ? "PASS" : "FAIL"));
        if (!pass) {
            System.exit(1);
        }
    }

    /** i_s 投影端到端：Mycat 能真执行且返回多行（>0）。 */
    static boolean stageRuns(String stage, Connection mycat, String sql) {
        try (Statement s = mycat.createStatement()) {
            s.setQueryTimeout(60);
            int rows = 0;
            try (ResultSet rs = s.executeQuery(sql)) {
                while (rs.next()) {
                    rs.getObject(1);
                    rows++;
                }
            }
            boolean ok = rows > 0;
            report(stage, ok, "rows=" + rows + "（旧 bug=此处 codegen 崩）");
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

    /** INFO 观测：逐值打印 Mycat/MySQL，不累计判红（已知偏差面留证）。 */
    static void info(String stage, Connection mycat, Connection mysql, String sql) {
        try (Statement s1 = mycat.createStatement(); Statement s2 = mysql.createStatement()) {
            Object mine = first(s1.executeQuery(sql));
            Object ref = first(s2.executeQuery(sql));
            System.out.println("INFO " + stage + " Mycat=" + mine + " MySQL=" + ref
                    + (norm(mine).equals(norm(ref)) ? " (same)" : " (DIVERGENT-留证)"));
        } catch (Exception e) {
            System.out.println("INFO " + stage + " ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static Object first(ResultSet rs) throws Exception {
        return rs.next() ? rs.getObject(1) : "<empty>";
    }

    static String scalar(Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            Object o = first(s.executeQuery(sql));
            return String.valueOf(o);
        } catch (Exception e) {
            return "<" + e.getClass().getSimpleName() + ">";
        }
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
