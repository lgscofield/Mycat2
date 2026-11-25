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
package io.mycat.plug.sequence;

import io.mycat.MetaClusterCurrent;
import io.mycat.config.SequenceConfig;
import io.mycat.datasource.jdbc.datasource.DefaultConnection;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.datasource.jdbc.datasource.JdbcDataSource;
import io.mycat.replica.ReplicaSelectorRuntime;
import io.mycat.util.SplitUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.concurrent.locks.ReentrantLock;

public class SequenceMySQLGenerator implements SequenceHandler {
    protected static final Logger LOGGER = LoggerFactory
            .getLogger(SequenceMySQLGenerator.class);
    private String sql;
    private String queryTargetName;
    private BiFunction<String, String, String> function;
    private long count = 0;
    private long limit = -1;
    // I-PR-1：get() 原为 synchronized，临界区内 function.apply 走阻塞 JDBC 取号段
    // ⇒ VT 在其中 park 会钉住载体线程（离线探针实测 holdsLock=true）。换每实例单把 ReentrantLock。
    // count/limit 只在本锁内读写，无锁外读点，故不需要 volatile（区别于 DefaultPrototypeGenerator 的 value）。
    private final ReentrantLock lock = new ReentrantLock();

    public void init(String sql, String targetName) {
        init(sql, targetName, (s, s2) -> {
            JdbcConnectionManager jdbcConnectionManager = MetaClusterCurrent.wrapper(JdbcConnectionManager.class);
            try (DefaultConnection mycatConnection = jdbcConnectionManager.getConnection(targetName)) {
                Connection rawConnection = mycatConnection.getRawConnection();
                try (Statement statement = rawConnection.createStatement()) {
                    try (ResultSet resultSet = statement.executeQuery(s2)) {
                        while (resultSet.next()) {
                            return resultSet.getString(1);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("can not get queryTargetName:" + s + ",sql:" + s2 + " e");
            }
            return null;
        });
    }

    public void init(String sql, String targetName, BiFunction<String, String, String> function) {
        this.sql = Objects.requireNonNull(sql);
        this.queryTargetName = Objects.requireNonNull(targetName);
        this.function = Objects.requireNonNull(function);
    }

    @Override
    public Number get() {
        lock.lock();
        try {
            if (count >= limit) {
                try {
                    String s = function.apply(queryTargetName, sql);
                    String[] split = SplitUtil.split(s, ',');
                    this.count = Long.parseLong(split[0]);
                    long inc = Long.parseLong(split[1]);
                    this.limit = this.count + inc;
                } catch (Throwable e) {
                    LOGGER.error("", e);
                    throw new RuntimeException(e);
                }
            }
            return (count++);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void init(SequenceConfig args, long workerId) {
        String[] split = args.getName().split("_");
        String db = Optional.ofNullable(args.getSchemaName()).orElse(split[0]);
        String targetName = Optional.ofNullable(args.getTargetName()).orElse("prototype");
        init(String.format("select %s.mycat_seq_nextval('%s')", db, args.getName()), targetName);
    }

    @Override
    public void setStart(Number value) {

    }

}