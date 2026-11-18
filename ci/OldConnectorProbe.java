import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 旧 Java Connector 线冒烟探针（I-DEBT-9 旧线 NO_DATA 治具，B 层仪器）。
 *
 * 为什么单独有这一个文件（不复用 {@code ci/L1Probe.java}）：L1Probe 只跑 {@code SELECT 1}，
 * 量的是"最新 Connector/J 能不能连上"。旧线要回答的是**另一个问题**——
 * Connector/J 5.1.x（旧类名 {@code com.mysql.jdbc.Driver}，早于 caching_sha2_password）与
 * 8.0.x（新类名 {@code com.mysql.cj.jdbc.Driver}）这些**旧客户端**过 Mycat 前端认证握手、
 * 会话初始化、以及一条基本 CRUD 时，是否仍然兼容。spec §3 尺子把 M2 定义为"全 connector
 * 跑基本 CRUD"，旧线此前无仪器覆盖（NO_DATA），本探针即为点亮该格而生。
 *
 * 关键约束（多 Connector 隔离）：不能把旧 jar 挂进 reactor 与主 connector 9.x 同 classpath
 * （类遮蔽），故用 source-file 模式、每次只喂一个 connector jar：
 *   java -cp &lt;old-connector.jar&gt; ci/OldConnectorProbe.java 127.0.0.1 8066 root 123456 mysql
 *
 * 输出契约：逐段打印 {@code STAGE <name> OK/FAIL <detail>}，末尾打印
 * {@code OLDLINE RESULT driver=<类名> version=<驱动版本> verdict=PASS|FAIL}；
 * 全段 OK 退出码 0，任一段 FAIL 退出码 1（宁挂账不伪绿）。
 */
public class OldConnectorProbe {

    public static void main(String[] args) {
        String host = arg(args, 0, "127.0.0.1");
        String port = arg(args, 1, "8066");
        String user = arg(args, 2, "root");
        String password = arg(args, 3, "123456");
        String schema = arg(args, 4, "mysql");
        // I-DEBT-9 旧线 TLS 轴（第 6 位参，缺省 off 保持 CI 向后兼容）：required 时按线别拼 TLS 参数，
        // 与以 -Dmycat.tls.mode=REQUIRED 起的服务端配合，量旧客户端过 Mycat 前端 TLS 握手的兼容面。
        String tls = arg(args, 5, "off");

        // 加载态自证：哪个 jar 真正在起作用（旧线 5.1 vs 8.x 类名不同，DriverManager 按 classpath 选）。
        String driverClass;
        String driverVersion;
        try {
            java.util.Enumeration<java.sql.Driver> drivers = DriverManager.getDrivers();
            java.sql.Driver picked = null;
            while (drivers.hasMoreElements()) {
                java.sql.Driver d = drivers.nextElement();
                String c = d.getClass().getName();
                if (c.startsWith("com.mysql.jdbc.Driver") || c.startsWith("com.mysql.cj.jdbc.Driver")) {
                    picked = d;
                }
            }
            driverClass = picked == null ? "<none>" : picked.getClass().getName();
            driverVersion = picked == null ? "<n/a>" : (picked.getMajorVersion() + "." + picked.getMinorVersion());
        } catch (Throwable t) {
            driverClass = "<probe-error:" + t + ">";
            driverVersion = "<n/a>";
        }

        // allowPublicKeyRetrieval：MySQL 8 默认 caching_sha2_password，公钥拉取须放行，
        // 否则握手在认证阶段即失败（5.1.x 不支持 caching_sha2，这正是要量的旧线兼容点）。
        // TLS 轴：required 时 5.1.x 用 useSSL=true&verifyServerCertificate=false（旧属性名），
        // 8.x 用 sslMode=REQUIRED（useSSL 已废弃）；自签证书故关服务端证书校验。
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8";
        if ("required".equalsIgnoreCase(tls)) {
            if (driverClass.startsWith("com.mysql.jdbc")) {
                url += "&useSSL=true&verifyServerCertificate=false";
            } else {
                url += "&sslMode=REQUIRED";
            }
        } else {
            url += "&useSSL=false&allowPublicKeyRetrieval=true";
        }
        url += "&connectTimeout=15000&socketTimeout=30000";
        System.out.println("TLS mode=" + tls + " driver=" + driverClass);

        boolean pass = true;
        Connection connection = null;

        // STAGE 1: auth + 会话初始化
        try {
            connection = DriverManager.getConnection(url, user, password);
            pass &= report("auth", true, "connected via " + driverClass);
            // TLS 生效判据（口径同 example/.../TlsAxisMatrixTest §2）：Connector/J sslMode=REQUIRED / 5.1 useSSL=true
            // 能建连即权威证明服务端广告 CLIENT_SSL 且本连接 STARTTLS 升级成功（客户端不回退明文）。
            // 故连接成功本身就是加密证据，无需硬依赖 Ssl_cipher。此处仅**非门控**读一行 Ssl_cipher 作参考
            // （Mycat 代理下该值语义可能透传后端，不作为 FAIL 判据，只打印供人判读，绝不并入 verdict）。
            if ("required".equalsIgnoreCase(tls)) {
                try (Statement t1 = connection.createStatement();
                     ResultSet rs = t1.executeQuery("SHOW STATUS LIKE 'Ssl_cipher'")) {
                    String cipher = rs.next() ? String.valueOf(rs.getObject(2)) : "<no-row>";
                    System.out.println("STAGE tlscipher-info (non-gating) Ssl_cipher=[" + cipher + "]");
                } catch (SQLException e) {
                    System.out.println("STAGE tlscipher-info (non-gating) read-failed: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            pass &= report("auth", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            finish(driverClass, driverVersion, pass);
            return;
        }

        try (Statement st = connection.createStatement()) {
            // STAGE 2: SELECT 1（L1 可达面）
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                int rows = 0;
                while (rs.next()) {
                    rs.getObject(1);
                    rows++;
                }
                pass &= report("select1", rows == 1, "rows=" + rows);
            } catch (SQLException e) {
                pass &= report("select1", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // STAGE 3: 版本回读（会话元数据面）
            try (ResultSet rs = st.executeQuery("SELECT VERSION()")) {
                String ver = rs.next() ? String.valueOf(rs.getObject(1)) : "<empty>";
                pass &= report("version", ver != null && !ver.isEmpty(), "server version=" + ver);
            } catch (SQLException e) {
                pass &= report("version", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // STAGE 4: 基本 CRUD 往返（经 Mycat 前端，getObject 逐值回读；M2 尺子"基本 CRUD"面）
            // 走 Mycat 正道：建逻辑库 → use → 带 dbpartition 分片建表 → INSERT → 跨分片归并 SELECT。
            // （往 schema mysql 裸建表会撞 Mycat 管理命令 MycatdbCommand，与 connector 无关，非旧线信号）
            // 用唯一库名避免撞既有库；建库/建表/删库全走代理，量旧线 DDL+DML+SELECT 全链。
            String db = "dboldprobe_" + System.currentTimeMillis();
            try {
                st.execute("DROP DATABASE IF EXISTS " + db);
                st.execute("CREATE DATABASE " + db);
                st.execute("USE " + db);
                st.execute("CREATE TABLE t1 (id INT PRIMARY KEY, name VARCHAR(32), amount DECIMAL(10,2))"
                        + " dbpartition by mod_hash(`id`) dbpartitions 2");
                st.execute("INSERT INTO t1 (id, name, amount) VALUES (1, '探针', 12.34), (2, 'abc', 99.99)");
                try (ResultSet rs = st.executeQuery("SELECT id, name, amount FROM t1 ORDER BY id")) {
                    boolean ok = false;
                    String detail = "no row";
                    if (rs.next()) {
                        Object id = rs.getObject(1);
                        Object name = rs.getObject(2);
                        Object amount = rs.getObject(3);
                        boolean second = rs.next();
                        Object id2 = second ? rs.getObject(1) : null;
                        ok = "1".equals(String.valueOf(id)) && "探针".equals(String.valueOf(name))
                                && String.valueOf(amount).startsWith("12.34")
                                && second && "2".equals(String.valueOf(id2));
                        detail = "r1=[" + id + "," + name + "," + amount + "] r2=" + id2
                                + " types=" + (id == null ? "null" : id.getClass().getSimpleName()) + "/"
                                + (name == null ? "null" : name.getClass().getSimpleName()) + "/"
                                + (amount == null ? "null" : amount.getClass().getSimpleName());
                    }
                    pass &= report("crud", ok, detail);
                }
            } catch (SQLException e) {
                pass &= report("crud", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                try {
                    st.execute("DROP DATABASE IF EXISTS " + db);
                } catch (SQLException ignore) {
                    // 清理尽力而为（含后端物理库不随逻辑 drop 消失的残留，属测试环境范畴），不影响判定
                }
            }

            // STAGE 5: 逐类型深面（旧线 NO_DATA 攻坚：BIT/DATE/DATETIME/TEXT 字符集/DECIMAL 精度/NULL 保真）。
            // 与 STAGE 4 的 INT/VARCHAR/DECIMAL 基本面相比，本段专打 I-DEBT-34/35/36 同族的“旧客户端拿到什么表征”：
            // 不 CAST、不 HEX 归一，直接按 JDBC 契约读回，让 5.1.x vs 8.0.x 的差异如实显形（真破面即真发现，不伪绿）。
            String db5 = "dbolddeep_" + System.currentTimeMillis();
            try {
                st.execute("DROP DATABASE IF EXISTS " + db5);
                st.execute("CREATE DATABASE " + db5);
                st.execute("USE " + db5);
                st.execute("CREATE TABLE tdeep (id INT PRIMARY KEY, b BIT(8), d DATE, dt DATETIME,"
                        + " txt VARCHAR(64), decv DECIMAL(20,10))"
                        + " dbpartition by mod_hash(`id`) dbpartitions 2");
                st.execute("INSERT INTO tdeep (id,b,d,dt,txt,decv) VALUES"
                        + " (1, b'10101010', '2026-10-18', '2026-10-18 13:45:30', '文本探针', 123456.1234567890),"
                        + " (2, b'00000001', '2000-01-01', '2000-02-29 00:00:00', NULL, -0.0000000001)");
                try (ResultSet rs = st.executeQuery("SELECT id,b,d,dt,txt,decv FROM tdeep ORDER BY id")) {
                    StringBuilder bad = new StringBuilder();
                    if (!rs.next()) {
                        bad.append("missing-row1;");
                    } else {
                        checkBit(bad, rs, "b", 2, 0xAA);
                        checkObj(bad, rs.getDate(3), java.sql.Date.valueOf("2026-10-18"), "d");
                        checkObj(bad, rs.getTimestamp(4), java.sql.Timestamp.valueOf("2026-10-18 13:45:30"), "dt");
                        checkStr(bad, rs.getString(5), "txt", "文本探针");
                        checkDec(bad, rs.getBigDecimal(6), "decv", "123456.1234567890");
                    }
                    if (!rs.next()) {
                        bad.append("missing-row2;");
                    } else {
                        checkBit(bad, rs, "b", 2, 0x01);
                        checkObj(bad, rs.getDate(3), java.sql.Date.valueOf("2000-01-01"), "d");
                        checkObj(bad, rs.getTimestamp(4), java.sql.Timestamp.valueOf("2000-02-29 00:00:00"), "dt");
                        checkStr(bad, rs.getString(5), "txt", null); // NULL 保真
                        checkDec(bad, rs.getBigDecimal(6), "decv", "-0.0000000001");
                    }
                    pass &= report("deeptype", bad.length() == 0,
                            bad.length() == 0 ? "bit/date/datetime/text/decimal/null 全保真" : bad.toString());
                }
            } catch (SQLException e) {
                pass &= report("deeptype", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                try {
                    st.execute("DROP DATABASE IF EXISTS " + db5);
                } catch (SQLException ignore) {
                    // 清理尽力而为
                }
            }

            // STAGE 6: full-CRUD 结果保真矩阵（复刻 example/.../CrudResultMatrixTest 的 C2/C5/... 缺口族，
            // 兑现 spec §3 M2"全 connector 跑基本 CRUD"在旧 Java 线（5.1.x/8.0.x）上的最后一格。
            // 与 STAGE4/5 的 INSERT+SELECT 不同，本段补 UPDATE/DELETE、字节/BIT、跨片归并、函数下推、错误契约。
            pass &= stage6(st, connection, url, user, password);
        } catch (SQLException e) {
            pass &= report("statement", false, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                if (connection != null) {
                    DatabaseMetaData md = connection.getMetaData();
                    // 客户端驱动自证（旧 jar 的 getDriverVersion 随线不同）
                    driverVersion = safeDriverVersion(md, driverVersion);
                    connection.close();
                }
            } catch (SQLException ignore) {
                // 关闭异常不改判定
            }
        }

        finish(driverClass, driverVersion, pass);
    }

    // -------------------------------------------------------------- STAGE 6 full-CRUD 矩阵脚手架
    // 独立探针库 dboldcm，建表走 Mycat 正道（mod_hash 分 2 物理库，读回必经跨库归并）。逐格 report("cm.<tag>",...) 纳入 verdict。
    private static final String CM_SHARD = " dbpartition by mod_hash(`id`) dbpartitions 2";

    // 跨片归并聚合硬编码 GT（MYSQL_8_0 persona 下由现代网 CrudResultMatrixTest stdout `oracle=` 固化，见 ledger；非整除数据令 scale/口径真具鉴别力）。
    private static final String AVG_INT_GT = "3.6667";        // C11 avg(int)：22/6，MySQL 整型 AVG scale=div_precision_increment(4)
    private static final String AVG_DEC_GT = "3.66666667";    // C11 avg(decimal(20,4))：scale=4+4=8
    private static final double STDDEV_GT = 1.9720265943665387; // C12 stddev_pop（总体口径 √(SS/n)，SS=23.3333）
    private static final double VAR_GT = 3.8888888888888893;     // C12 var_pop（总体口径 SS/n；实测 MySQL 裸 variance==var_pop 坐实）

    private static boolean stage6(Statement st, Connection connection, String url, String user, String password) throws SQLException {
        boolean ok = true;
        String db6 = "dboldcm_" + System.currentTimeMillis();
        try {
            st.execute("DROP DATABASE IF EXISTS " + db6);
            st.execute("CREATE DATABASE " + db6);
            st.execute("USE " + db6);

            // cm.c2 UPDATE + DELETE 语义（STAGE4/5 仅 INSERT+SELECT，UD 全缺）——判据照抄现代网 C2。
            try {
                st.execute("CREATE TABLE c2 (id bigint PRIMARY KEY, c_var varchar(64))" + CM_SHARD);
                st.execute("insert into c2(id,c_var) values (1,'a'),(2,'b')");
                int updateRows = st.executeUpdate("update c2 set c_var='z' where id=1");
                String gotZ;
                try (ResultSet rs = st.executeQuery("select c_var from c2 where id=1")) {
                    gotZ = rs.next() ? rs.getString(1) : "<none>";
                }
                int delRows = st.executeUpdate("delete from c2 where id=1");
                long cntAfter;
                try (ResultSet rs = st.executeQuery("select count(*) from c2")) {
                    cntAfter = rs.next() ? rs.getLong(1) : -1;
                }
                boolean c2 = "z".equals(gotZ) && cntAfter == 1;
                ok &= report("cm.c2", c2, "updateRows=" + updateRows + " readback=" + gotZ
                        + "(exp z) delRows=" + delRows + " cntAfterDel=" + cntAfter + "(exp 1)");
            } catch (SQLException e) {
                ok &= report("cm.c2", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // cm.c5 VARBINARY 逐字节保真（prepared 写入路径，5.1.x 服务端 prep 兼容点）——payload 照抄现代网 C5。
            try {
                st.execute("CREATE TABLE c5 (id bigint PRIMARY KEY, c_bin varbinary(64))" + CM_SHARD);
                byte[] payload = new byte[]{0, 1, 2, (byte) 0xFE, (byte) 0xFF, 'A', 'B'};
                try (PreparedStatement ps = connection.prepareStatement("insert into c5(id,c_bin) values (1, ?)")) {
                    ps.setBytes(1, payload);
                    ps.executeUpdate();
                }
                byte[] back = null;
                try (ResultSet rs = st.executeQuery("select c_bin from c5 where id=1")) {
                    if (rs.next()) {
                        back = rs.getBytes(1);
                    }
                }
                boolean c5 = Arrays.equals(payload, back);
                ok &= report("cm.c5", c5, "readback=" + (back == null ? "null" : Arrays.toString(back))
                        + " exp=" + Arrays.toString(payload));
            } catch (SQLException e) {
                ok &= report("cm.c5", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // cm.c6 BIT 单片列头保真（**单片命中 where id=1 ⇒ 列头来自后端**，官方 wire 口径：
            // bit(1)→Boolean true、bit(8)=b'10110011'→byte{0xB3}、bit(16)=b'1000000000000001'→{0x80,0x01}、NULL→null）。
            // 用 getObject + 归一 byte[]（参照现代网 canonicalBytes），对 5.1.x/8.x 驱动返回类型差异（byte[]/Number/Boolean）免疫。
            // 【边界·继承现代网】跨片归并 BIT 列头（CalciteRowMetaData 归数值、行值仍后端 byte[] ⇒ BaseRowObservable 崩）
            // 属"类型表征统一"专轮（UserCaseTest#caseCrossShardBitColumnFidelity），本格只测单片、不重开该债。
            try {
                st.execute("CREATE TABLE c6 (id bigint PRIMARY KEY, c_bit1 bit(1), c_bit8 bit(8),"
                        + " c_bit16 bit(16), c_bitnull bit(8))" + CM_SHARD);
                st.execute("insert into c6(id,c_bit1,c_bit8,c_bit16,c_bitnull) values (1, b'1', b'10110011',"
                        + " b'1000000000000001', NULL)");
                StringBuilder bad = new StringBuilder();
                try (ResultSet rs = st.executeQuery("select c_bit1,c_bit8,c_bit16,c_bitnull from c6 where id=1")) {
                    if (!rs.next()) {
                        bad.append("missing-row;");
                    } else {
                        Object o1 = rs.getObject(1);
                        if (!Boolean.TRUE.equals(cmBitBoolean(o1))) {
                            bad.append("c_bit1=[" + o1 + "/" + cmType(o1) + "](exp true);");
                        }
                        byte[] o8 = cmCanonicalBytes(rs.getObject(2));
                        if (!Arrays.equals(o8, new byte[]{(byte) 0xB3})) {
                            bad.append("c_bit8=" + Arrays.toString(o8) + "(exp [-77]);");
                        }
                        byte[] o16 = cmCanonicalBytes(rs.getObject(3));
                        if (!Arrays.equals(o16, new byte[]{(byte) 0x80, (byte) 0x01})) {
                            bad.append("c_bit16=" + Arrays.toString(o16) + "(exp [-128, 1]);");
                        }
                        Object on = rs.getObject(4);
                        if (on != null) {
                            bad.append("c_bitnull!=null(got " + on + "/" + cmType(on) + ");");
                        }
                    }
                }
                boolean c6 = bad.length() == 0;
                ok &= report("cm.c6", c6, c6 ? "bit1/bit8/bit16/null 单片全保真(官方 byte[] 口径)" : bad.toString());
            } catch (SQLException e) {
                ok &= report("cm.c6", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // cm.c7 跨 2 分库聚合+排序（count/sum/order by desc 归并后须与后端单算一致）——判据照抄现代网 C7。
            try {
                st.execute("CREATE TABLE c7 (id bigint PRIMARY KEY, val int)" + CM_SHARD);
                for (int i = 1; i <= 6; i++) {
                    st.execute("insert into c7(id,val) values (" + i + ", " + (i * 10) + ")");
                }
                long cnt;
                try (ResultSet rs = st.executeQuery("select count(*) from c7")) {
                    cnt = rs.next() ? rs.getLong(1) : -1;
                }
                long sum;
                try (ResultSet rs = st.executeQuery("select sum(val) from c7")) {
                    sum = rs.next() ? rs.getLong(1) : -1;
                }
                int rows = 0;
                long firstDesc = Long.MIN_VALUE;
                try (ResultSet rs = st.executeQuery("select val from c7 order by val desc")) {
                    while (rs.next()) {
                        if (rows == 0) {
                            firstDesc = rs.getLong(1);
                        }
                        rows++;
                    }
                }
                boolean c7 = cnt == 6 && sum == 210 && rows == 6 && firstDesc == 60;
                ok &= report("cm.c7", c7, "count=" + cnt + "(exp 6) sum=" + sum + "(exp 210)"
                        + " rows=" + rows + "(exp 6) desc首行=" + firstDesc + "(exp 60)");
            } catch (SQLException e) {
                ok &= report("cm.c7", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // cm.c8 跨分片 LIMIT/OFFSET：归并后分页窗口仍须正确切片——判据照抄现代网 C8。
            try {
                st.execute("CREATE TABLE c8 (id bigint PRIMARY KEY, val int)" + CM_SHARD);
                for (int i = 1; i <= 6; i++) {
                    st.execute("insert into c8(id,val) values (" + i + ", " + i + ")");
                }
                int rows = 0;
                long firstVal = Long.MIN_VALUE;
                try (ResultSet rs = st.executeQuery("select val from c8 order by val limit 2 offset 1")) {
                    while (rs.next()) {
                        if (rows == 0) {
                            firstVal = rs.getLong(1);
                        }
                        rows++;
                    }
                }
                boolean c8 = rows == 2 && firstVal == 2;
                ok &= report("cm.c8", c8, "limit2offset1 rows=" + rows + "(exp 2) 首行=" + firstVal + "(exp 2)");
            } catch (SQLException e) {
                ok &= report("cm.c8", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // cm.c11 跨片归并 AVG 值保真（各分片下推部分 SUM/COUNT、归并后本地算含 DIVIDE 的 AVG，
            // 必与 MySQL 十进制商逐值相等；BigDecimal.compareTo 忽略 scale 只比数值）。行集照抄现代网 C11。
            try {
                st.execute("CREATE TABLE c11 (id bigint PRIMARY KEY, i int, d decimal(20,4))" + CM_SHARD);
                String[][] rows11 = {{"1", "1", "1.0000"}, {"2", "2", "2.0000"}, {"3", "3", "3.0000"},
                        {"4", "4", "4.0000"}, {"5", "5", "5.0000"}, {"6", "7", "7.0000"}};
                for (String[] r : rows11) {
                    st.execute("insert into c11(id,i,d) values (" + r[0] + ", " + r[1] + ", " + r[2] + ")");
                }
                BigDecimal avgI = null;
                try (ResultSet rs = st.executeQuery("select avg(i) from c11")) {
                    if (rs.next()) {
                        Object v = rs.getObject(1);
                        avgI = v == null ? null : new BigDecimal(String.valueOf(v));
                    }
                }
                BigDecimal avgD = null;
                try (ResultSet rs = st.executeQuery("select avg(d) from c11")) {
                    if (rs.next()) {
                        Object v = rs.getObject(1);
                        avgD = v == null ? null : new BigDecimal(String.valueOf(v));
                    }
                }
                boolean c11 = avgI != null && avgI.compareTo(new BigDecimal(AVG_INT_GT)) == 0
                        && avgD != null && avgD.compareTo(new BigDecimal(AVG_DEC_GT)) == 0;
                ok &= report("cm.c11", c11, "avg(int)=" + avgI + "(exp " + AVG_INT_GT + ")"
                        + " avg(decimal)=" + avgD + "(exp " + AVG_DEC_GT + ")");
            } catch (SQLException e) {
                ok &= report("cm.c11", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // cm.c12 跨片归并 STDDEV/VARIANCE 口径保真（I-DEBT-61：裸算子须总体口径÷N 对齐真库，非样本÷N-1；
            // 含 SQRT 浮点，相对容差 1e-5。样本口径偏差 ~20% ≫ 容差 ⇒ 判别力不失）。行集照抄现代网 C12。
            try {
                st.execute("CREATE TABLE c12 (id bigint PRIMARY KEY, i int)" + CM_SHARD);
                String[][] rows12 = {{"1", "1"}, {"2", "2"}, {"3", "3"}, {"4", "4"}, {"5", "5"}, {"6", "7"}};
                for (String[] r : rows12) {
                    st.execute("insert into c12(id,i) values (" + r[0] + ", " + r[1] + ")");
                }
                double sd = Double.NaN, var = Double.NaN;
                try (ResultSet rs = st.executeQuery("select stddev(i) from c12")) {
                    if (rs.next()) {
                        sd = rs.getDouble(1);
                    }
                }
                try (ResultSet rs = st.executeQuery("select variance(i) from c12")) {
                    if (rs.next()) {
                        var = rs.getDouble(1);
                    }
                }
                boolean c12 = cmRelClose(sd, STDDEV_GT) && cmRelClose(var, VAR_GT);
                ok &= report("cm.c12", c12, "stddev=" + sd + "(exp " + STDDEV_GT + ")"
                        + " variance=" + var + "(exp " + VAR_GT + ") rel-tol 1e-5");
            } catch (SQLException e) {
                ok &= report("cm.c12", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // cm.c9 TIMESTAMPDIFF 下推（跨月跨年不等间隔，令 DAY/MONTH/YEAR 真具鉴别力：原生下推后端求值
            // 得 364/11/0，误走本地"字段直减"会得 27/-1/1 一类错值即红）；NULL 传播不崩会话。判据照抄现代网 C9。
            try {
                st.execute("CREATE TABLE c9 (id bigint PRIMARY KEY, a datetime, b datetime)" + CM_SHARD);
                st.execute("insert into c9(id,a,b) values (1, '2021-03-01 00:00:00', '2022-02-28 00:00:00')");
                String[][] units = {{"SECOND", "31449600"}, {"MINUTE", "524160"}, {"HOUR", "8736"},
                        {"DAY", "364"}, {"MONTH", "11"}, {"YEAR", "0"}};
                StringBuilder bad9 = new StringBuilder();
                for (String[] u : units) {
                    long got;
                    try (ResultSet rs = st.executeQuery(
                            "select timestampdiff(" + u[0] + ", a, b) from c9 where id=1")) {
                        got = rs.next() ? rs.getLong(1) : Long.MIN_VALUE;
                    }
                    if (got != Long.parseLong(u[1])) {
                        bad9.append(u[0]).append("=").append(got).append("(exp ").append(u[1]).append("); ");
                    }
                }
                boolean nullProp;
                try (ResultSet rs = st.executeQuery(
                        "select timestampdiff(SECOND, NULL, b) from c9 where id=1")) {
                    rs.next();
                    nullProp = (rs.getObject(1) == null);
                }
                boolean c9 = bad9.length() == 0 && nullProp;
                ok &= report("cm.c9", c9, (c9 ? "6 单位下推全对 + NULL 传播" : bad9 + "nullProp=" + nullProp));
            } catch (SQLException e) {
                ok &= report("cm.c9", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // cm.c10 除零值语义 + 未知列错误契约 + 会话存活（**独立连接**干净起点，量旧客户端过 Mycat 的传输层契约）。
            // ① select 1/0 = 单行 NULL（对齐 MySQL SELECT 期语义，非报错非断连）；② 随后 select 1 会话仍活；
            // ③ 未知列须抛 SQLException（非静默非断连）；④ 错误后 select 2 会话仍活。
            try (Connection c10conn = DriverManager.getConnection(url, user, password);
                 Statement s10 = c10conn.createStatement()) {
                boolean divNull;
                int divRows = 0;
                try (ResultSet rs = s10.executeQuery("select 1/0 as d")) {
                    if (rs.next()) {
                        divRows = 1;
                        divNull = (rs.getObject(1) == null);
                    } else {
                        divNull = false;
                    }
                }
                boolean alive1 = false;
                try (ResultSet rs = s10.executeQuery("select 1")) {
                    alive1 = rs.next();
                }
                boolean threw;
                try (ResultSet rs = s10.executeQuery("select `no_such_col_zzz` from (select 1) t")) {
                    while (rs.next()) {
                        rs.getObject(1);
                    }
                    threw = false;
                } catch (SQLException expected) {
                    threw = true;
                }
                boolean alive2 = false;
                try (ResultSet rs = s10.executeQuery("select 2")) {
                    alive2 = rs.next();
                }
                boolean c10 = divRows == 1 && divNull && alive1 && threw && alive2;
                ok &= report("cm.c10", c10, "1/0=" + (divNull ? "NULL" : "non-null") + "(exp NULL) 除零后存活="
                        + alive1 + " 未知列抛错=" + threw + "(exp true) 错误后存活=" + alive2);
            } catch (SQLException e) {
                ok &= report("cm.c10", false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
            try {
                st.execute("DROP DATABASE IF EXISTS " + db6);
            } catch (SQLException ignore) {
                // 清理尽力而为
            }
        }
        return ok;
    }

    // C12 浮点相对容差（|got-gt| ≤ 1e-5·max(1,|gt|)）：钉总体口径，偏差 ~20% 的样本口径必红；含 SQRT 精度噪声 ~1e-6 量级可容。
    private static boolean cmRelClose(double got, double gt) {
        if (Double.isNaN(got)) {
            return false;
        }
        double tol = 1e-5 * Math.max(1.0, Math.abs(gt));
        return Math.abs(got - gt) <= tol;
    }

    private static String safeDriverVersion(DatabaseMetaData md, String fallback) {
        try {
            return md.getDriverVersion();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean report(String stage, boolean ok, String detail) {
        System.out.println("STAGE " + stage + " " + (ok ? "OK" : "FAIL") + " " + detail);
        return ok;
    }

    private static void finish(String driverClass, String driverVersion, boolean pass) {
        System.out.println("OLDLINE RESULT driver=" + driverClass + " version=" + driverVersion
                + " verdict=" + (pass ? "PASS" : "FAIL"));
        if (!pass) {
            System.exit(1);
        }
    }

    // -------------------------------------------------------------- STAGE 6 C6 BIT 归一助手（移植自现代网 canonicalBytes/toBitBoolean）

    /** BIT(n>1) 读出归 byte[]（官方口径）：byte[] 原样；Number→大端最小无符号剔前导符号字节；Boolean→1 字节；String→UTF-8。 */
    private static byte[] cmCanonicalBytes(Object o) throws SQLException {
        if (o instanceof byte[]) {
            return (byte[]) o;
        }
        if (o == null) {
            return new byte[0];
        }
        if (o instanceof Number) {
            byte[] minimal = new BigDecimal(o.toString()).toBigInteger().toByteArray();
            if (minimal.length > 1 && (minimal[0] == 0 || minimal[0] == (byte) 0xFF)) {
                byte[] trimmed = new byte[minimal.length - 1];
                System.arraycopy(minimal, 1, trimmed, 0, trimmed.length);
                return trimmed;
            }
            return minimal;
        }
        if (o instanceof Boolean) {
            return new byte[]{(byte) (((Boolean) o) ? 1 : 0)};
        }
        if (o instanceof String) {
            return ((String) o).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new SQLException("cm.c6 不支持的读出类型: " + cmType(o));
    }

    /** BIT(1) 读出归 Boolean：Boolean 直取；byte[]/Number 按非零真。 */
    private static Boolean cmBitBoolean(Object o) throws SQLException {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        for (byte x : cmCanonicalBytes(o)) {
            if (x != 0) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    private static String cmType(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    // -------------------------------------------------------------- STAGE 5 逐类型断言助手
    // 每个 helper 只把“不符”追加到 bad（含实际值/期望值/返回 Java 类型），使 FAIL 详情足以定性表征漂移。

    /** BIT：旧线 getObject 可能返 byte[]/Number，归一为无符号低字节再比对（问“0xAA 有没有活下来”）。 */
    private static void checkBit(StringBuilder bad, ResultSet rs, String col, int idx, int expect) {
        try {
            Object o = rs.getObject(idx);
            if (o == null) {
                bad.append(col).append("=null(exp ").append(expect).append(");");
                return;
            }
            int got;
            if (o instanceof byte[]) {
                byte[] bb = (byte[]) o;
                got = bb.length == 0 ? 0 : (bb[bb.length - 1] & 0xFF);
            } else if (o instanceof Number) {
                got = ((Number) o).intValue();
            } else {
                try {
                    got = Integer.parseInt(o.toString().trim());
                } catch (NumberFormatException nfe) {
                    bad.append(col).append("?(").append(o.getClass().getName()).append(",").append(o).append(");");
                    return;
                }
            }
            if (got != expect) {
                bad.append(col).append("=").append(got).append("(exp ").append(expect)
                       .append(",type ").append(o.getClass().getSimpleName()).append(");");
            }
        } catch (SQLException e) {
            bad.append(col).append("err:").append(e.getMessage()).append(";");
        }
    }

    /** Date/Timestamp：按 java.sql 值对象 equals 比对（nanos/scale 差异如实反映）。 */
    private static void checkObj(StringBuilder bad, Object got, Object expect, String col) {
        if (got == null) {
            bad.append(col).append("=null(exp ").append(expect).append(");");
        } else if (!got.equals(expect)) {
            bad.append(col).append("=").append(got).append("(exp ").append(expect)
                    .append(",type ").append(got.getClass().getSimpleName()).append(");");
        }
    }

    private static void checkStr(StringBuilder bad, String got, String col, String expect) {
        if (expect == null) {
            if (got != null) {
                bad.append(col).append("!=null(exp null").append(",got ").append(got).append(");");
            }
            return;
        }
        if (got == null) {
            bad.append(col).append("=null(exp ").append(expect).append(");");
        } else if (!got.equals(expect)) {
            bad.append(col).append("=").append(got).append("(exp ").append(expect).append(");");
        }
    }

    /** DECIMAL：compareTo 容忍标度差异，只判数值是否失真。 */
    private static void checkDec(StringBuilder bad, BigDecimal got, String col, String expect) {
        if (got == null) {
            bad.append(col).append("=null(exp ").append(expect).append(");");
            return;
        }
        if (got.compareTo(new BigDecimal(expect)) != 0) {
            bad.append(col).append("=").append(got.toPlainString()).append("(exp ").append(expect).append(");");
        }
    }

    private static String arg(String[] args, int index, String defaultValue) {
        return args != null && args.length > index ? args[index] : defaultValue;
    }
}
