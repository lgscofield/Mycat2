/*
 * I-DEBT-88 离线锁：showDataSources 凭据脱敏。
 *
 * 活体 RED 证据（修复前）：audit/2026-11-08-capability-audit/logs/P-D-01.log ——
 * `/*+ mycat:showDataSources{} *`/ 结果行明文回显后端密码（prototypeDs / audit / Audit#2026）。
 * 本锁钉两件事：
 *  1) maskPassword 对任意非空口令输出固定掩码（不泄露长度/内容）；
 *  2) 形状锁：null 进 null 出（列型/单元格形状不变），非空输入的输出永不等于输入。
 */
package io.mycat.sqlhandler.dql;

import io.mycat.api.collector.RowBaseIterator;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.config.DatasourceConfig;
import io.mycat.datasource.jdbc.datasource.JdbcDataSource;
import io.mycat.replica.ReplicaSelectorRuntime;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HintHandlerShowDataSourcesMaskingTest {

    @Test
    public void nonBlankPasswordIsFullyMasked() {
        assertEquals("******", HintHandler.maskPassword("Audit#2026"));
        assertEquals("******", HintHandler.maskPassword("123456"));
        assertEquals("******", HintHandler.maskPassword("a"));
        assertEquals("******", HintHandler.maskPassword("  spaced secret  "));
    }

    @Test
    public void nullPasswordStaysNull() {
        assertNull(HintHandler.maskPassword(null));
    }

    @Test
    public void blankPasswordIsStillMasked() {
        // 空串也可能是有语义的口令形态（如禁用口令），一概掩码，保持输出与输入无信息等价
        assertEquals("******", HintHandler.maskPassword(""));
    }

    @Test
    public void outputNeverEqualsNonEmptyInput() {
        for (String raw : new String[]{"x", "Audit#2026", "p@ssw0rd-with-长度"}) {
            String masked = HintHandler.maskPassword(raw);
            assertNotEquals(raw, masked);
            assertEquals("******", masked);
        }
    }

    @Test
    public void urlUserInfoAndSensitiveQueryValuesAreMaskedWithoutRewritingOtherText() {
        String url = "jdbc:mysql://alice:p%40ss@db.example:3306/shop"
                + "?password=p%40ss&user=alice&pwd=one&token=two&access_token=three"
                + "&key=four&api_key=five&secret=six&accessKey=seven&secretKey=eight"
                + "&trustStorePassword=nine&keyStorePassword=ten"
                + "&useSSL=false&characterEncoding=UTF-8&note=password%3Dkept";

        assertEquals("jdbc:mysql://db.example:3306/shop"
                + "?password=******&user=******&pwd=******&token=******&access_token=******"
                + "&key=******&api_key=******&secret=******&accessKey=******&secretKey=******"
                + "&trustStorePassword=******&keyStorePassword=******"
                + "&useSSL=false&characterEncoding=UTF-8&note=password%3Dkept",
                HintHandler.maskDataSourceUrl(url));
    }

    @Test
    public void urlEncodedSensitiveKeysAndEmptyValuesAreMasked() {
        assertEquals("jdbc:mysql://db.example/shop?%70assword=******&%75ser=******"
                        + "&access%5Ftoken=******&%61pi%5Fkey=******&PASSWORD=******",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop?%70assword=a"
                        + "&%75ser=b&access%5Ftoken=c&%61pi%5Fkey=&PASSWORD=d"));
    }

    @Test
    public void urlAuthorityUserInfoIsRemovedEvenWhenPasswordContainsRawAtSign() {
        assertEquals("jdbc:mysql://db.example:3306/shop",
                HintHandler.maskDataSourceUrl("jdbc:mysql://alice:p@ss@db.example:3306/shop"));
        assertEquals("jdbc:mysql://db.example/shop",
                HintHandler.maskDataSourceUrl("jdbc:mysql://alice@db.example/shop"));
    }

    @Test
    public void mysqlAuthorityParenthesesDoNotHideUserInfo() {
        assertEquals("jdbc:mysql://db.example/shop",
                HintHandler.maskDataSourceUrl("jdbc:mysql://alice:p(q@db.example/shop"));
        assertEquals("jdbc:mariadb://db.example/shop",
                HintHandler.maskDataSourceUrl("jdbc:mariadb://alice:p)q@db.example/shop"));
    }

    @Test
    public void urlsWithoutSensitiveFragmentsAndNullKeepTheirOriginalShape() {
        String url = "jdbc:postgresql://db.example:5432/shop?sslmode=require&applicationName=mycat";
        assertEquals(url, HintHandler.maskDataSourceUrl(url));
        assertNull(HintHandler.maskDataSourceUrl(null));
    }

    @Test
    public void malformedEncodedQueryKeyFailsClosedWithoutDroppingOtherParameters() {
        assertEquals("jdbc:mysql://db.example/shop?pass%ZZword=******&useSSL=false",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop?pass%ZZword=secret&useSSL=false"));
    }

    @Test
    public void semicolonDelimitedJdbcPropertiesAreMasked() {
        assertEquals("jdbc:sqlserver://db.example:1433;database=shop;user=******;password=******;encrypt=true",
                HintHandler.maskDataSourceUrl("jdbc:sqlserver://db.example:1433;database=shop;user=alice;password=secret;encrypt=true"));
        assertEquals("jdbc:h2:mem:shop;USER=******;PASSWORD=******;DB_CLOSE_ON_EXIT=FALSE",
                HintHandler.maskDataSourceUrl("jdbc:h2:mem:shop;USER=alice;PASSWORD=secret;DB_CLOSE_ON_EXIT=FALSE"));
    }

    @Test
    public void semicolonTerminatesAuthorityBeforeApplicationPropertyValues() {
        assertEquals("jdbc:sqlserver://db.example:1433;applicationName=a@b;password=******;encrypt=true",
                HintHandler.maskDataSourceUrl("jdbc:sqlserver://db.example:1433;applicationName=a@b;password=secret;encrypt=true"));
    }

    @Test
    public void querySemicolonsRemainInsideValuesInsteadOfStartingNewProperties() {
        assertEquals("jdbc:mysql://db.example/shop?password=******&note=c;user=kept&user=******",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop?password=a;b&note=c;user=kept&user=alice"));
    }

    @Test
    public void semicolonDelimitedPropertiesKeepQuotedDelimitersAndEscapedQuotesInsideValues() {
        assertEquals("jdbc:h2:mem:shop;USER=******;PASSWORD=******;NOTE='a;user=kept';DB_CLOSE_ON_EXIT=FALSE",
                HintHandler.maskDataSourceUrl("jdbc:h2:mem:shop;USER='alice';PASSWORD='s;e''cret';NOTE='a;user=kept';DB_CLOSE_ON_EXIT=FALSE"));
    }

    @Test
    public void queryAmpersandsAreParsedAfterUnmatchedQuotes() {
        assertEquals("jdbc:mysql://db.example/shop?note='x&password=******&useSSL=false",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop?note='x&password=secret&useSSL=false"));
    }

    @Test
    public void sqlServerBraceEscapedPropertiesKeepDelimitersInsideValues() {
        assertEquals("jdbc:sqlserver://db.example:1433;password=******;user=******;note={a}}b;c};encrypt=true",
                HintHandler.maskDataSourceUrl("jdbc:sqlserver://db.example:1433;password={top;secret};user=alice;note={a}}b;c};encrypt=true"));
    }

    @Test
    public void mysqlAuthorityAllowsRawSemicolonInsidePassword() {
        assertEquals("jdbc:mysql://db.example:3306/shop?useSSL=false",
                HintHandler.maskDataSourceUrl("jdbc:mysql://alice:p;ss@db.example:3306/shop?useSSL=false"));
    }

    @Test
    public void mysqlAddressPropertiesMaskCredentialsWithoutDamagingOtherProperties() {
        assertEquals("jdbc:mysql://address=(host=db.example:3306)(user=******)(password=******)(note=a@b)/shop?useSSL=false",
                HintHandler.maskDataSourceUrl("jdbc:mysql://address=(host=db.example:3306)(user=alice)(password=secret)(note=a@b)/shop?useSSL=false"));
    }

    @Test
    public void malformedUtf8QueryKeyFailsClosed() {
        assertEquals("jdbc:mysql://db.example/shop?%FF=******&useSSL=false",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop?%FF=secret&useSSL=false"));
    }

    @Test
    public void sensitivePropertyKeysIgnoreSurroundingWhitespace() {
        assertEquals("jdbc:mysql://db.example/shop? password =******&useSSL=false",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop? password =secret&useSSL=false"));
        assertEquals("jdbc:mysql://address=(host=db.example)( password =******)/shop",
                HintHandler.maskDataSourceUrl("jdbc:mysql://address=(host=db.example)( password =secret)/shop"));
    }

    @Test
    public void mysqlBracketAuthorityPropertiesMaskCredentials() {
        assertEquals("jdbc:mysql://[host=db.example,user=******,password=******]/shop?useSSL=false",
                HintHandler.maskDataSourceUrl("jdbc:mysql://[host=db.example,user=alice,password=secret]/shop?useSSL=false"));
    }

    @Test
    public void queryAfterMysqlPathSemicolonStillMasksSensitiveProperties() {
        assertEquals("jdbc:mysql://db.example/shop;v=1?password=******&useSSL=false",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop;v=1?password=secret&useSSL=false"));
    }

    @Test
    public void addressTextInsideQueryValuesIsNotRewritten() {
        assertEquals("jdbc:mysql://db.example/shop?note=address=(host=db)(user=kept)",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop?note=address=(host=db)(user=kept)"));
    }

    @Test
    public void oracleThinCredentialsAreRemovedWithoutDamagingLdapUrls() {
        assertEquals("jdbc:oracle:thin:@db.example:1521:ORCL",
                HintHandler.maskDataSourceUrl("jdbc:oracle:thin:alice:p%40ss@db.example:1521:ORCL"));
        String ldapUrl = "jdbc:oracle:thin:@ldap://directory.example:389/db,cn=OracleContext,dc=example";
        assertEquals(ldapUrl, HintHandler.maskDataSourceUrl(ldapUrl));
    }

    @Test
    public void oracleLdapDnAtSignIsPreserved() {
        String ldapUrl = "jdbc:oracle:thin:@ldap://directory.example:389/db,cn=a@b,dc=example";
        assertEquals(ldapUrl, HintHandler.maskDataSourceUrl(ldapUrl));
    }

    @Test
    public void prefixedAndSeparatedSensitivePropertyNamesAreMasked() {
        assertEquals("jdbc:mysql://db.example/shop?sslPassword=******&client_secret=******"
                        + "&access-token=******&db.password=******&monkey=kept",
                HintHandler.maskDataSourceUrl("jdbc:mysql://db.example/shop?sslPassword=one&client_secret=two"
                        + "&access-token=three&db.password=four&monkey=kept"));
    }

    @Test
    public void showDataSourcesProducesMaskedUsableRowsAndNeverRendersDataSourceToString() {
        DatasourceConfig config = new DatasourceConfig();
        config.setName("secureDs");
        config.setUser("app-user");
        config.setPassword("column-secret");
        config.setDbType("mariadb");
        config.setUrl("jdbc:mariadb://alice:url-secret@db.example:3306/shop"
                + "?password=query-secret&user=alice&sslMode=require");
        config.setMaxCon(17);
        config.setMinCon(3);
        config.setInitSqls(Arrays.asList("SET NAMES utf8mb4", "SET SESSION sql_mode='STRICT_ALL_TABLES'"));
        JdbcDataSource dataSource = new JdbcDataSource(config, new ToStringMustNotBeCalledDataSource());
        ReplicaSelectorRuntime runtime = ReplicaSelectorRuntime.create(Collections.emptyList(),
                Collections.singletonMap(config.getName(), config), null, null, null);

        try (RowBaseIterator rows = HintHandler.showDataSources(Collections.singletonList(dataSource), runtime)) {
            MycatRowMetaData metadata = rows.getMetaData();
            assertArrayEquals(new String[]{
                    "NAME", "USERNAME", "PASSWORD", "MAX_CON", "MIN_CON", "EXIST_CON", "USE_CON",
                    "MAX_RETRY_COUNT", "MAX_CONNECT_TIMEOUT", "DB_TYPE", "URL", "WEIGHT", "INIT_SQL",
                    "INIT_SQL_GET_CONNECTION", "INSTANCE_TYPE", "IDLE_TIMEOUT", "DRIVER", "TYPE", "IS_MYSQL"
            }, columnNames(metadata));
            List<Map<String, Object>> result = rows.getResultSetMap();
            assertEquals(1, result.size());
            Map<String, Object> row = result.get(0);
            assertEquals("secureDs", row.get("NAME"));
            assertEquals("app-user", row.get("USERNAME"));
            assertEquals("******", row.get("PASSWORD"));
            assertEquals(17, row.get("MAX_CON"));
            assertEquals(3, row.get("MIN_CON"));
            assertEquals("mariadb", row.get("DB_TYPE"));
            assertEquals("jdbc:mariadb://db.example:3306/shop?password=******&user=******&sslMode=require",
                    row.get("URL"));
            assertEquals("SET NAMES utf8mb4;SET SESSION sql_mode='STRICT_ALL_TABLES'", row.get("INIT_SQL"));
            assertEquals(Boolean.TRUE, row.get("INIT_SQL_GET_CONNECTION"));
            assertEquals("READ_WRITE", row.get("INSTANCE_TYPE"));
            assertEquals(ToStringMustNotBeCalledDataSource.class.getName(), row.get("DRIVER"));
            assertEquals("JDBC", row.get("TYPE"));
            assertEquals(Boolean.FALSE, row.get("IS_MYSQL"));
        }
    }

    @Test
    public void showDataSourcesKeepsColumnCountAndNullUrlWhenUrlIsAbsent() {
        DatasourceConfig config = new DatasourceConfig();
        config.setName("noUrlDs");
        config.setUrl(null);
        JdbcDataSource dataSource = new JdbcDataSource(config, new ToStringMustNotBeCalledDataSource());
        ReplicaSelectorRuntime runtime = ReplicaSelectorRuntime.create(Collections.emptyList(),
                Collections.singletonMap(config.getName(), config), null, null, null);

        try (RowBaseIterator rows = HintHandler.showDataSources(Collections.singletonList(dataSource), runtime)) {
            assertEquals(19, rows.getMetaData().getColumnCount());
            List<Map<String, Object>> result = rows.getResultSetMap();
            assertEquals(1, result.size());
            Map<String, Object> row = result.get(0);
            assertNull(row.get("URL"));
            assertEquals(ToStringMustNotBeCalledDataSource.class.getName(), row.get("DRIVER"));
        }
    }

    private static String[] columnNames(MycatRowMetaData metadata) {
        String[] names = new String[metadata.getColumnCount()];
        for (int i = 0; i < names.length; i++) {
            names[i] = metadata.getColumnName(i);
        }
        return names;
    }

    private static final class ToStringMustNotBeCalledDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("not used");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("not used");
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not used");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }

        @Override
        public String toString() {
            throw new AssertionError("showDataSources must not call DataSource.toString()");
        }
    }
}
