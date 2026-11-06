/**
 * Copyright [2021] [chen junwen]
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.mycat.vertx.xa.impl;

import com.alibaba.druid.util.JdbcUtils;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;

/**
 * I-DEBT-84 事务腿 Phase 2 阶段 0：XA 提交意向日志（{@code mycat.xa_log}）的表结构与写入收口。
 *
 * <p>历史上该表只有单列 {@code (xid bigint PK)}，DDL 在启动扫描里就地拼装、且生产从不 INSERT（提交意向只存
 * 内存 {@link MemoryRepositoryImpl}），使 {@link XaLogImpl#readXARecoveryLog} 的"据 xa_log 判已决提交→COMMIT，
 * 否则→ROLLBACK"交叉判定在生产恒走 ROLLBACK——past-prepare 的已决提交会被误回滚（丢已提交数据）。
 *
 * <p>本类把建表/加列/写入/删除集中到一处，供 {@link XaLogImpl} 扫描与 {@link LocalXaMemoryRepositoryImpl}
 * 持久化共用，保证 DDL 单一真源。新增 {@code create_time}（计时维度，Phase 2 reaper 判超期用）与
 * {@code mycat_id}（多实例归属）两列。MySQL 无 {@code add column if not exists}，故对旧库用幂等
 * try-alter（重复列错误仅 WARN 跳过），沿用原 {@code create ... if not exists} 的宽容风格。
 */
final class XaLogSchema {
    private final static Logger LOGGER = LoggerFactory.getLogger(XaLogSchema.class);

    static final String DATABASE = "mycat";
    static final String TABLE = "xa_log";

    private XaLogSchema() {
    }

    static String createDatabaseSql() {
        return "create database if not exists `" + DATABASE + "`";
    }

    /** 新库直接建出含计时/归属列的完整结构。 */
    static String createTableSql() {
        return "create table if not exists `" + DATABASE + "`.`" + TABLE + "`"
                + "(`xid` bigint PRIMARY KEY NOT NULL,"
                + "`create_time` bigint,"
                + "`mycat_id` int"
                + ") ENGINE=InnoDB";
    }

    /** 旧库升级用：逐列 try-alter，重复列错误吞掉（MySQL 无 add column if not exists）。 */
    static String[] alterAddColumnSqls() {
        return new String[]{
                "alter table `" + DATABASE + "`.`" + TABLE + "` add column `create_time` bigint",
                "alter table `" + DATABASE + "`.`" + TABLE + "` add column `mycat_id` int",
        };
    }

    /**
     * 幂等地把某后端 DS 上的 {@code mycat.xa_log} 收口到目标结构：建库建表（含新列）+ 对既有旧表补列。
     * 单条 alter 失败（列已存在）只记 WARN，不阻断——不因此拒绝启动或提交流程。
     */
    static void ensureSchema(Connection connection) throws java.sql.SQLException {
        JdbcUtils.executeUpdate(connection, createDatabaseSql(), Collections.emptyList());
        JdbcUtils.executeUpdate(connection, createTableSql(), Collections.emptyList());
        for (String alter : alterAddColumnSqls()) {
            try {
                JdbcUtils.executeUpdate(connection, alter, Collections.emptyList());
            } catch (Throwable t) {
                // 多数为 "Duplicate column name"（新库/已补列），属预期，静默跳过。
                LOGGER.debug("xa_log add column skip (likely already exists): " + t.getMessage());
            }
        }
    }

    /**
     * 写提交意向（原子、持久）：xid 为 PK，重复写走 upsert 仅刷新计时/归属，不报错。
     * 失败抛 SQLException，由上层收口为"拒绝提交"（宁可拒绝，不可假装持久）。
     */
    static void insertCommitIntent(Connection connection, String xid, long createTime, long mycatId) throws java.sql.SQLException {
        String sql = "insert into `" + DATABASE + "`.`" + TABLE + "`(xid, create_time, mycat_id) values(?,?,?)"
                + " on duplicate key update create_time=values(create_time), mycat_id=values(mycat_id)";
        JdbcUtils.executeUpdate(connection, sql, Arrays.asList(xid, createTime, mycatId));
    }

    /** 撤销提交意向（决定回滚 / 完成清理）：条件删，双方并发删安全，删不存在亦为 no-op。 */
    static void deleteCommitIntent(Connection connection, String xid) throws java.sql.SQLException {
        String sql = "delete from `" + DATABASE + "`.`" + TABLE + "` where xid = ?";
        JdbcUtils.executeUpdate(connection, sql, Collections.singletonList(xid));
    }
}
