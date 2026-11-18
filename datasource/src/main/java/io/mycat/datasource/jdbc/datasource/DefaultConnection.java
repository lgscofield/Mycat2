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
package io.mycat.datasource.jdbc.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.pool.DruidStatementConnection;
import io.mycat.*;
import io.mycat.api.collector.*;
import io.mycat.beans.mycat.JdbcRowBaseIterator;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Junwen Chen
 **/
public class DefaultConnection implements MycatConnection {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultConnection.class);
    final Connection connection;
    private final JdbcDataSource jdbcDataSource;
    protected final ConnectionManager connectionManager;
    private final AtomicBoolean closeFlag = new AtomicBoolean(false);

    @SneakyThrows
    public DefaultConnection(Connection connection, JdbcDataSource dataSource,
                             Boolean autocommit,
                             int transactionIsolation, ConnectionManager connectionManager) {
        this.connection = connection;
        this.jdbcDataSource = dataSource;
        this.connectionManager = connectionManager;
//        if (autocommit != null) {
//            connection.setAutoCommit(autocommit);
//        }
//        connection.setReadOnly(readOnly);
       // connection.setTransactionIsolation(transactionIsolation);
    }


    public long[] executeUpdate(String sql, boolean needGeneratedKeys) {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql,
                    needGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
            if (needGeneratedKeys) {
                ResultSet generatedKeys = statement.getGeneratedKeys();
                if (generatedKeys != null && generatedKeys.next()) {
                    BigDecimal decimal = generatedKeys.getBigDecimal(1);
                    return new long[]{statement.getUpdateCount(), decimal.longValue()};
                }
            }
            return new long[]{statement.getUpdateCount(), 0};
        } catch (Exception e) {
            LOGGER.error("", e);
            throw new MycatException(e);
        }
    }


    public RowBaseIterator executeQuery(String sql) {
        try {
            Statement statement = connection.createStatement();
            statement.setFetchSize(1);
            ResultSet resultSet = statement.executeQuery(sql);
            return new JdbcRowBaseIterator(null, this, statement, resultSet, new RowIteratorCloseCallback() {

                @Override
                public void onClose(long rowCount) {

                }
            }, sql);
        } catch (Exception e) {
            throw new MycatException(e);
        }
    }


    public void onExceptionClose() {
        close();
    }


    public void close() {
        try {
            if (closeFlag.compareAndSet(false, true)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("close {}", connection);
                }
                connectionManager.closeConnection(DefaultConnection.this);
            }
        } catch (Exception e) {
            LOGGER.error("", e);
        }
    }

    // 物理弃用(force-close 专用,区别于 close 的 recycle):剥开 Druid 包装(DruidPooledConnection→DruidStatementConnection)直达物理连接 abort——包装层 abort 先 statement.close() 抢 mysql session 锁,会被冻结 query worker 持锁阻塞致 socket 永不杀;物理 ConnectionImpl.abort→forceClose 先无锁杀 socket 解困 worker 后加锁清理。direct executor 保 forceClose 同步先于 discard 的 isClosed 短路;discard 归位 druid activeCount(abort 本身不减)防多轮冻结耗尽池。discard 置于 finally:forceClose 已先无锁杀 socket,其后清理阶段若抛异常仍须归位 activeCount,否则反复超时泄漏耗尽池。
    public void abort() {
        try {
            if (closeFlag.compareAndSet(false, true)) {
                jdbcDataSource.counter.decrementAndGet();
                Connection physical = connection;
                if (physical instanceof DruidPooledConnection) {
                    physical = ((DruidPooledConnection) physical).getConnection();
                }
                if (physical instanceof DruidStatementConnection) {
                    physical = ((DruidStatementConnection) physical).getConnection();
                }
                try {
                    physical.abort(Runnable::run);
                } finally {
                    DataSource ds = jdbcDataSource.getDataSource();
                    if (ds instanceof DruidDataSource) {
                        ((DruidDataSource) ds).discardConnection(physical);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("", e);
        }
    }

    public void setTransactionIsolation(int transactionIsolation) {
        try {
            if (connection.getTransactionIsolation() != transactionIsolation) {
                this.connection.setTransactionIsolation(transactionIsolation);
            }
        } catch (SQLException e) {
            throw new MycatException(e);
        }
    }

    public void setReadyOnly(boolean readyOnly) {
        //            if (connection.isReadOnly() != readyOnly) {
//                this.connection.setReadOnly(readyOnly);
//            }
    }

    public JdbcDataSource getDataSource() {
        return jdbcDataSource;
    }

    public boolean isClosed() {
        return closeFlag.get();
    }

    public Connection getRawConnection() {
        return connection;
    }

    public Observable<MysqlPayloadObject> executeQuery(MycatRowMetaData mycatRowMetaData, String sql) {
        return (Observable.create(emitter -> {
            try (Statement statement = connection.createStatement();
                 RowBaseIterator rowBaseIterator = new JdbcRowBaseIterator(mycatRowMetaData, DefaultConnection.this, statement, statement.executeQuery(sql), null, sql);) {
                MycatRowMetaData metaData = rowBaseIterator.getMetaData();
                emitter.onNext(new MySQLColumnDef(metaData));
                while (rowBaseIterator.next()) {
                    emitter.onNext(new MysqlObjectArrayRow(rowBaseIterator.getObjects()));
                }
                emitter.onComplete();
            } catch (Throwable throwable) {
                emitter.onError(throwable);
            }
        }));
    }

    @Override
    @SneakyThrows
    public <T> T unwrap(Class<T> iface) {
        if (Connection.class == iface) {
            return (T) connection;
        }
        return connection.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return unwrap(iface) != null;
    }

    public void deleteTable(String schemaName, String tableName) {
        String dropTemplate = "drop table `%s`.`%s`";
        executeUpdate(String.format(dropTemplate, schemaName, tableName), false);
    }

    public void createTable(String rewriteCreateTableSql) {
        executeUpdate(rewriteCreateTableSql, false);
    }

    public void createDatabase(String schema) {
        executeUpdate("CREATE DATABASE IF NOT EXISTS `" + schema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci ", false);
    }
}