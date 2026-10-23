/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.datasource.jdbc;

import com.alibaba.druid.DbType;
import com.alibaba.druid.pool.DruidDataSource;
import io.mycat.MycatDataContext;
import io.mycat.TransactionSession;
import io.mycat.config.DatasourceConfig;
import io.mycat.config.ServerConfig;
import io.mycat.datasource.jdbc.datasource.JdbcDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * @author Junwen Chen
 **/
public class DruidDatasourceProvider implements DatasourceProvider {

    @Override
    public JdbcDataSource createDataSource(DatasourceConfig config) {
        if (System.getProperty("druid.mysql.usePingMethod") == null) {
            System.setProperty("druid.mysql.usePingMethod", "false");
        }
        String username = config.getUser();
        String password = config.getPassword();
        String url = Objects.requireNonNull(config.getUrl());
        String dbType = config.getDbType();
        int maxRetryCount = config.getMaxRetryCount();
        List<String> initSQLs = config.getInitSqls();

        int maxCon = config.getMaxCon();
        int minCon = config.getMinCon();

        DruidDataSource datasource = new DruidDataSource();
        datasource.setPassword(password);
        datasource.setUsername(username);
        // MYCAT CHANGE (I-AU-6): 显式驱动路由 + scheme/dbType 错配响亮报错。
        // 不赌 fat-jar 里 ServiceLoader 描述文件的存活（shade 只排 *.SF/*.DSA/*.RSA，但 DriverManager
        // 自动发现依赖 META-INF/services/java.sql.Driver，多驱动并存时取序不可控）；且 MariaDB 后端配
        // jdbc:mysql:// URL 时 mysql-connector-j 连 ed25519 用户必败于 "Unable to load authentication
        // plugin"，与其沉默到认证层，不如建池前指路。回归锁见 DriverRoutingTest。
        checkUrlSchemeMatchesDbType(dbType, url);
        String driverClassName = resolveDriverClass(dbType, url);
        if (driverClassName != null) {
            datasource.setDriverClassName(driverClassName);
        }
        datasource.setUrl(url);
        datasource.setMaxWait(config.getMaxConnectTimeout());
        datasource.setMaxActive(maxCon);
        datasource.setMinIdle(minCon);
        datasource.setKeepAlive(true);
//        datasource.setTestOnReturn(true);
//        datasource.setTestOnBorrow(true);
        datasource.setValidationQuery("select 'x'");
        datasource.setTestWhileIdle(true);
        datasource.setQueryTimeout(config.getQueryTimeout());

        if(config.isRemoveAbandoned()){
            datasource.setRemoveAbandoned(true);
            datasource.setRemoveAbandonedTimeout(config.getRemoveAbandonedTimeoutSecond());
            datasource.setLogAbandoned(config.isLogAbandoned());
        }

        if (maxRetryCount > 0) {
            datasource.setConnectionErrorRetryAttempts(maxRetryCount);
        }
        if (dbType != null) {
            datasource.setDbType(dbType);
        }
        if (initSQLs != null) {
            datasource.setConnectionInitSqls(initSQLs);
        }
        DataSource finalDataSource;
        if (config.computeType().isJdbc() && !"mysql".equalsIgnoreCase(config.getDbType())) {
            dbType = Optional.ofNullable(dbType).orElse("mysql");
            DbType dbTypeEnum = DbType.of(dbType);
            SQLDialect dialect;
            switch (dbTypeEnum) {
                case jtds:
                case other:
                case db2:
                case oracle:
                case hive:
                case dm:
                case polardb:
                case sqlserver:
                default:
                    dialect = SQLDialect.DEFAULT;
                    break;
                case hsql:
                    dialect = SQLDialect.HSQLDB;
                    break;
                case postgresql:
                    dialect = SQLDialect.POSTGRES;
                    break;
                case oceanbase:
                case mysql:
                    dialect = SQLDialect.MYSQL;
                    break;
                case mariadb:
                    dialect = SQLDialect.MARIADB;
                    break;
                case derby:
                    dialect = SQLDialect.DERBY;
                    break;
                case h2:
                    dialect = SQLDialect.H2;
                    break;
                case clickhouse:
                    return new JdbcDataSource(config, datasource);
            }
            DSLContext using = DSL.using(datasource, dialect);
            finalDataSource = using.parsingDataSource();
        } else {
            finalDataSource = datasource;
        }
        return new JdbcDataSource(config, finalDataSource);
    }

    /**
     * dbType → JDBC 驱动类的显式映射（I-AU-6）。仅钉 mysql/mariadb 两型——其余 dbType 返 null 维持
     * "不干预、由 DriverManager 自动发现"现状（clickhouse 等自有入口，不在此扩面）。
     */
    static String driverClassFor(String dbType) {
        if (dbType == null) {
            return null;
        }
        switch (dbType.toLowerCase()) {
            case "mariadb":
                return "org.mariadb.jdbc.Driver";
            case "mysql":
                return "com.mysql.cj.jdbc.Driver";
            default:
                return null;
        }
    }

    /**
     * URL scheme 优先的驱动解析（I-AU-6）：DatasourceConfig 字段默认 {@code dbType="mysql"}（L29）
     * 遮蔽 URL 真相——未显式声明 dbType 的 mariadb 后端会拿到 {@code getDbType()=="mysql"}，若只按
     * dbType 路由必错配 mariadb URL 进 mysql-connector-j（ed25519 认证沉默失败面）。故 URL 是
     * {@code jdbc:mariadb:} 时一律 mariadb 驱动；其余维持 dbType 原语义（不扩面）。
     */
    static String resolveDriverClass(String dbType, String url) {
        if (url != null && DatasourceConfig.getDbTypeRaw(url) != null
                && "mariadb".equalsIgnoreCase(DatasourceConfig.getDbTypeRaw(url))) {
            return driverClassFor("mariadb");
        }
        return driverClassFor(dbType);
    }

    /**
     * dbType 与 URL scheme 错配校验（I-AU-6）：仅对已显式声明 mariadb 的 dbType 把关——MariaDB 后端
     * 配 {@code jdbc:mysql://} 会被 getDbTypeRaw 判成 mysql 走 mysql-connector-j，连 client_ed25519
     * 用户必沉默失败于认证层，故在建池前抛 IllegalStateException 指路正确形态。
     * 其余组合（mysql dbType、非 mysql/mariadb dbType、null dbType）不改既有行为，放行。
     */
    static void checkUrlSchemeMatchesDbType(String dbType, String url) {
        if ("mariadb".equalsIgnoreCase(dbType) && url != null && url.startsWith("jdbc:mysql:")) {
            throw new IllegalStateException(
                    "MariaDB 后端需 jdbc:mariadb:// URL + mariadb 驱动，实得 dbType=" + dbType + " url=" + url);
        }
    }

    @Override
    public void closeDataSource(JdbcDataSource dataSource) {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    public void init(ServerConfig config) {

    }
//
//    @Override
//    public TransactionSession createSession(MycatDataContext context) {
//        LocalTransactionSession localTransactionSession = new LocalTransactionSession(context);
//        context.setTransactionSession(localTransactionSession);
//        return localTransactionSession;
//    }
}
